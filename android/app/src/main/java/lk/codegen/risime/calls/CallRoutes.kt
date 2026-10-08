package lk.codegen.risime.calls

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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

// ---------------------------------------------------------------- P0 audio routing

/** The `calls: route` name of a route kind (the same names as [endpointTypeName]). */
fun routeKindName(kind: EndpointUi.Kind?): String = when (kind) {
    null -> "NONE"
    EndpointUi.Kind.EARPIECE -> "EARPIECE"
    EndpointUi.Kind.SPEAKER -> "SPEAKER"
    EndpointUi.Kind.BLUETOOTH -> "BLUETOOTH"
    EndpointUi.Kind.WIRED -> "WIRED"
    EndpointUi.Kind.OTHER -> "UNKNOWN"
}

/** Telecom's endpoint type → the route kind. */
fun telecomKind(type: Int): EndpointUi.Kind = when (type) {
    CallEndpointCompat.TYPE_EARPIECE -> EndpointUi.Kind.EARPIECE
    CallEndpointCompat.TYPE_SPEAKER -> EndpointUi.Kind.SPEAKER
    CallEndpointCompat.TYPE_WIRED_HEADSET -> EndpointUi.Kind.WIRED
    CallEndpointCompat.TYPE_BLUETOOTH -> EndpointUi.Kind.BLUETOOTH
    else -> EndpointUi.Kind.OTHER
}

/** A Telecom endpoint for the UI and the policy. */
fun endpointUi(e: CallEndpointCompat): EndpointUi = EndpointUi(e.identifier.toString(), e.name.toString(), telecomKind(e.type))

/** Route kind → Telecom's endpoint type (proximity, the debug line). */
fun telecomType(kind: EndpointUi.Kind?): Int? = when (kind) {
    null -> null
    EndpointUi.Kind.EARPIECE -> CallEndpointCompat.TYPE_EARPIECE
    EndpointUi.Kind.SPEAKER -> CallEndpointCompat.TYPE_SPEAKER
    EndpointUi.Kind.WIRED -> CallEndpointCompat.TYPE_WIRED_HEADSET
    EndpointUi.Kind.BLUETOOTH -> CallEndpointCompat.TYPE_BLUETOOTH
    EndpointUi.Kind.OTHER -> CallEndpointCompat.TYPE_UNKNOWN
}

/** An AudioDeviceInfo type → the route kind (null: not a call route, e.g. A2DP media or HDMI). */
fun audioDeviceKind(type: Int): EndpointUi.Kind? = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> EndpointUi.Kind.EARPIECE
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> EndpointUi.Kind.SPEAKER
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> EndpointUi.Kind.WIRED
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> EndpointUi.Kind.BLUETOOTH
    else -> null
}

/** The picker's order: Bluetooth / Headset / Phone / Speaker. */
private val KIND_ORDER = listOf(EndpointUi.Kind.BLUETOOTH, EndpointUi.Kind.WIRED, EndpointUi.Kind.EARPIECE, EndpointUi.Kind.SPEAKER, EndpointUi.Kind.OTHER)

/** The button's and the picker's name of a route kind. */
fun routeKindLabel(kind: EndpointUi.Kind): String = when (kind) {
    EndpointUi.Kind.BLUETOOTH -> "Bluetooth"
    EndpointUi.Kind.WIRED -> "Headset"
    EndpointUi.Kind.EARPIECE -> "Phone"
    EndpointUi.Kind.SPEAKER -> "Speaker"
    EndpointUi.Kind.OTHER -> "Audio"
}

/** The routes the call screen shows: every available one and the real current one. */
data class CallRoutes(val available: List<EndpointUi> = emptyList(), val current: EndpointUi? = null, val via: String = "telecom") {
    /** `current=SPEAKER available=EARPIECE,SPEAKER via=telecom` (call-device-test parses current= and available=). */
    fun line(): String = "current=${routeKindName(current?.kind)} available=${available.joinToString(",") { routeKindName(it.kind) }} via=$via"
}

/**
 * P0 audio routing: Telecom's endpoints merged with AudioManager's devices. Telecom's lists can be
 * empty, late or short (one entry) on real phones, so the button never depends on them alone:
 * - available: Telecom's endpoints, plus every AudioManager kind Telecom didn't list (id `am:<KIND>`),
 *   plus the speaker (every phone has one), in the picker's order;
 * - current: AudioManager's when the last route change went through AudioManager ([viaAudio]) or
 *   Telecom reports none; otherwise Telecom's.
 */
fun mergeRoutes(telecom: List<EndpointUi>, telecomCurrent: EndpointUi?, audio: List<EndpointUi.Kind>, audioCurrent: EndpointUi.Kind?, viaAudio: Boolean): CallRoutes {
    val all = telecom.toMutableList()
    (audio + EndpointUi.Kind.SPEAKER).distinct().forEach { k ->
        if (k != EndpointUi.Kind.OTHER && all.none { it.kind == k }) all += EndpointUi("am:${k.name}", routeKindLabel(k), k)
    }
    val sorted = all.sortedBy { KIND_ORDER.indexOf(it.kind) }
    val useAudio = (viaAudio || telecomCurrent == null) && audioCurrent != null
    val current = if (useAudio) sorted.firstOrNull { it.kind == audioCurrent } else telecomCurrent?.let { t -> sorted.firstOrNull { it.id == t.id } ?: t }
    return CallRoutes(sorted, current, if (useAudio) "audio" else "telecom")
}

/** The in-call phases in which RisiMe moves the route (calling/ringing out, connecting, connected). */
fun routePhase(phase: CallPhase?): Boolean = when (phase) {
    CallPhase.CALLING, CallPhase.RINGING_OUT, CallPhase.ANSWERING, CallPhase.CONNECTING, CallPhase.ACTIVE, CallPhase.RECONNECTING -> true
    else -> false
}

/** A route decision: move to [target] (null = leave the route alone), and why (logged). */
data class RouteDecision(val target: EndpointUi.Kind?, val reason: String)

/**
 * P0 audio routing: the pure route policy (1:1 voice, 1:1 video and group calls alike).
 *
 * 1. Only in the in-call phases ([routePhase]); never while an incoming call rings.
 * 2. A Bluetooth device that just connected ([btConnected]) takes the route.
 * 3. The user's pick ([userPicked]) is never overridden ([RoutePolicyState] clears it only on a
 *    Bluetooth connect and on a voice→video switch, the events that come after it).
 * 4. Bluetooth just disconnected: a wired headset, else the speaker (video) or the earpiece (voice).
 * 5. The call just turned to video ([videoTurnedOn]): the speaker, unless a headset is there.
 * 6. Default: a Bluetooth device, else a wired headset, when available and no headset is current;
 *    else a video call on the earpiece moves to the speaker (an unknown route: wait for one); a voice
 *    call stays.
 */
fun routeTarget(
    video: Boolean,
    phase: CallPhase?,
    available: Set<EndpointUi.Kind>,
    current: EndpointUi.Kind?,
    userPicked: Boolean,
    btConnected: Boolean = false,
    btDisconnected: Boolean = false,
    videoTurnedOn: Boolean = false,
): RouteDecision {
    fun move(k: EndpointUi.Kind, why: String) = when {
        current == k -> RouteDecision(null, "already ${routeKindName(k)} ($why)")
        k !in available -> RouteDecision(null, "${routeKindName(k)} not available ($why)")
        else -> RouteDecision(k, why)
    }
    val bt = EndpointUi.Kind.BLUETOOTH in available
    val wired = EndpointUi.Kind.WIRED in available
    val headsetOn = current == EndpointUi.Kind.BLUETOOTH || current == EndpointUi.Kind.WIRED
    if (!routePhase(phase)) return RouteDecision(null, "not in a call phase (${phase?.name ?: "none"})")
    if (btConnected && bt) return move(EndpointUi.Kind.BLUETOOTH, "bluetooth connected")
    if (userPicked) return RouteDecision(null, "the user's pick")
    if (btDisconnected && !bt) {
        return when {
            wired -> move(EndpointUi.Kind.WIRED, "bluetooth disconnected, headset")
            video -> move(EndpointUi.Kind.SPEAKER, "bluetooth disconnected, video call")
            else -> move(EndpointUi.Kind.EARPIECE, "bluetooth disconnected, voice call")
        }
    }
    if (videoTurnedOn && !bt && !wired) return move(EndpointUi.Kind.SPEAKER, "switched to video")
    if (bt && !headsetOn) return move(EndpointUi.Kind.BLUETOOTH, "bluetooth available")
    if (wired && !headsetOn) return move(EndpointUi.Kind.WIRED, "headset available")
    if (headsetOn) return RouteDecision(null, "on a headset")
    if (video && current == EndpointUi.Kind.EARPIECE) return move(EndpointUi.Kind.SPEAKER, "video call")
    return RouteDecision(null, "${if (video) "video" else "voice"} call on ${routeKindName(current)}")
}

/**
 * P0 audio routing: one call's route memory around [routeTarget]: the user's pick and the events
 * (Bluetooth connected/disconnected, voice→video) seen since. An event stays pending until the route
 * matches it (or the user picks); the same move for the same routes is asked at most [MAX_TRIES]
 * times, so a route the platform refuses is never requested in a loop.
 *
 * Review fix (external picks): the route can also be moved outside RisiMe (the car display, a
 * headset button, the system output switcher). When the current route changes while no request of
 * RisiMe's is pending ([requested]) and the devices stayed the same, the new route is the user's
 * pick; the Bluetooth-priority and video-speaker rules never move it back. A Bluetooth device newly
 * connecting still takes the route.
 */
class RoutePolicyState(private val now: () -> Long = System::currentTimeMillis) {
    /** The kind the user picked last (null: no pick, or an event after it cleared it). */
    @Volatile var userPick: EndpointUi.Kind? = null
        private set
    private var lastAvailable: Set<EndpointUi.Kind>? = null
    private var lastCurrent: EndpointUi.Kind? = null
    private var settled = false
    private var request: EndpointUi.Kind? = null
    private var requestAt = 0L
    private var btConnected = false
    private var btDisconnected = false
    private var videoTurnedOn = false
    private var lastAsk: Any? = null
    private var tries = 0

    @Synchronized fun userPicked(kind: EndpointUi.Kind) {
        userPick = kind
        btConnected = false
        btDisconnected = false
        videoTurnedOn = false
    }

    /** RisiMe asks the platform for [kind] now (the policy's move or the user's pick in the app). */
    @Synchronized fun requested(kind: EndpointUi.Kind) {
        request = kind
        requestAt = now()
    }

    /**
     * RisiMe itself moved the route away (another call's AudioManager route was given back): the
     * next change is not someone else's pick.
     */
    @Synchronized fun routeReset() {
        lastCurrent = null
        settled = false
    }

    /** RisiMe's last request failed (refused, or the route didn't take): nothing is pending. */
    @Synchronized fun requestFailed() {
        request = null
    }

    /** The call's video flag changed: turning it ON (the voice→video switch) moves to the speaker. */
    @Synchronized fun videoChanged(video: Boolean) {
        if (!video) return
        videoTurnedOn = true
        btDisconnected = false
        userPick = null
    }

    @Synchronized fun decide(video: Boolean, phase: CallPhase?, available: Set<EndpointUi.Kind>, current: EndpointUi.Kind?): RouteDecision {
        val prev = lastAvailable
        val prevCurrent = lastCurrent
        val bt = EndpointUi.Kind.BLUETOOTH
        // A change to the route RisiMe asked for is RisiMe's own; a request older than PENDING_MS is over.
        val req = request
        val ours = req != null && current == req
        val pending = req != null && !ours && now() - requestAt <= PENDING_MS
        if (req != null && !pending) request = null
        val external = routePhase(phase) && !ours && !pending && settled && prev == available &&
            prevCurrent != null && current != null && current != prevCurrent && current != EndpointUi.Kind.OTHER
        if (external && current != null) userPicked(current)
        lastCurrent = current
        if (prev != null && bt in available && bt !in prev) {
            btConnected = true
            btDisconnected = false
            userPick = null
        }
        if (prev != null && bt !in available && bt in prev) {
            btDisconnected = true
            btConnected = false
        }
        // A picked device that went away (Bluetooth off, the headset unplugged) is no pick any more.
        userPick?.let { if (it !in available) userPick = null }
        lastAvailable = available
        val d0 = routeTarget(video, phase, available, current, userPick != null, btConnected, btDisconnected, videoTurnedOn)
        val d = if (external && d0.target == null) d0.copy(reason = "${d0.reason}: ${routeKindName(current)}, changed outside RisiMe") else d0
        settled = d.target == null
        if (d.target == null) {
            if (routePhase(phase)) {
                btConnected = false
                btDisconnected = false
                videoTurnedOn = false
            }
            return d
        }
        val ask = Triple(d, available, current)
        tries = if (lastAsk == ask) tries + 1 else 1
        lastAsk = ask
        if (tries > MAX_TRIES) {
            settled = true
            return RouteDecision(null, "gave up on ${routeKindName(d.target)} after $MAX_TRIES tries (${d.reason})")
        }
        return d
    }

    companion object {
        const val MAX_TRIES = 3

        /** How long a request of RisiMe's may take before a different route counts as someone else's. */
        const val PENDING_MS = 3_000L
    }
}

/** `requested=SPEAKER actual=EARPIECE`: the AudioManager route RisiMe asked for and the one it read back. */
fun audioRouteResult(requested: EndpointUi.Kind, actual: EndpointUi.Kind?): String =
    "requested=${routeKindName(requested)} actual=${routeKindName(actual)}"

/**
 * Review fix (SCO leak): gives back the communication route RisiMe set through AudioManager: the
 * communication device on API 31+, else the speakerphone and Bluetooth SCO (a started SCO link stays
 * open after the call unless it is stopped). [sdk] is a parameter for the tests.
 */
@Suppress("DEPRECATION")
fun clearAudioManagerRoute(am: AudioManager, sdk: Int = Build.VERSION.SDK_INT) {
    if (sdk >= 31) {
        runCatching { am.clearCommunicationDevice() }
        return
    }
    runCatching { if (am.isSpeakerphoneOn) am.isSpeakerphoneOn = false }
    runCatching {
        if (am.isBluetoothScoOn) {
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
        }
    }
}

/** What a tap on the route button does. */
sealed interface RouteTap {
    /** Only the earpiece and the speaker: switch straight to [to]. */
    data class Switch(val to: EndpointUi.Kind) : RouteTap

    /** A Bluetooth device or a wired headset is there: open the picker. */
    data object Picker : RouteTap
}

fun routeTap(available: List<EndpointUi>, current: EndpointUi.Kind?): RouteTap =
    if (available.any { it.kind == EndpointUi.Kind.BLUETOOTH || it.kind == EndpointUi.Kind.WIRED }) RouteTap.Picker
    else RouteTap.Switch(if (current == EndpointUi.Kind.SPEAKER) EndpointUi.Kind.EARPIECE else EndpointUi.Kind.SPEAKER)
