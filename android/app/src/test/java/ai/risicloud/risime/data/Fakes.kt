package ai.risicloud.risime.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import ai.risicloud.risime.data.db.BehaviourDao
import ai.risicloud.risime.data.db.BehaviourEventEntity
import ai.risicloud.risime.data.db.LastMessage
import ai.risicloud.risime.data.db.MessageDao
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.db.SeenEventEntity
import ai.risicloud.risime.data.db.SyncDao
import ai.risicloud.risime.data.db.SyncStateEntity
import ai.risicloud.risime.data.db.UnreadCount
import ai.risicloud.risime.net.MsgSend
import ai.risicloud.risime.net.MsgSendReply
import ai.risicloud.risime.realtime.ConnectionState
import ai.risicloud.risime.realtime.PushResult
import ai.risicloud.risime.realtime.RealtimeClient
import ai.risicloud.risime.realtime.RealtimeSession

/** In-memory MessageDao with the same semantics as the Room queries. */
class FakeMessageDao : MessageDao {
    val rows = linkedMapOf<String, MessageEntity>()

    override suspend fun countAll(): Int = rows.size

    override suspend fun countIn(conversationId: String): Int = rows.values.count { it.conversationId == conversationId }

    override suspend fun insert(m: MessageEntity): Long {
        if (rows.containsKey(m.clientMsgId)) return -1
        if (m.messageId != null && rows.values.any { it.messageId == m.messageId }) return -1
        rows[m.clientMsgId] = m
        return rows.size.toLong()
    }

    override suspend fun moveSystemLineForward(clientMsgId: String, serverTs: String?, localTs: Long): Int {
        val r = rows[clientMsgId]?.takeIf { it.kind == MessageEntity.KIND_SYSTEM && it.localTs < localTs } ?: return 0
        rows[clientMsgId] = r.copy(serverTs = serverTs, localTs = localTs)
        return 1
    }

    override suspend fun byClientMsgId(clientMsgId: String) = rows[clientMsgId]
    override suspend fun byMessageId(messageId: String) = rows.values.firstOrNull { it.messageId == messageId }
    override suspend fun callLine(conversationId: String, callId: String) = rows.values.firstOrNull { it.conversationId == conversationId && it.callId == callId }
    override fun observeCallLines(): Flow<List<MessageEntity>> = flowOf(rows.values.filter { it.call }.sortedByDescending { it.localTs })
    override suspend fun updateCallLine(clientMsgId: String, body: String, systemJson: String): Int {
        val r = rows[clientMsgId] ?: return 0
        rows[clientMsgId] = r.copy(body = body, systemJson = systemJson)
        return 1
    }
    /** v6: the media state of an image row (the Room query joins `media`). */
    var mediaState: (String) -> String? = { null }

    override suspend fun pendingOutbox() =
        rows.values.filter { it.outgoing && it.status == "PENDING" && (!it.media || mediaState(it.clientMsgId) == "UPLOADED") }
            .sortedBy { it.localTs }

    override suspend fun setBlobId(clientMsgId: String, blobId: String?) {
        rows[clientMsgId]?.let { rows[clientMsgId] = it.copy(blobId = blobId) }
    }

    override suspend fun failPending(clientMsgId: String, reason: String): Int {
        val r = rows[clientMsgId]?.takeIf { it.status == "PENDING" } ?: return 0
        rows[clientMsgId] = r.copy(status = "FAILED", failReason = reason)
        return 1
    }

    override suspend fun delete(clientMsgId: String): Int = if (rows.remove(clientMsgId) != null) 1 else 0

    override suspend fun unackedIncoming() = rows.values
        .filter { !it.outgoing && it.messageId != null && it.ackedStatus != it.status && it.ackedStatus != MessageEntity.ACKED_PLAYED }
        .sortedBy { it.localTs }

    override suspend fun setPlayed(clientMsgId: String, at: Long): Int {
        val r = rows[clientMsgId]?.takeIf { !it.outgoing && it.voice && it.playedAt == null } ?: return 0
        rows[clientMsgId] = r.copy(playedAt = at, status = "READ")
        return 1
    }

    override suspend fun unackedPlayed() = rows.values
        .filter { !it.outgoing && it.voice && it.messageId != null && it.playedAt != null && it.ackedStatus != MessageEntity.ACKED_PLAYED }
        .sortedBy { it.localTs }

    override suspend fun setOutgoingPlayed(clientMsgId: String, at: Long): Int {
        val r = rows[clientMsgId]?.takeIf { it.outgoing } ?: return 0
        rows[clientMsgId] = r.copy(playedAt = r.playedAt ?: at, readAt = r.readAt ?: at, deliveredAt = r.deliveredAt ?: at)
        return 1
    }

    override suspend fun setGroupPlayed(messageId: String, played: Int): Int {
        val r = rows.values.firstOrNull { it.messageId == messageId && it.outgoing } ?: return 0
        rows[r.clientMsgId] = r.copy(receiptPlayed = played)
        return 1
    }

    override suspend fun voiceNotesIn(conversationId: String) = rows.values.filter { it.conversationId == conversationId && it.voice }.sortedBy { it.localTs }

    override suspend fun updateStatus(clientMsgId: String, status: String, messageId: String?, serverTs: String?, failReason: String?) {
        val r = rows[clientMsgId] ?: return
        rows[clientMsgId] = r.copy(
            status = status,
            messageId = r.messageId ?: messageId,
            serverTs = r.serverTs ?: serverTs,
            failReason = failReason,
        )
    }

    override suspend fun setGroupReceipt(messageId: String, delivered: Int, read: Int, of: Int, status: String): Int {
        val r = rows.values.firstOrNull { it.messageId == messageId && it.outgoing } ?: return 0
        rows[r.clientMsgId] = r.copy(receiptDelivered = delivered, receiptRead = read, receiptOf = of, status = status)
        return 1
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

    override suspend fun setE2ee(clientMsgId: String, e2ee: String?) {
        rows[clientMsgId]?.let { rows[clientMsgId] = it.copy(e2ee = e2ee) }
    }

    override suspend fun allowPlaintext(clientMsgId: String): Int {
        val r = rows[clientMsgId]?.takeIf { it.outgoing && it.messageId == null && (it.status == "PENDING" || it.status == "FAILED") } ?: return 0
        rows[clientMsgId] = r.copy(e2ee = MessageEntity.E2EE_PLAIN_OK, status = "PENDING", failReason = null)
        return 1
    }

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
    override fun searchIn(conversationId: String, pattern: String, limit: Int): Flow<List<MessageEntity>> = flowOf(emptyList())
    override fun imagesIn(conversationId: String, limit: Int): Flow<List<MessageEntity>> = flowOf(emptyList())

    override suspend fun setDeliveredAt(clientMsgId: String, at: Long): Int {
        val r = rows[clientMsgId]?.takeIf { it.outgoing } ?: return 0
        rows[clientMsgId] = r.copy(deliveredAt = r.deliveredAt ?: at)
        return 1
    }

    override suspend fun setReadAt(clientMsgId: String, at: Long): Int {
        val r = rows[clientMsgId]?.takeIf { it.outgoing } ?: return 0
        rows[clientMsgId] = r.copy(readAt = r.readAt ?: at, deliveredAt = r.deliveredAt ?: at)
        return 1
    }

    override suspend fun inConversation(conversationId: String, messageId: String) =
        rows.values.firstOrNull { it.messageId == messageId && it.conversationId == conversationId }

    override suspend fun recentConversations(limit: Int) =
        rows.values.groupBy { it.conversationId }.map { (c, l) -> ai.risicloud.risime.data.db.ConversationActivity(c, l.maxOf { it.localTs }) }
            .sortedByDescending { it.lastTs }.take(limit)
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

    /** §17.4 history pushes as (event, payload); [historyReply] answers them (default ok `{}`). */
    val historyPushes = mutableListOf<Pair<String, kotlinx.serialization.json.JsonObject>>()
    var historyReply: (String, kotlinx.serialization.json.JsonObject) -> PushResult<kotlinx.serialization.json.JsonObject> = { _, _ -> PushResult.Ok(kotlinx.serialization.json.JsonObject(emptyMap())) }

    override suspend fun history(event: String, payload: kotlinx.serialization.json.JsonElement): PushResult<kotlinx.serialization.json.JsonObject> {
        if (!connected) return PushResult.Unavailable
        historyPushes += event to (payload as kotlinx.serialization.json.JsonObject)
        return historyReply(event, payload)
    }

    override suspend fun sendMessage(msg: MsgSend): PushResult<MsgSendReply> {
        if (!connected) return PushResult.Unavailable
        sent += msg
        return sendReplies(msg)
    }

    val sentReactions = mutableListOf<ai.risicloud.risime.net.MsgSendReaction>()
    var reactionReplies: (ai.risicloud.risime.net.MsgSendReaction) -> PushResult<MsgSendReply> = { m ->
        PushResult.Ok(MsgSendReply("rid-${m.clientMsgId}", "dm:x", "2026-10-06T08:20:00.000Z"))
    }

    override suspend fun sendReaction(msg: ai.risicloud.risime.net.MsgSendReaction): PushResult<MsgSendReply> {
        if (!connected) return PushResult.Unavailable
        sentReactions += msg
        return reactionReplies(msg)
    }

    val sentCallSignals = mutableListOf<ai.risicloud.risime.net.CallSignalPush>()
    var callSignalReply: (ai.risicloud.risime.net.CallSignalPush) -> PushResult<ai.risicloud.risime.net.CallSignalReply> = { m ->
        PushResult.Ok(ai.risicloud.risime.net.CallSignalReply("cs-${m.clientMsgId}", "2026-10-06T08:20:00.000Z"))
    }

    override suspend fun sendCallSignal(msg: ai.risicloud.risime.net.CallSignalPush): PushResult<ai.risicloud.risime.net.CallSignalReply> {
        if (!connected) return PushResult.Unavailable
        sentCallSignals += msg
        return callSignalReply(msg)
    }

    val sentEncrypted = mutableListOf<ai.risicloud.risime.net.MsgSendE2ee>()
    var encryptedReplies: (ai.risicloud.risime.net.MsgSendE2ee) -> PushResult<MsgSendReply> = { m ->
        PushResult.Ok(MsgSendReply("mid-${m.clientMsgId}", "dm:x", "2026-10-06T08:15:30.456Z"))
    }

    override suspend fun sendEncrypted(msg: ai.risicloud.risime.net.MsgSendE2ee): PushResult<MsgSendReply> {
        if (!connected) return PushResult.Unavailable
        sentEncrypted += msg
        return encryptedReplies(msg)
    }

    val sentGroup = mutableListOf<ai.risicloud.risime.net.MsgSendGroup>()
    var groupReplies: (ai.risicloud.risime.net.MsgSendGroup) -> PushResult<MsgSendReply> = { m ->
        PushResult.Ok(MsgSendReply("gid-${m.clientMsgId}", m.conversationId, "2026-10-06T08:15:30.456Z"))
    }

    override suspend fun sendGroup(msg: ai.risicloud.risime.net.MsgSendGroup): PushResult<MsgSendReply> {
        if (!connected) return PushResult.Unavailable
        sentGroup += msg
        return groupReplies(msg)
    }

    /** §15.2 / §15.9 pushes. */
    val sentDeletes = mutableListOf<ai.risicloud.risime.net.MsgDelete>()
    var deleteReplies: (ai.risicloud.risime.net.MsgDelete) -> PushResult<ai.risicloud.risime.net.MsgDeleteReply> = { m ->
        PushResult.Ok(ai.risicloud.risime.net.MsgDeleteReply(if (m.scope == "me") null else "del-${m.clientMsgId}", m.conversationId, if (m.scope == "me") null else "2026-10-06T09:00:00.000Z", m.targets, emptyList()))
    }
    val clears = mutableListOf<ai.risicloud.risime.net.ChatClear>()

    override suspend fun deleteMessages(msg: ai.risicloud.risime.net.MsgDelete): PushResult<ai.risicloud.risime.net.MsgDeleteReply> {
        if (!connected) return PushResult.Unavailable
        sentDeletes += msg
        return deleteReplies(msg)
    }

    override suspend fun clearChat(msg: ai.risicloud.risime.net.ChatClear): PushResult<Unit> {
        if (!connected) return PushResult.Unavailable
        clears += msg
        return PushResult.Ok(Unit)
    }

    override suspend fun ack(messageIds: List<String>, status: String): PushResult<Unit> {
        if (!connected) return PushResult.Unavailable
        acks += messageIds to status
        return ackReply
    }

    override suspend fun refreshAuth(token: String): PushResult<ai.risicloud.risime.net.AuthRefreshReply> =
        if (connected) PushResult.Ok(ai.risicloud.risime.net.AuthRefreshReply("2026-10-07T08:20:00.000Z")) else PushResult.Unavailable

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

/** In-memory ReactionDao with the Room queries' semantics. */
class FakeReactionDao : ai.risicloud.risime.data.db.ReactionDao {
    val rows = linkedMapOf<List<String>, ai.risicloud.risime.data.db.ReactionEntity>()
    private fun k(c: String, t: String, r: String, e: String) = listOf(c, t, r, e)
    override suspend fun get(conv: String, target: String, reactor: String, emoji: String) = rows[k(conv, target, reactor, emoji)]
    override suspend fun upsert(r: ai.risicloud.risime.data.db.ReactionEntity) { rows[k(r.conversationId, r.targetMessageId, r.reactorUserId, r.emoji)] = r }
    override fun forConversation(conv: String) = kotlinx.coroutines.flow.flowOf(rows.values.filter { it.conversationId == conv })
    override suspend fun pending() = rows.values.filter { it.pending }.sortedBy { it.localTs }
    override suspend fun isReaction(messageId: String) = rows.values.count { it.confirmedMessageId == messageId }
    override suspend fun addsSince(since: Long) = rows.values.filter { it.op == "add" && !it.pending && it.localTs > since }
}

/** In-memory GroupDao (Room semantics; flows are snapshots). */
class FakeGroupDao : ai.risicloud.risime.data.db.GroupDao {
    val groups = linkedMapOf<String, ai.risicloud.risime.data.db.GroupEntity>()
    val members = linkedMapOf<Pair<String, String>, ai.risicloud.risime.data.db.GroupMemberEntity>()
    override suspend fun upsert(g: ai.risicloud.risime.data.db.GroupEntity) { groups[g.conversationId] = g }
    override suspend fun get(conv: String) = groups[conv]
    override fun observe(conv: String) = kotlinx.coroutines.flow.flowOf(groups[conv])
    override fun all() = kotlinx.coroutines.flow.flowOf(groups.values.toList())
    override suspend fun allNow() = groups.values.toList()
    override suspend fun upsertMembers(m: List<ai.risicloud.risime.data.db.GroupMemberEntity>) { m.forEach { members[it.conversationId to it.userId] = it } }
    override suspend fun members(conv: String) = members.values.filter { it.conversationId == conv }
    override fun observeMembers(conv: String) = kotlinx.coroutines.flow.flowOf(members.values.filter { it.conversationId == conv }.sortedBy { it.displayName.lowercase() })
    override fun observeAllMembers() = kotlinx.coroutines.flow.flowOf(members.values.toList())
    override suspend fun setMemberState(conv: String, userIds: List<String>, state: String) {
        userIds.forEach { id -> members[conv to id]?.let { members[conv to id] = it.copy(state = state) } }
    }
    override suspend fun setMemberRole(conv: String, userIds: List<String>, role: String) {
        userIds.forEach { id -> members[conv to id]?.let { members[conv to id] = it.copy(role = role) } }
    }
    override suspend fun delete(conv: String) { groups.remove(conv) }
}

/** In-memory GroupOpDao with the unique op_id index. */
class FakeGroupOpDao : ai.risicloud.risime.data.db.GroupOpDao {
    val rows = linkedMapOf<Long, ai.risicloud.risime.data.db.GroupOpEntity>()
    private var next = 1L
    override suspend fun insert(op: ai.risicloud.risime.data.db.GroupOpEntity): Long {
        if (op.opId != null && rows.values.any { it.opId == op.opId }) return -1
        val id = next++
        rows[id] = op.copy(id = id)
        return id
    }
    override suspend fun due(now: Long) = rows.values.filter { it.state == "queued" && it.nextAt <= now }.sortedBy { it.id }
    override suspend fun queued() = rows.values.filter { it.state == "queued" }.sortedBy { it.id }
    override suspend fun get(id: Long) = rows[id]
    override suspend fun update(op: ai.risicloud.risime.data.db.GroupOpEntity) { rows[op.id] = op }
    override fun observeQueued(conv: String) = kotlinx.coroutines.flow.flowOf(rows.values.filter { it.conversationId == conv && it.state == "queued" })
    override fun observe(id: Long) = kotlinx.coroutines.flow.flowOf(rows[id])
}

class FakeCallMarkDao : ai.risicloud.risime.data.db.CallMarkDao {
    val rows = java.util.concurrent.ConcurrentHashMap<String, ai.risicloud.risime.data.db.CallMarkEntity>()
    override suspend fun get(callId: String) = rows[callId]
    override suspend fun put(m: ai.risicloud.risime.data.db.CallMarkEntity) { rows[m.callId] = m }
    override suspend fun prune(before: Long): Int { val old = rows.values.filter { it.at < before }; old.forEach { rows.remove(it.callId) }; return old.size }
}
