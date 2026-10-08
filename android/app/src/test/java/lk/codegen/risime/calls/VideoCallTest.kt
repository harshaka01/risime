package lk.codegen.risime.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract v1.18 §19 on the call machine and the SDP rules (§19.12 android JVM coverage). */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoCallTest {
    private val userA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val userB = "bbbbbbbb-0000-4000-8000-000000000002"
    private val conv = dmConversationId(userA, userB)
    private val t0 = 1_791_000_000_000L

    private fun TestScope.world(ids: Map<String, List<String>> = emptyMap()): Pair<CallNet, Map<String, CallNet.Dev>> {
        val clock = { testScheduler.currentTime + t0 }
        val net = CallNet(backgroundScope, clock)
        val devs = linkedMapOf(
            "A1" to net.Dev(userA, "a1a1a1a1-0000-4000-8000-000000000001"),
            "B1" to net.Dev(userB, "b1b1b1b1-0000-4000-8000-000000000003"),
            "B2" to net.Dev(userB, "b2b2b2b2-0000-4000-8000-000000000004"),
        )
        for ((name, d) in devs) {
            val queue = ids[name].orEmpty().toMutableList()
            d.machine = CallStateMachine(
                d.user, d.device, backgroundScope, d.media, d.signals, d.marks, d.environment,
                now = clock, serverNow = clock, log = { println("  [$name] $it") },
                newCallId = { if (queue.isNotEmpty()) queue.removeAt(0) else java.util.UUID.randomUUID().toString() },
            )
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

    private fun CallNet.Dev.snap() = machine.state.value
    private fun CallNet.Dev.session() = media.sessions.last()
    private fun CallNet.Dev.mediaSent() = sent.map { it.env }.filterIsInstance<CallEnvelope.Media>()

    private suspend fun TestScope.connect(net: CallNet, vararg devs: CallNet.Dev) {
        devs.forEach { it.session().listener.onIceState(IceState.CONNECTED) }
        settle(net)
        advanceTimeBy(1_000)
        settle(net)
    }

    /** A video call from A1 to B (both screens visible), answered on B1 with [camera]. */
    private suspend fun TestScope.videoCall(net: CallNet, a1: CallNet.Dev, b1: CallNet.Dev, camera: Boolean = true) {
        a1.machine.setScreenVisible(true)
        b1.machine.setScreenVisible(true)
        assertTrue(a1.machine.placeCall(conv, video = true, camera = true))
        settle(net)
        assertTrue(b1.machine.answer(camera = camera))
        settle(net)
        connect(net, a1, b1)
    }

    @Test
    fun videoCallBothWaysCarriesMediaEverywhereAndTheCameraFollowsTheUser() = runTest {
        val (net, d) = world()
        val (a1, b1, b2) = listOf(d["A1"]!!, d["B1"]!!, d["B2"]!!)
        a1.machine.setScreenVisible(true)
        b1.machine.setScreenVisible(true)
        b2.machine.setScreenVisible(true)
        a1.machine.placeCall(conv, video = true, camera = true)
        settle(net)
        // The caller's local preview runs from the start; the offer is a strict §19.4 video SDP.
        assertTrue(a1.session().video && a1.session().cameraOn)
        val offer = a1.sent.first().env as CallEnvelope.Offer
        assertEquals(CallEnvelope.MEDIA_VIDEO, offer.media)
        assertNull(SdpRules.validate(offer.sdp, SdpRules.Role.OFFER, video = true))
        SdpRules.DENIED_EXTENSIONS.forEach { assertFalse(it, offer.sdp.contains(it)) }
        // Never the camera unasked (android A3): both callee devices ring with no media session at all.
        assertEquals(CallPhase.RINGING_IN, b1.snap()!!.phase)
        assertTrue(b1.snap()!!.video)
        assertTrue(b1.media.sessions.isEmpty() && b2.media.sessions.isEmpty())
        assertEquals("Incoming video call", CallTexts.status(CallPhase.RINGING_IN, null, video = true))

        b1.machine.answer(camera = true)
        settle(net)
        assertTrue(b1.session().cameraOn)
        val answer = b1.sent.map { it.env }.filterIsInstance<CallEnvelope.Answer>().single()
        assertNull(SdpRules.validate(answer.sdp, SdpRules.Role.ANSWER, video = true))
        connect(net, a1, b1)
        assertEquals(CallPhase.ACTIVE, a1.snap()!!.phase)
        assertEquals(CallPhase.ACTIVE, b1.snap()!!.phase)
        // §19.2: every signal of the call carried media "video", both ways.
        assertTrue((a1.sent + b1.sent).filter { !it.durable }.all { it.media == CallEnvelope.MEDIA_VIDEO })
        assertTrue("both cameras on: no call_media yet", a1.mediaSent().isEmpty() && b1.mediaSent().isEmpty())

        // Camera off on B: setTrack(null) via the session and call_media camera=false to A1 only.
        b1.machine.setCameraWanted(false)
        settle(net)
        assertFalse(b1.session().cameraOn)
        val off = b1.mediaSent().single()
        assertFalse(off.camera)
        assertEquals(a1.device, off.toDevice)
        assertFalse(a1.snap()!!.peerCamera)
        // On again within the second: the last state wins, at most one per second.
        b1.machine.setCameraWanted(true)
        settle(net)
        assertEquals(1, b1.mediaSent().size)
        advanceTimeBy(1_100)
        settle(net)
        assertEquals(listOf(false, true), b1.mediaSent().map { it.camera })
        assertTrue(a1.snap()!!.peerCamera)

        // The call screen hidden (Back to chats, home, screen off): the camera stops and says so; back: it restarts.
        advanceTimeBy(1_100)
        a1.machine.setScreenVisible(false)
        settle(net)
        assertFalse(a1.session().cameraOn)
        assertFalse(a1.mediaSent().last().camera)
        assertEquals(CallPhase.ACTIVE, a1.snap()!!.phase) // audio goes on
        advanceTimeBy(1_100)
        a1.machine.setScreenVisible(true)
        settle(net)
        assertTrue(a1.session().cameraOn)
        assertTrue(a1.mediaSent().last().camera)

        // Switching cameras is local only.
        a1.machine.switchCamera()
        assertEquals(1, a1.session().switches)

        advanceTimeBy(190_000)
        a1.machine.hangUp()
        settle(net)
        val end = a1.ends.single()
        assertEquals(CallEnvelope.MEDIA_VIDEO, end.media)
        assertEquals("Video call · 3:1${end.durationS!! % 10}", CallLines.line(end.reason, true, end.durationS, false, video = true).text)
        assertTrue(a1.session().closed && b1.session().closed)
    }

    @Test
    fun answerWithoutVideoTellsThePeerAndCanTurnOnLater() = runTest {
        val (net, d) = world()
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        videoCall(net, a1, b1, camera = false)
        assertEquals(CallPhase.ACTIVE, b1.snap()!!.phase)
        assertFalse(b1.session().cameraOn)
        assertTrue(b1.session().camera.none { it })
        // Connected with the camera off: the peer is told at once (their avatar, not a black frame).
        assertEquals(listOf(false), b1.mediaSent().map { it.camera })
        assertFalse(a1.snap()!!.peerCamera)
        advanceTimeBy(1_100)
        b1.machine.setCameraWanted(true)
        settle(net)
        assertTrue(b1.session().cameraOn)
        assertTrue(a1.snap()!!.peerCamera)
    }

    @Test
    fun aTelecomAnswerNeverTurnsTheCameraOn() = runTest {
        val (net, d) = world()
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        a1.machine.setScreenVisible(true)
        b1.machine.setScreenVisible(true)
        a1.machine.placeCall(conv, video = true)
        settle(net)
        b1.machine.answer(b1.snap()!!.callId) // the default: no camera (a watch, the car, the notification)
        settle(net)
        assertFalse(b1.session().cameraOn)
        assertFalse(b1.snap()!!.wantCamera)
    }

    @Test
    fun aDeniedCameraStillCallsWithTheCameraOff() = runTest {
        val (net, d) = world()
        val a1 = d["A1"]!!
        a1.machine.setScreenVisible(true)
        a1.machine.placeCall(conv, video = true, camera = false)
        settle(net)
        assertEquals(CallEnvelope.MEDIA_VIDEO, (a1.sent.first().env as CallEnvelope.Offer).media)
        assertFalse(a1.session().cameraOn)
    }

    @Test
    fun anAudioOnlyAnswerMakesItAnAudioCall() = runTest {
        val (net, d) = world()
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        b1.media.rejectVideo = true
        videoCall(net, a1, b1)
        assertEquals(CallPhase.ACTIVE, a1.snap()!!.phase)
        assertFalse(a1.snap()!!.video)
        assertFalse(a1.session().cameraOn)
    }

    @Test
    fun glareAcrossMediaUsesTheCameraOnlyIfMyLosingCallWasVideo() = runTest {
        // A's id is lower: A's video call wins; B's losing call was voice → B answers with the camera off.
        val (net, d) = world(ids = mapOf("A1" to listOf("11111111-0000-4000-8000-000000000001"), "B1" to listOf("99999999-0000-4000-8000-000000000009")))
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        listOf(a1, b1).forEach { it.machine.setScreenVisible(true) }
        a1.machine.placeCall(conv, video = true, camera = true)
        b1.machine.placeCall(conv)
        settle(net)
        assertEquals("11111111-0000-4000-8000-000000000001", b1.snap()!!.callId)
        assertTrue(b1.snap()!!.video)
        assertFalse(b1.session().cameraOn)

        // The other way: B's video call loses to A's voice call → A's automatic answer is a voice call.
        val (net2, d2) = world(ids = mapOf("A1" to listOf("11111111-0000-4000-8000-00000000000a"), "B1" to listOf("99999999-0000-4000-8000-00000000000b")))
        val (x1, y1) = listOf(d2["A1"]!!, d2["B1"]!!)
        listOf(x1, y1).forEach { it.machine.setScreenVisible(true) }
        y1.machine.placeCall(dmConversationId(userA, userB), video = true, camera = true)
        x1.machine.placeCall(dmConversationId(userA, userB))
        settle(net2)
        assertEquals("11111111-0000-4000-8000-00000000000a", y1.snap()!!.callId)
        assertFalse(y1.snap()!!.video)

        // Same media both ways, video: the loser's camera choice carries over.
        val (net3, d3) = world(ids = mapOf("A1" to listOf("11111111-0000-4000-8000-00000000000c"), "B1" to listOf("99999999-0000-4000-8000-00000000000d")))
        val (p1, q1) = listOf(d3["A1"]!!, d3["B1"]!!)
        listOf(p1, q1).forEach { it.machine.setScreenVisible(true) }
        p1.machine.placeCall(dmConversationId(userA, userB), video = true, camera = true)
        q1.machine.placeCall(dmConversationId(userA, userB), video = true, camera = true)
        settle(net3)
        assertTrue(q1.session().cameraOn)
    }

    @Test
    fun mediaIsBoundAndCallMediaIsPinnedToTheSelectedDevice() = runTest {
        val (net, d) = world()
        val (a1, b1, b2) = listOf(d["A1"]!!, d["B1"]!!, d["B2"]!!)
        videoCall(net, a1, b1)
        val callId = a1.snap()!!.callId
        // A signal of this call with the wrong cleartext media is dropped (crypto K6).
        val before = a1.session().remoteCandidates.size
        a1.machine.onSignal(InboundCall(conv, userB, b1.device, t0, CallEnvelope.Ice(callId, a1.device, listOf(CallEnvelope.Candidate("candidate:9 1 udp 1 192.0.2.9 9 typ host", "0", 0)), false), media = CallEnvelope.MEDIA_AUDIO))
        assertEquals(before, a1.session().remoteCandidates.size)
        // call_media from B2 (not the selected device) or addressed elsewhere: ignored.
        a1.machine.onSignal(InboundCall(conv, userB, b2.device, t0, CallEnvelope.Media(callId, a1.device, false), media = CallEnvelope.MEDIA_VIDEO))
        assertTrue(a1.snap()!!.peerCamera)
        a1.machine.onSignal(InboundCall(conv, userB, b1.device, t0, CallEnvelope.Media(callId, b2.device, false), media = CallEnvelope.MEDIA_VIDEO))
        assertTrue(a1.snap()!!.peerCamera)
        a1.machine.onSignal(InboundCall(conv, userB, b1.device, t0, CallEnvelope.Media(callId, a1.device, false), media = CallEnvelope.MEDIA_VIDEO))
        assertFalse(a1.snap()!!.peerCamera)
    }

    @Test
    fun voiceCallsAreUnchanged() = runTest {
        val (net, d) = world()
        val (a1, b1) = listOf(d["A1"]!!, d["B1"]!!)
        a1.machine.setScreenVisible(true)
        a1.machine.placeCall(conv)
        settle(net)
        b1.machine.answer()
        settle(net)
        connect(net, a1, b1)
        assertEquals(CallPhase.ACTIVE, a1.snap()!!.phase)
        assertFalse(a1.snap()!!.video)
        assertTrue(a1.session().camera.isEmpty())
        assertTrue((a1.sent + b1.sent).all { it.media == CallEnvelope.MEDIA_AUDIO })
        assertEquals(1, SdpRules.mLineCount((a1.sent.first().env as CallEnvelope.Offer).sdp))
    }

    // ---- §19.5 the frozen-frame rule ----

    @Test
    fun peerAvatarAfterThreeSecondsWithoutAFrameAndOnCameraOff() {
        val t = 100_000L
        assertFalse("no frame yet", VideoRules.showPeerVideo(true, 0, 0, t))
        assertTrue(VideoRules.showPeerVideo(true, 0, t - 500, t))
        assertFalse("frozen", VideoRules.showPeerVideo(true, 0, t - 3_000, t))
        assertFalse("camera: false", VideoRules.showPeerVideo(false, t - 10_000, t - 20_000, t))
        // A lost camera:true: frames newer than the change bring the video back.
        assertTrue(VideoRules.showPeerVideo(false, t - 10_000, t - 100, t))
    }

    // ---- §19.3 history lines ----

    @Test
    fun videoHistoryLines() {
        assertEquals("Video call · 3:12", CallLines.line(CallEnvelope.R_HANGUP, true, 192, false, video = true).text)
        assertEquals("Video call · No answer", CallLines.line(CallEnvelope.R_TIMEOUT, true, null, false, video = true).text)
        assertEquals(CallLines.Line("Missed video call", true), CallLines.line(CallEnvelope.R_CANCELLED, false, null, false, video = true))
        assertEquals(CallLines.Line("Missed video call", true), CallLines.line(CallEnvelope.R_BUSY, false, null, false, video = true))
        assertEquals("Video call · Declined", CallLines.line(CallEnvelope.R_DECLINED, false, null, false, video = true).text)
        assertEquals("Declined video call", CallLines.line(CallEnvelope.R_DECLINED, true, null, false, video = true).text)
        assertEquals("Video call · Couldn't connect", CallLines.line(CallEnvelope.R_FAILED, true, null, false, video = true).text)
        assertEquals("Missed video call", CallLines.line(CallEnvelope.R_FAILED, false, null, true, video = true).text)
        assertTrue(CallLines.isVideo("Video call · 3:12") && CallLines.isVideo("Missed video call") && !CallLines.isVideo("Voice call · 0:07"))
        assertTrue(CallLines.isMissed("Missed video call") && CallLines.isMissed("Missed voice call"))
    }

    // ---- §19.4 the SDP drops (crypto K1–K5) ----

    private val ok = fakeVideoSdp(offer = false, fp = fingerprintOf(3), ufrag = "uf3", leaky = false)

    private fun answer(mut: (String) -> String) = SdpRules.validate(mut(ok), SdpRules.Role.ANSWER, video = true)

    @Test
    fun videoSdpRules() {
        assertNull(SdpRules.validate(ok, SdpRules.Role.ANSWER, video = true))
        assertNotNull("unbundled", answer { it.replace("a=group:BUNDLE 0 1", "a=group:BUNDLE 0") })
        assertNotNull("no BUNDLE", answer { it.replace("a=group:BUNDLE 0 1\r\n", "") })
        assertNotNull("rtcp-mux missing", answer { it.replaceFirst("a=rtcp-mux\r\na=rtcp-rsize", "a=rtcp-rsize") })
        val other = fingerprintOf(4)
        assertNotNull("two different fingerprints", answer { s -> val i = s.lastIndexOf("a=fingerprint:"); s.substring(0, i) + s.substring(i).replaceFirst(fingerprintOf(3), other) })
        assertNotNull("H264 in an answer", answer { it.replace("UDP/TLS/RTP/SAVPF 96 97", "UDP/TLS/RTP/SAVPF 96 97 102").replace("a=fmtp:97 apt=96\r\n", "a=fmtp:97 apt=96\r\na=rtpmap:102 H264/90000\r\n") })
        assertNotNull("red", answer { it.replace("UDP/TLS/RTP/SAVPF 96 97", "UDP/TLS/RTP/SAVPF 96 97 116").replace("a=fmtp:97 apt=96\r\n", "a=fmtp:97 apt=96\r\na=rtpmap:116 red/90000\r\n") })
        assertNotNull("ulpfec", answer { it.replace("UDP/TLS/RTP/SAVPF 96 97", "UDP/TLS/RTP/SAVPF 96 97 117").replace("a=fmtp:97 apt=96\r\n", "a=fmtp:97 apt=96\r\na=rtpmap:117 ulpfec/90000\r\n") })
        assertNotNull("rtx not for VP8", answer { it.replace("a=fmtp:97 apt=96", "a=fmtp:97 apt=100") })
        assertNotNull("a=rid", answer { it.replace("a=ssrc-group:FID", "a=rid:h send\r\na=ssrc-group:FID") })
        assertNotNull("a=simulcast", answer { it.replace("a=ssrc-group:FID", "a=simulcast:send h;l\r\na=ssrc-group:FID") })
        assertNotNull("SIM ssrc-group", answer { it.replace("a=ssrc-group:FID 1001 1002", "a=ssrc-group:SIM 1001 1002") })
        assertNotNull("b=AS over 1500", answer { it.replace("a=mid:1\r\n", "a=mid:1\r\nb=AS:2500\r\n") })
        assertNotNull("no VP8", answer { it.replace("VP8/90000", "VP9/90000") })
        assertNotNull("one m-line", SdpRules.validate(fakeSdp(false, fingerprintOf(3), "uf3"), SdpRules.Role.ANSWER, video = true))
        assertNotNull("video port 0 in an offer", SdpRules.validate(fakeVideoSdp(true, fingerprintOf(3), "uf3", leaky = false, rejectVideo = true), SdpRules.Role.OFFER, video = true))
        assertNull("video rejected in an answer: an audio call", SdpRules.validate(fakeVideoSdp(false, fingerprintOf(3), "uf3", leaky = false, rejectVideo = true), SdpRules.Role.ANSWER, video = true))
        // Each denied extension rejects a video SDP...
        for (ext in SdpRules.DENIED_EXTENSIONS) assertNotNull(ext, answer { it.replace("a=mid:1\r\n", "a=mid:1\r\na=extmap:9 $ext\r\n") })
        // ...while a voice SDP is still rejected only for the audio level (v1.13–v1.17 peers keep working).
        val voice = fakeSdp(false, fingerprintOf(3), "uf3")
        assertNull(SdpRules.validate(voice.replace("a=mid:0\r\n", "a=mid:0\r\na=extmap:9 ${SdpRules.CSRC_AUDIO_LEVEL}\r\n"), SdpRules.Role.ANSWER))
        assertNotNull(SdpRules.validate(voice.replace("a=mid:0\r\n", "a=mid:0\r\na=extmap:9 ${SdpRules.AUDIO_LEVEL}\r\n"), SdpRules.Role.ANSWER))
        // What the app produces carries none of them (voice too).
        val produced = SdpRules.prepareLocal(fakeVideoSdp(true, fingerprintOf(3), "uf3", leaky = true))
        SdpRules.DENIED_EXTENSIONS.forEach { assertFalse(it, produced.contains(it)) }
        assertNull(SdpRules.validate(produced, SdpRules.Role.OFFER, video = true))
        // The contract's bad example (simulcast) is dropped by the envelope's strict decode.
        val bad = javaClass.classLoader!!.getResource("contract/v1/examples/call_offer_video_payload_bad.json")!!.readText()
        assertNull(CallEnvelope.decode(bad.toByteArray()))
        // A direction other than sendrecv in an offer.
        assertNotNull(SdpRules.validate(produced.replaceFirst("a=sendrecv", "a=recvonly"), SdpRules.Role.OFFER, video = true))
    }

    @Test
    fun callMediaEnvelopeIsStrict() {
        val ok = """{"v":1,"type":"call_media","call_id":"9a3f6c2d-7e1b-4c58-a0d4-3b2e1f0c9d87","to_device":"c0a80101-0000-4000-8000-000000000004","camera":true}"""
        assertEquals(CallEnvelope.Media("9a3f6c2d-7e1b-4c58-a0d4-3b2e1f0c9d87", "c0a80101-0000-4000-8000-000000000004", true), CallEnvelope.decode(ok.toByteArray()))
        assertNull(CallEnvelope.decode(ok.replace("true}", "\"yes\"}").toByteArray()))
        assertNull(CallEnvelope.decode(ok.replace("c0a80101-0000-4000-8000-000000000004", "not-a-uuid").toByteArray()))
        assertNull(CallEnvelope.decode(ok.replace(",\"camera\":true", "").toByteArray()))
        // Encode → decode round trip.
        val m = CallEnvelope.Media("9a3f6c2d-7e1b-4c58-a0d4-3b2e1f0c9d87", "c0a80101-0000-4000-8000-000000000004", false)
        assertEquals(m, CallEnvelope.decode(CallEnvelope.encode(m)))
        assertFalse(CallEnvelope.ringFor(m))
        // An offer's media must be audio or video.
        val offer = javaClass.classLoader!!.getResource("contract/v1/examples/call_offer_video_payload.json")!!.readText()
        assertNull(CallEnvelope.decode(offer.replace("\"media\":\"video\"", "\"media\":\"screen\"").toByteArray()))
        // A video SDP under a voice offer fails the voice rules (one m-line).
        assertNull(CallEnvelope.decode(offer.replace("\"media\":\"video\"", "\"media\":\"audio\"").toByteArray()))
    }
}
