package ai.risicloud.risime.calls

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ai.risicloud.risime.data.FakeCallMarkDao
import ai.risicloud.risime.net.ApiClient
import ai.risicloud.risime.net.dmConversationId
import ai.risicloud.risime.realtime.ConnectionState
import ai.risicloud.risime.realtime.PushResult
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** P0 calls (Harsha 2026-10-16): the one endCall(reason) path at the platform layer, and the test ring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallManagerEndCallTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conn = MutableStateFlow(ConnectionState.Live)
    private val me = "aaaaaaaa-0000-4000-8000-000000000001"
    private val peer = "bbbbbbbb-0000-4000-8000-000000000002"
    private val conv = dmConversationId(me, peer)
    private val ends = CopyOnWriteArrayList<CallEnvelope.End>()
    private val sent = CopyOnWriteArrayList<CallEnvelope.Env>()

    private val port = object : CallAppPort {
        override val scope = this@CallManagerEndCallTest.scope
        override val api = ApiClient(OkHttpClient(), { "http://127.0.0.1:9" }, { null })
        override val connection = conn
        override val callMarkDao = FakeCallMarkDao()
        override suspend fun me() = me
        override suspend fun deviceId() = "a1a1a1a1-0000-4000-8000-000000000001"
        override suspend fun sessionLocked() = false
        override fun serverNow() = System.currentTimeMillis()
        override suspend fun displayName(userId: String) = "Shenika"
        override suspend fun sendSignal(conv: String, peer: String, env: CallEnvelope.Env, media: String): PushResult<*> {
            sent += env
            return PushResult.Ok(Unit)
        }
        override suspend fun queueCallEnd(conv: String, peer: String, env: CallEnvelope.End, rangUnanswered: Boolean) {
            ends += env
        }
        override fun foreground() = true
    }

    @After fun tearDown() = scope.cancel()

    private fun audio() = app.getSystemService(AudioManager::class.java)

    private suspend fun <T> until(what: String, ms: Long = 5_000, p: suspend () -> T?): T =
        withTimeoutOrNull(ms) { var v = p(); while (v == null) { delay(20); v = p() }; v } ?: throw AssertionError(what)

    private fun ended(m: CallManager) = if (m.state.value == null || m.state.value?.phase == CallPhase.ENDED) Unit else null

    @Test fun aCellularCallEndsTheRisiMeCallAndTheNextCallIsNeverInAnotherCall() = runBlocking {
        val m = CallManager(app, port) { FakeCallMedia() }
        until("machine") { m.machine }
        m.placeCall(conv)
        until("the offer went out", 8_000) { sent.firstOrNull { it is CallEnvelope.Offer } }
        audio().mode = AudioManager.MODE_IN_COMMUNICATION
        m.endCall(CallEndReason.CELLULAR).join()
        until("ended") { ended(m) }
        val end = until("the peer is told") { ends.firstOrNull() }
        assertEquals(CallEnvelope.R_CANCELLED, end.reason)
        until("audio mode back to normal") { if (audio().mode == AudioManager.MODE_NORMAL) Unit else null }
        until("no Telecom call held") { if (m.telecomCallIds().isEmpty()) Unit else null }
        assertTrue(!m.inCall())
        // Call again without restarting the app.
        m.placeCall(conv)
        val s = until("second call") { m.state.value?.takeIf { it.phase != CallPhase.ENDED } }
        assertTrue(s.notice != CallNotice.IN_ANOTHER_CALL)
        m.hangUp()
        until("ended again") { ended(m) }
    }

    @Test fun everyPlatformEndReasonFreesTheCall() = runBlocking {
        val m = CallManager(app, port) { FakeCallMedia() }
        until("machine") { m.machine }
        for (reason in listOf(CallEndReason.HANGUP, CallEndReason.SYSTEM, CallEndReason.CELLULAR, CallEndReason.BACKGROUND)) {
            val id = UUID.randomUUID().toString()
            m.machine!!.onSignal(InboundCall(conv, peer, "b1b1b1b1-0000-4000-8000-000000000003", System.currentTimeMillis(), CallEnvelope.Offer(id, fakeSdp(true, fingerprintOf(9), "ufx"), CallStateMachine.iso(System.currentTimeMillis()))))
            until("rings ($reason)") { m.state.value?.takeIf { it.phase == CallPhase.RINGING_IN && it.callId == id } }
            if (reason == CallEndReason.BACKGROUND) m.onTaskRemoved() else m.endCall(reason, id).join()
            until("ended ($reason)") { ended(m) }
            until("no Telecom call ($reason)") { if (m.telecomCallIds().isEmpty()) Unit else null }
            assertTrue("$reason", !m.inCall())
            delay(100)
        }
    }

    @Test fun theTestRingUsesTheRealCallChannelWithFullScreenAndRingtone() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(TestRing.ring(app))
        val nm = app.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).activeNotifications.single { it.id == TestRing.NOTIFICATION_ID }.notification
        assertEquals(CallNotifications.CH_CALLS, n.channelId)
        assertTrue("insistent", n.flags and Notification.FLAG_INSISTENT != 0)
        assertNotNull("full-screen intent", n.fullScreenIntent)
        assertEquals(Notification.CATEGORY_CALL, n.category)
        val ch = nm.getNotificationChannel(CallNotifications.CH_CALLS)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
        assertNotNull("ringtone", ch.sound)
        assertEquals(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE, ch.audioAttributes.usage)
        assertTrue(ch.shouldVibrate())
        TestRing.stop(app)
        assertTrue(shadowOf(nm).activeNotifications.none { it.id == TestRing.NOTIFICATION_ID })
    }
}
