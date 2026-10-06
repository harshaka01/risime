package lk.codegen.risime.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import lk.codegen.risime.data.db.BehaviourDao
import lk.codegen.risime.data.db.BehaviourEventEntity
import lk.codegen.risime.data.db.LastMessage
import lk.codegen.risime.data.db.MessageDao
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.SeenEventEntity
import lk.codegen.risime.data.db.SyncDao
import lk.codegen.risime.data.db.SyncStateEntity
import lk.codegen.risime.data.db.UnreadCount
import lk.codegen.risime.net.MsgSend
import lk.codegen.risime.net.MsgSendReply
import lk.codegen.risime.realtime.ConnectionState
import lk.codegen.risime.realtime.PushResult
import lk.codegen.risime.realtime.RealtimeClient
import lk.codegen.risime.realtime.RealtimeSession

/** In-memory MessageDao with the same semantics as the Room queries. */
class FakeMessageDao : MessageDao {
    val rows = linkedMapOf<String, MessageEntity>()

    override suspend fun insert(m: MessageEntity): Long {
        if (rows.containsKey(m.clientMsgId)) return -1
        if (m.messageId != null && rows.values.any { it.messageId == m.messageId }) return -1
        rows[m.clientMsgId] = m
        return rows.size.toLong()
    }

    override suspend fun byClientMsgId(clientMsgId: String) = rows[clientMsgId]
    override suspend fun byMessageId(messageId: String) = rows.values.firstOrNull { it.messageId == messageId }
    override suspend fun pendingOutbox() =
        rows.values.filter { it.outgoing && it.status == "PENDING" }.sortedBy { it.localTs }

    override suspend fun unackedIncoming() = rows.values
        .filter { !it.outgoing && it.messageId != null && it.ackedStatus != it.status }
        .sortedBy { it.localTs }

    override suspend fun updateStatus(clientMsgId: String, status: String, messageId: String?, serverTs: String?, failReason: String?) {
        val r = rows[clientMsgId] ?: return
        rows[clientMsgId] = r.copy(
            status = status,
            messageId = r.messageId ?: messageId,
            serverTs = r.serverTs ?: serverTs,
            failReason = failReason,
        )
    }

    override suspend fun setAcked(clientMsgIds: List<String>, acked: String) {
        clientMsgIds.forEach { id -> rows[id]?.let { rows[id] = it.copy(ackedStatus = acked) } }
    }

    override suspend fun markIncomingRead(conversationId: String): Int {
        var n = 0
        rows.values.toList().forEach {
            if (it.conversationId == conversationId && !it.outgoing && it.status != "READ") {
                rows[it.clientMsgId] = it.copy(status = "READ")
                n++
            }
        }
        return n
    }

    override fun conversation(conversationId: String): Flow<List<MessageEntity>> =
        flowOf(rows.values.filter { it.conversationId == conversationId }.sortedBy { it.localTs })

    override suspend fun lastInConversation(conversationId: String) =
        rows.values.filter { it.conversationId == conversationId }.maxByOrNull { it.localTs }

    override fun lastMessages(): Flow<List<LastMessage>> = flowOf(emptyList())

    override fun unreadCounts(): Flow<List<UnreadCount>> = flowOf(
        rows.values.filter { !it.outgoing && it.status != "READ" }.groupingBy { it.conversationId }.eachCount()
            .map { (k, v) -> UnreadCount(k, v) },
    )

    override suspend fun retryFailed(clientMsgId: String): Int {
        val r = rows[clientMsgId]?.takeIf { it.status == "FAILED" } ?: return 0
        rows[clientMsgId] = r.copy(status = "PENDING", failReason = null)
        return 1
    }

    override suspend fun deleteFailed(clientMsgId: String): Int =
        if (rows[clientMsgId]?.status == "FAILED") { rows.remove(clientMsgId); 1 } else 0

    override suspend fun unreadIncoming(): List<MessageEntity> =
        rows.values.filter { !it.outgoing && it.status != "READ" }.sortedBy { it.localTs }

    override fun search(pattern: String, limit: Int): Flow<List<MessageEntity>> = flowOf(emptyList())
}

class FakeSyncDao : SyncDao {
    var last: String? = null
    val seen = mutableSetOf<String>()
    override suspend fun cursor() = last
    override suspend fun setState(s: SyncStateEntity) { last = s.lastEventId }
    override suspend fun seenCount(eventId: String) = if (eventId in seen) 1 else 0
    override suspend fun markSeen(e: SeenEventEntity) { seen += e.eventId }
}

class FakeBehaviourDao : BehaviourDao {
    val rows = mutableListOf<BehaviourEventEntity>()
    override suspend fun insert(e: BehaviourEventEntity): Long {
        rows += e.copy(id = rows.size + 1L)
        return rows.size.toLong()
    }
    override suspend fun all() = rows.toList()
}

/** Scripted RealtimeClient: records pushes and answers from [sendReplies] / [ackReply]. */
class FakeRealtime : RealtimeClient {
    override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Live)
    val sent = mutableListOf<MsgSend>()
    val acks = mutableListOf<Pair<List<String>, String>>()
    var connected = true
    var sendReplies: (MsgSend) -> PushResult<MsgSendReply> = { m ->
        PushResult.Ok(MsgSendReply("mid-${m.clientMsgId}", "dm:x", "2026-10-06T08:15:30.456Z"))
    }
    var ackReply: PushResult<Unit> = PushResult.Ok(Unit)

    override fun start(session: RealtimeSession) = Unit
    override fun stop() = Unit

    override suspend fun sendMessage(msg: MsgSend): PushResult<MsgSendReply> {
        if (!connected) return PushResult.Unavailable
        sent += msg
        return sendReplies(msg)
    }

    override suspend fun ack(messageIds: List<String>, status: String): PushResult<Unit> {
        if (!connected) return PushResult.Unavailable
        acks += messageIds to status
        return ackReply
    }

    override suspend fun refreshAuth(token: String): PushResult<lk.codegen.risime.net.AuthRefreshReply> =
        if (connected) PushResult.Ok(lk.codegen.risime.net.AuthRefreshReply("2026-10-07T08:20:00.000Z")) else PushResult.Unavailable

    val watches = mutableListOf<Set<String>>()
    val typings = mutableListOf<Pair<String, Boolean>>()

    override fun setWatch(userIds: Set<String>) {
        watches += userIds
    }

    override suspend fun typing(to: String, typing: Boolean): PushResult<Unit> {
        if (!connected) return PushResult.Unavailable
        typings += to to typing
        return PushResult.Ok(Unit)
    }
}
