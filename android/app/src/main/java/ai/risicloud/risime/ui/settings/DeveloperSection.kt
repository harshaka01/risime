package ai.risicloud.risime.ui.settings

import ai.risicloud.risime.AppContainer
import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.net.CallTranscribeErrors
import ai.risicloud.risime.net.RisiDevSettings
import ai.risicloud.risime.ui.common.SectionHeader
import ai.risicloud.risime.ui.theme.Spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlinx.coroutines.launch

/** Settings → About: seven taps on the version open the Developer section (like Android's own). */
class DevTapCounter(private val needed: Int = 7, private val windowMs: Long = 3_000) {
    private var count = 0
    private var first = 0L

    /** One tap at [nowMs]; true once the [needed]-th tap lands within [windowMs] of the first. */
    fun tap(nowMs: Long): Boolean {
        if (count == 0 || nowMs - first > windowMs) {
            count = 0
            first = nowMs
        }
        count++
        if (count >= needed) {
            count = 0
            return true
        }
        return false
    }
}

const val DEV_COMPARE_LABEL = "Compare speech models on call transcripts"

/**
 * v1.39 §36.1 the Developer section: only for users with `risi_dev: true` (from a fresh `GET /me`). The one
 * setting is `transcribe_compare` (`GET`/`PUT /api/v1/me/risi/dev`); the server decides on each request.
 */
@Composable
fun RisiDevSection(c: AppContainer) {
    var dev by remember { mutableStateOf<Boolean?>(null) }
    var compare by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val me = c.api.me()
        dev = (me as? ApiResult.Ok)?.value?.user?.risiDev == true
        // Part 12 quick fix 2: the `phone_stt` flag follows a fresh `risi_dev` (a non-developer account turns it off).
        if (me is ApiResult.Ok) c.phoneSttFlag.setDeveloper(dev == true)
        if (dev == true) {
            when (val r = c.api.risiDev()) {
                is ApiResult.Ok -> compare = r.value.transcribeCompare
                is ApiResult.Error -> if (r.code == CallTranscribeErrors.NOT_DEVELOPER) { dev = false; c.phoneSttFlag.setDeveloper(false) } else error = "Couldn't load the developer settings"
                is ApiResult.NetworkError -> error = "Not connected"
            }
        }
    }
    SectionHeader("Developer")
    when (dev) {
        null -> Text("Checking…", style = MaterialTheme.typography.bodySmall)
        false -> Text("Developer options aren't available for this account.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("dev_unavailable"))
        true -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(DEV_COMPARE_LABEL, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Every speech model runs on each chunk of an Official call transcript, and Risi posts the results side by side. The other person is told before they allow.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = compare == true,
                    enabled = compare != null,
                    onCheckedChange = { on ->
                        val before = compare
                        compare = on
                        scope.launch {
                            when (val r = c.api.putRisiDev(RisiDevSettings(on))) {
                                is ApiResult.Ok -> compare = r.value.transcribeCompare
                                else -> {
                                    compare = before
                                    error = "Couldn't save. Try again"
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("dev_transcribe_compare"),
                )
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            PhoneRecogniserSection(c)
        }
    }
}
