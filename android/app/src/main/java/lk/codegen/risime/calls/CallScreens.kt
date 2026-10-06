package lk.codegen.risime.calls

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
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
import lk.codegen.risime.ui.theme.Sizes
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
)

private val Green = Color(0xFF1B8A3A)
private val Red = Color(0xFFC62828)

/**
 * The incoming call. [name] null = the locked ring of decision 051: "Incoming RisiMe call", no
 * caller, Answer asks for the fingerprint first.
 */
@Composable
fun IncomingCallScreen(name: String?, onAnswer: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Spacing.xxl))
            if (name != null) InitialsAvatar(name, size = 96.dp)
            Spacer(Modifier.height(Spacing.lg))
            Text(
                name ?: "Incoming RisiMe call",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                if (name == null) "Unlock RisiMe to see who's calling" else "RisiMe voice call",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f).height(Spacing.xxl))
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xl), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundAction("Decline", Icons.Default.Close, Red, onDecline)
                RoundAction("Answer", Icons.Default.Call, Green, onAnswer)
            }
        }
    }
}

@Composable
private fun RoundAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp).semantics { contentDescription = label },
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
        ) { Icon(icon, null, Modifier.size(32.dp)) }
        Spacer(Modifier.height(Spacing.xs))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Outgoing, connecting and connected: mute, the audio route picker, end, the timer. */
@Composable
fun InCallScreen(ui: InCallUi, onMute: (Boolean) -> Unit, onEndpoint: (EndpointUi) -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Spacing.xxl))
            InitialsAvatar(ui.name, size = 96.dp)
            Spacer(Modifier.height(Spacing.lg))
            Text(ui.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Spacing.xs))
            Text(ui.status, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (ui.verified) {
                Row(Modifier.padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(Spacing.xs))
                    Text("End-to-end encrypted", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.weight(1f).height(Spacing.xxl))
            if (!ui.ended) {
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledTonalIconToggleButton(
                            checked = ui.muted, onCheckedChange = onMute,
                            modifier = Modifier.size(Sizes.minTouch + 8.dp).semantics { contentDescription = if (ui.muted) "Unmute" else "Mute" },
                        ) { Text(if (ui.muted) "🔇" else "🎤") }
                        Text(if (ui.muted) "Muted" else "Mute", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    EndpointPicker(ui, onEndpoint)
                }
                RoundAction("End call", Icons.Default.Close, Red, onEnd)
            }
        }
    }
}

@Composable
private fun EndpointPicker(ui: InCallUi, onEndpoint: (EndpointUi) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = { open = true }, enabled = ui.endpoints.size > 1, modifier = Modifier.semantics { contentDescription = "Audio output" }) {
                Text(
                    when (ui.current?.kind) {
                        EndpointUi.Kind.SPEAKER -> "🔊"
                        EndpointUi.Kind.BLUETOOTH -> "🎧"
                        EndpointUi.Kind.WIRED -> "🎧"
                        else -> "📱"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(ui.current?.name ?: "Phone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            ui.endpoints.forEach { e ->
                DropdownMenuItem(text = { Text(e.name + if (e.id == ui.current?.id) "  ✓" else "") }, onClick = { open = false; onEndpoint(e) })
            }
        }
    }
}

/** A §16.6 call-history line in a DM: centred, muted; tap → "Call back", long-press menu "Delete for me". */
@Composable
fun CallLineRow(text: String, missed: Boolean, onCallBack: (() -> Unit)?, onDeleteForMe: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            onClick = { menu = true },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = Spacing.xxs),
        ) {
            Text(
                "📞 $text",
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                style = MaterialTheme.typography.labelLarge,
                color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            onCallBack?.let { DropdownMenuItem(text = { Text("Call back") }, onClick = { menu = false; it() }) }
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
