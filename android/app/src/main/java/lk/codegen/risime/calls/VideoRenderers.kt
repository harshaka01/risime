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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.delay
import livekit.org.webrtc.RendererCommon

/**
 * §23.5 the viewer sees exactly what the sender sees: a shared screen is fitted into the stage
 * (letterboxed, never cropped), at the sender's orientation and aspect ratio (the frame's width and
 * height after its rotation; a rotation on either phone re-fits). Pure, unit-tested.
 */
object ShareFit {
    /** The frame as the sender sees it: width × height after the frame's [rotation] (0/90/180/270). */
    fun oriented(width: Int, height: Int, rotation: Int): Pair<Int, Int> =
        if (((rotation % 360) + 360) % 180 == 90) height to width else width to height

    /**
     * The displayed size of a [contentW] × [contentH] frame fitted into [boxW] × [boxH]: the largest
     * size with the content's aspect ratio that fits inside the box (one side fills, the other is
     * letterboxed). No frame yet: the whole box.
     */
    fun fit(boxW: Int, boxH: Int, contentW: Int, contentH: Int): Pair<Int, Int> {
        if (boxW <= 0 || boxH <= 0 || contentW <= 0 || contentH <= 0) return boxW.coerceAtLeast(0) to boxH.coerceAtLeast(0)
        val s = minOf(boxW.toDouble() / contentW, boxH.toDouble() / contentH)
        val w = Math.round(contentW * s).toInt().coerceIn(1, boxW)
        val h = Math.round(contentH * s).toInt().coerceIn(1, boxH)
        return w to h
    }

    /**
     * Pinch-zoom's pan limit on one axis: the zoomed content ([fitted] × [zoom]) may move only as far
     * as it overflows the box; at zoom 1 it stays centred.
     */
    fun maxPan(fitted: Int, box: Int, zoom: Float): Float = ((fitted * zoom - box) / 2f).coerceAtLeast(0f)

    fun clampPan(pan: Offset, fittedW: Int, fittedH: Int, boxW: Int, boxH: Int, zoom: Float): Offset {
        val mx = maxPan(fittedW, boxW, zoom)
        val my = maxPan(fittedH, boxH, zoom)
        // + 0f: no -0.0 (an axis that cannot move is exactly 0).
        return Offset(pan.x.coerceIn(-mx, mx) + 0f, pan.y.coerceIn(-my, my) + 0f)
    }

    const val MAX_ZOOM = 5f
}

/**
 * §19.5/§23.2 the peer's video over the real media, full screen: rendered only in video mode and
 * while the peer's `call_media` (or fresh frames, the 3-s rule) says so. A camera fills the stage
 * (cropped, as before); a shared screen (§23.5, android A7) is fitted, never cropped ([ShareFit]),
 * with pinch-zoom and pan (double tap resets), local only.
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
    VideoCallStage(
        showPeer = showPeer,
        name = name,
        photoKey = snap.peerUserId,
        modifier = modifier,
        remote = { m -> Renderer(media, remote = true, visible = showPeer, mirror = false, fit = screen, modifier = m) },
    )
}

/** My camera's preview (mirrored for the front camera). */
@Composable
fun LiveLocalVideo(media: WebRtcCallMedia, modifier: Modifier = Modifier) {
    Renderer(media, remote = false, visible = true, mirror = media.frontCamera, fit = false, modifier = modifier)
}

/**
 * The camera paths keep v1.18's SurfaceViewRenderer (proven on every call in the device gate); only
 * a shared screen uses a TextureViewRenderer, because pinch-zoom and pan need a view that scales
 * (a SurfaceView ignores view transforms). The two are swapped by key when the peer starts or stops sharing:
 * the new view's factory attaches it before the old one's onDispose runs, so the detach is owner-checked.
 */
@Composable
private fun Renderer(media: WebRtcCallMedia, remote: Boolean, visible: Boolean, mirror: Boolean, fit: Boolean, modifier: Modifier) {
    androidx.compose.runtime.key(fit) {
        if (fit) ScreenRenderer(media, remote, visible, modifier) else SurfaceRenderer(media, remote, visible, mirror, modifier)
    }
}

@Composable
private fun SurfaceRenderer(media: WebRtcCallMedia, remote: Boolean, visible: Boolean, mirror: Boolean, modifier: Modifier) {
    val view = remember { arrayOfNulls<livekit.org.webrtc.SurfaceViewRenderer>(1) }
    DisposableEffect(remote) {
        onDispose {
            // Owner-checked (the swap's new renderer attached itself before this runs): never clears the successor.
            view[0]?.let { if (remote) media.detachRemote(it) else media.detachLocal(it) }
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

/**
 * A shared screen: the renderer's view is laid out at exactly the fitted size ([ShareFit.fit] of the
 * frame's oriented size), centred in the stage. The renderer crops a frame to its view's aspect
 * ratio (EglRenderer's layout aspect ratio), so a full-stage view — what this was before — cropped and
 * enlarged a screen whose aspect differs from the viewer's; a view with the frame's own aspect draws
 * all of it. Debug builds log `calls: share-fit frame=… view=… box=… at=…` (the device gate reads it).
 */
@Composable
private fun ScreenRenderer(media: WebRtcCallMedia, remote: Boolean, visible: Boolean, modifier: Modifier) {
    var frame by remember { mutableStateOf(0 to 0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val boxW = constraints.maxWidth
        val boxH = constraints.maxHeight
        val (fw, fh) = ShareFit.fit(boxW, boxH, frame.first, frame.second)
        val box = remember { java.util.concurrent.atomic.AtomicReference(0 to 0) }
        box.set(boxW to boxH)
        TextureRenderer(
            media, remote, visible, zoom, pan,
            onFrame = { w, h, rot -> frame = ShareFit.oriented(w, h, rot) },
            box = box,
            modifier = Modifier.layout { m, _ ->
                val p = m.measure(Constraints.fixed(fw.coerceAtLeast(1), fh.coerceAtLeast(1)))
                layout(p.width, p.height) { p.place(0, 0) }
            },
        )
        // Over the renderer: pinch-zoom and pan (clamped to the zoomed overflow), double tap resets.
        Box(
            Modifier.matchParentSize()
                .pointerInput(fw, fh, boxW, boxH) {
                    detectTransformGestures { _, p, z, _ ->
                        zoom = (zoom * z).coerceIn(1f, ShareFit.MAX_ZOOM)
                        pan = if (zoom == 1f) Offset.Zero else ShareFit.clampPan(pan + p, fw, fh, boxW, boxH, zoom)
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        zoom = 1f
                        pan = Offset.Zero
                    })
                },
        )
    }
}

@Composable
private fun TextureRenderer(
    media: WebRtcCallMedia,
    remote: Boolean,
    visible: Boolean,
    zoom: Float,
    pan: Offset,
    onFrame: (width: Int, height: Int, rotation: Int) -> Unit,
    box: java.util.concurrent.atomic.AtomicReference<Pair<Int, Int>>,
    modifier: Modifier,
) {
    val view = remember { arrayOfNulls<TextureViewRenderer>(1) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val last = remember { java.util.concurrent.atomic.AtomicReference(Triple(0, 0, 0)) }
    val report = remember { arrayOfNulls<() -> Unit>(1) }
    DisposableEffect(remote) {
        onDispose {
            // Owner-checked (the swap's new renderer attached itself before this runs): never clears the successor.
            view[0]?.let { if (remote) media.detachRemote(it) else media.detachLocal(it) }
            view[0]?.release()
            view[0] = null
            report[0] = null
        }
    }
    AndroidView(
        factory = { ctx ->
            TextureViewRenderer(ctx).apply {
                val self = this
                report[0] = {
                    val (fw, fh, rot) = last.get()
                    val (bw, bh) = box.get()
                    val at = IntArray(2).also { self.getLocationOnScreen(it) }
                    media.debugLine("calls: share-fit frame=${fw}x$fh rot=$rot view=${self.width}x${self.height} box=${bw}x$bh at=${at[0]},${at[1]}")
                }
                init(
                    media.egl.eglBaseContext,
                    object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() = Unit

                        override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                            last.set(Triple(videoWidth, videoHeight, rotation))
                            main.post {
                                onFrame(videoWidth, videoHeight, rotation)
                                report[0]?.invoke()
                            }
                        }
                    },
                )
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                // The view itself has the frame's aspect ratio (ScreenRenderer): no fixed-size surface tricks.
                setEnableHardwareScaler(false)
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> main.post { report[0]?.invoke() } }
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
