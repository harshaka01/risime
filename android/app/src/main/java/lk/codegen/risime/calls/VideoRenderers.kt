package lk.codegen.risime.calls

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer

/**
 * §19.5 the video slot of the GO UX call screen over the real media: the remote renderer full
 * screen, the local preview as a mirrored picture-in-picture, and the 3-s frozen-frame rule.
 */
@Composable
fun LiveVideoStage(media: WebRtcCallMedia, snap: CallSnapshot, name: String) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snap.callId) {
        while (true) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }
    val showPeer = VideoRules.showPeerVideo(snap.peerCamera, snap.peerCameraAtMs, media.lastRemoteFrameAt(), now)
    VideoCallStage(
        showPeer = showPeer,
        showLocal = snap.cameraOn,
        name = name,
        photoKey = snap.peerUserId,
        remote = { m -> Renderer(media, remote = true, visible = showPeer, mirror = false, modifier = m) },
        local = { m -> Renderer(media, remote = false, visible = true, mirror = media.frontCamera, modifier = m) },
    )
}

@Composable
private fun Renderer(media: WebRtcCallMedia, remote: Boolean, visible: Boolean, mirror: Boolean, modifier: Modifier) {
    val view = remember { arrayOfNulls<SurfaceViewRenderer>(1) }
    DisposableEffect(remote) {
        onDispose {
            if (remote) media.attachRemote(null) else media.attachLocal(null)
            view[0]?.release()
            view[0] = null
        }
    }
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
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
