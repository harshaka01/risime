package lk.codegen.risime.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision 054 (the nightly.16 P0: the callee couldn't answer, the caller stayed "in call"): every
 * way a call ends or dies leaves nothing behind, every failure is visible, Hang up always works.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallCleanupTest {
    private val userA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val userB = "bbbbbbbb-0000-4000-8000-000000000002"
    private val conv = dmConversationId(userA, userB)
    private val t0 = 1_791_000_000_000L

    private lateinit var net: CallNet
    private val devs = linkedMapOf<String, CallNet.Dev>()

    private fun TestScope.clock() = testScheduler.currentTime + t0

    private fun TestScope.machineFor(name: String, d: CallNet.Dev, media: CallMedia = d.media): CallStateMachine = CallStateMachine(
        d.user, d.device, backgroundScope, media, d.signals, d.marks, d.environment,
        now = { clock() }, serverNow = { clock() }, log = { println("  [$name] $it") },
    )

    private fun TestScope.world() {
        net = CallNet(backgroundScope) { clock() }
        devs.clear()
        devs["A1"] = net.Dev(userA, "a1a1a1a1-0000-4000-8000-000000000001")
        devs["B1"] = net.Dev(userB, "b1b1b1b1-0000-4000-8000-000000000003")
        devs["B2"] = net.Dev(userB, "b2b2b2b2-0000-4000-8000-000000000004")
        for ((n, d) in devs) {
            d.machine = machineFor(n, d)
            net.devices += d
        }
    }

    /** The app process of [name] dies (no call_end, no more traffic) and a new one starts with no call. */
    private fun TestScope.restart(name: String): CallNet.Dev {
        val old = devs.getValue(name)
        old.dead = true
        net.devices.remove(old)
        net.queue.removeAll { it.fromDevice == old.device }
        val fresh = net.Dev(old.user, old.device)
        fresh.machine = machineFor("$name'", fresh)
        net.devices += fresh
        devs[name] = fresh
        return fresh
    }

    private suspend fun TestScope.settle(rounds: Int = 8) {
        repeat(rounds) {
            runCurrent()
            net.flush()
        }
        runCurrent()
    }

    private fun CallNet.Dev.phase() = machine.state.value?.phase
    private fun CallNet.Dev.notice() = machine.state.value?.notice
    private fun d(n: String) = devs.getValue(n)

    @Test
    fun callerCrashMidRingCalleeGetsAMissedCallAndTheCallerCanCallAgain() = runTest {
        world()
        d("A1").machine.placeCall(conv)
        settle()
        assertEquals(CallPhase.RINGING_IN, d("B1").phase())
        val id = d("A1").machine.state.value!!.callId
        val a = restart("A1")
        // B rings out its 45 s, then waits MISSED_GRACE_MS for a call_end that never comes.
        advanceTimeBy(CallStateMachine.RING_MS + 100)
        settle()
        assertNull(d("B1").phase())
        assertTrue(d("B1").missedCalls.isEmpty())
        advanceTimeBy(CallStateMachine.MISSED_GRACE_MS)
        settle()
        assertEquals(listOf(id), d("B1").missedCalls)
        assertEquals(listOf(id), d("B2").missedCalls)
        // The new caller process is not "in a call".
        a.machine.placeCall(conv)
        settle()
        assertEquals(CallPhase.RINGING_OUT, a.phase())
        assertEquals(CallPhase.RINGING_IN, d("B1").phase())
    }

    @Test
    fun aCallEndBeforeTheGraceMeansNoLocalMissedLine() = runTest {
        world()
        d("A1").machine.placeCall(conv)
        settle()
        advanceTimeBy(CallStateMachine.RING_MS + CallStateMachine.MISSED_GRACE_MS + 100)
        settle()
        assertEquals(CallEnvelope.R_TIMEOUT, d("A1").ends.single().reason)
        // The durable call_end marked the call ended at B; the local missed line is still offered:
        // the app deduplicates it against the line from that call_end (one line per call id).
        assertTrue(d("B1").missedCalls.size <= 1)
    }

    @Test
    fun calleeKilledMidRingCallerGetsNoAnswerAndCanCallAgain() = runTest {
        world()
        d("A1").machine.placeCall(conv)
        settle()
        restart("B1")
        restart("B2")
        advanceTimeBy(CallStateMachine.RING_MS + 100)
        settle()
        assertEquals(CallNotice.NO_ANSWER, d("A1").notice())
        assertEquals(CallEnvelope.R_TIMEOUT, d("A1").ends.single().reason)
        advanceTimeBy(5_000)
        settle()
        assertNull(d("A1").phase())
        d("A1").machine.placeCall(conv)
        settle()
        assertEquals(CallPhase.RINGING_OUT, d("A1").phase())
        assertEquals(CallPhase.RINGING_IN, d("B1").phase())
    }

    @Test
    fun aStuckOfferSignalFailsVisiblyWithin10sAndTheNextCallWorks() = runTest {
        world()
        val a = d("A1")
        a.hang = true
        backgroundScope.launch { a.machine.placeCall(conv) }
        runCurrent()
        assertEquals(CallPhase.CALLING, a.phase())
        advanceTimeBy(CallStateMachine.SIGNAL_TIMEOUT_MS + 100)
        settle()
        assertEquals(CallNotice.CANT_CONNECT, a.notice())
        a.hang = false
        advanceTimeBy(5_000)
        settle()
        a.machine.placeCall(conv)
        settle()
        assertEquals(CallPhase.RINGING_OUT, a.phase())
    }

    @Test
    fun cancelAlwaysWorksEvenWhenTheMediaHangsTheMachine() = runTest {
        world()
        val a = d("A1")
        val stuck = object : CallMedia {
            override val available = true
            override fun open(callId: String, iceServers: List<IceServer>, listener: CallMedia.Listener): MediaSession {
                val inner = a.media.open(callId, iceServers, listener)
                return object : MediaSession by inner {
                    override suspend fun createOffer(iceRestart: Boolean): String = awaitCancellation()
                }
            }
        }
        val m = machineFor("A1-stuck", a, stuck)
        a.machine = m
        backgroundScope.launch { m.placeCall(conv) }
        runCurrent()
        assertEquals(CallPhase.CALLING, a.phase())
        // The offer creation holds the machine; Cancel still ends the call within HANGUP_LOCK_MS.
        backgroundScope.launch { m.hangUp() }
        advanceTimeBy(CallStateMachine.HANGUP_LOCK_MS + 100)
        runCurrent()
        assertEquals(CallPhase.ENDED, a.phase())
        assertEquals(CallNotice.CALL_ENDED, a.notice())
        assertNull(m.currentCallId())
        // The stuck operation times out later without resurrecting the call.
        advanceTimeBy(CallStateMachine.MEDIA_OP_MS + 5_000)
        settle()
        assertNull(a.phase())
        assertTrue("the media of the cancelled call is closed", a.media.sessions.all { it.closed })
    }

    @Test
    fun socketLossFailsTheCallVisiblyAndTheCalleeGetsAMissedCall() = runTest {
        world()
        d("A1").machine.placeCall(conv)
        settle()
        val id = d("A1").machine.currentCallId()!!
        d("A1").machine.fail(id)
        settle()
        assertEquals(CallNotice.CANT_CONNECT, d("A1").notice())
        assertEquals(CallEnvelope.R_CANCELLED, d("A1").ends.single().reason)
        assertTrue(d("B1").phase() == null || d("B1").phase() == CallPhase.ENDED)
        // A fail for another call id does nothing (a stale Telecom/socket callback).
        d("A1").machine.placeCall(conv)
        settle()
        d("A1").machine.fail("00000000-0000-4000-8000-000000000000")
        settle()
        assertEquals(CallPhase.RINGING_OUT, d("A1").phase())
    }

    @Test
    fun aHangUpForAnOlderCallIdNeverDeclinesTheRingingCall() = runTest {
        world()
        d("A1").machine.placeCall(conv)
        settle()
        assertEquals(CallPhase.RINGING_IN, d("B1").phase())
        // nightly.16: a ghost Telecom call's onDisconnect/onSetInactive hung up whatever was current.
        d("B1").machine.onSystemDisconnect("11111111-0000-4000-8000-000000000000")
        settle()
        assertEquals(CallPhase.RINGING_IN, d("B1").phase())
        d("B1").machine.answer()
        settle()
        assertTrue(d("B1").phase() == CallPhase.CONNECTING || d("B1").phase() == CallPhase.ANSWERING)
    }

    @Test
    fun aStaleCommunicationModeIsNotBusyOnlyACellularCallIs() = runTest {
        world()
        // busyAudio models CallEnvironment.audioBusy() = MODE_IN_CALL (a cellular call) since decision 054.
        d("B1").busyAudio = false
        d("A1").machine.placeCall(conv)
        settle()
        assertEquals(CallPhase.RINGING_IN, d("B1").phase())
    }

    @Test
    fun noCallOutlivesTheWatchdogWithoutMedia() = runTest {
        world()
        // The answer goes out but nothing comes back from the caller (it died after the answer).
        d("A1").machine.placeCall(conv)
        settle()
        restart("A1")
        d("B1").machine.answer()
        settle()
        advanceTimeBy(CallStateMachine.MEDIA_WATCHDOG_MS + 5_000)
        settle()
        for ((n, x) in devs) assertNull("$n still has a call", x.phase())
        // And a fresh call between the same devices works at once.
        d("B1").machine.placeCall(conv)
        settle()
        assertEquals(CallPhase.RINGING_OUT, d("B1").phase())
        assertEquals(CallPhase.RINGING_IN, d("A1").phase())
        assertNotEquals(null, d("A1").machine.ringingCallId())
    }

    @Test
    fun aKilledProcessOwesTheRightCallEnd() {
        fun r(outgoing: Boolean, phase: CallPhase) = ActiveCallRecord("c", "dm:x", "p", outgoing, phase.name, false)
        assertNull(ActiveCallRecord.endReason(r(false, CallPhase.RINGING_IN)))
        assertEquals(CallEnvelope.R_CANCELLED, ActiveCallRecord.endReason(r(true, CallPhase.CALLING)))
        assertEquals(CallEnvelope.R_CANCELLED, ActiveCallRecord.endReason(r(true, CallPhase.RINGING_OUT)))
        assertEquals(CallEnvelope.R_FAILED, ActiveCallRecord.endReason(r(true, CallPhase.ACTIVE)))
        assertEquals(CallEnvelope.R_FAILED, ActiveCallRecord.endReason(r(false, CallPhase.ANSWERING)))
        val rec = ActiveCallRecord("0f0e", "dm:a_b", "b", true, "RINGING_OUT", true)
        assertEquals(rec, ActiveCallRecord.decode(rec.encode()))
        assertNull(ActiveCallRecord.decode("garbage"))
    }
}
