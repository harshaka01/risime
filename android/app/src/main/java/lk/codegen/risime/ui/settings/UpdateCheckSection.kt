package lk.codegen.risime.ui.settings

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.launch
import lk.codegen.risime.BuildConfig
import lk.codegen.risime.RisiMeApp
import lk.codegen.risime.update.CheckStatus
import lk.codegen.risime.update.DOWNLOAD_FROM_WEBSITE
import lk.codegen.risime.update.DOWNLOAD_PAGE_URL
import lk.codegen.risime.update.NEVER_UNINSTALL_TEXT
import lk.codegen.risime.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

const val CHECK_FOR_UPDATES = "Check for updates"

/** "Last checked: 8 Oct 2026, 14:05 — Up to date" (or "Not checked yet"). */
fun lastCheckText(c: CheckStatus?, format: (Long) -> String): String =
    if (c == null) "Not checked yet" else "Last checked: ${format(c.atWallMs)} — ${c.result}"

/** Settings → About (P0-1): the last check and its result, "Check for updates", the website fallback. */
@Composable
internal fun UpdateCheckSection() {
    val updater = (LocalContext.current.applicationContext as? RisiMeApp)?.container?.updater ?: return
    val last by updater.lastCheck.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var checking by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<CheckStatus?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            lastCheckText(shown ?: last) { fmt.format(Date(it)) },
            style = MaterialTheme.typography.bodySmall,
        )
        if (BuildConfig.UPDATE_BASE_URL_OVERRIDDEN) {
            Text("Update server (test build): ${BuildConfig.UPDATE_BASE_URL}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(
            onClick = {
                checking = true
                scope.launch {
                    shown = updater.maybeCheck(SystemClock.elapsedRealtime(), force = true)
                    checking = false
                }
            },
            enabled = !checking,
        ) { Text(if (checking) "Checking…" else CHECK_FOR_UPDATES) }
        Text(NEVER_UNINSTALL_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { runCatching { uri.openUri(DOWNLOAD_PAGE_URL) } }) { Text(DOWNLOAD_FROM_WEBSITE) }
    }
}
