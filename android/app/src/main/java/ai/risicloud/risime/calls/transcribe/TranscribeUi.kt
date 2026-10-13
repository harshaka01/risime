package ai.risicloud.risime.calls.transcribe

import ai.risicloud.risime.net.CallTranscribeErrors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant

/** Decision 048: the honest line on every §36 surface. */
const val TRANSCRIBE_HONEST_LINE = "Audio is sent to Risi for transcription"

private val TranscribeRed = Color(0xFFD93025)

/** The short line for a notice ([name] resolves a user id to a display name). */
fun transcribeNoticeText(n: TranscribeNotice, name: (String) -> String): String = when (n.code) {
    TranscribeConsentMachine.NOTICE_DECLINED -> "${n.by?.let(name) ?: "Someone"} declined transcription"
    TranscribeConsentMachine.NOTICE_EXPIRED -> "No answer to the transcription request"
    TranscribeConsentMachine.NOTICE_STOPPED -> n.by?.let { "${name(it)} stopped transcription" } ?: "Transcription stopped"
    CallTranscribeErrors.PEER_NOT_READY -> "The other phone can't transcribe calls yet"
    CallTranscribeErrors.BUSY -> "Transcription is busy for this call"
    CallTranscribeErrors.RATE_LIMITED -> "Too many requests. Try again in a moment"
    CallTranscribeErrors.TRANSCRIBE_OFF, CallTranscribeErrors.TRANSCRIBE_UNAVAILABLE -> "Transcription isn't available right now"
    CallTranscribeErrors.NOT_OFFICIAL -> "Transcription is only for Official calls"
    "offline" -> "Not connected. Try again"
    else -> "Couldn't start transcription"
}

/** The prompt's text: "<name> wants Risi to transcribe this call." (+ the compare note). */
fun transcribePromptText(name: String, compare: Boolean): String =
    "$name wants Risi to transcribe this call." + if (compare) " (developer model comparison on)" else ""

/**
 * §36.6 the red "Risi transcribing" bar with [Stop] (two choices: "Stop", "Stop and delete transcript").
 * Shown from the `on` signal until a final state; it fails toward showing.
 */
@Composable
fun TranscribeBar(view: TranscribeView, onStop: (discard: Boolean) -> Unit, modifier: Modifier = Modifier, name: (String) -> String = { "Someone" }) {
    if (!view.bar) return
    var menu by remember { mutableStateOf(false) }
    Surface(color = TranscribeRed, contentColor = Color.White, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("transcribe_bar")) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color.White, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text("Risi transcribing" + if (view.compare) " · comparing models" else "", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                // v1.45 §40.9: a joiner hasn't allowed yet: nothing is recorded or sent meanwhile.
                if (view.pausedFor.isNotEmpty()) {
                    Text(pausedLine(view.pausedFor.map(name)), style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.testTag("transcribe_paused"))
                }
                Text(TRANSCRIBE_HONEST_LINE, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
            }
            Box {
                TextButton(onClick = { menu = true }, modifier = Modifier.testTag("transcribe_stop")) { Text("Stop", color = Color.White, fontWeight = FontWeight.Bold) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Stop") }, onClick = { menu = false; onStop(false) }, modifier = Modifier.testTag("transcribe_stop_keep"))
                    DropdownMenuItem(text = { Text("Stop and delete transcript") }, onClick = { menu = false; onStop(true) }, modifier = Modifier.testTag("transcribe_stop_discard"))
                }
            }
        }
    }
}

/** v1.45 §40.9 "Paused until Sunil allows" / "Paused until Sunil and Ruwan allow". */
fun pausedLine(names: List<String>): String =
    "Paused until " + ai.risicloud.risime.calls.CallMover.joinNames(names) + if (names.size == 1) " allows" else " allow"

/** [Transcribe] (Official calls only), "Asking <name>…" with Cancel, and the last notice. */
@Composable
fun TranscribeControls(
    view: TranscribeView,
    peerName: String,
    name: (String) -> String,
    onTranscribe: () -> Unit,
    onCancel: () -> Unit,
    onNoticeShown: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (view.button) {
            OutlinedButton(onClick = onTranscribe, modifier = Modifier.testTag("transcribe_button")) {
                Text("Transcribe", color = Color.White)
            }
        }
        if (view.asking) {
            Surface(color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Asking $peerName…", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("transcribe_asking"))
                    TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
                }
            }
        }
        view.notice?.let { n ->
            Surface(color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White, shape = RoundedCornerShape(12.dp)) {
                Text(transcribeNoticeText(n, name), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("transcribe_notice"))
            }
            LaunchedEffect(n) {
                delay(5_000)
                onNoticeShown()
            }
        }
    }
}

/** §36.6 the peer's prompt over the call screen; it closes when `expires_at` passes. */
@Composable
fun TranscribePrompt(view: TranscribeView, name: (String) -> String, serverNow: () -> Long, onAllow: () -> Unit, onDecline: () -> Unit) {
    val p = view.prompt ?: return
    val expiresAt = remember(p.expiresAt) { p.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } }
    var now by remember { mutableLongStateOf(serverNow()) }
    LaunchedEffect(p.sessionId) {
        while (true) {
            delay(500)
            now = serverNow()
        }
    }
    if (expiresAt != null && now >= expiresAt) return
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.testTag("transcribe_prompt"),
        title = { Text("Transcribe this call?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(transcribePromptText(name(p.requestedBy), p.compare))
                Text("$TRANSCRIBE_HONEST_LINE.", style = MaterialTheme.typography.bodySmall)
                expiresAt?.let { Text("${((it - now) / 1000).coerceAtLeast(0)} s", style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = { Button(onClick = onAllow, modifier = Modifier.testTag("transcribe_allow")) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDecline, modifier = Modifier.testTag("transcribe_decline")) { Text("Decline") } },
    )
}
