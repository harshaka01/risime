package lk.codegen.risime.calls

import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.delay
import livekit.org.webrtc.RendererCommon

/**
 * §19.5/§23.2 the peer's video over the real media, full screen: rendered only in video mode and
 * while the peer's `call_media` (or fresh frames, the 3-s rule) says so. A shared screen (§23.5,
 * android A7) is fitted, never cropped, with pinch-zoom and pan (double tap resets), local only.
 */
@Composable
fun LiveRemoteVideo(media: WebRtcCallMedia, snap: CallSnapshot, name: String, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snap.callId) {
        while (true) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }
    val showPeer = VideoRules.render(snap.video, snap.peerCamera, snap.peerCameraAtMs, media.lastRemoteFrameAt(), now)
    val screen = snap.peerSharing
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    if (!screen && (zoom != 1f || pan != Offset.Zero)) {
        zoom = 1f
        pan = Offset.Zero
    }
    VideoCallStage(
        showPeer = showPeer,
        name = name,
        photoKey = snap.peerUserId,
        modifier = modifier,
        remote = { m ->
            val gestures = if (screen) {
                m.pointerInput(Unit) {
                    detectTransformGestures { _, p, z, _ ->
                        zoom = (zoom * z).coerceIn(1f, 5f)
                        pan = if (zoom == 1f) Offset.Zero else pan + p
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        zoom = 1f
                        pan = Offset.Zero
                    })
                }
            } else m
            Renderer(media, remote = true, visible = showPeer, mirror = false, fit = screen, zoom = zoom, pan = pan, modifier = gestures)
        },
    )
}

/** My camera's preview (mirrored for the front camera). */
@Composable
fun LiveLocalVideo(media: WebRtcCallMedia, modifier: Modifier = Modifier) {
    Renderer(media, remote = false, visible = true, mirror = media.frontCamera, fit = false, zoom = 1f, pan = Offset.Zero, modifier = modifier)
}

@Composable
private fun Renderer(media: WebRtcCallMedia, remote: Boolean, visible: Boolean, mirror: Boolean, fit: Boolean, zoom: Float, pan: Offset, modifier: Modifier) {
    val view = remember { arrayOfNulls<TextureViewRenderer>(1) }
    DisposableEffect(remote) {
        onDispose {
            if (remote) media.attachRemote(null) else media.attachLocal(null)
            view[0]?.release()
            view[0] = null
        }
    }
    AndroidView(
        factory = { ctx ->
            TextureViewRenderer(ctx).apply {
                init(media.egl.eglBaseContext, null)
                setEnableHardwareScaler(true)
                view[0] = this
                if (remote) media.attachRemote(this) else media.attachLocal(this)
            }
        },
        update = {
            it.setMirror(mirror)
            it.setScalingType(if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            it.scaleX = zoom
            it.scaleY = zoom
            it.translationX = pan.x
            it.translationY = pan.y
            it.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        },
        modifier = modifier,
    )
}
