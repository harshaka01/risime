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

/**
 * The camera paths keep v1.18's SurfaceViewRenderer (proven on every call in the device gate); only
 * a shared screen uses a TextureViewRenderer, because pinch-zoom and pan need a view that scales
 * (a SurfaceView ignores view transforms). The two are swapped by key when the peer starts or stops sharing.
 */
@Composable
private fun Renderer(media: WebRtcCallMedia, remote: Boolean, visible: Boolean, mirror: Boolean, fit: Boolean, zoom: Float, pan: Offset, modifier: Modifier) {
    androidx.compose.runtime.key(fit) {
        if (fit) TextureRenderer(media, remote, visible, zoom, pan, modifier) else SurfaceRenderer(media, remote, visible, mirror, modifier)
    }
}

@Composable
private fun SurfaceRenderer(media: WebRtcCallMedia, remote: Boolean, visible: Boolean, mirror: Boolean, modifier: Modifier) {
    val view = remember { arrayOfNulls<livekit.org.webrtc.SurfaceViewRenderer>(1) }
    DisposableEffect(remote) {
        onDispose {
            if (remote) media.attachRemote(null) else media.attachLocal(null)
            view[0]?.release()
            view[0] = null
        }
    }
    AndroidView(
        factory = { ctx ->
            livekit.org.webrtc.SurfaceViewRenderer(ctx).apply {
                init(media.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (!remote) setZOrderMediaOverlay(true)
                view[0] = this
                if (remote) media.attachRemote(this) else media.attachLocal(this)
            }
        },
        update = {
            it.setMirror(mirror)
            it.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        },
        modifier = modifier,
    )
}

@Composable
private fun TextureRenderer(media: WebRtcCallMedia, remote: Boolean, visible: Boolean, zoom: Float, pan: Offset, modifier: Modifier) {
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
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                view[0] = this
                if (remote) media.attachRemote(this) else media.attachLocal(this)
            }
        },
        update = {
            it.scaleX = zoom
            it.scaleY = zoom
            it.translationX = pan.x
            it.translationY = pan.y
            it.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        },
        modifier = modifier,
    )
}
