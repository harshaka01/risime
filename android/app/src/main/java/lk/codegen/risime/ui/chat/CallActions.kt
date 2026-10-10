package lk.codegen.risime.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.net.ApiResult

/** §16.1: the DM header's call button state, refetched on chat open and on `mls_membership`. */
class CallActions(private val c: AppContainer, private val scope: CoroutineScope, private val conversationId: String) {
    private val _ready = MutableStateFlow(false)
    val callsReady: StateFlow<Boolean> = _ready
    private val _video = MutableStateFlow(false)

    /** §19.1 `video_ready` (each user has a recent `video` device). */
    val videoReady: StateFlow<Boolean> = _video
    private val _e2ee = MutableStateFlow(false)

    /** §10 the DM is e2ee (the server's view; for a header that has no E2EE state of its own, v1.33 Official 1:1). */
    val e2ee: StateFlow<Boolean> = _e2ee

    fun refresh() {
        scope.launch {
            val r = c.api.mlsGroup(conversationId)
            if (r is ApiResult.Ok) {
                _e2ee.value = r.value.e2ee
                _ready.value = r.value.e2ee && r.value.callsReady
                _video.value = r.value.e2ee && r.value.videoReady
            }
        }
    }

    /** Null = the video button works; otherwise what a tap explains (§19.1 UI). */
    fun videoBlockedText(encrypted: Boolean, ready: Boolean, videoReady: Boolean, peerName: String): String? =
        blockedText(encrypted, ready, peerName) ?: videoBlockedText(
            thisPhone = runCatching { c.calls.canAdvertiseVideo() }.getOrDefault(false), videoReady = videoReady, peerName = peerName,
        )

    /** §19.6: a video call; [camera] = CAMERA granted (denied: the call goes on with the camera off, android A2). */
    fun startVideo(camera: Boolean) {
        c.calls.placeCall(conversationId, video = true, camera = camera)
        c.calls.openCallScreen()
    }

    fun hasCamera(): Boolean = c.calls.hasCameraPermission()

    /** Null = the button works; otherwise what a tap explains (§16.1 UI). */
    fun blockedText(encrypted: Boolean, ready: Boolean, peerName: String): String? = callBlockedText(
        thisPhone = runCatching { c.calls.unsupportedReason() }.getOrElse { "Calls aren't supported on this phone" },
        encrypted = encrypted, ready = ready, peerName = peerName,
    )

    fun start() {
        c.calls.placeCall(conversationId)
        c.calls.openCallScreen()
    }
}

/**
 * The rule order of §16.1: this phone first, then e2ee, then the peer's readiness. This phone's
 * reason is shown as it is ("Turn on notifications so RisiMe calls can ring"): "Update" only when
 * this build really has calls off. The peer's version isn't on the wire, so a peer without `calls`
 * is never told to update (nightly.16: a current phone that hadn't allowed notifications yet).
 */
fun callBlockedText(thisPhone: String?, encrypted: Boolean, ready: Boolean, peerName: String): String? = when {
    thisPhone == CALLS_OFF_IN_BUILD -> "Update RisiMe on this phone to make calls"
    thisPhone != null -> thisPhone
    !encrypted -> "Calls need an end-to-end encrypted chat."
    !ready -> peerCantTakeCallsText(peerName)
    else -> null
}

/** §19.1: after the voice rules, this phone's VP8, then the peer's `video_ready`. */
fun videoBlockedText(thisPhone: Boolean, videoReady: Boolean, peerName: String): String? = when {
    !thisPhone -> "Video calls aren't supported on this phone"
    !videoReady -> lk.codegen.risime.calls.CallTexts.videoNotReadyText(peerName)
    else -> null
}

/** CallManager.unsupportedReason() for a build without calls (§16.14 receive-only). */
const val CALLS_OFF_IN_BUILD = "calls are off in this build"

/** §16.1 peer without a `calls` device (the button and the server's `calls_not_ready`). */
fun peerCantTakeCallsText(peerName: String) = "$peerName's phone can't take calls yet"

/** The call button: asks RECORD_AUDIO on the first call, explains when calls can't work yet. */
@Composable
fun CallHeaderButton(blocked: String?, onBlocked: (String) -> Unit, onCall: () -> Unit) {
    val ctx = LocalContext.current
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onCall() else onBlocked("RisiMe needs the microphone for calls")
    }
    IconButton(onClick = {
        when {
            blocked != null -> onBlocked(blocked)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> onCall()
            else -> mic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }) {
        Icon(Icons.Default.Call, if (blocked == null) "Voice call" else "Voice call (unavailable)", tint = if (blocked == null) androidx.compose.material3.LocalContentColor.current else Color.Gray)
    }
}

/**
 * §19.1/§19.6 the DM header's video button next to the call button: disabled until `video_ready`
 * (a tap explains); asks RECORD_AUDIO and CAMERA; a denied camera still calls, camera off (android A2).
 */
@Composable
fun VideoHeaderButton(blocked: String?, onBlocked: (String) -> Unit, onVideoCall: (camera: Boolean) -> Unit) {
    val ctx = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val mic = r[Manifest.permission.RECORD_AUDIO] ?: granted(Manifest.permission.RECORD_AUDIO)
        if (!mic) onBlocked("RisiMe needs the microphone for calls") else onVideoCall(r[Manifest.permission.CAMERA] ?: granted(Manifest.permission.CAMERA))
    }
    IconButton(onClick = {
        when {
            blocked != null -> onBlocked(blocked)
            granted(Manifest.permission.RECORD_AUDIO) && granted(Manifest.permission.CAMERA) -> onVideoCall(true)
            else -> ask.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
        }
    }) {
        Icon(lk.codegen.risime.ui.common.RisiIcons.Videocam, if (blocked == null) "Video call" else "Video call (unavailable)", tint = if (blocked == null) androidx.compose.material3.LocalContentColor.current else Color.Gray)
    }
}

/**
 * v1.33 §24.5 (NEXT-PHASE D1) a 1:1's Official tab: its calls are the §16/§19 peer-to-peer calls on the
 * chat's `dm:` — the same [CallActions] as the Private tab (readiness refetched on open, on
 * `mls_membership` and on reconnect), never the Official `grp:`.
 */
class DmCallsViewModel(private val c: AppContainer, val dm: String) : ViewModel() {
    val calls = CallActions(c, viewModelScope, dm)

    init {
        calls.refresh()
        viewModelScope.launch { c.mlsMembershipSeen.collect { e -> if (e.conversationId == dm) calls.refresh() } }
        viewModelScope.launch {
            c.realtime.state.collect { if (it == lk.codegen.risime.realtime.ConnectionState.Live) calls.refresh() }
        }
    }
}

/** The 1:1 header's video and voice buttons for [calls] (the Private chat's rules and texts, the peer's name). */
@Composable
fun DmCallButtons(calls: CallActions, peerName: String, onBlocked: (String) -> Unit) {
    val e2ee by calls.e2ee.collectAsStateWithLifecycle()
    val ready by calls.callsReady.collectAsStateWithLifecycle()
    val video by calls.videoReady.collectAsStateWithLifecycle()
    val tap: (String) -> Unit = { t -> onBlocked(t); calls.refresh() }
    VideoHeaderButton(blocked = calls.videoBlockedText(e2ee, ready, video, peerName), onBlocked = tap, onVideoCall = calls::startVideo)
    CallHeaderButton(blocked = calls.blockedText(e2ee, ready, peerName), onBlocked = tap, onCall = calls::start)
}
