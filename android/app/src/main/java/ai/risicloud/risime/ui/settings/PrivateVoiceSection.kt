package ai.risicloud.risime.ui.settings

import ai.risicloud.risime.AppContainer
import ai.risicloud.risime.ui.chat.PRIVATE_CONSENT_ROWS
import ai.risicloud.risime.ui.common.SectionHeader
import ai.risicloud.risime.ui.theme.Spacing
import ai.risicloud.risime.voice.PrivateVoiceStrings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Decision 092: Settings → "Private voice & translate", only while the server's RISI_PRIVATE_TRANSCRIBE applies to
 * this account: the reader's two "Don't ask again" choices, each resettable ("Ask again"). The sender's own switch
 * is [PrivateSenderSetting], in Settings → Privacy (Harsha 10:31).
 */
@Composable
fun PrivateVoiceSection(c: AppContainer) {
    LaunchedEffect(Unit) { c.refreshPrivateTranscribe() }
    val on by c.privateTranscribeFlag.on.collectAsStateWithLifecycle()
    if (!on) return
    val remembered by c.privateConsent.remembered.collectAsStateWithLifecycle()
    SectionHeader(PrivateVoiceStrings.SETTINGS_TITLE)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(vertical = Spacing.xs).testTag("settings_private_voice")) {
        for ((kind, label) in PRIVATE_CONSENT_ROWS) {
            val dontAsk = kind in remembered
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(if (dontAsk) PrivateVoiceStrings.SETTING_NO_ASK else PrivateVoiceStrings.SETTING_ASKS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (dontAsk) TextButton(onClick = { c.privateConsent.reset(kind) }, modifier = Modifier.testTag("settings_private_reset_${kind.name.lowercase()}")) { Text(PrivateVoiceStrings.SETTING_RESET) }
            }
        }
    }
    HorizontalDivider()
}

/**
 * Decision 092 (Harsha 10:31): Settings → Privacy → "Let others transcribe my voice notes (Sinhala/Tamil go through
 * Google)", default on. Off stamps `allow_transcribe: false` on new Private notes. The one-time notice taps here.
 */
@Composable
fun PrivateSenderSetting(c: AppContainer) {
    val letOthers by c.privateConsent.letOthersTranscribe.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().testTag("settings_private_sender"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(PrivateVoiceStrings.SETTING_LET_OTHERS, style = MaterialTheme.typography.bodyMedium)
            Text(PrivateVoiceStrings.SETTING_LET_OTHERS_SUB, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = letOthers, onCheckedChange = { c.privateConsent.setLetOthers(it) }, modifier = Modifier.testTag("settings_private_let_others"))
    }
}
