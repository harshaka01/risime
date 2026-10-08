package lk.codegen.risime.ui.lock

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * Decision 064 review: while the fingerprint lock is on, the Recents screen shows no thumbnail of
 * RisiMe's chats. API 33+: `setRecentsScreenshotEnabled(false)` (screenshots by the user still
 * work); below 33: FLAG_SECURE. Both are undone when the lock is turned off. Only the chats window
 * (MainActivity) is covered: CallActivity is never made secure (calls stay answerable and visible).
 */
object LockPrivacy {
    /** [recentsScreenshot]: the API 33+ setting (null: not available); [flagSecure]: the pre-33 fallback. */
    data class Plan(val recentsScreenshot: Boolean?, val flagSecure: Boolean)

    fun plan(lockOn: Boolean, sdk: Int): Plan =
        if (sdk >= 33) Plan(recentsScreenshot = !lockOn, flagSecure = false) else Plan(recentsScreenshot = null, flagSecure = lockOn)

    /** [sharing] (§23.5): while this phone shares its screen RisiMe's own windows are FLAG_SECURE (viewers see black). */
    fun apply(activity: Activity, lockOn: Boolean, sdk: Int = Build.VERSION.SDK_INT, sharing: Boolean = false) {
        val p = plan(lockOn, sdk).let { if (sharing) it.copy(flagSecure = true) else it }
        if (p.recentsScreenshot != null && Build.VERSION.SDK_INT >= 33) {
            runCatching { activity.setRecentsScreenshotEnabled(p.recentsScreenshot) }
        }
        if (p.flagSecure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
