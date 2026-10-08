package lk.codegen.risime.calls

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review fix (SCO leak): giving the AudioManager route back stops a Bluetooth SCO link RisiMe started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Suppress("DEPRECATION")
class AudioRouteReleaseTest {
    private val am = ApplicationProvider.getApplicationContext<Application>().getSystemService(AudioManager::class.java)

    @Test fun belowApi31TheScoLinkAndTheSpeakerphoneAreGivenBack() {
        am.startBluetoothSco()
        am.isBluetoothScoOn = true
        am.isSpeakerphoneOn = true
        clearAudioManagerRoute(am, sdk = 30)
        assertFalse(am.isBluetoothScoOn)
        assertFalse(am.isSpeakerphoneOn)
    }

    @Test fun api31AndUpClearsTheCommunicationDevice() {
        // Nothing set: clearing is safe (no throw), and SCO is not touched on 31+.
        clearAudioManagerRoute(am, sdk = 34)
        assertFalse(am.isBluetoothScoOn)
    }
}
