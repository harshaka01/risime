package ai.risicloud.risime.data

import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.mls.DeviceRef
import ai.risicloud.risime.data.mls.EncryptionHold
import ai.risicloud.risime.data.mls.FakeMlsEngine
import ai.risicloud.risime.data.mls.GroupRef
import ai.risicloud.risime.net.Event
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.dmConversationId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat-list clarity item 3 (Harsha 2026-10-12) through the real outbox: a 1:1 message waits for encryption
 * ("Waiting for encryption…"), goes out encrypted once ready, never in plaintext on its own; only the explicit
 * "Send without encryption" sends plaintext (marked ⚠). The held message survives a restart (rule 9).
 */
class EncryptionHoldEngineTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val peer = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val conv = dmConversationId(me, peer)

    private val messages = FakeMessageDao()
    private val sync = FakeSyncDao()
    private val realtime = FakeRealtime()
    private val mls = FakeMlsEngine(me, "d-me")
    private var now = 1_000L
    private var ids = 0
    private val repairs = mutableListOf<String>()

    private fun TestScope.engine(engine: () -> ai.risicloud.risime.data.mls.MlsEngine? = { mls }, hold: Boolean = false) = ChatEngine(
        messages = messages, sync = sync,
        tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
        scope = this, realtime = { realtime }, meId = { me }, clock = { now + testScheduler.currentTime }, newClientMsgId = { "c${++ids}" },
        mlsEngine = engine, onDmNeedsRepair = { repairs += it }, plaintextHold = { hold },
    )

    private fun row(id: String) = messages.rows.getValue(id)

    @Test fun noGroupYetHoldsTheMessageAndNeverSendsPlaintextOnItsOwn() = runTest {
        val e = engine()
        val id = e.sendText(peer, "hi")!!
        advanceTimeBy(1_000)
        assertEquals("PENDING", row(id).status)
        assertEquals(ChatEngine.E2EE_WAITING, row(id).failReason)
        assertTrue("an upgrade/repair was asked for", repairs.isNotEmpty() && repairs.all { it == conv })
        // 30 s later: still held, now offering "Send without encryption"; nothing went out in plaintext.
        advanceTimeBy(EncryptionHold.HOLD_MS + 5_000)
        now += 60 * 60_000L // an hour later, a flush (onLive, a new send) still never sends it in plaintext
        e.flushOutbox()
        assertEquals("PENDING", row(id).status)
        assertEquals(ChatEngine.E2EE_WAITING_LONG, row(id).failReason)
        assertTrue("no plaintext msg:send", realtime.sent.isEmpty())
        assertTrue(realtime.sentEncrypted.isEmpty())
    }

    @Test fun whenTheGroupArrivesTheHeldMessageGoesOutEncrypted() = runTest {
        val e = engine()
        val id = e.sendText(peer, "hi")!!
        advanceTimeBy(1_000)
        assertEquals(ChatEngine.E2EE_WAITING, row(id).failReason)
        mls.groups[conv] = GroupRef(conv, 1, 1) // the Welcome / our own epoch-0 commit
        advanceTimeBy(EncryptionHold.RECHECK_MS + 100)
        assertEquals("SENT", row(id).status)
        assertNull(row(id).failReason)
        assertEquals(MessageEntity.E2EE_YES, row(id).e2ee)
        assertEquals(listOf(id), realtime.sentEncrypted.map { it.clientMsgId })
        assertTrue(realtime.sent.isEmpty())
    }

    @Test fun onlyTheExplicitTapSendsPlaintextAndMarksItNotEncrypted() = runTest {
        val e = engine()
        val held = e.sendText(peer, "first")!!
        val other = e.sendText(peer, "second")!!
        advanceTimeBy(EncryptionHold.HOLD_MS + 5_000)
        assertEquals(ChatEngine.E2EE_WAITING_LONG, row(held).failReason)
        assertTrue(e.sendWithoutEncryption(held))
        advanceTimeBy(1_000)
        assertEquals("SENT", row(held).status)
        assertEquals(MessageEntity.E2EE_PLAIN, row(held).e2ee)
        assertTrue(row(held).notEncrypted)
        assertEquals("only the tapped message went in plaintext", listOf(held), realtime.sent.map { it.clientMsgId })
        assertEquals("PENDING", row(other).status)
        assertTrue(realtime.sentEncrypted.isEmpty())
        // A sent message can't be switched any more.
        assertTrue(!e.sendWithoutEncryption(held))
    }

    @Test fun aPeersNewPhoneHoldsMessagesUntilItIsInTheGroup() = runTest {
        // The reinstall case: B holds the DM group, A's new phone was announced (mls_membership added) but not added yet.
        mls.groups[conv] = GroupRef(conv, 1, 7)
        mls.memberLists[conv] = mutableListOf(DeviceRef(me, "d-me"), DeviceRef(peer, "d-peer-old"))
        val e = engine()
        e.onMembershipAnnounced(conv, "d-peer-new", "added")
        val id = e.sendText(peer, "hi")!!
        advanceTimeBy(1_000)
        assertEquals("held for the new phone", ChatEngine.E2EE_WAITING, row(id).failReason)
        assertTrue(realtime.sentEncrypted.isEmpty() && realtime.sent.isEmpty())
        mls.memberLists.getValue(conv) += DeviceRef(peer, "d-peer-new") // the commit that adds it
        advanceTimeBy(EncryptionHold.RECHECK_MS + 100)
        assertEquals("SENT", row(id).status)
        assertEquals(MessageEntity.E2EE_YES, row(id).e2ee)
        assertEquals(listOf(id), realtime.sentEncrypted.map { it.clientMsgId })
        assertTrue(realtime.sent.isEmpty())
    }

    @Test fun aHeldMessageSurvivesARestartAndStillGoesOutEncrypted() = runTest {
        val first = engine()
        val id = first.sendText(peer, "hi")!!
        advanceTimeBy(1_000)
        assertEquals(ChatEngine.E2EE_WAITING, row(id).failReason)
        // "Restart": a new engine over the same database (the row is in Room, not in memory).
        mls.groups[conv] = GroupRef(conv, 1, 1)
        val second = engine()
        second.flushOutbox()
        assertEquals("SENT", row(id).status)
        assertEquals(MessageEntity.E2EE_YES, row(id).e2ee)
        assertEquals(1, messages.rows.size)
    }

    @Test fun whileTheCoreIsStillOpeningA1to1MessageWaitsToo() = runTest {
        val e = engine(engine = { null }, hold = true)
        val id = e.sendText(peer, "hi")!!
        advanceTimeBy(1_000)
        assertEquals(ChatEngine.E2EE_WAITING, row(id).failReason)
        assertTrue(realtime.sent.isEmpty())
    }

    @Test fun anAppWithoutMlsSendsAsBeforeButMarksItNotEncrypted() = runTest {
        val e = engine(engine = { null }, hold = false)
        val id = e.sendText(peer, "hi")!!
        advanceTimeBy(1_000)
        assertEquals("SENT", row(id).status)
        assertEquals(MessageEntity.E2EE_PLAIN, row(id).e2ee)
    }

    @Test fun anIncomingPlaintextMessageIsMarkedNotEncrypted() = runTest {
        val e = engine()
        val ev = ProtocolJson.decodeFromString<Event>(
            """{"event_id":"e1","kind":"message","data":{"message_id":"3f2b1c00-a5f1-11f1-8000-000000000001","client_msg_id":"x1",
            "conversation_id":"$conv","from":"$peer","to":"$me","body":"hi","server_ts":"2026-10-12T13:35:12.000Z"}}""",
        )
        e.onEvents(listOf(ev))
        assertEquals(MessageEntity.E2EE_PLAIN, row("x1").e2ee)
    }
}
