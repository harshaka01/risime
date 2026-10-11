package lk.codegen.risime.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lk.codegen.risime.update.DOWNLOAD_PAGE_URL

/**
 * Decision 079: the last lk.codegen.risime build. The app moved to ai.risicloud.risime; this banner sends the
 * user to the download page that offers the new app. Always on in this build (no flag). It only opens the
 * browser: nothing is deleted and nobody is signed out (rule 9) — this app keeps working.
 *
 * The app ships English UI only (no values-si / values-ta resources and no in-app language switch), so the
 * strings are English like every other UI string.
 */
object MovedNotice {
    const val TITLE = "RisiMe has moved — tap to install the new RisiMe"
    const val DETAIL = "This app keeps working. Install the new RisiMe from risicloud.ai."
    const val DISMISS = "Hide for now"
    const val URL = DOWNLOAD_PAGE_URL
    const val BANNER_TAG = "moved_banner"
    const val DISMISS_TAG = "moved_banner_dismiss"
    const val SETTINGS_TAG = "moved_settings_row"

    /** Dismissed for this process only: the banner comes back on the next app start. */
    var dismissedThisSession by mutableStateOf(false)

    fun open(context: Context) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Top of the chat list: dismissible for this session, back on every app start. */
@Composable
fun MovedBanner(modifier: Modifier = Modifier) {
    if (MovedNotice.dismissedThisSession) return
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth().testTag(MovedNotice.BANNER_TAG).clickable { MovedNotice.open(context) },
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(MovedNotice.TITLE, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(MovedNotice.DETAIL, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { MovedNotice.dismissedThisSession = true }, modifier = Modifier.testTag(MovedNotice.DISMISS_TAG)) {
                Icon(Icons.Default.Close, MovedNotice.DISMISS)
            }
        }
    }
}

/** The Settings row: always shown (not dismissible). */
@Composable
fun MovedSettingsRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().testTag(MovedNotice.SETTINGS_TAG).clickable { MovedNotice.open(context) },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(MovedNotice.TITLE, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(MovedNotice.DETAIL, style = MaterialTheme.typography.bodySmall)
        }
    }
}
