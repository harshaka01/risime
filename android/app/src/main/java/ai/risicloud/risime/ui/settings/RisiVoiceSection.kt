package ai.risicloud.risime.ui.settings

import ai.risicloud.risime.AppContainer
import ai.risicloud.risime.ui.chat.VoiceStrings
import ai.risicloud.risime.ui.theme.Spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * v1.41 §37.7 Settings → Risi: "Transcribe my voice notes in Official chats" and "Find action items in my calls and
 * voice notes" (`/me/risi/voice`). Off: Risi never transcribes this user's Official notes and the phone stamps
 * `transcribe: false` on each new one. Private notes are never transcribed, whatever this says.
 */
@Composable
fun RisiVoiceSection(c: AppContainer) {
    val transcribe by c.voice.transcribeMine.collectAsStateWithLifecycle()
    val actionItems by c.voice.actionItems.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { c.voice.refreshSettings() }
    fun save(t: Boolean, a: Boolean) {
        saving = true
        error = null
        scope.launch {
            if (!c.voice.setSettings(t, a)) error = "Couldn't save. Try again"
            saving = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(vertical = Spacing.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(VoiceStrings.TRANSCRIBE_SETTING, style = MaterialTheme.typography.bodyMedium)
                Text(VoiceStrings.TRANSCRIBE_SETTING_SUB, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = transcribe, enabled = !saving, onCheckedChange = { save(it, actionItems) }, modifier = Modifier.testTag("settings_voice_transcribe"))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(VoiceStrings.ACTION_ITEMS_SETTING, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = actionItems, enabled = !saving, onCheckedChange = { save(transcribe, it) }, modifier = Modifier.testTag("settings_voice_action_items"))
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
