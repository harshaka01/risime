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
                // Decision 092: with Private transcription on, "Private chats are never transcribed" would be untrue.
                val privateOn = c.privateTranscribeFlag.on.collectAsStateWithLifecycle().value
                Text(if (privateOn) VoiceStrings.TRANSCRIBE_SETTING_SUB_PRIVATE else VoiceStrings.TRANSCRIBE_SETTING_SUB, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = transcribe, enabled = !saving, onCheckedChange = { save(it, actionItems) }, modifier = Modifier.testTag("settings_voice_transcribe"))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(VoiceStrings.ACTION_ITEMS_SETTING, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = actionItems, enabled = !saving, onCheckedChange = { save(transcribe, it) }, modifier = Modifier.testTag("settings_voice_action_items"))
        }
        // Proposal 2026-10-14-voice-translate (v1.50 §37.13.2): only while Translate is available to this user.
        val available by c.voice.translateAvailable.collectAsStateWithLifecycle()
        val always by c.voice.alwaysTranslate.collectAsStateWithLifecycle()
        if (available) {
            Text(ai.risicloud.risime.net.VoiceTranslate150.ALWAYS_SETTING, style = MaterialTheme.typography.bodyMedium)
            // Architect 14:05 item 3: Off, Sinhala / English / Tamil, the current pick if it's another language, and
            // "More languages…" (the same searchable picker as under messages).
            val supported by c.voice.translateLanguages.collectAsStateWithLifecycle()
            var more by remember { mutableStateOf(false) }
            val pick: (String) -> Unit = { v ->
                saving = true
                error = null
                scope.launch { if (!c.voice.setAlwaysTranslate(v)) error = "Couldn't save. Try again"; saving = false }
            }
            AlwaysTranslateRow(always, supported, !saving, onMore = { more = true }) { v ->
                if (v != ai.risicloud.risime.net.VoiceTranslate150.OFF) c.voice.recentTranslate.record(v)
                pick(v)
            }
            if (more) ai.risicloud.risime.ui.chat.LanguagePickerDialog(null, supported, c.voice.recentTranslate, onDismiss = { more = false }) { more = false; pick(it) }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** Architect 14:05 item 3: the always-translate chips: Off, the default buttons, and the current pick when it's another language. */
fun alwaysTranslateChips(current: String, supported: List<String>): List<String> {
    val off = ai.risicloud.risime.net.VoiceTranslate150.OFF
    val base = ai.risicloud.risime.voice.TranslateLanguages.DEFAULTS.filter { it in supported }
    return listOf(off) + base + listOfNotNull(current.takeIf { it != off && it !in base })
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun AlwaysTranslateRow(always: String, supported: List<String>, enabled: Boolean, onMore: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        for (v in alwaysTranslateChips(always, supported)) {
            androidx.compose.material3.FilterChip(
                selected = always == v, enabled = enabled,
                onClick = { onPick(v) },
                label = { Text(if (v == ai.risicloud.risime.net.VoiceTranslate150.OFF) "Off" else ai.risicloud.risime.voice.TranslateLanguages.name(v)) },
                modifier = Modifier.testTag("settings_always_translate_$v"),
            )
        }
        if (ai.risicloud.risime.voice.TranslateLanguages.hasMore(supported)) {
            androidx.compose.material3.TextButton(onClick = onMore, enabled = enabled, modifier = Modifier.testTag("settings_always_translate_more")) {
                Text(ai.risicloud.risime.voice.TranslateLanguages.MORE)
            }
        }
    }
}
