package lk.codegen.risime.data

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.GroupRef
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.realtime.PushResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §11.2 reaction state, outbox and events (plaintext and e2ee). */
class ReactionsTest {
    private val me = "aaaa0000-0000-4000-8000-000000000001"
    private val peer = "bbbb0000-0000-4000-8000-000000000002"
    private val conv = dmConversationId(me, peer)
    private val target = "c1a2b3c4-a0b1-11f0-8000-0242ac120002"

    private val dao = FakeReactionDao()
    private val realtime = FakeRealtime()
    private var ids = 0
    private var mls: FakeMlsEngine? = null

    private fun TestScope.engine() = ChatEngine(
        messages = FakeMessageDao(), sync = FakeSyncDao(),
        tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
        scope = this, realtime = { realtime }, meId = { me },
        clock = { testScheduler.currentTime }, newClientMsgId = { "r${++ids}" },
        mls = MlsPipeline({ mls }, FakeMlsPendingDao()), mlsEngine = { mls },
        reactionsDao = dao,
    )

    private fun shown(reactor: String = me, emoji: String = "👍") = dao.rows[listOf(conv, target, reactor, emoji)]?.op

    private var n = 0
    private fun reactionEvent(from: String, emoji: String, op: String, ts: String, mid: String = "m${++n}", cmid: String = "x$n") = Event(mid, "reaction", buildJsonObject {
        put("message_id", mid); put("client_msg_id", cmid); put("conversation_id", conv); put("from", from); put("to", if (from == me) peer else me)
        put("target", target); put("emoji", emoji); put("op", op); put("server_ts", ts)
    })

    @Test fun orderingIsByServerTimestampThenMessageIdWhateverTheSyncOrder() = runTest {
        val e = engine()
        // Arrive out of order: remove (later) first, then add (earlier).
        e.onEvents(listOf(reactionEvent(peer, "👍", "remove", "2026-10-06T08:00:02.000Z"), reactionEvent(peer, "👍", "add", "2026-10-06T08:00:01.000Z")))
        assertEquals("remove", shown(peer))
        // Same timestamp: the larger message_id wins on every device.
        e.onEvents(listOf(reactionEvent(peer, "❤️", "add", "2026-10-06T08:00:05.000Z", mid = "z1"), reactionEvent(peer, "❤️", "remove", "2026-10-06T08:00:05.000Z", mid = "a1")))
        assertEquals("add", shown(peer, "❤️"))
        assertEquals(listOf(ReactionChip("❤️", 1, false, listOf(peer))), chipsFor(dao.rows.values.toList(), me))
    }

    @Test fun debounceSendsOnlyTheFinalStateWithFreshIdsAndConfirms() = runTest {
        val e = engine()
        e.react(peer, target, "👍", "add")
        advanceTimeBy(200); e.react(peer, target, "👍", "remove")
        advanceTimeBy(200); e.react(peer, target, "👍", "add")
        assertEquals("add", shown()) // shown at once
        advanceUntilIdle()
        assertEquals(1, realtime.sentReactions.size) // only the final op
        val sent = realtime.sentReactions.single()
        assertEquals("add", sent.reaction.op)
        assertEquals(target, sent.reaction.target)
        val row = dao.rows.values.single()
        assertFalse(row.pending)
        assertEquals("rid-${sent.clientMsgId}", row.confirmedMessageId)
        // Tap again → remove with a NEW client_msg_id.
        e.react(peer, target, "👍", "remove")
        advanceUntilIdle()
        assertEquals(2, realtime.sentReactions.size)
        assertTrue(realtime.sentReactions[0].clientMsgId != realtime.sentReactions[1].clientMsgId)
        assertEquals("remove", shown())
        // add → remove inside the debounce of a confirmed "remove": nothing to send.
        e.react(peer, target, "👍", "add"); e.react(peer, target, "👍", "remove")
        advanceUntilIdle()
        assertEquals(2, realtime.sentReactions.size)
        assertFalse(dao.rows.values.single().pending)
    }

    @Test fun unknownTargetRevertsAndARetryReusesItsId() = runTest {
        val e = engine()
        realtime.reactionReplies = { PushResult.Rejected("unknown_target") }
        e.react(peer, target, "😂", "add")
        advanceUntilIdle()
        assertEquals("remove", shown(emoji = "😂")) // reverted
        assertFalse(dao.rows.values.single().pending)
        // Offline → stays pending with its id; the retry reuses it.
        realtime.reactionReplies = { m -> PushResult.Ok(lk.codegen.risime.net.MsgSendReply("rid", conv, "2026-10-06T09:00:00.000Z")) }
        realtime.connected = false
        e.react(peer, target, "🙏", "add")
        advanceUntilIdle()
        val id = dao.rows[listOf(conv, target, me, "🙏")]!!.pendingClientMsgId
        realtime.connected = true
        e.onLive()
        advanceUntilIdle()
        assertEquals(id, realtime.sentReactions.last().clientMsgId)
        assertEquals("add", shown(emoji = "🙏"))
    }

    @Test fun ownEchoSettlesAndReactionsToReactionsAreIgnored() = runTest {
        val e = engine()
        realtime.connected = false
        e.react(peer, target, "😮", "add")
        advanceUntilIdle()
        val id = dao.rows.values.single().pendingClientMsgId!!
        // My plaintext reaction event comes back through my inbox (same event_id as the peer's copy).
        e.onEvents(listOf(reactionEvent(me, "😮", "add", "2026-10-06T08:10:00.000Z", mid = "own1", cmid = id)))
        assertFalse(dao.rows.values.single().pending)
        // Someone reacting to that reaction's message_id: ignored.
        val r2r = Event("m99", "reaction", buildJsonObject {
            put("message_id", "m99"); put("client_msg_id", "x99"); put("conversation_id", conv); put("from", peer); put("to", me)
            put("target", "own1"); put("emoji", "👍"); put("op", "add"); put("server_ts", "2026-10-06T08:11:00.000Z")
        })
        e.onEvents(listOf(r2r))
        assertNull(dao.rows[listOf(conv, "own1", peer, "👍")])
    }

    @Test fun e2eeReactionsUseTheEnvelopeBothWays() = runTest {
        val f = FakeMlsEngine(me, "d-me").also { it.groups[conv] = GroupRef(conv, 1, 2) }
        mls = f
        val e = engine()
        e.react(peer, target, "❤️", "add")
        advanceUntilIdle()
        assertTrue(realtime.sentReactions.isEmpty())
        val sent = realtime.sentEncrypted.single()
        val plain = java.util.Base64.getDecoder().decode(sent.ciphertext).decodeToString().split('|', limit = 4)[3]
        assertEquals(MlsPayload.Decoded.Reaction(target, "❤️", "add"), MlsPayload.decode(plain.toByteArray()))
        // Incoming e2ee reaction from the peer.
        val ct = FakeMlsEngine.b64("1|2|$peer/d-peer|" + MlsPayload.reaction(target, "👍", "add").decodeToString())
        e.onEvents(listOf(Event("ev1", "message", buildJsonObject {
            put("message_id", "m-e1"); put("client_msg_id", "c-e1"); put("conversation_id", conv); put("from", peer); put("to", me)
            put("from_device", "d-peer"); put("ciphertext", ct); put("generation", 1); put("epoch", 2); put("server_ts", "2026-10-06T08:30:00.000Z")
        })))
        assertEquals("add", shown(peer))
    }
}
