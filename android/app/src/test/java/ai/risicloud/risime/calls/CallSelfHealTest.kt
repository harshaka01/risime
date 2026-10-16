package ai.risicloud.risime.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ai.risicloud.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 calls (Harsha 2026-10-16 11:42): every end path frees the call, and a held call that isn't live
 * any more never answers "busy" (Shenika's phone) or "You're already in a call" (after a phone call).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallSelfHealTest {
    private val userA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val userB = "bbbbbbbb-0000-4000-8000-000000000002"
    private val conv = dmConversationId(userA, userB)
    private val t0 = 1_791_000_000_000L

    private fun TestScope.world(): Pair<CallNet, Map<String, CallNet.Dev>> {
        val clock = { testScheduler.currentTime + t0 }
        val net = CallNet(backgroundScope, clock)
        val devs = linkedMapOf(
            "A1" to net.Dev(userA, "a1a1a1a1-0000-4000-8000-000000000001"),
            "B1" to net.Dev(userB, "b1b1b1b1-0000-4000-8000-000000000003"),
        )
        for ((name, d) in devs) {
            d.machine = CallStateMachine(d.user, d.device, backgroundScope, d.media, d.signals, d.marks, d.environment, now = clock, serverNow = clock, log = { println("  [$name] $it") })
            net.devices += d
        }
        return net to devs
    }

    private suspend fun TestScope.settle(net: CallNet, rounds: Int = 8) {
        repeat(rounds) {
            runCurrent()
            net.flush()
        }
        runCurrent()
    }

    private fun CallNet.Dev.phase() = machine.state.value?.phase
    private fun CallNet.Dev.notice() = machine.state.value?.notice
    private fun CallNet.Dev.session() = media.sessions.last()

    /** A1 calls B1, B1 answers, both connect. */
    private suspend fun TestScope.connected(net: CallNet, a1: CallNet.Dev, b1: CallNet.Dev) {
        assertTrue(a1.machine.placeCall(conv))
        settle(net)
        assertTrue(b1.machine.answer())
        settle(net)
        listOf(a1, b1).forEach { it.session().listener.onIceState(IceState.CONNECTED) }
        settle(net)
        advanceTimeBy(1_000)
        settle(net)
        assertEquals(CallPhase.ACTIVE, a1.phase())
        assertEquals(CallPhase.ACTIVE, b1.phase())
    }

    /** The caller's `call_end` / cancel never reaches the callee (lost, never sent, a killed process). */
    private fun CallNet.loseEndsFrom(user: String) {
        holdIf = { it.fromUser == user && (it.durable || it.env is CallEnvelope.Cancel) }
    }

    @Test
    fun shenikasCase_aNewRingFromTheSamePeerClearsTheStaleCallAndRings() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        connected(net, a1, b1)
        // A hangs up, but the call_end is lost: B still holds the call.
        net.loseEndsFrom(userA)
        a1.machine.hangUp()
        settle(net)
        assertEquals(CallPhase.ACTIVE, b1.phase())
        net.holdIf = null
        // A calls again: B must ring, not answer busy.
        advanceTimeBy(5_000)
        assertTrue(a1.machine.placeCall(conv))
        settle(net)
        assertEquals(CallPhase.RINGING_IN, b1.phase())
        assertTrue("no call_busy", b1.sent.none { it.env is CallEnvelope.Busy })
        assertEquals(CallPhase.RINGING_OUT, a1.phase())
        assertNotEquals("the ring is the new call", b1.machine.state.value?.callId, a1.ends.firstOrNull()?.callId)
        // The stale call's media is closed.
        assertTrue(b1.media.sessions.first().closed)
    }

    @Test
    fun aRingFromTheSamePeerReplacesAnOldRingWhoseCancelWasLost() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        assertTrue(a1.machine.placeCall(conv))
        settle(net)
        val first = b1.machine.state.value!!.callId
        net.loseEndsFrom(userA)
        a1.machine.hangUp()
        settle(net)
        assertEquals(CallPhase.RINGING_IN, b1.phase())
        net.holdIf = null
        advanceTimeBy(2_000)
        assertTrue(a1.machine.placeCall(conv))
        settle(net)
        assertEquals(CallPhase.RINGING_IN, b1.phase())
        assertNotEquals(first, b1.machine.state.value!!.callId)
        assertTrue(b1.sent.none { it.env is CallEnvelope.Busy })
    }

    @Test
    fun aConnectedCallWithNoAudioFor20sIsClearedBeforeInAnotherCall() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        connected(net, a1, b1)
        // The media layer counts bytes, but no audio ever arrives (the peer's side is gone).
        b1.session().bytes = 0
        net.loseEndsFrom(userA)
        a1.machine.hangUp()
        settle(net)
        advanceTimeBy(CallStateMachine.MEDIA_STALL_MS + 3_000)
        settle(net)
        assertEquals("the stall watch never judges 0 bytes", CallPhase.ACTIVE, b1.phase())
        net.holdIf = null
        // B's own call button: the dead call is cleared, the new call goes out.
        assertTrue(b1.machine.placeCall(conv))
        settle(net)
        assertNotEquals(CallNotice.IN_ANOTHER_CALL, b1.notice())
        assertTrue(b1.phase() == CallPhase.CALLING || b1.phase() == CallPhase.RINGING_OUT)
        // The stale call told the peer it is over (a hang-up).
        assertTrue(b1.ends.any { it.reason == CallEnvelope.R_HANGUP })
    }

    @Test
    fun aLiveCallWithAudioStaysBusyForMyOwnCallButton() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        connected(net, a1, b1)
        var bytes = 100L
        repeat(15) {
            bytes += 50
            b1.session().bytes = bytes
            advanceTimeBy(2_000)
            settle(net)
        }
        assertFalse(b1.machine.placeCall(conv))
        settle(net, 1)
        assertEquals(CallNotice.IN_ANOTHER_CALL, b1.machine.state.value?.notice)
        assertTrue(b1.machine.healIfStale())
        assertNotNull(b1.machine.liveCallId())
        b1.machine.hangUp()
        settle(net)
    }

    @Test
    fun aCellularCallEndsTheCallTellsThePeerAndTheNextCallWorks() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        connected(net, a1, b1)
        a1.machine.onCellularCall()
        settle(net)
        assertEquals(CallNotice.INTERRUPTED, a1.notice())
        assertEquals(CallEnvelope.R_HANGUP, a1.ends.single().reason)
        assertEquals("the peer was told", CallPhase.ENDED, b1.phase())
        assertTrue(a1.session().closed)
        assertNull(a1.machine.currentCallId())
        advanceTimeBy(3_000)
        settle(net)
        // Call again without restarting the app.
        assertTrue(a1.machine.placeCall(conv))
        settle(net)
        assertEquals(CallPhase.RINGING_IN, b1.phase())
        assertEquals(CallPhase.RINGING_OUT, a1.phase())
    }

    @Test
    fun aCellularCallWhileCallingCancelsTheRing() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        assertTrue(a1.machine.placeCall(conv))
        settle(net)
        a1.machine.onCellularCall()
        settle(net)
        assertEquals(CallEnvelope.R_CANCELLED, a1.ends.single().reason)
        assertNull("the callee stops ringing", b1.machine.currentCallId())
    }

    @Test
    fun everyEndPathFreesTheCall() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        // 1. Hang up (me).
        connected(net, a1, b1)
        a1.machine.hangUp()
        settle(net)
        assertNull(a1.machine.currentCallId()); assertNull(b1.machine.currentCallId())
        advanceTimeBy(3_000); settle(net)
        // 2. Remote end (the peer hangs up).
        connected(net, a1, b1)
        b1.machine.hangUp()
        settle(net)
        assertNull(a1.machine.currentCallId()); assertNull(b1.machine.currentCallId())
        advanceTimeBy(3_000); settle(net)
        // 3. Network loss: ICE fails and doesn't come back within the reconnect bound.
        connected(net, a1, b1)
        listOf(a1, b1).forEach { it.session().listener.onIceState(IceState.FAILED) }
        settle(net)
        advanceTimeBy(CallStateMachine.RECONNECT_MS + 2_000)
        settle(net)
        assertNull(a1.machine.currentCallId()); assertNull(b1.machine.currentCallId())
        advanceTimeBy(3_000); settle(net)
        // 4. Telecom / the system disconnects it.
        connected(net, a1, b1)
        a1.machine.onSystemDisconnect(a1.machine.currentCallId())
        settle(net)
        assertNull(a1.machine.currentCallId()); assertNull(b1.machine.currentCallId())
        advanceTimeBy(3_000); settle(net)
        // 5. Timeout: nobody answers within the ring.
        assertTrue(a1.machine.placeCall(conv))
        settle(net)
        advanceTimeBy(CallStateMachine.RING_MS + 1_000)
        settle(net)
        assertNull(a1.machine.currentCallId()); assertNull(b1.machine.currentCallId())
        advanceTimeBy(CallStateMachine.MISSED_GRACE_MS + 3_000); settle(net)
        // After all of them a new call goes out at once.
        assertTrue(a1.machine.placeCall(conv))
        settle(net)
        assertEquals(CallPhase.RINGING_IN, b1.phase())
        a1.machine.hangUp()
        settle(net)
    }

    // ---- the cellular watch (audio mode only, no READ_PHONE_STATE) ----

    @Test
    fun theCellularWatchFiresOnceWhenAPhoneCallTakesOverAConnectedCall() {
        val w = CellularWatch(modeInCall = 2, modeInCommunication = 3)
        assertFalse(w.observe("c1", connected = false, mode = 0))
        assertFalse("ringing, not connected yet", w.observe("c1", connected = false, mode = 2))
        assertFalse(w.observe("c1", connected = true, mode = 3))
        assertTrue("the phone call took the audio", w.observe("c1", connected = true, mode = 2))
        assertFalse("once per call", w.observe("c1", connected = true, mode = 2))
        // A new call starts fresh.
        assertFalse(w.observe("c2", connected = true, mode = 2))
        assertFalse(w.observe(null, connected = false, mode = 2))
    }

    @Test
    fun aPhoneWhoseVoipCallsRunInCallModeNeverEndsItsOwnCall() {
        val w = CellularWatch(modeInCall = 2, modeInCommunication = 3)
        repeat(10) { assertFalse(w.observe("c1", connected = true, mode = 2)) }
    }

    private fun assertNotNull(v: Any?) = assertTrue(v != null)
}
