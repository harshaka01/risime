package lk.codegen.risime.push

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import lk.codegen.risime.AppContainer
import lk.codegen.risime.BuildConfig
import lk.codegen.risime.calls.CallNotifications

/** Reads the device state for [healthChecks] and opens the page that fixes each row. */
class NotificationHealthProbe(private val context: Context, private val c: AppContainer) {
    suspend fun inputs(): HealthInputs {
        c.notifier.ensureChannels()
        runCatching { c.calls.notifications.ensureChannels() }
        val nm = context.getSystemService(NotificationManager::class.java)
        fun channel(id: String): ChannelState? =
            if (Build.VERSION.SDK_INT < 26) null else nm.getNotificationChannel(id)?.let { ChannelState(it.importance) }
        val pm = context.getSystemService(PowerManager::class.java)
        val am = context.getSystemService(ActivityManager::class.java)
        val token = runCatching { c.push.currentToken() }.getOrNull()
        return HealthInputs(
            sdk = Build.VERSION.SDK_INT,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            messagesChannel = channel(Notifier.CH_MESSAGES),
            callsChannel = channel(CallNotifications.CH_CALLS),
            ignoringBatteryOptimizations = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
            backgroundRestricted = Build.VERSION.SDK_INT >= 28 && am?.isBackgroundRestricted == true,
            canUseFullScreenIntent = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
            pushConfigured = c.push.available,
            currentTokenHash = tokenHash(token),
            registeredTokenHash = c.push.registeredTokenHash(),
        )
    }

    suspend fun checks(): List<HealthCheck> = healthChecks(inputs()).also { rows ->
        Log.i("RisiMe", "RisiMe push: health " + rows.joinToString(" ") { "${it.row}=${if (it.ok) "ok" else "FAIL"}" })
    }

    /** The intent that fixes [fix] (RETRY_PUSH is an action, not a page: null). */
    @SuppressLint("BatteryLife") // permitted for messaging/VoIP apps (Play policy); asked only on a tap
    fun intentFor(fix: HealthFix): Intent? {
        val pkg = context.packageName
        return when (fix) {
            HealthFix.APP_NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            HealthFix.MESSAGES_CHANNEL -> channelIntent(Notifier.CH_MESSAGES)
            HealthFix.CALLS_CHANNEL -> channelIntent(CallNotifications.CH_CALLS)
            HealthFix.BATTERY -> {
                val am = context.getSystemService(ActivityManager::class.java)
                if (Build.VERSION.SDK_INT >= 28 && am?.isBackgroundRestricted == true) appDetails()
                else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
            }
            HealthFix.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg"))
            } else appDetails()
            HealthFix.RETRY_PUSH -> null
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun channelIntent(id: String): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, id)

    fun appDetails(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens [fix]'s page; falls back to the app details page when the phone has no such screen. */
    fun open(fix: HealthFix): Boolean {
        val i = intentFor(fix) ?: return false
        return runCatching { context.startActivity(i) }.isSuccess || runCatching { context.startActivity(appDetails()) }.isSuccess
    }

    /** The OEM's autostart screen (known component names, in order), else the app details page. */
    fun openAutostart(brand: OemBrand = oemBrand(Build.MANUFACTURER, Build.BRAND)) {
        for ((p, cls) in oemAutostartComponents(brand)) {
            val i = Intent().setComponent(ComponentName(p, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i) }.isSuccess) return
        }
        runCatching { context.startActivity(appDetails()) }
    }

    /** "Retry" on the push row: fetch the token again and register it with the server. */
    suspend fun retryPush(): Boolean = runCatching { c.push.ensureRegistered() }.getOrDefault(false)

    companion object {
        val versionCode: Int get() = BuildConfig.VERSION_CODE
    }
}
