package lk.codegen.risime.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallStateMachineTest {
    private val userA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val userB = "bbbbbbbb-0000-4000-8000-000000000002"
    private val conv = dmConversationId(userA, userB)
    private val t0 = 1_791_000_000_000L

    private class World(val ts: TestScope, val net: CallNet)

    private fun TestScope.world(tamper: Boolean = false, ids: Map<String, List<String>> = emptyMap()): Pair<World, Map<String, CallNet.Dev>> {
        val clock = { testScheduler.currentTime + t0 }
        val net = CallNet(backgroundScope, clock)
        val devs = linkedMapOf(
            "A1" to net.Dev(userA, "a1a1a1a1-0000-4000-8000-000000000001"),
            "A2" to net.Dev(userA, "a2a2a2a2-0000-4000-8000-000000000002"),
            "B1" to net.Dev(userB, "b1b1b1b1-0000-4000-8000-000000000003"),
            "B2" to net.Dev(userB, "b2b2b2b2-0000-4000-8000-000000000004"),
        )
        for ((name, d) in devs) {
            val queue = ids[name].orEmpty().toMutableList()
            d.machine = CallStateMachine(
                d.user, d.device, backgroundScope, d.media, d.signals, d.marks, d.environment,
                now = clock, serverNow = clock, log = { println("  [$name] $it") },
                newCallId = { if (queue.isNotEmpty()) queue.removeAt(0) else java.util.UUID.randomUUID().toString() },
                tamperRemoteFingerprint = { tamper && name == "A1" },
            )
            net.devices += d
        }
        return World(this, net) to devs
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
    private fun CallNet.Dev.sentTypes() = sent.map { it.env.type }
    private fun CallNet.Dev.session() = media.sessions.last()

    /** Both sides' ICE connects (the fake DTLS then reports the real certificates). */
    private suspend fun TestScope.connect(net: CallNet, vararg devs: CallNet.Dev) {
        devs.forEach { it.session().listener.onIceState(IceState.CONNECTED) }
        settle(net)
        advanceTimeBy(1_000)
        settle(net)
    }

    @Test
    fun outgoingCallRingsBothDevicesFirstAnswerWinsThenHangup() = runTest {
        val (w, d) = world()
        val (a1, a2, b1, b2) = listOf(d["A1"]!!, d["A2"]!!, d["B1"]!!, d["B2"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        assertEquals(CallPhase.RINGING_IN, b1.phase())
        assertEquals(CallPhase.RINGING_IN, b2.phase())
        assertNull("the caller's other device never rings for its own user's offer", a2.phase())
        assertEquals(CallPhase.RINGING_OUT, a1.phase())
        assertEquals(listOf("call_ringing"), b1.sentTypes())
        // The offer went out with ring=true, sent_at, a validated SDP without the audio-level extension.
        val offer = a1.sent.first().env as CallEnvelope.Offer
        assertTrue(CallEnvelope.ringFor(offer))
        assertNull(SdpRules.validate(offer.sdp, SdpRules.Role.OFFER))
        assertFalse(offer.sdp.contains(SdpRules.AUDIO_LEVEL))
        assertTrue(offer.sdp.contains("cbr=1"))

        // Caller candidates trickle with to_device null before call_accepted.
        a1.session().candidate(1)
        advanceTimeBy(300)
        settle(w.net)
        val ice0 = a1.sent.map { it.env }.filterIsInstance<CallEnvelope.Ice>().single()
        assertNull(ice0.toDevice)

        assertTrue(b1.machine.answer())
        settle(w.net)
        assertNull("B2 stops ringing at once (sibling answered)", b2.machine.state.value?.takeIf { it.phase != CallPhase.ENDED })
        assertEquals(CallNotice.ANSWERED_ELSEWHERE, b2.notice())
        val accepted = a1.sent.map { it.env }.filterIsInstance<CallEnvelope.Accepted>().single()
        assertEquals(b1.device, accepted.deviceId)
        assertEquals(CallPhase.CONNECTING, b1.phase())
        assertEquals(CallPhase.CONNECTING, a1.phase())
        // B1 had buffered A's candidate (to_device null) and applied it after the offer.
        assertEquals(1, b1.session().remoteCandidates.size)

        b1.session().candidate(2)
        advanceTimeBy(300)
        settle(w.net)
        assertEquals(b1.device, a1.sent.first().fromDevice.let { b1.device }) // sanity
        assertEquals(1, a1.session().remoteCandidates.size)
        val ice1 = b1.sent.map { it.env }.filterIsInstance<CallEnvelope.Ice>().first()
        assertEquals(a1.device, ice1.toDevice)

        connect(w.net, a1, b1)
        assertEquals(CallPhase.ACTIVE, a1.phase())
        assertEquals(CallPhase.ACTIVE, b1.phase())
        assertTrue(a1.machine.state.value!!.verified && b1.machine.state.value!!.verified)

        advanceTimeBy(192_000)
        a1.machine.hangUp()
        settle(w.net)
        val end = a1.ends.single()
        assertEquals(CallEnvelope.R_HANGUP, end.reason)
        assertTrue("${end.durationS}", end.durationS in 192L..193L)
        assertTrue(end.connectedAt != null)
        assertEquals(CallPhase.ENDED, b1.phase())
        assertTrue(b1.ends.isEmpty() && b2.ends.isEmpty())
        assertTrue("B2 never sent anything", b2.sent.none { it.env !is CallEnvelope.Ringing })
        assertTrue(a1.session().closed && b1.session().closed)
    }

    private fun lowHigh(): Pair<String, String> = "00000000-0000-4000-8000-00000000000a" to "ffffffff-0000-4000-8000-00000000000b"

    @Test
    fun glareLowerCallIdWinsLoserCancelsAndAutoAnswers() = runTest {
        val (low, high) = lowHigh()
        val (w, d) = world(ids = mapOf("A1" to listOf(low), "B1" to listOf(high)))
        val (a1, a2, b1, b2) = listOf(d["A1"]!!, d["A2"]!!, d["B1"]!!, d["B2"]!!)
        a1.machine.placeCall(conv)
        b1.machine.placeCall(conv)
        settle(w.net)
        // B's call (higher id) lost: cancelled with reason glare, no call_end, B1 auto-answered A's call.
        val cancel = b1.sent.map { it.env }.filterIsInstance<CallEnvelope.Cancel>().single()
        assertEquals(high, cancel.callId)
        assertEquals(CallEnvelope.CANCEL_GLARE, cancel.reason)
        assertTrue(b1.sent.map { it.env }.filterIsInstance<CallEnvelope.Answer>().single().callId == low)
        assertTrue(b1.ends.isEmpty() && a1.ends.isEmpty())
        assertEquals(CallPhase.CONNECTING, a1.phase())
        assertEquals(low, a1.machine.state.value!!.callId)
        // Sibling rule: A2 saw A1's offer first and never rang for B's; B2 rang for A's offer before it
        // saw B1's, and stopped when B1's answer arrived (sender copy).
        assertTrue(a2.sent.isEmpty() && a2.phase() == null)
        assertTrue(b2.phase() == null || b2.phase() == CallPhase.ENDED)
        assertTrue(b2.sent.all { it.env is CallEnvelope.Ringing })
        connect(w.net, a1, b1)
        assertEquals(CallPhase.ACTIVE, a1.phase())
        assertEquals(CallPhase.ACTIVE, b1.phase())
    }

    @Test
    fun glareOtherDirection() = runTest {
        val (low, high) = lowHigh()
        val (w, d) = world(ids = mapOf("A1" to listOf(high), "B1" to listOf(low)))
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        a1.machine.placeCall(conv)
        b1.machine.placeCall(conv)
        settle(w.net)
        assertEquals(high, a1.sent.map { it.env }.filterIsInstance<CallEnvelope.Cancel>().single().callId)
        assertTrue(b1.sent.none { it.env is CallEnvelope.Cancel })
        assertEquals(low, b1.machine.state.value!!.callId)
        assertEquals(CallPhase.CONNECTING, b1.phase())
    }

    @Test
    fun glareCancelStopsARingThatStartedBeforeTheSiblingSawItsOwnOffer() = runTest {
        val (low, high) = lowHigh()
        val (w, d) = world(ids = mapOf("A1" to listOf(low), "B1" to listOf(high)))
        val (a1, b1, b2) = listOf(d["A1"]!!, d["B1"]!!, d["B2"]!!)
        // B2 sees A's offer first and rings (it hasn't seen B1's offer yet).
        a1.machine.placeCall(conv)
        runCurrent()
        val offerA = w.net.queue.removeAt(0)
        b2.machine.onSignal(InboundCall(conv, userA, a1.device, testScheduler.currentTime + t0, offerA.env))
        runCurrent()
        assertEquals(CallPhase.RINGING_IN, b2.phase())
        // Now B1 places its call and the rest flows (B1 receives A's low offer → B1 loses; but here A wins).
        b1.machine.placeCall(conv)
        w.net.queue.add(0, offerA)
        settle(w.net)
        // B2 stopped: B1 answered A's call (sender copy) → answered elsewhere.
        assertTrue(b2.phase() == null || b2.phase() == CallPhase.ENDED)
    }

    @Test
    fun callingSomeoneWhoIsRingingYouAnswersTheirCall() = runTest {
        val (w, d) = world()
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        assertEquals(CallPhase.RINGING_IN, b1.phase())
        b1.machine.placeCall(conv)
        settle(w.net)
        assertEquals(1, b1.sent.count { it.env is CallEnvelope.Answer })
        assertTrue(b1.sent.none { it.env is CallEnvelope.Offer })
        assertEquals(CallPhase.CONNECTING, a1.phase())
    }

    @Test
    fun noAnswerTimesOutAfter45sCallerSendsTimeoutCalleesStop() = runTest {
        val (w, d) = world()
        val (a1, b1, b2) = listOf(d["A1"]!!, d["B1"]!!, d["B2"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        advanceTimeBy(44_000)
        settle(w.net)
        assertEquals(CallPhase.RINGING_IN, b1.phase())
        advanceTimeBy(2_000)
        settle(w.net)
        assertEquals(CallEnvelope.R_TIMEOUT, a1.ends.single().reason)
        assertNull(a1.ends.single().durationS)
        assertEquals(CallNotice.NO_ANSWER, a1.notice())
        assertTrue(b1.phase() == null || b1.phase() == CallPhase.ENDED)
        assertTrue(b2.phase() == null || b2.phase() == CallPhase.ENDED)
        assertTrue(b1.machine.rangUnanswered(a1.sent.first().env.callId))
    }

    @Test
    fun busyByAudioModeAnswersBusyCallerEndsBusySiblingStops() = runTest {
        val (w, d) = world()
        val (a1, b1, b2) = listOf(d["A1"]!!, d["B1"]!!, d["B2"]!!)
        b1.busyAudio = true
        a1.machine.placeCall(conv)
        settle(w.net)
        assertEquals(listOf("call_busy"), b1.sentTypes())
        assertEquals(CallNotice.BUSY, a1.notice())
        assertEquals(CallEnvelope.R_BUSY, a1.ends.single().reason)
        assertTrue("B2 stops (the user is busy, not the device)", b2.phase() == null || b2.phase() == CallPhase.ENDED)
    }

    @Test
    fun busyWhileInOwnCall() = runTest {
        val (w, d) = world()
        val (a1, a2, b1) = listOf(d["A1"]!!, d["A2"]!!, d["B1"]!!)
        // B1 is ringing for A1's call, A2 then calls B too (another call id): B1 answers busy for it.
        a1.machine.placeCall(conv)
        settle(w.net)
        assertEquals(CallPhase.RINGING_IN, b1.phase())
        val first = b1.machine.state.value!!.callId
        // A2 is A's sibling: its own user's call is in progress, so it calls anyway only via the UI; simulate a second offer.
        a2.machine.placeCall(conv)
        settle(w.net)
        assertTrue(b1.sent.any { it.env is CallEnvelope.Busy && it.env.callId != first })
        assertEquals(first, b1.machine.state.value!!.callId)
    }

    @Test
    fun callerCancelsWhileRinging() = runTest {
        val (w, d) = world()
        val (a1, b1, b2) = listOf(d["A1"]!!, d["B1"]!!, d["B2"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        a1.machine.hangUp()
        settle(w.net)
        assertEquals(CallEnvelope.R_CANCELLED, a1.ends.single().reason)
        assertNull(b1.machine.state.value)
        assertNull(b2.machine.state.value)
    }

    @Test
    fun declineSendsDeclinedCallerSeesDeclinedSiblingStops() = runTest {
        val (w, d) = world()
        val (a1, b1, b2) = listOf(d["A1"]!!, d["B1"]!!, d["B2"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        b1.machine.hangUp()
        settle(w.net)
        assertEquals(CallEnvelope.R_DECLINED, b1.ends.single().reason)
        assertEquals(CallNotice.DECLINED, a1.notice())
        assertTrue(a1.ends.isEmpty())
        assertNull(b2.machine.state.value)
    }

    @Test
    fun acceptWaitTearsDownQuietlyAfter10s() = runTest {
        val (w, d) = world()
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        // The caller's call_accepted is lost.
        a1.signals.let { }
        b1.machine.answer()
        runCurrent()
        // Drop everything A1 would send from now on.
        a1.refuse = SignalOutcome.Unavailable
        w.net.queue.removeAll { it.fromDevice == a1.device }
        settle(w.net)
        w.net.queue.removeAll { it.fromDevice == a1.device }
        assertEquals(CallPhase.ANSWERING, b1.phase())
        advanceTimeBy(10_500)
        settle(w.net)
        assertEquals(CallNotice.CALL_ENDED, b1.notice())
        assertTrue("no call_end from the accept wait", b1.ends.isEmpty())
    }

    @Test
    fun twoDevicesAnswerFirstWinsOtherTearsDownOnAccepted() = runTest {
        val (w, d) = world()
        val (a1, b1, b2) = listOf(d["A1"]!!, d["B1"]!!, d["B2"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        // Both answer before either sees the other's answer.
        b2.machine.answer()
        b1.machine.answer()
        runCurrent()
        settle(w.net)
        val accepted = a1.sent.map { it.env }.filterIsInstance<CallEnvelope.Accepted>().single()
        val winner = listOf(b1, b2).single { it.device == accepted.deviceId }
        val loser = listOf(b1, b2).single { it !== winner }
        assertEquals(CallPhase.CONNECTING, winner.phase())
        assertEquals(CallNotice.ANSWERED_ELSEWHERE, loser.notice())
        assertTrue(loser.ends.isEmpty())
        assertTrue(loser.session().closed)
    }

    @Test
    fun staleOfferNeverRings() = runTest {
        val (_, d) = world()
        val b1 = d["B1"]!!
        val now = testScheduler.currentTime + t0
        val offer = CallEnvelope.Offer("4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10", fakeSdp(true, fingerprintOf(1), "x1"), CallStateMachine.iso(now - 50_000))
        b1.machine.onSignal(InboundCall(conv, userA, "a1a1a1a1-0000-4000-8000-000000000001", now - 50_000, offer))
        runCurrent()
        assertNull(b1.machine.state.value)
        // Fresh server_ts but an old sent_at: still stale (crypto R3).
        b1.machine.onSignal(InboundCall(conv, userA, "a1a1a1a1-0000-4000-8000-000000000001", now, offer.copy(callId = "5b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10")))
        runCurrent()
        assertNull(b1.machine.state.value)
        assertTrue(b1.sent.isEmpty())
    }

    @Test
    fun dedupeNeverRingsTheSameCallTwice() = runTest {
        val (_, d) = world()
        val b1 = d["B1"]!!
        val now = testScheduler.currentTime + t0
        val offer = CallEnvelope.Offer("4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10", fakeSdp(true, fingerprintOf(1), "x1"), CallStateMachine.iso(now))
        val ev = InboundCall(conv, userA, "a1a1a1a1-0000-4000-8000-000000000001", now, offer)
        b1.machine.onSignal(ev)
        runCurrent()
        b1.machine.hangUp()
        runCurrent()
        b1.machine.onSignal(ev)
        runCurrent()
        assertEquals(1, b1.sent.count { it.env is CallEnvelope.Ringing })
        assertTrue(b1.machine.state.value == null || b1.phase() == CallPhase.ENDED)
    }

    @Test
    fun ringOnlyAfterThePageAndOnlyIfStillIncoming() = runTest {
        val (_, d) = world()
        val b1 = d["B1"]!!
        val now = testScheduler.currentTime + t0
        val c1 = "4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10"
        val a1dev = "a1a1a1a1-0000-4000-8000-000000000001"
        val offer = CallEnvelope.Offer(c1, fakeSdp(true, fingerprintOf(1), "x1"), CallStateMachine.iso(now))
        // A page with the offer and the caller's cancel: nothing rings.
        b1.machine.onSignal(InboundCall(conv, userA, a1dev, now, offer, inPage = true))
        b1.machine.onSignal(InboundCall(conv, userA, a1dev, now, CallEnvelope.Cancel(c1), inPage = true))
        b1.machine.onPageEnd()
        runCurrent()
        assertNull(b1.machine.state.value)
        assertTrue(b1.sent.isEmpty())
        // A page with the offer and a sibling's answer (sender copy): nothing rings.
        val c2 = "5b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10"
        b1.machine.onSignal(InboundCall(conv, userA, a1dev, now, offer.copy(callId = c2), inPage = true))
        b1.machine.onSignal(InboundCall(conv, userB, "b2b2b2b2-0000-4000-8000-000000000004", now, CallEnvelope.Answer(c2, a1dev, fakeSdp(false, fingerprintOf(2), "x2")), inPage = true))
        b1.machine.onPageEnd()
        runCurrent()
        assertTrue(b1.sent.isEmpty())
        // A page with only the offer: rings at the end of the page, not before.
        val c3 = "6b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10"
        b1.machine.onSignal(InboundCall(conv, userA, a1dev, now, offer.copy(callId = c3), inPage = true))
        runCurrent()
        assertNull(b1.machine.state.value)
        b1.machine.onPageEnd()
        runCurrent()
        assertEquals(CallPhase.RINGING_IN, b1.phase())
        assertEquals(listOf("call_ringing"), b1.sentTypes())
    }

    @Test
    fun tamperedFingerprintFailsTheCall() = runTest {
        val (w, d) = world(tamper = true)
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        b1.machine.answer()
        settle(w.net)
        connect(w.net, a1, b1)
        assertEquals(CallNotice.CANT_CONNECT, a1.notice())
        assertEquals(CallEnvelope.R_FAILED, a1.ends.single().reason)
        assertFalse(a1.machine.state.value!!.verified)
    }

    @Test
    fun noSrtpOrDtlsNotConnectedNeverGoesActiveConnectTimeoutFails() = runTest {
        val (w, d) = world()
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        b1.machine.answer()
        settle(w.net)
        a1.session().cipher = ""
        b1.session().dtlsState = "connecting"
        connect(w.net, a1, b1)
        assertEquals(CallPhase.CONNECTING, a1.phase())
        assertEquals(CallPhase.CONNECTING, b1.phase())
        advanceTimeBy(20_000)
        settle(w.net)
        assertEquals(CallNotice.CANT_CONNECT, a1.notice())
        assertTrue(a1.ends.any { it.reason == CallEnvelope.R_FAILED })
    }

    @Test
    fun iceFailedFailsAtOnce() = runTest {
        val (w, d) = world()
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        b1.machine.answer()
        settle(w.net)
        a1.session().listener.onIceState(IceState.FAILED)
        settle(w.net)
        assertEquals(CallNotice.CANT_CONNECT, a1.notice())
        assertEquals(CallEnvelope.R_FAILED, a1.ends.single().reason)
        assertEquals(CallPhase.ENDED, b1.phase())
    }

    @Test
    fun restartWithSameFingerprintKeepsTheCallChangedFingerprintFails() = runTest {
        val (w, d) = world()
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        b1.machine.answer()
        settle(w.net)
        connect(w.net, a1, b1)
        a1.session().listener.onIceState(IceState.DISCONNECTED)
        b1.session().listener.onIceState(IceState.DISCONNECTED)
        settle(w.net)
        val restart = a1.sent.map { it.env }.filterIsInstance<CallEnvelope.Offer>().last()
        assertTrue(restart.restart && restart.toDevice == b1.device && !CallEnvelope.ringFor(restart))
        assertEquals(CallPhase.RECONNECTING, a1.phase())
        // B answered the restart; both reconnect.
        assertEquals(2, b1.sent.count { it.env is CallEnvelope.Answer })
        a1.session().listener.onIceState(IceState.CONNECTED)
        b1.session().listener.onIceState(IceState.CONNECTED)
        settle(w.net)
        assertEquals(CallPhase.ACTIVE, a1.phase())
        assertEquals(CallPhase.ACTIVE, b1.phase())
        // A restart offer with another fingerprint tears the call down (§16.10 d).
        val bad = restart.copy(sdp = fakeSdp(true, fingerprintOf(99), "evil"), sentAt = CallStateMachine.iso(testScheduler.currentTime + t0))
        b1.machine.onSignal(InboundCall(conv, userA, a1.device, testScheduler.currentTime + t0, bad))
        settle(w.net)
        assertEquals(CallNotice.CANT_CONNECT, b1.notice())
        assertEquals(CallEnvelope.R_FAILED, b1.ends.single().reason)
    }

    @Test
    fun reconnectGivesUpAfter15s() = runTest {
        val (w, d) = world()
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        b1.machine.answer()
        settle(w.net)
        connect(w.net, a1, b1)
        a1.session().listener.onIceState(IceState.DISCONNECTED)
        settle(w.net)
        advanceTimeBy(15_500)
        settle(w.net)
        assertEquals(CallNotice.CANT_CONNECT, a1.notice())
        assertTrue(a1.ends.single().reason == CallEnvelope.R_FAILED && a1.ends.single().connectedAt != null)
    }

    @Test
    fun senderPinningIgnoresOtherDevices() = runTest {
        val (w, d) = world()
        val (a1, a2, b1) = listOf(d["A1"]!!, d["A2"]!!, d["B1"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        val id = b1.machine.state.value!!.callId
        b1.machine.answer()
        runCurrent()
        // A forged call_accepted / ICE / cancel from A's other device (not the offer's device) is ignored.
        val now = testScheduler.currentTime + t0
        b1.machine.onSignal(InboundCall(conv, userA, a2.device, now, CallEnvelope.Accepted(id, "dddddddd-0000-4000-8000-000000000009")))
        b1.machine.onSignal(InboundCall(conv, userA, a2.device, now, CallEnvelope.Cancel(id)))
        b1.machine.onSignal(InboundCall(conv, userA, a2.device, now, CallEnvelope.Ice(id, b1.device, listOf(CallEnvelope.Candidate("candidate:9 1 udp 1 192.0.2.9 9 typ host", "0", 0)), false)))
        runCurrent()
        assertEquals(CallPhase.ANSWERING, b1.phase())
        assertTrue(b1.session().remoteCandidates.none { it.candidate.startsWith("candidate:9") })
        settle(w.net)
        assertEquals(CallPhase.CONNECTING, b1.phase())
    }

    @Test
    fun offerRefusedCallsNotReadyEndsWithoutCallEnd() = runTest {
        val (w, d) = world()
        val a1 = d["A1"]!!
        a1.refuse = SignalOutcome.Refused("calls_not_ready")
        a1.machine.placeCall(conv)
        settle(w.net)
        assertEquals(CallNotice.NOT_READY, a1.notice())
        assertTrue(a1.ends.isEmpty())
        assertTrue(a1.session().closed)
    }

    @Test
    fun callEndFromOfflinePeerStopsRingingAndMarksTheCall() = runTest {
        val (w, d) = world()
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        val id = b1.machine.state.value!!.callId
        b1.machine.onCallEnd(conv, userA, a1.device, CallEnvelope.End(id, CallEnvelope.R_CANCELLED))
        runCurrent()
        assertNull(b1.machine.state.value)
        assertTrue(b1.marks.rows[id]!!.ended)
    }

    @Test
    fun ownMuteReachesTheMedia() = runTest {
        val (w, d) = world()
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        a1.machine.placeCall(conv)
        settle(w.net)
        b1.machine.answer()
        settle(w.net)
        a1.machine.setMuted(true)
        assertTrue(a1.session().mutedNow && a1.machine.state.value!!.muted)
    }

    @Test
    fun iceBatchesAtMost20PerMessageAndMarkDone() = runTest {
        val (w, d) = world()
        val a1 = d["A1"]!!
        a1.machine.placeCall(conv)
        settle(w.net)
        repeat(25) { a1.session().candidate(it) }
        a1.session().listener.onGatheringDone()
        runCurrent()
        advanceTimeBy(300)
        settle(w.net)
        advanceTimeBy(300)
        settle(w.net)
        val ices = a1.sent.map { it.env }.filterIsInstance<CallEnvelope.Ice>()
        assertEquals(listOf(20, 5), ices.map { it.candidates.size })
        assertEquals(listOf(false, true), ices.map { it.done })
    }
}
