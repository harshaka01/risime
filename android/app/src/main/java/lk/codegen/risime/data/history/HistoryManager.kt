package lk.codegen.risime.data.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.data.HistoryMarkers
import lk.codegen.risime.data.ReactionStore
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.DeleteDao
import lk.codegen.risime.data.db.HistoryDao
import lk.codegen.risime.data.db.HistoryPartEntity
import lk.codegen.risime.data.db.HistoryProvideEntity
import lk.codegen.risime.data.db.HistoryRequestEntity
import lk.codegen.risime.data.db.MediaDao
import lk.codegen.risime.data.db.MessageDao
import lk.codegen.risime.data.db.ReactionDao
import lk.codegen.risime.data.deletes.DeleteApplier
import lk.codegen.risime.data.media.ImageHooks
import lk.codegen.risime.data.media.MediaSealer
import lk.codegen.risime.data.mls.HistoryCrypto
import lk.codegen.risime.data.mls.HistoryCtx
import lk.codegen.risime.data.mls.HistoryException
import lk.codegen.risime.data.mls.MlsEngine
import lk.codegen.risime.data.mls.MlsResult
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.BlobUploadReply
import lk.codegen.risime.net.HistoryAck
import lk.codegen.risime.net.HistoryDeliver
import lk.codegen.risime.net.HistoryOwnDevice
import lk.codegen.risime.net.HistoryRange
import lk.codegen.risime.net.HistoryRefresh
import lk.codegen.risime.net.HistoryRequestClosedEvent
import lk.codegen.risime.net.HistoryRequestEvent
import lk.codegen.risime.net.HistoryRequestPush
import lk.codegen.risime.net.HistoryRequestRef
import lk.codegen.risime.net.HistoryRequestReply
import lk.codegen.risime.net.HistoryRespond
import lk.codegen.risime.net.HistoryState
import lk.codegen.risime.net.HistoryStatusEvent
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmPeer
import lk.codegen.risime.net.isGroupConversation
import lk.codegen.risime.realtime.PushResult
import lk.codegen.risime.realtime.RealtimeClient
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** What the chat engine hands to the history manager, inside each event's transaction (and after it). */
interface HistoryHooks {
    suspend fun onRequestInTx(r: MlsResult.HistoryRequest)

    suspend fun onRequestStaleInTx(e: HistoryRequestEvent)

    suspend fun onStatusInTx(e: HistoryStatusEvent)

    suspend fun onClosedInTx(e: HistoryRequestClosedEvent)

    suspend fun onShareInTx(r: MlsResult.HistoryShare)

    /** After the event's transaction committed (never inside): act on what changed. */
    fun afterCommit()

    /** §17.13 Clear chat / Delete chat: cancel this device's open request for the chat. */
    suspend fun onChatCleared(conversationId: String)
}

/** The chat engine's serial encrypt-and-push lane per conversation (§15.7, §16.4, §17.16). */
interface SendLanes {
    suspend fun <T> withLane(conversationId: String, block: suspend () -> T): T
}

/** §17.9 `history` blobs. */
interface HistoryBlobApi {
    suspend fun upload(conversationId: String, requestId: String, clientBlobId: String, file: File): ApiResult<BlobUploadReply>

    /** Downloads the whole blob into [into]; returns its length. */
    suspend fun download(blobId: String, into: File): ApiResult<Long>
}

/** What the manager needs from the app (notifications, the foreground worker, names). */
interface HistoryPorts {
    /** Start (or resume) the foreground `dataSync` export worker for [requestId]. */
    fun startExport(requestId: String)

    fun cancelExport(requestId: String) = Unit

    /** The set of requests waiting for the user's answer changed (post or cancel the "History requests" notification). */
    fun promptsChanged(asks: List<HistoryProvideEntity>) = Unit

    /** §17.8: "Shared chat history with your new phone" (quiet). */
    fun sharedWithOwnDevice() = Unit

    /** Image rows were imported: schedule thumbnails/auto-download as for live images. */
    fun imagesImported() = Unit

    suspend fun name(userId: String): String
}

/** Per-part export progress (android R7): resumable, never re-sealed once uploaded. */
@Serializable
data class PartProgress(
    val part: Int,
    /** The part's oldest and newest unit (server ms, message id): the boundaries survive a restart. */
    @SerialName("first_ts") val firstTs: Long,
    @SerialName("first_id") val firstId: String,
    @SerialName("last_ts") val lastTs: Long,
    @SerialName("last_id") val lastId: String,
    @SerialName("client_blob_id") val clientBlobId: String? = null,
    @SerialName("blob_id") val blobId: String? = null,
    val size: Long = 0,
    val sha256: String? = null,
    @SerialName("plain_size") val plainSize: Long = 0,
    @SerialName("hpke_enc") val hpkeEnc: String? = null,
    @SerialName("sealed_key") val sealedKey: String? = null,
    val count: Int = 0,
    val delivered: Boolean = false,
)

/** The marker line's view of a conversation's history state (§17.12). */
data class HistoryMarkerState(
    val gapCount: Int,
    val request: HistoryRequestEntity?,
    /** Providers the remaining gap rows were already asked from (android R6). */
    val askedFrom: Set<String>,
) {
    val open: Boolean get() = request?.closed == false
    val canRequest: Boolean get() = gapCount > 0 && !open
    val ownDevices: List<HistoryOwnDevice>
        get() = request?.ownDevicesJson?.let { j -> runCatching { ProtocolJson.decodeFromString(ListSerializer(HistoryOwnDevice.serializer()), j) }.getOrNull() }.orEmpty()
}

/** The outcome of "Request history". */
sealed interface RequestOutcome {
    data class Sent(val requestId: String) : RequestOutcome

    /** Not connected: the request is kept and sent when live. */
    data class Queued(val requestId: String) : RequestOutcome

    data class Refused(val reason: String) : RequestOutcome

    data object NothingToRequest : RequestOutcome

    data object AlreadyOpen : RequestOutcome

    data object NotAvailable : RequestOutcome
}

/** What one run of the export worker achieved. */
enum class ExportOutcome { DONE, RETRY, GONE }

/**
 * §17 history sharing on this device, both sides:
 * - **requester:** "Request history" (the request row written with the core's keygen, then
 *   `history:request` through the send lane), status and refresh, the parts stored by the
 *   pipeline, then fetched, opened (sha256 first), imported (R4b) and acked;
 * - **provider:** named requests decrypted in order, own vs member from the MLS sender, option A
 *   (one approval per new phone, then automatic while unlocked) or always ask, the export (R2–R4)
 *   in a foreground worker, sealed per part, uploaded as `history` blobs and delivered.
 */
class HistoryManager(
    private val dao: HistoryDao,
    private val messages: MessageDao,
    private val deletes: DeleteDao?,
    private val media: MediaDao?,
    private val sealer: MediaSealer?,
    reactionsDao: ReactionDao?,
    private val images: ImageHooks?,
    private val tx: TransactionRunner,
    private val engine: () -> MlsEngine?,
    private val crypto: () -> HistoryCrypto?,
    private val realtime: () -> RealtimeClient,
    private val blobs: HistoryBlobApi,
    private val lanes: () -> SendLanes,
    private val catchUp: suspend (String) -> Unit,
    private val me: suspend () -> String?,
    private val deviceId: suspend () -> String,
    private val membersAsk: suspend () -> Boolean,
    private val ownAllowed: suspend () -> Boolean,
    private val workDir: File,
    private val scope: CoroutineScope,
    private val ports: HistoryPorts,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = {},
    private val staleEpochRetries: Int = 3,
    /** Every entry point that reaches Room or the MLS core runs here (never the main thread). */
    private val io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : HistoryHooks {
    val approvals = HistoryApprovals(engine)
    private val applier = deletes?.let { DeleteApplier(it, messages, images, clock, log) }
    private val importer = HistoryImporter(messages, dao, applier, reactionsDao?.let { ReactionStore(it, clock) }, images, clock, log)
    private val runLock = Mutex()
    private val exportLock = Mutex()
    private val kickQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    /** Requests whose `history_status` said `refresh` (re-encrypted and pushed after the commit). */
    private val refreshOwed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()


    /** Whether this app can both provide and receive (§17.1): the core's functions are present. */
    fun supported(): Boolean = engine()?.historySupported == true && crypto() != null

    // ---- UI ----

    /** §17.12 the marker line's state for one conversation. */
    fun markerState(conversationId: String): Flow<HistoryMarkerState> =
        combine(dao.observeGaps(conversationId), dao.observeLatestRequest(conversationId)) { gaps, req ->
            HistoryMarkerState(gaps.size, req, gaps.mapNotNull { it.askedFrom }.toSet())
        }

    /** §17.8 requests waiting for this user's answer (own approvals and member prompts). */
    val prompts: Flow<List<HistoryProvideEntity>> = dao.observeOpenProvides().map { l -> l.filter { it.state == HistoryProvideEntity.ASK } }

    // ---- hooks (inside the event's transaction) ----

    override suspend fun onRequestInTx(r: MlsResult.HistoryRequest) {
        val e = r.event
        val prev = dao.provide(e.requestId)
        if (prev != null && prev.state !in setOf(HistoryProvideEntity.ASK, HistoryProvideEntity.UNABLE, HistoryProvideEntity.CLOSED)) return
        // §17.7 (crypto R4): own vs member from the MLS sender; a `consent` that disagrees means member (always ask).
        val own = r.sender.own && e.consent != HistoryRequestEvent.CONSENT_MEMBER
        if (r.sender.own != (e.consent == HistoryRequestEvent.CONSENT_OWN)) log("history_request ${e.requestId}: consent hint disagrees with the MLS sender")
        val now = clock()
        dao.upsertProvide(
            HistoryProvideEntity(
                requestId = e.requestId.lowercase(), conversationId = e.conversationId, requesterUser = r.sender.device.userId.lowercase(),
                requesterDevice = r.sender.device.deviceId.lowercase(), requesterSigKey = r.sender.signatureKey, own = own, rpk = r.env.rpk,
                rangeFrom = e.range.from, rangeTo = e.range.to,
                intervalsJson = ProtocolJson.encodeToString(ListSerializer(HistoryRange.serializer()), e.intervals),
                gapCount = r.env.gapCount, expiresAt = e.expiresAt, state = HistoryProvideEntity.ASK, createdAt = prev?.createdAt ?: now, updatedAt = now,
            ),
        )
    }

    override suspend fun onRequestStaleInTx(e: HistoryRequestEvent) {
        val prev = dao.provide(e.requestId)
        if (prev != null && prev.state != HistoryProvideEntity.ASK) return
        val now = clock()
        dao.upsertProvide(
            HistoryProvideEntity(
                requestId = e.requestId.lowercase(), conversationId = e.conversationId, requesterUser = e.from.lowercase(), requesterDevice = e.fromDevice.lowercase(),
                requesterSigKey = ByteArray(0), own = false, rpk = ByteArray(0), rangeFrom = e.range.from, rangeTo = e.range.to, intervalsJson = "[]",
                gapCount = e.gapCount, expiresAt = e.expiresAt, state = HistoryProvideEntity.UNABLE, progressJson = HistoryRespond.REASON_STALE,
                createdAt = now, updatedAt = now,
            ),
        )
    }

    override suspend fun onClosedInTx(e: HistoryRequestClosedEvent) {
        val p = dao.provide(e.requestId) ?: return
        if (p.state == HistoryProvideEntity.DELIVERED || p.state == HistoryProvideEntity.DECLINED || p.state == HistoryProvideEntity.CLOSED) return
        dao.upsertProvide(p.copy(state = HistoryProvideEntity.CLOSED, updatedAt = clock()))
        ports.cancelExport(p.requestId)
    }

    override suspend fun onStatusInTx(e: HistoryStatusEvent) {
        val dev = deviceId()
        if (e.toDevices.isNotEmpty() && e.toDevices.none { it.equals(dev, true) }) return
        val r = dao.request(e.requestId) ?: return
        if (r.closed) return
        var next = r.copy(state = e.state, providerUser = e.provider?.userId?.lowercase() ?: r.providerUser, providerDevice = e.provider?.deviceId?.lowercase() ?: r.providerDevice)
        if (e.state == HistoryState.REFRESH) refreshOwed += r.requestId
        if (e.state in HistoryState.TERMINAL) {
            // §17.3: every terminal state forgets rsk; partial parts stay (verified rows).
            runCatching { engine()?.historyForget(r.requestId) }
            next = next.copy(closed = true)
            if (e.state == HistoryState.DONE) residual(r, next.providerUser)
        }
        dao.upsertRequest(next)
    }

    /** android R6: after a `done` share the remaining gap rows of the range were asked from that provider. */
    private suspend fun residual(r: HistoryRequestEntity, provider: String?) {
        provider ?: return
        dao.markAsked(r.conversationId, r.rangeFrom, r.rangeTo, provider)
        if (dao.gapCount(r.conversationId) > 0) importer.refreshGapMarker(r.conversationId, residual = true)
    }

    override suspend fun onShareInTx(r: MlsResult.HistoryShare) {
        val s = r.event
        val req = dao.request(s.requestId) ?: return log("history_share for an unknown request: dropped")
        if (req.closed || !req.conversationId.equals(s.conversationId, true)) return log("history_share for a closed request: dropped")
        val me = me() ?: return
        // §17.6 (crypto S3): own device, or (sources any) a current member (the MLS group proves membership).
        val ownSender = r.sender.userId.equals(me, true)
        val memberOk = req.sources == HistoryRequestPush.SOURCES_ANY &&
            (isGroupConversation(req.conversationId) || dmPeer(req.conversationId, me)?.equals(r.sender.userId, true) == true)
        if (!ownSender && !memberOk) return log("history_share from a sender this request didn't ask: dropped")
        if (req.parts != 0 && req.parts != r.env.parts) return log("history_share with a different part count: dropped")
        if (req.providerUser != null && req.partsDone > 0 && !req.providerUser.equals(r.sender.userId, true)) return log("history_share from a second provider: dropped")
        val env = r.env
        dao.insertPart(
            HistoryPartEntity(
                requestId = req.requestId, part = env.part, parts = env.parts, providerUser = r.sender.userId.lowercase(), providerDevice = r.sender.deviceId.lowercase(),
                blobId = env.blob.blobId, size = env.blob.size, sha256 = env.blob.sha256, plainSize = env.plainSize, hpkeEnc = env.hpkeEnc,
                sealedKey = env.sealedKey, count = env.count, state = HistoryPartEntity.PENDING,
            ),
        )
        dao.upsertRequest(req.copy(parts = env.parts, state = if (req.state in HistoryState.TERMINAL) req.state else HistoryState.RECEIVING))
    }

    override fun afterCommit() {
        // Coalesced: one run queued behind the current one at most.
        if (!kickQueued.compareAndSet(false, true)) return
        scope.launch {
            kickQueued.set(false)
            runCatching { process() }.onFailure { log("history: ${it.message}") }
        }
    }

    override suspend fun onChatCleared(conversationId: String) {
        val r = dao.openRequest(conversationId) ?: return
        cancel(r)
    }

    // ---- requester ----

    /** "Request history" from the marker: [sources] = own | any. */
    suspend fun request(conversationId: String, sources: String): RequestOutcome = kotlinx.coroutines.withContext(io) { requestNow(conversationId, sources) }

    /**
     * Decision 055: after a new install or a lost database, the missing history comes back by itself:
     * every conversation with a history gap that has never been asked for gets one request (own
     * devices or members, §17). Conversations asked before keep their marker for a manual retry.
     * @return how many requests went out (or were queued).
     */
    suspend fun autoRequestAll(): Int = kotlinx.coroutines.withContext(io) {
        if (!supported()) return@withContext 0
        var n = 0
        for (conv in dao.allGaps().map { it.conversationId }.distinct()) {
            if (dao.latestRequest(conv) != null) continue
            val r = runCatching { requestNow(conv, lk.codegen.risime.net.HistoryRequestPush.SOURCES_ANY) }.getOrNull()
            if (r is RequestOutcome.Sent || r is RequestOutcome.Queued) n++
        }
        if (n > 0) log("history: asked for the missing history of $n conversation(s)")
        n
    }

    private suspend fun requestNow(conversationId: String, sources: String): RequestOutcome {
        val eng = engine() ?: return RequestOutcome.NotAvailable
        if (!supported()) return RequestOutcome.NotAvailable
        if (eng.group(conversationId) == null) return RequestOutcome.NotAvailable
        if (dao.openRequest(conversationId) != null) return RequestOutcome.AlreadyOpen
        val gaps = dao.gaps(conversationId)
        val range = HistoryGaps.range(gaps) ?: return RequestOutcome.NothingToRequest
        val id = newId()
        val now = clock()
        tx.run {
            // §17.16: the request row in the same transaction as the core's keygen, before the push.
            eng.historyKeygen(id)
            dao.upsertRequest(
                HistoryRequestEntity(
                    requestId = id, conversationId = conversationId, sources = sources, state = HistoryState.NEW, createdAt = now,
                    expiresAt = now + EXPIRY_MS, rangeFrom = range.first, rangeTo = range.second, gapCount = gaps.size,
                ),
            )
        }
        return send(id)
    }

    private suspend fun envelope(r: HistoryRequestEntity): HistoryRequestEnvelope? {
        val rpk = engine()?.historyPublicKey(r.requestId) ?: return null
        return HistoryRequestEnvelope(r.requestId, HistoryRange(r.rangeFrom, r.rangeTo), r.gapCount, rpk)
    }

    /** Encrypts [plaintext] with the 'H' AAD in the conversation's lane and pushes [push] (stale_epoch → catch up, re-encrypt). */
    private suspend fun encryptAndPush(
        conv: String,
        requestId: String,
        plaintext: ByteArray,
        push: suspend (ciphertext: String, generation: Long, epoch: Long) -> PushResult<JsonObject>,
    ): PushResult<JsonObject> {
        var attempt = 0
        while (true) {
            val eng = engine() ?: return PushResult.Rejected(AuthErrors.NOT_E2EE)
            val r = lanes().withLane(conv) {
                val g = eng.group(conv) ?: return@withLane PushResult.Rejected(AuthErrors.NOT_E2EE)
                val ct = tx.run { eng.encryptWithAad(conv, plaintext, HistoryAad.encode(requestId)) }
                push(b64.encodeToString(ct), g.generation, g.epoch)
            }
            if ((r as? PushResult.Rejected)?.reason != AuthErrors.STALE_EPOCH || attempt++ >= staleEpochRetries) return r
            catchUp(conv)
        }
    }

    private suspend fun send(requestId: String): RequestOutcome {
        val r = dao.request(requestId) ?: return RequestOutcome.NotAvailable
        val env = envelope(r) ?: return fail(r, "no key").let { RequestOutcome.Refused("no_key") }
        val res = encryptAndPush(r.conversationId, r.requestId, env.encode()) { ct, gen, epoch ->
            realtime().history(
                "history:request",
                ProtocolJson.encodeToJsonElement(HistoryRequestPush.serializer(), HistoryRequestPush(r.requestId, r.conversationId, r.sources, HistoryRange(r.rangeFrom, r.rangeTo), r.gapCount, ct, gen, epoch)),
            )
        }
        return when (res) {
            is PushResult.Ok -> {
                val reply = runCatching { ProtocolJson.decodeFromJsonElement(HistoryRequestReply.serializer(), res.value) }.getOrNull()
                val cur = dao.request(requestId) ?: r
                if (cur.state == HistoryState.NEW) {
                    dao.upsertRequest(
                        cur.copy(
                            state = reply?.state ?: HistoryState.SEARCHING,
                            ownDevicesJson = reply?.ownDevices?.let { ProtocolJson.encodeToString(ListSerializer(HistoryOwnDevice.serializer()), it) },
                        ),
                    )
                }
                RequestOutcome.Sent(requestId)
            }
            is PushResult.Rejected -> {
                val openId = res.body?.let { b -> (b["request_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
                if (res.reason == REQUEST_OPEN && openId != null && !openId.equals(requestId, true)) {
                    // The server holds an open request of this device that this database doesn't know (lost state): cancel it, retry once.
                    if (dao.request(openId) == null) {
                        realtime().history("history:cancel", ProtocolJson.encodeToJsonElement(HistoryRequestRef.serializer(), HistoryRequestRef(openId)))
                        val again = encryptAndPush(r.conversationId, r.requestId, env.encode()) { ct, gen, epoch ->
                            realtime().history(
                                "history:request",
                                ProtocolJson.encodeToJsonElement(HistoryRequestPush.serializer(), HistoryRequestPush(r.requestId, r.conversationId, r.sources, HistoryRange(r.rangeFrom, r.rangeTo), r.gapCount, ct, gen, epoch)),
                            )
                        }
                        if (again is PushResult.Ok) {
                            dao.upsertRequest(r.copy(state = HistoryState.SEARCHING))
                            return RequestOutcome.Sent(requestId)
                        }
                    }
                }
                fail(r, res.reason)
                RequestOutcome.Refused(res.reason)
            }
            PushResult.Unavailable -> RequestOutcome.Queued(requestId)
        }
    }

    private suspend fun fail(r: HistoryRequestEntity, why: String) {
        log("history request ${r.requestId} failed: $why")
        tx.run {
            runCatching { engine()?.historyForget(r.requestId) }
            dao.upsertRequest(r.copy(state = HistoryState.FAILED, closed = true))
        }
    }

    /** §17.4 the refresh: a new `history_request` envelope (same request id, same rpk) at the current epoch. */
    private suspend fun refresh(r: HistoryRequestEntity) {
        val env = envelope(r) ?: return fail(r, "no key for refresh")
        val res = encryptAndPush(r.conversationId, r.requestId, env.encode()) { ct, gen, epoch ->
            realtime().history("history:refresh", ProtocolJson.encodeToJsonElement(HistoryRefresh.serializer(), HistoryRefresh(r.requestId, ct, gen, epoch)))
        }
        when (res) {
            is PushResult.Ok -> refreshOwed -= r.requestId
            is PushResult.Rejected -> {
                refreshOwed -= r.requestId
                if (res.reason == GONE) close(r, HistoryState.EXPIRED)
            }
            PushResult.Unavailable -> Unit
        }
    }

    /** "Ask group members now" (ends the own phase). */
    suspend fun escalate(conversationId: String): Boolean = kotlinx.coroutines.withContext(io) {
        val r = dao.openRequest(conversationId) ?: return@withContext false
        realtime().history("history:escalate", ProtocolJson.encodeToJsonElement(HistoryRequestRef.serializer(), HistoryRequestRef(r.requestId))) is PushResult.Ok
    }

    suspend fun cancel(conversationId: String) = kotlinx.coroutines.withContext(io) {
        dao.openRequest(conversationId)?.let { cancel(it) }
        Unit
    }

    private suspend fun cancel(r: HistoryRequestEntity) {
        realtime().history("history:cancel", ProtocolJson.encodeToJsonElement(HistoryRequestRef.serializer(), HistoryRequestRef(r.requestId)))
        close(r, HistoryState.CANCELLED)
        dao.parts(r.requestId).filter { it.state == HistoryPartEntity.PENDING }.forEach { dao.updatePart(it.copy(state = HistoryPartEntity.REJECTED)) }
    }

    private suspend fun close(r: HistoryRequestEntity, state: String) {
        tx.run {
            runCatching { engine()?.historyForget(r.requestId) }
            dao.upsertRequest((dao.request(r.requestId) ?: r).copy(state = state, closed = true))
        }
    }

    /** One part: fetch, check the SHA-256, open in the core, import in one transaction, ack. */
    private suspend fun importPart(p: HistoryPartEntity): Boolean {
        val req = dao.request(p.requestId) ?: return true.also { dao.updatePart(p.copy(state = ACKED_REJECTED)) }
        val me = me() ?: return false
        val dev = deviceId()
        val eng = engine() ?: return false
        val file = File(workDir, "history-${p.requestId}-${p.part}.blob")
        fun reject(why: String): String {
            log("history part ${p.requestId}/${p.part} rejected: $why")
            return HistoryAck.REJECTED
        }
        val result: String = try {
            workDir.mkdirs()
            when (val d = blobs.download(p.blobId, file)) {
                is ApiResult.Ok -> Unit
                is ApiResult.Error -> if (d.httpStatus == 404) return finishPart(p, reject("blob gone")) else return false
                is ApiResult.NetworkError -> return false
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            val expected = b64d.decode(p.sha256)
            if (file.length() != p.size || !digest.contentEquals(expected)) {
                reject("integrity: size or sha256")
            } else {
                val ctx = HistoryCtx(req.requestId, req.conversationId, "$me/$dev".lowercase(), "${p.providerUser}/${p.providerDevice}", p.part, p.parts, expected, p.plainSize)
                val plain = try {
                    eng.historyOpen(req.requestId, ctx, p.hpkeEnc, p.sealedKey, file)
                } catch (e: HistoryException) {
                    when (e.kind) {
                        HistoryException.Kind.UnknownRequest -> {
                            close(req, HistoryState.FAILED)
                            return finishPart(p, reject("no key"))
                        }
                        HistoryException.Kind.Io, HistoryException.Kind.Storage -> return false
                        else -> return finishPart(p, reject("open: ${e.message}"))
                    }
                }
                val parsed = HistoryBundle.parse(plain)
                val c = HistoryImporter.Context(req.requestId, req.conversationId, req.createdAt, p.part, p.parts, p.providerUser, me)
                if (parsed == null || !importer.headerMatches(c, parsed.header)) {
                    reject("header")
                } else {
                    val name = if (c.own) "" else ports.name(p.providerUser)
                    val res = tx.run {
                        val r = importer.importPart(c, parsed.entries, parsed.malformed)
                        importer.updateMarkers(req.conversationId, r.oldestLocalTs, c.own, name)
                        val cur = dao.request(req.requestId) ?: req
                        dao.upsertRequest(cur.copy(partsDone = cur.partsDone + 1, imported = cur.imported + r.imported, providerUser = cur.providerUser ?: p.providerUser))
                        r
                    }
                    if (res.images) ports.imagesImported()
                    HistoryAck.IMPORTED
                }
            }
        } finally {
            file.delete()
        }
        return finishPart(p, result)
    }

    /** Records the part's result, then `history:ack` (owed until the server answered). */
    private suspend fun finishPart(p: HistoryPartEntity, result: String): Boolean {
        val state = if (result == HistoryAck.IMPORTED) HistoryPartEntity.IMPORTED else HistoryPartEntity.REJECTED
        if (p.state == HistoryPartEntity.PENDING) dao.updatePart(p.copy(state = state))
        return ack(p.copy(state = state))
    }

    private suspend fun ack(p: HistoryPartEntity): Boolean {
        val result = if (p.state == HistoryPartEntity.IMPORTED) HistoryAck.IMPORTED else HistoryAck.REJECTED
        return when (val r = realtime().history("history:ack", ProtocolJson.encodeToJsonElement(HistoryAck.serializer(), HistoryAck(p.requestId, p.part, result)))) {
            is PushResult.Ok, is PushResult.Rejected -> {
                if (r is PushResult.Rejected) log("history:ack ${p.requestId}/${p.part}: ${r.reason}")
                dao.updatePart(p.copy(state = if (result == HistoryAck.IMPORTED) ACKED_IMPORTED else ACKED_REJECTED))
                true
            }
            PushResult.Unavailable -> false
        }
    }

    // ---- provider ----

    /** The user answered a prompt ([allow]: Allow / Share; else Not now). */
    suspend fun answer(requestId: String, allow: Boolean) = kotlinx.coroutines.withContext(io) { answerNow(requestId, allow) }

    private suspend fun answerNow(requestId: String, allow: Boolean) {
        val p = dao.provide(requestId) ?: return
        if (p.state != HistoryProvideEntity.ASK) return
        if (allow && p.own) approvals.add(p.requesterUser, p.requesterDevice, p.requesterSigKey, clock())
        dao.upsertProvide(p.copy(state = if (allow) HistoryProvideEntity.ACCEPTING else HistoryProvideEntity.DECLINING, updatedAt = clock()))
        ports.promptsChanged(dao.openProvides().filter { it.state == HistoryProvideEntity.ASK })
        afterCommit()
    }

    /** §17.8: a device left my device set: its approval goes. */
    fun onOwnDeviceRemoved(deviceId: String) = approvals.remove(deviceId)

    private suspend fun respond(p: HistoryProvideEntity, decision: String, reason: String?): PushResult<JsonObject> =
        realtime().history("history:respond", ProtocolJson.encodeToJsonElement(HistoryRespond.serializer(), HistoryRespond(p.requestId, decision, reason)))

    private fun exportRequest(p: HistoryProvideEntity) = ExportRequest(
        p.requestId, p.conversationId, p.requesterUser, p.own, HistoryRange(p.rangeFrom, p.rangeTo),
        runCatching { ProtocolJson.decodeFromString(ListSerializer(HistoryRange.serializer()), p.intervalsJson) }.getOrDefault(emptyList()),
    )

    private suspend fun selectUnits(p: HistoryProvideEntity) = HistoryExport.select(exportRequest(p), dao, deletes, media, sealer)

    private fun expired(p: HistoryProvideEntity): Boolean {
        val exp = HistoryMarkers.epochMs(p.expiresAt) ?: (p.createdAt + EXPIRY_MS)
        val memberWindow = if (p.own) Long.MAX_VALUE else p.createdAt + MEMBER_WINDOW_MS
        return clock() > minOf(exp, memberWindow)
    }

    private suspend fun processProvides() {
        var asksChanged = false
        for (p in dao.openProvides() + dao.provides(HistoryProvideEntity.DECLINING) + dao.provides(HistoryProvideEntity.UNABLE)) {
            when (p.state) {
                HistoryProvideEntity.ASK -> {
                    if (expired(p)) {
                        dao.upsertProvide(p.copy(state = HistoryProvideEntity.CLOSED, updatedAt = clock()))
                        asksChanged = true
                        continue
                    }
                    // §17.8 option A, only while unlocked (this runs only with a bearer and a live socket).
                    val next = when {
                        p.own && !ownAllowed() -> HistoryProvideEntity.DECLINING
                        p.own && approvals.approved(p.requesterDevice, p.requesterSigKey) -> HistoryProvideEntity.ACCEPTING
                        !p.own && !membersAsk() -> HistoryProvideEntity.DECLINING
                        else -> null
                    }
                    if (next != null) {
                        dao.upsertProvide(p.copy(state = next, updatedAt = clock()))
                        asksChanged = true
                        process(dao.provide(p.requestId)!!)
                    } else {
                        asksChanged = true
                    }
                }
                else -> process(p)
            }
        }
        if (asksChanged) ports.promptsChanged(dao.openProvides().filter { it.state == HistoryProvideEntity.ASK })
    }

    private suspend fun process(p: HistoryProvideEntity) {
        when (p.state) {
            HistoryProvideEntity.DECLINING -> when (val r = respond(p, HistoryRespond.DECLINE, null)) {
                is PushResult.Ok, is PushResult.Rejected -> dao.upsertProvide(p.copy(state = HistoryProvideEntity.DECLINED, updatedAt = clock())).also { if (r is PushResult.Rejected) log("decline: ${r.reason}") }
                PushResult.Unavailable -> Unit
            }
            HistoryProvideEntity.UNABLE -> when (respond(p, HistoryRespond.UNABLE, p.progressJson ?: HistoryRespond.REASON_STALE)) {
                is PushResult.Ok, is PushResult.Rejected -> dao.upsertProvide(p.copy(state = HistoryProvideEntity.CLOSED, progressJson = null, updatedAt = clock()))
                PushResult.Unavailable -> Unit
            }
            HistoryProvideEntity.ACCEPTING -> {
                // Plan the parts first: nothing to share → `unable`/`no_data` instead of an empty accept.
                val me = me() ?: return
                val plan = HistoryExport.parts(selectUnits(p))
                if (plan.isEmpty()) {
                    when (respond(p, HistoryRespond.UNABLE, HistoryRespond.REASON_NO_DATA)) {
                        is PushResult.Ok, is PushResult.Rejected -> dao.upsertProvide(p.copy(state = HistoryProvideEntity.CLOSED, updatedAt = clock()))
                        PushResult.Unavailable -> Unit
                    }
                    return
                }
                when (val r = respond(p, HistoryRespond.ACCEPT, null)) {
                    is PushResult.Ok -> {
                        val progress = plan.mapIndexed { i, units ->
                            PartProgress(i + 1, units.first().ts, units.first().messageId, units.last().ts, units.last().messageId, count = units.sumOf { it.entries })
                        }
                        dao.upsertProvide(p.copy(state = HistoryProvideEntity.EXPORTING, parts = plan.size, progressJson = encodeProgress(progress), updatedAt = clock()))
                        log("history ${p.requestId}: accepted by $me, ${plan.size} part(s)")
                        ports.startExport(p.requestId)
                    }
                    is PushResult.Rejected -> {
                        log("accept refused: ${r.reason}")
                        dao.upsertProvide(p.copy(state = HistoryProvideEntity.CLOSED, updatedAt = clock()))
                    }
                    PushResult.Unavailable -> Unit
                }
            }
            HistoryProvideEntity.EXPORTING -> ports.startExport(p.requestId)
        }
    }

    private fun encodeProgress(list: List<PartProgress>) = ProtocolJson.encodeToString(ListSerializer(PartProgress.serializer()), list)

    private fun decodeProgress(s: String?) = s?.let { runCatching { ProtocolJson.decodeFromString(ListSerializer(PartProgress.serializer()), it) }.getOrNull() }.orEmpty()

    private fun within(u: ExportUnit, pp: PartProgress): Boolean {
        fun cmp(ts: Long, id: String, ts2: Long, id2: String) = compareValuesBy(ts to id, ts2 to id2, { it.first }, { it.second })
        return cmp(u.ts, u.messageId, pp.firstTs, pp.firstId) >= 0 && cmp(u.ts, u.messageId, pp.lastTs, pp.lastId) <= 0
    }

    /**
     * The export worker's body (android R7): every part not yet delivered is built from Room within
     * its persisted boundaries, sealed in the core, uploaded as a `history` blob (sealed metadata
     * persisted with the blob id: a resumed part is never re-sealed), then delivered.
     */
    suspend fun runExport(requestId: String): ExportOutcome = kotlinx.coroutines.withContext(io) { exportLock.withLock { exportNow(requestId) } }

    private suspend fun exportNow(requestId: String): ExportOutcome {
        val p0 = dao.provide(requestId) ?: return ExportOutcome.GONE
        if (p0.state != HistoryProvideEntity.EXPORTING) return if (p0.state == HistoryProvideEntity.DELIVERED) ExportOutcome.DONE else ExportOutcome.GONE
        val me = me() ?: return ExportOutcome.RETRY
        val dev = deviceId()
        val c = crypto() ?: return ExportOutcome.RETRY
        var progress = decodeProgress(p0.progressJson)
        val parts = p0.parts
        if (progress.isEmpty() || parts == 0) return ExportOutcome.GONE
        var unitsCache: List<ExportUnit>? = null
        workDir.mkdirs()
        for (i in progress.indices) {
            var pp = progress[i]
            if (pp.delivered) continue
            if (pp.blobId == null) {
                val units = unitsCache ?: selectUnits(p0).also { unitsCache = it }
                val mine = units.filter { within(it, pp) }
                val plain = HistoryExport.plaintext(requestId, p0.conversationId, "$me/$dev".lowercase(), pp.part, parts, mine)
                val file = File(workDir, "history-out-$requestId-${pp.part}.blob")
                try {
                    val ctx = HistoryCtx(requestId, p0.conversationId, "${p0.requesterUser}/${p0.requesterDevice}", "$me/$dev".lowercase(), pp.part, parts)
                    val sealed = try {
                        c.seal(p0.rpk, ctx, plain, file)
                    } catch (e: HistoryException) {
                        log("history seal ${requestId}/${pp.part}: ${e.kind} ${e.message}")
                        dao.upsertProvide(p0.copy(state = HistoryProvideEntity.CLOSED, updatedAt = clock()))
                        return ExportOutcome.GONE
                    }
                    // A fresh idempotency id per seal: a retried upload of the same sealed file reuses it.
                    val cbid = newId()
                    pp = pp.copy(clientBlobId = cbid, count = mine.sumOf { it.entries })
                    when (val up = blobs.upload(p0.conversationId, requestId, cbid, file)) {
                        is ApiResult.Ok -> pp = pp.copy(
                            blobId = up.value.blobId, size = sealed.size, sha256 = b64.encodeToString(sealed.sha256), plainSize = sealed.plainSize,
                            hpkeEnc = b64.encodeToString(sealed.hpkeEnc), sealedKey = b64.encodeToString(sealed.sealedKey),
                        )
                        is ApiResult.Error -> {
                            log("history upload ${requestId}/${pp.part}: ${up.httpStatus} ${up.code}")
                            if (up.httpStatus == 404 || up.httpStatus == 400) {
                                dao.upsertProvide(p0.copy(state = HistoryProvideEntity.CLOSED, updatedAt = clock()))
                                return ExportOutcome.GONE
                            }
                            return ExportOutcome.RETRY
                        }
                        is ApiResult.NetworkError -> return ExportOutcome.RETRY
                    }
                } finally {
                    file.delete()
                }
                progress = progress.toMutableList().also { it[i] = pp }
                dao.upsertProvide((dao.provide(requestId) ?: p0).copy(progressJson = encodeProgress(progress), updatedAt = clock()))
            }
            val env = HistoryShareEnvelope(
                requestId, pp.part, parts, BlobRef(pp.blobId!!, pp.size, pp.sha256!!), pp.plainSize, b64d.decode(pp.hpkeEnc!!), b64d.decode(pp.sealedKey!!),
                pp.count, HistoryRange(p0.rangeFrom, p0.rangeTo),
            )
            val res = encryptAndPush(p0.conversationId, requestId, env.encode()) { ct, gen, epoch ->
                realtime().history("history:deliver", ProtocolJson.encodeToJsonElement(HistoryDeliver.serializer(), HistoryDeliver(requestId, ct, gen, epoch, pp.part, parts)))
            }
            when (res) {
                is PushResult.Ok -> Unit
                is PushResult.Rejected -> {
                    log("history:deliver ${requestId}/${pp.part}: ${res.reason}")
                    if (res.reason == AuthErrors.STALE_EPOCH || res.reason == RATE_LIMITED) return ExportOutcome.RETRY
                    dao.upsertProvide((dao.provide(requestId) ?: p0).copy(state = HistoryProvideEntity.CLOSED, updatedAt = clock()))
                    return ExportOutcome.GONE
                }
                PushResult.Unavailable -> return ExportOutcome.RETRY
            }
            progress = progress.toMutableList().also { it[i] = pp.copy(delivered = true) }
            dao.upsertProvide((dao.provide(requestId) ?: p0).copy(progressJson = encodeProgress(progress), updatedAt = clock()))
        }
        val done = (dao.provide(requestId) ?: p0)
        dao.upsertProvide(done.copy(state = HistoryProvideEntity.DELIVERED, updatedAt = clock()))
        log("history ${requestId}: delivered ${parts} part(s)")
        if (done.own) ports.sharedWithOwnDevice()
        return ExportOutcome.DONE
    }

    // ---- the loop ----

    /** Owed work, in order: provider answers, requester sends/refreshes, part imports and acks, the sweep. */
    suspend fun process() = kotlinx.coroutines.withContext(io) { runLock.withLock { processNow() } }

    private suspend fun processNow() {
        processProvides()
        for (r in dao.openRequests()) {
            if (clock() > r.expiresAt) {
                close(r, HistoryState.EXPIRED)
                continue
            }
            when {
                r.state == HistoryState.NEW -> send(r.requestId)
                r.requestId in refreshOwed || r.state == HistoryState.REFRESH && r.requestId !in refreshDone -> {
                    refresh(r)
                    refreshDone += r.requestId
                }
            }
        }
        for (p in dao.pendingParts()) {
            if (!importPart(p)) break
        }
        for (p in dao.owedAcks()) {
            if (!ack(p)) break
        }
    }

    /** Requests already refreshed in this process (a persisted `refresh` state is re-sent once after a restart). */
    private val refreshDone = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Start-up: forget keys of requests closed or older than 48 h (§17.3), then resume owed work. */
    suspend fun startup() = kotlinx.coroutines.withContext(io) { startupNow() }

    private suspend fun startupNow() {
        val eng = engine() ?: return
        val open = dao.openRequests().associateBy { it.requestId }
        for (id in runCatching { eng.historyOpenRequests() }.getOrDefault(emptyList())) {
            val r = open[id]
            if (r == null || clock() > r.expiresAt) {
                runCatching { eng.historyForget(id) }
                r?.let { close(it, HistoryState.EXPIRED) }
            }
        }
        for (r in open.values) if (runCatching { eng.historyPublicKey(r.requestId) }.getOrNull() == null) close(r, HistoryState.FAILED)
        approvals.refresh()
        afterCommit()
    }

    companion object {
        const val EXPIRY_MS = 48L * 3600_000
        const val MEMBER_WINDOW_MS = 24L * 3600_000
        const val REQUEST_OPEN = "request_open"
        const val GONE = "gone"
        const val RATE_LIMITED = "rate_limited"

        /** Part states after the server took the ack. */
        const val ACKED_IMPORTED = "acked_imported"
        const val ACKED_REJECTED = "acked_rejected"
    }
}
