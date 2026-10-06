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
    /** v6 (§14): an image message's blob (kept for v1.12 "delete for everyone"); null for text. */
    @ColumnInfo(name = "blob_id") val blobId: String? = null,
    /** v7 (§15.6): a tombstone's deleter (user id), whether by an admin (D ≠ S), and when (device ms). */
    @ColumnInfo(name = "deleted_by") val deletedBy: String? = null,
    @ColumnInfo(name = "deleted_by_admin", defaultValue = "0") val deletedByAdmin: Boolean = false,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    /** v7 (§15.7, android S-b): null | [DELETE_STATE_DELETING] | [DELETE_STATE_CANCEL_AFTER_SEND]; ticks survive a restore. */
    @ColumnInfo(name = "delete_state") val deleteState: String? = null,
    /** v7 (android R2): pushes attempted for this PENDING row (0 = never pushed: cancelled locally). */
    @ColumnInfo(name = "send_attempts", defaultValue = "0") val sendAttempts: Int = 0,
    /** v7 (§15.4): a delete control for this message couldn't be verified (the core said Malformed): shown as a note. */
    @ColumnInfo(name = "delete_unverified", defaultValue = "0") val deleteUnverified: Boolean = false,
) {
    val system: Boolean get() = kind == KIND_SYSTEM
    val image: Boolean get() = kind == KIND_IMAGE

    /** §15.6 a tombstone row, or a row being deleted for everyone (rendered as the tombstone meanwhile). */
    val deleted: Boolean get() = kind == KIND_DELETED
    val showsAsDeleted: Boolean get() = deleted || deleteState != null

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_SYSTEM = "system"

        /** v6 (§14): an image; [body] holds the caption (or ""). */
        const val KIND_IMAGE = "image"

        /** v7 (§15.6): a tombstone ("This message was deleted"); body is "". */
        const val KIND_DELETED = "deleted"

        const val DELETE_STATE_DELETING = "deleting"
        const val DELETE_STATE_CANCEL_AFTER_SEND = "cancel_after_send"

        /** §15.5 R9: the local id of a positioned tombstone for a target never stored. */
        fun placeholderId(messageId: String) = "del:$messageId"
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
    /** v7 (§15.6): a tombstone preview ("You deleted this message" / "…deleted" / "…by an admin"). */
    @ColumnInfo(name = "deleted_by") val deletedBy: String? = null,
    @ColumnInfo(name = "deleted_by_admin") val deletedByAdmin: Boolean = false,
    @ColumnInfo(name = "delete_state") val deleteState: String? = null,
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

/**
 * v6 (§14.7): one image message's media state, keyed like its `messages` row. The content key and
 * the thumbnail are **sealed** with the database key (KvSealer under MlsDbKey, AAD bound to the
 * row, android R1); the ciphertext file in `noBackupFilesDir/media` is a cache of the blob. The
 * sender keeps the complete envelope too (R8), so its own image is restorable while the blob lives.
 */
@Entity(
    tableName = "media",
    indices = [Index(value = ["state", "next_at"]), Index("last_access"), Index("conversation_id")],
)
class MediaEntity(
    @PrimaryKey @ColumnInfo(name = "client_msg_id") val clientMsgId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val outgoing: Boolean,
    /** [MediaState] name. */
    val state: String,
    /** The server's blob id (null until the upload committed, for an outgoing image). */
    @ColumnInfo(name = "blob_id") val blobId: String?,
    /** `cipher_size` and the base64 SHA-256 of the ciphertext, as computed by the sender. */
    @ColumnInfo(name = "blob_size") val blobSize: Long,
    @ColumnInfo(name = "blob_sha256") val blobSha256: String,
    /** Outgoing: the idempotency id of the upload (a new one after a digest mismatch). */
    @ColumnInfo(name = "client_blob_id") val clientBlobId: String?,
    /** Sealed JSON `{"alg","key","plain_size"}`. */
    @ColumnInfo(name = "sealed_enc") val sealedEnc: ByteArray,
    /** Sealed JSON `{"mime","w","h","data"}`, or null without a thumbnail. */
    @ColumnInfo(name = "sealed_thumb") val sealedThumb: ByteArray?,
    val mime: String,
    val w: Int,
    val h: Int,
    /** The ciphertext cache file name (in the media directory), null when not on the device. */
    @ColumnInfo(name = "file_name") val fileName: String?,
    /** Bytes of the `.part` file (resumable download). */
    @ColumnInfo(name = "bytes_have") val bytesHave: Long = 0,
    /** When the blob stops being fetchable (`server_ts + 29 days`, ms); null until known. */
    @ColumnInfo(name = "expires_at_est") val expiresAtEst: Long?,
    @ColumnInfo(name = "last_access") val lastAccess: Long,
    val attempts: Int = 0,
    @ColumnInfo(name = "next_at") val nextAt: Long = 0,
    @ColumnInfo(name = "fail_reason") val failReason: String? = null,
) {
    fun copy(
        state: String = this.state,
        blobId: String? = this.blobId,
        blobSize: Long = this.blobSize,
        blobSha256: String = this.blobSha256,
        clientBlobId: String? = this.clientBlobId,
        fileName: String? = this.fileName,
        bytesHave: Long = this.bytesHave,
        expiresAtEst: Long? = this.expiresAtEst,
        lastAccess: Long = this.lastAccess,
        attempts: Int = this.attempts,
        nextAt: Long = this.nextAt,
        failReason: String? = this.failReason,
    ) = MediaEntity(
        clientMsgId, conversationId, outgoing, state, blobId, blobSize, blobSha256, clientBlobId, sealedEnc, sealedThumb,
        mime, w, h, fileName, bytesHave, expiresAtEst, lastAccess, attempts, nextAt, failReason,
    )
}

/** §14.7 media states (outgoing: encrypted → uploading → uploaded | failed; incoming: none → downloading → cached | gone | corrupt). */
enum class MediaState {
    /** Outgoing: encrypted and persisted, waiting for the upload job. */
    ENCRYPTED,
    UPLOADING,

    /** Outgoing: the blob reference is stored; the outbox may send the envelope. */
    UPLOADED,

    /** Outgoing: the upload was refused ([MediaEntity.failReason]); Retry or Delete. */
    FAILED,

    /** Not on the device (incoming, or evicted while fetchable): downloadable. */
    NONE,
    DOWNLOADING,

    /** The verified ciphertext is in the cache. */
    CACHED,

    /** 404: "This photo is no longer available" (the thumbnail stays). */
    GONE,

    /** Size, digest, AEAD, padding or decode failure after one re-download: "Couldn't open this photo". */
    CORRUPT,
}

/** A cached image for the LRU (android R4). */
data class CachedMedia(
    @ColumnInfo(name = "client_msg_id") val clientMsgId: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "blob_size") val blobSize: Long,
    @ColumnInfo(name = "last_access") val lastAccess: Long,
    @ColumnInfo(name = "expires_at_est") val expiresAtEst: Long?,
    val outgoing: Boolean,
    val state: String,
    @ColumnInfo(name = "message_status") val messageStatus: String,
)

/**
 * v7 (§15.6): hidden tombstones — a delete for a message this device doesn't hold (or "Delete for
 * me", [scope] = me). When the message arrives later it is decrypted first and re-judged with these
 * values (crypto R3). Kept 30 days.
 */
@Entity(tableName = "deleted_ids", indices = [Index("at")])
data class DeletedIdEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "deleted_by") val deletedBy: String,
    @ColumnInfo(name = "deleter_is_admin") val deleterIsAdmin: Boolean,
    @ColumnInfo(name = "delete_server_ts") val deleteServerTs: String?,
    /** "everyone" or "me". */
    val scope: String,
    val at: Long,
)

/**
 * v7 (§15.7): the delete outbox — `msg:delete` requests (scope me/everyone) and `chat:clear`
 * ([scope] = "clear" with [upto]). Retried with the same [clientMsgId].
 */
@Entity(tableName = "delete_outbox", indices = [Index(value = ["state", "next_at"]), Index("conversation_id")])
data class DeleteOutboxEntity(
    @PrimaryKey @ColumnInfo(name = "client_msg_id") val clientMsgId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    /** me | everyone | clear */
    val scope: String,
    @ColumnInfo(name = "targets_json") val targetsJson: String,
    @ColumnInfo(name = "blob_ids_json") val blobIdsJson: String,
    /** queued | failed */
    val state: String,
    val attempts: Int = 0,
    @ColumnInfo(name = "next_at") val nextAt: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    /** chat:clear only: the cursor event id. */
    val upto: String? = null,
) {
    companion object {
        const val SCOPE_CLEAR = "clear"
        const val QUEUED = "queued"
        const val FAILED = "failed"
    }
}

/**
 * v7 (§15.7): per-chat state for Clear chat / Delete chat. [clearedUpto] = the TimeUUID time (100 ns
 * ticks) at or before which every visible effect of this conversation is ignored; [hidden] = Delete
 * chat (back on the list with the next new message).
 */
@Entity(tableName = "chat_state")
data class ChatStateEntity(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "cleared_upto") val clearedUpto: Long?,
    @ColumnInfo(name = "hidden", defaultValue = "0") val hidden: Boolean = false,
)
