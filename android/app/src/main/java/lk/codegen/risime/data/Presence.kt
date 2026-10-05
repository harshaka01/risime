package lk.codegen.risime.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lk.codegen.risime.net.PRESENCE_WATCH_MAX
import lk.codegen.risime.net.Presence
import lk.codegen.risime.net.Signal
import lk.codegen.risime.realtime.SignalSink
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory presence and typing state (§2.5/§2.6). Never persisted. A user missing from [presence]
 * is "unknown" (not registered, not watched, or we're disconnected), never "offline".
 */
class PresenceTracker(
    private val scope: CoroutineScope,
    private val typingTimeoutMs: Long = TYPING_TIMEOUT_MS,
) : SignalSink {
    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    val presence: StateFlow<Map<String, Presence>> = _presence.asStateFlow()

    /** User ids currently shown as "typing…". */
    private val _typing = MutableStateFlow<Set<String>>(emptySet())
    val typing: StateFlow<Set<String>> = _typing.asStateFlow()

    private val expiry = ConcurrentHashMap<String, Job>()

    override fun onPresenceSnapshot(presences: List<Presence>) {
        // The reply covers the whole watch list, so it replaces what we knew.
        _presence.value = presences.associateBy { it.userId.lowercase() }
    }

    override fun onSignal(signal: Signal) {
        signal.presence()?.let { p ->
            _presence.update { it + (p.userId.lowercase() to p) }
            if (!p.online) stopTyping(p.userId)
        }
        signal.typing()?.let { t -> if (t.typing) startTyping(t.from) else stopTyping(t.from) }
    }

    override fun onDisconnected() {
        _presence.value = emptyMap()
        expiry.values.forEach { it.cancel() }
        expiry.clear()
        _typing.value = emptySet()
    }

    /** A message from [userId] arrived: they've stopped typing. */
    fun onMessageFrom(userId: String) = stopTyping(userId)

    /** `typing: true` (re)starts the 6 s window; the indicator ends when it passes without a refresh. */
    private fun startTyping(userId: String) {
        val id = userId.lowercase()
        _typing.update { it + id }
        val job = scope.launch {
            delay(typingTimeoutMs)
            _typing.update { it - id }
        }
        expiry.put(id, job)?.cancel()
    }

    private fun stopTyping(userId: String) {
        val id = userId.lowercase()
        expiry.remove(id)?.cancel()
        _typing.update { it - id }
    }

    companion object {
        const val TYPING_TIMEOUT_MS = 6_000L
    }
}

/** The presence watch list: registered contacts plus the open chat, at most 200, open chat kept first. */
fun watchList(registeredContactIds: List<String>, openChatPeer: String?): Set<String> {
    val ids = LinkedHashSet<String>()
    openChatPeer?.let { ids += it.lowercase() }
    registeredContactIds.forEach { ids += it.lowercase() }
    return ids.take(PRESENCE_WATCH_MAX).toSet()
}

/**
 * Sender side of §2.6 for one chat's composer. `true` when typing starts and again at most every
 * [refreshMs] while it continues; `false` after [idleMs] without input, when the input is cleared,
 * or when the message is sent. [send] must not block; it drops the push when offline (never queued).
 * [scope] only runs the idle timer; call from one thread (the main thread in the app).
 */
class TypingSender(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val send: (Boolean) -> Unit,
    private val refreshMs: Long = 3_000,
    private val idleMs: Long = 3_000,
) {
    private var active = false
    private var lastTrueAt = Long.MIN_VALUE
    private var idle: Job? = null

    /** Composer text changed. */
    fun onInput(text: String) {
        if (text.isBlank()) return stop()
        val now = clock()
        if (!active || now - lastTrueAt >= refreshMs) {
            active = true
            lastTrueAt = now
            send(true)
        }
        idle?.cancel()
        idle = scope.launch {
            delay(idleMs)
            stop()
        }
    }

    /** Message sent, input cleared, or the chat closed. */
    fun stop() {
        idle?.cancel()
        idle = null
        if (active) {
            active = false
            lastTrueAt = Long.MIN_VALUE
            send(false)
        }
    }
}
