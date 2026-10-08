package lk.codegen.risime.calls

import androidx.core.telecom.CallEndpointCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P0-3: when a video call moves to the speaker (§19.6, android A6). */
class VideoSpeakerRuleTest {
    private data class Ep(val type: Int)

    private val ear = Ep(CallEndpointCompat.TYPE_EARPIECE)
    private val spk = Ep(CallEndpointCompat.TYPE_SPEAKER)
    private val bt = Ep(CallEndpointCompat.TYPE_BLUETOOTH)
    private val wired = Ep(CallEndpointCompat.TYPE_WIRED_HEADSET)
    private val both = listOf(ear, spk)

    private fun target(video: Boolean = true, phase: CallPhase? = CallPhase.ACTIVE, available: List<Ep> = both, current: Ep? = ear, userPicked: Boolean = false) =
        videoSpeakerTarget(video, phase, available, current, userPicked) { it.type }

    @Test fun aVideoCallOnTheEarpieceMovesToTheSpeakerWhenConnectingOrActive() {
        assertEquals(spk, target(phase = CallPhase.CONNECTING))
        assertEquals(spk, target(phase = CallPhase.ACTIVE))
    }

    @Test fun answerWithoutVideoIsStillAVideoCallAndKeepsTheSpeaker() {
        // The camera is off, the call stays video: the rule doesn't look at the camera.
        assertEquals(spk, target(video = true))
        assertNull(target(current = spk))
    }

    @Test fun aVoiceCallStaysOnTheEarpiece() {
        assertNull(target(video = false))
    }

    @Test fun onlyWhileConnectingOrActive() {
        CallPhase.values().filter { it != CallPhase.CONNECTING && it != CallPhase.ACTIVE }.forEach { assertNull(it.name, target(phase = it)) }
        assertNull(target(phase = null))
    }

    @Test fun aHeadsetWins() {
        assertNull(target(available = listOf(ear, spk, bt)))
        assertNull(target(available = listOf(ear, spk, wired)))
        assertNull(target(available = listOf(spk, wired), current = wired))
    }

    @Test fun onlyFromTheEarpiece() {
        assertNull(target(current = spk))
        assertNull(target(current = null))
        assertNull(target(current = Ep(CallEndpointCompat.TYPE_UNKNOWN)))
    }

    @Test fun noSpeakerListedNothingToDo() {
        assertNull(target(available = listOf(ear)))
        assertNull(target(available = emptyList()))
    }

    @Test fun theUsersPickIsNeverOverridden() {
        assertNull(target(userPicked = true))
        assertNull(target(userPicked = true, phase = CallPhase.CONNECTING))
    }

    @Test fun theRouteDebugLine() {
        assertEquals("current=SPEAKER available=EARPIECE,SPEAKER", routeLine(CallEndpointCompat.TYPE_SPEAKER, listOf(CallEndpointCompat.TYPE_EARPIECE, CallEndpointCompat.TYPE_SPEAKER)))
        assertEquals("current=NONE available=", routeLine(null, emptyList()))
        assertEquals("current=BLUETOOTH available=WIRED,BLUETOOTH", routeLine(CallEndpointCompat.TYPE_BLUETOOTH, listOf(CallEndpointCompat.TYPE_WIRED_HEADSET, CallEndpointCompat.TYPE_BLUETOOTH)))
    }
}
