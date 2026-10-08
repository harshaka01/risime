package lk.codegen.risime.calls

import android.app.Application
import android.content.Context
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
import lk.codegen.risime.data.FakeCallMarkDao
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.realtime.ConnectionState
import lk.codegen.risime.realtime.PushResult
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Decision 054 at the platform layer: process-start cleanup, the busy rule, Hang up with nothing behind it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallManagerCleanupTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conn = MutableStateFlow(ConnectionState.Live)
    private val me = "aaaaaaaa-0000-4000-8000-000000000001"
    private val peer = "bbbbbbbb-0000-4000-8000-000000000002"
    private val conv = dmConversationId(me, peer)
    private val ends = CopyOnWriteArrayList<CallEnvelope.End>()
    private val sent = CopyOnWriteArrayList<CallEnvelope.Env>()
    private val marksDao = FakeCallMarkDao()

    private val port = object : CallAppPort {
        override val scope = this@CallManagerCleanupTest.scope
        override val api = ApiClient(OkHttpClient(), { "http://127.0.0.1:9" }, { null })
        override val connection = conn
        override val callMarkDao = marksDao
        override suspend fun me() = me
        override suspend fun deviceId() = "a1a1a1a1-0000-4000-8000-000000000001"
        override suspend fun sessionLocked() = false
        override fun serverNow() = System.currentTimeMillis()
        override suspend fun displayName(userId: String) = "Kamal"
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

    private fun prefs() = app.getSharedPreferences("risime_calls", Context.MODE_PRIVATE)
    private fun audio() = app.getSystemService(AudioManager::class.java)

    private suspend fun <T> until(what: String, ms: Long = 5_000, p: suspend () -> T?): T =
        withTimeoutOrNull(ms) { var v = p(); while (v == null) { delay(20); v = p() }; v } ?: throw AssertionError(what)

    private suspend fun offer(m: CallManager): String {
        val id = UUID.randomUUID().toString()
        val machine = until("machine") { m.machine }
        machine.onSignal(InboundCall(conv, peer, "b1b1b1b1-0000-4000-8000-000000000003", System.currentTimeMillis(), CallEnvelope.Offer(id, fakeSdp(true, fingerprintOf(9), "ufx"), CallStateMachine.iso(System.currentTimeMillis()))))
        return id
    }

    @Test fun aProcessStartEndsTheCallTheKilledProcessHadAndGivesTheAudioModeBack() = runBlocking {
        prefs().edit().putString("active_call", ActiveCallRecord("c0ffee00-0000-4000-8000-000000000001", conv, peer, true, CallPhase.RINGING_OUT.name, false).encode()).commit()
        audio().mode = AudioManager.MODE_IN_COMMUNICATION
        val m = CallManager(app, port) { FakeCallMedia() }
        val end = until("call_end of the dead call") { ends.firstOrNull() }
        assertEquals("c0ffee00-0000-4000-8000-000000000001", end.callId)
        assertEquals(CallEnvelope.R_CANCELLED, end.reason)
        assertNull(prefs().getString("active_call", null))
        assertEquals(AudioManager.MODE_NORMAL, audio().mode)
        assertTrue(marksDao.get("c0ffee00-0000-4000-8000-000000000001")!!.ended)
        assertTrue(!m.inCall())
    }

    @Test fun aKilledRingingCalleeOwesNothing() = runBlocking {
        prefs().edit().putString("active_call", ActiveCallRecord("c0ffee00-0000-4000-8000-000000000002", conv, peer, false, CallPhase.RINGING_IN.name, false).encode()).commit()
        CallManager(app, port) { FakeCallMedia() }
        until("record cleared") { prefs().getString("active_call", null)?.let { null } ?: Unit }
        delay(300)
        assertTrue(ends.isEmpty())
    }

    @Test fun aStaleCommunicationModeDoesNotMakeTheCalleeBusy() = runBlocking {
        val m = CallManager(app, port) { FakeCallMedia() }
        until("cleanup ran") { if (audio().mode == AudioManager.MODE_NORMAL) Unit else null }
        audio().mode = AudioManager.MODE_IN_COMMUNICATION // e.g. another app's leftover, a ghost Telecom call
        offer(m)
        until("rings") { m.state.value?.takeIf { it.phase == CallPhase.RINGING_IN } }
        assertTrue(sent.none { it is CallEnvelope.Busy })
        // The ringing call is persisted for the next process.
        assertTrue(prefs().getString("active_call", null)!!.contains("RINGING_IN"))
        m.hangUp()
        until("declined") { ends.firstOrNull { it.reason == CallEnvelope.R_DECLINED } }
        until("record cleared") { if (prefs().getString("active_call", null) == null) Unit else null }
    }

    @Test fun everyEndGivesTheAudioBack() = runBlocking {
        val m = CallManager(app, port) { FakeCallMedia() }
        until("machine") { m.machine }
        delay(200)
        offer(m)
        until("rings") { m.state.value?.takeIf { it.phase == CallPhase.RINGING_IN } }
        // What a stuck Telecom/WebRTC call left: the communication mode and the speakerphone.
        audio().mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audio().isSpeakerphoneOn = true
        m.hangUp()
        until("audio mode back to normal") { if (audio().mode == AudioManager.MODE_NORMAL) Unit else null }
        @Suppress("DEPRECATION")
        assertTrue(!audio().isSpeakerphoneOn)
        assertNull(m.state.value?.takeIf { it.phase != CallPhase.ENDED })
    }

    /** Review fix (no Telecom): every call gets a route handle, so the button has routes with or without core-telecom. */
    @Test fun aCallAlwaysHasRoutesAndTheEndClearsThem() = runBlocking {
        val m = CallManager(app, port) { FakeCallMedia() }
        until("machine") { m.machine }
        delay(200)
        offer(m)
        until("rings") { m.state.value?.takeIf { it.phase == CallPhase.RINGING_IN } }
        val r = until("routes published") { m.routes.value.takeIf { it.available.isNotEmpty() } }
        assertTrue(r.available.any { it.kind == EndpointUi.Kind.SPEAKER })
        m.hangUp()
        until("routes cleared") { if (m.routes.value.available.isEmpty()) Unit else null }
    }

    @Test fun swipingTheAppAwayEndsTheCallAndGivesTheAudioBack() = runBlocking {
        val m = CallManager(app, port) { FakeCallMedia() }
        until("machine") { m.machine }
        m.placeCall(conv)
        until("the offer went out", 8_000) { sent.firstOrNull { it is CallEnvelope.Offer } }
        audio().mode = AudioManager.MODE_IN_COMMUNICATION
        m.onTaskRemoved()
        until("no call") { if (m.state.value == null || m.state.value?.phase == CallPhase.ENDED) Unit else null }
        until("audio mode back to normal") { if (audio().mode == AudioManager.MODE_NORMAL) Unit else null }
        until("the caller's cancel") { ends.firstOrNull { it.reason == CallEnvelope.R_CANCELLED } }
        Unit
    }

    @Test fun aCellularCallIsBusy() = runBlocking {
        val m = CallManager(app, port) { FakeCallMedia() }
        until("machine") { m.machine }
        delay(200)
        audio().mode = AudioManager.MODE_IN_CALL
        offer(m)
        until("busy sent") { sent.firstOrNull { it is CallEnvelope.Busy } }
        assertNull(m.state.value)
    }

    @Test fun placingACallAfterAnEndedOneIsNeverInAnotherCall() = runBlocking {
        val m = CallManager(app, port) { FakeCallMedia() }
        until("machine") { m.machine }
        m.placeCall(conv)
        until("calling") { m.state.value?.takeIf { it.phase == CallPhase.RINGING_OUT || it.phase == CallPhase.CALLING } }
        m.hangUp()
        until("ended") { if (m.state.value == null || m.state.value?.phase == CallPhase.ENDED) Unit else null }
        m.placeCall(conv)
        val s = until("second call") { m.state.value?.takeIf { it.phase != CallPhase.ENDED } }
        assertTrue(s.notice != CallNotice.IN_ANOTHER_CALL)
        assertTrue("at most one Telecom call is held", m.telecomCallIds().size <= 1)
    }
}
