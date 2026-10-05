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
import lk.codegen.risime.net.MsgSend
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
                    Event.KIND_MESSAGE -> runCatching { e.messageData() }.getOrNull()?.let { applyMessage(me, it) } ?: false
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

    /** @return true if a new incoming message was stored (needs a delivered ack). */
    private suspend fun applyMessage(me: String, m: MessageData): Boolean {
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
                body = m.body,
                serverTs = m.serverTs,
                localTs = clock(),
                status = (if (outgoing) MessageStatus.SENT else MessageStatus.DELIVERED).name,
                outgoing = outgoing,
            ),
        )
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
    suspend fun flushOutbox(): Unit = outboxLock.withLock {
        for (m in messages.pendingOutbox()) {
            val req = MsgSend(m.clientMsgId, m.to, m.body, isoMillis(m.localTs))
            when (val r = realtime().sendMessage(req)) {
                is PushResult.Ok -> {
                    val cur = messages.byClientMsgId(m.clientMsgId) ?: continue
                    val next = MessageStatus.valueOf(cur.status).advance(MessageStatus.SENT)
                    messages.updateStatus(m.clientMsgId, next.name, r.value.messageId, r.value.serverTs, null)
                }
                is PushResult.Rejected -> if (r.reason == "rate_limited") {
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
        const val ACK_BATCH = 100
        private val ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun isoMillis(epochMs: Long): String = ISO_MILLIS.format(Instant.ofEpochMilli(epochMs))
    }
}
