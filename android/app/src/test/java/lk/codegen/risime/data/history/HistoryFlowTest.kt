package lk.codegen.risime.data.history

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.HistoryMarkers
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.DeletedIdEntity
import lk.codegen.risime.data.db.HistoryProvideEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.data.history.HistoryDevice.Companion.requestEvent
import lk.codegen.risime.data.history.HistoryDevice.Companion.shareEvent
import lk.codegen.risime.data.history.HistoryDevice.Companion.statusEvent
import lk.codegen.risime.data.history.HistoryDevice.Companion.str
import lk.codegen.risime.data.history.HistoryFixtures.GRP
import lk.codegen.risime.data.history.HistoryFixtures.ME
import lk.codegen.risime.data.history.HistoryFixtures.PEER
import lk.codegen.risime.data.history.HistoryFixtures.THIRD
import lk.codegen.risime.data.history.HistoryFixtures.gap
import lk.codegen.risime.data.history.HistoryFixtures.ms
import lk.codegen.risime.data.history.HistoryFixtures.msg
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.HistoryState
import lk.codegen.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §17 end to end through two app instances (ChatEngine + MlsPipeline + HistoryManager over fakes):
 * request, consent (option A; member always asks), export, seal, upload, deliver, open, import, ack.
 */
class HistoryFlowTest {
    private val dm = dmConversationId(ME, PEER)
    private val store = FakeBlobStore()
    private var now = ms("2026-10-06T12:00:00.000Z")

    private fun TestScope.dev(user: String, device: String) =
        HistoryDevice(user, device, this, StandardTestDispatcher(testScheduler), store, { now++ }, listOf(dm, GRP), mapOf(THIRD to "Chamari", PEER to "Kamal"))

    /** The provider's rows, and the requester's matching gap rows (recorded before the request). */
    private fun seed(provider: HistoryDevice, requester: HistoryDevice, conv: String, rows: List<MessageEntity>) {
        rows.forEach { provider.messages.rows[it.clientMsgId] = it }
        rows.forEach { requester.dao.gapRows.value = requester.dao.gapRows.value + (it.messageId!! to gap(it, createdAt = now - 1000)) }
        requester.messages.rows[HistoryMarkers.historyId(conv)] = HistoryMarkers.row(conv, SystemLine.HISTORY_GAP, rows.maxOf { it.serverTs!! }, 0)
    }

    private suspend fun TestScope.share(provider: HistoryDevice, requester: HistoryDevice, conv: String, consent: String, sources: String = "own"): String {
        val out = requester.manager.request(conv, sources)
        advanceUntilIdle()
        assertTrue("$out", out is RequestOutcome.Sent)
        val rid = (out as RequestOutcome.Sent).requestId
        provider.deliver(Event.KIND_HISTORY_REQUEST, requestEvent(requester.pushed("history:request"), requester.user, requester.device, provider.device, consent))
        advanceUntilIdle()
        return rid
    }

    private suspend fun TestScope.export(provider: HistoryDevice, requester: HistoryDevice, rid: String, conv: String) {
        assertEquals(HistoryProvideEntity.EXPORTING, provider.dao.provide(rid)!!.state)
        assertEquals(ExportOutcome.DONE, provider.manager.runExport(rid))
        val delivers = provider.realtime.historyPushes.filter { it.first == "history:deliver" && it.second.str("request_id") == rid }
        requester.deliver(Event.KIND_HISTORY_STATUS, statusEvent(rid, conv, HistoryState.ACCEPTED, requester.device, provider.user to provider.device))
        delivers.forEach { (_, d) -> requester.deliver(Event.KIND_HISTORY_SHARE, shareEvent(d, conv, provider.user, provider.device, requester.device)) }
        advanceUntilIdle()
    }

    @Test fun aNewInstallAsksForTheMissingHistoryByItselfOncePerChat() = runTest {
        // Decision 055: a phone whose chats are gone (new install) asks every chat with a gap, once.
        val old = dev(ME, "d-old")
        val new = dev(ME, "d-new")
        seed(old, new, dm, listOf(msg(dm, PEER, "2026-10-02T09:00:00.000Z", "from Kamal")))
        assertEquals(1, new.manager.autoRequestAll())
        advanceUntilIdle()
        assertEquals("any", new.pushed("history:request").str("sources"))
        // Asked before (open or closed): never again by itself.
        assertEquals(0, new.manager.autoRequestAll())
    }

    @Test fun ownNewPhoneAsksOnceThenSharesAutomatically() = runTest {
        val old = dev(ME, "d-old")
        val new = dev(ME, "d-new")
        val a = msg(dm, PEER, "2026-10-02T09:00:00.000Z", "from Kamal")
        val b = msg(dm, ME, "2026-10-02T09:01:00.000Z", "from me")
        val call = msg(dm, PEER, "2026-10-02T09:02:00.000Z", "Missed voice call", kind = MessageEntity.KIND_CALL)
            .copy(systemJson = """{"v":1,"type":"call_end","call_id":"5b0c7e2a-3d4f-4a1b-9c8d-7e6f5a4b3c2d","reason":"timeout"}""")
        seed(old, new, dm, listOf(a, b, call))

        val rid = share(old, new, dm, "own")
        // Option A: the first request from this new phone asks.
        val ask = old.dao.provide(rid)!!
        assertEquals(HistoryProvideEntity.ASK, ask.state)
        assertTrue(ask.own)
        assertEquals(listOf(rid), old.prompts.last().map { it.requestId })
        old.manager.answer(rid, allow = true)
        advanceUntilIdle()
        assertEquals("accept", old.pushed("history:respond").str("decision"))
        export(old, new, rid, dm)

        assertEquals(listOf("from Kamal", "from me", "Missed voice call"), new.messages.rows.values.filter { !it.system }.sortedBy { it.localTs }.map { it.body })
        val imported = new.messages.byMessageId(a.messageId!!)!!
        assertEquals(a.clientMsgId, imported.clientMsgId)
        assertEquals(MessageEntity.ORIGIN_OWN_DEVICE, imported.origin)
        assertEquals(MessageStatus.READ.name, imported.status)
        assertEquals(MessageStatus.READ.name, imported.ackedStatus) // never acked, never unread
        assertEquals(ms(a.serverTs!!), imported.localTs)
        assertTrue(new.messages.byMessageId(b.messageId!!)!!.outgoing)
        assertEquals(MessageEntity.KIND_CALL, new.messages.byMessageId(call.messageId!!)!!.kind) // own share: call lines go
        assertTrue(new.dao.gaps(dm).isEmpty())
        // The gap marker goes; the block header says where it came from.
        assertNull(new.messages.byClientMsgId(HistoryMarkers.historyId(dm)))
        val header = new.messages.byClientMsgId(HistoryMarkers.sharedId(dm))!!
        assertEquals(SystemLine.HISTORY_RESTORED_TEXT, header.body)
        assertEquals(ms(a.serverTs!!) - 1, header.localTs)
        assertEquals("imported", new.pushed("history:ack").str("result"))
        assertEquals(1, old.sharedQuietly)

        // Done: rsk forgotten.
        new.deliver(Event.KIND_HISTORY_STATUS, statusEvent(rid, dm, HistoryState.DONE, new.device))
        advanceUntilIdle()
        assertNull(new.mls.historyPublicKey(rid))
        assertTrue(new.dao.request(rid)!!.closed)

        // A second request from the same new phone (same key) is answered automatically.
        val c = msg(dm, PEER, "2026-10-03T09:00:00.000Z", "later gap")
        seed(old, new, dm, listOf(c))
        val rid2 = share(old, new, dm, "own")
        assertEquals(HistoryProvideEntity.EXPORTING, old.dao.provide(rid2)!!.state)
        export(old, new, rid2, dm)
        assertNotNull(new.messages.byMessageId(c.messageId!!))

        // A re-registered device (new signature key) asks again.
        old.mls.sigKeyOf = { "rotated".toByteArray() }
        val d = msg(dm, PEER, "2026-10-04T09:00:00.000Z", "third gap")
        seed(old, new, dm, listOf(d))
        new.deliver(Event.KIND_HISTORY_STATUS, statusEvent(rid2, dm, HistoryState.DONE, new.device))
        advanceUntilIdle()
        val rid3 = share(old, new, dm, "own")
        assertEquals(HistoryProvideEntity.ASK, old.dao.provide(rid3)!!.state)
    }

    @Test fun aMemberAlwaysAsksSharesOnlyItsIntervalAndIsLabelled() = runTest {
        val c = dev(THIRD, "d-c")
        val b = dev(ME, "d-b-new")
        val before = msg(GRP, THIRD, "2026-09-10T00:00:00.000Z", "before B joined")
        c.messages.rows[before.clientMsgId] = before
        c.messages.rows["sys:add"] = HistoryMarkers.line("sys:add", GRP, "added", "x", ms("2026-09-15T00:00:00.000Z"))
            .copy(systemJson = SystemLine("added", THIRD, listOf(ME)).encode())
        val m1 = msg(GRP, THIRD, "2026-09-20T00:00:00.000Z", "C in B's interval")
        val m2 = msg(GRP, PEER, "2026-09-21T00:00:00.000Z", "A in B's interval")
        seed(c, b, GRP, listOf(m1, m2))
        val rid = share(c, b, GRP, "member", sources = "any")
        assertEquals(HistoryProvideEntity.ASK, c.dao.provide(rid)!!.state)
        assertFalse(c.dao.provide(rid)!!.own)
        c.manager.answer(rid, allow = true)
        advanceUntilIdle()
        export(c, b, rid, GRP)
        assertNull(b.messages.byMessageId(before.messageId!!))
        assertEquals(MessageEntity.ORIGIN_SHARED, b.messages.byMessageId(m2.messageId!!)!!.origin)
        assertEquals(THIRD, b.messages.byMessageId(m2.messageId!!)!!.sharedBy)
        assertEquals(SystemLine.historySharedText("Chamari"), b.messages.byClientMsgId(HistoryMarkers.sharedId(GRP))!!.body)
        // The bundle carried only the interval's messages (count from the share envelope).
        assertEquals(2, b.dao.parts(rid).single().count)
    }

    @Test fun aLyingConsentHintStillAsksAndTheMembersSwitchDeclines() = runTest {
        val c = dev(THIRD, "d-c")
        val b = dev(ME, "d-b-new")
        seed(c, b, GRP, listOf(msg(GRP, THIRD, "2026-09-20T00:00:00.000Z", "x")))
        // The server says "own", the MLS sender is another user: member, always ask.
        val rid = share(c, b, GRP, "own", sources = "any")
        assertFalse(c.dao.provide(rid)!!.own)
        assertEquals(HistoryProvideEntity.ASK, c.dao.provide(rid)!!.state)
        c.manager.answer(rid, allow = false)
        advanceUntilIdle()
        assertEquals("decline", c.pushed("history:respond").str("decision"))
        // Settings → Privacy off: declined without a prompt.
        val c2 = dev(THIRD, "d-c2").also { it.membersAsk = false }
        b.deliver(Event.KIND_HISTORY_STATUS, statusEvent(rid, GRP, HistoryState.UNAVAILABLE, b.device))
        advanceUntilIdle()
        val rid2 = share(c2, b, GRP, "member", sources = "any")
        assertEquals(HistoryProvideEntity.DECLINED, c2.dao.provide(rid2)!!.state)
        assertTrue(c2.prompts.none { it.isNotEmpty() })
    }

    @Test fun deletedMessagesNeverComeBack() = runTest {
        val old = dev(ME, "d-old")
        val new = dev(ME, "d-new")
        val kept = msg(dm, PEER, "2026-10-02T09:00:00.000Z", "kept")
        val tomb = msg(dm, PEER, "2026-10-02T09:01:00.000Z", "", seq = 1).copy(kind = MessageEntity.KIND_DELETED)
        val forMe = msg(dm, PEER, "2026-10-02T09:02:00.000Z", "deleted for me on the new phone", seq = 2)
        val lateDelete = msg(dm, PEER, "2026-10-02T09:03:00.000Z", "deleted after the export", seq = 3)
        seed(old, new, dm, listOf(kept, tomb, forMe, lateDelete))
        new.deletes.putDeletedId(DeletedIdEntity(forMe.messageId!!, dm, ME, false, null, "me", 0))
        val rid = share(old, new, dm, "own")
        old.manager.answer(rid, allow = true)
        advanceUntilIdle()
        assertEquals(ExportOutcome.DONE, old.manager.runExport(rid))
        // A delete for everyone reaches the new phone before the part is imported (its hidden tombstone).
        new.deletes.putDeletedId(DeletedIdEntity(lateDelete.messageId!!, dm, PEER, false, "2026-10-02T09:04:00.000Z", "everyone", 0))
        old.realtime.historyPushes.filter { it.first == "history:deliver" }.forEach { (_, d) -> new.deliver(Event.KIND_HISTORY_SHARE, shareEvent(d, dm, ME, "d-old", "d-new")) }
        advanceUntilIdle()
        assertEquals("kept", new.messages.byMessageId(kept.messageId!!)!!.body)
        assertNull(new.messages.byMessageId(tomb.messageId!!)) // never exported
        assertNull(new.messages.byMessageId(forMe.messageId!!)) // a `me` hidden tombstone blocks
        assertEquals(MessageEntity.KIND_DELETED, new.messages.byMessageId(lateDelete.messageId!!)!!.kind) // authorised: a tombstone
    }
}
