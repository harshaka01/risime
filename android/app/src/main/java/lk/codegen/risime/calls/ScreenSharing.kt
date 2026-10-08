package lk.codegen.risime.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * §23.5 privacy rules while this device shares its screen: RisiMe's own windows are `FLAG_SECURE`
 * (viewers see black: no mirror loop, other chats never leak) and its own message notifications
 * carry no sender or content and make no sound. Read by the activities and the notifier; set by
 * the call manager from the call state.
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

    /** `FLAG_SECURE` on RisiMe's own windows: while sharing (and, below API 33, while the app lock asks for it). */
    fun secureWindow(sharing: Boolean, lockWants: Boolean): Boolean = sharing || lockWants
}
