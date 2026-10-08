package lk.codegen.risime.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract v1.23 §23 (1:1): features, `call_switch`, renegotiation, screen sharing (§23.12 android JVM coverage). */
@OptIn(ExperimentalCoroutinesApi::class)
class CallSwitchTest {
    private val userA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val userB = "bbbbbbbb-0000-4000-8000-000000000002"
    private val conv = dmConversationId(userA, userB)
    private val t0 = 1_791_000_000_000L
    private val v123 = listOf(CallEnvelope.FEATURE_SWITCH, CallEnvelope.FEATURE_SCREEN)

    // ---------------------------------------------------------------- envelopes

    private fun read(name: String): String =
        javaClass.classLoader!!.getResource("contract/v1/examples/$name")?.readText() ?: error("missing $name")

    private fun obj(name: String) = ProtocolJson.parseToJsonElement(read(name)) as JsonObject

    @Test
    fun everyV123ExampleDecodesAndReEncodes() {
        for (n in listOf(
            "call_offer_features_payload.json", "call_answer_features_payload.json", "call_switch_request_payload.json",
            "call_switch_accept_payload.json", "call_switch_voice_payload.json", "call_offer_renegotiate_payload.json",
            "call_answer_renegotiate_payload.json", "call_media_screen_payload.json",
        )) {
            val env = CallEnvelope.decode(read(n).toByteArray()) ?: throw AssertionError("$n dropped")
            assertEquals(n, obj(n), CallEnvelope.toJson(env))
        }
        val offer = CallEnvelope.decode(read("call_offer_features_payload.json").toByteArray()) as CallEnvelope.Offer
        assertEquals(listOf("switch", "screen"), offer.features)
        assertTrue(CallEnvelope.ringFor(offer))
        val re = CallEnvelope.decode(read("call_offer_renegotiate_payload.json").toByteArray()) as CallEnvelope.Offer
        assertTrue(re.renegotiate)
        assertFalse("a re-offer never rings", CallEnvelope.ringFor(re))
        val req = CallEnvelope.decode(read("call_switch_request_payload.json").toByteArray()) as CallEnvelope.Switch
        assertEquals(CallEnvelope.SW_REQUEST, req.action)
        assertEquals(CallEnvelope.SOURCE_CAMERA, req.source)
        val media = CallEnvelope.decode(read("call_media_screen_payload.json").toByteArray()) as CallEnvelope.Media
        assertEquals(CallEnvelope.VIDEO_SCREEN, media.state)
        // §23.3 the bad re-offer carries another fingerprint than the call's first offer.
        val first = CallEnvelope.decode(read("call_offer_payload.json").toByteArray()) as CallEnvelope.Offer
        val bad = CallEnvelope.decode(read("call_offer_renegotiate_payload_bad.json").toByteArray()) as? CallEnvelope.Offer
        if (bad != null) assertNotNull(SdpRules.renegotiationProblem(first.sdp, first.sdp, bad.sdp, SdpRules.Role.OFFER))
    }

    private fun sw(extra: String) = """{"v":1,"type":"call_switch","call_id":"4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10","to_device":"c0a80101-0000-4000-8000-000000000001",$extra}""".toByteArray()

    @Test
    fun strictSwitchAndMediaValidation() {
        assertNotNull(CallEnvelope.decode(sw(""""seq":1,"action":"request","source":"screen"""")))
        assertNull(CallEnvelope.decode(sw(""""seq":0,"action":"voice"""")))
        assertNull(CallEnvelope.decode(sw(""""seq":65536,"action":"voice"""")))
        assertNull(CallEnvelope.decode(sw(""""seq":"1","action":"voice"""")))
        assertNull(CallEnvelope.decode(sw(""""seq":1,"action":"jump"""")))
        assertNull("request needs a source", CallEnvelope.decode(sw(""""seq":1,"action":"request"""")))
        assertNull("only request has a source", CallEnvelope.decode(sw(""""seq":1,"action":"accept","source":"camera"""")))
        assertNull(CallEnvelope.decode(sw(""""seq":1,"action":"request","source":"window"""")))
        val m = """{"v":1,"type":"call_media","call_id":"4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10","to_device":"c0a80101-0000-4000-8000-000000000001","""
        assertNull("camera and video disagree", CallEnvelope.decode("""$m"camera":true,"video":"screen"}""".toByteArray()))
        assertEquals(CallEnvelope.VIDEO_CAMERA, (CallEnvelope.decode("""$m"camera":true}""".toByteArray()) as CallEnvelope.Media).state)
        val tooMany = CallEnvelope.toJson(CallEnvelope.Answer("4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10", "c0a80101-0000-4000-8000-000000000001", fakeSdp(false, fingerprintOf(1), "u1"), List(9) { "f$it" }))
        assertNull(CallEnvelope.decode(tooMany.toString().toByteArray()))
    }

    @Test
    fun renegotiationSdpRules() {
        val fp = fingerprintOf(3)
        val first = fakeSdp(true, fp, "uf3")
        val re = SdpRules.prepareLocal(fakeVideoSdp(true, fp, "uf3"))
        assertNull(SdpRules.renegotiationProblem(first, first, re, SdpRules.Role.OFFER))
        assertEquals("fingerprint changed", SdpRules.renegotiationProblem(first, first, SdpRules.prepareLocal(fakeVideoSdp(true, fingerprintOf(4), "uf3")), SdpRules.Role.OFFER))
        assertEquals("ICE credentials changed", SdpRules.renegotiationProblem(first, first, SdpRules.prepareLocal(fakeVideoSdp(true, fp, "other")), SdpRules.Role.OFFER))
        // §23.3 after an ICE restart: the ICE credentials of the current session, the fingerprint of the first SDP.
        val restarted = fakeSdp(true, fp, "uf3r1")
        assertNull(SdpRules.renegotiationProblem(first, restarted, SdpRules.prepareLocal(fakeVideoSdp(true, fp, "uf3r1")), SdpRules.Role.OFFER))
        assertEquals("ICE credentials changed", SdpRules.renegotiationProblem(first, restarted, re, SdpRules.Role.OFFER))
        assertEquals("fingerprint changed", SdpRules.renegotiationProblem(first, fakeSdp(true, fingerprintOf(4), "uf3r1"), SdpRules.prepareLocal(fakeVideoSdp(true, fingerprintOf(4), "uf3r1")), SdpRules.Role.OFFER))
        val firstAnswer = fakeSdp(false, fp, "uf3")
        val reAnswer = SdpRules.prepareLocal(fakeVideoSdp(false, fp, "uf3"))
        assertNull(SdpRules.renegotiationProblem(firstAnswer, firstAnswer, reAnswer, SdpRules.Role.ANSWER))
        assertNotNull("a changed DTLS role", SdpRules.renegotiationProblem(firstAnswer, firstAnswer, reAnswer.replace("a=setup:active", "a=setup:passive"), SdpRules.Role.ANSWER))
        assertNotNull("mid order", SdpRules.renegotiationProblem(first, first, re.replace("a=mid:1", "a=mid:2").replace("BUNDLE 0 1", "BUNDLE 0 2"), SdpRules.Role.OFFER))
        assertNotNull("video rejected", SdpRules.renegotiationProblem(firstAnswer, firstAnswer, SdpRules.prepareLocal(fakeVideoSdp(false, fp, "uf3", rejectVideo = true)), SdpRules.Role.ANSWER))
    }

    @Test
    fun renderingRuleNeverInVoiceMode() {
        assertFalse(VideoRules.render(false, true, 0, 9_000, 10_000))
        assertTrue(VideoRules.render(true, true, 0, 9_000, 10_000))
    }

    // ---------------------------------------------------------------- the machine

    /** Every machine's log line, "[A1] …" (the review-fix tests read the media watch's lines). */
    private val logs = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun TestScope.world(featA: List<String> = v123, featB: List<String> = v123, strict: Boolean = false): Pair<CallNet, Map<String, CallNet.Dev>> {
        val clock = { testScheduler.currentTime + t0 }
        val net = CallNet(backgroundScope, clock)
        val devs = linkedMapOf(
            "A1" to net.Dev(userA, "a1a1a1a1-0000-4000-8000-000000000001"),
            "B1" to net.Dev(userB, "b1b1b1b1-0000-4000-8000-000000000003"),
        )
        for ((name, d) in devs) {
            val f = if (name == "A1") featA else featB
            d.machine = CallStateMachine(
                d.user, d.device, backgroundScope, d.media, d.signals, d.marks, d.environment,
                now = clock, serverNow = clock, log = { println("  [$name] $it"); logs += "[$name] $it" }, features = { f },
            )
            d.media.strict = strict
            net.devices += d
        }
        return net to devs
    }

    private suspend fun TestScope.settle(net: CallNet, rounds: Int = 10) {
        repeat(rounds) {
            runCurrent()
            net.flush()
        }
        runCurrent()
    }

    private fun CallNet.Dev.snap() = machine.state.value!!
    private fun CallNet.Dev.session() = media.sessions.last()
    private fun CallNet.Dev.switches() = sent.map { it.env }.filterIsInstance<CallEnvelope.Switch>()
    private fun CallNet.Dev.reOffers() = sent.map { it.env }.filterIsInstance<CallEnvelope.Offer>().filter { it.renegotiate }

    /** A voice call A1 → B1, both call screens visible, connected. */
    private suspend fun TestScope.voiceCall(net: CallNet, a1: CallNet.Dev, b1: CallNet.Dev) {
        a1.machine.setScreenVisible(true)
        b1.machine.setScreenVisible(true)
        assertTrue(a1.machine.placeCall(conv))
        settle(net)
        assertTrue(b1.machine.answer())
        settle(net)
        listOf(a1, b1).forEach { it.session().listener.onIceState(IceState.CONNECTED) }
        settle(net)
        advanceTimeBy(1_000)
        settle(net)
        assertEquals(CallPhase.ACTIVE, a1.snap().phase)
        assertEquals(CallPhase.ACTIVE, b1.snap().phase)
    }

    @Test
    fun featuresGateTheButtons() = runTest {
        val (net, d) = world(featB = emptyList()) // B is a v1.22 app
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        assertEquals(v123, (a1.sent.first().env as CallEnvelope.Offer).features)
        assertFalse(a1.snap().canSwitch)
        assertFalse(a1.snap().canShare)
        assertFalse(a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA))
        settle(net)
        assertTrue(a1.switches().isEmpty())
    }

    @Test
    fun requestAcceptRenegotiatesOnceFromTheCallerAndVoiceGoesBack() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        assertTrue(a1.snap().canSwitch && b1.snap().canSwitch && a1.snap().canShare)
        assertTrue(a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA))
        settle(net)
        assertEquals(CallEnvelope.SOURCE_CAMERA, a1.snap().asking)
        assertEquals(CallEnvelope.SOURCE_CAMERA, b1.snap().prompt)
        assertFalse("nothing on the video path before the accept", a1.session().videoOn)
        b1.machine.answerVideoRequest(accept = true, camera = true)
        settle(net)
        assertTrue(a1.snap().video && b1.snap().video)
        assertEquals(1, a1.reOffers().size)
        assertTrue(b1.reOffers().isEmpty())
        assertTrue(a1.session().cameraOn && b1.session().cameraOn)
        assertTrue(a1.snap().everVideo)
        // call_media after entering video mode, with `video`.
        assertEquals(CallEnvelope.VIDEO_CAMERA, a1.sent.map { it.env }.filterIsInstance<CallEnvelope.Media>().last().video)
        assertTrue(b1.snap().peerCamera)
        // Voice: both stop sending video, nothing rendered.
        b1.machine.backToVoice()
        settle(net)
        assertFalse(a1.snap().video || b1.snap().video)
        assertFalse(a1.session().cameraOn || b1.session().cameraOn)
        // A later switch: a new request, no new renegotiation.
        advanceTimeBy(1_000)
        assertTrue(b1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA))
        settle(net)
        a1.machine.answerVideoRequest(accept = true, camera = false)
        settle(net)
        assertTrue(a1.snap().video && b1.snap().video)
        assertEquals(1, a1.reOffers().size)
        assertTrue(b1.session().cameraOn)
        assertFalse(a1.session().cameraOn)
        a1.machine.hangUp()
        settle(net)
        assertEquals(CallEnvelope.MEDIA_VIDEO, a1.ends.single().media)
    }

    @Test
    fun theCalleeAsksAndTheCallerStillRenegotiates() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        assertTrue(b1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA))
        settle(net)
        a1.machine.answerVideoRequest(accept = true, camera = true)
        settle(net)
        assertEquals(1, a1.reOffers().size)
        assertTrue(b1.reOffers().isEmpty())
        assertTrue(a1.snap().video && b1.snap().video)
        assertTrue(b1.session().videoOn)
    }

    @Test
    fun declineCooldownAndNoAnswerCancel() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA)
        settle(net)
        b1.machine.answerVideoRequest(accept = false)
        settle(net)
        assertNull(a1.snap().asking)
        assertEquals(SwitchNotice.DECLINED, a1.snap().switchNotice)
        assertFalse(a1.snap().video)
        assertFalse("10-s cooldown", a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA))
        advanceTimeBy(10_500)
        settle(net)
        assertTrue(a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA))
        settle(net)
        assertNotNull(b1.snap().prompt)
        advanceTimeBy(20_500)
        settle(net)
        assertEquals(CallEnvelope.SW_CANCEL, a1.switches().last().action)
        assertNull(b1.snap().prompt)
        assertNull(a1.snap().asking)
        assertTrue(a1.reOffers().isEmpty())
    }

    @Test
    fun crossingRequestsAcceptEachOtherWithoutAPrompt() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA)
        b1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA)
        settle(net)
        assertTrue(a1.snap().video && b1.snap().video)
        assertNull(a1.snap().prompt)
        assertNull(b1.snap().prompt)
        assertEquals(1, a1.switches().count { it.action == CallEnvelope.SW_ACCEPT })
        assertEquals(1, b1.switches().count { it.action == CallEnvelope.SW_ACCEPT })
        assertEquals(1, a1.reOffers().size)
    }

    @Test
    fun replayedAndStaleSwitchesAndAnUnconsentedReOfferAreDropped() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        val id = a1.snap().callId
        // A forged re-offer before any accept: the callee doesn't apply it (crypto C2).
        val sdp = SdpRules.prepareLocal(fakeVideoSdp(true, a1.session().fp, a1.session().ufrag))
        b1.machine.onSignal(InboundCall(conv, userA, a1.device, t0, CallEnvelope.Offer(id, sdp, CallStateMachine.iso(t0), toDevice = b1.device, renegotiate = true)))
        settle(net)
        assertFalse(b1.session().videoOn)
        assertNull(b1.session().remote?.takeIf { SdpRules.mLineCount(it) == 2 })
        // A stale accept (no pending request) changes nothing.
        b1.machine.onSignal(InboundCall(conv, userA, a1.device, t0, CallEnvelope.Switch(id, b1.device, 7, CallEnvelope.SW_ACCEPT)))
        settle(net)
        assertFalse(b1.snap().video)
        // A request, then the same seq again: one prompt only; after a decline the replay doesn't prompt.
        val req = CallEnvelope.Switch(id, b1.device, 3, CallEnvelope.SW_REQUEST, CallEnvelope.SOURCE_CAMERA)
        b1.machine.onSignal(InboundCall(conv, userA, a1.device, t0, req))
        b1.machine.answerVideoRequest(accept = false)
        b1.machine.onSignal(InboundCall(conv, userA, a1.device, t0, req))
        settle(net)
        assertNull(b1.snap().prompt)
        // From another device than the selected one: dropped.
        b1.machine.onSignal(InboundCall(conv, userA, "a2a2a2a2-0000-4000-8000-000000000009", t0, req.copy(seq = 9)))
        assertNull(b1.snap().prompt)
    }

    @Test
    fun aFailedRenegotiationRollsBackKeepsAudioAndStaysVoice() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        net.devices.remove(b1) // the re-offer gets no answer (B's app went quiet), but the call goes on
        a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA)
        settle(net)
        // Hand B's accept to A by hand.
        val seq = a1.switches().last().seq
        a1.machine.onSignal(InboundCall(conv, userB, b1.device, t0, CallEnvelope.Switch(a1.snap().callId, a1.device, seq, CallEnvelope.SW_ACCEPT)))
        settle(net)
        assertEquals(1, a1.reOffers().size)
        assertTrue(a1.snap().video)
        // The server took the re-offer (B may still apply it): no rollback at 10 s, only after the reconnect bound too.
        advanceTimeBy(10_500)
        settle(net)
        assertEquals(0, a1.session().rollbacks)
        advanceTimeBy(CallStateMachine.RENEGOTIATE_DELIVERED_MS)
        settle(net)
        assertFalse(a1.snap().video)
        assertEquals(1, a1.session().rollbacks)
        assertEquals(SwitchNotice.FAILED, a1.snap().switchNotice)
        assertEquals(CallEnvelope.SW_VOICE, a1.switches().last().action)
        assertEquals(CallPhase.ACTIVE, a1.snap().phase)
        assertFalse("at most one renegotiation per call", a1.snap().canSwitch)
    }

    @Test
    fun screenShareFromVoiceModeWithTheConsentUsedOnce() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        assertFalse("a screen request needs the consent", a1.machine.requestVideo(CallEnvelope.SOURCE_SCREEN))
        val grant = Any()
        assertTrue(a1.machine.requestVideo(CallEnvelope.SOURCE_SCREEN, grant))
        settle(net)
        assertEquals(CallEnvelope.SOURCE_SCREEN, b1.snap().prompt)
        assertTrue("nothing shared before the accept", a1.session().screens.isEmpty())
        b1.machine.answerVideoRequest(accept = true, camera = false)
        settle(net)
        assertEquals(listOf(grant), a1.session().screens)
        assertTrue(a1.snap().sharing)
        assertTrue(b1.snap().peerSharing)
        assertFalse(a1.session().cameraOn)
        assertEquals(CallEnvelope.VIDEO_SCREEN, a1.sent.map { it.env }.filterIsInstance<CallEnvelope.Media>().last().video)
        // Stop: within the call, `call_media` off, the label goes.
        advanceTimeBy(1_100)
        a1.machine.stopShare()
        settle(net)
        advanceTimeBy(1_100)
        settle(net)
        assertFalse(a1.snap().sharing)
        assertFalse(b1.snap().peerSharing)
        assertEquals(1, a1.session().screenStops)
        // In video mode a new share needs a new consent; the platform's onStop also stops it.
        val g2 = Any()
        assertTrue(a1.machine.startShare(g2))
        settle(net)
        assertEquals(listOf(grant, g2), a1.session().screens)
        a1.session().onScreenStopped!!.invoke()
        settle(net)
        assertFalse(a1.snap().sharing)
        // Voice stops any share.
        assertTrue(a1.machine.startShare(Any()))
        b1.machine.backToVoice()
        settle(net)
        assertFalse(a1.snap().sharing)
        assertFalse(a1.session().screenOn)
    }

    @Test
    fun theShareStopsOnCallEnd() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        a1.machine.requestVideo(CallEnvelope.SOURCE_SCREEN, Any())
        settle(net)
        b1.machine.answerVideoRequest(accept = true)
        settle(net)
        assertTrue(a1.session().screenOn)
        b1.machine.hangUp()
        settle(net)
        assertFalse(a1.session().screenOn)
        assertTrue(a1.session().closed)
    }

    // ---------------------------------------------------------------- §23 review fixes

    private fun CallNet.Dev.restartOffers() = sent.map { it.env }.filterIsInstance<CallEnvelope.Offer>().filter { it.restart }

    private fun CallNet.Dev.phase() = machine.state.value?.phase

    /** A's ICE drops and comes back through a restart (§16.2): new ICE credentials on both sides. */
    private suspend fun TestScope.dropAndRestart(net: CallNet, a1: CallNet.Dev) {
        val before = a1.restartOffers().size
        a1.session().listener.onIceState(IceState.DISCONNECTED)
        settle(net)
        assertEquals("one restart offer", before + 1, a1.restartOffers().size)
        a1.session().listener.onIceState(IceState.CONNECTED)
        settle(net)
    }

    @Test
    fun aSwitchAfterAnIceRestartComparesTheCurrentCredentials() = runTest {
        val (net, d) = world(strict = true)
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        val ufA = a1.session().ufrag
        val ufB = b1.session().ufrag
        dropAndRestart(net, a1)
        assertTrue("the restart changed both sides' ICE credentials", a1.session().ufrag != ufA && b1.session().ufrag != ufB)
        assertEquals(CallPhase.ACTIVE, a1.phase())
        assertTrue(a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA))
        settle(net)
        b1.machine.answerVideoRequest(accept = true, camera = true)
        settle(net)
        assertNull(a1.snap().switchNotice)
        assertTrue(a1.snap().video && b1.snap().video)
        assertEquals(1, a1.reOffers().size)
        assertEquals(2, a1.session().held)
        assertEquals(2, b1.session().held)
        assertEquals(0, a1.session().rollbacks)
        // A later restart in the renegotiated call carries both m-lines.
        dropAndRestart(net, a1)
        assertEquals(CallPhase.ACTIVE, a1.phase())
        assertEquals(CallPhase.ACTIVE, b1.phase())
    }

    @Test
    fun aLateReAnswerIsStillAppliedAndTheNextRestartKeepsTheCall() = runTest {
        val (net, d) = world(strict = true)
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA)
        settle(net)
        net.holdIf = { it.env is CallEnvelope.Answer && it.fromDevice == b1.device }
        b1.machine.answerVideoRequest(accept = true, camera = true)
        settle(net)
        assertEquals("B applied the re-offer", 2, b1.session().held)
        assertEquals(1, net.held.size)
        // The answer is 12 s late: past the 10-s bound, but the re-offer was delivered.
        advanceTimeBy(12_000)
        settle(net)
        assertEquals(0, a1.session().rollbacks)
        net.release()
        settle(net)
        assertEquals("A applied the late answer: both sessions carry m=video", 2, a1.session().held)
        assertNull(a1.snap().switchNotice)
        dropAndRestart(net, a1)
        assertEquals(CallPhase.ACTIVE, a1.phase())
        assertEquals(CallPhase.ACTIVE, b1.phase())
        assertTrue(a1.ends.isEmpty() && b1.ends.isEmpty())
    }

    @Test
    fun theCalleeNeverAppliesAReOfferOlderThanTheBoundSoBothStayVoice() = runTest {
        val (net, d) = world(strict = true)
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA)
        settle(net)
        net.holdIf = { (it.env as? CallEnvelope.Offer)?.renegotiate == true }
        b1.machine.answerVideoRequest(accept = true, camera = true)
        settle(net)
        assertEquals(1, net.held.size)
        advanceTimeBy(11_000)
        net.release()
        settle(net)
        assertEquals("the stale re-offer isn't applied", 1, b1.session().held)
        assertTrue(logs.any { it.startsWith("[B1] couldn't switch to video: re-offer ") && it.contains("ms old") })
        // A gives up after the delivered bound; both sessions are voice, the next restart works.
        advanceTimeBy(CallStateMachine.RENEGOTIATE_DELIVERED_MS)
        settle(net)
        assertEquals(1, a1.session().rollbacks)
        assertFalse(a1.session().cameraOn)
        dropAndRestart(net, a1)
        assertEquals(CallPhase.ACTIVE, a1.phase())
        assertEquals(CallPhase.ACTIVE, b1.phase())
    }

    @Test
    fun anUndeliveredReOfferRollsBackAtOnce() = runTest {
        val (net, d) = world(strict = true)
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA)
        settle(net)
        net.holdIf = { (it.env as? CallEnvelope.Switch)?.action == CallEnvelope.SW_ACCEPT }
        b1.machine.answerVideoRequest(accept = true, camera = true)
        settle(net)
        a1.refuse = SignalOutcome.Unavailable // the re-offer can't be sent
        net.release()
        settle(net)
        assertEquals(1, a1.session().rollbacks)
        assertFalse(a1.snap().video)
        assertFalse("the camera stopped with the rollback", a1.session().cameraOn)
        assertEquals(SwitchNotice.FAILED, a1.snap().switchNotice)
    }

    @Test
    fun aDropWhileTheReOfferIsOutRestartsAsSoonAsItResolves() = runTest {
        val (net, d) = world(strict = true)
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA)
        settle(net)
        net.holdIf = { it.env is CallEnvelope.Answer && it.fromDevice == b1.device }
        b1.machine.answerVideoRequest(accept = true, camera = true)
        settle(net)
        // B goes back to voice while A's re-offer is still out: A enters voice mode.
        b1.machine.backToVoice()
        settle(net)
        assertFalse(a1.snap().video)
        assertFalse(a1.session().cameraOn)
        val before = a1.restartOffers().size
        a1.session().listener.onIceState(IceState.DISCONNECTED)
        settle(net)
        assertEquals(CallPhase.RECONNECTING, a1.phase())
        assertEquals("one offer at a time", before, a1.restartOffers().size)
        net.release()
        settle(net)
        assertEquals("the restart ran once the re-offer resolved", before + 1, a1.restartOffers().size)
        assertEquals(2, a1.session().held)
        a1.session().listener.onIceState(IceState.CONNECTED)
        settle(net)
        assertEquals(CallPhase.ACTIVE, a1.phase())
        assertEquals(CallPhase.ACTIVE, b1.phase())
    }

    @Test
    fun theMediaWatchLogsStalledAudioAndSilentStats() = runTest {
        val (net, d) = world()
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        a1.session().bytes = 1_000
        advanceTimeBy(9_000)
        settle(net)
        assertTrue(logs.any { it.startsWith("[A1] media watch: audio_recv stopped advancing at 1000") })
        assertEquals(CallPhase.ACTIVE, a1.phase())
        a1.session().bytes = 2_000
        advanceTimeBy(2_100)
        settle(net)
        assertTrue(logs.any { it.startsWith("[A1] media watch: audio_recv advancing again") })
        a1.session().statsHang = true
        advanceTimeBy(CallStateMachine.STATS_TIMEOUT_MS + 2_500)
        settle(net)
        assertTrue("a getStats that never calls back is logged, the watch goes on", logs.any { it.startsWith("[A1] media watch: getStats gave no result") })
        b1.machine.hangUp()
        settle(net)
    }

    @Test
    fun theCameraIsOffAfterEveryExitFromVideo() = runTest {
        val (net, d) = world(strict = true)
        val (a1, b1) = d["A1"]!! to d["B1"]!!
        voiceCall(net, a1, b1)
        // Decline: the camera never starts.
        a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA)
        settle(net)
        b1.machine.answerVideoRequest(accept = false)
        settle(net)
        assertFalse(a1.session().cameraOn)
        advanceTimeBy(10_500)
        settle(net)
        // Accept, then voice: off on both.
        a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA)
        settle(net)
        b1.machine.answerVideoRequest(accept = true, camera = true)
        settle(net)
        assertTrue(a1.session().cameraOn && b1.session().cameraOn)
        a1.machine.backToVoice()
        settle(net)
        assertFalse(a1.session().cameraOn || b1.session().cameraOn)
        // Video again, then the end: the session closes (the real one stops the capturer in close()).
        advanceTimeBy(1_000)
        a1.machine.requestVideo(CallEnvelope.SOURCE_CAMERA)
        settle(net)
        b1.machine.answerVideoRequest(accept = true, camera = true)
        settle(net)
        assertTrue(a1.session().cameraOn)
        a1.machine.hangUp()
        settle(net)
        assertTrue(a1.session().closed && b1.session().closed)
    }
}
