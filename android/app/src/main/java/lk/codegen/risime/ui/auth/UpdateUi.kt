package lk.codegen.risime.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.update.DOWNLOAD_FROM_WEBSITE
import lk.codegen.risime.update.DOWNLOAD_PAGE_URL
import lk.codegen.risime.update.NEVER_UNINSTALL_TEXT
import lk.codegen.risime.update.UpdateInfo
import lk.codegen.risime.update.UpdateState
import lk.codegen.risime.update.displayNotes
import lk.codegen.risime.update.infoOrNull
import lk.codegen.risime.update.updateBanner
import lk.codegen.risime.ui.theme.Sizes
import lk.codegen.risime.ui.theme.Spacing

const val UPDATE_GATE_NOTES_TAG = "update_gate_notes"
const val UPDATE_GATE_ACTIONS_TAG = "update_gate_actions"

/** The fallback on every update surface (P0-1): opens https://risicloud.ai/app/risime/. */
const val OPEN_DOWNLOAD_PAGE = DOWNLOAD_FROM_WEBSITE

private fun UpdateState.status(): String? = when (this) {
    is UpdateState.Working -> step
    is UpdateState.Failed -> message
    is UpdateState.NeedsPermission -> "Allow RisiMe to install updates, then tap Update again."
    else -> null
}

/**
 * Optional update: a banner above every screen with the release notes and one "Update" tap. It only
 * starts or observes the update; the download and install run in UpdateWorker (P0-1), so leaving
 * the app doesn't stop them.
 */
@Composable
fun UpdateBar(state: UpdateState, c: AppContainer) {
    val banner = updateBanner(state) ?: return
    val info = state.infoOrNull() ?: return
    val uri = LocalUriHandler.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val fg = MaterialTheme.colorScheme.onSecondaryContainer
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(banner.title, style = MaterialTheme.typography.titleSmall, color = fg)
                banner.status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (banner.error) MaterialTheme.colorScheme.error else fg)
                }
            }
            if (banner.busy && banner.percent == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            banner.action?.let { TextButton(onClick = { c.updater.start(info) }) { Text(it) } }
            if (banner.canDismiss) TextButton(onClick = c.updater::dismiss) { Text("Later") }
        }
        banner.percent?.let { p ->
            LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs))
        }
        Text(NEVER_UNINSTALL_TEXT, style = MaterialTheme.typography.bodySmall, color = fg)
        if (state is lk.codegen.risime.update.UpdateState.Failed) {
            // §22.7: the installer failed: say it plainly and offer a backup now.
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lk.codegen.risime.ui.backup.DONT_UNINSTALL_BACKUP_TEXT, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { c.backups.backupNow() } }) { Text(lk.codegen.risime.ui.backup.BACK_UP_NOW) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            banner.notes?.let { TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide what's new" else "What's new") } }
            TextButton(onClick = { runCatching { uri.openUri(DOWNLOAD_PAGE_URL) } }) { Text(OPEN_DOWNLOAD_PAGE) }
        }
        if (expanded) {
            banner.notes?.let { notes ->
                Text(
                    notes,
                    Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(bottom = Spacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                    color = fg,
                )
            }
        }
    }
}

/** The gate's colour roles: all from the theme, text on `background` (tested for contrast). */
data class UpdateGateColors(val background: Color, val title: Color, val body: Color, val notes: Color, val error: Color)

fun updateGateColors(scheme: ColorScheme) = UpdateGateColors(
    background = scheme.background,
    title = scheme.onBackground,
    body = scheme.onBackground,
    notes = scheme.onSurfaceVariant,
    error = scheme.error,
)

/** `required: true`: chats are unreachable until updated; the dev banner (above) and Sign out stay. */
@Composable
fun RequiredUpdateScreen(state: UpdateState, info: UpdateInfo, c: AppContainer) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    RequiredUpdateContent(
        state = state,
        info = info,
        onUpdate = { c.updater.start(info) },
        onOpenDownloadPage = { runCatching { uri.openUri(DOWNLOAD_PAGE_URL) } },
        onSignOut = { scope.launch { c.signOutKeepChats() } },
    )
}

/**
 * The gate itself (stateless, UI-tested). The notes scroll in the space left; the actions are a
 * bottom bar that is always on screen: Update/Retry, Download from website, Sign out. Never a dead end.
 */
@Composable
fun RequiredUpdateContent(
    state: UpdateState,
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onOpenDownloadPage: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = updateGateColors(MaterialTheme.colorScheme)
    val working = state is UpdateState.Working
    val failed = state is UpdateState.Failed
    Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.title) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().testTag(UPDATE_GATE_NOTES_TAG)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text("Update required", style = MaterialTheme.typography.titleLarge, color = colors.title,
                    modifier = Modifier.semantics { heading() })
                Text("RisiMe ${info.versionName} is needed to keep chatting.", style = MaterialTheme.typography.bodyLarge, color = colors.body)
                Text(NEVER_UNINSTALL_TEXT, style = MaterialTheme.typography.bodyMedium, color = colors.body)
                // The whole error (the bar below may shorten it on a small screen).
                if (state is UpdateState.Failed) Text(state.message, style = MaterialTheme.typography.bodyMedium, color = colors.error)
                info.displayNotes()?.let {
                    Text("What's new", style = MaterialTheme.typography.titleSmall, color = colors.title)
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.notes)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.fillMaxWidth().testTag(UPDATE_GATE_ACTIONS_TAG).navigationBarsPadding()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                state.status()?.let {
                    Text(
                        it,
                        color = if (failed) colors.error else colors.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                (state as? UpdateState.Working)?.percent?.let { p ->
                    LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Button(
                        onClick = onUpdate,
                        enabled = !working,
                        modifier = Modifier.weight(1f).heightIn(min = Sizes.minTouch),
                    ) { Text(if (failed) "Retry" else "Update") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = onOpenDownloadPage, modifier = Modifier.weight(1f)) {
                        Text(OPEN_DOWNLOAD_PAGE, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onSignOut) { Text(SIGN_OUT_KEEPS_CHATS, maxLines = 1) }
                }
            }
        }
    }
}
