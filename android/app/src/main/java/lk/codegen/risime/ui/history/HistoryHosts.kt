package lk.codegen.risime.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.BuildConfig
import lk.codegen.risime.RisiMeApp
import lk.codegen.risime.data.history.HistoryApproval
import lk.codegen.risime.ui.common.SectionHeader

/** §17.8: the first request waiting for this user's answer, anywhere in the app (behind the feature flag). */
@Composable
fun HistoryPromptHost(c: AppContainer) {
    if (!BuildConfig.HISTORY_SHARE_ENABLED) return
    val prompts by c.history.prompts.collectAsState(emptyList())
    val p = prompts.firstOrNull() ?: return
    var name by remember(p.requestId) { mutableStateOf("Someone") }
    LaunchedEffect(p.requestId) { name = c.historyName(p.requesterUser) }
    HistoryPromptDialog(p, name) { allow -> c.scope.launch { c.history.answer(p.requestId, allow) } }
}

/** Settings → Privacy (§17.8): the two switches and "Phones allowed to get your history". */
@Composable
fun HistoryPrivacyContent(
    members: Boolean,
    own: Boolean,
    approvals: List<HistoryApproval>,
    onMembers: (Boolean) -> Unit,
    onOwn: (Boolean) -> Unit,
    onRemove: (String) -> Unit,
    showHeader: Boolean = true,
) {
    if (showHeader) SectionHeader("Privacy")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Share chat history with other members' new devices", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = members, onCheckedChange = onMembers)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Share chat history with my new phones", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = own, onCheckedChange = onOwn)
    }
    if (approvals.isNotEmpty()) {
        Text("Phones allowed to get your history", style = MaterialTheme.typography.titleSmall)
        approvals.forEach { a ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Phone ${a.deviceId.take(8)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Allowed ${java.text.DateFormat.getDateInstance().format(java.util.Date(a.at))}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onRemove(a.deviceId) }) { Text("Remove", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
fun HistoryPrivacySection(showHeader: Boolean = true) {
    if (!BuildConfig.HISTORY_SHARE_ENABLED) return
    val c = (LocalContext.current.applicationContext as? RisiMeApp)?.container ?: return
    val members by c.sessionStore.historyMembers.collectAsState(true)
    val own by c.sessionStore.historyOwn.collectAsState(true)
    val approvals by c.history.approvals.flow.collectAsState()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.history.approvals.refresh() } }
    HistoryPrivacyContent(
        members, own, approvals,
        onMembers = { v -> c.scope.launch { c.sessionStore.setHistoryMembers(v) } },
        onOwn = { v -> c.scope.launch { c.sessionStore.setHistoryOwn(v) } },
        onRemove = { id -> c.scope.launch(kotlinx.coroutines.Dispatchers.IO) { c.history.approvals.remove(id) } },
        showHeader = showHeader,
    )
}
