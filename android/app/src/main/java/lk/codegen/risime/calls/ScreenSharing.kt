package lk.codegen.risime.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * §23.5 privacy rules while this device shares its screen: its own message notifications carry no
 * sender or content and make no sound. RisiMe's normal screens are NOT FLAG_SECURE while sharing
 * (Harsha's real-phone report: an "Entire screen" share showed black to the viewer while RisiMe was on
 * screen; WhatsApp shows itself): only the app-lock screen, the Locked chats folder and an open locked
 * chat are ([lk.codegen.risime.ui.lock.SecureScreens]). Before every share the call screen says "Your
 * whole screen, including notifications, will be visible". Read by MainActivity and the notifier; set
 * by the call manager from the call state.
 */
object ScreenSharing {
    private val _flow = MutableStateFlow(false)
    val flow: StateFlow<Boolean> = _flow.asStateFlow()

    val active: Boolean get() = _flow.value

    fun set(on: Boolean) {
        _flow.value = on
    }

    /**
     * §23.5: when a share starts, notifications already in the shade are reposted without sender or
     * content (new ones are posted that way via the notifier's `hideContent`); when it stops, they get
     * their content back. [refresh] reposts the posted chat notifications (silently).
     */
    fun watch(scope: kotlinx.coroutines.CoroutineScope, refresh: suspend (sharing: Boolean) -> Unit): kotlinx.coroutines.Job =
        scope.launch {
            var last = _flow.value
            _flow.collect { now ->
                if (now == last) return@collect
                last = now
                runCatching { refresh(now) }
            }
        }
}
