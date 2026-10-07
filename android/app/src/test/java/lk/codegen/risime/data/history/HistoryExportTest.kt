package lk.codegen.risime.data.history

import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeReactionDao
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.ChatStateEntity
import lk.codegen.risime.data.db.DeletedIdEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.ReactionEntity
import lk.codegen.risime.data.deletes.FakeDeleteDao
import lk.codegen.risime.data.deletes.TimeUuid
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.data.history.HistoryFixtures.GRP
import lk.codegen.risime.data.history.HistoryFixtures.ME
import lk.codegen.risime.data.history.HistoryFixtures.PEER
import lk.codegen.risime.data.history.HistoryFixtures.THIRD
import lk.codegen.risime.data.history.HistoryFixtures.ms
import lk.codegen.risime.data.history.HistoryFixtures.msg
import lk.codegen.risime.net.GroupEvent
import lk.codegen.risime.net.HistoryRange
import lk.codegen.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §17.7 export filters (android R2, R3) and part building (R4). */
class HistoryExportTest {
    private val dm = dmConversationId(ME, PEER)
    private val messages = FakeMessageDao()
    private val reactions = FakeReactionDao()
    private val deletes = FakeDeleteDao(messages, reactions)
    private val dao = FakeHistoryDao(messages, reactions)
    private val all = HistoryRange("2026-09-01T00:00:00.000Z", "2026-10-07T00:00:00.000Z")

    private fun put(m: MessageEntity) = m.also { messages.rows[it.clientMsgId] = it }

    private fun line(action: String, actor: String, targets: List<String>, ts: String) = put(
        MessageEntity(
            clientMsgId = "sys:$action$ts", messageId = null, conversationId = GRP, from = actor, to = GRP, body = action, serverTs = null,
            localTs = ms(ts), status = MessageStatus.READ.name, outgoing = false, kind = MessageEntity.KIND_SYSTEM,
            systemJson = SystemLine(action, actor, targets).encode(),
        ),
    )

    private suspend fun ids(req: ExportRequest) = HistoryExport.select(req, dao, deletes, null, null).map { it.messageId }

    @Test fun deletedOutboxClearedAndHiddenRowsNeverGo() = runTest {
        val keep = put(msg(dm, PEER, "2026-10-02T09:00:00.000Z", "keep"))
        val mine = put(msg(dm, ME, "2026-10-02T09:01:00.000Z", "mine"))
        put(msg(dm, PEER, "2026-10-02T09:02:00.000Z", "", seq = 1).copy(kind = MessageEntity.KIND_DELETED)) // tombstone
        put(msg(dm, PEER, "2026-10-02T09:03:00.000Z", "deleting", seq = 2).copy(deleteState = MessageEntity.DELETE_STATE_DELETING))
        put(msg(dm, PEER, "2026-10-02T09:04:00.000Z", "unverified", seq = 3).copy(deleteUnverified = true))
        put(msg(dm, ME, "2026-10-02T09:05:00.000Z", "failed", seq = 4).copy(status = MessageStatus.FAILED.name))
        val hidden = put(msg(dm, PEER, "2026-10-02T09:06:00.000Z", "hidden", seq = 5))
        deletes.putDeletedId(DeletedIdEntity(hidden.messageId!!, dm, PEER, false, null, "everyone", 0))
        put(msg(dm, PEER, "2026-10-02T09:07:00.000Z", "Missed voice call", seq = 6, kind = MessageEntity.KIND_CALL).copy(systemJson = """{"v":1,"type":"call_end","call_id":"5b0c7e2a-3d4f-4a1b-9c8d-7e6f5a4b3c2d","reason":"timeout"}"""))
        val old = put(msg(dm, PEER, "2026-09-02T09:00:00.000Z", "before clear", seq = 7))
        deletes.putChatState(ChatStateEntity(dm, TimeUuid.ticks(old.messageId), false))
        put(HistoryMarkers_line())
        val member = ExportRequest("r", dm, PEER, own = false, all, listOf(all))
        assertEquals(listOf(mine.messageId, keep.messageId), ids(member))
        // android R3: the call line only to my own device.
        val own = ExportRequest("r", dm, ME, own = true, all, listOf(all))
        assertEquals(3, ids(own).size)
    }

    private fun HistoryMarkers_line() = lk.codegen.risime.data.HistoryMarkers.line("sys:history:$dm", dm, SystemLine.HISTORY_GAP, "x", ms("2026-10-02T09:00:30.000Z"))

    @Test fun groupExportIsTheServersIntervalsIntersectedWithTheLocalView() = runTest {
        line(GroupEvent.CREATED, ME, listOf(PEER), "2026-09-05T00:00:00.000Z")
        val before = put(msg(GRP, PEER, "2026-09-10T00:00:00.000Z", "before THIRD joined"))
        line(GroupEvent.ADDED, ME, listOf(THIRD), "2026-09-15T00:00:00.000Z")
        val inside = put(msg(GRP, PEER, "2026-09-20T00:00:00.000Z", "while THIRD was in"))
        line(GroupEvent.REMOVED, ME, listOf(THIRD), "2026-09-25T00:00:00.000Z")
        val after = put(msg(GRP, PEER, "2026-09-28T00:00:00.000Z", "after removal"))
        line(GroupEvent.ADDED, ME, listOf(THIRD), "2026-10-01T00:00:00.000Z")
        val again = put(msg(GRP, ME, "2026-10-02T00:00:00.000Z", "after rejoin"))
        // A lying server grants the whole range: the local view still cuts it.
        val wide = ExportRequest("r", GRP, THIRD, own = false, all, listOf(all))
        assertEquals(listOf(again.messageId, inside.messageId), ids(wide))
        // The server's narrower interval wins too.
        val narrow = ExportRequest("r", GRP, THIRD, own = false, all, listOf(HistoryRange("2026-10-01T12:00:00.000Z", "2026-10-07T00:00:00.000Z")))
        assertEquals(listOf(again.messageId), ids(narrow))
        // Someone who was a member before this device's first line: everything from the start.
        val peerReq = ExportRequest("r", GRP, PEER, own = false, all, listOf(all))
        assertEquals(listOf(again, after, inside, before).map { it.messageId }, ids(peerReq))
    }

    @Test fun reactionsFollowTheirTargetWithTheirClientMsgId() = runTest {
        val m = put(msg(dm, PEER, "2026-10-02T09:00:00.000Z", "hi"))
        val rid = HistoryFixtures.tuuid(ms("2026-10-02T09:01:00.000Z"), 9)
        reactions.upsert(ReactionEntity(dm, m.messageId!!, ME, "👍", "add", "add", "2026-10-02T09:01:00.000Z", rid, false, null, 0, confirmedClientMsgId = "cm-r"))
        // A reaction confirmed before v1.15 (no client_msg_id): can't be matched, not exported.
        reactions.upsert(ReactionEntity(dm, m.messageId!!, PEER, "❤️", "add", "add", "2026-10-02T09:02:00.000Z", HistoryFixtures.tuuid(ms("2026-10-02T09:02:00.000Z"), 8), false, null, 0))
        val units = HistoryExport.select(ExportRequest("r", dm, PEER, false, all, listOf(all)), dao, deletes, null, null)
        assertEquals(2, units.single().entries)
        assertTrue(units.single().lines[1].decodeToString().contains("\"cm-r\""))
    }

    @Test fun partsAreByteBoundedNewestFirstAndTheCapCutsTheOldest() {
        val units = (0 until 10).map { i -> ExportUnit("id$i", 1000L - i, listOf(ByteArray(400) { 'a'.code.toByte() })) } // newest first
        val parts = HistoryExport.parts(units, maxParts = 3, maxEntries = 100, maxBytes = HistoryExport.HEADER_RESERVE + 1000L)
        assertEquals(3, parts.size)
        assertEquals(listOf("id1", "id0"), parts[0].map { it.messageId }) // part 1 = newest, written oldest first
        assertEquals(listOf("id5", "id4"), parts[2].map { it.messageId })
        // Entry bound.
        val byCount = HistoryExport.parts(units, maxParts = 20, maxEntries = 4, maxBytes = Long.MAX_VALUE / 2)
        assertEquals(listOf(4, 4, 2), byCount.map { it.size })
        // The real bound fits 16 515 072 bytes including the header.
        val big = (0 until 5).map { i -> ExportUnit("b$i", 10L - i, listOf(ByteArray(5_000_000))) }
        val p = HistoryExport.parts(big)
        assertEquals(listOf(3, 2), p.map { it.size })
        assertTrue(p.all { part -> HistoryExport.plaintext("r", dm, "u/d", 1, 2, part).size <= HistoryLimits.MAX_PLAIN })
    }
}
