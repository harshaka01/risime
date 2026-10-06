package lk.codegen.risime.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.update.UpdateInfo
import lk.codegen.risime.update.UpdateState
import lk.codegen.risime.update.updateBanner
import lk.codegen.risime.ui.theme.Spacing

private fun UpdateState.status(): String? = when (this) {
    is UpdateState.Working -> step
    is UpdateState.Failed -> message
    is UpdateState.NeedsPermission -> "Allow RisiMe to install updates, then tap Update again."
    else -> null
}

private fun UpdateState.info(): UpdateInfo? = when (this) {
    UpdateState.Idle -> null
    is UpdateState.Available -> info
    is UpdateState.Working -> info
    is UpdateState.NeedsPermission -> info
    is UpdateState.Failed -> info
}

/** Optional update: a banner under the dev banner with the release notes and one "Update" tap. */
@Composable
fun UpdateBar(state: UpdateState, c: AppContainer) {
    val banner = updateBanner(state) ?: return
    val info = state.info() ?: return
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(banner.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                banner.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
            }
            if (banner.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            banner.action?.let { TextButton(onClick = { scope.launch { c.updater.update(info) } }) { Text(it) } }
            if (banner.canDismiss) TextButton(onClick = c.updater::dismiss) { Text("Later") }
        }
        banner.notes?.let { notes ->
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide what's new" else "What's new") }
            if (expanded) {
                Text(
                    notes,
                    Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(bottom = Spacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** `required: true`: chats are unreachable until updated; the dev banner (above) and Sign out stay. */
@Composable
fun RequiredUpdateScreen(state: UpdateState, info: UpdateInfo, c: AppContainer) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    ) {
        Text("Update required", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text("RisiMe ${info.versionName} is needed to keep chatting.", textAlign = TextAlign.Center)
        info.notes?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        state.status()?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Button(
            onClick = { scope.launch { c.updater.update(info) } },
            enabled = state !is UpdateState.Working,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Update") }
        OutlinedButton(onClick = { scope.launch { c.logout() } }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}
