package lk.codegen.risime.calls

import androidx.core.telecom.CallEndpointCompat
import lk.codegen.risime.calls.EndpointUi.Kind.BLUETOOTH
import lk.codegen.risime.calls.EndpointUi.Kind.EARPIECE
import lk.codegen.risime.calls.EndpointUi.Kind.SPEAKER
import lk.codegen.risime.calls.EndpointUi.Kind.WIRED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0 audio routing: the route policy, the per-call state, the merge with AudioManager, the tap. */
class RoutePolicyTest {
    private val both = setOf(EARPIECE, SPEAKER)
    private val inCall = listOf(CallPhase.CALLING, CallPhase.RINGING_OUT, CallPhase.ANSWERING, CallPhase.CONNECTING, CallPhase.ACTIVE, CallPhase.RECONNECTING)

    private fun target(
        video: Boolean = false,
        phase: CallPhase? = CallPhase.ACTIVE,
        available: Set<EndpointUi.Kind> = both,
        current: EndpointUi.Kind? = EARPIECE,
        userPicked: Boolean = false,
        btConnected: Boolean = false,
        btDisconnected: Boolean = false,
        videoOn: Boolean = false,
    ) = routeTarget(video, phase, available, current, userPicked, btConnected, btDisconnected, videoOn).target

    // ---- routeTarget (pure) ----

    @Test fun videoCallsDefaultToTheSpeakerInEveryInCallPhase() {
        inCall.forEach { assertEquals(it.name, SPEAKER, target(video = true, phase = it)) }
        assertNull(target(video = true, current = null))
        assertNull(target(video = true, current = SPEAKER))
    }

    @Test fun neverWhileRingingInOrEnded() {
        assertNull(target(video = true, phase = CallPhase.RINGING_IN))
        assertNull(target(video = true, phase = CallPhase.ENDED))
        assertNull(target(video = true, phase = null))
        assertNull(target(phase = CallPhase.RINGING_IN, available = both + BLUETOOTH, btConnected = true))
    }

    @Test fun voiceCallsStayOnTheEarpiece() {
        assertNull(target(video = false))
        assertNull(target(video = false, current = SPEAKER))
    }

    @Test fun bluetoothOrAHeadsetWinsOverTheVideoSpeaker() {
        assertEquals(BLUETOOTH, target(video = true, available = both + BLUETOOTH))
        assertEquals(WIRED, target(video = true, available = both + WIRED))
        assertNull(target(video = true, available = both + BLUETOOTH, current = BLUETOOTH))
        assertNull(target(video = true, available = both + WIRED + BLUETOOTH, current = WIRED))
        assertEquals(BLUETOOTH, target(video = false, available = both + BLUETOOTH))
    }

    @Test fun theUsersPickIsNeverOverridden() {
        assertNull(target(video = true, userPicked = true))
        assertNull(target(video = true, available = both + BLUETOOTH, userPicked = true))
        assertNull(target(video = true, userPicked = true, btDisconnected = true, available = both))
    }

    @Test fun aBluetoothConnectTakesTheRouteEvenAfterAPick() {
        assertEquals(BLUETOOTH, target(available = both + BLUETOOTH, current = SPEAKER, userPicked = true, btConnected = true))
        assertNull(target(available = both + BLUETOOTH, current = BLUETOOTH, btConnected = true))
    }

    @Test fun aBluetoothDisconnectFallsBackToSpeakerForVideoAndEarpieceForVoice() {
        assertEquals(SPEAKER, target(video = true, current = EARPIECE, btDisconnected = true))
        assertEquals(EARPIECE, target(video = false, current = SPEAKER, btDisconnected = true))
        assertEquals(WIRED, target(video = true, available = both + WIRED, current = SPEAKER, btDisconnected = true))
    }

    @Test fun switchingToVideoMovesToTheSpeakerUnlessAHeadsetIsThere() {
        assertEquals(SPEAKER, target(video = true, videoOn = true))
        assertNull(target(video = true, videoOn = true, available = both + BLUETOOTH, current = BLUETOOTH))
    }

    @Test fun aRouteThatIsNotAvailableIsNeverAsked() {
        assertNull(target(video = true, available = setOf(EARPIECE)))
        assertNull(target(video = false, available = setOf(SPEAKER), current = SPEAKER, btDisconnected = true))
    }

    @Test fun everyDecisionHasAReason() {
        assertEquals("video call", routeTarget(true, CallPhase.ACTIVE, both, EARPIECE, false).reason)
        assertEquals("the user's pick", routeTarget(true, CallPhase.ACTIVE, both, EARPIECE, true).reason)
        assertEquals("voice call on EARPIECE", routeTarget(false, CallPhase.ACTIVE, both, EARPIECE, false).reason)
        assertEquals("not in a call phase (RINGING_IN)", routeTarget(true, CallPhase.RINGING_IN, both, EARPIECE, false).reason)
    }

    // ---- RoutePolicyState (per call) ----

    @Test fun stateVideoStartsOnTheSpeakerAndKeepsTheUsersPick() {
        val p = RoutePolicyState()
        assertEquals(SPEAKER, p.decide(true, CallPhase.CONNECTING, both, EARPIECE).target)
        assertNull(p.decide(true, CallPhase.ACTIVE, both, SPEAKER).target)
        p.userPicked(EARPIECE)
        assertNull(p.decide(true, CallPhase.ACTIVE, both, EARPIECE).target)
        assertNull(p.decide(true, CallPhase.ACTIVE, both, EARPIECE).target)
    }

    /** The v1.23 hook (CallManager.onCallVideoChanged → videoChanged): voice → video moves to the speaker once. */
    @Test fun stateVoiceToVideoMovesToTheSpeakerThenTheUsersPickHolds() {
        val p = RoutePolicyState()
        assertNull(p.decide(false, CallPhase.ACTIVE, both, EARPIECE).target)
        p.userPicked(EARPIECE) // an earlier pick in the voice call
        p.videoChanged(true)
        assertEquals("switched to video", p.decide(true, CallPhase.ACTIVE, both, EARPIECE).reason)
        assertEquals(SPEAKER, p.decide(true, CallPhase.ACTIVE, both, EARPIECE).target)
        assertNull(p.decide(true, CallPhase.ACTIVE, both, SPEAKER).target)
        // The user goes back to the earpiece afterwards: never overridden.
        p.userPicked(EARPIECE)
        assertNull(p.decide(true, CallPhase.ACTIVE, both, EARPIECE).target)
        // Video off does nothing to the route.
        p.videoChanged(false)
        assertNull(p.decide(false, CallPhase.ACTIVE, both, EARPIECE).target)
    }

    @Test fun stateVoiceToVideoWithBluetoothStaysOnBluetooth() {
        val p = RoutePolicyState()
        val bt = both + BLUETOOTH
        assertNull(p.decide(false, CallPhase.ACTIVE, bt, BLUETOOTH).target)
        p.videoChanged(true)
        assertNull(p.decide(true, CallPhase.ACTIVE, bt, BLUETOOTH).target)
    }

    @Test fun stateBluetoothConnectMidCallWinsOverAnEarlierPickAndAPickAfterItHolds() {
        val p = RoutePolicyState()
        assertNull(p.decide(false, CallPhase.ACTIVE, both, EARPIECE).target)
        p.userPicked(SPEAKER)
        assertNull(p.decide(false, CallPhase.ACTIVE, both, SPEAKER).target)
        // Buds connect: Bluetooth.
        assertEquals("bluetooth connected", p.decide(false, CallPhase.ACTIVE, both + BLUETOOTH, SPEAKER).reason)
        assertNull(p.decide(false, CallPhase.ACTIVE, both + BLUETOOTH, BLUETOOTH).target)
        // The user picks the speaker after that: kept.
        p.userPicked(SPEAKER)
        assertNull(p.decide(false, CallPhase.ACTIVE, both + BLUETOOTH, SPEAKER).target)
    }

    @Test fun stateBluetoothAtTheStartTakesTheRoute() {
        val p = RoutePolicyState()
        assertEquals(BLUETOOTH, p.decide(true, CallPhase.CALLING, both + BLUETOOTH, SPEAKER).target)
        assertEquals(BLUETOOTH, p.decide(false, CallPhase.ACTIVE, both + BLUETOOTH, EARPIECE).target)
    }

    @Test fun stateBluetoothDisconnectFallsBack() {
        val video = RoutePolicyState()
        assertNull(video.decide(true, CallPhase.ACTIVE, both + BLUETOOTH, BLUETOOTH).target)
        assertEquals(SPEAKER, video.decide(true, CallPhase.ACTIVE, both, EARPIECE).target)
        assertNull(video.decide(true, CallPhase.ACTIVE, both, SPEAKER).target)

        val voice = RoutePolicyState()
        assertNull(voice.decide(false, CallPhase.ACTIVE, both + BLUETOOTH, BLUETOOTH).target)
        assertEquals(EARPIECE, voice.decide(false, CallPhase.ACTIVE, both, SPEAKER).target)
        assertNull(voice.decide(false, CallPhase.ACTIVE, both, EARPIECE).target)
        // Settled: a voice call on the speaker (the user's choice through the system) stays.
        assertNull(voice.decide(false, CallPhase.ACTIVE, both, SPEAKER).target)
    }

    @Test fun stateABluetoothPickThatDisconnectsFallsBackButAnotherPickHolds() {
        val p = RoutePolicyState()
        assertNull(p.decide(false, CallPhase.ACTIVE, both + BLUETOOTH, BLUETOOTH).target)
        p.userPicked(BLUETOOTH)
        assertEquals(EARPIECE, p.decide(false, CallPhase.ACTIVE, both, SPEAKER).target)

        val q = RoutePolicyState()
        assertNull(q.decide(true, CallPhase.ACTIVE, both + BLUETOOTH, BLUETOOTH).target)
        q.userPicked(EARPIECE)
        assertNull(q.decide(true, CallPhase.ACTIVE, both + BLUETOOTH, EARPIECE).target)
        assertNull(q.decide(true, CallPhase.ACTIVE, both, EARPIECE).target)
    }

    @Test fun stateNeverAsksTheSameRefusedMoveForever() {
        val p = RoutePolicyState()
        repeat(RoutePolicyState.MAX_TRIES) { assertEquals(SPEAKER, p.decide(true, CallPhase.ACTIVE, both, EARPIECE).target) }
        val d = p.decide(true, CallPhase.ACTIVE, both, EARPIECE)
        assertNull(d.target)
        assertTrue(d.reason, d.reason.startsWith("gave up on SPEAKER"))
        // The routes change: asked again.
        assertEquals(SPEAKER, p.decide(true, CallPhase.ACTIVE, both + EndpointUi.Kind.OTHER, EARPIECE).target)
    }

    // ---- mergeRoutes (Telecom + AudioManager) ----

    private val tEar = EndpointUi("t1", "Earpiece", EARPIECE)
    private val tSpk = EndpointUi("t2", "Speaker", SPEAKER)
    private val tBt = EndpointUi("t3", "Pixel Buds", BLUETOOTH)

    @Test fun mergeEmptyTelecomUsesAudioManager() {
        val r = mergeRoutes(emptyList(), null, listOf(EARPIECE, SPEAKER), EARPIECE, viaAudio = false)
        assertEquals(listOf(EARPIECE, SPEAKER), r.available.map { it.kind })
        assertEquals("am:EARPIECE", r.current?.id)
        assertEquals("current=EARPIECE available=EARPIECE,SPEAKER via=audio", r.line())
    }

    @Test fun mergeAlwaysOffersTheSpeaker() {
        val r = mergeRoutes(listOf(tEar), tEar, emptyList(), null, viaAudio = false)
        assertEquals(listOf("t1", "am:SPEAKER"), r.available.map { it.id })
        assertEquals(tEar, r.current)
        assertEquals("current=EARPIECE available=EARPIECE,SPEAKER via=telecom", r.line())
        assertEquals(listOf(SPEAKER), mergeRoutes(emptyList(), null, emptyList(), null, false).available.map { it.kind })
    }

    @Test fun mergeKeepsTelecomEndpointsAndOrdersThePicker() {
        val r = mergeRoutes(listOf(tSpk, tEar, tBt), tBt, listOf(EARPIECE, SPEAKER, BLUETOOTH, WIRED), BLUETOOTH, viaAudio = false)
        assertEquals(listOf("t3", "am:WIRED", "t1", "t2"), r.available.map { it.id })
        assertEquals(tBt, r.current)
    }

    @Test fun mergeTakesAudioManagersCurrentAfterAnAudioManagerRoute() {
        val r = mergeRoutes(listOf(tEar), tEar, listOf(EARPIECE, SPEAKER), SPEAKER, viaAudio = true)
        assertEquals("am:SPEAKER", r.current?.id)
        assertEquals("audio", r.via)
        // No AudioManager answer: Telecom's.
        assertEquals(tEar, mergeRoutes(listOf(tEar), tEar, emptyList(), null, viaAudio = true).current)
    }

    // ---- the tap ----

    @Test fun theTapSwitchesEarpieceAndSpeakerOrOpensThePicker() {
        assertEquals(RouteTap.Switch(SPEAKER), routeTap(listOf(tEar, tSpk), EARPIECE))
        assertEquals(RouteTap.Switch(EARPIECE), routeTap(listOf(tEar, tSpk), SPEAKER))
        assertEquals(RouteTap.Switch(SPEAKER), routeTap(emptyList(), null))
        assertEquals(RouteTap.Switch(SPEAKER), routeTap(listOf(tEar), EARPIECE))
        assertEquals(RouteTap.Picker, routeTap(listOf(tEar, tSpk, tBt), BLUETOOTH))
        assertEquals(RouteTap.Picker, routeTap(listOf(tEar, tSpk, EndpointUi("w", "Wired", WIRED)), EARPIECE))
    }

    @Test fun kindNamesMatchTheDebugLine() {
        assertEquals(EARPIECE, telecomKind(CallEndpointCompat.TYPE_EARPIECE))
        assertEquals(CallEndpointCompat.TYPE_SPEAKER, telecomType(SPEAKER))
        assertEquals(endpointTypeName(CallEndpointCompat.TYPE_BLUETOOTH), routeKindName(BLUETOOTH))
        assertEquals(endpointTypeName(CallEndpointCompat.TYPE_WIRED_HEADSET), routeKindName(WIRED))
        assertEquals(WIRED, audioDeviceKind(android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(BLUETOOTH, audioDeviceKind(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertNull(audioDeviceKind(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
    }
}
