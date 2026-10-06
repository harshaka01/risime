package lk.codegen.risime.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import lk.codegen.risime.net.AuthRefresh
import lk.codegen.risime.net.AuthRefreshReply
import lk.codegen.risime.net.ErrorReason
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.EventsPage
import lk.codegen.risime.net.JoinPayload
import lk.codegen.risime.net.MsgAck
import lk.codegen.risime.net.MsgSend
import lk.codegen.risime.net.MsgSendReply
import lk.codegen.risime.net.PRESENCE_WATCH_MAX
import lk.codegen.risime.net.PresenceWatch
import lk.codegen.risime.net.PresenceWatchReply
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.Signal
import lk.codegen.risime.net.TypingPush
import lk.codegen.risime.net.SyncPayload
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal Phoenix V2 channels client on OkHttp WebSocket (docs/decisions/002): join/push/reply refs,
 * heartbeat, reconnect backoff and the `since`/`sync` loop on topic `inbox:<user id>`.
 */
class PhoenixRealtimeClient(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val listener: RealtimeListener,
    private val backoffMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 30_000),
    private val heartbeatMs: Long = 30_000,
    private val replyTimeoutMs: Long = 10_000,
    private val pageLimit: Int = 500,
    private val signals: SignalSink? = null,
    /** Null: a refusal stops the client in [ConnectionState.AuthFailed] (dev tokens, tests). */
    private val refusals: AuthRefusalHandler? = null,
) : RealtimeClient {

    /** Set by `auth:expired`: the next connect asks for a refreshed token. */
    @Volatile
    private var forceRefresh = false

    private val watch = MutableStateFlow<Set<String>>(emptySet())

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var job: Job? = null
    private val refs = AtomicLong(0)

    @Volatile
    private var current: Connection? = null

    override fun start(session: RealtimeSession) {
        if (job?.isActive == true) return
        job = scope.launch { runLoop(session) }
    }

    override fun stop() {
        job?.cancel()
        job = null
        current?.close()
        current = null
        if (_state.value != ConnectionState.AuthFailed) _state.value = ConnectionState.Disconnected
    }

    override suspend fun sendMessage(msg: MsgSend): PushResult<MsgSendReply> =
        liveConnection()?.push("msg:send", ProtocolJson.encodeToJsonElement(msg))
            ?.map { ProtocolJson.decodeFromJsonElement<MsgSendReply>(it) }
            ?: PushResult.Unavailable

    override suspend fun ack(messageIds: List<String>, status: String): PushResult<Unit> =
        liveConnection()?.push("msg:ack", ProtocolJson.encodeToJsonElement(MsgAck(messageIds, status)))
            ?.map { }
            ?: PushResult.Unavailable

    override fun setWatch(userIds: Set<String>) {
        watch.value = userIds
    }

    override suspend fun refreshAuth(token: String): PushResult<AuthRefreshReply> =
        current?.takeIf { _state.value == ConnectionState.Live || _state.value == ConnectionState.Syncing }
            ?.push("auth:refresh", ProtocolJson.encodeToJsonElement(AuthRefresh(token)))
            ?.map { ProtocolJson.decodeFromJsonElement<AuthRefreshReply>(it) }
            ?: PushResult.Unavailable

    override suspend fun typing(to: String, typing: Boolean): PushResult<Unit> =
        current?.takeIf { _state.value == ConnectionState.Live }
            ?.push("typing", ProtocolJson.encodeToJsonElement(TypingPush(to, typing)))
            ?.map { }
            ?: PushResult.Unavailable

    private fun liveConnection(): Connection? =
        current?.takeIf { _state.value == ConnectionState.Live || _state.value == ConnectionState.Syncing }

    private suspend fun runLoop(session: RealtimeSession) {
        var attempt = 0
        while (scope.isActive) {
            _state.value = ConnectionState.Connecting
            val force = forceRefresh
            forceRefresh = false
            val token = try {
                session.token(force)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (token == null) {
                // No usable token (locked, refresh failed): the auth layer decides what happens next.
                if (refusals?.onRefused() != true) {
                    _state.value = ConnectionState.AuthFailed
                    listener.onAuthFailed()
                    return
                }
                _state.value = ConnectionState.Disconnected
                delay(backoffMs[attempt.coerceAtMost(backoffMs.lastIndex)])
                attempt++
                continue
            }
            val conn = Connection(session, token)
            current = conn
            val outcome = try {
                conn.run { attempt = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Outcome.Closed // e.g. a malformed reply; reconnect with the persisted cursor
            } finally {
                conn.close()
                if (current === conn) current = null
                signals?.onDisconnected()
            }
            if (outcome == Outcome.AuthFailed) {
                // §6.2: a refused upgrade isn't "logged out" by itself; ask the auth layer (GET /me).
                if (refusals?.onRefused() != true) {
                    _state.value = ConnectionState.AuthFailed
                    listener.onAuthFailed()
                    return
                }
                forceRefresh = true
            }
            _state.value = ConnectionState.Disconnected
            delay(backoffMs[attempt.coerceAtMost(backoffMs.lastIndex)])
            attempt++
        }
    }

    private enum class Outcome { Closed, AuthFailed }

    private sealed interface Inbound {
        data object Open : Inbound
        data class Frame(val frame: PhoenixFrame) : Inbound
        data class Closed(val authFailed: Boolean) : Inbound
    }

    private inner class Connection(private val session: RealtimeSession, private val token: String) {
        private val topic = "inbox:${session.userId}"
        private val inbound = Channel<Inbound>(Channel.UNLIMITED)
        private val liveEvents = Channel<Event>(Channel.UNLIMITED)
        private val pending = ConcurrentHashMap<String, CompletableDeferred<PhoenixFrame?>>()
        private val closed = CompletableDeferred<Outcome>()
        private var joinRef: String? = null
        private var ws: WebSocket? = null

        fun close() {
            ws?.close(1000, null)
            ws = null
            pending.values.forEach { it.complete(null) }
            pending.clear()
            closed.complete(Outcome.Closed)
        }

        suspend fun push(event: String, payload: JsonElement): PushResult<JsonObject> {
            val socket = ws ?: return PushResult.Unavailable
            val ref = refs.incrementAndGet().toString()
            val deferred = CompletableDeferred<PhoenixFrame?>()
            pending[ref] = deferred
            if (!socket.send(PhoenixFrame(joinRef, ref, topic, event, payload).encode())) {
                pending.remove(ref)
                return PushResult.Unavailable
            }
            val reply = withTimeoutOrNull(replyTimeoutMs) { deferred.await() }
            pending.remove(ref)
            if (reply == null) {
                // No reply in time: treat the socket as dead so we reconnect (and resend) promptly.
                socket.cancel()
                return PushResult.Unavailable
            }
            return when (reply.replyStatus) {
                "ok" -> PushResult.Ok(reply.replyResponse)
                else -> PushResult.Rejected(
                    runCatching { ProtocolJson.decodeFromJsonElement<ErrorReason>(reply.replyResponse).reason }
                        .getOrDefault("unknown"),
                )
            }
        }

        /** Runs one socket lifetime. [onJoined] is called once the join succeeded. */
        suspend fun run(onJoined: () -> Unit): Outcome = coroutineScope {
            // §6.2: the token goes in the Authorization header, never the URL (proxy logs).
            ws = http.newWebSocket(
                Request.Builder().url(socketUrl(session)).header("Authorization", "Bearer $token").build(),
                SocketListener(),
            )
            // Wait for open (or failure).
            when (val first = inbound.receive()) {
                is Inbound.Closed -> return@coroutineScope if (first.authFailed) Outcome.AuthFailed else Outcome.Closed
                else -> Unit
            }
            val reader = launch { readLoop() }
            val heartbeat = launch { heartbeatLoop() }
            val outcome = try {
                session()
            } finally {
                reader.cancel()
                heartbeat.cancel()
            }
            if (outcome == null) {
                onJoined()
                closed.await()
            } else {
                outcome
            }
        }

        /** Join + sync loop, then hand live events to the listener until the socket closes. Null = joined OK, closed later. */
        private suspend fun session(): Outcome? = coroutineScope {
            _state.value = ConnectionState.Syncing
            joinRef = refs.incrementAndGet().toString()
            val join = pushJoin(JoinPayload(listener.cursor(), pageLimit))
            val page = when (join) {
                is PushResult.Ok -> ProtocolJson.decodeFromJsonElement<EventsPage>(join.value)
                is PushResult.Rejected ->
                    return@coroutineScope if (join.reason == "unauthorized") Outcome.AuthFailed else Outcome.Closed
                PushResult.Unavailable -> return@coroutineScope Outcome.Closed
            }
            // §2.5: watches don't survive a rejoin; send the list right after every join reply, then on change.
            val watcher = launch {
                watch.collect { ids ->
                    val r = push("presence:watch", ProtocolJson.encodeToJsonElement(PresenceWatch(ids.take(PRESENCE_WATCH_MAX))))
                    if (r is PushResult.Ok) {
                        runCatching { ProtocolJson.decodeFromJsonElement<PresenceWatchReply>(r.value) }
                            .onSuccess { signals?.onPresenceSnapshot(it.presences) }
                    }
                }
            }
            try {
                syncThenLive(page)
            } finally {
                watcher.cancel()
            }
        }

        private suspend fun syncThenLive(page: EventsPage): Outcome? = coroutineScope {
            var current = page
            listener.onEvents(current.events)
            while (current.hasMore && current.events.isNotEmpty()) {
                val since = current.events.last().eventId
                current = when (val r = push("sync", ProtocolJson.encodeToJsonElement(SyncPayload(since)))) {
                    is PushResult.Ok -> ProtocolJson.decodeFromJsonElement<EventsPage>(r.value)
                    else -> return@coroutineScope Outcome.Closed
                }
                listener.onEvents(current.events)
            }
            _state.value = ConnectionState.Live
            launch { listener.onLive() }
            // Live events (buffered while syncing) are applied in arrival order.
            while (true) {
                val next = select<Event?> {
                    closed.onAwait { null }
                    liveEvents.onReceive { it }
                } ?: break
                val batch = mutableListOf<Event>(next)
                while (true) batch += liveEvents.tryReceive().getOrNull() ?: break
                listener.onEvents(batch)
            }
            null
        }

        private suspend fun pushJoin(payload: JoinPayload): PushResult<JsonObject> {
            val socket = ws ?: return PushResult.Unavailable
            val ref = joinRef!!
            val deferred = CompletableDeferred<PhoenixFrame?>()
            pending[ref] = deferred
            socket.send(
                PhoenixFrame(ref, ref, topic, PhoenixFrame.PHX_JOIN, ProtocolJson.encodeToJsonElement(payload)).encode(),
            )
            val reply = withTimeoutOrNull(replyTimeoutMs) { deferred.await() }
            pending.remove(ref)
            reply ?: return PushResult.Unavailable
            return if (reply.replyStatus == "ok") {
                PushResult.Ok(reply.replyResponse)
            } else {
                PushResult.Rejected(
                    runCatching { ProtocolJson.decodeFromJsonElement<ErrorReason>(reply.replyResponse).reason }
                        .getOrDefault("unknown"),
                )
            }
        }

        private suspend fun readLoop() {
            for (msg in inbound) {
                when (msg) {
                    is Inbound.Closed -> {
                        closed.complete(if (msg.authFailed) Outcome.AuthFailed else Outcome.Closed)
                        pending.values.forEach { it.complete(null) }
                        return
                    }
                    Inbound.Open -> Unit
                    is Inbound.Frame -> handleFrame(msg.frame)
                }
            }
        }

        private fun handleFrame(f: PhoenixFrame) {
            when (f.event) {
                PhoenixFrame.PHX_REPLY -> f.ref?.let { pending[it]?.complete(f) }
                "signal" -> if (f.topic == topic) {
                    runCatching { ProtocolJson.decodeFromJsonElement<Signal>(f.payload) }
                        .onSuccess { sig -> signals?.onSignal(sig) }
                }
                "event" -> if (f.topic == topic) {
                    runCatching { ProtocolJson.decodeFromJsonElement<Event>(f.payload) }
                        .onSuccess { liveEvents.trySend(it) }
                }
                "auth:expired" -> if (f.topic == topic) {
                    // §6.2: refresh, then reconnect with `since`. Never a logout.
                    forceRefresh = true
                    ws?.close(1000, null)
                    closed.complete(Outcome.Closed)
                }
                PhoenixFrame.PHX_ERROR, PhoenixFrame.PHX_CLOSE -> if (f.topic == topic) {
                    // Channel crashed or was closed: drop the socket and rejoin with the cursor.
                    ws?.close(1000, null)
                    closed.complete(Outcome.Closed)
                }
            }
        }

        private suspend fun heartbeatLoop() {
            var outstanding: CompletableDeferred<PhoenixFrame?>? = null
            while (true) {
                delay(heartbeatMs)
                if (outstanding != null && !outstanding.isCompleted) {
                    // No reply to the previous heartbeat: the connection is dead.
                    ws?.cancel()
                    closed.complete(Outcome.Closed)
                    return
                }
                val ref = refs.incrementAndGet().toString()
                val d = CompletableDeferred<PhoenixFrame?>()
                pending[ref] = d
                outstanding = d
                d.invokeOnCompletion { pending.remove(ref) }
                ws?.send(
                    PhoenixFrame(null, ref, PhoenixFrame.PHOENIX_TOPIC, PhoenixFrame.HEARTBEAT, PhoenixFrame.emptyPayload())
                        .encode(),
                )
            }
        }

        private inner class SocketListener : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                inbound.trySend(Inbound.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                PhoenixFrame.decode(text)?.let { inbound.trySend(Inbound.Frame(it)) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                inbound.trySend(Inbound.Closed(authFailed = false))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                inbound.trySend(Inbound.Closed(authFailed = false))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                inbound.trySend(Inbound.Closed(authFailed = code == 401 || code == 403))
            }
        }
    }

    companion object {
        /** `{SERVER_WS}/socket/websocket?vsn=2.0.0` (OkHttp maps ws/wss onto http/https); token in the header. */
        fun socketUrl(session: RealtimeSession): String =
            session.serverUrl.trimEnd('/').toHttpUrl().newBuilder()
                .addPathSegments("socket/websocket")
                .addQueryParameter("vsn", "2.0.0")
                .build()
                .toString()
    }
}

private inline fun <T, R> PushResult<T>.map(f: (T) -> R): PushResult<R> = when (this) {
    is PushResult.Ok -> PushResult.Ok(f(value))
    is PushResult.Rejected -> this
    PushResult.Unavailable -> PushResult.Unavailable
}
