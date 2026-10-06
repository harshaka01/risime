package lk.codegen.risime.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lk.codegen.risime.data.db.MessageDao
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.SeenEventEntity
import lk.codegen.risime.data.db.SyncDao
import lk.codegen.risime.data.db.SyncStateEntity
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.MessageData
import lk.codegen.risime.data.mls.MlsEngine
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.data.mls.MlsResult
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.MsgSend
import lk.codegen.risime.net.MsgSendE2ee
import lk.codegen.risime.net.StatusData
import lk.codegen.risime.net.conversationFor
import lk.codegen.risime.net.dmPeer
import lk.codegen.risime.net.isGroupConversation
import lk.codegen.risime.realtime.PushResult
import lk.codegen.risime.realtime.RealtimeClient
import lk.codegen.risime.realtime.RealtimeListener
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Runs a block in one DB transaction (Room's withTransaction in the app; inline in tests). */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

/**
 * Store-and-forward client side (RELEASE-0.1 A2): outbox, incoming dedupe, cursor, acks.
 * It is the realtime listener: every event goes through [onEvents] in order.
 */
class ChatEngine(
    private val messages: MessageDao,
    private val sync: SyncDao,
    private val tx: TransactionRunner,
    private val scope: CoroutineScope,
    private val realtime: () -> RealtimeClient,
    private val meId: suspend () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newClientMsgId: () -> String = { UUID.randomUUID().toString() },
    private val behaviour: BehaviourLog? = null,
    private val rateLimitRetryMs: Long = 10_000,
    /** Called after a new incoming message from this user id is stored (ends their "typing…"). */
    private val onIncomingFrom: (String) -> Unit = {},
    /** §10: e2ee events (null = no MLS core: e2ee events are skipped like a v1.6 app). */
    private val mls: MlsPipeline? = null,
    private val mlsEngine: () -> MlsEngine? = { null },
    /** §10.4: fetch missing commits for a conversation (stale_epoch / e2ee_required). */
    private val catchUp: suspend (conversationId: String) -> Unit = {},
    private val staleEpochRetries: Int = 3,
    /** §11.2 reaction state (null in tests that don't cover reactions). */
    private val reactionsDao: lk.codegen.risime.data.db.ReactionDao? = null,
    private val reactionDebounceMs: Long = 500,
    /** §12: false = a pre-groups app; `grp:` events are skipped (they still advance the cursor). */
    private val groupsEnabled: () -> Boolean = { false },
    /** §12.7 group_event / group_op / group_receipt (null = no groups). */
    private val groups: lk.codegen.risime.data.groups.GroupStore? = null,
    /** §12.6: fetches a referenced commit/Welcome before the event's transaction. */
    private val blobs: (suspend (lk.codegen.risime.net.BlobRef) -> BlobFetch)? = null,
    /** §12.8: this device can't follow the group any more (rejoin). */
    private val onUnrecoverable: (conversationId: String) -> Unit = {},
    /**
     * §13.3: the first `since: null` join has caught up (called at the start of that onLive): treat
     * everything up to now as already notified.
     */
    private val onFreshReplayDone: suspend () -> Unit = {},
) : RealtimeListener {
    /** §13.2: this join's `history_before` (in memory only; every join returns it). */
    @Volatile private var historyBefore: Instant? = null

    /** §13.3: a `since: null` join is replaying the inbox (no notifications until it is live). */
    @Volatile var replayingFresh: Boolean = false
        private set
    private val reactionStore = reactionsDao?.let { ReactionStore(it, clock) }
    private val reactionLock = Mutex()


    private val outboxLock = Mutex()
    private val ackLock = Mutex()

    // ---- RealtimeListener ----

    override suspend fun cursor(): String? = sync.cursor().also { if (it == null) replayingFresh = true }

    override suspend fun onHistoryBefore(ts: String?) {
        historyBefore = ts?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }

    override suspend fun onEvents(events: List<Event>) {
        if (events.isEmpty()) return
        val me = meId() ?: return
        var newIncoming = false
        for (e0 in events) {
            // §12.6: a referenced blob is fetched first, outside the ordered transaction. A transient
            // failure stops here without moving the cursor (the next sync redelivers from it).
            val e = when (val r = resolveBlobRefs(e0, me)) {
                null -> break
                else -> r
            }
            val applied = tx.run {
                if (sync.seenCount(e.eventId) > 0) return@run false
                // Unknown kinds and undecodable data are skipped but still advance the cursor.
                val incoming = if (!groupsEnabled() && isGroupEvent(e)) false else when (e.kind) {
                    Event.KIND_MESSAGE -> runCatching { e.messageData() }.getOrNull()?.let { md ->
                        if (md.encrypted) applyMls(me, e) else md.body?.let { applyMessage(me, md, it) } ?: false
                    } ?: false
                    Event.KIND_REACTION -> {
                        runCatching { e.reaction() }.getOrNull()?.let { r ->
                            applyReaction(r.conversationId, r.target, r.from, r.emoji, r.op, r.serverTs, r.messageId, r.clientMsgId)
                        }
                        false
                    }
                    Event.KIND_MLS_COMMIT, Event.KIND_MLS_WELCOME, Event.KIND_MLS_MEMBERSHIP ->
                        runCatching { applyMls(me, e) }.getOrDefault(false)
                    Event.KIND_STATUS -> {
                        runCatching { e.statusData() }.getOrNull()?.let { applyStatus(it) }
                        false
                    }
                    Event.KIND_GROUP_EVENT -> {
                        runCatching { e.groupEvent() }.getOrNull()?.let { groups?.applyEvent(e.eventId, it, me, historicalLocalTs(it.serverTs)) }
                        false
                    }
                    Event.KIND_GROUP_OP -> {
                        runCatching { e.groupOp() }.getOrNull()?.let { groups?.applyOp(it, me) }
                        false
                    }
                    Event.KIND_GROUP_RECEIPT -> {
                        runCatching { e.groupReceipt() }.getOrNull()?.let { groups?.applyReceipt(it) }
                        false
                    }
                    else -> false
                }
                sync.markSeen(SeenEventEntity(e.eventId))
                sync.setState(SyncStateEntity(0, e.eventId))
                incoming
            }
            newIncoming = newIncoming || applied
        }
        if (newIncoming) flushAcks()
    }

    /**
     * Inline a referenced commit/Welcome (§12.6). Returns the event to apply, or null on a transient
     * failure. A blob that is gone (404, bad hash) is left unresolved: the pipeline reports it as
     * unrecoverable.
     */
    private suspend fun resolveBlobRefs(e: Event, me: String): Event? {
        val fetch = blobs ?: return e
        val (field, ref) = when (e.kind) {
            Event.KIND_MLS_COMMIT -> runCatching { e.mlsCommit() }.getOrNull()?.takeIf { it.commit == null }?.commitRef?.let { "commit" to it }
            Event.KIND_MLS_WELCOME -> runCatching { e.mlsWelcome() }.getOrNull()
                ?.takeIf { w -> w.welcome == null && w.toDevices.any { it.equals(mlsEngine()?.deviceId, true) } }?.welcomeRef?.let { "welcome" to it }
            else -> null
        } ?: return e
        if (sync.seenCount(e.eventId) > 0) return e
        return when (val r = fetch(ref)) {
            is BlobFetch.Ok -> e.copy(data = kotlinx.serialization.json.JsonObject(e.data + (field to kotlinx.serialization.json.JsonPrimitive(java.util.Base64.getEncoder().encodeToString(r.bytes)))))
            BlobFetch.Gone -> e
            BlobFetch.Transient -> null
        }
    }

    private fun isGroupEvent(e: Event): Boolean {
        val conv = (e.data["conversation_id"] ?: e.data["group_id"]) as? kotlinx.serialization.json.JsonPrimitive
        return conv?.isString == true && isGroupConversation(conv.content)
    }

    /** Commits fetched by catch-up (GET …/commits): applied in order, each in its own transaction, no cursor move. */
    suspend fun applyOutOfBand(events: List<Event>) {
        val me = meId() ?: return
        var newIncoming = false
        for (e0 in events) {
            val e = resolveBlobRefs(e0, me) ?: return
            newIncoming = tx.run { runCatching { applyMls(me, e) }.getOrDefault(false) } || newIncoming
        }
        if (newIncoming) flushAcks()
    }

    override suspend fun onLive() {
        if (replayingFresh) {
            onFreshReplayDone()
            replayingFresh = false
        }
        flushOutbox()
        flushAcks()
    }

    override suspend fun onAuthFailed() = Unit // handled by the session owner via ConnectionState

    // ---- Applying events ----

    /**
     * §10.3: e2ee events through the MLS pipeline, inside this event's transaction. A group change
     * replays the conversation's pending events in arrival order. @return true if a new incoming
     * message was stored.
     */
    private suspend fun applyMls(me: String, e: Event): Boolean {
        val pipeline = mls ?: return false
        var incoming = false
        val results = mutableListOf(pipeline.apply(e, historyBefore))
        var i = 0
        while (i < results.size) {
            val r = results[i++]
            // §13.3: lost history is always visible (one deduplicated line per chat), never silent.
            if (r is MlsResult.BeforeInstall) upsertMarker(r.conversationId, SystemLine.HISTORY_GAP, r.serverTs)
            if (r is MlsResult.Dropped) r.conversationId?.let { upsertMarker(it, SystemLine.UNDECRYPTABLE, r.serverTs) }
            if (r is MlsResult.Plaintext) incoming = applyMessage(me, r.message, r.body) || incoming
            if (r is MlsResult.Reaction) {
                applyReaction(r.message.conversationId, r.target, r.message.from, r.emoji, r.op, r.message.serverTs, r.message.messageId, r.message.clientMsgId)
            }
            if (r is MlsResult.Unrecoverable) onUnrecoverable(r.conversationId)
            (r as? MlsResult.GroupChanged)?.let { gc ->
                val conv = gc.conversationId
                results += gc.extra
                if (isGroupConversation(conv)) {
                    groups?.onGroupStateChanged(conv, removedSelf = mlsEngine()?.group(conv) == null)
                    scope.launch { flushOutbox() } // messages waiting for this group's Welcome
                }
                results += pipeline.replay(conv, gc.joined)
            }
        }
        return incoming
    }

    private suspend fun upsertMarker(conversationId: String, action: String, serverTs: String?) {
        messages.upsertSystemLine(HistoryMarkers.row(conversationId, action, serverTs, clock()))
    }

    /**
     * §13.3 R5: a row restored from the inbox that is clearly historical (before `history_before`, or
     * more than [HISTORICAL_MS] older than the device clock) takes its local time from `server_ts`.
     * Null = live: keep the device clock.
     */
    private fun historicalLocalTs(serverTs: String?): Long? {
        val ts = HistoryMarkers.epochMs(serverTs) ?: return null
        val hb = historyBefore?.toEpochMilli()
        return ts.takeIf { (hb != null && it < hb) || it < clock() - HISTORICAL_MS }
    }

    /** @return true if a new incoming message was stored (needs a delivered ack). */
    private suspend fun applyMessage(me: String, m: MessageData, body: String): Boolean {
        if (messages.byMessageId(m.messageId) != null) return false
        val outgoing = m.from.equals(me, ignoreCase = true)
        messages.byClientMsgId(m.clientMsgId)?.let { row ->
            // §13.1 S-a: my own copy for a PENDING outbox row (the msg:send reply was lost): it reached the server.
            if (outgoing && row.outgoing && row.status == MessageStatus.PENDING.name) {
                messages.updateStatus(row.clientMsgId, MessageStatus.SENT.name, m.messageId, m.serverTs, null)
            }
            return false
        }
        val restored = historicalLocalTs(m.serverTs)
        // S-b: received history from before this install was handled by the old one: read, no acks, no badge.
        val preInstall = !outgoing && historyBefore?.let { hb -> HistoryMarkers.epochMs(m.serverTs)?.let { it < hb.toEpochMilli() } } == true
        messages.insert(
            MessageEntity(
                clientMsgId = m.clientMsgId,
                messageId = m.messageId,
                conversationId = m.conversationId,
                from = m.from,
                to = m.to ?: m.conversationId, // §12.7: group messages have no `to`
                body = body,
                serverTs = m.serverTs,
                localTs = restored ?: clock(),
                status = when {
                    outgoing -> MessageStatus.SENT
                    preInstall -> MessageStatus.READ
                    else -> MessageStatus.DELIVERED
                }.name,
                outgoing = outgoing,
                ackedStatus = if (preInstall) MessageStatus.READ.name else null,
            ),
        )
        if (outgoing || preInstall) return false
        onIncomingFrom(m.from)
        return true
    }

    private suspend fun applyStatus(s: StatusData) {
        val row = messages.byClientMsgId(s.clientMsgId) ?: messages.byMessageId(s.messageId) ?: return
        if (!row.outgoing) return
        val incoming = MessageStatus.fromWire(s.status) ?: return
        val current = MessageStatus.valueOf(row.status)
        val next = current.advance(incoming)
        if (next != current || row.messageId == null) {
            messages.updateStatus(row.clientMsgId, next.name, s.messageId, null, if (next == MessageStatus.FAILED) row.failReason else null)
        }
    }

    // ---- Outbox ----

    /**
     * Insert as pending first, then try to push. [target] is a conversation id (`dm:`/`grp:`) or,
     * for a DM, the peer's user id. Returns the client_msg_id.
     */
    suspend fun sendText(target: String, text: String): String? {
        val body = text.trim()
        // §11.1: the server counts graphemes (authoritative); the composer warns with ICU. Here only the byte cap.
        if (body.isEmpty() || body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) return null
        val me = meId() ?: return null
        val id = newClientMsgId()
        val conv = conversationFor(me, target)
        // §12 (Room v5): `to_id` holds the conversation id for groups.
        val to = dmPeer(conv, me) ?: conv
        val previous = messages.lastInConversation(conv)
        val now = clock()
        messages.insert(
            MessageEntity(
                clientMsgId = id,
                messageId = null,
                conversationId = conv,
                from = me,
                to = to,
                body = body,
                serverTs = null,
                localTs = now,
                status = MessageStatus.PENDING.name,
                outgoing = true,
            ),
        )
        behaviour?.messageSent(to, body.length, previous?.takeIf { !it.outgoing }?.let { now - it.localTs })
        scope.launch { flushOutbox() }
        return id
    }

    /** Push every pending message in local_ts order with its original client_msg_id. */
    /**
     * §10.4: e2ee conversations are encrypted **at send time** (inside a transaction, the ratchet
     * advances). stale_epoch → catch up, re-encrypt with the same client_msg_id, up to
     * [staleEpochRetries] times, then back off; e2ee_required → catch up and encrypt (never FAILED).
     */
    private suspend fun send(m: MessageEntity): PushResult<lk.codegen.risime.net.MsgSendReply> =
        sendPayload(m.conversationId, m.to, m.clientMsgId, m.localTs, { lk.codegen.risime.data.mls.MlsPayload.text(m.body) }) {
            realtime().sendMessage(MsgSend(m.clientMsgId, m.to, m.body, isoMillis(m.localTs)))
        }

    /** e2ee when the conversation has a group (envelope encrypted at send time), else [plain]; shared retry loops. */
    private suspend fun sendPayload(
        conv: String,
        to: String,
        clientMsgId: String,
        localTs: Long,
        envelope: () -> ByteArray,
        plain: suspend () -> PushResult<lk.codegen.risime.net.MsgSendReply>,
    ): PushResult<lk.codegen.risime.net.MsgSendReply> {
        var attempt = 0
        while (true) {
            val engine = mlsEngine()
            val group = engine?.group(conv)
            val r = if (isGroupConversation(conv)) {
                // §12.9: groups are e2ee-only; without the local group (Welcome not here yet) the message waits.
                if (engine == null || group == null) return PushResult.Rejected(WAITING_FOR_GROUP)
                val ct = tx.run { engine.encrypt(conv, envelope()) }
                realtime().sendGroup(
                    lk.codegen.risime.net.MsgSendGroup(clientMsgId, conv, java.util.Base64.getEncoder().encodeToString(ct), group.generation, group.epoch, isoMillis(localTs)),
                )
            } else if (engine != null && group != null) {
                val ct = tx.run { engine.encrypt(conv, envelope()) }
                realtime().sendEncrypted(
                    MsgSendE2ee(clientMsgId, to, java.util.Base64.getEncoder().encodeToString(ct), group.generation, group.epoch, isoMillis(localTs)),
                )
            } else {
                plain()
            }
            val reason = (r as? PushResult.Rejected)?.reason
            if (reason != AuthErrors.STALE_EPOCH && reason != AuthErrors.E2EE_REQUIRED) return r
            if (attempt++ >= staleEpochRetries) return PushResult.Rejected(RETRY_LATER)
            catchUp(conv)
            if (reason == AuthErrors.E2EE_REQUIRED && mlsEngine()?.group(conv) == null) return PushResult.Rejected(RETRY_LATER)
            if (isGroupConversation(conv) && mlsEngine()?.group(conv) == null) return PushResult.Rejected(WAITING_FOR_GROUP)
        }
    }

    // ---- Reactions (§11.2) ----

    /**
     * My tap: shown at once (pending), sent after [reactionDebounceMs] if nothing changed, as the
     * final state only. Only on messages that have a server message_id.
     */
    suspend fun react(target: String, targetMessageId: String, emoji: String, op: String) {
        val store = reactionStore ?: return
        val me = meId() ?: return
        val conv = conversationFor(me, target)
        val row = store.tap(conv, targetMessageId, me, emoji, op)
        scope.launch {
            delay(reactionDebounceMs)
            val cur = reactionsDao?.get(conv, targetMessageId, me, emoji)
            if (cur != null && cur.localTs == row.localTs) flushReactions(debounced = false)
        }
    }

    private suspend fun flushReactions(debounced: Boolean = true): Unit = reactionLock.withLock {
        val store = reactionStore ?: return@withLock
        val dao = reactionsDao ?: return@withLock
        val me = meId() ?: return@withLock
        for (r0 in dao.pending()) {
            if (debounced && clock() - r0.localTs < reactionDebounceMs) continue // its own timer sends it
            if (!store.needsSend(r0)) { store.settleNoSend(r0); continue }
            val r = store.assignId(r0, newClientMsgId)
            val peer = conversationPeer(r.conversationId, me)
            val body = lk.codegen.risime.net.ReactionBody(r.targetMessageId, r.emoji, r.op)
            val res = sendPayload(r.conversationId, peer, r.pendingClientMsgId!!, r.localTs, { lk.codegen.risime.data.mls.MlsPayload.reaction(body.target, body.emoji, body.op) }) {
                realtime().sendReaction(lk.codegen.risime.net.MsgSendReaction(r.pendingClientMsgId, peer, body, isoMillis(r.localTs)))
            }
            when (res) {
                is PushResult.Ok -> store.confirmOwn(r, res.value.serverTs, res.value.messageId)
                is PushResult.Rejected -> if (res.reason == WAITING_FOR_GROUP) {
                    continue
                } else if (res.reason == "rate_limited" || res.reason == RETRY_LATER) {
                    scope.launch { delay(rateLimitRetryMs); flushReactions() }
                    return@withLock
                } else {
                    store.revert(r) // unknown_target, invalid_emoji, not_friends, bad_request, …
                }
                PushResult.Unavailable -> return@withLock // retried on the next onLive()
            }
        }
    }

    /** The `to` of a send: the DM peer, or the conversation id itself for a group. */
    private fun conversationPeer(conv: String, me: String): String = dmPeer(conv, me) ?: conv

    private suspend fun applyReaction(conv: String, target: String, reactor: String, emoji: String, op: String, ts: String, messageId: String, clientMsgId: String?) {
        reactionStore?.applyConfirmed(conv, target, reactor, emoji, op, ts, messageId, clientMsgId)
    }

    suspend fun flushOutbox() {
        flushMessages()
        flushReactions()
    }

    private suspend fun flushMessages(): Unit = outboxLock.withLock {
        for (m in messages.pendingOutbox()) {
            when (val r = send(m)) {
                is PushResult.Ok -> {
                    val cur = messages.byClientMsgId(m.clientMsgId) ?: continue
                    val next = MessageStatus.valueOf(cur.status).advance(MessageStatus.SENT)
                    messages.updateStatus(m.clientMsgId, next.name, r.value.messageId, r.value.serverTs, null)
                }
                is PushResult.Rejected -> if (r.reason == WAITING_FOR_GROUP) {
                    continue // stays PENDING; retried when the group arrives (and on every onLive)
                } else if (r.reason == "rate_limited" || r.reason == RETRY_LATER) {
                    scope.launch {
                        delay(rateLimitRetryMs)
                        flushOutbox()
                    }
                    return@withLock
                } else {
                    messages.updateStatus(m.clientMsgId, MessageStatus.FAILED.name, null, null, r.reason)
                }
                PushResult.Unavailable -> return@withLock // retried on the next onLive()
            }
        }
    }

    /** FAILED → PENDING, then resend with the same client_msg_id. Returns false if it wasn't FAILED. */
    suspend fun retry(clientMsgId: String): Boolean {
        val row = messages.byClientMsgId(clientMsgId) ?: return false
        if (MessageStatus.valueOf(row.status).retry() == null) return false
        if (messages.retryFailed(clientMsgId) == 0) return false
        scope.launch { flushOutbox() }
        return true
    }

    /** Deletes a FAILED message (never accepted by the server). */
    suspend fun deleteFailed(clientMsgId: String): Boolean = messages.deleteFailed(clientMsgId) > 0

    // ---- Acks ----

    /** The chat screen is showing this conversation: mark incoming as read and ack. */
    suspend fun markConversationRead(conversationId: String) {
        if (messages.markIncomingRead(conversationId) > 0) flushAcks()
    }

    /** Send delivered/read acks the server hasn't confirmed yet. Safe to call any time. */
    suspend fun flushAcks(): Unit = ackLock.withLock {
        val todo = messages.unackedIncoming()
        for ((status, rows) in todo.groupBy { it.status }) {
            val wire = MessageStatus.valueOf(status).wire
            for (chunk in rows.chunked(ACK_BATCH)) {
                when (realtime().ack(chunk.mapNotNull { it.messageId }, wire)) {
                    is PushResult.Ok -> messages.setAcked(chunk.map { it.clientMsgId }, status)
                    is PushResult.Rejected -> messages.setAcked(chunk.map { it.clientMsgId }, status) // don't loop on a bad ack
                    PushResult.Unavailable -> return@withLock
                }
            }
        }
    }

    companion object {
        const val MAX_BODY = 4096
        const val MAX_BODY_BYTES = 16 * 1024

        /** Local only: stale_epoch/e2ee_required couldn't be resolved now; stays PENDING, retried later. */
        const val RETRY_LATER = "retry_later"

        /** Local only: a group message whose MLS group isn't here yet; stays PENDING without blocking the outbox. */
        const val WAITING_FOR_GROUP = "waiting_for_group"
        const val ACK_BATCH = 100

        /** §13.3: rows this much older than the device clock are restored history. */
        const val HISTORICAL_MS = 5 * 60_000L
        private val ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun isoMillis(epochMs: Long): String = ISO_MILLIS.format(Instant.ofEpochMilli(epochMs))
    }
}

/** §12.6 blob download outcome. */
sealed interface BlobFetch {
    class Ok(val bytes: ByteArray) : BlobFetch

    /** 404 / expired / size or SHA-256 mismatch: unrecoverable for this event. */
    data object Gone : BlobFetch

    /** Network or server trouble: retry later. */
    data object Transient : BlobFetch
}
