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
)

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
