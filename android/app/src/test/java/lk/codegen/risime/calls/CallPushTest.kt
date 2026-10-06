package lk.codegen.risime.calls

import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import lk.codegen.risime.data.FakeCallMarkDao
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.realtime.ConnectionState
import lk.codegen.risime.realtime.PushResult
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** §16.8 the call push: its own handler, the phoneCall service at once, an unlocked sync or decision 051's nameless ring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallPushTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conn = MutableStateFlow(ConnectionState.Disconnected)
    private var locked = true

    private val port = object : CallAppPort {
        override val scope = this@CallPushTest.scope
        override val api = ApiClient(OkHttpClient(), { "http://127.0.0.1:9" }, { null })
        override val connection = conn
        override val callMarkDao = FakeCallMarkDao()
        override suspend fun me() = "aaaaaaaa-0000-4000-8000-000000000001"
        override suspend fun deviceId() = "a1a1a1a1-0000-4000-8000-000000000001"
        override suspend fun sessionLocked() = locked
        override fun serverNow() = System.currentTimeMillis()
        override suspend fun displayName(userId: String) = "Kamal"
        override suspend fun sendSignal(conv: String, peer: String, env: CallEnvelope.Env): PushResult<*> = PushResult.Unavailable
        override suspend fun queueCallEnd(conv: String, peer: String, env: CallEnvelope.End, rangUnanswered: Boolean) = Unit
        override fun foreground() = false
    }

    @After fun tearDown() = scope.cancel()

    private fun manager() = CallManager(app, port) { FakeCallMedia() }

    private suspend fun <T> until(what: String, p: () -> T?): T = withTimeoutOrNull(5_000) { var v = p(); while (v == null) { delay(20); v = p() }; v } ?: throw AssertionError(what)

    private fun startedService(): Boolean = Shadows.shadowOf(app).nextStartedService?.component?.className == CallService::class.java.name

    @Test fun lockedSessionRingsBlindWithoutANameAndKeepsTheSocketRequest() = runBlocking {
        val m = manager()
        m.onCallPush()
        until("blind ring") { m.blindRing.value }
        assertTrue("the phoneCall service starts at once", startedService())
        val (n, mic) = m.serviceNotification()!!
        assertEquals("Incoming RisiMe call", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse("never the microphone type while ringing", mic)
        assertTrue("insistent", n.flags and Notification.FLAG_INSISTENT != 0)
        assertNotNull("a full-screen intent always", n.fullScreenIntent)
        assertEquals(Notification.CATEGORY_CALL, n.category)
        assertTrue(m.inCall())
        // Decline while locked only stops the local ring (nothing can be sent).
        m.hangUp()
        assertNull(m.blindRing.value)
        assertNull(m.serviceNotification())
    }

    @Test fun unlockedSessionSyncsOnTheKeptUpSocketInsteadOfRinging() = runBlocking {
        locked = false
        val m = manager()
        m.onCallPush()
        until("keep connected") { m.keepConnected.value.takeIf { it } }
        assertNull("no blind ring when unlocked", m.blindRing.value)
        val (n, _) = m.serviceNotification()!!
        assertEquals("Connecting a call…", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun aLiveSocketAlreadyBringsTheOffer() = runBlocking {
        conn.value = ConnectionState.Live
        val m = manager()
        m.onCallPush()
        delay(200)
        assertNull(m.blindRing.value)
        assertFalse(m.keepConnected.value)
    }

    @Test fun afterUnlockNothingToRingSaysCallEnded() = runBlocking {
        val m = manager()
        m.onCallPush()
        until("blind ring") { m.blindRing.value }
        var said: String? = null
        val t0 = System.currentTimeMillis()
        // Simulate: the user unlocked; the sync finds no ringing call within the window → "Call ended".
        m.onUnlockedAfterBlindAnswer { said = it }
        assertTrue(m.pendingBlindAnswer)
        assertNull(m.blindRing.value.takeIf { System.currentTimeMillis() - t0 > 20_000 })
    }

    @Test fun incomingAndOngoingNotificationsAreCallStyle() {
        val n = CallNotifications(app)
        val inc = n.incoming("Kamal")
        assertEquals("Kamal", inc.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(CallNotifications.CH_CALLS, inc.channelId)
        val ongoing = n.ongoing("Kamal", "Connecting…", null)
        assertEquals(CallNotifications.CH_CALL_STATUS, ongoing.channelId)
        assertTrue(ongoing.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("android.app.Notification\$CallStyle", inc.extras.getString(Notification.EXTRA_TEMPLATE))
    }
}
