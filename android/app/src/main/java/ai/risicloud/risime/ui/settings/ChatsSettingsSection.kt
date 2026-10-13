package ai.risicloud.risime.ui.settings

import ai.risicloud.risime.AppContainer
import ai.risicloud.risime.data.linkpreview.LinkPreviews
import ai.risicloud.risime.ui.common.SectionHeader
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** v1.44 §39.0 Settings → Chats: "Link previews" (default on; device-local). Off: no fetch and no preview sent. */
@Composable
fun ChatsSettingsSection(c: AppContainer) {
    val on by c.linkPreviews.on.collectAsStateWithLifecycle()
    SectionHeader("Chats")
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = c.linkPreviews::set).testTag("settings_link_previews"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(LinkPreviews.SETTING, style = MaterialTheme.typography.bodyMedium)
            Text(LinkPreviews.SETTING_SUB, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}
