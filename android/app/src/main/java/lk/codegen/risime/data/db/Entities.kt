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
    val registered: Boolean,
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
