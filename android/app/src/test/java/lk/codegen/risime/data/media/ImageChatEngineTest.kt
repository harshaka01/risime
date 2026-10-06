package lk.codegen.risime.data.media

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeRealtime
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.GroupRef
import lk.codegen.risime.data.mls.KvSealer
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.realtime.PushResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/** §14.7 in the chat engine: receive in the cursor's transaction, the outbox never blocked by an image, send-time encryption. */
class ImageChatEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val me = "u-me"
    private val kamal = "u-kamal"
    private val conv = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val messages = FakeMessageDao()
    private val media = FakeMediaDao(messages)
    private val sync = FakeSyncDao()
    private val realtime = FakeRealtime()
    private val mls = FakeMlsEngine(me, "dev-me")
    private val pending = FakeMlsPendingDao()
    private val sealer = MediaSealer { KvSealer(ByteArray(32) { 1 }) }
    private var inTx = false
    private val log = mutableListOf<String>()
    private var ids = 0
    private var now = 1_000L

    private val tx = object : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T {
            inTx = true
            try { return block() } finally { inTx = false }
        }
    }

    private val repo by lazy {
        ImageRepository(media, messages, tx, sealer, MediaFiles(tmp.newFolder("m")), { FakeMediaCrypto() }, null,
            enqueueUpload = { log += "upload $it" }, enqueueDownloads = { log += "downloads inTx=$inTx" }, clock = { now })
    }

    /** Records that envelopes are stored inside the transaction and downloads scheduled outside it. */
    private val hooks by lazy {
        object : ImageHooks by repo {
            override suspend fun stored(row: MessageEntity, envelope: ImageEnvelope) {
                log += "stored inTx=$inTx"
                repo.stored(row, envelope)
            }
        }
    }

    private fun TestScope.engine() = ChatEngine(
        messages = messages, sync = sync, tx = tx, scope = this, realtime = { realtime }, meId = { me },
        clock = { now++ }, newClientMsgId = { "c${++ids}" },
        mls = MlsPipeline({ mls }, pending), mlsEngine = { mls }, groupsEnabled = { true }, images = hooks,
    )

    private val example = javaClass.classLoader!!.getResource("contract/v1/examples/image_payload.json")!!.readText()

    private fun groupEvent(eid: String, mid: String, from: String, dev: String, payload: String) = ProtocolJson.decodeFromString<Event>(
        """{"event_id":"$eid","kind":"message","data":{"message_id":"$mid","client_msg_id":"cm-$mid","conversation_id":"$conv",
        "from":"$from","from_device":"$dev","ciphertext":"${FakeMlsEngine.ciphertext(1, 1, from, dev, payload)}",
        "generation":1,"epoch":1,"server_ts":"2026-10-06T08:15:30.456Z"}}""",
    )

    @Test fun receivedImageIsStoredSealedWithTheCursorAndDownloadsComeAfter() = runTest {
        val e = engine()
        mls.groups[conv] = GroupRef(conv, 1, 1)
        e.onEvents(listOf(groupEvent("e1", "m1", kamal, "dev-k", example)))
        val row = messages.rows["cm-m1"]!!
        assertEquals(MessageEntity.KIND_IMAGE, row.kind)
        assertEquals("Site visit, level 3", row.body)
        assertEquals("7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", row.blobId)
        val m = media.get("cm-m1")!!
        assertEquals(MediaState.NONE.name, m.state)
        assertEquals(1_311_040L, m.blobSize)
        // The key and thumbnail are sealed, bound to the row.
        val key = Base64.getDecoder().decode("usaTFgbKkrHupsKDgiEilO6aTvojlqaCfF8jyJ2PAII=")
        assertFalse(m.sealedEnc.toList().windowed(32).any { it.toByteArray().contentEquals(key) })
        assertTrue(sealer.openEnc("cm-m1", m.sealedEnc).key.contentEquals(key))
        assertEquals(128, sealer.openThumb("cm-m1", m.sealedThumb!!).w)
        assertEquals("e1", sync.last)
        // Stored inside the event's transaction; downloads scheduled only after it (no network inside).
        assertEquals(listOf("stored inTx=true", "downloads inTx=false"), log)
        assertEquals(listOf("m1"), realtime.acks.single().first)
    }

    @Test fun malformedImageIsDroppedButTheCursorMoves() = runTest {
        val e = engine()
        mls.groups[conv] = GroupRef(conv, 1, 1)
        val bad = javaClass.classLoader!!.getResource("contract/v1/examples/image_payload_bad_key.json")!!.readText()
        e.onEvents(listOf(groupEvent("e1", "m1", kamal, "dev-k", bad)))
        assertTrue(messages.rows.isEmpty())
        assertTrue(media.rows.value.isEmpty())
        assertEquals("e1", sync.last)
        assertTrue(log.isEmpty())
    }

    @Test fun myImageFromMyOtherDeviceIsOutgoingAndDownloadable() = runTest {
        val e = engine()
        mls.groups[conv] = GroupRef(conv, 1, 1)
        e.onEvents(listOf(groupEvent("e1", "m1", me, "dev-other", example)))
        val row = messages.rows["cm-m1"]!!
        assertTrue(row.outgoing)
        assertTrue(media.get("cm-m1")!!.outgoing)
        assertEquals(1, media.downloadable().size)
    }

    private suspend fun queueImage(id: String, caption: String = ""): MessageEntity {
        val row = MessageEntity(id, null, conv, me, conv, caption, null, now++, "PENDING", true, kind = MessageEntity.KIND_IMAGE)
        messages.insert(row)
        val env = (MlsPayload.decode(example.toByteArray()) as MlsPayload.Decoded.Image).envelope
        repo.stored(row.copy(outgoing = true), env)
        media.update(media.get(id)!!.copy(state = MediaState.UPLOADING.name, blobId = null))
        return row
    }

    @Test fun textTypedDuringAnUploadIsSentFirstAndTheImageAfterItsUpload() = runTest {
        val e = engine()
        mls.groups[conv] = GroupRef(conv, 1, 1)
        queueImage("img", caption = "Site visit, level 3")
        val text = e.sendText(conv, "typed while uploading")!!
        advanceUntilIdle()
        assertEquals("SENT", messages.rows[text]!!.status)
        assertEquals("PENDING", messages.rows["img"]!!.status)
        assertEquals(1, realtime.sentGroup.size)

        // Upload done: the blob reference is stored, the outbox sends the full envelope (R8).
        media.update(media.get("img")!!.copy(state = MediaState.UPLOADED.name, blobId = "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f"))
        e.flushOutbox()
        val sent = realtime.sentGroup.last()
        assertEquals("img", sent.clientMsgId)
        val plain = String(Base64.getDecoder().decode(sent.ciphertext)).substringAfter("dev-me|")
        assertEquals(ProtocolJson.parseToJsonElement(example), ProtocolJson.parseToJsonElement(plain))
        assertEquals("SENT", messages.rows["img"]!!.status)
    }

    @Test fun staleEpochReencryptsTheSameEnvelopeUnderTheSameId() = runTest {
        val e = engine()
        mls.groups[conv] = GroupRef(conv, 1, 1)
        queueImage("img")
        media.update(media.get("img")!!.copy(state = MediaState.UPLOADED.name, blobId = "b1"))
        var first = true
        realtime.groupReplies = { m ->
            if (first) { first = false; PushResult.Rejected("stale_epoch") } else PushResult.Ok(lk.codegen.risime.net.MsgSendReply("gid-${m.clientMsgId}", conv, "2026-10-06T08:15:30.456Z"))
        }
        e.flushOutbox()
        assertEquals(2, realtime.sentGroup.size)
        assertEquals(setOf("img"), realtime.sentGroup.map { it.clientMsgId }.toSet())
        val a = String(Base64.getDecoder().decode(realtime.sentGroup[0].ciphertext)).substringAfter("dev-me|")
        val b = String(Base64.getDecoder().decode(realtime.sentGroup[1].ciphertext)).substringAfter("dev-me|")
        assertEquals(a, b)
        assertEquals("SENT", messages.rows["img"]!!.status)
    }

    @Test fun imagesNeverGoOutInPlaintext() = runTest {
        val e = engine()
        val dm = lk.codegen.risime.net.dmConversationId(me, kamal)
        val row = MessageEntity("img", null, dm, me, kamal, "", null, now++, "PENDING", true, kind = MessageEntity.KIND_IMAGE)
        messages.insert(row)
        repo.stored(row, (MlsPayload.decode(example.toByteArray()) as MlsPayload.Decoded.Image).envelope)
        media.update(media.get("img")!!.copy(state = MediaState.UPLOADED.name))
        e.flushOutbox() // no DM group: plaintext DM
        assertTrue(realtime.sent.isEmpty())
        assertEquals("FAILED", messages.rows["img"]!!.status)
        assertEquals("not_e2ee", messages.rows["img"]!!.failReason)
    }

    @Test fun deletingAFailedImageDeletesItsKey() = runTest {
        val e = engine()
        queueImage("img")
        messages.failPending("img", "too_large")
        assertTrue(e.deleteFailed("img"))
        assertNull(media.get("img"))
        assertNull(messages.rows["img"])
    }

    @Test fun anAppWithoutImagesIgnoresThem() = runTest {
        val e = ChatEngine(
            messages = messages, sync = sync, tx = tx, scope = this, realtime = { realtime }, meId = { me },
            mls = MlsPipeline({ mls }, pending), mlsEngine = { mls }, groupsEnabled = { true },
        )
        mls.groups[conv] = GroupRef(conv, 1, 1)
        e.onEvents(listOf(groupEvent("e1", "m1", kamal, "dev-k", example)))
        assertTrue(messages.rows.isEmpty())
        assertNotNull(sync.last)
    }
}
