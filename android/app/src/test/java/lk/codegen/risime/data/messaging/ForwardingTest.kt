package lk.codegen.risime.data.messaging

import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.media.FakeMediaCrypto
import lk.codegen.risime.data.media.FakeMediaDao
import lk.codegen.risime.data.media.FileEnvelope
import lk.codegen.risime.data.media.FileMeta
import lk.codegen.risime.data.media.ImageEnc
import lk.codegen.risime.data.media.ImageEnvelope
import lk.codegen.risime.data.media.MediaFiles
import lk.codegen.risime.data.media.MediaSealer
import lk.codegen.risime.data.media.PreparedImage
import lk.codegen.risime.data.mls.KvSealer
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.data.mls.sendExtras
import lk.codegen.risime.net.EnvelopeExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** §33.20 gate 15: the forward builder, the target rules, the 30 × 5 persistence and the re-upload state machine. */
class ForwardingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val kamal = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val conv = "dm:a_b"
    private fun row(id: String, from: String = kamal, body: String = "hi", kind: String = MessageEntity.KIND_TEXT, hops: Int? = null, systemJson: String? = null) =
        MessageEntity(id, "m-$id", conv, from, me, body, "2026-10-10T01:56:00.000Z", 1, "READ", from == me, kind = kind, forwardHops = hops, systemJson = systemJson)

    @Test fun hopsFollowTheOwnMessageRuleAndTheCap() {
        assertEquals(1, ForwardRules.build(row("a"), me)!!.hops)
        assertNull("own unforwarded: no field", ForwardRules.build(row("b", from = me), me)!!.hops)
        assertEquals(3, ForwardRules.build(row("c", hops = 2), me)!!.hops)
        assertEquals(5, ForwardRules.build(row("d", from = me, hops = 4), me)!!.hops)
        assertEquals(255, ForwardRules.build(row("e", hops = 255), me)!!.hops)
    }

    @Test fun theBuilderDropsRisiReplyToAndUnknownFields() {
        // A Risi answer becomes plain text from its body; its risi object never travels.
        val risi = """{"v":1,"kind":"answer","answer":"Yes","notify":[]}"""
        val spec = ForwardRules.build(row("r", body = "Yes, you're free", systemJson = risi), me)!!
        assertEquals(MessageEntity.KIND_TEXT, spec.kind)
        assertEquals("Yes, you're free", spec.body)
        assertEquals(1, spec.hops)
        // The sent envelope: only v, type, body and forwarded.
        val sent = MlsPayload.text(spec.body, EnvelopeExtras(spec.hops))
        val obj = lk.codegen.risime.net.ProtocolJson.parseToJsonElement(sent.decodeToString()) as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("v", "type", "body", "forwarded"), obj.keys)
        // A reply's reply_to is not copied on forward.
        val reply = row("q").copy(replyToMessageId = "c1a2b3e1-a0b1-11f0-8000-0242ac120002", replyToFrom = kamal)
        val fwd = ForwardRules.build(reply, me)!!
        val target = row("t", from = me).copy(forwardHops = fwd.hops, body = fwd.body)
        assertNull(sendExtras(target).replyTo)
        // Cards with buttons, errors, view once and controls: never.
        for (k in listOf("confirm", "offer", "calendar_offer", "calendar_invite", "error")) {
            assertNull(k, ForwardRules.build(row("k$k", systemJson = """{"v":1,"kind":"$k"}"""), me))
        }
        assertNull(ForwardRules.build(row("vo").copy(viewOnce = true), me))
        assertNull(ForwardRules.build(row("ctl", kind = MessageEntity.KIND_RISI_CTL, systemJson = """{"type":"risi_action"}"""), me))
        // A note card: the §30.6 share text.
        val note = ForwardRules.build(row("n", systemJson = """{"v":1,"kind":"note_card","note_id":"n1"}"""), me) { "Notes: X\n— shared from Risi Notes" }!!
        assertTrue(note.body.endsWith("— shared from Risi Notes"))
    }

    @Test fun mediaAndFilesKeepTheirFieldsAndAPartsFileIsNotForwardable() {
        val img = ForwardRules.build(row("i", kind = MessageEntity.KIND_IMAGE, body = "Level 3"), me)!!
        assertEquals("Level 3", img.body)
        assertEquals("i", img.mediaSource)
        val meta = FileMeta("Plan.pdf", "application/pdf", 3)
        val f = ForwardRules.build(row("f", kind = MessageEntity.KIND_FILE, systemJson = meta.encode()), me)!!
        assertEquals(meta, FileMeta.decode(f.systemJson))
        assertNull(ForwardRules.build(row("p", kind = MessageEntity.KIND_FILE, systemJson = meta.copy(parts = true).encode()), me))
    }

    @Test fun targetRulesExcludeAndDisableWithReasons() {
        val c = listOf(
            ForwardCandidate("dm:a_k", "Kamal", official = false, risiChat = false, e2ee = true, activeMember = true, imagesReady = true, filesReady = false, lastActivity = 5),
            ForwardCandidate("grp:o", "Kamal", official = true, risiChat = false, e2ee = true, activeMember = true, lastActivity = 9),
            ForwardCandidate("grp:risi", "Risi", official = true, risiChat = true, e2ee = true, activeMember = true),
            ForwardCandidate("dm:a_x", "Plain", official = false, risiChat = false, e2ee = false, activeMember = true),
            ForwardCandidate("grp:left", "Left", official = false, risiChat = false, e2ee = true, activeMember = false),
            ForwardCandidate("grp:off", "Off", official = true, risiChat = false, e2ee = true, activeMember = true, officialOff = true),
            ForwardCandidate("dm:a_b2", "Blocked", official = false, risiChat = false, e2ee = true, activeMember = true, blockedOrUnfriended = true),
        )
        val t = ForwardRules.targets(c, hasImage = false, hasFile = true)
        assertEquals(listOf("dm:a_k", "grp:o"), t.map { it.conversationId })
        assertEquals("Kamal needs to update the app to receive files", t[0].disabledReason)
        assertEquals(ForwardTarget.OFFICIAL_LABEL, t[1].label)
        val (recent, all) = ForwardRules.lists(t, "")
        assertEquals(listOf("grp:o", "dm:a_k"), recent.map { it.conversationId })
        assertEquals(2, all.size)
        assertEquals(0, ForwardRules.lists(t, "zz").second.size)
    }

    @Test fun manyTimesAllowsOneTargetAndTheHintIsDueOnce() {
        assertEquals(5, ForwardRules.maxTargets(listOf(row("a", hops = 4))))
        assertEquals(1, ForwardRules.maxTargets(listOf(row("a"), row("b", hops = 5))))
        val official = ForwardTarget("grp:o", "K", official = true, dm = false)
        val private = ForwardTarget("dm:x", "K", official = false, dm = true)
        assertTrue(ForwardRules.hintDue(sourcePrivate = true, listOf(private, official), alreadyShown = false))
        assertFalse(ForwardRules.hintDue(sourcePrivate = true, listOf(official), alreadyShown = true))
        assertFalse(ForwardRules.hintDue(sourcePrivate = false, listOf(official), alreadyShown = false))
        assertFalse(ForwardRules.hintDue(sourcePrivate = true, listOf(private), alreadyShown = false))
    }

    private val tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() }

    @Test fun thirtyByFiveIsPersistedInOneGoWithFreshIdsAndResumesAfterAKill() = runTest {
        val messages = FakeMessageDao()
        val media = FakeMediaDao(messages)
        var n = 0
        val fw = Forwarder(messages, media, tx, clock = { 1000L }, newId = { "id${n++}" })
        val specs = (1..30).map { i -> if (i % 10 == 0) ForwardSpec(MessageEntity.KIND_IMAGE, "p$i", 1, mediaSource = "src$i") else ForwardSpec(MessageEntity.KIND_TEXT, "t$i", 1) }
        val targets = (1..5).map { "grp:t$it" }
        val plan = fw.persist(specs, targets, me, addMessage = "FYI")
        assertEquals(5 * 31, plan.rows.size)
        assertEquals(5 * 3, plan.mediaJobs.size)
        assertEquals(plan.rows.map { it.clientMsgId }.toSet().size, plan.rows.size)
        // Chat order per target; the added message last, unforwarded.
        val t1 = messages.rows.values.filter { it.conversationId == "grp:t1" }.sortedBy { it.localTs }
        assertEquals((1..30).map { if (it % 10 == 0) "p$it" else "t$it" } + "FYI", t1.map { it.body })
        assertNull(t1.last().forwardHops)
        // Every row pending (the outbox sends them); each media row is a REENCRYPT job with its own client_blob_id.
        assertTrue(messages.rows.values.all { it.status == "PENDING" })
        val jobs = media.reencryptJobs()
        assertEquals(15, jobs.size)
        assertEquals(15, jobs.map { it.clientBlobId }.toSet().size)
        // Texts go at once: media rows wait for their upload.
        assertEquals(5 * 28, messages.pendingOutbox().size)
    }

    @Test fun reuploadStateMachine() = runTest {
        val messages = FakeMessageDao()
        val media = FakeMediaDao(messages)
        val files = MediaFiles(tmp.newFolder("media"))
        val sealer = MediaSealer { KvSealer(ByteArray(32) { 7 }) }
        val crypto = FakeMediaCrypto()
        val uploads = mutableListOf<String>()
        var downloads = 0
        var now = 10_000L
        // The source: a cached file.
        val plain = tmp.newFile("p").apply { writeBytes(ByteArray(5000) { 1 }) }
        val srcBlob = crypto.encryptFile(plain, files.file("src.enc"))
        fun srcMedia(id: String, state: MediaState, fileName: String?, expires: Long? = null, w: Int = 0) = MediaEntity(
            id, conv, false, state.name, "blob-$id", srcBlob.cipherSize, java.util.Base64.getEncoder().encodeToString(srcBlob.sha256), null,
            sealer.sealEnc(id, ImageEnc(srcBlob.alg, srcBlob.key, srcBlob.plainSize)), null, FileEnvelope.MIME_PDF, w, w, fileName, expiresAtEst = expires, lastAccess = 0,
        )
        media.insert(srcMedia("cached", MediaState.CACHED, "src.enc"))
        media.insert(srcMedia("fetch", MediaState.NONE, null, expires = now + 1000))
        media.insert(srcMedia("expired", MediaState.NONE, null, expires = now - 1))
        media.insert(srcMedia("img", MediaState.CACHED, "src.enc", w = 100))
        val job = ForwardMedia(
            media, messages, sealer, files, { crypto },
            download = { id -> downloads++; media.get(id)?.let { media.update(it.copy(state = MediaState.CACHED.name, fileName = "src.enc")) }; true },
            reencodeImage = { PreparedImage(ByteArray(300) { 2 }, ImageEnvelope.MIME_JPEG, 64, 48, null) },
            enqueueUpload = { uploads += it }, clock = { now },
        )
        val fw = Forwarder(messages, media, tx, clock = { now }, newId = { java.util.UUID.randomUUID().toString() })
        val plan = fw.persist(
            listOf(
                ForwardSpec(MessageEntity.KIND_FILE, "", 1, FileMeta("a.pdf", FileEnvelope.MIME_PDF).encode(), "cached"),
                ForwardSpec(MessageEntity.KIND_FILE, "", 1, FileMeta("b.pdf", FileEnvelope.MIME_PDF).encode(), "fetch"),
                ForwardSpec(MessageEntity.KIND_FILE, "", 1, FileMeta("c.pdf", FileEnvelope.MIME_PDF).encode(), "expired"),
                ForwardSpec(MessageEntity.KIND_IMAGE, "cap", 1, mediaSource = "img"),
            ),
            listOf("grp:t1", "grp:t2"), me, null,
        )
        for (id in plan.mediaJobs) job.run(id)
        val rows = plan.mediaJobs.map { media.get(it)!! }
        // Cached and fetchable (downloaded first) sources: ENCRYPTED with a fresh key, then the upload job.
        val encrypted = rows.filter { it.state == MediaState.ENCRYPTED.name }
        assertEquals(6, encrypted.size)
        assertEquals(6, uploads.size)
        assertEquals(1, downloads)
        val keys = encrypted.map { sealer.openEnc(it.clientMsgId, it.sealedEnc).key.toList() }
        assertEquals("a fresh key per target", 6, keys.toSet().size)
        assertTrue(keys.none { it == srcBlob.key.toList() })
        // A forwarded image is re-encoded (new mime/size/thumb from the pipeline).
        val imgs = rows.filter { it.w == 64 }
        assertEquals(2, imgs.size)
        val img = imgs.first()
        assertEquals(ImageEnvelope.MIME_JPEG, img.mime)
        // Expired source never downloaded: failed with the reason, the message FAILED (Retry / Delete).
        val failed = rows.filter { it.state == MediaState.FAILED.name }
        assertEquals(2, failed.size)
        assertTrue(failed.all { it.failReason == ForwardMedia.SOURCE_GONE && messages.rows[it.clientMsgId]!!.status == "FAILED" })
        // No plaintext left in tmp.
        assertTrue(files.tmp.listFiles()!!.isEmpty())
        // Resume is a no-op once done.
        job.resumeAll()
        assertEquals(6, uploads.size)
        assertNotEquals(srcBlob.cipherSize, 0L)
    }
}
