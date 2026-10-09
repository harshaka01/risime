package lk.codegen.risime.calls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import lk.codegen.risime.ui.common.RisiIcons
import lk.codegen.risime.ui.theme.BrandCyan
import lk.codegen.risime.ui.theme.RisiTheme
import lk.codegen.risime.ui.theme.Spacing
import kotlin.math.roundToInt

/** Where the call is, for the one call screen (WhatsApp-style: the same screen for every phase). */
enum class CallStage { INCOMING, OUTGOING, CONNECTING, ACTIVE, ENDED }

/** One control's state: shown on/off, enabled, its spoken name, and (disabled) why. */
data class ControlUi(val on: Boolean = false, val enabled: Boolean = true, val description: String, val hint: String? = null)

/** Everything the one call screen shows (voice and video, incoming to ended). */
data class CallScreenUi(
    /** null = the nameless locked ring (decision 051). */
    val name: String?,
    val status: String,
    val stage: CallStage,
    val photoKey: String? = null,
    /** §16.10 (e) passed (decision 048: never claimed otherwise). */
    val encrypted: Boolean = false,
    /** Video mode: the remote video full screen, controls auto-hide. */
    val video: Boolean = false,
    /** An incoming video call: "Answer without video" too. */
    val incomingVideo: Boolean = false,
    val incomingSubtitle: String? = null,
    val muted: Boolean = false,
    val endpoints: List<EndpointUi> = emptyList(),
    val current: EndpointUi? = null,
    /** The Video button (voice: ask to switch; video: my camera / back to voice). */
    val videoControl: ControlUi = ControlUi(description = "Switch to video", enabled = false),
    /** Share screen / Stop sharing. */
    val shareControl: ControlUi = ControlUi(description = "Share screen", enabled = false),
    /** My camera preview runs (the draggable corner). */
    val showLocal: Boolean = false,
    /** "Flip camera" in More. */
    val canFlip: Boolean = false,
    /** "Turn camera off" in More (video mode, my camera on, the Video button means "back to voice"). */
    val canCameraOff: Boolean = false,
    /** "Switch to voice call" in More (video mode with a v1.23 peer, whatever my camera does). */
    val canBackToVoice: Boolean = false,
    /** "You're sharing your screen" with Stop. */
    val sharing: Boolean = false,
    /** "<name> is sharing their screen". */
    val peerSharing: Boolean = false,
    /** "Asking … to switch to video…" with Cancel. */
    val asking: String? = null,
    /** The incoming switch prompt's source (camera / screen). */
    val prompt: String? = null,
    /** A short line after a switch attempt. */
    val notice: String? = null,
    /** Group calls: no add-participant upgrade from here. */
    val addParticipantHint: String = "Adding people to a call isn't available yet",
)

/** What the call screen's controls do. */
data class CallScreenActions(
    val onMinimise: () -> Unit = {},
    val onAnswer: () -> Unit = {},
    val onAnswerWithoutVideo: () -> Unit = {},
    val onDecline: () -> Unit = {},
    val onMute: (Boolean) -> Unit = {},
    val onEndpoint: (EndpointUi) -> Unit = {},
    val onVideo: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onEnd: () -> Unit = {},
    val onFlip: () -> Unit = {},
    val onCameraOff: () -> Unit = {},
    val onBackToVoice: () -> Unit = {},
    val onCallInfo: () -> Unit = {},
    val onCancelAsk: () -> Unit = {},
    /** The switch prompt: (accept, my camera on). */
    val onPrompt: (Boolean, Boolean) -> Unit = { _, _ -> },
    val onStopShare: () -> Unit = {},
)

/** Controls hide after this in video mode (a tap brings them back). */
const val CONTROLS_HIDE_MS = 4_000L

/** In video mode (connected, nothing asked) the controls auto-hide; everywhere else they stay. */
fun controlsAutoHide(ui: CallScreenUi): Boolean = ui.video && ui.stage == CallStage.ACTIVE && ui.prompt == null && ui.asking == null

private val Green = Color(0xFF1E9E4A)
private val Red = Color(0xFFD93025)

/**
 * The voice call background: the brand gradient with a light doodle (chat bubbles and dots, like
 * the chat's wallpaper) and the soft glow behind the avatar. White text stays ≥ 4.5:1 (decision 040).
 */
@Composable
fun CallDoodleBackground(modifier: Modifier = Modifier) {
    val c = RisiTheme.colors
    Box(
        modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.callTop, c.callBottom))).drawBehind {
            val step = 72.dp.toPx()
            val ink = Color.White.copy(alpha = 0.05f)
            var row = 0
            var y = step / 2
            while (y < size.height + step) {
                var x = if (row % 2 == 0) step / 2 else step
                while (x < size.width + step) {
                    when ((row + (x / step).toInt()) % 3) {
                        0 -> drawRoundRect(ink, Offset(x - 14.dp.toPx(), y - 9.dp.toPx()), Size(28.dp.toPx(), 18.dp.toPx()), CornerRadius(9.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                        1 -> drawCircle(ink, 4.dp.toPx(), Offset(x, y))
                        else -> drawCircle(ink, 9.dp.toPx(), Offset(x, y), style = Stroke(1.5.dp.toPx()))
                    }
                    x += step
                }
                y += step
                row++
            }
            drawRect(
                Brush.radialGradient(
                    listOf(BrandCyan.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 0.40f),
                    radius = size.width * 0.75f,
                ),
            )
        },
    )
}

/**
 * The one call screen (Harsha's overnight item 3): voice and video, incoming, outgoing,
 * connecting, connected and ended. Top: minimise, name, "End-to-end encrypted" (only when
 * verified), add participant. Voice: a large avatar on the doodle background. Video: [remote] full
 * screen, [local] in a draggable corner that snaps to the corners, a tap toggles the controls
 * (auto-hide after 4 s). Bottom: Speaker | Video | Mute / More | Share | End.
 */
@Composable
fun CallScreen(
    ui: CallScreenUi,
    a: CallScreenActions,
    modifier: Modifier = Modifier,
    remote: (@Composable (Modifier) -> Unit)? = null,
    local: (@Composable (Modifier) -> Unit)? = null,
    /** §20.5 a group voice call's participant list instead of the avatar. */
    center: (@Composable () -> Unit)? = null,
    /** Debug builds' device test keeps the controls up (`files/debug_keep_call_controls`). */
    allowAutoHide: Boolean = true,
) {
    var controls by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }
    // TalkBack users keep the controls (a hidden button can't be found by touch exploration).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val talkBack = remember { ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }
    val autoHide = controlsAutoHide(ui) && !talkBack && allowAutoHide
    if (!autoHide && !controls) controls = true
    LaunchedEffect(autoHide, controls, touch) {
        if (autoHide && controls) {
            delay(CONTROLS_HIDE_MS)
            controls = false
        }
    }
    val videoStage = ui.video && remote != null && ui.stage != CallStage.ENDED
    Box(
        // A tap anywhere not on a control shows or hides the controls (no semantics merge: every label stays its own node).
        modifier.fillMaxSize().testTag("call-stage").pointerInput(autoHide) {
            if (autoHide) {
                detectTapGestures {
                    controls = !controls
                    touch++
                }
            }
        }.semantics {
            if (autoHide) {
                onClick(if (controls) "Hide call controls" else "Show call controls") {
                    controls = !controls
                    touch++
                    true
                }
            }
        },
    ) {
        if (videoStage) remote!!(Modifier.fillMaxSize()) else CallDoodleBackground()
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val compact = maxHeight < 600.dp
                Column(Modifier.fillMaxSize()) {
                    AnimatedVisibility(controls || !videoStage, enter = fadeIn(), exit = fadeOut()) {
                        TopBar(ui, a, scrim = videoStage)
                    }
                    if (!videoStage) {
                        Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            if (center != null) center() else CallAvatar(ui.name, pulse = ui.stage == CallStage.INCOMING || ui.stage == CallStage.OUTGOING, photoKey = ui.photoKey, scale = if (compact) 0.72f else 1f)
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Banners(ui, a)
                    AnimatedVisibility(controls || !videoStage, enter = fadeIn(), exit = fadeOut()) {
                        BottomPanel(ui, a, compact) { touch++ }
                    }
                }
                if (videoStage && ui.showLocal && local != null) {
                    DraggablePreview(maxWidth.value, maxHeight.value, local)
                }
            }
        }
        ui.prompt?.let { SwitchPrompt(ui.name ?: "", it, a.onPrompt) }
    }
}

@Composable
private fun TopBar(ui: CallScreenUi, a: CallScreenActions, scrim: Boolean) {
    var hint by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .then(if (scrim) Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))) else Modifier)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (ui.stage != CallStage.INCOMING && ui.stage != CallStage.ENDED) {
                IconButton(onClick = a.onMinimise, modifier = Modifier.semantics { contentDescription = "Minimise" }) {
                    Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(30.dp))
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (ui.encrypted) {
                    Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.85f))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("End-to-end encrypted", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
                }
            }
            if (ui.stage != CallStage.INCOMING && ui.stage != CallStage.ENDED) {
                Box {
                    // §20/§23 have no 1:1 → group upgrade yet: shown, disabled, with why on a tap.
                    IconButton(
                        onClick = { hint = true },
                        modifier = Modifier.semantics { contentDescription = "Add participant (unavailable)" },
                    ) { Icon(RisiIcons.PersonAdd, null, tint = Color.White.copy(alpha = 0.45f)) }
                    DropdownMenu(hint, onDismissRequest = { hint = false }) {
                        DropdownMenuItem(text = { Text(ui.addParticipantHint) }, onClick = { hint = false })
                    }
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
        Text(
            ui.name ?: "Incoming RisiMe call",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.lg).semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            if (ui.name == null) "Unlock RisiMe to see who's calling" else ui.status,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** The lines above the controls: my share (red, with Stop), the peer's share, my pending request, a notice. */
@Composable
private fun Banners(ui: CallScreenUi, a: CallScreenActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
        if (ui.sharing) {
            Surface(color = Red, contentColor = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxs)) {
                Row(Modifier.padding(start = Spacing.md, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Icon(RisiIcons.ScreenShare, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text("You're sharing your screen", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = a.onStopShare) { Text("Stop", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
        if (ui.peerSharing) Pill("${ui.name ?: ""} is sharing their screen")
        ui.asking?.let { line ->
            Surface(color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(vertical = Spacing.xxs)) {
                Row(Modifier.padding(start = Spacing.md, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text(line, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = a.onCancelAsk) { Text("Cancel", color = Color.White) }
                }
            }
        }
        ui.notice?.let { Pill(it) }
    }
}

@Composable
private fun Pill(text: String) {
    Surface(color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(vertical = Spacing.xxs)) {
        Text(text, Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun BottomPanel(ui: CallScreenUi, a: CallScreenActions, compact: Boolean, onTouch: () -> Unit) {
    when (ui.stage) {
        CallStage.ENDED -> Spacer(Modifier.height(Spacing.xl))
        CallStage.INCOMING -> Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xl), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundAction("Decline", RisiIcons.CallEnd, Red, a.onDecline)
            if (ui.incomingVideo && ui.name != null) {
                RoundAction("Answer without video", RisiIcons.VideocamOff, Green, a.onAnswerWithoutVideo)
                RoundAction("Answer", RisiIcons.Videocam, Green, a.onAnswer)
            } else {
                RoundAction("Answer", Icons.Default.Call, Green, a.onAnswer)
            }
        }
        else -> Surface(
            color = Color.Black.copy(alpha = 0.35f),
            contentColor = Color.White,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        ) {
            Column(Modifier.padding(vertical = if (compact) Spacing.sm else Spacing.lg), verticalArrangement = Arrangement.spacedBy(if (compact) Spacing.sm else Spacing.lg)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RouteControl(InCallUi(ui.name ?: "", ui.status, endpoints = ui.endpoints, current = ui.current, ended = false), { onTouch(); a.onEndpoint(it) }, speakerLabel = true)
                    val v = ui.videoControl
                    CallControl(
                        label = "Video",
                        description = v.hint?.let { "${v.description} (unavailable: $it)" } ?: v.description,
                        icon = if (v.on) RisiIcons.Videocam else RisiIcons.VideocamOff,
                        on = v.on,
                        enabled = v.enabled,
                        onClick = { onTouch(); a.onVideo() },
                    )
                    CallControl(
                        label = if (ui.muted) "Muted" else "Mute",
                        description = if (ui.muted) "Unmute" else "Mute",
                        icon = if (ui.muted) RisiIcons.MicOff else RisiIcons.Mic,
                        on = ui.muted,
                        onClick = { onTouch(); a.onMute(!ui.muted) },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                    MoreControl(ui, a, onTouch)
                    val s = ui.shareControl
                    CallControl(
                        label = if (ui.sharing) "Stop" else "Share",
                        description = s.hint?.let { "${s.description} (unavailable: $it)" } ?: s.description,
                        icon = RisiIcons.ScreenShare,
                        on = ui.sharing,
                        enabled = s.enabled,
                        onClick = { onTouch(); a.onShare() },
                    )
                    EndControl(a.onEnd)
                }
            }
        }
    }
}

@Composable
private fun EndControl(onEnd: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
        androidx.compose.material3.FilledIconButton(
            onClick = onEnd,
            shape = CircleShape,
            modifier = Modifier.size(60.dp).semantics { contentDescription = "End call" },
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = Red, contentColor = Color.White),
        ) { Icon(RisiIcons.CallEnd, null, Modifier.size(28.dp)) }
        Spacer(Modifier.height(Spacing.xs + Spacing.xxs))
        Text("End", style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/** More: flip camera, camera off, audio output (the full list), call info. */
@Composable
private fun MoreControl(ui: CallScreenUi, a: CallScreenActions, onTouch: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var routes by remember { mutableStateOf(false) }
    Box {
        CallControl(label = "More", description = "More options", icon = Icons.Default.MoreVert, on = false, onClick = { onTouch(); open = true })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (ui.canFlip) DropdownMenuItem(leadingIcon = { Icon(RisiIcons.CameraSwitch, null) }, text = { Text("Flip camera") }, onClick = { open = false; a.onFlip() })
            if (ui.canCameraOff) DropdownMenuItem(leadingIcon = { Icon(RisiIcons.VideocamOff, null) }, text = { Text("Turn camera off") }, onClick = { open = false; a.onCameraOff() })
            if (ui.canBackToVoice) DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Call, null) }, text = { Text("Switch to voice call") }, onClick = { open = false; a.onBackToVoice() })
            DropdownMenuItem(leadingIcon = { Icon(RisiIcons.Speaker, null) }, text = { Text("Audio output") }, onClick = { open = false; routes = true })
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Lock, null) }, text = { Text("Call info") }, onClick = { open = false; a.onCallInfo() })
        }
        DropdownMenu(routes, onDismissRequest = { routes = false }) {
            val list = ui.endpoints.ifEmpty { listOf(EndpointUi("am:EARPIECE", "Phone", EndpointUi.Kind.EARPIECE), EndpointUi("am:SPEAKER", "Speaker", EndpointUi.Kind.SPEAKER)) }
            list.forEach { e ->
                DropdownMenuItem(
                    text = { Text(if (e.kind == EndpointUi.Kind.BLUETOOTH || e.kind == EndpointUi.Kind.OTHER) e.name.ifBlank { routeKindLabel(e.kind) } else routeKindLabel(e.kind)) },
                    trailingIcon = { if (e.id == ui.current?.id) Icon(Icons.Default.Check, "Selected") },
                    onClick = { routes = false; a.onEndpoint(e) },
                )
            }
        }
    }
}

/** My camera in a corner: dragged anywhere, it snaps to the nearest corner when let go. */
@Composable
private fun DraggablePreview(maxW: Float, maxH: Float, local: @Composable (Modifier) -> Unit) {
    val density = LocalDensity.current
    val w = 104f
    val h = 148f
    val m = 12f
    var corner by remember { mutableIntStateOf(1) } // 0 top-start, 1 top-end, 2 bottom-start, 3 bottom-end
    var drag by remember { mutableStateOf(Offset.Zero) }
    fun base(c: Int) = Offset(if (c % 2 == 0) m else maxW - w - m, if (c < 2) m + 96f else maxH - h - m - 220f)
    val pos = base(corner) + drag
    Box(
        Modifier.offset { with(density) { IntOffset(pos.x.dp.roundToPx(), pos.y.dp.roundToPx()) } }
            .size(w.dp, h.dp).clip(RoundedCornerShape(16.dp))
            .semantics { contentDescription = "Your camera" }
            .pointerInput(maxW, maxH) {
                detectDragGestures(
                    onDragEnd = {
                        val p = base(corner) + drag
                        val right = p.x + w / 2 > maxW / 2
                        val bottom = p.y + h / 2 > maxH / 2
                        corner = (if (bottom) 2 else 0) + (if (right) 1 else 0)
                        drag = Offset.Zero
                    },
                ) { change, amount ->
                    change.consume()
                    drag += Offset(amount.x / density.density, amount.y / density.density)
                }
            },
    ) { local(Modifier.fillMaxSize()) }
}

/** §23.2 step 2: "<name> wants to switch to video" (Accept / Without my camera / Decline) or to share the screen (Watch / Decline). */
@Composable
private fun SwitchPrompt(name: String, source: String, onPrompt: (Boolean, Boolean) -> Unit) {
    val screen = source == CallEnvelope.SOURCE_SCREEN
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (screen) "Watch $name's screen?" else "Switch to video call?") },
        text = { Text(CallTexts.promptLine(source, name)) },
        confirmButton = {
            TextButton(onClick = { onPrompt(true, !screen) }) { Text(if (screen) "Watch" else "Accept") }
        },
        dismissButton = {
            Row {
                if (!screen) TextButton(onClick = { onPrompt(true, false) }) { Text("Without my camera") }
                TextButton(onClick = { onPrompt(false, false) }) { Text("Decline") }
            }
        },
    )
}

/**
 * More → Call info: this call's facts (encryption, mode, route, path). The per-contact call list
 * lives in the Calls tab's call info (call records).
 */
@Composable
fun CallInfoDialog(s: CallSnapshot, name: String, routes: CallRoutes, stats: DtlsStats?, onDismiss: () -> Unit) {
    val lines = callInfoLines(s, routes, stats)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = { Column { lines.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodyMedium) } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The call info lines (decision 048: "End-to-end encrypted" only when verified). */
fun callInfoLines(s: CallSnapshot, routes: CallRoutes, stats: DtlsStats?): List<Pair<String, String>> = listOfNotNull(
    "Call" to (if (s.group) "Group " else "") + (if (s.video) "video call" else "voice call") + (if (s.sharing) " · sharing your screen" else if (s.peerSharing) " · watching a shared screen" else ""),
    "Encryption" to (if (s.verified) "End-to-end encrypted (MLS-bound DTLS-SRTP)" else "Not verified yet"),
    "Audio" to routeKindLabel(routes.current?.kind ?: EndpointUi.Kind.OTHER),
    stats?.localCandidateType?.let { l -> "Path" to (if (l == "relay" || stats.remoteCandidateType == "relay") "Relayed" else "Direct") },
)

const val SHARE_DIALOG_TITLE = "Share your screen?"
const val SHARE_DIALOG_TEXT = "Your whole screen, including notifications, will be visible."
const val SHARE_DIALOG_DND_TEXT = "Turn on Do Not Disturb to hide notifications from other apps."
const val SHARE_DIALOG_START = "Start"

/** Android ≤ 14 has no system redaction of other apps' notifications during a projection (§23.5): the DND hint. */
fun shareDialogShowsDnd(sdk: Int): Boolean = sdk <= 34

/**
 * Before every share (Harsha's report; WhatsApp's wording), then the system consent: RisiMe's own
 * screens are visible in an "Entire screen" share (only the app lock, the Locked chats folder and a
 * locked chat are hidden). [dnd] (Android ≤ 14): the Do Not Disturb hint and its button.
 */
@Composable
fun ShareConfirmDialog(dnd: Boolean, onStart: () -> Unit, onDnd: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(SHARE_DIALOG_TITLE) },
        text = { Text(if (dnd) "$SHARE_DIALOG_TEXT $SHARE_DIALOG_DND_TEXT" else SHARE_DIALOG_TEXT) },
        confirmButton = { TextButton(onClick = onStart) { Text(SHARE_DIALOG_START) } },
        dismissButton = {
            Row {
                if (dnd) TextButton(onClick = onDnd) { Text("Do Not Disturb") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

// ---------------------------------------------------------------- snapshot → screen (pure, unit-tested)

/** What the Video button does now. */
enum class VideoAction { REQUEST, BACK_TO_VOICE, CAMERA_ON, CAMERA_OFF, NONE }

fun callStage(phase: CallPhase): CallStage = when (phase) {
    CallPhase.RINGING_IN -> CallStage.INCOMING
    CallPhase.CALLING, CallPhase.RINGING_OUT -> CallStage.OUTGOING
    CallPhase.ANSWERING, CallPhase.CONNECTING -> CallStage.CONNECTING
    CallPhase.ACTIVE, CallPhase.RECONNECTING -> CallStage.ACTIVE
    CallPhase.ENDED -> CallStage.ENDED
}

private fun live(s: CallSnapshot) = s.phase == CallPhase.ACTIVE || s.phase == CallPhase.RECONNECTING

/**
 * §23.7 the Video button: in voice mode it asks to switch (both listed `switch`); in video mode with
 * a v1.23 peer it goes back to voice while my camera runs, else turns my camera on; with an old peer
 * (or in a group video room) it is v1.18's camera on/off.
 */
fun videoAction(s: CallSnapshot, nowMs: Long): VideoAction = when {
    s.phase == CallPhase.ENDED || s.phase == CallPhase.RINGING_IN -> VideoAction.NONE
    s.video && s.canSwitch && !s.group && live(s) -> if (s.wantCamera) VideoAction.BACK_TO_VOICE else VideoAction.CAMERA_ON
    s.video -> if (s.wantCamera) VideoAction.CAMERA_OFF else VideoAction.CAMERA_ON
    s.group || !live(s) || !s.canSwitch || s.asking != null || s.prompt != null || nowMs < s.videoBlockedUntilMs -> VideoAction.NONE
    else -> VideoAction.REQUEST
}

fun videoControl(s: CallSnapshot, name: String, nowMs: Long): ControlUi = when (videoAction(s, nowMs)) {
    VideoAction.BACK_TO_VOICE -> ControlUi(on = true, description = "Switch to voice call")
    VideoAction.CAMERA_OFF -> ControlUi(on = true, description = "Turn camera off")
    VideoAction.CAMERA_ON -> ControlUi(on = false, description = "Turn camera on")
    VideoAction.REQUEST -> ControlUi(on = false, description = "Switch to video")
    VideoAction.NONE -> ControlUi(
        on = false, enabled = false, description = "Switch to video",
        hint = when {
            s.group -> "video in a group voice call isn't available yet"
            !live(s) -> null
            !s.canSwitch -> CallTexts.switchUnavailableText(name)
            nowMs < s.videoBlockedUntilMs -> "$name declined video"
            else -> null
        },
    )
}

fun shareControl(s: CallSnapshot, name: String, nowMs: Long): ControlUi = when {
    s.sharing -> ControlUi(on = true, description = "Stop sharing")
    s.group -> ControlUi(enabled = false, description = "Share screen", hint = "screen sharing in group calls isn't available yet")
    !live(s) -> ControlUi(enabled = false, description = "Share screen")
    !s.canShare -> ControlUi(enabled = false, description = "Share screen", hint = CallTexts.shareUnavailableText(name))
    s.asking != null || s.prompt != null || (!s.video && nowMs < s.videoBlockedUntilMs) -> ControlUi(enabled = false, description = "Share screen")
    else -> ControlUi(description = "Share screen")
}

/** The one call screen's state for a call snapshot ([status] already worded by the caller). */
fun callScreenUi(s: CallSnapshot, name: String, status: String, routes: CallRoutes, nowMs: Long): CallScreenUi = CallScreenUi(
    name = name,
    status = status,
    stage = callStage(s.phase),
    photoKey = if (s.group) s.conversationId else s.peerUserId,
    encrypted = s.verified && s.phase != CallPhase.ENDED,
    video = s.video && s.phase != CallPhase.ENDED,
    incomingVideo = s.video && s.phase == CallPhase.RINGING_IN,
    incomingSubtitle = if (!s.group) null else if (s.video) "Group video call" else "Group voice call",
    muted = s.muted,
    endpoints = routes.available,
    current = routes.current,
    videoControl = videoControl(s, name, nowMs),
    shareControl = shareControl(s, name, nowMs),
    showLocal = s.cameraOn,
    canFlip = s.cameraOn,
    canCameraOff = s.video && s.wantCamera && videoAction(s, nowMs) == VideoAction.BACK_TO_VOICE,
    canBackToVoice = s.video && s.canSwitch && !s.group && live(s),
    sharing = s.sharing,
    peerSharing = s.peerSharing,
    asking = s.asking?.let { CallTexts.askingLine(it, name) },
    prompt = s.prompt.takeIf { s.phase != CallPhase.ENDED },
    notice = s.switchNotice?.let { CallTexts.switchNotice(it, name) },
)
