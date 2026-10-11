package lk.codegen.risime.data.backup

import android.app.Application
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.backup.BackupData.DM
import lk.codegen.risime.data.backup.BackupData.ME
import lk.codegen.risime.data.backup.BackupData.PEER
import lk.codegen.risime.data.backup.BackupData.msg
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.StarEntity
import lk.codegen.risime.data.media.FileMeta
import lk.codegen.risime.data.mls.MlsPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.34 §22.5 amendments / §33.20 gate 9: `starred_at` on the `message` line (restore re-creates the
 * star), a `file` by reference, `forwarded` and `reply_to` inside the payload; real Room SQL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupV134Test {
    @get:Rule val tmp = TemporaryFolder()
    private val phones = mutableListOf<BackupPhone>()

    private fun phone() = BackupPhone(dir = tmp.newFolder()).also { phones += it }

    @After fun close() = phones.forEach { it.close() }

    private val file by lazy {
        val text = javaClass.classLoader!!.getResource("contract/v1/examples/file_payload.json")!!.readText()
        (MlsPayload.decode(text.toByteArray()) as MlsPayload.Decoded.File).envelope
    }

    @Test fun starsFilesForwardsAndRepliesSurviveARoundTrip() = runBlocking {
        val a = phone()
        val m = a.db.messages()
        val fwd = msg(DM, PEER, 1, body = "Site visit moved to 3 PM").copy(forwardHops = 7)
        val first = msg(DM, PEER, 2, body = "Can we meet?")
        val reply = msg(DM, ME, 3, body = "Yes, 3 works").copy(replyToMessageId = first.messageId, replyToFrom = PEER)
        val f = msg(DM, PEER, 4, body = "Notes from Friday").copy(kind = MessageEntity.KIND_FILE, blobId = file.blob!!.blobId, systemJson = FileMeta.of(file).encode())
        listOf(fwd, first, reply, f).forEach { m.insert(it) }
        a.images.storedFile(f, file)
        a.db.stars().put(listOf(StarEntity(fwd.messageId!!, DM, 1_760_000_000_000L), StarEntity(f.messageId!!, DM, 1_760_000_100_000L)))

        val lines = bundleOf(a)
        val text = lines.joinToString("\n")
        assertTrue(text.contains("\"starred_at\":\""))
        assertTrue(text.contains("\"type\":\"file\"") && text.contains("\"forwarded\":{\"hops\":7}") && text.contains("\"reply_to\":{"))
        // The un-starred lines carry no starred_at key at all.
        assertEquals(2, lines.count { it.contains("\"starred_at\"") })

        val b = phone()
        b.importer().import(lines.iterator())
        assertEquals(7, b.db.messages().byMessageId(fwd.messageId!!)!!.forwardHops)
        val r = b.db.messages().byMessageId(reply.messageId!!)!!
        assertEquals(first.messageId, r.replyToMessageId)
        assertEquals(PEER, r.replyToFrom)
        val gotFile = b.db.messages().byMessageId(f.messageId!!)!!
        assertEquals(MessageEntity.KIND_FILE, gotFile.kind)
        assertEquals("Interview planning – 2026-10-09.pdf", FileMeta.decode(gotFile.systemJson)!!.name)
        assertNotNull("the file's key, re-sealed on this phone", b.db.media().get(gotFile.clientMsgId))
        // Restore re-creates the stars (with their times).
        val stars = b.db.stars().all().associate { it.messageId to it.starredAt }
        assertEquals(mapOf(fwd.messageId to 1_760_000_000_000L, f.messageId to 1_760_000_100_000L), stars)
        assertNull(stars[first.messageId])
    }
}
