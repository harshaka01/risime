package lk.codegen.risime.calls

import androidx.core.telecom.CallEndpointCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GO UX "dark screen after Back from a call": the proximity screen-off lock stayed held while the
 * call went on behind the chats, so the chat went black whenever the sensor read "near". It is
 * wanted only while the call screen is in front.
 */
class ProximityRuleTest {
    private val earpiece = CallEndpointCompat.TYPE_EARPIECE

    @Test fun onlyAnActiveEarpieceCallWithItsScreenInFront() {
        assertTrue(wantsProximity(CallPhase.ACTIVE, earpiece, screenVisible = true))
        assertTrue(wantsProximity(CallPhase.ACTIVE, null, screenVisible = true))
    }

    @Test fun neverBehindTheChats() {
        assertFalse(wantsProximity(CallPhase.ACTIVE, earpiece, screenVisible = false))
        assertFalse(wantsProximity(CallPhase.ACTIVE, null, screenVisible = false))
    }

    @Test fun neverOnSpeakerBluetoothOrBeforeAndAfterTheCall() {
        assertFalse(wantsProximity(CallPhase.ACTIVE, CallEndpointCompat.TYPE_SPEAKER, true))
        assertFalse(wantsProximity(CallPhase.ACTIVE, CallEndpointCompat.TYPE_BLUETOOTH, true))
        assertFalse(wantsProximity(CallPhase.ACTIVE, CallEndpointCompat.TYPE_WIRED_HEADSET, true))
        assertFalse(wantsProximity(CallPhase.RINGING_IN, earpiece, true))
        assertFalse(wantsProximity(CallPhase.ENDED, earpiece, true))
        assertFalse(wantsProximity(null, earpiece, true))
    }
}
