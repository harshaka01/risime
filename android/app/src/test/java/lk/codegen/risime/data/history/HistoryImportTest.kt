package lk.codegen.risime.data.history

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeReactionDao
import lk.codegen.risime.data.HistoryMarkers
import lk.codegen.risime.data.ReactionStore
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.deletes.DeleteApplier
import lk.codegen.risime.data.deletes.FakeDeleteDao
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.data.history.HistoryFixtures.GRP
import lk.codegen.risime.data.history.HistoryFixtures.ME
import lk.codegen.risime.data.history.HistoryFixtures.PEER
import lk.codegen.risime.data.history.HistoryFixtures.THIRD
import lk.codegen.risime.data.history.HistoryFixtures.gap
import lk.codegen.risime.data.history.HistoryFixtures.ms
import lk.codegen.risime.data.history.HistoryFixtures.msg
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.net.HistoryBundleEntry
import lk.codegen.risime.net.HistoryBundleHeader
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §17.7 import matching (crypto R6), dedupe, the header check and the marker exception (android R4b, R5). */
class HistoryImportTest {
    private val messages = FakeMessageDao()
    private val reactions = FakeReactionDao()
    private val deletes = FakeDeleteDao(messages, reactions)
    private val dao = FakeHistoryDao(messages, reactions)
    private val importer = HistoryImporter(messages, dao, DeleteApplier(deletes, messages, null), ReactionStore(reactions), null)
    private val requestAt = 10_000L
    private val ctx = HistoryImporter.Context("r1", GRP, requestAt, 1, 1, THIRD, ME)

    private fun json(b: ByteArray) = ProtocolJson.parseToJsonElement(b.decodeToString()) as JsonObject

    private fun entry(m: MessageEntity, payload: JsonObject = json(MlsPayload.text(m.body))) =
        HistoryBundleEntry(m.messageId!!, m.clientMsgId, m.from, null, m.serverTs!!, payload)

    @Test fun onlyExactGapMatchesImportOnce() = runTest {
        val ok = msg(GRP, PEER, "2026-09-20T00:00:00.000Z", "ok")
        val badCmid = msg(GRP, PEER, "2026-09-20T00:01:00.000Z", "client_msg_id differs")
        val badFrom = msg(GRP, PEER, "2026-09-20T00:02:00.000Z", "from differs")
        val badTs = msg(GRP, PEER, "2026-09-20T00:03:00.000Z", "server_ts differs")
        val late = msg(GRP, PEER, "2026-09-20T00:04:00.000Z", "gap row after the request")
        val noGap = msg(GRP, PEER, "2026-09-20T00:05:00.000Z", "no gap row")
        val held = msg(GRP, PEER, "2026-09-20T00:06:00.000Z", "already held")
        val malformed = msg(GRP, PEER, "2026-09-20T00:07:00.000Z", "malformed")
        listOf(ok, badCmid, badFrom, badTs, held, malformed).forEach { dao.insertGap(gap(it, createdAt = 1)) }
        dao.insertGap(gap(late, createdAt = requestAt + 1))
        messages.insert(held)
        val entries = listOf(
            entry(ok),
            entry(badCmid).copy(clientMsgId = "forged"),
            entry(badFrom).copy(from = THIRD),
            entry(badTs).copy(serverTs = "2026-09-20T00:03:00.001Z"),
            entry(late),
            entry(noGap),
            entry(held),
            entry(malformed, json("""{"v":1,"type":"image","blob":{}}""".toByteArray())),
            // The same id twice in one part: imported once.
            entry(ok),
        )
        val r = importer.importPart(ctx, entries)
        assertEquals(1, r.imported)
        assertEquals(mapOf("mismatch" to 3, "gap_after_request" to 1, "no_gap_row" to 1, "existing_row" to 2, "malformed" to 1), r.skipped)
        val row = messages.byMessageId(ok.messageId!!)!!
        assertEquals(ok.clientMsgId, row.clientMsgId) // from the gap row
        assertEquals(MessageEntity.ORIGIN_SHARED, row.origin)
        assertEquals(THIRD, row.sharedBy)
        assertEquals("READ", row.status)
        assertEquals("READ", row.ackedStatus)
        assertNull(dao.gap(ok.messageId!!))
        assertNotNull(dao.gap(badCmid.messageId!!)) // residual
        assertNull(dao.gap(held.messageId!!))
        // A second import of the same part is a no-op.
        assertEquals(0, importer.importPart(ctx, listOf(entry(ok))).imported)
    }

    @Test fun reactionsApplyOnlyToAHeldTarget() = runTest {
        val target = msg(GRP, PEER, "2026-09-20T00:00:00.000Z", "target")
        val react = msg(GRP, THIRD, "2026-09-20T00:01:00.000Z", "", seq = 1)
        val orphan = msg(GRP, THIRD, "2026-09-20T00:02:00.000Z", "", seq = 2)
        listOf(target, react, orphan).forEach { dao.insertGap(gap(it, createdAt = 1)) }
        val r = importer.importPart(
            ctx,
            listOf(
                entry(react, json(MlsPayload.reaction(target.messageId!!, "👍", "add"))), // before its target in the part
                entry(target),
                entry(orphan, json(MlsPayload.reaction("c1a2b3e1-a0b1-11f0-8000-0242ac120002", "👍", "add"))),
            ),
        )
        assertEquals(2, r.imported)
        assertEquals("add", reactions.get(GRP, target.messageId!!, THIRD, "👍")!!.op)
        assertNotNull(dao.gap(orphan.messageId!!))
    }

    @Test fun headerMustMatchTheRequest() {
        val h = HistoryBundleHeader(1, HistoryBundleHeader.TYPE, "r1", GRP, "x/y", 1, 1, 0)
        assertTrue(importer.headerMatches(ctx, h))
        assertFalse(importer.headerMatches(ctx, h.copy(requestId = "r2")))
        assertFalse(importer.headerMatches(ctx, h.copy(conversationId = "grp:other")))
        assertFalse(importer.headerMatches(ctx, h.copy(part = 2, parts = 2)))
        assertNull(HistoryBundle.parse("not json\n".toByteArray()))
        val p = HistoryBundle.parse((ProtocolJson.encodeToString(HistoryBundleHeader.serializer(), h) + "\n{bad}\n").toByteArray())!!
        assertEquals(1, p.malformed)
    }

    @Test fun markerException() = runTest {
        val a = msg(GRP, PEER, "2026-09-20T00:00:00.000Z", "a")
        val b = msg(GRP, PEER, "2026-09-25T00:00:00.000Z", "b")
        listOf(a, b).forEach { dao.insertGap(gap(it, createdAt = 1)) }
        messages.upsertSystemLine(HistoryMarkers.row(GRP, SystemLine.HISTORY_GAP, "2026-09-25T00:00:00.000Z", 0))
        val r = importer.importPart(ctx, listOf(entry(b)))
        importer.updateMarkers(GRP, r.oldestLocalTs, own = false, providerName = "Chamari")
        // The gap marker moves EARLIER to the newest remaining gap row; the header sits before the import.
        val marker = messages.byClientMsgId(HistoryMarkers.historyId(GRP))!!
        assertEquals(ms(a.serverTs!!) + 1, marker.localTs)
        assertEquals(SystemLine.HISTORY_GAP_SOME_TEXT, marker.body)
        val header = messages.byClientMsgId(HistoryMarkers.sharedId(GRP))!!
        assertEquals("History shared by Chamari", header.body)
        assertEquals(ms(b.serverTs!!) - 1, header.localTs)
        // The rest arrives from another provider: the marker goes, the header names the latest provider and only moves earlier.
        val r2 = importer.importPart(ctx, listOf(entry(a)))
        importer.updateMarkers(GRP, r2.oldestLocalTs, own = false, providerName = "Kamal")
        assertNull(messages.byClientMsgId(HistoryMarkers.historyId(GRP)))
        assertEquals("History shared by Kamal", messages.byClientMsgId(HistoryMarkers.sharedId(GRP))!!.body)
        assertEquals(ms(a.serverTs!!) - 1, messages.byClientMsgId(HistoryMarkers.sharedId(GRP))!!.localTs)
    }
}
