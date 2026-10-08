package lk.codegen.risime.calls

import androidx.compose.foundation.background
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import lk.codegen.risime.ui.common.RisiIcons
import lk.codegen.risime.ui.theme.BrandCyan
import lk.codegen.risime.ui.theme.RisiTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.theme.Spacing

/** One audio route for the picker (Telecom's CallEndpointCompat, flattened for the UI and tests). */
data class EndpointUi(val id: String, val name: String, val kind: Kind) {
    enum class Kind { EARPIECE, SPEAKER, WIRED, BLUETOOTH, OTHER }
}

/** What the in-call screen shows. */
data class InCallUi(
    val name: String,
    val status: String,
    val muted: Boolean = false,
    val endpoints: List<EndpointUi> = emptyList(),
    val current: EndpointUi? = null,
    /** §16.10 (e) passed: only then "End-to-end encrypted". */
    val verified: Boolean = false,
    val ended: Boolean = false,
    /** §18.6: the peer's user id for their photo (initials without one). */
    val photoKey: String? = null,
    /** The call is ACTIVE. */
    val active: Boolean = false,
)

/**
 * P0 audio routing: the route button is enabled in every in-call phase (calling, ringing out,
 * connecting, active), whatever Telecom lists: an empty or one-entry list falls back to AudioManager.
 */
fun routeButtonEnabled(ui: InCallUi): Boolean = !ui.ended

private val Green = Color(0xFF1E9E4A)

/** A large initials avatar with a soft ring; [pulse] animates the ring while ringing. */
@Composable
internal fun CallAvatar(name: String?, pulse: Boolean, photoKey: String? = null, scale: Float = 1f) {
    val k = scale
    val ring = if (pulse) {
        val t = rememberInfiniteTransition(label = "ring")
        t.animateFloat(1f, 1.18f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "ring").value
    } else 1f
    Box(Modifier.size(200.dp * k), contentAlignment = Alignment.Center) {
        Box(Modifier.size(180.dp * k).scale(ring).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)))
        Box(Modifier.size(160.dp * k).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)))
        if (name != null) {
            InitialsAvatar(name, size = 140.dp * k, photoKey = photoKey)
        } else {
            Image(painterResource(lk.codegen.risime.R.drawable.brand_mark), null, Modifier.size(140.dp * k))
        }
    }
}

/**
 * The incoming call (the one call screen, [CallScreen], in its incoming stage). [name] null = the
 * locked ring of decision 051: "Incoming RisiMe call", no caller, Answer asks for the fingerprint first.
 */
@Composable
fun IncomingCallScreen(
    name: String?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    photoKey: String? = null,
    /** §19.6: "Incoming video call" with Answer (camera on), Answer without video and Decline. Null = a voice call. */
    onAnswerWithoutVideo: (() -> Unit)? = null,
    /** §20.4 a group call's line under the title ("Group voice call"); null = the 1:1 texts. */
    subtitle: String? = null,
) {
    val video = onAnswerWithoutVideo != null && name != null
    CallScreen(
        CallScreenUi(
            name = name,
            status = when {
                subtitle != null -> subtitle
                video -> "Incoming video call"
                else -> "RisiMe voice call"
            },
            stage = CallStage.INCOMING,
            photoKey = photoKey.takeIf { name != null },
            incomingVideo = video,
        ),
        CallScreenActions(onAnswer = onAnswer, onDecline = onDecline, onAnswerWithoutVideo = { onAnswerWithoutVideo?.invoke() }),
        modifier,
    )
}

@Composable
internal fun RoundAction(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 104.dp)) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp).semantics { contentDescription = label },
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
        ) { Icon(icon, null, Modifier.size(32.dp)) }
        Spacer(Modifier.height(Spacing.sm))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, textAlign = TextAlign.Center)
    }
}

/**
 * §19.5 the video stage: the peer full-screen (or their avatar on `camera: false` and after 3 s
 * without a frame). My camera is drawn by [CallScreen]'s draggable corner.
 */
@Composable
fun VideoCallStage(
    showPeer: Boolean,
    name: String,
    photoKey: String?,
    remote: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        remote(Modifier.fillMaxSize())
        if (!showPeer) {
            Box(Modifier.fillMaxSize().semantics { contentDescription = "$name's camera is off" }, contentAlignment = Alignment.Center) {
                CallDoodleBackground()
                CallAvatar(name, pulse = false, photoKey = photoKey)
            }
        }
    }
}

/** A round call control: frosted when off, white when on (speaker, muted). */
@Composable
internal fun CallControl(
    label: String,
    description: String,
    icon: ImageVector,
    on: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            modifier = Modifier.size(60.dp).semantics {
                contentDescription = description
                stateDescription = if (on) "On" else "Off"
            },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (on) Color.White else Color.White.copy(alpha = 0.16f),
                contentColor = if (on) RisiTheme.colors.callBottom else Color.White,
                disabledContainerColor = Color.White.copy(alpha = 0.08f),
                disabledContentColor = Color.White.copy(alpha = 0.5f),
            ),
        ) { Icon(icon, null, Modifier.size(26.dp)) }
        Spacer(Modifier.height(Spacing.xs + Spacing.xxs))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = if (enabled) 1f else 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Outgoing, connecting, connected and ended (voice): the one call screen ([CallScreen]) with its
 * Speaker | Video | Mute / More | Share | End panel. Kept for the voice-only callers and tests.
 */
@Composable
fun InCallScreen(
    ui: InCallUi,
    onMute: (Boolean) -> Unit,
    onEndpoint: (EndpointUi) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    /** §20.5 a group call's participant list (voice) shown instead of the avatar. */
    center: (@Composable () -> Unit)? = null,
) {
    CallScreen(
        CallScreenUi(
            name = ui.name,
            status = ui.status,
            stage = when {
                ui.ended -> CallStage.ENDED
                ui.active -> CallStage.ACTIVE
                else -> CallStage.OUTGOING
            },
            photoKey = ui.photoKey,
            encrypted = ui.verified,
            muted = ui.muted,
            endpoints = ui.endpoints,
            current = ui.current,
        ),
        CallScreenActions(onMute = onMute, onEndpoint = onEndpoint, onEnd = onEnd),
        modifier,
        center = center,
    )
}

/**
 * P0 audio routing: the button always shows the real current route (icon) and is always enabled
 * in a call; "Speaker" is highlighted while the speaker (or a headset) plays. With only the
 * earpiece and the speaker a tap switches between them; with a Bluetooth device or a headset it
 * opens the picker (Bluetooth / Headset / Phone / Speaker, the current one checked). A route
 * nobody listed is still asked for (id "am:<KIND>": AudioManager). [speakerLabel]: the label says
 * "Speaker" on the earpiece and the speaker (WhatsApp), else the route's name.
 */
@Composable
internal fun RouteControl(ui: InCallUi, onEndpoint: (EndpointUi) -> Unit, speakerLabel: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val current = ui.current?.kind
    Box {
        CallControl(
            label = when {
                current == EndpointUi.Kind.BLUETOOTH || current == EndpointUi.Kind.WIRED -> routeKindLabel(current)
                speakerLabel -> "Speaker"
                else -> current?.let(::routeKindLabel) ?: "Audio"
            },
            description = "Audio output",
            icon = when (current) {
                EndpointUi.Kind.BLUETOOTH -> RisiIcons.Bluetooth
                EndpointUi.Kind.WIRED -> RisiIcons.Headset
                EndpointUi.Kind.EARPIECE -> if (speakerLabel) RisiIcons.Speaker else RisiIcons.Phone
                else -> RisiIcons.Speaker
            },
            on = current == EndpointUi.Kind.SPEAKER || current == EndpointUi.Kind.BLUETOOTH || current == EndpointUi.Kind.WIRED,
            enabled = routeButtonEnabled(ui),
            onClick = {
                when (val tap = routeTap(ui.endpoints, current)) {
                    RouteTap.Picker -> open = true
                    is RouteTap.Switch -> onEndpoint(ui.endpoints.firstOrNull { it.kind == tap.to } ?: EndpointUi("am:${tap.to.name}", routeKindLabel(tap.to), tap.to))
                }
            },
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            ui.endpoints.forEach { e ->
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            when (e.kind) {
                                EndpointUi.Kind.SPEAKER -> RisiIcons.Speaker
                                EndpointUi.Kind.BLUETOOTH -> RisiIcons.Bluetooth
                                EndpointUi.Kind.WIRED -> RisiIcons.Headset
                                else -> RisiIcons.Phone
                            },
                            null,
                        )
                    },
                    text = { Text(if (e.kind == EndpointUi.Kind.BLUETOOTH || e.kind == EndpointUi.Kind.OTHER) e.name.ifBlank { routeKindLabel(e.kind) } else routeKindLabel(e.kind)) },
                    trailingIcon = { if (e.id == ui.current?.id) Icon(Icons.Default.Check, "Selected") },
                    onClick = { open = false; onEndpoint(e) },
                )
            }
        }
    }
}

/**
 * A §16.6 call-history line in a DM, like WhatsApp's: centred, a voice or video icon (red when
 * missed), the label and the time. Tap -> call back with the same type; long-press -> the menu
 * ("Voice call back" / "Video call back" / "Delete for me").
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CallLineRow(
    text: String,
    missed: Boolean,
    onCallBack: (() -> Unit)?,
    onDeleteForMe: (() -> Unit)?,
    onVideoCallBack: (() -> Unit)? = null,
    video: Boolean = lk.codegen.risime.calls.CallLines.isVideo(text),
    time: String? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val tap = if (video) (onVideoCallBack ?: onCallBack) else onCallBack
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .padding(vertical = Spacing.xxs)
                .clip(MaterialTheme.shapes.large)
                .semantics { contentDescription = listOfNotNull(text, time).joinToString(", ") }
                .combinedClickable(onClick = { if (tap != null) tap() else menu = true }, onLongClick = { menu = true }),
        ) {
            Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (video) RisiIcons.Videocam else Icons.Default.Call, null, Modifier.size(16.dp), tint = tint)
                Spacer(Modifier.width(Spacing.xs))
                Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
                time?.let {
                    Spacer(Modifier.width(Spacing.sm))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            onVideoCallBack?.let { DropdownMenuItem(text = { Text("Video call back") }, onClick = { menu = false; it() }) }
            onCallBack?.let { DropdownMenuItem(text = { Text(if (onVideoCallBack != null) "Voice call back" else "Call back") }, onClick = { menu = false; it() }) }
            onDeleteForMe?.let { DropdownMenuItem(text = { Text("Delete for me") }, onClick = { menu = false; it() }) }
        }
    }
}

/** One row of a group call's participant list (§20.5 UI; names from the app's database, android A7). */
data class GroupMemberUi(
    val key: String,
    val name: String,
    val photoKey: String?,
    val speaking: Boolean,
    val muted: Boolean,
    /** "Not a member" (K7) or "Can't verify" (K6); null = a verified member. */
    val warning: String?,
)

fun groupMemberUi(m: GroupMember, name: String): GroupMemberUi = GroupMemberUi(
    key = m.identity,
    name = if (m.local) "You" else if (m.member) name else "Not a member",
    photoKey = m.userId.takeIf { m.member },
    speaking = m.speaking,
    muted = m.muted,
    warning = when {
        !m.member -> "Not a member · not played"
        m.cantVerify -> "Can't verify · not played"
        else -> null
    },
)

/** §20.5 the voice call's participant list: speaking ring, muted mic, the K6/K7 warnings. */
@Composable
fun GroupParticipantList(members: List<GroupMemberUi>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.lazy.LazyColumn(modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        items(members.size, key = { members[it].key }) { i ->
            val m = members[i]
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).semantics {
                    contentDescription = buildString {
                        append(m.name)
                        if (m.speaking) append(", speaking")
                        if (m.muted) append(", muted")
                        m.warning?.let { append(", ").append(it) }
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(if (m.speaking) Green else Color.Transparent)
                        .padding(3.dp),
                    contentAlignment = Alignment.Center,
                ) { InitialsAvatar(m.name, size = 42.dp, photoKey = m.photoKey) }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    m.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB4A9)) }
                }
                if (m.muted) Icon(RisiIcons.MicOff, "Muted", tint = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

/** §20.4 a group call's history line: centred; Join while the call runs; "Delete for me" in its menu. */
@Composable
fun GroupCallLineRow(text: String, video: Boolean, missed: Boolean, onJoin: (() -> Unit)?, onDeleteForMe: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            onClick = { menu = true },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = Spacing.xxs),
        ) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (video) "📹 " else "📞 ") + text,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onJoin?.let { TextButton(onClick = it) { Text("Join") } }
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            onJoin?.let { DropdownMenuItem(text = { Text("Join") }, onClick = { menu = false; it() }) }
            onDeleteForMe?.let { DropdownMenuItem(text = { Text("Delete for me") }, onClick = { menu = false; it() }) }
        }
    }
}

/** A small banner for the in-app "full-screen calls are off" card (§16.9 A3). */
@Composable
fun FullScreenIntentCard(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Text("Calls can't take over the screen", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text("Allow full-screen notifications so a call rings even when the phone is locked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(Modifier.background(Color.Transparent)) {
                TextButton(onClick = onOpenSettings) { Text("Open settings") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}
