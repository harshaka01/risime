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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        // Decision 090: so does `call_risi` (and the device re-advertises without it).
        if (me is ApiResult.Ok) {
            val before = c.callRisiFlag.on.value
            c.callRisiFlag.setDeveloper(dev == true)
            if (before != c.callRisiFlag.on.value) c.refreshCapabilities()
            // Proposal 2026-10-14-send-for-me: `send_for_me` (on by default for a developer account) follows it too.
            val sendBefore = c.sendForMeFlag.on.value
            c.sendForMeFlag.setDeveloper(dev == true)
            if (sendBefore != c.sendForMeFlag.on.value) c.refreshCapabilities()
        }
        if (dev == true) {
            when (val r = c.api.risiDev()) {
                is ApiResult.Ok -> compare = r.value.transcribeCompare
                is ApiResult.Error -> if (r.code == CallTranscribeErrors.NOT_DEVELOPER) {
                    dev = false
                    c.phoneSttFlag.setDeveloper(false)
                    if (c.callRisiFlag.on.value) { c.callRisiFlag.setDeveloper(false); c.refreshCapabilities() }
                } else error = "Couldn't load the developer settings"
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
            PhoneTtsSection()
            CallRisiDevRow(c)
            SendForMeDevRow(c)
        }
    }
}

const val SEND_FOR_ME_FLAG_LABEL = "Send a message for me (one-tap card)"
const val SEND_FOR_ME_FLAG_HELP = "Risi drafts the message and shows one card with Send and Edit. Your phone sends it, encrypted. On by default for developer accounts."

/** Proposal 2026-10-14-send-for-me: the developer flag `send_for_me` (toggling it re-advertises `risi_send`). */
@Composable
fun SendForMeDevRow(c: AppContainer) {
    val on by c.sendForMeFlag.on.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().testTag("dev_send_for_me"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(SEND_FOR_ME_FLAG_LABEL, style = MaterialTheme.typography.bodyMedium)
            Text(SEND_FOR_ME_FLAG_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { c.sendForMeFlag.set(it); c.refreshCapabilities() }, modifier = Modifier.testTag("dev_send_for_me_switch"))
    }
}

const val CALL_RISI_FLAG_LABEL = "Call Risi (voice call with Risi)"
const val CALL_RISI_FLAG_HELP = "Shows Call Risi in your Risi chat when the server allows it for this account. Risi hears the call; it isn't end-to-end encrypted."

/** Decision 090: the developer flag `call_risi` (off by default; toggling it re-advertises the device's capabilities). */
@Composable
fun CallRisiDevRow(c: AppContainer) {
    val on by c.callRisiFlag.on.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().testTag("dev_call_risi"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(CALL_RISI_FLAG_LABEL, style = MaterialTheme.typography.bodyMedium)
            Text(CALL_RISI_FLAG_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { c.callRisiFlag.set(it); c.refreshCapabilities() }, modifier = Modifier.testTag("dev_call_risi_switch"))
    }
}
