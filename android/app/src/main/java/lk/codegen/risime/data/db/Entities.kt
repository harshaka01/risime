package lk.codegen.risime.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["message_id"], unique = true),
        Index(value = ["conversation_id", "local_ts"]),
        Index(value = ["status", "local_ts"]),
    ],
)
data class MessageEntity(
    @PrimaryKey @ColumnInfo(name = "client_msg_id") val clientMsgId: String,
    @ColumnInfo(name = "message_id") val messageId: String?,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "from_id") val from: String,
    @ColumnInfo(name = "to_id") val to: String,
    val body: String,
    @ColumnInfo(name = "server_ts") val serverTs: String?,
    @ColumnInfo(name = "local_ts") val localTs: Long,
    /** MessageStatus name. Outgoing: pending/sent/delivered/read/failed. Incoming: delivered/read. */
    val status: String,
    val outgoing: Boolean,
    /** Incoming only: highest status the server confirmed via msg:ack (null = none yet). */
    @ColumnInfo(name = "acked_status") val ackedStatus: String? = null,
    @ColumnInfo(name = "fail_reason") val failReason: String? = null,
    /** v5 (§12): "text", or "system" for a local group system line ([systemJson] = the group_event). */
    @ColumnInfo(name = "kind", defaultValue = "text") val kind: String = KIND_TEXT,
    @ColumnInfo(name = "system_json") val systemJson: String? = null,
    /** v5 (§12.7): the sender's aggregated group receipt (null until the first group_receipt). */
    @ColumnInfo(name = "receipt_delivered") val receiptDelivered: Int? = null,
    @ColumnInfo(name = "receipt_read") val receiptRead: Int? = null,
    @ColumnInfo(name = "receipt_of") val receiptOf: Int? = null,
) {
    val system: Boolean get() = kind == KIND_SYSTEM

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_SYSTEM = "system"
    }
}

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val phone: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val company: String,
    @ColumnInfo(name = "user_id") val userId: String?,
    /** Can be messaged right now (§9.2: a friend whose phone gate is passed). */
    val registered: Boolean,
    /** v2: an accepted friend. Former friends keep their row so the chat stays visible, read-only. */
    @ColumnInfo(name = "friend", defaultValue = "0") val friend: Boolean = true,
    /** v2: "vouched by <name>" (§9.1), null for allowlisted/verified users. */
    @ColumnInfo(name = "vouched_by_name") val vouchedByName: String? = null,
    /** v5 (§12.1): can be added to a group (absent on the wire = false: "needs to update"). */
    @ColumnInfo(name = "group_ready", defaultValue = "0") val groupReady: Boolean = false,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "last_event_id") val lastEventId: String?,
)

/** Event ids already applied, for dedupe of live push + join replay. */
@Entity(tableName = "seen_events")
data class SeenEventEntity(@PrimaryKey @ColumnInfo(name = "event_id") val eventId: String)

/** A6: local-only behaviour log. Never uploaded. */
@Entity(tableName = "behaviour_events", indices = [Index("type"), Index("at")])
data class BehaviourEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    @ColumnInfo(name = "peer_hash") val peerHash: String?,
    val at: Long,
    @ColumnInfo(name = "meta_json") val metaJson: String?,
)

/** Chats-list row: last message per conversation. */
data class LastMessage(
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val body: String,
    @ColumnInfo(name = "local_ts") val localTs: Long,
    val outgoing: Boolean,
    val status: String,
    /** v5: the sender ("Kamal: …" in group rows) and text/system. */
    @ColumnInfo(name = "from_id") val from: String = "",
    val kind: String = MessageEntity.KIND_TEXT,
)

/** Chats-list badge: unread incoming messages in one conversation. */
data class UnreadCount(
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val unread: Int,
)

/**
 * v3 (§10.4, decision 033): the MLS core's key-value state, in the same database as messages so
 * decrypt → insert → cursor is one transaction. Values are AES-GCM sealed (SealedKvStore).
 */
@Entity(tableName = "mls_kv", primaryKeys = ["namespace", "key"])
class MlsKvEntity(
    val namespace: String,
    val key: ByteArray,
    val value: ByteArray,
)

/** v3: e2ee events that arrived ahead of their epoch, replayed after the matching commit. */
@Entity(tableName = "mls_pending", indices = [Index("conversation_id")])
data class MlsPendingEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val generation: Long,
    val epoch: Long,
    /** Arrival order (event_id TimeUUID strings don't sort by time). */
    val seq: Long,
    /** The whole Event JSON, re-applied through the normal pipeline. */
    @ColumnInfo(name = "event_json") val eventJson: String,
)

/**
 * v4 (§11.2): reaction state per (conversation, target message, reactor user, emoji). [op] is what
 * the UI shows (my pending tap, else the confirmed op); confirmed_* is the latest op from the
 * server by (server_ts, message_id), which decides between devices.
 */
@Entity(
    tableName = "reactions",
    primaryKeys = ["conversation_id", "target_message_id", "reactor_user_id", "emoji"],
    indices = [Index(value = ["conversation_id", "target_message_id"]), Index("pending_client_msg_id")],
)
data class ReactionEntity(
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "target_message_id") val targetMessageId: String,
    @ColumnInfo(name = "reactor_user_id") val reactorUserId: String,
    val emoji: String,
    /** "add" or "remove" as displayed. */
    val op: String,
    @ColumnInfo(name = "confirmed_op") val confirmedOp: String?,
    @ColumnInfo(name = "confirmed_ts") val confirmedTs: String?,
    @ColumnInfo(name = "confirmed_message_id") val confirmedMessageId: String?,
    /** My own tap not yet confirmed by the server. */
    val pending: Boolean,
    /** Assigned at the first send attempt; a retry reuses it (§11.2). */
    @ColumnInfo(name = "pending_client_msg_id") val pendingClientMsgId: String?,
    /** When this row last changed locally (debounce and notifications). */
    @ColumnInfo(name = "local_ts") val localTs: Long,
)

/**
 * v5 (§12): a group conversation. [name] is a cached copy of the encrypted `group_meta` (for the
 * list and notifications); roles shown are the server's (S6).
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: String,
    val name: String?,
    @ColumnInfo(name = "my_role") val myRole: String,
    /** creating | active | left | removed */
    val state: String,
    @ColumnInfo(name = "created_by") val createdBy: String?,
    @ColumnInfo(name = "created_at") val createdAt: String?,
    val generation: Long,
    /** The epoch of the last group_event applied. */
    @ColumnInfo(name = "epoch_seen") val epochSeen: Long?,
    @ColumnInfo(name = "meta_updated_at") val metaUpdatedAt: Long?,
    @ColumnInfo(name = "last_refreshed_at") val lastRefreshedAt: Long?,
    /** When this row appeared locally (orders a group with no messages yet in the chats list). */
    @ColumnInfo(name = "local_ts") val localTs: Long,
) {
    val readOnly: Boolean get() = state == STATE_LEFT || state == STATE_REMOVED

    companion object {
        const val STATE_CREATING = "creating"
        const val STATE_ACTIVE = "active"
        const val STATE_LEFT = "left"
        const val STATE_REMOVED = "removed"
    }
}

/** v5: members, including former ones (state left/removed) so old bubbles keep their names. */
@Entity(tableName = "group_members", primaryKeys = ["conversation_id", "user_id"], indices = [Index("user_id")])
data class GroupMemberEntity(
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val phone: String?,
    val role: String,
    val kind: String,
    /** active | pending_add | pending_remove | left | removed */
    val state: String,
    @ColumnInfo(name = "joined_at") val joinedAt: String?,
) {
    val current: Boolean get() = state == "active" || state == "pending_add" || state == "pending_remove"
}

/**
 * v5: the persisted outbox for group REST + commit work, so create/add/remove/leave/role/rename and
 * named-committer ops survive process death. [opId] (the server's PendingOp id) dedupes `group_op`.
 */
@Entity(
    tableName = "group_ops",
    indices = [Index("conversation_id"), Index(value = ["op_id"], unique = true), Index(value = ["state", "next_at"])],
)
data class GroupOpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null for a create before the server assigned the id. */
    @ColumnInfo(name = "conversation_id") val conversationId: String?,
    /** create | add | remove | leave | role | rename | commit | rejoin | reset */
    val type: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    /** queued | done | failed */
    val state: String,
    val attempts: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "client_group_id") val clientGroupId: String? = null,
    @ColumnInfo(name = "op_id") val opId: String? = null,
    @ColumnInfo(name = "next_at") val nextAt: Long = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)
