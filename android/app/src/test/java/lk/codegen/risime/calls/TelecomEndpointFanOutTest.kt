package lk.codegen.risime.calls

import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0-3: core-telecom 1.0.1's endpoint flows are `Channel.receiveAsFlow()` (each value reaches ONE
 * collector). Through [EndpointTracker] (the only reader) on a multi-threaded dispatcher, a video
 * call always ends up with both endpoints listed and the speaker current. The old code (a UI reader
 * plus a separate speaker coroutine on the same flows) lost values here within a few repetitions.
 */
class TelecomEndpointFanOutTest {
    private data class Ep(val type: Int)

    private val earpiece = Ep(CallEndpointCompat.TYPE_EARPIECE)
    private val speaker = Ep(CallEndpointCompat.TYPE_SPEAKER)

    /** Telecom's side: unlimited channels read as flows, as core-telecom 1.0.1 does. */
    private class FakeTelecom {
        val availableCh = Channel<List<Ep>>(Channel.UNLIMITED)
        val currentCh = Channel<Ep>(Channel.UNLIMITED)
        val available = availableCh.receiveAsFlow()
        val current = currentCh.receiveAsFlow()
        fun requestEndpointChange(e: Ep) { currentCh.trySend(e) }
    }

    @Test fun oneReaderPerFlowNeverLosesTheEndpointsOrTheSpeaker() = runBlocking {
        repeat(200) { rep ->
            val telecom = FakeTelecom()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                // The call is added: Telecom reports the routes, the call starts on the earpiece.
                scope.launch { telecom.availableCh.send(listOf(earpiece, speaker)) }
                scope.launch { telecom.currentCh.send(earpiece) }
                val tracker = EndpointTracker(telecom.available, telecom.current)
                tracker.start(scope) {
                    videoSpeakerTarget(true, CallPhase.ACTIVE, tracker.endpoints.value, tracker.current.value, userPicked = false) { it.type }
                        ?.let(telecom::requestEndpointChange)
                }
                val ok = withTimeoutOrNull(2_000) {
                    tracker.endpoints.first { it.size == 2 }
                    tracker.current.first { it == speaker }
                }
                assertEquals("repetition $rep: endpoints=${tracker.endpoints.value} current=${tracker.current.value}", speaker, ok)
                assertEquals("repetition $rep", 2, tracker.endpoints.value.size)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun theUsersPickIsNeverOverridden() = runBlocking {
        repeat(50) { rep ->
            val telecom = FakeTelecom()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val userPicked = MutableStateFlow(false)
            try {
                val tracker = EndpointTracker(telecom.available, telecom.current)
                tracker.start(scope) {
                    videoSpeakerTarget(true, CallPhase.ACTIVE, tracker.endpoints.value, tracker.current.value, userPicked.value) { it.type }
                        ?.let(telecom::requestEndpointChange)
                }
                telecom.availableCh.send(listOf(earpiece, speaker))
                telecom.currentCh.send(earpiece)
                withTimeoutOrNull(2_000) { tracker.current.first { it == speaker } } ?: error("repetition $rep: never on the speaker")
                // The user taps back to the earpiece (selectEndpoint sets userPicked first).
                userPicked.value = true
                telecom.requestEndpointChange(earpiece)
                withTimeoutOrNull(2_000) { tracker.current.first { it == earpiece } } ?: error("repetition $rep: never back on the earpiece")
                // More endpoint updates (a route list refresh) never move it back.
                telecom.availableCh.send(listOf(earpiece, speaker))
                kotlinx.coroutines.delay(20)
                assertEquals("repetition $rep", earpiece, tracker.current.value)
            } finally {
                scope.cancel()
            }
        }
    }
}
