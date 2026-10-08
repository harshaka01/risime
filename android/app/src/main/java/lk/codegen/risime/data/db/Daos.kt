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

    /** Rows of one conversation (the §24.14 per-conversation and per-chat upgrade counts). */
    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun countIn(conversationId: String): Int

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

    /** §16.6: the one call-history line of [callId] in a conversation. */
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND call_id = :callId LIMIT 1")
    suspend fun callLine(conversationId: String, callId: String): MessageEntity?

    /** The Calls tab: every 1:1 call-history line, newest first (group lines are skipped by the reader). */
    @Query("SELECT * FROM messages WHERE kind = 'call' AND conversation_id NOT LIKE 'grp:%' ORDER BY local_ts DESC LIMIT 500")
    fun observeCallLines(): kotlinx.coroutines.flow.Flow<List<MessageEntity>>

    /** §20.4: a group call's line changes with `ended` (or a `status` that says the room is gone). */
    @Query("UPDATE messages SET body = :body, system_json = :systemJson WHERE client_msg_id = :clientMsgId")
    suspend fun updateCallLine(clientMsgId: String, body: String, systemJson: String): Int

    /**
     * §14.7 (android R5): an image row is sent only once its blob reference is stored; until then
     * (uploading, or after a failed upload) it is skipped and never blocks the rows behind it.
     */
    @Query(
        "SELECT m.* FROM messages m LEFT JOIN media x ON x.client_msg_id = m.client_msg_id " +
            "WHERE m.outgoing = 1 AND m.status = 'PENDING' AND (m.kind != 'image' OR x.state = 'UPLOADED') ORDER BY m.local_ts ASC",
    )
    suspend fun pendingOutbox(): List<MessageEntity>

    @Query("UPDATE messages SET blob_id = :blobId WHERE client_msg_id = :clientMsgId")
    suspend fun setBlobId(clientMsgId: String, blobId: String?)

    /** An outgoing image whose upload failed: FAILED with the reason (Retry / Delete). */
    @Query("UPDATE messages SET status = 'FAILED', fail_reason = :reason WHERE client_msg_id = :clientMsgId AND status = 'PENDING'")
    suspend fun failPending(clientMsgId: String, reason: String): Int

    @Query("DELETE FROM messages WHERE client_msg_id = :clientMsgId")
    suspend fun delete(clientMsgId: String): Int

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
        "SELECT conversation_id, body, local_ts, outgoing, status, from_id, kind, deleted_by, deleted_by_admin, delete_state FROM messages m " +
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

    /** Local search (decision 009: LIKE, no FTS). [pattern] comes from likePattern (backslash escapes). Tombstones and system lines never match (§15.6). */
    @Query(
        "SELECT * FROM messages WHERE kind IN ('text', 'image') AND delete_state IS NULL AND body LIKE :pattern ESCAPE '\\' ORDER BY local_ts DESC LIMIT :limit",
    )
    fun search(pattern: String, limit: Int): Flow<List<MessageEntity>>

    /** §24.9 search inside one chat's current tab: one conversation only (the same rules as [search]). */
    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationId AND kind IN ('text', 'image') AND delete_state IS NULL " +
            "AND body LIKE :pattern ESCAPE '\\' ORDER BY local_ts DESC LIMIT :limit",
    )
    fun searchIn(conversationId: String, pattern: String, limit: Int): Flow<List<MessageEntity>>

    /** §24.9 chat info media, per tab (one conversation): photos newest first. */
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND kind = 'image' AND delete_state IS NULL ORDER BY local_ts DESC LIMIT :limit")
    fun imagesIn(conversationId: String, limit: Int): Flow<List<MessageEntity>>
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
        media()
        deletedIds()
        deleteOutbox()
        chatState()
        callMarks()
        historyGaps()
        historyRequests()
        historyParts()
        historyProvides()
        profilePhotos()
        profilePhotoConvs()
        chatTabs()
        chatPrefs()
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

    @Query("DELETE FROM media")
    suspend fun media()

    @Query("DELETE FROM deleted_ids")
    suspend fun deletedIds()

    @Query("DELETE FROM delete_outbox")
    suspend fun deleteOutbox()

    @Query("DELETE FROM chat_state")
    suspend fun chatState()

    @Query("DELETE FROM call_marks")
    suspend fun callMarks()

    @Query("DELETE FROM history_gap")
    suspend fun historyGaps()

    @Query("DELETE FROM history_requests")
    suspend fun historyRequests()

    @Query("DELETE FROM history_parts")
    suspend fun historyParts()

    @Query("DELETE FROM history_provides")
    suspend fun historyProvides()

    @Query("DELETE FROM profile_photos")
    suspend fun profilePhotos()

    @Query("DELETE FROM profile_photo_convs")
    suspend fun profilePhotoConvs()

    @Query("DELETE FROM chat_tabs")
    suspend fun chatTabs()

    @Query("DELETE FROM chat_prefs")
    suspend fun chatPrefs()
}

/** v7 (§15): tombstones, hidden tombstones, the delete outbox and Clear/Delete chat state. */
@Dao
interface DeleteDao {
    // ---- hidden tombstones ----

    @Upsert
    suspend fun putDeletedId(d: DeletedIdEntity)

    @Query("SELECT * FROM deleted_ids WHERE message_id = :messageId")
    suspend fun deletedId(messageId: String): DeletedIdEntity?

    @Query("DELETE FROM deleted_ids WHERE message_id = :messageId")
    suspend fun removeDeletedId(messageId: String)

    /** Kept 30 days (§15.6). */
    @Query("DELETE FROM deleted_ids WHERE at < :before")
    suspend fun pruneDeletedIds(before: Long): Int

    // ---- tombstones and rows ----

    /**
     * §15.6: the row becomes a tombstone in place (same message_id, sender and position): no body,
     * image reference or receipts; an incoming one is read and acked (never unread, never acked again).
     */
    @Query(
        "UPDATE messages SET kind = 'deleted', body = '', system_json = NULL, blob_id = NULL, deleted_by = :by, " +
            "deleted_by_admin = :byAdmin, deleted_at = :at, delete_state = NULL, delete_unverified = 0, " +
            "receipt_delivered = NULL, receipt_read = NULL, receipt_of = NULL, fail_reason = NULL, " +
            "status = CASE WHEN outgoing = 0 THEN 'READ' ELSE status END, " +
            "acked_status = CASE WHEN outgoing = 0 THEN 'READ' ELSE acked_status END " +
            "WHERE client_msg_id = :clientMsgId",
    )
    suspend fun tombstone(clientMsgId: String, by: String, byAdmin: Boolean, at: Long): Int

    @Query("UPDATE messages SET delete_state = :state WHERE client_msg_id IN (:clientMsgIds)")
    suspend fun setDeleteState(clientMsgIds: List<String>, state: String?)

    @Query("UPDATE messages SET delete_unverified = 1 WHERE message_id IN (:messageIds) AND conversation_id = :conversationId AND kind != 'deleted'")
    suspend fun markUnverified(conversationId: String, messageIds: List<String>): Int

    /** Android R2: set before every push of a PENDING row. */
    @Query("UPDATE messages SET send_attempts = send_attempts + 1 WHERE client_msg_id = :clientMsgId")
    suspend fun countAttempt(clientMsgId: String)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId")
    suspend fun conversationRows(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE delete_state IS NOT NULL")
    suspend fun deleting(): List<MessageEntity>

    @Query("DELETE FROM reactions WHERE target_message_id IN (:messageIds)")
    suspend fun deleteReactions(messageIds: List<String>)

    @Query("DELETE FROM reactions WHERE conversation_id = :conversationId")
    suspend fun deleteConversationReactions(conversationId: String)

    // ---- outbox ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun queue(o: DeleteOutboxEntity): Long

    @Query("SELECT * FROM delete_outbox WHERE state = 'queued' ORDER BY created_at ASC")
    suspend fun queued(): List<DeleteOutboxEntity>

    @Query("SELECT * FROM delete_outbox WHERE client_msg_id = :clientMsgId")
    suspend fun outboxRow(clientMsgId: String): DeleteOutboxEntity?

    @androidx.room.Update
    suspend fun updateOutbox(o: DeleteOutboxEntity)

    @Query("DELETE FROM delete_outbox WHERE client_msg_id = :clientMsgId")
    suspend fun removeOutbox(clientMsgId: String)

    // ---- Clear chat / Delete chat ----

    @Upsert
    suspend fun putChatState(s: ChatStateEntity)

    @Query("SELECT * FROM chat_state WHERE conversation_id = :conversationId")
    suspend fun chatState(conversationId: String): ChatStateEntity?

    @Query("SELECT * FROM chat_state")
    fun observeChatStates(): Flow<List<ChatStateEntity>>

    /** Delete chat: a new message brings the row back. */
    @Query("UPDATE chat_state SET hidden = 0 WHERE conversation_id = :conversationId AND hidden = 1")
    suspend fun unhide(conversationId: String): Int
}

/** v6 (§14.7) image media rows. */
@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: MediaEntity): Long

    @androidx.room.Update
    suspend fun update(m: MediaEntity)

    @Query("SELECT * FROM media WHERE client_msg_id = :clientMsgId")
    suspend fun get(clientMsgId: String): MediaEntity?

    @Query("SELECT * FROM media WHERE client_msg_id = :clientMsgId")
    fun observe(clientMsgId: String): Flow<MediaEntity?>

    @Query("SELECT * FROM media WHERE conversation_id = :conversationId")
    fun forConversation(conversationId: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE outgoing = 1 AND state IN ('ENCRYPTED', 'UPLOADING')")
    suspend fun owedUploads(): List<MediaEntity>

    /** Downloadable images, newest message first (§14.7 Receiving 7). */
    @Query(
        "SELECT x.* FROM media x JOIN messages m ON m.client_msg_id = x.client_msg_id " +
            "WHERE x.state IN ('NONE', 'DOWNLOADING') AND x.blob_id IS NOT NULL ORDER BY m.local_ts DESC",
    )
    suspend fun downloadable(): List<MediaEntity>

    /** Rows with a cache file, with their message's status (eviction, R4). */
    @Query(
        "SELECT x.client_msg_id, x.file_name, x.blob_size, x.last_access, x.expires_at_est, x.outgoing, x.state, m.status AS message_status " +
            "FROM media x JOIN messages m ON m.client_msg_id = x.client_msg_id WHERE x.file_name IS NOT NULL",
    )
    suspend fun cached(): List<CachedMedia>

    @Query("SELECT file_name FROM media WHERE file_name IS NOT NULL")
    suspend fun fileNames(): List<String>

    @Query("DELETE FROM media WHERE client_msg_id = :clientMsgId")
    suspend fun delete(clientMsgId: String): Int
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

@Dao
interface CallMarkDao {
    @Query("SELECT * FROM call_marks WHERE call_id = :callId")
    suspend fun get(callId: String): CallMarkEntity?

    @Upsert
    suspend fun put(m: CallMarkEntity)

    @Query("DELETE FROM call_marks WHERE at < :before")
    suspend fun prune(before: Long): Int
}

/** v9 (§17): the gap index, requests and parts (requester), provides (provider). */
@Dao
interface HistoryDao {
    // ---- §17.2 gap index ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGap(g: HistoryGapEntity): Long

    @Query("SELECT * FROM history_gap WHERE conversation_id = :conv ORDER BY server_ts ASC")
    suspend fun gaps(conv: String): List<HistoryGapEntity>

    @Query("SELECT * FROM history_gap WHERE message_id = :messageId")
    suspend fun gap(messageId: String): HistoryGapEntity?

    @Query("SELECT COUNT(*) FROM history_gap WHERE conversation_id = :conv")
    suspend fun gapCount(conv: String): Int

    @Query("SELECT COUNT(*) FROM history_gap WHERE conversation_id = :conv")
    fun observeGapCount(conv: String): Flow<Int>

    /** The conversation's gap rows (the marker's state; `asked_from` per android R6). */
    @Query("SELECT * FROM history_gap WHERE conversation_id = :conv")
    fun observeGaps(conv: String): Flow<List<HistoryGapEntity>>

    @Query("SELECT * FROM history_gap")
    suspend fun allGaps(): List<HistoryGapEntity>

    @Query("DELETE FROM history_gap WHERE message_id IN (:messageIds)")
    suspend fun deleteGaps(messageIds: List<String>): Int

    @Query("DELETE FROM history_gap WHERE conversation_id = :conv")
    suspend fun deleteConversationGaps(conv: String): Int

    @Query("UPDATE history_gap SET asked_from = :provider WHERE conversation_id = :conv AND server_ts >= :from AND server_ts <= :to")
    suspend fun markAsked(conv: String, from: String, to: String, provider: String): Int

    // ---- §17.16 requests and parts (requester) ----

    @Upsert
    suspend fun upsertRequest(r: HistoryRequestEntity)

    @Query("SELECT * FROM history_requests WHERE request_id = :requestId")
    suspend fun request(requestId: String): HistoryRequestEntity?

    @Query("SELECT * FROM history_requests WHERE conversation_id = :conv AND closed = 0 ORDER BY created_at DESC LIMIT 1")
    suspend fun openRequest(conv: String): HistoryRequestEntity?

    @Query("SELECT * FROM history_requests WHERE closed = 0")
    suspend fun openRequests(): List<HistoryRequestEntity>

    /** The latest request of a conversation (open or not), for the marker's progress line. */
    @Query("SELECT * FROM history_requests WHERE conversation_id = :conv ORDER BY created_at DESC LIMIT 1")
    fun observeLatestRequest(conv: String): Flow<HistoryRequestEntity?>

    @Query("SELECT * FROM history_requests WHERE conversation_id = :conv ORDER BY created_at DESC LIMIT 1")
    suspend fun latestRequest(conv: String): HistoryRequestEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPart(p: HistoryPartEntity): Long

    @Upsert
    suspend fun updatePart(p: HistoryPartEntity)

    @Query("SELECT * FROM history_parts WHERE request_id = :requestId AND part = :part")
    suspend fun part(requestId: String, part: Int): HistoryPartEntity?

    @Query("SELECT * FROM history_parts WHERE request_id = :requestId ORDER BY part ASC")
    suspend fun parts(requestId: String): List<HistoryPartEntity>

    @Query("SELECT * FROM history_parts WHERE state = 'pending' ORDER BY request_id, part")
    suspend fun pendingParts(): List<HistoryPartEntity>

    @Query("DELETE FROM history_parts WHERE request_id = :requestId")
    suspend fun deleteParts(requestId: String)

    // ---- §17.7/§17.8 provides (provider) ----

    @Upsert
    suspend fun upsertProvide(p: HistoryProvideEntity)

    @Query("SELECT * FROM history_provides WHERE request_id = :requestId")
    suspend fun provide(requestId: String): HistoryProvideEntity?

    @Query("SELECT * FROM history_provides WHERE state IN ('ask', 'accepting', 'exporting') ORDER BY created_at ASC")
    suspend fun openProvides(): List<HistoryProvideEntity>

    @Query("SELECT * FROM history_provides WHERE state IN ('ask', 'accepting', 'exporting') ORDER BY created_at ASC")
    fun observeOpenProvides(): Flow<List<HistoryProvideEntity>>

    @Query("SELECT * FROM history_provides WHERE state = :state ORDER BY created_at ASC")
    suspend fun provides(state: String): List<HistoryProvideEntity>

    /** Parts imported or rejected whose `history:ack` the server hasn't taken yet. */
    @Query("SELECT * FROM history_parts WHERE state IN ('imported', 'rejected') ORDER BY request_id, part")
    suspend fun owedAcks(): List<HistoryPartEntity>

    // ---- §17.7 export reads ----

    /** Candidate rows of one conversation, newest first (filtered further in Kotlin, android R2). */
    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conv AND message_id IS NOT NULL AND kind IN ('text', 'image', 'call') " +
            "ORDER BY local_ts DESC",
    )
    suspend fun exportCandidates(conv: String): List<MessageEntity>

    /** The conversation's system lines (group_event lines: the local view of membership). */
    @Query("SELECT * FROM messages WHERE conversation_id = :conv AND kind = 'system' ORDER BY local_ts ASC")
    suspend fun systemRows(conv: String): List<MessageEntity>

    @Query("SELECT * FROM reactions WHERE conversation_id = :conv AND confirmed_message_id IS NOT NULL")
    suspend fun confirmedReactions(conv: String): List<ReactionEntity>

    @Query("SELECT * FROM history_requests")
    suspend fun allRequests(): List<HistoryRequestEntity>
}

/** §22 (v1.22) backup export reads (paged, oldest first) and the restore's per-conversation counts. No schema change. */
@Dao
interface BackupDao {
    @Query(
        "SELECT conversation_id FROM messages UNION SELECT conversation_id FROM `groups` UNION SELECT conversation_id FROM chat_state " +
            "ORDER BY conversation_id",
    )
    suspend fun conversationIds(): List<String>

    /** One page of a conversation's rows, oldest first (keyset on local_ts, client_msg_id). */
    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conv AND (local_ts > :afterTs OR (local_ts = :afterTs AND client_msg_id > :afterId)) " +
            "ORDER BY local_ts ASC, client_msg_id ASC LIMIT :limit",
    )
    suspend fun page(conv: String, afterTs: Long, afterId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM reactions WHERE conversation_id = :conv AND confirmed_message_id IS NOT NULL AND confirmed_op = 'add' ORDER BY confirmed_ts ASC")
    suspend fun confirmedAdds(conv: String): List<ReactionEntity>

    @Query("SELECT * FROM deleted_ids WHERE conversation_id = :conv ORDER BY message_id")
    suspend fun deletedIds(conv: String): List<DeletedIdEntity>

    @Query("SELECT * FROM contacts")
    suspend fun contacts(): List<ContactEntity>

    @Query("SELECT * FROM group_members")
    suspend fun allMembers(): List<GroupMemberEntity>

    @Query("SELECT * FROM contacts WHERE phone = :phone")
    suspend fun contactByPhone(phone: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContact(c: ContactEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(g: GroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(m: GroupMemberEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeletedId(d: DeletedIdEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChatState(s: ChatStateEntity): Long

    /** The upgrade gate's per-conversation counts (hard rule 9): 1:1/group messages, photos, call lines. */
    @Query(
        "SELECT conversation_id, " +
            "SUM(CASE WHEN kind = 'text' THEN 1 ELSE 0 END) AS texts, " +
            "SUM(CASE WHEN kind = 'image' THEN 1 ELSE 0 END) AS images, " +
            "SUM(CASE WHEN kind = 'call' THEN 1 ELSE 0 END) AS calls, " +
            "SUM(CASE WHEN kind = 'deleted' THEN 1 ELSE 0 END) AS tombstones " +
            "FROM messages GROUP BY conversation_id ORDER BY conversation_id",
    )
    suspend fun counts(): List<ConversationCounts>
}

data class ConversationCounts(
    @androidx.room.ColumnInfo(name = "conversation_id") val conversationId: String,
    val texts: Int,
    val images: Int,
    val calls: Int,
    val tombstones: Int,
)

/** v10 (§18): profile photos / group icons, and the per-conversation send state. */
@Dao
interface ProfilePhotoDao {
    @Query("SELECT * FROM profile_photos WHERE user_id = :id")
    suspend fun get(id: String): ProfilePhotoEntity?

    @Query("SELECT * FROM profile_photos")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<ProfilePhotoEntity>>

    @Query("SELECT * FROM profile_photos WHERE blob_id IS NOT NULL AND fetched = 0")
    suspend fun unfetched(): List<ProfilePhotoEntity>

    @Query("SELECT * FROM profile_photos WHERE blob_id IS NOT NULL AND fetched = 2 AND checked_at < :before")
    suspend fun goneBefore(before: Long): List<ProfilePhotoEntity>

    @Upsert
    suspend fun put(p: ProfilePhotoEntity)

    @Query("UPDATE profile_photos SET fetched = :fetched, checked_at = :at WHERE user_id = :id AND blob_id = :blobId")
    suspend fun setFetched(id: String, blobId: String, fetched: Int, at: Long): Int

    @Query("DELETE FROM profile_photos WHERE user_id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM profile_photo_convs WHERE conversation_id = :conv")
    suspend fun conv(conv: String): ProfilePhotoConvEntity?

    @Query("SELECT * FROM profile_photo_convs")
    suspend fun convs(): List<ProfilePhotoConvEntity>

    @Query("SELECT * FROM profile_photo_convs WHERE pending_ver IS NOT NULL ORDER BY due_at ASC")
    suspend fun pendingSends(): List<ProfilePhotoConvEntity>

    @Upsert
    suspend fun putConv(c: ProfilePhotoConvEntity)

    @Query("UPDATE profile_photo_convs SET pending_ver = NULL, dirty = 0 WHERE conversation_id = :conv AND (pending_ver IS NULL OR pending_ver <= :ver)")
    suspend fun clearSent(conv: String, ver: Long): Int

    @Query("DELETE FROM profile_photo_convs WHERE conversation_id = :conv")
    suspend fun deleteConv(conv: String)
}

/** v11 (§24): chat ids, tabs and per-chat tab preferences. */
@Dao
interface ChatTabDao {
    @Query("SELECT * FROM chat_tabs")
    fun all(): Flow<List<ChatTabEntity>>

    @Query("SELECT * FROM chat_tabs")
    suspend fun allNow(): List<ChatTabEntity>

    @Query("SELECT * FROM chat_tabs WHERE conversation_id = :conversationId")
    suspend fun get(conversationId: String): ChatTabEntity?

    @Query("SELECT * FROM chat_tabs WHERE chat_id = :chatId")
    suspend fun byChat(chatId: String): List<ChatTabEntity>

    /** Only from the conversation's MLS `group_meta` (§24.1: tab and chat id never change after epoch 0). */
    @Upsert
    suspend fun upsert(t: ChatTabEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(t: ChatTabEntity): Long

    @Query("SELECT * FROM chat_prefs")
    fun prefs(): Flow<List<ChatPrefEntity>>

    @Query("SELECT * FROM chat_prefs WHERE chat_id = :chatId")
    suspend fun pref(chatId: String): ChatPrefEntity?

    @Upsert
    suspend fun upsertPref(p: ChatPrefEntity)

    @androidx.room.Transaction
    suspend fun setLastTab(chatId: String, tab: String) {
        upsertPref((pref(chatId) ?: ChatPrefEntity(chatId, null, null)).copy(lastTab = tab))
    }

    @androidx.room.Transaction
    suspend fun setOfficialState(chatId: String, state: String) {
        upsertPref((pref(chatId) ?: ChatPrefEntity(chatId, null, null)).copy(officialState = state))
    }
}
