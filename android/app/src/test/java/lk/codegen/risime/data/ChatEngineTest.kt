package lk.codegen.risime.data

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.MsgSendReply
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.realtime.PushResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEngineTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val peer = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val conv = dmConversationId(me, peer)

    private val messages = FakeMessageDao()
    private val sync = FakeSyncDao()
    private val behaviourDao = FakeBehaviourDao()
    private val realtime = FakeRealtime()
    private var now = 1_000L
    private var ids = 0

    private fun TestScope.engine() = ChatEngine(
        messages = messages,
        sync = sync,
        tx = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        },
        scope = this,
        realtime = { realtime },
        meId = { me },
        clock = { now++ },
        newClientMsgId = { "c${++ids}" },
        behaviour = BehaviourLog(behaviourDao, { "salt" }, { now }),
    )

    private fun msgEvent(eventId: String, messageId: String, clientMsgId: String, from: String = peer, to: String = me) =
        ProtocolJson.decodeFromString<Event>(
            """{"event_id":"$eventId","kind":"message","data":{"message_id":"$messageId","client_msg_id":"$clientMsgId",
            "conversation_id":"$conv","from":"$from","to":"$to","body":"hi $messageId","server_ts":"2026-10-06T08:15:30.456Z"}}""",
        )

    private fun statusEvent(eventId: String, clientMsgId: String, messageId: String, status: String) =
        ProtocolJson.decodeFromString<Event>(
            """{"event_id":"$eventId","kind":"status","data":{"message_id":"$messageId","client_msg_id":"$clientMsgId",
            "conversation_id":"$conv","status":"$status","by":"$peer","at":"2026-10-06T08:15:31.002Z"}}""",
        )

    @Test
    fun localMissedCallLineIsUnreadNeverAckedAndOnePerCallId() = runTest {
        val e = engine()
        assertTrue(e.insertLocalMissedCall(conv, peer, "call-1"))
        val row = messages.callLine(conv, "call-1")!!
        assertEquals("Missed voice call", row.body)
        assertEquals("DELIVERED", row.status)
        assertEquals("READ", row.ackedStatus)
        assertEquals(peer, row.from)
        assertTrue(!row.outgoing)
        // A second one (or the durable call_end's line later) for the same call id: no new row.
        assertTrue(!e.insertLocalMissedCall(conv, peer, "call-1"))
        assertEquals(1, messages.rows.values.count { it.callId == "call-1" })
    }

    @Test
    fun oneChatThatMustWaitNeverBlocksTheOthersAndFailsVisiblyAfter30s() = runTest {
        // nightly.19 P0: one DM whose sends could only be retried later kept every chat "pending".
        val other = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f"
        realtime.sendReplies = { m ->
            if (m.to == peer) PushResult.Rejected(lk.codegen.risime.net.AuthErrors.E2EE_REQUIRED)
            else PushResult.Ok(MsgSendReply("mid-${m.clientMsgId}", "dm:x", "2026-10-06T08:15:30.456Z"))
        }
        val e = engine()
        val stuck = e.sendText(peer, "to the broken DM")!!
        val later = e.sendText(peer, "second to the broken DM")!!
        val fine = e.sendText(other, "to a healthy chat")!!
        advanceTimeBy(1_000)
        assertEquals("the healthy chat is not blocked", "SENT", messages.rows[fine]!!.status)
        assertEquals("PENDING", messages.rows[stuck]!!.status)
        assertEquals("order kept inside the waiting chat", "PENDING", messages.rows[later]!!.status)
        assertTrue("the second message of the waiting chat was never pushed before the first", realtime.sent.none { it.clientMsgId == later })
        // Retries keep going; after 30 s it becomes "Not sent" with the reason (tap to retry).
        now += ChatEngine.OUTBOX_GIVE_UP_MS + 1
        advanceTimeBy(30_000)
        assertEquals("FAILED", messages.rows[stuck]!!.status)
        assertEquals(ChatEngine.E2EE_NOT_READY, messages.rows[stuck]!!.failReason)
    }

    @Test
    fun e2eeDmWithoutALocalGroupStaysPendingAndAsksForRepair() = runTest {
        // v1.16: the DM is e2ee on the server but this phone has no group: no "Not sent", a repair kick.
        realtime.sendReplies = { PushResult.Rejected(lk.codegen.risime.net.AuthErrors.E2EE_REQUIRED) }
        val repairs = mutableListOf<String>()
        val mls = lk.codegen.risime.data.mls.FakeMlsEngine(me, "d-me")
        val e = ChatEngine(
            messages = messages, sync = sync,
            tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
            scope = this, realtime = { realtime }, meId = { me }, clock = { now++ }, newClientMsgId = { "c${++ids}" },
            mlsEngine = { mls }, onDmNeedsRepair = { repairs += it },
        )
        val id = e.sendText(peer, "while this phone is re-added")!!
        now += ChatEngine.OUTBOX_GIVE_UP_MS + 1
        advanceTimeBy(60_000)
        assertEquals("PENDING", messages.rows[id]!!.status)
        assertTrue(repairs.isNotEmpty() && repairs.all { it == conv })
    }

    @Test
    fun sendInsertsPendingThenSentWithServerIds() = runTest {
        val e = engine()
        realtime.connected = false
        val id = e.sendText(peer, "  hello  ")!!
        advanceUntilIdle()
        assertEquals("PENDING", messages.rows[id]!!.status)
        assertEquals("hello", messages.rows[id]!!.body)
        assertEquals(conv, messages.rows[id]!!.conversationId)

        realtime.connected = true
        e.onLive()
        val row = messages.rows[id]!!
        assertEquals("SENT", row.status)
        assertEquals("mid-$id", row.messageId)
        assertEquals(id, realtime.sent.single().clientMsgId)
    }

    @Test
    fun outboxResendsInLocalTsOrderWithSameClientMsgIdAndNoDuplicates() = runTest {
        val e = engine()
        realtime.connected = false
        val a = e.sendText(peer, "one")!!
        val b = e.sendText(peer, "two")!!
        val c = e.sendText(peer, "three")!!
        advanceUntilIdle()
        // Reconnect: server dropped after the first send's reply was lost.
        realtime.connected = true
        var calls = 0
        realtime.sendReplies = { m ->
            calls++
            if (calls == 2) PushResult.Unavailable else PushResult.Ok(MsgSendReply("mid-${m.clientMsgId}", conv, "t"))
        }
        e.onLive()
        assertEquals(listOf(a, b), realtime.sent.map { it.clientMsgId })
        assertEquals("SENT", messages.rows[a]!!.status)
        assertEquals("PENDING", messages.rows[b]!!.status)
        e.onLive()
        assertEquals(listOf(a, b, b, c), realtime.sent.map { it.clientMsgId })
        assertTrue(messages.rows.values.all { it.status == "SENT" })
        assertEquals(3, messages.rows.size)
    }

    @Test
    fun rejectedSendBecomesFailedRateLimitedStaysPending() = runTest {
        val e = engine()
        realtime.sendReplies = { PushResult.Rejected("too_long") }
        val a = e.sendText(peer, "x")!!
        advanceUntilIdle()
        assertEquals("FAILED", messages.rows[a]!!.status)
        assertEquals("too_long", messages.rows[a]!!.failReason)

        realtime.sendReplies = { PushResult.Rejected("rate_limited") }
        val b = e.sendText(peer, "y")!!
        e.flushOutbox()
        assertEquals("PENDING", messages.rows[b]!!.status)
        realtime.sendReplies = { m -> PushResult.Ok(MsgSendReply("mid-${m.clientMsgId}", conv, "t")) }
        advanceUntilIdle() // retry after rateLimitRetryMs
        assertEquals("SENT", messages.rows[b]!!.status)
    }

    @Test
    fun retryFailedResendsWithSameIdAndDeleteOnlyFailed() = runTest {
        val e = engine()
        realtime.sendReplies = { PushResult.Rejected("bad_request") }
        val a = e.sendText(peer, "x")!!
        advanceUntilIdle()
        assertEquals("FAILED", messages.rows[a]!!.status)

        realtime.sendReplies = { m -> PushResult.Ok(MsgSendReply("mid-${m.clientMsgId}", conv, "t")) }
        assertTrue(e.retry(a))
        advanceUntilIdle()
        assertEquals("SENT", messages.rows[a]!!.status)
        assertNull(messages.rows[a]!!.failReason)
        assertEquals(listOf(a, a), realtime.sent.map { it.clientMsgId })
        // Only FAILED can be retried or deleted.
        assertEquals(false, e.retry(a))
        assertEquals(false, e.deleteFailed(a))
        assertTrue(messages.rows.containsKey(a))

        realtime.sendReplies = { PushResult.Rejected("unknown_recipient") }
        val b = e.sendText(peer, "y")!!
        advanceUntilIdle()
        assertTrue(e.deleteFailed(b))
        assertNull(messages.rows[b])
        assertEquals(false, e.retry("nope"))
    }

    @Test
    fun emptyOrTooLongBodyIsNotQueued() = runTest {
        val e = engine()
        assertNull(e.sendText(peer, "   "))
        assertNull(e.sendText(peer, "a".repeat(16 * 1024 + 1))) // §11.1 byte cap; graphemes are the server's call
        assertTrue(messages.rows.isEmpty())
    }

    @Test
    fun incomingIsDedupedAckedDeliveredAndCursorAdvances() = runTest {
        val e = engine()
        val ev = msgEvent("e1", "m1", "x1")
        e.onEvents(listOf(ev, ev))
        e.onEvents(listOf(msgEvent("e1b", "m1", "x1"))) // same message under another event id
        assertEquals(1, messages.rows.size)
        assertEquals("DELIVERED", messages.rows["x1"]!!.status)
        assertEquals(listOf(listOf("m1") to "delivered"), realtime.acks)
        assertEquals("DELIVERED", messages.rows["x1"]!!.ackedStatus)
        assertEquals("e1b", sync.cursor())
    }

    @Test
    fun readAckWhenConversationShownAndRetriedAfterReconnect() = runTest {
        val e = engine()
        realtime.connected = false
        e.onEvents(listOf(msgEvent("e1", "m1", "x1"), msgEvent("e2", "m2", "x2")))
        e.markConversationRead(conv)
        assertTrue(realtime.acks.isEmpty())
        realtime.connected = true
        e.onLive()
        assertEquals(listOf(listOf("m1", "m2") to "read"), realtime.acks)
        e.flushAcks()
        assertEquals(1, realtime.acks.size) // nothing left to ack
    }

    @Test
    fun statusEventsMoveOutgoingForwardOnly() = runTest {
        val e = engine()
        val id = e.sendText(peer, "hello")!!
        advanceUntilIdle()
        e.onEvents(listOf(statusEvent("s1", id, "mid-$id", "read")))
        assertEquals("READ", messages.rows[id]!!.status)
        e.onEvents(listOf(statusEvent("s2", id, "mid-$id", "delivered")))
        assertEquals("READ", messages.rows[id]!!.status)
        assertEquals("s2", sync.cursor())
    }

    @Test
    fun statusBeforeSendReplyFillsMessageId() = runTest {
        val e = engine()
        realtime.connected = false
        val id = e.sendText(peer, "hello")!!
        advanceUntilIdle()
        e.onEvents(listOf(statusEvent("s1", id, "m9", "delivered")))
        assertEquals("DELIVERED", messages.rows[id]!!.status)
        assertEquals("m9", messages.rows[id]!!.messageId)
        realtime.connected = true
        e.onLive()
        assertTrue(realtime.sent.isEmpty()) // no longer pending, never resent
    }

    @Test
    fun behaviourLogRecordsLengthAndReplyLatencyOnly() = runTest {
        val e = engine()
        e.onEvents(listOf(msgEvent("e1", "m1", "x1")))
        now += 5_000
        e.sendText(peer, "reply!")
        advanceUntilIdle()
        val rows = behaviourDao.rows
        assertEquals(listOf("message_sent", "reply_latency_ms"), rows.map { it.type })
        assertEquals("""{"length":6}""", rows[0].metaJson)
        assertEquals(BehaviourLog.hash(peer, "salt"), rows[0].peerHash)
        assertTrue(rows.none { it.metaJson!!.contains("reply!") })
        assertTrue(rows[1].metaJson!!.contains("\"ms\":"))
    }
}
