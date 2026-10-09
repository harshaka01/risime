package lk.codegen.risime.ui.lock

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Decision 064 review: while the fingerprint lock is on, the Recents screen shows no thumbnail of
 * RisiMe's chats. API 33+: `setRecentsScreenshotEnabled(false)` (screenshots by the user still
 * work); below 33: FLAG_SECURE. Both are undone when the lock is turned off. Only the chats window
 * (MainActivity) is covered: CallActivity is never made secure (calls stay answerable and visible).
 *
 * Screen sharing (Harsha's real-phone report, WhatsApp's behaviour): normal screens are never
 * FLAG_SECURE, so an "Entire screen" share shows RisiMe to the viewer like any other app. Only the
 * [SecureScreen]s (the app-lock screen, the Locked chats folder, a locked chat while it is open) are
 * FLAG_SECURE, always (they show black in screenshots, recordings and shares). Below API 33 the
 * lock's Recents fallback (FLAG_SECURE on every screen) is dropped while this phone shares its screen:
 * the user chose to show it.
 */
object LockPrivacy {
    /** [recentsScreenshot]: the API 33+ setting (null: not available); [flagSecure]: FLAG_SECURE on the window. */
    data class Plan(val recentsScreenshot: Boolean?, val flagSecure: Boolean)

    fun plan(lockOn: Boolean, sdk: Int, secureScreen: Boolean = false, sharing: Boolean = false): Plan =
        if (sdk >= 33) {
            Plan(recentsScreenshot = !lockOn, flagSecure = secureScreen)
        } else {
            Plan(recentsScreenshot = null, flagSecure = secureScreen || (lockOn && !sharing))
        }

    fun apply(activity: Activity, lockOn: Boolean, secureScreen: Boolean, sharing: Boolean, sdk: Int = Build.VERSION.SDK_INT) {
        val p = plan(lockOn, sdk, secureScreen, sharing)
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

/** The only screens that are FLAG_SECURE (never shown in a screenshot, a recording or a screen share). */
enum class SecureScreen { APP_LOCK, LOCKED_CHATS_FOLDER, LOCKED_CHAT }

/**
 * Which [SecureScreen]s are on screen now (MainActivity reads [active] for FLAG_SECURE). Screens
 * register while composed ([SecureWindow]); each registration is its own token, so two at once (a
 * locked chat over the folder) keep the window secure until the last one leaves.
 */
object SecureScreens {
    private val _held = MutableStateFlow<List<Pair<Any, SecureScreen>>>(emptyList())
    val held: StateFlow<List<Pair<Any, SecureScreen>>> = _held.asStateFlow()

    val active: Boolean get() = _held.value.isNotEmpty()

    fun hold(token: Any, screen: SecureScreen) = _held.update { it + (token to screen) }

    fun release(token: Any) = _held.update { l -> l.filterNot { it.first === token } }
}

/** While composed (and [on]): this window is FLAG_SECURE ([SecureScreens]). */
@Composable
fun SecureWindow(screen: SecureScreen, on: Boolean = true) {
    DisposableEffect(screen, on) {
        val token = Any()
        if (on) SecureScreens.hold(token, screen)
        onDispose { SecureScreens.release(token) }
    }
}
