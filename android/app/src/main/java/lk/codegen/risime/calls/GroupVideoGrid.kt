package lk.codegen.risime.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.theme.Spacing
import livekit.org.webrtc.RendererCommon

/** §20.9: at most 8 tiles (the video room's cap). */
const val MAX_VIDEO_TILES = 8

/** Tiles per row for [n] tiles: one per row up to 2, then two per row (8 tiles = 4 rows of 2). */
fun gridRows(n: Int): List<Int> {
    val k = n.coerceIn(0, MAX_VIDEO_TILES)
    if (k <= 2) return List(k) { 1 }
    val cols = 2
    return (0 until k step cols).map { minOf(cols, k - it) }
}

/**
 * §20.5 group video: a grid of up to 8 tiles (members only, K7; a tile without a decrypting camera
 * shows the avatar), the camera only while the screen is visible (§19.5, the machine's rule). The
 * tiles' visibility drives LiveKit's adaptive stream (small tiles get the small simulcast layer).
 */
@Composable
fun GroupVideoGrid(session: SfuSession, snap: CallSnapshot, names: Map<String, String>) {
    val room = (session as? LiveKitRoomHolder)?.room() ?: return
    val tiles = snap.members.filter { it.member }.take(MAX_VIDEO_TILES)
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 124.dp, bottom = 296.dp, start = Spacing.xs, end = Spacing.xs), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var i = 0
        for (cols in gridRows(tiles.size)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(cols) {
                    val m = tiles[i++]
                    val name = if (m.local) "You" else names[m.userId] ?: "…"
                    Box(
                        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.35f))
                            .then(if (m.speaking) Modifier.border(3.dp, Color(0xFF1E9E4A), RoundedCornerShape(12.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        val showVideo = if (m.local) snap.cameraOn else m.hasVideo && m.verified
                        if (showVideo) {
                            // Looked up on every snapshot (500 ms): the track object arrives after its publication.
                            val p: Participant? = if (m.local) room.localParticipant else room.remoteParticipants[Participant.Identity(m.identity)]
                            val track = p?.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
                            if (track != null) {
                                androidx.compose.runtime.key(track) { VideoTile(room, track, mirror = m.local) { InitialsAvatar(name, size = 64.dp, photoKey = m.userId) } }
                            } else {
                                InitialsAvatar(name, size = 64.dp, photoKey = m.userId)
                            }
                        } else {
                            InitialsAvatar(name, size = 64.dp, photoKey = m.userId)
                        }
                        Text(
                            name + if (m.cantVerify) " · Can't verify" else "",
                            Modifier.align(Alignment.BottomStart).padding(Spacing.xs).background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp),
                            color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** §19.5 rule for tiles: a camera that sent no frame for 3 s (stopped, frozen) shows the avatar instead. */
@Composable
private fun VideoTile(room: io.livekit.android.room.Room, track: VideoTrack, mirror: Boolean, avatar: @Composable () -> Unit) {
    val holder = remember(track) { arrayOfNulls<TextureViewRenderer>(1) }
    val lastFrame = remember(track) { java.util.concurrent.atomic.AtomicLong(0) }
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(track) {
        while (true) {
            kotlinx.coroutines.delay(500)
            now = System.currentTimeMillis()
        }
    }
    val counter = remember(track) { livekit.org.webrtc.VideoSink { lastFrame.set(System.currentTimeMillis()) } }
    DisposableEffect(track) {
        track.addRenderer(counter)
        onDispose {
            runCatching { track.removeRenderer(counter) }
            holder[0]?.let { v ->
                runCatching { track.removeRenderer(v) }
                runCatching { v.release() }
            }
            holder[0] = null
        }
    }
    AndroidView(
        factory = { ctx ->
            TextureViewRenderer(ctx).apply {
                room.initVideoRenderer(this)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(mirror)
                holder[0] = this
                track.addRenderer(this)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    if (now - lastFrame.get() >= VideoRules.FROZEN_MS) {
        Box(Modifier.fillMaxSize().background(Color(0xFF16324A)), contentAlignment = Alignment.Center) { avatar() }
    }
}

/** The LiveKit session exposes its room to the call screen's renderers. */
interface LiveKitRoomHolder {
    fun room(): io.livekit.android.room.Room
}
