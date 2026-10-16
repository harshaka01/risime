package ai.risicloud.risime.calls

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import ai.risicloud.risime.push.OemBrand
import ai.risicloud.risime.push.oemBrand
import ai.risicloud.risime.push.oemGuidance
import ai.risicloud.risime.ui.common.SectionHeader

/** What the Settings "Calls" row says is missing (§16.9), with the deep link that fixes it. */
data class CallsSetupItem(val text: String, val action: String?, val label: String?)

/** P0 calls (2026-10-16): opens the OEM's autostart / background screen (not a Settings action). */
const val ACTION_OEM_AUTOSTART = "risime.oem_autostart"

/**
 * The Settings → Calls items, most important first. P0 calls (2026-10-16): [callsMuted] = the Calls
 * channel is silent or low (Fix opens it); [pushDowngraded] = FCM recently delivered a call push at
 * normal priority; [brand] = the phone maker's own background rule, named once for this brand.
 */
fun callsSetupItems(
    unsupported: String?,
    fullScreen: Boolean,
    batteryOptimised: Boolean,
    sdk: Int = Build.VERSION.SDK_INT,
    callsMuted: Boolean = false,
    pushDowngraded: Boolean = false,
    brand: OemBrand = OemBrand.OTHER,
): List<CallsSetupItem> = buildList {
    if (unsupported != null) add(CallsSetupItem(unsupported, if (unsupported.startsWith("Turn on notifications")) Settings.ACTION_APP_NOTIFICATION_SETTINGS else null, "Notifications"))
    if (callsMuted && sdk >= 26) add(CallsSetupItem("Calls are silent on this phone: RisiMe's Calls notifications have no sound or are set low, so a call doesn't ring", Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, "Fix"))
    if (!fullScreen && sdk >= 34) add(CallsSetupItem("Let RisiMe show incoming calls: without full-screen notifications a call is only a small banner on a locked phone", "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", "Allow"))
    if (pushDowngraded) add(CallsSetupItem("Android delayed a call alert on this phone (sent urgent, delivered late). Set RisiMe's battery use to Unrestricted", Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, "Battery"))
    if (batteryOptimised) add(CallsSetupItem("Battery optimisation can delay or block incoming calls", Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, "Battery"))
    if (brand != OemBrand.OTHER) add(CallsSetupItem(oemGuidance(brand), ACTION_OEM_AUTOSTART, "Open"))
    else add(CallsSetupItem("On Xiaomi and some other phones, also allow \"Show on lock screen\" and \"Display pop-up windows while running in background\"", Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "App settings"))
}

/** The setup items that stop a call from ringing (everything but the always-present brand advice). */
fun callsBlocking(items: List<CallsSetupItem>): List<CallsSetupItem> =
    items.filter { it.action != ACTION_OEM_AUTOSTART && it.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS }

@Composable
fun CallsSettingsSection() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as? RisiMeApp ?: return
    val calls = app.container.calls
    SectionHeader("Calls")
    val pm = ctx.getSystemService(PowerManager::class.java)
    val items = callsSetupItems(
        runCatching { calls.unsupportedReason() }.getOrNull(),
        calls.notifications.canUseFullScreenIntent(),
        pm?.isIgnoringBatteryOptimizations(ctx.packageName) == false,
        callsMuted = calls.callsChannelMuted(),
        pushDowngraded = calls.pushDowngradedRecently(),
        brand = oemBrand(Build.MANUFACTURER, Build.BRAND),
    )
    if (callsBlocking(items).isEmpty()) Text("Calls are ready on this phone.", style = MaterialTheme.typography.bodyMedium)
    items.forEach { item ->
        Column {
            Text(item.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.action != null) {
                TextButton(onClick = { openCallsSetup(ctx, app, item.action) }) { Text(item.label ?: "Open") }
            }
        }
    }
    // P0 calls (2026-10-16): the phone rings in 5 s exactly like a real incoming call (local only).
    OutlinedButton(onClick = {
        TestRing.schedule(ctx)
        runCatching { Toast.makeText(ctx, "A test call will ring in 5 seconds. You can lock the phone.", Toast.LENGTH_LONG).show() }
    }) { Text("Test incoming call") }
    if (BuildConfig.DEBUG) {
        var tamper by remember { mutableStateOf(calls.debugTamperFingerprint) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Debug: tamper the answer's fingerprint (must fail)", style = MaterialTheme.typography.bodySmall)
            Switch(tamper, onCheckedChange = { tamper = it; calls.debugTamperFingerprint = it })
        }
    }
}

private fun openCallsSetup(ctx: android.content.Context, app: RisiMeApp, action: String) {
    if (action == ACTION_OEM_AUTOSTART) {
        runCatching { ai.risicloud.risime.push.NotificationHealthProbe(ctx, app.container).openAutostart() }
        return
    }
    runCatching {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        if (action == Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS) {
            i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CallNotifications.CH_CALLS)
        }
        if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS || action.contains("FULL_SCREEN")) i.data = Uri.parse("package:${ctx.packageName}")
        ctx.startActivity(i)
    }
}

/**
 * §16.9 (A3) and P0 calls (2026-10-16): one in-app card above the chats when something stops calls
 * from ringing: a silent Calls channel ("Fix" opens it) first, else a missing full-screen permission on
 * Android 14+ ("Allow", one tap). Shown after a ring that hit it, or once a day otherwise ("Not now").
 */
@Composable
fun FullScreenIntentPrompt() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as? RisiMeApp ?: return
    val calls = app.container.calls
    val denied by calls.fullScreenDenied.collectAsState()
    val muted = remember { calls.callsChannelMuted() }
    val due = remember { calls.callsPromptDue() }
    var hidden by remember { mutableStateOf(false) }
    if (hidden) return
    val dismiss = {
        hidden = true
        calls.fullScreenDenied.value = false
        calls.dismissCallsPrompt()
    }
    if (muted && (due || denied)) {
        CallsChannelMutedCard(onFix = { dismiss(); openCallsSetup(ctx, app, Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS) }, onDismiss = dismiss)
        return
    }
    if (Build.VERSION.SDK_INT < 34 || calls.notifications.canUseFullScreenIntent() || !(denied || due)) return
    FullScreenIntentCard(
        onOpenSettings = {
            dismiss()
            runCatching {
                ctx.startActivity(Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT").setData(Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        onDismiss = dismiss,
    )
}
