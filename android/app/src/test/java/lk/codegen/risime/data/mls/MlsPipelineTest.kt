package lk.codegen.risime.data.mls

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.BehaviourLog
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeBehaviourDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeRealtime
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.MsgSendReply
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.realtime.PushResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The e2ee sync pipeline and outbox against the fake MLS core (phase A, decision 033). */
class MlsPipelineTest {
    private val me = "aaaa0000-0000-4000-8000-000000000001"
    private val peer = "bbbb0000-0000-4000-8000-000000000002"
    private val myDev = "d-me"
    private val peerDev = "d-peer"
    private val conv = dmConversationId(me, peer)

    private val messages = FakeMessageDao()
    private val sync = FakeSyncDao()
    private val pending = FakeMlsPendingDao()
    private val realtime = FakeRealtime()
    private val mls = FakeMlsEngine(me, myDev)
    private val memberships = mutableListOf<MembershipAction>()
    private var seq = 0L
    private var ids = 0
    private var catchUps = mutableListOf<String>()
    private var onCatchUp: () -> Unit = {}

    private fun TestScope.engine(withMls: Boolean = true, scope: kotlinx.coroutines.CoroutineScope = this) = ChatEngine(
        messages = messages, sync = sync,
        tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
        scope = scope, realtime = { realtime }, meId = { me },
        clock = { 1_000L + seq }, newClientMsgId = { "c${++ids}" },
        behaviour = BehaviourLog(FakeBehaviourDao(), { "salt" }, { 0L }),
        mls = MlsPipeline({ if (withMls) mls else null }, pending, { ++seq }, { memberships += it }, { 7_000 }),
        mlsEngine = { if (withMls) mls else null },
        catchUp = { c -> catchUps += c; onCatchUp() },
    )

    private var evN = 0
    private fun ev(kind: String, data: JsonObject) = Event("e${++evN}", kind, data)

    private fun msg(epoch: Long, text: String, gen: Long = 1, fromDev: String = peerDev, from: String = peer, ts: String = "2026-10-06T08:00:00.000Z") = ev("message", buildJsonObject {
        put("message_id", "m${evN + 1}"); put("client_msg_id", "cm${evN + 1}"); put("conversation_id", conv)
        put("from", from); put("to", if (from == me) peer else me); put("from_device", fromDev)
        put("ciphertext", FakeMlsEngine.ciphertext(gen, epoch, from, fromDev, text)); put("generation", gen); put("epoch", epoch)
        put("server_ts", ts)
    })

    private fun commit(epoch: Long, note: String = "x", fromDev: String = peerDev, gen: Long = 1) = ev("mls_commit", buildJsonObject {
        put("conversation_id", conv); put("generation", gen); put("epoch", epoch); put("commit", FakeMlsEngine.b64("commit|$note")); put("from_device", fromDev)
    })

    private fun welcome(epoch: Long, to: List<String>, gen: Long = 1) = ev("mls_welcome", buildJsonObject {
        put("conversation_id", conv); put("generation", gen); put("epoch", epoch); put("welcome", FakeMlsEngine.b64("welcome|$epoch"))
        put("to_devices", buildJsonArray { to.forEach { add(JsonPrimitive(it)) } })
    })

    private fun bodies() = messages.rows.values.filter { !it.outgoing && !it.system }.sortedBy { it.localTs }.map { it.body }

    private fun markers() = messages.rows.values.filter { it.system }.map { it.clientMsgId }

    private val gap = "sys:history:$conv"
    private val undecryptable = "sys:undecryptable:$conv"

    @Test fun welcomeThenMessagesDecryptIntoTheSameTransactionAsTheCursor() = runTest {
        val e = engine()
        e.onEvents(listOf(welcome(1, listOf("someone-else")))) // not for this device
        assertNull(mls.group(conv))
        val w = welcome(1, listOf(myDev))
        val m = msg(1, "hello")
        e.onEvents(listOf(w, m))
        assertEquals(listOf("hello"), bodies())
        assertEquals(m.eventId, sync.last) // cursor moved with the insert
    }

    @Test fun outOfOrderMessagesWaitForTheirCommitAndReplayInOrder() = runTest {
        val e = engine()
        mls.groups[conv] = GroupRef(conv, 1, 1)
        val m2a = msg(2, "two-a"); val m2b = msg(2, "two-b")
        e.onEvents(listOf(m2a, m2b))
        assertTrue(bodies().isEmpty())
        assertEquals(2, pending.rows.size)
        assertEquals(m2b.eventId, sync.last) // the cursor still moves; the events are safe in mls_pending
        e.onEvents(listOf(commit(1)))
        assertEquals(listOf("two-a", "two-b"), bodies())
        assertTrue(pending.rows.isEmpty())
    }

    @Test fun commitsBeforeTheWelcomeAreParkedThenIgnoredBelowItsEpoch() = runTest {
        val e = engine()
        e.onEvents(listOf(commit(0, "create"), commit(1, "later"), welcome(2, listOf(myDev)), commit(2, "next")))
        assertEquals(listOf("next"), mls.processed) // epoch-0/1 commits are below the Welcome's epoch 2
        assertEquals(3L, mls.group(conv)!!.epoch)
        assertTrue(pending.rows.isEmpty())
    }

    @Test fun ownCommitsAndOwnMessagesAreSkippedAndSenderMismatchIsDropped() = runTest {
        val e = engine()
        mls.groups[conv] = GroupRef(conv, 1, 1)
        e.onEvents(listOf(commit(1, "mine", fromDev = myDev)))
        assertTrue(mls.processed.isEmpty())
        e.onEvents(listOf(msg(1, "from me", fromDev = myDev, from = me)))
        assertTrue(messages.rows.isEmpty())
        // Ciphertext from peerDev but the event claims another device: dropped.
        val forged = msg(1, "forged").let { ev ->
            Event(ev.eventId, ev.kind, JsonObject(ev.data + ("from_device" to JsonPrimitive("d-other"))))
        }
        e.onEvents(listOf(forged))
        assertEquals(listOf(undecryptable), markers()) // §13.3: never silent
        // My other device's message is mine (outgoing) and decrypts.
        e.onEvents(listOf(msg(1, "from my tablet", fromDev = "d-tablet", from = me)))
        assertEquals(listOf(true), messages.rows.values.filter { !it.system }.map { it.outgoing })
    }

    @Test fun staleGenerationDroppedNewerGenerationWaitsRemovedSelfDeletesTheGroup() = runTest {
        val e = engine()
        mls.groups[conv] = GroupRef(conv, 2, 1)
        e.onEvents(listOf(msg(1, "old gen", gen = 1)))
        assertTrue(bodies().isEmpty() && pending.rows.isEmpty())
        assertEquals(listOf(undecryptable), markers()) // a live stale generation is a visible loss
        e.onEvents(listOf(msg(1, "new gen", gen = 3)))
        assertEquals(1, pending.rows.size)
        e.onEvents(listOf(commit(1, "remove-me", gen = 2)))
        assertNull(mls.group(conv))
        e.onEvents(listOf(welcome(1, listOf(myDev), gen = 3)))
        assertEquals(listOf("new gen"), bodies())
    }

    @Test fun membershipNamedCommitterRule() = runTest {
        fun m(user: String, dev: String) = lk.codegen.risime.net.MlsMembershipEvent(conv, user, dev, "added")
        assertNull(membershipAction(m(me, myDev), me, myDev) { 9 })
        assertEquals(0L, membershipAction(m(me, "d-tablet"), me, myDev) { 9 }!!.delayMs)
        assertEquals(12_345L, membershipAction(m(peer, "d-new"), me, myDev) { r -> assertEquals(5_000..30_000, r); 12_345 }!!.delayMs)
        val e = engine()
        e.onEvents(listOf(ev("mls_membership", buildJsonObject {
            put("conversation_id", conv); put("user_id", peer); put("device_id", "d-new"); put("change", "added")
        })))
        assertEquals(7_000L, memberships.single().delayMs)
    }

    @Test fun withoutAnMlsCoreE2eeEventsAreSkippedButTheCursorMoves() = runTest {
        val e = engine(withMls = false)
        val m = msg(1, "x")
        e.onEvents(listOf(welcome(1, listOf(myDev)), m))
        assertTrue(messages.rows.isEmpty() && pending.rows.isEmpty())
        assertEquals(m.eventId, sync.last)
    }

    // ---- §13.3 history after a reinstall ----

    private val hb = "2026-10-06T09:00:00.000Z"
    private val before = "2026-10-06T08:30:00.000Z"
    private val after = "2026-10-06T09:30:00.000Z"

    @Test fun rule1NoGroupBeforeHistoryIsPreInstallNotParked() = runTest {
        val e = engine()
        e.onHistoryBefore(hb)
        e.onEvents(listOf(msg(0, "old", ts = before), msg(0, "older", ts = "2026-10-06T08:00:00.000Z")))
        assertTrue(pending.rows.isEmpty())
        assertTrue(bodies().isEmpty())
        val marker = messages.rows[gap]!!
        assertEquals(lk.codegen.risime.data.groups.SystemLine.HISTORY_GAP_TEXT, marker.body)
        assertEquals(java.time.Instant.parse(before).toEpochMilli() + 1, marker.localTs) // forward-only: the later one wins
        assertEquals("READ", marker.status)
        assertTrue(realtime.acks.isEmpty())
        // At or after history_before: parked (the Welcome may still come).
        e.onEvents(listOf(msg(0, "new", ts = after), msg(0, "edge", ts = hb)))
        assertEquals(2, pending.rows.size)
        assertEquals(listOf(gap), markers())
    }

    @Test fun nullHistoryBeforeMeansEpochRuleOnly() = runTest {
        val e = engine()
        e.onHistoryBefore(null)
        e.onEvents(listOf(msg(0, "old", ts = before)))
        assertEquals(1, pending.rows.size)
        assertTrue(markers().isEmpty())
    }

    @Test fun anExistingGroupAtOurEpochDecryptsEvenBeforeHistory() = runTest {
        // Key packages published before the first census connect: decryptable despite server_ts < history_before.
        mls.groups[conv] = GroupRef(conv, 1, 1)
        val e = engine()
        e.onHistoryBefore(hb)
        e.onEvents(listOf(msg(1, "readable", ts = before)))
        assertEquals(listOf("readable"), bodies())
        assertTrue(markers().isEmpty())
    }

    @Test fun parkedRowsAreNeverReJudgedByRule1() = runTest {
        val e = engine()
        e.onHistoryBefore(null) // an upgraded device: parked before v1.10
        e.onEvents(listOf(msg(1, "parked", ts = before)))
        assertEquals(1, pending.rows.size)
        e.onHistoryBefore(hb) // a later join with a (late) boundary
        e.onEvents(listOf(welcome(1, listOf(myDev))))
        assertEquals(listOf("parked"), bodies())
        assertTrue(markers().isEmpty())
    }

    @Test fun rule2WelcomeReplayBelowTheJoinEpochIsPreInstall() = runTest {
        val e = engine()
        e.onHistoryBefore(null)
        e.onEvents(listOf(msg(0, "e0", ts = "2026-10-06T08:00:00.000Z"), commit(0, "c"), msg(1, "e1", ts = before), welcome(2, listOf(myDev)), msg(2, "now", ts = after)))
        assertEquals(listOf("now"), bodies())
        assertEquals(listOf(gap), markers())
        assertEquals(java.time.Instant.parse(before).toEpochMilli() + 1, messages.rows[gap]!!.localTs)
        assertTrue(pending.rows.isEmpty())
        assertTrue(mls.processed.isEmpty()) // the parked commit is below the Welcome too
    }

    @Test fun discardedOlderGenerationRowsGiveOneMarker() = runTest {
        val e = engine()
        e.onHistoryBefore(null)
        e.onEvents(listOf(msg(1, "g1-a", gen = 1, ts = "2026-10-06T08:00:00.000Z"), msg(3, "g1-b", gen = 1, ts = before)))
        assertEquals(2, pending.rows.size)
        e.onEvents(listOf(welcome(1, listOf(myDev), gen = 2)))
        assertTrue(pending.rows.isEmpty())
        assertEquals(listOf(gap), markers())
        assertEquals(java.time.Instant.parse(before).toEpochMilli() + 1, messages.rows[gap]!!.localTs)
    }

    @Test fun theOldDevicesOwnSendsArePreInstall() = runTest {
        val e = engine()
        e.onHistoryBefore(hb)
        e.onEvents(listOf(msg(0, "mine from the old install", fromDev = "d-old", from = me, ts = before)))
        assertTrue(messages.rows.values.none { !it.system })
        assertEquals(listOf(gap), markers())
        // This device's own send is still skipped first (no marker for it).
        messages.rows.clear()
        e.onEvents(listOf(msg(0, "mine", fromDev = myDev, from = me, ts = before)))
        assertTrue(messages.rows.isEmpty())
    }

    @Test fun aWelcomeFromBeforeThisDevicesMlsStateIsIgnoredButALaterRejectedOneIsVisible() = runTest {
        // Logout + login keeps the device id: the replayed inbox holds a Welcome for the wiped key package.
        val e = engine()
        fun badWelcome(eventId: String) = welcome(1, listOf(myDev)).let { w ->
            Event(eventId, w.kind, JsonObject(w.data + ("welcome" to JsonPrimitive(FakeMlsEngine.b64("welcome|not-a-key-package")))))
        }
        e.onHistoryBefore("2026-01-01T00:00:00.000Z")
        e.onEvents(listOf(badWelcome("c1a2b3c7-a0b1-11f0-8000-0242ac120002"))) // TimeUUID of 2025-10-03
        assertTrue(markers().isEmpty())
        e.onHistoryBefore("2025-01-01T00:00:00.000Z")
        e.onEvents(listOf(badWelcome("c1a2b3c8-a0b1-11f0-8000-0242ac120002"))) // after history_before: a real failure
        assertEquals(listOf(undecryptable), markers())
    }

    @Test fun aCorruptedCiphertextGivesOneUndecryptableLine() = runTest {
        mls.groups[conv] = GroupRef(conv, 1, 1)
        val e = engine()
        fun corrupt(ts: String) = msg(1, "x", ts = ts).let { ev -> Event(ev.eventId, ev.kind, JsonObject(ev.data + ("ciphertext" to JsonPrimitive(FakeMlsEngine.b64("garbage"))))) }
        e.onEvents(listOf(corrupt(before), corrupt(after)))
        e.onEvents(listOf(corrupt("2026-10-06T08:00:00.000Z"))) // earlier: the line doesn't move back
        assertEquals(listOf(undecryptable), markers())
        assertEquals(lk.codegen.risime.data.groups.SystemLine.UNDECRYPTABLE_TEXT, messages.rows[undecryptable]!!.body)
        assertEquals(java.time.Instant.parse(after).toEpochMilli() + 1, messages.rows[undecryptable]!!.localTs)
        assertTrue(realtime.acks.isEmpty())
    }

    // ---- outbox ----

    @Test fun e2eeSendsAreEncryptedAtSendTime() = runTest {
        mls.groups[conv] = GroupRef(conv, 1, 4)
        val e = engine()
        val id = e.sendText(peer, "secret")!!
        advanceUntilIdle()
        val sent = realtime.sentEncrypted.single()
        assertEquals(id, sent.clientMsgId)
        assertEquals(4L, sent.epoch)
        assertTrue(realtime.sent.isEmpty()) // no plaintext msg:send
        assertEquals("SENT", messages.rows[id]!!.status)
    }

    @Test fun staleEpochCatchesUpAndReEncryptsWithTheSameIdThenBacksOff() = runTest {
        mls.groups[conv] = GroupRef(conv, 1, 4)
        var stale = 1
        realtime.encryptedReplies = { m -> if (stale-- > 0) PushResult.Rejected("stale_epoch") else PushResult.Ok(MsgSendReply("mid", conv, "t")) }
        onCatchUp = { mls.groups[conv] = mls.groups[conv]!!.copy(epoch = 5) }
        val e = engine()
        val id = e.sendText(peer, "x")!!
        advanceUntilIdle()
        assertEquals(listOf(4L, 5L), realtime.sentEncrypted.map { it.epoch })
        assertEquals(setOf(id), realtime.sentEncrypted.map { it.clientMsgId }.toSet())
        assertEquals("SENT", messages.rows[id]!!.status)

        // Always stale: 1 + 3 retries, then it stays PENDING (retried later), never FAILED.
        realtime.encryptedReplies = { PushResult.Rejected("stale_epoch") }
        realtime.sentEncrypted.clear()
        val bg = engine(scope = backgroundScope) // its delayed retries run forever; keep them out of runTest
        messages.rows.clear()
        val id2 = bg.sendText(peer, "y")!!
        kotlinx.coroutines.yield()
        assertEquals(4, realtime.sentEncrypted.size)
        assertEquals("PENDING", messages.rows[id2]!!.status)
    }

    @Test fun e2eeRequiredOnPlaintextCatchesUpThenEncrypts() = runTest {
        realtime.sendReplies = { PushResult.Rejected("e2ee_required") }
        onCatchUp = { mls.groups[conv] = GroupRef(conv, 1, 1) } // the catch-up delivered our Welcome
        val e = engine()
        val id = e.sendText(peer, "z")!!
        advanceUntilIdle()
        assertEquals(1, realtime.sent.size) // the plaintext attempt
        assertEquals(1, realtime.sentEncrypted.size)
        assertEquals("SENT", messages.rows[id]!!.status)
        assertFalse(catchUps.isEmpty())
    }
}
