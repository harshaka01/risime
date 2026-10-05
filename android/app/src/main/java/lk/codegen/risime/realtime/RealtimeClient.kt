package lk.codegen.risime.realtime

import kotlinx.coroutines.flow.StateFlow
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.MsgSend
import lk.codegen.risime.net.MsgSendReply
import lk.codegen.risime.net.Presence
import lk.codegen.risime.net.Signal

enum class ConnectionState { Disconnected, Connecting, Syncing, Live, AuthFailed }

data class RealtimeSession(val serverUrl: String, val token: String, val userId: String)

sealed interface PushResult<out T> {
    data class Ok<T>(val value: T) : PushResult<T>
    /** The server answered with an error `reason` (e.g. unknown_recipient, rate_limited, bad_request). */
    data class Rejected(val reason: String) : PushResult<Nothing>
    /** Not connected, socket dropped, or no reply in time. Safe to retry with the same ids. */
    data object Unavailable : PushResult<Nothing>
}

/** Receives what the realtime layer learns. Called from a single coroutine, in order. */
interface RealtimeListener {
    /** Last processed event_id, sent as `since` on every (re)join. */
    suspend fun cursor(): String?

    /** Apply events in order (dedupe + persist cursor). Called for join/sync pages and live pushes. */
    suspend fun onEvents(events: List<Event>)

    /** Join and the `sync` loop finished; the inbox is live. Flush the outbox and pending acks. */
    suspend fun onLive()

    /** The token was refused (socket upgrade 401/403 or join unauthorized). */
    suspend fun onAuthFailed()
}

/**
 * Ephemeral state (§2.3 `signal`, §2.5, §2.6). Called straight from the socket reader: never buffered
 * behind the sync loop, never touching the cursor. Implementations must be thread-safe and fast.
 */
interface SignalSink {
    /** Reply to `presence:watch`: the full presence list for the watched ids (missing id = unknown). */
    fun onPresenceSnapshot(presences: List<Presence>)

    fun onSignal(signal: Signal)

    /** The socket is gone: presence and typing are no longer known. */
    fun onDisconnected()
}

/** The app's view of the realtime connection (PROTOCOL.md §2). Hides the Phoenix details. */
interface RealtimeClient {
    val state: StateFlow<ConnectionState>

    fun start(session: RealtimeSession)

    fun stop()

    suspend fun sendMessage(msg: MsgSend): PushResult<MsgSendReply>

    suspend fun ack(messageIds: List<String>, status: String): PushResult<Unit>

    /**
     * Users whose presence to watch (at most 200 are sent). Remembered across reconnects and sent
     * as `presence:watch` right after every successful join reply, and again whenever it changes.
     */
    fun setWatch(userIds: Set<String>)

    /** §2.6 typing. Never queued: returns [PushResult.Unavailable] when not connected. */
    suspend fun typing(to: String, typing: Boolean): PushResult<Unit>
}
