package lk.codegen.risime.calls

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import lk.codegen.risime.BuildConfig
import lk.codegen.risime.RisiMeApp
import lk.codegen.risime.ui.common.SectionHeader

/** What the Settings "Calls" row says is missing (§16.9), with the deep link that fixes it. */
data class CallsSetupItem(val text: String, val action: String?, val label: String?)

fun callsSetupItems(unsupported: String?, fullScreen: Boolean, batteryOptimised: Boolean, sdk: Int = Build.VERSION.SDK_INT): List<CallsSetupItem> = buildList {
    if (unsupported != null) add(CallsSetupItem(unsupported, if (unsupported.startsWith("Turn on notifications")) Settings.ACTION_APP_NOTIFICATION_SETTINGS else null, "Notifications"))
    if (!fullScreen && sdk >= 34) add(CallsSetupItem("Calls can't take over the screen when the phone is locked", "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", "Allow"))
    if (batteryOptimised) add(CallsSetupItem("Battery optimisation can delay or block incoming calls", Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, "Battery"))
    add(CallsSetupItem("On Xiaomi and some other phones, also allow \"Show on lock screen\" and \"Display pop-up windows while running in background\"", Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "App settings"))
}

/** §16.9 (S-i): the notices we ship (the APK is distributed outside Play). */
const val OPEN_SOURCE_LICENCES = """libwebrtc (io.github.webrtc-sdk:android-prefixed 144.7559.14, LiveKit's build) — BSD-3-Clause, with the WebRTC PATENTS grant. Bundled components include Opus (BSD-3-Clause), libsrtp (BSD-3-Clause), BoringSSL (OpenSSL/ISC), abseil-cpp (Apache-2.0), libyuv (BSD-3-Clause), usrsctp (BSD-3-Clause), protobuf (BSD-3-Clause) and others listed in the WebRTC source tree.
androidx.core:core-telecom 1.0.1 — Apache-2.0.
AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines, kotlinx.serialization, Room, WorkManager — Apache-2.0.
OkHttp — Apache-2.0. AppAuth — Apache-2.0. libphonenumber-android — Apache-2.0. Firebase Messaging — Apache-2.0.
OpenMLS and the RisiMe MLS core's Rust dependencies — MIT/Apache-2.0. JNA — Apache-2.0/LGPL-2.1."""

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
    SectionHeader("Open-source licences")
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show licences") }
    if (open) Text(OPEN_SOURCE_LICENCES, style = MaterialTheme.typography.bodySmall)
}
