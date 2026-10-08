package lk.codegen.risime.push

/**
 * "Notifications health" (P0 background delivery): what can stop a message or a call from reaching
 * this phone while RisiMe is in the background, one row each, with the settings page that fixes it.
 * Pure: the probe ([NotificationHealthProbe]) gathers the inputs, this decides the rows.
 */
data class ChannelState(
    /** NotificationManager importance (0 = none/off, 3 = default, 4 = high). */
    val importance: Int,
)

data class HealthInputs(
    val sdk: Int,
    /** `NotificationManagerCompat.areNotificationsEnabled()` (includes POST_NOTIFICATIONS on 33+). */
    val notificationsEnabled: Boolean,
    /** Null: the channel doesn't exist yet (it is created with the right settings when first used). */
    val messagesChannel: ChannelState?,
    val callsChannel: ChannelState?,
    /** `PowerManager.isIgnoringBatteryOptimizations`. */
    val ignoringBatteryOptimizations: Boolean,
    /** `ActivityManager.isBackgroundRestricted` (API 28+; false below). */
    val backgroundRestricted: Boolean,
    /** `NotificationManager.canUseFullScreenIntent` (API 34+; true below). */
    val canUseFullScreenIntent: Boolean,
    /** This build has Firebase configured. */
    val pushConfigured: Boolean,
    /** Hash of the current FCM token (null: none could be fetched). */
    val currentTokenHash: String?,
    /** Hash of the token the server last accepted for this device (null: never). */
    val registeredTokenHash: String?,
)

enum class HealthRow { NOTIFICATIONS, MESSAGES_CHANNEL, CALLS_CHANNEL, BATTERY, FULL_SCREEN, PUSH }

/** The settings page (or action) each ✗ row links to. */
enum class HealthFix { APP_NOTIFICATIONS, MESSAGES_CHANNEL, CALLS_CHANNEL, BATTERY, FULL_SCREEN, RETRY_PUSH }

data class HealthCheck(val row: HealthRow, val ok: Boolean, val title: String, val detail: String, val fix: HealthFix?)

const val IMPORTANCE_NONE = 0
const val IMPORTANCE_HIGH = 4

fun healthChecks(i: HealthInputs): List<HealthCheck> {
    val notif = i.notificationsEnabled
    val msg = i.messagesChannel
    // A channel that doesn't exist yet is created at high importance on first use.
    val msgOk = notif && (msg == null || msg.importance >= IMPORTANCE_HIGH)
    val callsOk = notif && (i.callsChannel == null || i.callsChannel.importance >= IMPORTANCE_HIGH)
    val battery = i.ignoringBatteryOptimizations && !i.backgroundRestricted
    val pushOk = i.pushConfigured && i.currentTokenHash != null && i.currentTokenHash == i.registeredTokenHash
    return listOf(
        HealthCheck(
            HealthRow.NOTIFICATIONS, notif, "Notifications allowed",
            if (notif) "RisiMe may show notifications." else "Notifications are off for RisiMe: no messages or calls will show.",
            HealthFix.APP_NOTIFICATIONS.takeIf { !notif },
        ),
        HealthCheck(
            HealthRow.MESSAGES_CHANNEL, msgOk, "Message notifications",
            when {
                !notif -> "Turn on notifications first."
                msg != null && msg.importance == IMPORTANCE_NONE -> "The Messages category is turned off."
                msgOk -> "On, pop on screen and show on the lock screen."
                else -> "The Messages category won't pop on screen. Set it to \"Alert\" / high."
            },
            (if (!notif) HealthFix.APP_NOTIFICATIONS else HealthFix.MESSAGES_CHANNEL).takeIf { !msgOk },
        ),
        HealthCheck(
            HealthRow.CALLS_CHANNEL, callsOk, "Call notifications",
            when {
                !notif -> "Turn on notifications first."
                i.callsChannel != null && i.callsChannel.importance == IMPORTANCE_NONE -> "The Calls category is turned off: calls can't ring."
                callsOk -> "On: incoming calls ring."
                else -> "The Calls category won't ring on screen. Set it to \"Alert\" / high."
            },
            (if (!notif) HealthFix.APP_NOTIFICATIONS else HealthFix.CALLS_CHANNEL).takeIf { !callsOk },
        ),
        HealthCheck(
            HealthRow.BATTERY, battery, "Battery: unrestricted",
            when {
                i.backgroundRestricted -> "Battery use is set to Restricted: Android blocks RisiMe in the background."
                !i.ignoringBatteryOptimizations -> "Battery optimisation can delay messages and calls while the phone sleeps."
                else -> "RisiMe may run in the background."
            },
            HealthFix.BATTERY.takeIf { !battery },
        ),
        HealthCheck(
            HealthRow.FULL_SCREEN, i.canUseFullScreenIntent, "Full-screen calls",
            if (i.canUseFullScreenIntent) "Incoming calls show full screen on a locked phone."
            else "Incoming calls can't show full screen on a locked phone.",
            HealthFix.FULL_SCREEN.takeIf { !i.canUseFullScreenIntent },
        ),
        HealthCheck(
            HealthRow.PUSH, pushOk, "Push registered",
            when {
                !i.pushConfigured -> "This build has no push service."
                i.currentTokenHash == null -> "No push token from Google Play services yet."
                i.registeredTokenHash == null -> "The push token isn't registered with the server yet."
                i.currentTokenHash != i.registeredTokenHash -> "The server has an older push token for this phone."
                else -> "The server can wake this phone."
            },
            HealthFix.RETRY_PUSH.takeIf { !pushOk && i.pushConfigured },
        ),
    )
}

/** After an update: show the health screen once for this version, and only when something is wrong. */
fun shouldShowHealthAfterUpdate(versionCode: Int, lastShownFor: Int?, checks: List<HealthCheck>): Boolean =
    lastShownFor != versionCode && checks.any { !it.ok }

/** Short, stable hash of a push token (the token itself is never stored twice or logged). */
fun tokenHash(token: String?): String? = token?.takeIf { it.isNotBlank() }?.let {
    java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).take(12).joinToString("") { b -> "%02x".format(b) }
}

// ---- OEM background guidance ----

enum class OemBrand { XIAOMI, HUAWEI, SAMSUNG, OPPO, VIVO, REALME, ONEPLUS, OTHER }

fun oemBrand(manufacturer: String, brand: String = ""): OemBrand {
    val m = (manufacturer + " " + brand).lowercase()
    return when {
        "xiaomi" in m || "redmi" in m || "poco" in m -> OemBrand.XIAOMI
        "huawei" in m || "honor" in m -> OemBrand.HUAWEI
        "samsung" in m -> OemBrand.SAMSUNG
        "oneplus" in m -> OemBrand.ONEPLUS
        "realme" in m -> OemBrand.REALME
        "oppo" in m -> OemBrand.OPPO
        "vivo" in m || "iqoo" in m -> OemBrand.VIVO
        else -> OemBrand.OTHER
    }
}

fun oemGuidance(b: OemBrand): String = when (b) {
    OemBrand.XIAOMI -> "Xiaomi / MIUI / HyperOS: Settings → Apps → RisiMe → turn on Autostart, and set Battery saver to \"No restrictions\". Lock RisiMe in Recents (pull it down) so a cleanup doesn't close it."
    OemBrand.HUAWEI -> "Huawei / Honor: Settings → Battery → App launch → RisiMe → Manage manually, and turn on Auto-launch, Secondary launch and Run in background."
    OemBrand.SAMSUNG -> "Samsung: Settings → Battery → Background usage limits → Never sleeping apps → add RisiMe. Make sure RisiMe isn't in \"Sleeping\" or \"Deep sleeping\" apps."
    OemBrand.OPPO, OemBrand.REALME -> "Oppo / Realme / ColorOS: Settings → Apps → RisiMe → Battery usage → allow background activity and Auto launch. Lock RisiMe in Recents."
    OemBrand.VIVO -> "Vivo / iQOO: Settings → Battery → Background power consumption → RisiMe → allow, and turn on Autostart in i Manager → App manager."
    OemBrand.ONEPLUS -> "OnePlus: Settings → Battery → Battery optimisation → RisiMe → Don't optimise; Settings → Apps → RisiMe → allow Auto launch. Lock RisiMe in Recents."
    OemBrand.OTHER -> "If messages still arrive late: Settings → Apps → RisiMe → Battery → Unrestricted, and don't swipe RisiMe away with \"Close all\" cleaners."
}

/** Known autostart/background screens (package, activity), tried in order; the app details page is the fallback. */
fun oemAutostartComponents(b: OemBrand): List<Pair<String, String>> = when (b) {
    OemBrand.XIAOMI -> listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
    )
    OemBrand.HUAWEI -> listOf(
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
    )
    OemBrand.SAMSUNG -> listOf(
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        "com.samsung.android.sm" to "com.samsung.android.sm.battery.ui.BatteryActivity",
    )
    OemBrand.OPPO, OemBrand.REALME -> listOf(
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
    )
    OemBrand.VIVO -> listOf(
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
    )
    OemBrand.ONEPLUS -> listOf(
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
    )
    OemBrand.OTHER -> emptyList()
}
