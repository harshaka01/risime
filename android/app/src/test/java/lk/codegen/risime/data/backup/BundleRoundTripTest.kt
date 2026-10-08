package lk.codegen.risime.data.backup

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.backup.BackupData.DM
import lk.codegen.risime.data.backup.BackupData.GRP
import lk.codegen.risime.data.backup.BackupData.ME
import lk.codegen.risime.data.backup.BackupData.PEER
import lk.codegen.risime.data.backup.BackupData.THIRD
import lk.codegen.risime.data.backup.BackupData.msg
import lk.codegen.risime.data.db.ChatStateEntity
import lk.codegen.risime.data.db.DeletedIdEntity
import lk.codegen.risime.data.db.HistoryGapEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.deletes.TimeUuid
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.data.HistoryMarkers
import lk.codegen.risime.net.dmConversationId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §22.5/§22.6 the bundle writer and importer against the real Room SQL: what goes in (and what
 * never does), a round trip into a fresh phone with equal per-conversation counts, merging into a
 * non-empty phone (insert-only, hard rule 9), deletes winning, idempotence and resume.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BundleRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()
    private val phones = mutableListOf<BackupPhone>()

    private fun phone() = BackupPhone(dir = tmp.newFolder()).also { phones += it }

    @After fun close() = phones.forEach { it.close() }

    @Test fun theBundleHasEveryLineTypeAndNothingFromTheNeverList() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        val lines = bundleOf(a)
        val types = lines.drop(1).map(BackupData::type)
        assertEquals(BackupBundleHeader.TYPE, BackupData.type(lines.first()))
        assertEquals(setOf("conversation", "message", "tombstone", "group_event", "contact"), types.toSet())
        // Per conversation: its line first, contacts last.
        assertEquals("conversation", types.first())
        assertEquals("contact", types.last())
        val text = lines.joinToString("\n")
        assertFalse("outbox row", text.contains("pending-20"))
        assertFalse("§13.3 marker", text.contains(SystemLine.HISTORY_GAP_TEXT) || text.contains("sys:history"))
        assertTrue("the photo by reference with its key", text.contains("\"type\":\"image\"") && text.contains("\"enc\""))
        assertTrue("call lines always", text.contains("\"call_end\"") || text.contains("call_end"))
        assertTrue("the local missed call", text.contains("local-missed:"))
        assertTrue("the hidden tombstone", lines.any { it.contains("\"hidden\":true") })
        assertTrue("the reaction", text.contains("\"reaction\""))
        assertTrue("Clear chat watermark", lines.any { it.contains("\"cleared_upto\":\"") })
        // Header counts match what was written.
        val h = lk.codegen.risime.net.ProtocolJson.decodeFromString(BackupBundleHeader.serializer(), lines.first())
        assertEquals(types.count { it == "conversation" }, h.counts.conversations)
        assertEquals(types.count { it == "message" }, h.counts.messages)
        assertEquals(types.count { it == "tombstone" }, h.counts.tombstones)
        assertEquals(types.count { it == "contact" }, h.counts.contacts)
        // Contract shapes: every line parses as its example's type.
        lines.drop(1).forEach { BackupData.json(it) }
    }

    @Test fun aFreshPhoneGetsEqualPerConversationCounts() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        val b = phone()
        val r = b.importer().import(bundleOf(a).iterator())
        val expected = a.counts().toMutableMap()
        // The outbox row and the marker never travel.
        expected[DM] = expected[DM]!!.let { listOf(it[0] - 1, it[1], it[2], it[3]) }
        val got = b.counts()
        // B has the group line too (a system row) but counts() only counts texts/images/calls/tombstones.
        assertEquals(expected, got)
        assertTrue(r.images)
        // Restored rows: read, never unread or acked, origin "backup", outgoing keep their status.
        assertTrue(b.db.messages().unreadIncoming().isEmpty())
        assertTrue(b.db.messages().unackedIncoming().isEmpty())
        val out = b.db.messages().byMessageId(msg(DM, ME, 1).messageId!!)!!
        assertEquals(MessageStatus.DELIVERED.name, out.status)
        assertEquals(BundleImporter.ORIGIN_BACKUP, out.origin)
        // The photo is downloadable by reference (key re-sealed on this phone).
        val img = b.db.messages().byMessageId(msg(DM, PEER, 10).messageId!!)!!
        assertEquals(MessageEntity.KIND_IMAGE, img.kind)
        assertNotNull(b.db.media().get(img.clientMsgId))
        // Group line, group row (display cache), reaction, hidden tombstone, watermark, contact cache.
        assertNotNull(b.db.messages().byClientMsgId("sys:c1a2b3eb-a0b1-11f0-8000-0242ac120002"))
        assertEquals("Site team", b.db.groups().get(GRP)!!.name)
        assertEquals("admin", b.db.groups().get(GRP)!!.myRole)
        assertEquals("add", b.db.reactions().get(DM, msg(DM, PEER, 1).messageId!!, ME, "👍")!!.op)
        // The hidden tombstone, plus one behind the visible tombstone (a later arrival stays deleted).
        assertEquals(2, b.db.backup().deletedIds(DM).size)
        assertEquals(1, b.db.backup().deletedIds(DM).count { it.messageId == msg(DM, PEER, 13).messageId })
        val third = dmConversationId(ME, THIRD)
        assertEquals(a.db.deletes().chatState(third)!!.clearedUpto, b.db.deletes().chatState(third)!!.clearedUpto)
        assertNotNull(b.db.contacts().byUserId(THIRD))
        assertFalse(b.db.contacts().byUserId(THIRD)!!.friend)
        assertEquals(0, r.skipped["malformed"] ?: 0)
    }

    @Test fun mergeIntoANonEmptyPhoneOnlyInsertsAndDeletesWin() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        val b = phone()
        // B already holds one of the messages (with another body: never overwritten), a newer one of its own,
        // a hidden tombstone for a message in the backup, and a later Clear chat watermark in the group.
        val held = msg(DM, PEER, 0)
        b.db.messages().insert(held.copy(body = "local copy"))
        val own = msg(DM, PEER, 99, body = "only on B")
        b.db.messages().insert(own)
        val gone = msg(DM, ME, 3)
        b.db.deletes().putDeletedId(DeletedIdEntity(gone.messageId!!, DM, ME, false, BackupData.at(50), "me", 1))
        b.db.deletes().putChatState(ChatStateEntity(GRP, TimeUuid.ticks(msg(GRP, ME, 32).messageId), false))
        val r = b.importer().import(bundleOf(a).iterator())
        assertEquals("local copy", b.db.messages().byMessageId(held.messageId!!)!!.body)
        assertNotNull(b.db.messages().byMessageId(own.messageId!!))
        assertNull("deleted for me on B stays deleted", b.db.messages().byMessageId(gone.messageId!!))
        // Group messages at or before B's watermark (31, 32) are skipped.
        assertNull(b.db.messages().byMessageId(msg(GRP, THIRD, 31).messageId!!))
        assertNotNull(b.db.messages().byMessageId(msg(GRP, THIRD, 33).messageId!!))
        assertTrue(r.skipped["existing_row"]!! >= 1)
        assertEquals(1, r.skipped["tombstone"])
        assertEquals(3, r.skipped["cleared"]) // 31, 32 and the group line at 30
        // The watermark never moves earlier through a restore.
        assertEquals(TimeUuid.ticks(msg(GRP, ME, 32).messageId), b.db.deletes().chatState(GRP)!!.clearedUpto)
    }

    @Test fun aRepeatedImportChangesNothing() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        val b = phone()
        val lines = bundleOf(a)
        b.importer().import(lines.iterator())
        val before = b.db.messages().conversation(DM).first().map { it.clientMsgId to it.body } + b.db.messages().conversation(GRP).first().map { it.clientMsgId to it.body }
        val counts = b.counts()
        val again = b.importer().import(lines.iterator())
        assertEquals(0, again.imported)
        assertEquals(0, again.tombstones)
        assertEquals(0, again.groupEvents)
        assertEquals(counts, b.counts())
        assertEquals(before, b.db.messages().conversation(DM).first().map { it.clientMsgId to it.body } + b.db.messages().conversation(GRP).first().map { it.clientMsgId to it.body })
    }

    @Test fun aKilledRestoreResumesFromItsLastBatch() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        val b = phone()
        val lines = bundleOf(a, backupId = "6f1e2d3c-4b5a-4968-8776-a5b4c3d2e1f0")
        try {
            b.importer(batch = 4).import(lines.iterator()) { n -> if (n >= 8) throw IllegalStateException("killed") }
            fail("not killed")
        } catch (e: IllegalStateException) {
            assertEquals("killed", e.message)
        }
        assertEquals(8, b.progress.done("6f1e2d3c-4b5a-4968-8776-a5b4c3d2e1f0"))
        val r = b.importer(batch = 4).import(lines.iterator())
        assertEquals(0, r.skipped["existing_row"] ?: 0) // the committed lines were skipped, not re-read
        val fresh = phone()
        fresh.importer().import(lines.iterator())
        assertEquals(fresh.counts(), b.counts())
        assertEquals(0, b.progress.done("6f1e2d3c-4b5a-4968-8776-a5b4c3d2e1f0"))
    }

    @Test fun restoredMessagesClearTheirGapRowsAndTheMarker() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        val b = phone()
        // B joined first: pre-install events left gap rows and the §13.3 marker.
        val m = msg(DM, PEER, 2)
        b.db.history().insertGap(HistoryGapEntity(m.messageId!!, DM, m.clientMsgId, PEER, null, m.serverTs!!, 1, 0, 1))
        b.db.messages().upsertSystemLine(HistoryMarkers.row(DM, SystemLine.HISTORY_GAP, m.serverTs, 0))
        b.importer().import(bundleOf(a).iterator())
        assertNull(b.db.history().gap(m.messageId!!))
        assertNull(b.db.messages().byClientMsgId(HistoryMarkers.historyId(DM)))
        assertNull("no 'history shared' line for a backup", b.db.messages().byClientMsgId(HistoryMarkers.sharedId(DM)))
    }

    @Test fun anotherAccountsBundleIsRejectedWhole() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        val other = BackupPhone(me = THIRD, dir = tmp.newFolder()).also { phones += it }
        try {
            other.importer().import(bundleOf(a).iterator())
            fail("imported another account's backup")
        } catch (e: BundleRejected) {
            assertEquals("another account", e.reason)
        }
        assertTrue(other.counts().isEmpty())
    }

    @Test fun unknownLineTypesAreSkipped() = runBlocking {
        val a = phone()
        a.db.messages().insert(msg(DM, PEER, 1))
        val lines = bundleOf(a).toMutableList()
        lines.add(2, """{"type":"future_thing","x":1}""")
        val b = phone()
        val r = b.importer().import(lines.iterator())
        assertEquals(1, r.imported)
        assertEquals(1, r.skipped["unknown_type"])
    }
}
