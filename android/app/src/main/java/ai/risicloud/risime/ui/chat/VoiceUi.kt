package ai.risicloud.risime.ui.chat

import ai.risicloud.risime.AppContainer
import ai.risicloud.risime.data.db.MediaEntity
import ai.risicloud.risime.data.db.MediaState
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.media.VoiceMeta
import ai.risicloud.risime.data.media.VoiceText
import ai.risicloud.risime.data.media.Waveform
import ai.risicloud.risime.data.messaging.SelectionRules
import ai.risicloud.risime.net.RisiKinds141
import ai.risicloud.risime.net.RisiVoiceTranscript
import ai.risicloud.risime.ui.theme.RisiTheme
import ai.risicloud.risime.ui.theme.Spacing
import ai.risicloud.risime.voice.VoiceRecorder
import ai.risicloud.risime.voice.VoiceSpeeds
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** v1.41 §37 voice-note strings. */
object VoiceStrings {
    const val PLAY = "Play voice note"
    const val PAUSE = "Pause voice note"
    const val SLIDE_TO_CANCEL = "‹ Slide to cancel"
    const val LOCKED_HINT = "Recording"
    const val TOO_SHORT = "Hold to record, release to send"
    const val MIC_RATIONALE_TITLE = "Allow the microphone"
    const val MIC_RATIONALE = "RisiMe needs the microphone to record voice notes. Audio is recorded only while you hold the mic (or after you lock it), and the note is end-to-end encrypted."
    const val MIC_DENIED = "Voice notes need the microphone. You can allow it in Android Settings → Apps → RisiMe → Permissions."
    const val CANT_RECORD = "This phone can't record voice notes"
    const val MIC_BUSY = "The microphone is busy"
    const val OFFICIAL_NOTE = "Risi transcribes voice notes in Official chats. You can turn this off in Settings → Risi."
    const val TRANSCRIPT = "Transcript"
    const val COULDNT = "Couldn't transcribe"
    const val DELETE_TRANSCRIPT = "Delete transcript"
    const val TRANSCRIBE_SETTING = "Transcribe my voice notes in Official chats"
    const val TRANSCRIBE_SETTING_SUB = "Risi turns your Official voice notes into text for everyone in the chat. Private chats are never transcribed."
    const val ACTION_ITEMS_SETTING = "Find action items in my calls and voice notes"
    fun dmNotReady(name: String) = "$name needs to update RisiMe to get voice notes"
    const val GROUP_NOT_READY = "Some members need to update RisiMe to get voice notes"

    /** TalkBack for the bubble: "Voice note, 0:12, played". */
    fun label(m: MessageEntity, meta: VoiceMeta?): String = buildString {
        append("Voice note")
        meta?.let { append(", ").append(VoiceText.duration(it.durationMs)) }
        if (m.outgoing && m.playedAt != null) append(", played")
        if (!m.outgoing && m.playedAt == null) append(", not played yet")
    }
}

/** The mic glyph (Material "mic"). */
val MicIcon: ImageVector by lazy {
    ImageVector.Builder("Mic", 24.dp, 24.dp, 24f, 24f).addPath(
        PathParser().parsePathString(
            "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
        ).toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
    ).build()
}

/** The pause glyph. */
val PauseIcon: ImageVector by lazy {
    ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).addPath(
        PathParser().parsePathString("M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z").toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
    ).build()
}

/** The bars of a 64-level waveform with the played part in [played]; [progress] 0..1. */
@Composable
fun WaveformBars(levels: FloatArray, progress: Float, played: Color, rest: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = levels.size.coerceAtLeast(1)
        val gap = 1.5.dp.toPx()
        val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val minH = 2.dp.toPx()
        for (i in 0 until n) {
            val h = (minH + levels[i] * (size.height - minH)).coerceAtMost(size.height)
            val x = i * (w + gap)
            drawRoundRect(
                color = if ((i + 0.5f) / n <= progress) played else rest,
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

/**
 * §37.3 the voice-note bubble content: play/pause (or the download state), the waveform as the scrubber (tap or
 * drag to seek), the time, the speed (while it is the current note), the unplayed dot (received) and the mic that
 * turns blue once played (mine).
 */
@Composable
fun VoiceBubbleContent(c: AppContainer, m: MessageEntity, media: MediaEntity?) {
    val meta = remember(m.systemJson) { VoiceMeta.decode(m.systemJson) }
    val s by c.voicePlayer.state.collectAsStateWithLifecycle()
    val current = s.clientMsgId == m.clientMsgId
    val dur = if (current && s.durationMs > 0) s.durationMs else meta?.durationMs ?: 0
    val progress = if (current && dur > 0) (s.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val levels = remember(meta) { Waveform.fractions(meta?.levels ?: ByteArray(64)) }
    val colors = RisiTheme.colors
    val metaColor = if (m.outgoing) colors.bubbleMineMeta else colors.bubbleTheirsMeta
    val accent = colors.readTick
    val avail = SelectionRules.mediaAvail(m, media, System.currentTimeMillis())
    val gone = avail == SelectionRules.MediaAvail.GONE && !(m.outgoing && media?.fileName != null)
    if (ai.risicloud.risime.BuildConfig.DEBUG) {
        androidx.compose.runtime.DisposableEffect(m.clientMsgId) {
            onDispose { VoiceUiDebug.spots.remove("play:${m.clientMsgId}"); VoiceUiDebug.spots.remove("speed:${m.clientMsgId}") }
        }
    }
    Column(Modifier.widthIn(min = 220.dp).padding(top = Spacing.xxs).testTag("voice_bubble")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).then(VoiceUiDebug.track(m, "play")), contentAlignment = Alignment.Center) {
                when {
                    gone -> Icon(MicIcon, null, tint = metaColor)
                    current && s.loading || (media?.state == MediaState.DOWNLOADING.name && !m.outgoing) ->
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    media?.state == MediaState.CORRUPT.name -> Icon(Icons.Default.Refresh, null, tint = metaColor)
                    else -> IconButton(onClick = { meta?.let { c.voicePlayer.toggle(m, it.durationMs) } }, modifier = Modifier.testTag("voice_play")) {
                        Icon(if (current && s.playing) PauseIcon else Icons.Default.PlayArrow, if (current && s.playing) VoiceStrings.PAUSE else VoiceStrings.PLAY, Modifier.size(32.dp))
                    }
                }
            }
            Box(
                Modifier.weight(1f).height(32.dp).padding(horizontal = Spacing.xs)
                    .pointerInput(m.clientMsgId, dur) {
                        // Tap to seek without taking the press from the bubble: a long press still opens its menu.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                            if (up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis && !gone && meta != null) {
                                up.consume()
                                c.voicePlayer.seek(m, meta.durationMs, up.position.x / size.width)
                            }
                        }
                    }
                    .pointerInput(m.clientMsgId, dur) {
                        detectHorizontalDragGestures { change, _ ->
                            if (!gone && meta != null) c.voicePlayer.seek(m, meta.durationMs, change.position.x / size.width)
                        }
                    },
            ) {
                WaveformBars(levels, progress, played = accent, rest = metaColor.copy(alpha = 0.55f), modifier = Modifier.fillMaxWidth().height(32.dp))
            }
            // While it is the current note: the speed pill (WhatsApp's place). Otherwise, mine: the mic that turns accent
            // (blue) once played (DM `played`, group `all_played`); received: the unplayed dot.
            Box(Modifier.size(width = 40.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                if (current && !gone) {
                    Surface(
                        shape = RoundedCornerShape(12.dp), color = metaColor.copy(alpha = 0.22f),
                        modifier = Modifier.size(width = 40.dp, height = 28.dp).clickable { c.voicePlayer.cycleSpeed() }.testTag("voice_speed")
                            .then(VoiceUiDebug.track(m, "speed")),
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text(VoiceSpeeds.label(s.speed), style = MaterialTheme.typography.labelMedium) }
                    }
                } else if (m.outgoing) {
                    Icon(MicIcon, if (m.playedAt != null) "Played" else null, Modifier.size(20.dp).testTag(if (m.playedAt != null) "voice_mic_played" else "voice_mic"), tint = if (m.playedAt != null) accent else metaColor)
                } else if (m.playedAt == null) {
                    Box(Modifier.size(10.dp).background(accent, CircleShape).testTag("voice_unplayed"))
                }
            }
        }
        Row(Modifier.padding(start = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            val shown = if (current && (s.playing || s.positionMs > 0)) s.positionMs else dur
            Text(
                if (gone) SelectionRules.VOICE_GONE else VoiceText.duration(shown),
                style = MaterialTheme.typography.labelSmall, color = metaColor,
            )
            if (current && s.error != null) {
                Spacer(Modifier.width(Spacing.sm))
                Text(s.error!!, style = MaterialTheme.typography.labelSmall, color = metaColor)
            }
        }
    }
}

/**
 * §37.6 Risi's transcript of a note, attached under its bubble (Official only), collapsed to one line; every member
 * sees it. `unclear` / `failed` read "Couldn't transcribe". No model names, no script check. The note's sender can
 * delete only the transcript ([onDelete]).
 */
@Composable
fun VoiceTranscriptBlock(t: RisiVoiceTranscript, mine: Boolean, onDelete: (() -> Unit)?) {
    var userOpen by remember(t.messageId) { mutableStateOf(false) }
    // v1.42 §38.3 a jump to an action item's line in this note expands its transcript.
    val forced = t.messageId?.lowercase()?.let { VoiceJump.expanded[it] } == true
    val open = userOpen || forced
    val ok = t.ok
    Column(
        Modifier.widthIn(max = 320.dp).padding(top = Spacing.xxs, start = if (mine) 0.dp else Spacing.sm, end = if (mine) Spacing.sm else 0.dp)
            .testTag("voice_transcript"),
    ) {
        Row(
            Modifier.clickable(enabled = ok) {
                if (open) { userOpen = false; t.messageId?.lowercase()?.let { VoiceJump.expanded.remove(it) } } else userOpen = true
            }.semantics { contentDescription = if (ok) "${VoiceStrings.TRANSCRIPT}: ${if (open) "expanded" else "collapsed"}" else VoiceStrings.COULDNT },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (ok) VoiceStrings.TRANSCRIPT else VoiceStrings.COULDNT,
                style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.75f),
            )
            if (ok) Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp), tint = LocalContentColor.current.copy(alpha = 0.75f))
        }
        if (open && ok) {
            Text(t.text.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xxs).testTag("voice_transcript_text"))
            if (onDelete != null) {
                Text(
                    VoiceStrings.DELETE_TRANSCRIPT, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.xxs).clickable(onClick = onDelete),
                )
            }
        }
    }
}

/** §37.6 the transcript row of [note] among [rows] (a Risi `voice_transcript` whose `message_id` is the note's). */
fun transcriptFor(note: MessageEntity, rows: List<MessageEntity>): RisiVoiceTranscript? {
    val id = note.messageId ?: return null
    for (r in rows) {
        if (r.kind != MessageEntity.KIND_TEXT || r.systemJson == null || !r.systemJson.contains(RisiKinds141.VOICE_TRANSCRIPT)) continue
        val t = RisiVoiceTranscript.parse(r.systemJson) ?: continue
        if (t.messageId.equals(id, true)) return t
    }
    return null
}

/**
 * Debug builds only: where each laid-out voice bubble's play button and speed pill are on screen, so the redroid gate
 * (whose uiautomator can't see inside the merged bubble) can tap them; logged on the debug `VOICE_INJECT --ez where`
 * broadcast. Release builds track nothing (the modifier is empty).
 */
object VoiceUiDebug {
    data class Spot(val clientMsgId: String, val conversationId: String, val outgoing: Boolean, val localTs: Long, val x: Int, val y: Int)

    val spots = java.util.concurrent.ConcurrentHashMap<String, Spot>()

    fun track(m: MessageEntity, what: String): Modifier = if (!ai.risicloud.risime.BuildConfig.DEBUG) Modifier else
        Modifier.onGloballyPositioned { lc ->
            val r = lc.boundsInWindow()
            if (r.width > 0 && r.height > 0) spots["$what:${m.clientMsgId}"] = Spot(m.clientMsgId, m.conversationId, m.outgoing, m.localTs, r.center.x.toInt(), r.center.y.toInt())
        }

    /** One line per spot: "voice ui <what> <id> <conv> <in|out> <localTs> <x> <y>". */
    fun lines(): List<String> = spots.entries.sortedBy { it.value.localTs }.map { (k, s) ->
        "voice ui ${k.substringBefore(':')} ${s.clientMsgId} ${s.conversationId} ${if (s.outgoing) "out" else "in"} ${s.localTs} ${s.x} ${s.y}"
    }

    fun clear() = spots.clear()
}

/**
 * v1.42 §38.3 a jump to an action item's line in a voice note: the note's transcript expands and playback seeks to
 * the line's `offset_ms` (the player starts there, as a tap on the waveform does).
 */
object VoiceJump {
    /** Note message ids (lowercase) whose transcript a jump expanded. */
    val expanded = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    fun apply(c: AppContainer, row: MessageEntity, ref: ai.risicloud.risime.net.SourceRef) {
        if (!row.voice || !ref.isVoiceNote) return
        row.messageId?.lowercase()?.let { expanded[it] = true }
        val meta = VoiceMeta.decode(row.systemJson) ?: return
        c.voicePlayer.seek(row, meta.durationMs, ai.risicloud.risime.data.tabs.RisiActionItems.seekFraction(ref.offsetMs, meta.durationMs))
    }
}

/** A tap on a voice bubble: play / pause. */
fun voiceTap(c: AppContainer, m: MessageEntity) {
    VoiceMeta.decode(m.systemJson)?.let { c.voicePlayer.toggle(m, it.durationMs) }
}

/**
 * Under a voice bubble: Risi's transcript, only in Official and the Risi chat (a Private note never shows one, §37.0).
 * The note's sender may delete only the transcript (`risi_action` `transcript_delete`, §37.6).
 */
@Composable
fun VoiceFooter(m: MessageEntity, x: ChatMessaging?) {
    if (!m.voice || x == null) return
    // Proposal 2026-10-14: the sender phone's own on-device transcript (inside the E2EE note; any tab). Absent on every
    // note sent without the developer `phone_stt` flag, so nothing changes for those.
    val phone = remember(m.systemJson) { VoiceMeta.decode(m.systemJson)?.phoneTranscript }
    if (x.c.voice.place(m.conversationId) != ai.risicloud.risime.voice.VoicePlace.PRIVATE) RisiVoiceFooter(m, x)
    if (phone != null) PhoneTranscriptBlock(m.clientMsgId, phone.text, mine = m.outgoing)
}

/** The sender phone's transcript under the bubble, collapsed to one line, expandable. Never labelled as Risi's. */
@Composable
fun PhoneTranscriptBlock(key: String, text: String, mine: Boolean) {
    var open by remember(key) { mutableStateOf(false) }
    Column(
        Modifier.widthIn(max = 320.dp).padding(top = Spacing.xxs, start = if (mine) 0.dp else Spacing.sm, end = if (mine) Spacing.sm else 0.dp)
            .testTag("voice_phone_transcript"),
    ) {
        Row(
            Modifier.clickable { open = !open }
                .semantics { contentDescription = "${ai.risicloud.risime.voice.PhoneSttStrings.BLOCK_LABEL}: ${if (open) "expanded" else "collapsed"}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ai.risicloud.risime.voice.PhoneSttStrings.BLOCK_LABEL, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.75f))
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp), tint = LocalContentColor.current.copy(alpha = 0.75f))
        }
        if (open) Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xxs).testTag("voice_phone_transcript_text"))
    }
}

/** §37.6 Risi's transcript under an Official / Risi-chat note (the v1.41 footer, unchanged). */
@Composable
private fun RisiVoiceFooter(m: MessageEntity, x: ChatMessaging) {
    val t = remember(m.messageId, x.rows) { transcriptFor(m, x.rows) } ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    VoiceTranscriptBlock(
        t, mine = m.outgoing,
        onDelete = if (m.outgoing && m.messageId != null && x.c.voice.place(m.conversationId) == ai.risicloud.risime.voice.VoicePlace.OFFICIAL) ({
            scope.launch {
                x.c.engine.sendRisiControl(
                    m.conversationId,
                    kotlinx.serialization.json.buildJsonObject {
                        put("v", kotlinx.serialization.json.JsonPrimitive(1))
                        put("type", kotlinx.serialization.json.JsonPrimitive(ai.risicloud.risime.data.tabs.RisiControl.TYPE_ACTION))
                        put("target", kotlinx.serialization.json.JsonPrimitive(m.messageId))
                        put("action", kotlinx.serialization.json.JsonPrimitive(RisiKinds141.ACTION_TRANSCRIPT_DELETE))
                        put("edit", kotlinx.serialization.json.JsonNull)
                    },
                )
            }
        }) else null,
    )
}

/** The note this transcript row belongs to, if it is on the phone (then the row draws nothing of its own). */
fun transcriptHasNote(row: MessageEntity, rows: List<MessageEntity>): Boolean {
    val t = RisiVoiceTranscript.parse(row.systemJson) ?: return false
    return rows.any { it.voice && it.messageId != null && it.messageId.equals(t.messageId, true) }
}

/** The composer's recording strip: the red dot, the timer, the live waveform and "Slide to cancel" (or [Delete] when locked). */
@Composable
fun RecordingBar(state: VoiceRecorder.State, locked: Boolean, dragX: Float, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val rec = state as? VoiceRecorder.State.Recording
    val colors = RisiTheme.colors
    Row(modifier.height(48.dp).padding(horizontal = Spacing.sm).testTag("voice_recording"), verticalAlignment = Alignment.CenterVertically) {
        if (locked) {
            IconButton(onClick = onDelete, modifier = Modifier.testTag("voice_delete")) { Icon(Icons.Default.Delete, "Delete recording", tint = MaterialTheme.colorScheme.error) }
        } else {
            Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(VoiceText.duration(rec?.elapsedMs ?: 0).let { if ((rec?.elapsedMs ?: 0) < 500) "0:00" else it }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("voice_timer"))
        Spacer(Modifier.width(Spacing.sm))
        val live = rec?.levels.orEmpty()
        val bars = FloatArray(VoiceRecorder.LIVE_BARS) { i -> live.getOrNull(live.size - VoiceRecorder.LIVE_BARS + i) ?: 0f }
        if (locked) {
            WaveformBars(bars, 1f, played = colors.readTick, rest = colors.readTick, modifier = Modifier.weight(1f).height(28.dp))
        } else {
            WaveformBars(bars, 1f, played = LocalContentColor.current.copy(alpha = 0.6f), rest = LocalContentColor.current.copy(alpha = 0.6f), modifier = Modifier.width(72.dp).height(24.dp))
            Spacer(Modifier.weight(1f))
            Text(
                VoiceStrings.SLIDE_TO_CANCEL, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f),
                modifier = Modifier.padding(end = Spacing.sm).graphicsLayer { translationX = dragX.coerceIn(-400f, 0f) / 2 },
            )
            Icon(Icons.Default.Lock, "Slide up to lock", Modifier.size(16.dp), tint = LocalContentColor.current.copy(alpha = 0.5f))
        }
    }
}


/**
 * The round mic button in the Send button's place (§37.3): hold to record, slide left to cancel, slide up to lock.
 * [onPress] returns false when recording can't start (permission asked, one-time note shown, not ready).
 */
@Composable
fun MicButton(
    enabled: Boolean,
    onPress: () -> Boolean,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
    onLock: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    size: androidx.compose.ui.unit.Dp,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cancelPx = with(density) { 110.dp.toPx() }
    val lockPx = with(density) { 80.dp.toPx() }
    Surface(
        shape = CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(size).testTag("voice_mic_button").semantics { contentDescription = "Record voice note" }
            .pointerInput(enabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (!onPress()) {
                        // Drain this gesture (the press only asked for something).
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    var ended = false
                    while (!ended) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id } ?: e.changes.first()
                        val dx = ch.position.x - down.position.x
                        val dy = ch.position.y - down.position.y
                        when {
                            !ch.pressed -> { onRelease(); ended = true }
                            dx < -cancelPx -> { onCancel(); ended = true }
                            dy < -lockPx -> { onLock(); ended = true }
                            else -> onDrag(dx, dy)
                        }
                        ch.consume()
                    }
                    // After a cancel or lock: wait for the finger to lift.
                    while (true) {
                        val e = awaitPointerEvent()
                        e.changes.forEach { it.consume() }
                        if (e.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(MicIcon, null, Modifier.size(24.dp)) }
    }
}

