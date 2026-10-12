package ai.risicloud.risime.calls

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import ai.risicloud.risime.BuildConfig
import ai.risicloud.risime.RisiMeApp
import ai.risicloud.risime.ui.common.SectionHeader

/** What the Settings "Calls" row says is missing (§16.9), with the deep link that fixes it. */
data class CallsSetupItem(val text: String, val action: String?, val label: String?)

fun callsSetupItems(unsupported: String?, fullScreen: Boolean, batteryOptimised: Boolean, sdk: Int = Build.VERSION.SDK_INT): List<CallsSetupItem> = buildList {
    if (unsupported != null) add(CallsSetupItem(unsupported, if (unsupported.startsWith("Turn on notifications")) Settings.ACTION_APP_NOTIFICATION_SETTINGS else null, "Notifications"))
    if (!fullScreen && sdk >= 34) add(CallsSetupItem("Calls can't take over the screen when the phone is locked", "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", "Allow"))
    if (batteryOptimised) add(CallsSetupItem("Battery optimisation can delay or block incoming calls", Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, "Battery"))
    add(CallsSetupItem("On Xiaomi and some other phones, also allow \"Show on lock screen\" and \"Display pop-up windows while running in background\"", Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "App settings"))
}

@Composable
fun CallsSettingsSection() {
    val ctx = LocalContext.current
    val calls = (ctx.applicationContext as? RisiMeApp)?.container?.calls ?: return
    SectionHeader("Calls")
    val pm = ctx.getSystemService(PowerManager::class.java)
    val items = callsSetupItems(
        runCatching { calls.unsupportedReason() }.getOrNull(),
        calls.notifications.canUseFullScreenIntent(),
        pm?.isIgnoringBatteryOptimizations(ctx.packageName) == false,
    )
    if (items.size == 1) Text("Calls are ready on this phone.", style = MaterialTheme.typography.bodyMedium)
    items.forEach { item ->
        Column {
            Text(item.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.action != null) {
                TextButton(onClick = {
                    runCatching {
                        val i = Intent(item.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (item.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        if (item.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS || item.action.contains("FULL_SCREEN")) i.data = Uri.parse("package:${ctx.packageName}")
                        ctx.startActivity(i)
                    }
                }) { Text(item.label ?: "Open") }
            }
        }
    }
    if (BuildConfig.DEBUG) {
        var tamper by remember { mutableStateOf(calls.debugTamperFingerprint) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Debug: tamper the answer's fingerprint (must fail)", style = MaterialTheme.typography.bodySmall)
            Switch(tamper, onCheckedChange = { tamper = it; calls.debugTamperFingerprint = it })
        }
    }
}

/** §16.9 (A3): after a ring without full-screen permission, one in-app card that opens the setting. */
@Composable
fun FullScreenIntentPrompt() {
    val ctx = LocalContext.current
    val calls = (ctx.applicationContext as? RisiMeApp)?.container?.calls ?: return
    val denied by calls.fullScreenDenied.collectAsState()
    if (!denied || Build.VERSION.SDK_INT < 34) return
    FullScreenIntentCard(
        onOpenSettings = {
            calls.fullScreenDenied.value = false
            runCatching {
                ctx.startActivity(Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT").setData(Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        onDismiss = { calls.fullScreenDenied.value = false },
    )
}
