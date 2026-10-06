package lk.codegen.risime.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: MessageEntity): Long

    /** Every row (history recovery: a cursor with no messages means lost data). */
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countAll(): Int

    /** §13.3 marker lines: move a local system row forward only (never earlier). */
    @Query(
        "UPDATE messages SET server_ts = :serverTs, local_ts = :localTs " +
            "WHERE client_msg_id = :clientMsgId AND kind = 'system' AND local_ts < :localTs",
    )
    suspend fun moveSystemLineForward(clientMsgId: String, serverTs: String?, localTs: Long): Int

    /** §13.3: one row per deterministic id (`sys:history:<conv>`), forward-only position. */
    @androidx.room.Transaction
    suspend fun upsertSystemLine(m: MessageEntity) {
        if (insert(m) == -1L) moveSystemLineForward(m.clientMsgId, m.serverTs, m.localTs)
    }

    @Query("SELECT * FROM messages WHERE client_msg_id = :clientMsgId")
    suspend fun byClientMsgId(clientMsgId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE message_id = :messageId")
    suspend fun byMessageId(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE outgoing = 1 AND status = 'PENDING' ORDER BY local_ts ASC")
    suspend fun pendingOutbox(): List<MessageEntity>

    /** Incoming messages whose local status is ahead of what the server has confirmed. */
    @Query(
        "SELECT * FROM messages WHERE outgoing = 0 AND message_id IS NOT NULL " +
            "AND (acked_status IS NULL OR acked_status != status) ORDER BY local_ts ASC",
    )
    suspend fun unackedIncoming(): List<MessageEntity>

    @Query(
        "UPDATE messages SET status = :status, message_id = COALESCE(message_id, :messageId), " +
            "server_ts = COALESCE(server_ts, :serverTs), fail_reason = :failReason WHERE client_msg_id = :clientMsgId",
    )
    suspend fun updateStatus(clientMsgId: String, status: String, messageId: String?, serverTs: String?, failReason: String?)

    /** §12.7 group_receipt on my own message (status only moves forward: the caller decides). */
    @Query(
        "UPDATE messages SET receipt_delivered = :delivered, receipt_read = :read, receipt_of = :of, status = :status " +
            "WHERE message_id = :messageId AND outgoing = 1",
    )
    suspend fun setGroupReceipt(messageId: String, delivered: Int, read: Int, of: Int, status: String): Int

    @Query("UPDATE messages SET acked_status = :acked WHERE client_msg_id IN (:clientMsgIds)")
    suspend fun setAcked(clientMsgIds: List<String>, acked: String)

    @Query(
        "UPDATE messages SET status = 'READ' WHERE conversation_id = :conversationId AND outgoing = 0 AND status != 'READ'",
    )
    suspend fun markIncomingRead(conversationId: String): Int

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY local_ts ASC")
    fun conversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY local_ts DESC LIMIT 1")
    suspend fun lastInConversation(conversationId: String): MessageEntity?

    @Query(
        "SELECT conversation_id, body, local_ts, outgoing, status, from_id, kind FROM messages m " +
            "WHERE local_ts = (SELECT MAX(local_ts) FROM messages WHERE conversation_id = m.conversation_id) " +
            "GROUP BY conversation_id",
    )
    fun lastMessages(): Flow<List<LastMessage>>

    /** Incoming messages not yet read, per conversation (only conversations with unread > 0). */
    @Query(
        "SELECT conversation_id, COUNT(*) AS unread FROM messages " +
            "WHERE outgoing = 0 AND status != 'READ' GROUP BY conversation_id",
    )
    fun unreadCounts(): Flow<List<UnreadCount>>

    /** Push: unread incoming messages (notifications are built from these, never from the payload). */
    @Query("SELECT * FROM messages WHERE outgoing = 0 AND status != 'READ' ORDER BY local_ts ASC")
    suspend fun unreadIncoming(): List<MessageEntity>

    /** FAILED → PENDING (local only; the outbox resends with the same client_msg_id). */
    @Query("UPDATE messages SET status = 'PENDING', fail_reason = NULL WHERE client_msg_id = :clientMsgId AND status = 'FAILED'")
    suspend fun retryFailed(clientMsgId: String): Int

    /** Only messages the server never accepted can be deleted (they exist nowhere else). */
    @Query("DELETE FROM messages WHERE client_msg_id = :clientMsgId AND status = 'FAILED'")
    suspend fun deleteFailed(clientMsgId: String): Int

    /** Local search (decision 009: LIKE, no FTS). [pattern] comes from likePattern (backslash escapes). */
    @Query(
        "SELECT * FROM messages WHERE kind != 'system' AND body LIKE :pattern ESCAPE '\\' ORDER BY local_ts DESC LIMIT :limit",
    )
    fun search(pattern: String, limit: Int): Flow<List<MessageEntity>>
}

@Dao
interface SyncDao {
    @Query("SELECT last_event_id FROM sync_state WHERE id = 0")
    suspend fun cursor(): String?

    @Upsert
    suspend fun setState(s: SyncStateEntity)

    @Query("SELECT COUNT(*) FROM seen_events WHERE event_id = :eventId")
    suspend fun seenCount(eventId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(e: SeenEventEntity)
}

/** Wipes chat data on logout. The behaviour log is kept (it is the device owner's, never uploaded). */
@Dao
interface WipeDao {
    /** Every chat table in one transaction (logout, server change, confirmed other account only). */
    @androidx.room.Transaction
    suspend fun allChatData() {
        messages()
        contacts()
        syncState()
        seenEvents()
        mlsKv()
        mlsPending()
        reactions()
        groups()
        groupMembers()
        groupOps()
    }

    @Query("DELETE FROM messages")
    suspend fun messages()

    @Query("DELETE FROM contacts")
    suspend fun contacts()

    @Query("DELETE FROM sync_state")
    suspend fun syncState()

    @Query("DELETE FROM seen_events")
    suspend fun seenEvents()

    /** Logout wipes MLS state too (the device is removed on the server). */
    @Query("DELETE FROM mls_kv")
    suspend fun mlsKv()

    @Query("DELETE FROM mls_pending")
    suspend fun mlsPending()

    @Query("DELETE FROM reactions")
    suspend fun reactions()

    @Query("DELETE FROM `groups`")
    suspend fun groups()

    @Query("DELETE FROM group_members")
    suspend fun groupMembers()

    @Query("DELETE FROM group_ops")
    suspend fun groupOps()
}

@Dao
interface GroupDao {
    @Upsert
    suspend fun upsert(g: GroupEntity)

    @Query("SELECT * FROM `groups` WHERE conversation_id = :conv")
    suspend fun get(conv: String): GroupEntity?

    @Query("SELECT * FROM `groups` WHERE conversation_id = :conv")
    fun observe(conv: String): Flow<GroupEntity?>

    @Query("SELECT * FROM `groups`")
    fun all(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM `groups`")
    suspend fun allNow(): List<GroupEntity>

    @Upsert
    suspend fun upsertMembers(m: List<GroupMemberEntity>)

    @Query("SELECT * FROM group_members WHERE conversation_id = :conv")
    suspend fun members(conv: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE conversation_id = :conv ORDER BY display_name COLLATE NOCASE")
    fun observeMembers(conv: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members")
    fun observeAllMembers(): Flow<List<GroupMemberEntity>>

    @Query("UPDATE group_members SET state = :state WHERE conversation_id = :conv AND user_id IN (:userIds)")
    suspend fun setMemberState(conv: String, userIds: List<String>, state: String)

    @Query("UPDATE group_members SET role = :role WHERE conversation_id = :conv AND user_id IN (:userIds)")
    suspend fun setMemberRole(conv: String, userIds: List<String>, role: String)

    @Query("DELETE FROM `groups` WHERE conversation_id = :conv")
    suspend fun delete(conv: String)
}

@Dao
interface GroupOpDao {
    /** -1 when [GroupOpEntity.opId] is already queued (a repeated group_op naming). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(op: GroupOpEntity): Long

    @Query("SELECT * FROM group_ops WHERE state = 'queued' AND next_at <= :now ORDER BY id ASC")
    suspend fun due(now: Long): List<GroupOpEntity>

    @Query("SELECT * FROM group_ops WHERE state = 'queued' ORDER BY id ASC")
    suspend fun queued(): List<GroupOpEntity>

    @Query("SELECT * FROM group_ops WHERE id = :id")
    suspend fun get(id: Long): GroupOpEntity?

    @androidx.room.Update
    suspend fun update(op: GroupOpEntity)

    @Query("SELECT * FROM group_ops WHERE conversation_id = :conv AND state = 'queued'")
    fun observeQueued(conv: String): Flow<List<GroupOpEntity>>

    @Query("SELECT * FROM group_ops WHERE id = :id")
    fun observe(id: Long): Flow<GroupOpEntity?>
}

@Dao
interface ReactionDao {
    @Query("SELECT * FROM reactions WHERE conversation_id = :conv AND target_message_id = :target AND reactor_user_id = :reactor AND emoji = :emoji")
    suspend fun get(conv: String, target: String, reactor: String, emoji: String): ReactionEntity?

    @Upsert
    suspend fun upsert(r: ReactionEntity)

    @Query("SELECT * FROM reactions WHERE conversation_id = :conv")
    fun forConversation(conv: String): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM reactions WHERE pending = 1 ORDER BY local_ts ASC")
    suspend fun pending(): List<ReactionEntity>

    /** §11.2: a reaction's own message_id (reactions to reactions are ignored). */
    @Query("SELECT COUNT(*) FROM reactions WHERE confirmed_message_id = :messageId")
    suspend fun isReaction(messageId: String): Int

    /** Notifications: effective adds that changed after [since] (filtered to my messages by the caller). */
    @Query("SELECT * FROM reactions WHERE op = 'add' AND pending = 0 AND local_ts > :since")
    suspend fun addsSince(since: Long): List<ReactionEntity>
}

@Dao
interface MlsPendingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(e: MlsPendingEntity)

    /** In arrival (= inbox) order. */
    @Query("SELECT * FROM mls_pending WHERE conversation_id = :conversationId ORDER BY seq ASC")
    suspend fun forConversation(conversationId: String): List<MlsPendingEntity>

    @Query("DELETE FROM mls_pending WHERE event_id = :eventId")
    suspend fun remove(eventId: String)

    /** §13.3: the rows [dropOlderGenerations] would delete (read first: they count as pre-install). */
    @Query("SELECT * FROM mls_pending WHERE conversation_id = :conversationId AND generation < :generation ORDER BY seq ASC")
    suspend fun olderGenerations(conversationId: String, generation: Long): List<MlsPendingEntity>

    @Query("DELETE FROM mls_pending WHERE conversation_id = :conversationId AND generation < :generation")
    suspend fun dropOlderGenerations(conversationId: String, generation: Long)
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY registered DESC, display_name COLLATE NOCASE ASC")
    fun all(): Flow<List<ContactEntity>>

    /** §9: everyone stops being a friend, then the fresh list is upserted (same transaction). */
    @Query("UPDATE contacts SET friend = 0, registered = 0")
    suspend fun unfriendAll()

    @androidx.room.Transaction
    suspend fun replaceFriends(friends: List<ContactEntity>) {
        unfriendAll()
        upsertAll(friends)
    }

    @Query("SELECT * FROM contacts WHERE user_id = :userId LIMIT 1")
    suspend fun byUserId(userId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE user_id = :userId LIMIT 1")
    fun observeByUserId(userId: String): Flow<ContactEntity?>

    @Query("DELETE FROM contacts")
    suspend fun clear()

    @Upsert
    suspend fun upsertAll(contacts: List<ContactEntity>)
}

@Dao
interface BehaviourDao {
    @Insert
    suspend fun insert(e: BehaviourEventEntity): Long

    @Query("SELECT * FROM behaviour_events ORDER BY id ASC")
    suspend fun all(): List<BehaviourEventEntity>
}
