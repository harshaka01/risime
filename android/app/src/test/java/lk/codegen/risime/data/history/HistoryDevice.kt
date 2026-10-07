package lk.codegen.risime.data.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeReactionDao
import lk.codegen.risime.data.FakeRealtime
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.HistoryProvideEntity
import lk.codegen.risime.data.deletes.FakeDeleteDao
import lk.codegen.risime.data.mls.FakeHistoryCrypto
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.GroupRef
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.BlobUploadReply
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.ProtocolJson
import java.io.File
import java.nio.file.Files

/** A shared fake blob store (the server's `history` blobs). */
class FakeBlobStore {
    val blobs = linkedMapOf<String, ByteArray>()
    var n = 0
}

/**
 * One app instance for history tests: the real ChatEngine + MlsPipeline + HistoryManager over
 * fakes (FakeMlsEngine, FakeHistoryCrypto, in-memory DAOs, a scripted realtime and blob store).
 */
class HistoryDevice(
    val user: String,
    val device: String,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    store: FakeBlobStore,
    val clock: () -> Long,
    conversations: List<String>,
    names: Map<String, String> = emptyMap(),
) {
    val messages = FakeMessageDao()
    val reactions = FakeReactionDao()
    val deletes = FakeDeleteDao(messages, reactions)
    val dao = FakeHistoryDao(messages, reactions)
    val mls = FakeMlsEngine(user, device).also { e -> conversations.forEach { e.groups[it] = GroupRef(it, 1, 0) } }
    val realtime = FakeRealtime()
    var membersAsk = true
    var ownAllowed = true
    val exports = mutableListOf<String>()
    val prompts = mutableListOf<List<HistoryProvideEntity>>()
    var sharedQuietly = 0
    private val tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() }
    private val dir: File = Files.createTempDirectory("hist-$device").toFile()
    lateinit var chat: ChatEngine
    val manager: HistoryManager = HistoryManager(
        dao, messages, deletes, null, null, reactions, null, tx, { mls }, { FakeHistoryCrypto() }, { realtime },
        object : HistoryBlobApi {
            override suspend fun upload(conversationId: String, requestId: String, clientBlobId: String, file: File): ApiResult<BlobUploadReply> {
                val id = "blob-${++store.n}"
                store.blobs[id] = file.readBytes()
                return ApiResult.Ok(BlobUploadReply(id, file.length(), "x"))
            }

            override suspend fun download(blobId: String, into: File): ApiResult<Long> {
                val b = store.blobs[blobId] ?: return ApiResult.Error(404, "not_found", "")
                into.writeBytes(b)
                return ApiResult.Ok(b.size.toLong())
            }
        },
        { chat }, {}, { user }, { device }, { membersAsk }, { ownAllowed }, dir, scope,
        object : HistoryPorts {
            override fun startExport(requestId: String) { exports += requestId }
            override fun promptsChanged(asks: List<HistoryProvideEntity>) { prompts += asks }
            override fun sharedWithOwnDevice() { sharedQuietly++ }
            override suspend fun name(userId: String) = names[userId] ?: "Someone"
        },
        clock = clock, io = io, log = { println("  [$device] $it") },
    )

    init {
        chat = ChatEngine(
            messages = messages, sync = FakeSyncDao(), tx = tx, scope = scope, realtime = { realtime }, meId = { user }, clock = clock,
            mls = MlsPipeline({ mls }, FakeMlsPendingDao()), mlsEngine = { mls }, reactionsDao = reactions, groupsEnabled = { true },
            deletes = deletes, historyDao = dao, history = manager,
        )
    }

    private var eventSeq = 0

    suspend fun deliver(kind: String, data: JsonObject) {
        chat.onEvents(listOf(Event("ev-$device-${++eventSeq}", kind, data)))
    }

    /** The last `history:<event>` push of this device. */
    fun pushed(event: String): JsonObject = realtime.historyPushes.last { it.first == event }.second

    companion object {
        fun JsonObject.str(k: String) = (this[k] as JsonPrimitive).content

        /** The server's `history_request` event for a named device, from the requester's push. */
        fun requestEvent(push: JsonObject, from: String, fromDevice: String, to: String, consent: String): JsonObject = buildJsonObject {
            push.forEach { (k, v) -> if (k != "sources") put(k, v) }
            put("from", from)
            put("from_device", fromDevice)
            put("intervals", kotlinx.serialization.json.JsonArray(listOf(push["range"]!!)))
            put("consent", consent)
            put("expires_at", "2026-10-09T00:00:00.000Z")
            put("to_devices", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(to))))
            put("server_ts", "2026-10-07T00:00:00.000Z")
        }

        /** The server's `history_share` event for the requester, from the provider's `history:deliver`. */
        fun shareEvent(deliver: JsonObject, conv: String, from: String, fromDevice: String, to: String): JsonObject = buildJsonObject {
            deliver.forEach { (k, v) -> put(k, v) }
            put("conversation_id", conv)
            put("from", from)
            put("from_device", fromDevice)
            put("to_devices", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(to))))
            put("server_ts", "2026-10-07T00:01:00.000Z")
        }

        fun statusEvent(requestId: String, conv: String, state: String, to: String, provider: Pair<String, String>? = null): JsonObject =
            ProtocolJson.parseToJsonElement(
                """{"request_id":"$requestId","conversation_id":"$conv","state":"$state","provider":${provider?.let { "{\"user_id\":\"${it.first}\",\"device_id\":\"${it.second}\"}" } ?: "null"},"to_devices":["$to"],"server_ts":"2026-10-07T00:02:00.000Z"}""",
            ) as JsonObject
    }
}
