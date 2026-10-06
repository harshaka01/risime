package lk.codegen.risime.realtime

import kotlinx.coroutines.flow.StateFlow
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.MsgSend
import lk.codegen.risime.net.MsgSendReply
import lk.codegen.risime.net.Presence
import lk.codegen.risime.net.Signal

enum class ConnectionState { Disconnected, Connecting, Syncing, Live, AuthFailed }

/**
 * One signed-in session. [token] returns the current bearer token (forceRefresh = the server said
 * it expired); it's asked again on every (re)connect, so a refreshed token is always used.
 */
class RealtimeSession(
    val serverUrl: String,
    val userId: String,
    /** §10.1 census: stable per-install id and app version, sent on every connect. */
    val deviceId: String? = null,
    val appVersion: String? = null,
    val token: suspend (forceRefresh: Boolean) -> String?,
) {
    constructor(serverUrl: String, userId: String, token: suspend (forceRefresh: Boolean) -> String?) :
        this(serverUrl, userId, null, null, token)

    constructor(serverUrl: String, token: String, userId: String) : this(serverUrl, userId, null, null, { _: Boolean -> token })
}

/** What to do when the server refuses our token (upgrade 401/403 or join `unauthorized`). */
fun interface AuthRefusalHandler {
    /** Typically: GET /me and act on it. True = reconnect (with backoff), false = stop. */
    suspend fun onRefused(): Boolean
}

sealed interface PushResult<out T> {
    data class Ok<T>(val value: T) : PushResult<T>
    /** The server answered with an error `reason` (e.g. unknown_recipient, rate_limited, bad_request). */
    data class Rejected(val reason: String) : PushResult<Nothing> {
        /** The whole error reply (§15.2 `failures`); not part of equality. */
        var body: kotlinx.serialization.json.JsonObject? = null
            private set

        companion object {
            fun withBody(reason: String, body: kotlinx.serialization.json.JsonObject?) = Rejected(reason).also { it.body = body }
        }
    }
    /** Not connected, socket dropped, or no reply in time. Safe to retry with the same ids. */
    data object Unavailable : PushResult<Nothing>
}

/** Receives what the realtime layer learns. Called from a single coroutine, in order. */
interface RealtimeListener {
    /** Last processed event_id, sent as `since` on every (re)join. */
    suspend fun cursor(): String?

    /** §13.2: the page's `history_before` (null = absent), delivered before that page's [onEvents]. */
    suspend fun onHistoryBefore(ts: String?) = Unit

    /** §15.7: the page's `server_time` (the server-clock offset for the 48 h check). */
    suspend fun onServerTime(ts: String?) = Unit

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

    /** §11.2 plaintext reaction: msg:send with `reaction` instead of `body`. */
    suspend fun sendReaction(msg: lk.codegen.risime.net.MsgSendReaction): PushResult<MsgSendReply>

    /** §10.3 encrypted msg:send (no body). */
    suspend fun sendEncrypted(msg: lk.codegen.risime.net.MsgSendE2ee): PushResult<MsgSendReply>

    /** §12.9 group msg:send (`conversation_id`, always ciphertext). */
    suspend fun sendGroup(msg: lk.codegen.risime.net.MsgSendGroup): PushResult<MsgSendReply> = PushResult.Unavailable

    suspend fun ack(messageIds: List<String>, status: String): PushResult<Unit>

    /** §15.2 `msg:delete`; a refusal carries the reply body (`failures`) in [PushResult.Rejected.body]. */
    suspend fun deleteMessages(msg: lk.codegen.risime.net.MsgDelete): PushResult<lk.codegen.risime.net.MsgDeleteReply> = PushResult.Unavailable

    /** §15.9 `chat:clear` (reply ok `{}`). */
    suspend fun clearChat(msg: lk.codegen.risime.net.ChatClear): PushResult<Unit> = PushResult.Unavailable

    /**
     * Users whose presence to watch (at most 200 are sent). Remembered across reconnects and sent
     * as `presence:watch` right after every successful join reply, and again whenever it changes.
     */
    fun setWatch(userIds: Set<String>)

    /** §6.2 `auth:refresh` on the live connection; Unavailable when not connected (reconnect uses the new token). */
    suspend fun refreshAuth(token: String): PushResult<lk.codegen.risime.net.AuthRefreshReply>

    /** §2.6 typing ([to] = a peer) or §12.9 group typing ([to] = a `grp:` id). Never queued: Unavailable when not connected. */
    suspend fun typing(to: String, typing: Boolean): PushResult<Unit>
}
