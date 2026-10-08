package lk.codegen.risime.calls

import android.app.Application
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import lk.codegen.risime.data.FakeCallMarkDao
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.realtime.ConnectionState
import lk.codegen.risime.realtime.PushResult
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Logcat at app start (Redroid, no call): NullPointerException in CallManager.updateProximity.
 * The constructor's init block launched the call-state collector on the app's Default scope, and
 * the first onState(null) reached updateProximity before the constructor had initialised
 * `callScreenVisible` (declared below the init block). An unconfined scope makes that ordering
 * deterministic: the collector runs inside the constructor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallManagerStartTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> errors += e })

    private val port = object : CallAppPort {
        override val scope = this@CallManagerStartTest.scope
        override val api = ApiClient(OkHttpClient(), { "http://127.0.0.1:9" }, { null })
        override val connection = MutableStateFlow(ConnectionState.Disconnected)
        override val callMarkDao = FakeCallMarkDao()
        override suspend fun me(): String? = null
        override suspend fun deviceId() = "a1a1a1a1-0000-4000-8000-000000000001"
        override suspend fun sessionLocked() = false
        override fun serverNow() = System.currentTimeMillis()
        override suspend fun displayName(userId: String) = "Kamal"
        override suspend fun sendSignal(conv: String, peer: String, env: CallEnvelope.Env, media: String): PushResult<*> = PushResult.Ok(Unit)
        override suspend fun queueCallEnd(conv: String, peer: String, env: CallEnvelope.End, rangUnanswered: Boolean) = Unit
        override fun foreground() = true
    }

    @After fun tearDown() = scope.cancel()

    private fun noProximitySensor() =
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, false)

    @Test fun appStartWithNoCallThrowsNothing() {
        val m = CallManager(app, port) { FakeCallMedia() }
        assertEquals(emptyList<Throwable>(), errors.toList())
        assertFalse(m.proximityHeld())
    }

    @Test fun noProximitySupportAtStartAndOnTheCallScreen() {
        noProximitySensor()
        val m = CallManager(app, port) { FakeCallMedia() }
        m.onCallScreenVisible(true)
        m.onCallScreenVisible(false)
        assertEquals(emptyList<Throwable>(), errors.toList())
        assertFalse(m.proximityHeld())
    }
}
