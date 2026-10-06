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
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.MsgSend
import lk.codegen.risime.net.MsgSendE2ee
import lk.codegen.risime.net.StatusData
import lk.codegen.risime.net.dmConversationId
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
) : RealtimeListener {

    private val outboxLock = Mutex()
    private val ackLock = Mutex()

    // ---- RealtimeListener ----

    override suspend fun cursor(): String? = sync.cursor()

    override suspend fun onEvents(events: List<Event>) {
        if (events.isEmpty()) return
        val me = meId() ?: return
        var newIncoming = false
        for (e in events) {
            val applied = tx.run {
                if (sync.seenCount(e.eventId) > 0) return@run false
                // Unknown kinds and undecodable data are skipped but still advance the cursor.
                val incoming = when (e.kind) {
                    Event.KIND_MESSAGE -> runCatching { e.messageData() }.getOrNull()?.let { md ->
                        if (md.encrypted) applyMls(me, e) else md.body?.let { applyMessage(me, md, it) } ?: false
                    } ?: false
                    Event.KIND_MLS_COMMIT, Event.KIND_MLS_WELCOME, Event.KIND_MLS_MEMBERSHIP ->
                        runCatching { applyMls(me, e) }.getOrDefault(false)
                    Event.KIND_STATUS -> {
                        runCatching { e.statusData() }.getOrNull()?.let { applyStatus(it) }
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

    override suspend fun onLive() {
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
        fun handle(r: MlsResult): String? = (r as? MlsResult.GroupChanged)?.conversationId
        val results = mutableListOf(pipeline.apply(e))
        var i = 0
        while (i < results.size) {
            val r = results[i++]
            if (r is MlsResult.Plaintext) incoming = applyMessage(me, r.message, r.body) || incoming
            handle(r)?.let { conv -> results += pipeline.replay(conv) }
        }
        return incoming
    }

    /** @return true if a new incoming message was stored (needs a delivered ack). */
    private suspend fun applyMessage(me: String, m: MessageData, body: String): Boolean {
        if (messages.byMessageId(m.messageId) != null) return false
        if (messages.byClientMsgId(m.clientMsgId) != null) return false
        val outgoing = m.from.equals(me, ignoreCase = true)
        messages.insert(
            MessageEntity(
                clientMsgId = m.clientMsgId,
                messageId = m.messageId,
                conversationId = m.conversationId,
                from = m.from,
                to = m.to,
                body = body,
                serverTs = m.serverTs,
                localTs = clock(),
                status = (if (outgoing) MessageStatus.SENT else MessageStatus.DELIVERED).name,
                outgoing = outgoing,
            ),
        )
        if (!outgoing) onIncomingFrom(m.from)
        return !outgoing
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

    /** Insert as pending first, then try to push. Returns the client_msg_id. */
    suspend fun sendText(peerId: String, text: String): String? {
        val body = text.trim()
        if (body.isEmpty() || body.length > MAX_BODY) return null
        val me = meId() ?: return null
        val id = newClientMsgId()
        val conv = dmConversationId(me, peerId)
        val previous = messages.lastInConversation(conv)
        val now = clock()
        messages.insert(
            MessageEntity(
                clientMsgId = id,
                messageId = null,
                conversationId = conv,
                from = me,
                to = peerId,
                body = body,
                serverTs = null,
                localTs = now,
                status = MessageStatus.PENDING.name,
                outgoing = true,
            ),
        )
        behaviour?.messageSent(peerId, body.length, previous?.takeIf { !it.outgoing }?.let { now - it.localTs })
        scope.launch { flushOutbox() }
        return id
    }

    /** Push every pending message in local_ts order with its original client_msg_id. */
    /**
     * §10.4: e2ee conversations are encrypted **at send time** (inside a transaction, the ratchet
     * advances). stale_epoch → catch up, re-encrypt with the same client_msg_id, up to
     * [staleEpochRetries] times, then back off; e2ee_required → catch up and encrypt (never FAILED).
     */
    private suspend fun send(m: MessageEntity): PushResult<lk.codegen.risime.net.MsgSendReply> {
        var attempt = 0
        while (true) {
            val engine = mlsEngine()
            val group = engine?.group(m.conversationId)
            val r = if (engine != null && group != null) {
                val ct = tx.run { engine.encrypt(m.conversationId, m.body.toByteArray()) }
                realtime().sendEncrypted(
                    MsgSendE2ee(m.clientMsgId, m.to, java.util.Base64.getEncoder().encodeToString(ct), group.generation, group.epoch, isoMillis(m.localTs)),
                )
            } else {
                realtime().sendMessage(MsgSend(m.clientMsgId, m.to, m.body, isoMillis(m.localTs)))
            }
            val reason = (r as? PushResult.Rejected)?.reason
            if (reason != AuthErrors.STALE_EPOCH && reason != AuthErrors.E2EE_REQUIRED) return r
            if (attempt++ >= staleEpochRetries) return PushResult.Rejected(RETRY_LATER)
            catchUp(m.conversationId)
            if (reason == AuthErrors.E2EE_REQUIRED && mlsEngine()?.group(m.conversationId) == null) return PushResult.Rejected(RETRY_LATER)
        }
    }

    suspend fun flushOutbox(): Unit = outboxLock.withLock {
        for (m in messages.pendingOutbox()) {
            when (val r = send(m)) {
                is PushResult.Ok -> {
                    val cur = messages.byClientMsgId(m.clientMsgId) ?: continue
                    val next = MessageStatus.valueOf(cur.status).advance(MessageStatus.SENT)
                    messages.updateStatus(m.clientMsgId, next.name, r.value.messageId, r.value.serverTs, null)
                }
                is PushResult.Rejected -> if (r.reason == "rate_limited" || r.reason == RETRY_LATER) {
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

        /** Local only: stale_epoch/e2ee_required couldn't be resolved now; stays PENDING, retried later. */
        const val RETRY_LATER = "retry_later"
        const val ACK_BATCH = 100
        private val ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun isoMillis(epochMs: Long): String = ISO_MILLIS.format(Instant.ofEpochMilli(epochMs))
    }
}
