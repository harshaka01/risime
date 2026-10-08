package lk.codegen.risime.calls

import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * P0-3: the ONE reader of a Telecom call's endpoint flows.
 *
 * core-telecom 1.0.1's `availableEndpoints` and `currentCallEndpoint` are `Channel.receiveAsFlow()`:
 * every value goes to exactly one collector. Two readers (the UI copy and the old video-speaker
 * coroutine) raced for the values on a multi-threaded dispatcher: the UI's endpoint list could stay
 * empty (the route button greyed out for the whole call), the UI could miss the switch to SPEAKER,
 * and the speaker logic could miss the earpiece value and never switch. So nothing else may collect
 * those flows: everyone reads [endpoints] and [current] (StateFlows, any number of readers).
 */
class EndpointTracker<T>(
    private val availableSource: Flow<List<T>>,
    private val currentSource: Flow<T?>,
    val endpoints: MutableStateFlow<List<T>> = MutableStateFlow(emptyList()),
    val current: MutableStateFlow<T?> = MutableStateFlow(null),
) {
    /** Starts the two readers in [scope] (the Telecom call's own scope); [onChange] runs after every new value. */
    fun start(scope: CoroutineScope, onChange: () -> Unit = {}) {
        scope.launch { availableSource.collect { endpoints.value = it; onChange() } }
        scope.launch { currentSource.collect { current.value = it; onChange() } }
    }
}

/**
 * P0-3 (§19.6, android A6): the route a video call should move to now, or null to leave it alone.
 * The speaker only when the call is video, it is connecting or active, no wired or Bluetooth headset
 * is available, the current route is the earpiece, and the user hasn't picked a route themselves
 * (their choice is never overridden). "Answer without video" is still a video call: the speaker stays.
 */
fun <T> videoSpeakerTarget(video: Boolean, phase: CallPhase?, available: List<T>, current: T?, userPicked: Boolean, type: (T) -> Int): T? {
    if (!video || userPicked) return null
    if (phase != CallPhase.CONNECTING && phase != CallPhase.ACTIVE) return null
    if (available.any { type(it) == CallEndpointCompat.TYPE_BLUETOOTH || type(it) == CallEndpointCompat.TYPE_WIRED_HEADSET }) return null
    if (current == null || type(current) != CallEndpointCompat.TYPE_EARPIECE) return null
    return available.firstOrNull { type(it) == CallEndpointCompat.TYPE_SPEAKER }
}

/** The name in the `calls: route current=<TYPE> available=<TYPE,…>` debug line. */
fun endpointTypeName(type: Int?): String = when (type) {
    null -> "NONE"
    CallEndpointCompat.TYPE_EARPIECE -> "EARPIECE"
    CallEndpointCompat.TYPE_SPEAKER -> "SPEAKER"
    CallEndpointCompat.TYPE_BLUETOOTH -> "BLUETOOTH"
    CallEndpointCompat.TYPE_WIRED_HEADSET -> "WIRED"
    CallEndpointCompat.TYPE_STREAMING -> "STREAMING"
    else -> "UNKNOWN"
}

/** `current=SPEAKER available=EARPIECE,SPEAKER` */
fun routeLine(current: Int?, available: List<Int>): String =
    "current=${endpointTypeName(current)} available=${available.joinToString(",") { endpointTypeName(it) }}"
