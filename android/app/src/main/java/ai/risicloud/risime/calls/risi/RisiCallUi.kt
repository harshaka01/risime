package ai.risicloud.risime.calls.risi

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ai.risicloud.risime.ui.tabs.RisiAvatar

/** §41.4 Risi's avatar with the agent badge, over the name on the call screen. */
@Composable
fun RisiCallBadge() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp).testTag("risi_call_badge")) {
        RisiAvatar(28.dp)
        Spacer(Modifier.width(6.dp))
        Surface(color = Color.White.copy(alpha = 0.18f), contentColor = Color.White, shape = RoundedCornerShape(8.dp)) {
            Text("Agent", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** §41.4 the persistent banner: Risi hears this call, and it isn't end-to-end encrypted (never a lock). */
@Composable
fun RisiHearsBanner() {
    Surface(
        color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("risi_hears_banner"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(CallRisiTexts.BANNER, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(CallRisiTexts.NOT_E2EE, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
        }
    }
}

/** §41.4 the one-time explainer before the first Call Risi on this phone. */
@Composable
fun RisiCallExplainer(onOk: () -> Unit, onCancel: () -> Unit) {
    // v1.52 §41.8: the meeting-titles setting is also a line of the first-call sheet.
    val flag = (LocalContext.current.applicationContext as? ai.risicloud.risime.RisiMeApp)?.container?.callRisiFlag
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(CallRisiTexts.EXPLAIN_TITLE) },
        text = {
            Column {
                Text(CallRisiTexts.EXPLAIN_TEXT)
                if (flag != null) {
                    Spacer(Modifier.height(12.dp))
                    CallRisiTitlesRow(flag)
                }
            }
        },
        confirmButton = { TextButton(onClick = onOk, modifier = Modifier.testTag("risi_call_explain_ok")) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** v1.52 §41.8 "Risi can say my meeting titles on calls" (default on), as a row with a switch. */
@Composable
fun CallRisiTitlesRow(flag: CallRisiFlag, modifier: Modifier = Modifier) {
    val on by flag.calTitles.collectAsStateWithLifecycle()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(CallRisiTexts.CAL_TITLES_SETTING, style = MaterialTheme.typography.bodyMedium)
            Text(CallRisiTexts.CAL_TITLES_SUB, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { flag.setCalTitles(it) }, modifier = Modifier.testTag("settings_call_risi_titles"))
    }
}

/** v1.52 §41.8 Settings → Risi: the meeting-titles setting, only while Call Risi is on for this phone. */
@Composable
fun CallRisiTitlesSetting(flag: CallRisiFlag) {
    val callRisi by flag.on.collectAsStateWithLifecycle()
    if (callRisi) CallRisiTitlesRow(flag, Modifier.padding(vertical = 4.dp))
}

/** RECORD_AUDIO first (asked on the first call), then [onCall]; a refusal → [onBlocked]. */
@Composable
private fun rememberMicThen(onBlocked: (String) -> Unit, onCall: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onCall() else onBlocked("RisiMe needs the microphone for calls")
    }
    return {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) onCall()
        else mic.launch(Manifest.permission.RECORD_AUDIO)
    }
}

/** §41.1 the Risi chat's top-bar Call Risi button: asks RECORD_AUDIO on the first call. */
@Composable
fun CallRisiButton(onBlocked: (String) -> Unit, onCall: () -> Unit) {
    val call = rememberMicThen(onBlocked, onCall)
    IconButton(
        onClick = call,
        modifier = Modifier.testTag("call_risi_button").semantics { contentDescription = CallRisiTexts.BUTTON },
    ) { Icon(Icons.Default.Call, null) }
}

/**
 * v1.48 §41.1/§41.3 (Harsha 2026-10-14): the Risi contact sheet, opened from Risi's avatar on the chat list's
 * Risi row while Call Risi is available (the same rules as the Risi chat's button): Risi with the agent badge,
 * [Message] and [Call Risi]. The list offers it only while [CallRisiRules.contactSheet] holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisiContactSheet(onMessage: () -> Unit, onCall: () -> Unit, onBlocked: (String) -> Unit, onDismiss: () -> Unit) {
    val call = rememberMicThen(onBlocked) { onDismiss(); onCall() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("risi_contact_sheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RisiAvatar(48.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(CallRisiTexts.TITLE, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(CallRisiTexts.CONTACT_SUBTITLE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onDismiss(); onMessage() }, modifier = Modifier.weight(1f).testTag("risi_contact_message")) {
                    Text(CallRisiTexts.MESSAGE, maxLines = 1)
                }
                Button(
                    onClick = call,
                    modifier = Modifier.weight(1f).testTag("risi_contact_call").semantics { contentDescription = CallRisiTexts.BUTTON },
                ) {
                    Icon(Icons.Default.Call, null)
                    Spacer(Modifier.width(6.dp))
                    Text(CallRisiTexts.BUTTON, maxLines = 1)
                }
            }
        }
    }
}

/** §41.5 the `call_transcript` card in the Risi chat (filler lines dimmed). */
@Composable
fun CallTranscriptCard(t: CallTranscript, modifier: Modifier = Modifier) {
    Column(modifier.testTag("call_transcript")) {
        Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        t.lines.forEach { l ->
            val dim = l.filler
            Text(
                (if (l.risi) "Risi: " else "You: ") + l.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
            // v1.52 §41.8: the titles were spoken from the phone and are not in the stored text.
            if (l.titlesSpoken) {
                Text(
                    CallRisiTexts.TITLES_SPOKEN,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("call_transcript_titles_spoken"),
                )
            }
        }
        if (t.truncated) Text("Earlier lines aren't shown.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
