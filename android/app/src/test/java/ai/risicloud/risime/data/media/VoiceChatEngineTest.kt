package ai.risicloud.risime.data.media

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import ai.risicloud.risime.data.ChatEngine
import ai.risicloud.risime.data.FakeGroupDao
import ai.risicloud.risime.data.FakeGroupOpDao
import ai.risicloud.risime.data.FakeMessageDao
import ai.risicloud.risime.data.FakeRealtime
import ai.risicloud.risime.data.FakeSyncDao
import ai.risicloud.risime.data.TransactionRunner
import ai.risicloud.risime.data.db.MediaState
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.groups.GroupStore
import ai.risicloud.risime.data.mls.FakeMlsEngine
import ai.risicloud.risime.data.mls.FakeMlsPendingDao
import ai.risicloud.risime.data.mls.GroupRef
import ai.risicloud.risime.data.mls.KvSealer
import ai.risicloud.risime.data.mls.MlsPayload
import ai.risicloud.risime.data.mls.MlsPipeline
import ai.risicloud.risime.net.ACK_PLAYED
import ai.risicloud.risime.net.Event
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.ReplyRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/** v1.41 §37 in the chat engine: receive (sealed key, auto-download), strict drop, the `played` ack, the blue mic, send. */
class VoiceChatEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val kamal = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val conv = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val messages = FakeMessageDao()
    private val media = FakeMediaDao(messages)
    private val sync = FakeSyncDao()
    private val realtime = FakeRealtime()
    private val mls = FakeMlsEngine(me, "dev-me")
    private val sealer = MediaSealer { KvSealer(ByteArray(32) { 1 }) }
    private val log = mutableListOf<String>()
    private var ids = 0
    private var now = 1_000L
    private val tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() }

    private val repo by lazy {
        ImageRepository(media, messages, tx, sealer, MediaFiles(tmp.newFolder("m")), { FakeMediaCrypto() }, null,
            enqueueUpload = { log += "upload $it" }, enqueueDownloads = { log += "downloads" }, clock = { now }, newId = { "id${++ids}" })
    }

    private fun TestScope.engine() = ChatEngine(
        messages = messages, sync = sync, tx = tx, scope = this, realtime = { realtime }, meId = { me },
        clock = { now++ }, newClientMsgId = { "c${++ids}" },
        mls = MlsPipeline({ mls }, FakeMlsPendingDao()), mlsEngine = { mls }, groupsEnabled = { true }, images = repo,
        groups = GroupStore(FakeGroupDao(), FakeGroupOpDao(), messages, { "dev-me" }, { mls.groupMeta(it) }, { now++ }),
    )

    /** The contract example with its `blob.size` corrected to the §14.3 cipher size (proposal 2026-10-12-voice-example-sizes). */
    private fun example(name: String): String {
        val o = ProtocolJson.parseToJsonElement(javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()).jsonObject
        val plain = o["enc"]!!.jsonObject["plain_size"]!!.jsonPrimitive.long
        return JsonObject(o + ("blob" to JsonObject(o["blob"]!!.jsonObject + ("size" to JsonPrimitive(MediaFormat.cipherSize(plain)!!))))).toString()
    }

    private fun groupEvent(eid: String, mid: String, from: String, dev: String, payload: String) = ProtocolJson.decodeFromString<Event>(
        """{"event_id":"$eid","kind":"message","data":{"message_id":"$mid","client_msg_id":"cm-$mid","conversation_id":"$conv",
        "from":"$from","from_device":"$dev","ciphertext":"${FakeMlsEngine.ciphertext(1, 1, from, dev, payload)}",
        "generation":1,"epoch":1,"server_ts":"2026-10-12T09:15:30.456Z"}}""",
    )

    @Test fun receivedVoiceNoteIsStoredSealedAndDownloads() = runTest {
        val e = engine()
        mls.groups[conv] = GroupRef(conv, 1, 1)
        e.onEvents(listOf(groupEvent("e1", "m1", kamal, "dev-k", example("voice_payload_reply_no_transcribe.json"))))
        val row = messages.rows["cm-m1"]!!
        assertTrue(row.voice && row.media)
        assertEquals("", row.body)
        assertEquals("c1a2b3e1-a0b1-11f0-8000-0242ac120002", row.replyToMessageId)
        val meta = VoiceMeta.decode(row.systemJson)!!
        assertEquals(12_400L, meta.durationMs)
        assertEquals(false, meta.transcribe)
        assertNull(row.playedAt)
        val m = media.get("cm-m1")!!
        assertEquals(MediaState.NONE.name, m.state)
        assertEquals(VoiceEnvelope.MIME_OGG_OPUS, m.mime)
        // Sealed key, bound to the row; auto-download is scheduled (any network: the voice query).
        val key = Base64.getDecoder().decode("Hc2Tj9wQ1mZ8rV4yK7pL3sX6aN0bF5gD2eU9iO1tR8c=")
        assertTrue(sealer.openEnc("cm-m1", m.sealedEnc).key.contentEquals(key))
        assertEquals(listOf("downloads"), log)
        assertEquals(listOf("cm-m1"), media.downloadableVoice().map { it.clientMsgId })
        // Delivered ack as for any message.
        assertEquals(listOf("m1") to "delivered", realtime.acks.single())
    }

    @Test fun badWaveformIsDroppedAndTheCursorMoves() = runTest {
        val e = engine()
        mls.groups[conv] = GroupRef(conv, 1, 1)
        e.onEvents(listOf(groupEvent("e1", "m1", kamal, "dev-k", example("voice_payload_bad_waveform.json"))))
        assertTrue(messages.rows.isEmpty())
        assertTrue(media.rows.value.isEmpty())
        assertEquals("e1", sync.last)
    }

    @Test fun playedAckGoesOnceAfterReadAndNeverForMyOwnNotes() = runTest {
        val e = engine()
        mls.groups[conv] = GroupRef(conv, 1, 1)
        e.onEvents(listOf(groupEvent("e1", "m1", kamal, "dev-k", example("voice_payload.json"))))
        realtime.acks.clear()
        e.markPlayed("cm-m1")
        assertEquals(listOf(listOf("m1") to "read", listOf("m1") to ACK_PLAYED), realtime.acks)
        val row = messages.rows["cm-m1"]!!
        assertNotNull(row.playedAt)
        assertEquals("READ", row.status)
        assertEquals(MessageEntity.ACKED_PLAYED, row.ackedStatus)
        // Playing again, or any later flush, sends nothing more.
        realtime.acks.clear()
        e.markPlayed("cm-m1")
        e.flushAcks()
        e.markConversationRead(conv)
        assertTrue(realtime.acks.isEmpty())
        // My own note (from my other device): never a `played` ack.
        e.onEvents(listOf(groupEvent("e2", "m2", me, "dev-other", example("voice_payload.json").replace("5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c", "6f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c"))))
        e.markPlayed("cm-m2")
        assertTrue(realtime.acks.none { it.second == ACK_PLAYED })
        assertNull(messages.rows["cm-m2"]!!.playedAt)
    }

    @Test fun playedAckWaitsForTheConnection() = runTest {
        val e = engine()
        mls.groups[conv] = GroupRef(conv, 1, 1)
        e.onEvents(listOf(groupEvent("e1", "m1", kamal, "dev-k", example("voice_payload.json"))))
        realtime.connected = false
        e.markPlayed("cm-m1")
        assertEquals(null, messages.rows["cm-m1"]!!.ackedStatus.takeIf { it == MessageEntity.ACKED_PLAYED })
        realtime.connected = true
        realtime.acks.clear()
        e.flushAcks()
        assertEquals(listOf(ACK_PLAYED), realtime.acks.filter { it.second == ACK_PLAYED }.map { it.second })
    }

    @Test fun dmPlayedStatusTurnsTheMicBlueAndImpliesRead() = runTest {
        val e = engine()
        val dm = ai.risicloud.risime.net.dmConversationId(me, kamal)
        messages.insert(MessageEntity("o1", "m-o1", dm, me, kamal, "", "2026-10-12T09:15:30.456Z", 1, "DELIVERED", true, kind = MessageEntity.KIND_VOICE))
        val ev = ProtocolJson.decodeFromString<Event>(
            """{"event_id":"s1","kind":"status","data":{"message_id":"m-o1","client_msg_id":"o1","conversation_id":"$dm","status":"played","by":"$kamal","at":"2026-10-12T09:15:41.002Z"}}""",
        )
        e.onEvents(listOf(ev))
        val o1 = messages.rows["o1"]!!
        assertEquals("READ", o1.status)
        assertEquals(java.time.Instant.parse("2026-10-12T09:15:41.002Z").toEpochMilli(), o1.playedAt)
        assertEquals(o1.playedAt, o1.readAt)
    }

    @Test fun groupReceiptCarriesPlayedAndAllPlayed() = runTest {
        val e = engine()
        messages.insert(MessageEntity("7a1c2b9e-3d7d-4f43-9a51-2d0c8f6b1e02", "d1e2f3a4-a6f2-11f1-8000-0242ac120002", "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b", me, "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b", "", null, 1, "SENT", true, kind = MessageEntity.KIND_VOICE))
        val ev = javaClass.classLoader!!.getResource("contract/v1/examples/event_group_receipt_v141.json")!!.readText()
        e.onEvents(listOf(ProtocolJson.decodeFromString<Event>(ev)))
        val row = messages.rows["7a1c2b9e-3d7d-4f43-9a51-2d0c8f6b1e02"]!!
        assertEquals("READ", row.status)
        assertEquals(1, row.receiptPlayed)
        assertNull(row.playedAt) // not all played yet
        e.onEvents(listOf(ProtocolJson.decodeFromString<Event>(ev.replace("\"played\":1,\"all_played\":false", "\"played\":2,\"all_played\":true").replace("d1e2f3b1", "d1e2f3b2"))))
        assertEquals(2, messages.rows["7a1c2b9e-3d7d-4f43-9a51-2d0c8f6b1e02"]!!.receiptPlayed)
        assertNotNull(messages.rows["7a1c2b9e-3d7d-4f43-9a51-2d0c8f6b1e02"]!!.playedAt)
    }

    @Test fun aRecordedNoteIsEncryptedWithAFreshKeyAndSentAsAVoiceEnvelope() = runTest {
        val ogg = "OggS".toByteArray() + ByteArray(3_000) { (it % 251).toByte() }
        val wave = ByteArray(64) { it.toByte() }
        val meta = VoiceMeta(VoiceEnvelope.MIME_OGG_OPUS, 3_200, Base64.getEncoder().encodeToString(wave), transcribe = false)
        val row = repo.commitVoice(ogg, conv, me, conv, meta, ReplyRef("c1a2b3e1-a0b1-11f0-8000-0242ac120002", kamal))
        val row2 = repo.commitVoice(ogg, conv, me, conv, meta)
        assertEquals(MessageEntity.KIND_VOICE, row.kind)
        assertEquals("PENDING", row.status)
        assertTrue(log.contains("upload ${row.clientMsgId}"))
        val m = media.get(row.clientMsgId)!!
        assertEquals(MediaState.ENCRYPTED.name, m.state)
        // No plaintext left anywhere under the media dir (only the ciphertext file).
        assertTrue(repo.files.tmp.listFiles().orEmpty().isEmpty())
        assertFalse(repo.files.file(m.fileName!!).readBytes().toList().windowed(4).any { it == "OggS".toByteArray().toList() })
        // Fresh key per note.
        val k1 = sealer.openEnc(row.clientMsgId, m.sealedEnc).key
        val k2 = sealer.openEnc(row2.clientMsgId, media.get(row2.clientMsgId)!!.sealedEnc).key
        assertFalse(k1.contentEquals(k2))
        // After the upload: the send-time envelope is a strictly valid `voice` with everything the row knows.
        media.update(m.copy(state = MediaState.UPLOADED.name, blobId = "b-1"))
        val env = (MlsPayload.decode(repo.envelope(row.clientMsgId)!!) as MlsPayload.Decoded.Voice).envelope
        assertEquals("b-1", env.blob.blobId)
        assertEquals(3_200L, env.durationMs)
        assertArrayEquals(wave, env.waveform)
        assertEquals(false, env.transcribe)
        assertEquals("c1a2b3e1-a0b1-11f0-8000-0242ac120002", env.extras.replyTo!!.messageId)
        assertEquals(ogg.size.toLong(), env.enc.plainSize)
    }

    @Test fun voiceNotesAreNeverEvicted() = runTest {
        messages.insert(MessageEntity("v1", "m-v1", conv, kamal, conv, "", "2026-10-12T09:15:30.456Z", 1, "READ", false, kind = MessageEntity.KIND_VOICE))
        repo.storedVoice(messages.rows["v1"]!!, VoiceEnvelope.validate(ProtocolJson.parseToJsonElement(example("voice_payload.json")).jsonObject)!!)
        media.update(media.get("v1")!!.copy(state = MediaState.CACHED.name, fileName = "f1"))
        repo.files.file("f1").writeBytes(ByteArray(10))
        repo.evict(budget = 0)
        assertEquals(MediaState.CACHED.name, media.get("v1")!!.state)
        assertTrue(repo.files.file("f1").isFile)
    }
}
