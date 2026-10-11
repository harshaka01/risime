package lk.codegen.risime.data.deletes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeReactionDao
import lk.codegen.risime.data.db.ChatStateEntity
import lk.codegen.risime.data.db.DeleteDao
import lk.codegen.risime.data.db.DeleteOutboxEntity
import lk.codegen.risime.data.db.DeletedIdEntity
import lk.codegen.risime.data.db.MessageEntity

/** In-memory DeleteDao over the fake message and reaction tables (same semantics as the Room SQL). */
class FakeDeleteDao(private val messages: FakeMessageDao, private val reactions: FakeReactionDao? = null) : DeleteDao {
    val deletedIds = linkedMapOf<String, DeletedIdEntity>()
    val outbox = linkedMapOf<String, DeleteOutboxEntity>()
    val chatStates = MutableStateFlow<Map<String, ChatStateEntity>>(emptyMap())

    override suspend fun putDeletedId(d: DeletedIdEntity) { deletedIds[d.messageId] = d }
    override suspend fun deletedId(messageId: String) = deletedIds[messageId]
    override suspend fun removeDeletedId(messageId: String) { deletedIds.remove(messageId) }
    override suspend fun pruneDeletedIds(before: Long): Int {
        val old = deletedIds.values.filter { it.at < before }.map { it.messageId }
        old.forEach { deletedIds.remove(it) }
        return old.size
    }

    override suspend fun tombstone(clientMsgId: String, by: String, byAdmin: Boolean, at: Long): Int {
        val r = messages.rows[clientMsgId] ?: return 0
        messages.rows[clientMsgId] = r.copy(
            kind = MessageEntity.KIND_DELETED, body = "", systemJson = null, blobId = null, forwardHops = null, replyToMessageId = null, replyToFrom = null, deletedBy = by, deletedByAdmin = byAdmin,
            deletedAt = at, deleteState = null, deleteUnverified = false, receiptDelivered = null, receiptRead = null, receiptOf = null,
            failReason = null, status = if (!r.outgoing) "READ" else r.status, ackedStatus = if (!r.outgoing) "READ" else r.ackedStatus,
        )
        return 1
    }

    override suspend fun setDeleteState(clientMsgIds: List<String>, state: String?) {
        clientMsgIds.forEach { id -> messages.rows[id]?.let { messages.rows[id] = it.copy(deleteState = state) } }
    }

    override suspend fun markUnverified(conversationId: String, messageIds: List<String>): Int {
        var n = 0
        messages.rows.values.toList().forEach {
            if (it.messageId in messageIds && it.conversationId == conversationId && it.kind != MessageEntity.KIND_DELETED) {
                messages.rows[it.clientMsgId] = it.copy(deleteUnverified = true); n++
            }
        }
        return n
    }

    override suspend fun countAttempt(clientMsgId: String) {
        messages.rows[clientMsgId]?.let { messages.rows[clientMsgId] = it.copy(sendAttempts = it.sendAttempts + 1) }
    }

    override suspend fun conversationRows(conversationId: String) = messages.rows.values.filter { it.conversationId == conversationId }
    override suspend fun deleting() = messages.rows.values.filter { it.deleteState != null }

    override suspend fun deleteReactions(messageIds: List<String>) {
        reactions?.rows?.entries?.removeAll { it.value.targetMessageId in messageIds }
    }

    override suspend fun deleteConversationReactions(conversationId: String) {
        reactions?.rows?.entries?.removeAll { it.value.conversationId == conversationId }
    }

    /** §33.11 the star rows (the purge removes them in the same transaction). */
    val stars = linkedMapOf<String, lk.codegen.risime.data.db.StarEntity>()

    override suspend fun deleteStars(messageIds: List<String>) {
        stars.keys.removeAll(messageIds.toSet())
    }

    override suspend fun deleteConversationStars(conversationId: String) {
        stars.entries.removeAll { it.value.conversationId == conversationId }
    }

    override suspend fun queue(o: DeleteOutboxEntity): Long {
        if (outbox.containsKey(o.clientMsgId)) return -1
        outbox[o.clientMsgId] = o
        return 1
    }

    override suspend fun queued() = outbox.values.filter { it.state == DeleteOutboxEntity.QUEUED }.sortedBy { it.createdAt }
    override suspend fun outboxRow(clientMsgId: String) = outbox[clientMsgId]
    override suspend fun updateOutbox(o: DeleteOutboxEntity) { outbox[o.clientMsgId] = o }
    override suspend fun removeOutbox(clientMsgId: String) { outbox.remove(clientMsgId) }

    override suspend fun putChatState(s: ChatStateEntity) { chatStates.value = chatStates.value + (s.conversationId to s) }
    override suspend fun chatState(conversationId: String) = chatStates.value[conversationId]
    override fun observeChatStates(): Flow<List<ChatStateEntity>> = MutableStateFlow(chatStates.value.values.toList())
    override suspend fun unhide(conversationId: String): Int {
        val s = chatStates.value[conversationId]?.takeIf { it.hidden } ?: return 0
        putChatState(s.copy(hidden = false))
        return 1
    }
}
