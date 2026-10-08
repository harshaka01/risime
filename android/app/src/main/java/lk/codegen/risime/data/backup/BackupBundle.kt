package lk.codegen.risime.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lk.codegen.risime.net.ProtocolJson
import java.time.Instant

// ---- §22.5 the bundle (plaintext, schema 1): JSON Lines, header first, then per conversation its
// `conversation` line and its rows oldest first; `contact` lines last. ----

/** The newest bundle schema this app reads (§24.10: 2 = with Official conversations). */
const val BUNDLE_SCHEMA = 2

/** §22.5 a bundle with Private conversations only (what every pre-v1.24 app reads). */
const val BUNDLE_SCHEMA_PRIVATE = 1

/** §24.10 a bundle with any Official conversation: older apps refuse it ("Update RisiMe to restore this backup"). */
const val BUNDLE_SCHEMA_TABS = 2

/** §22.5 the header (`backup_bundle_header.json`). */
@Serializable
data class BackupBundleHeader(
    val v: Int = 1,
    val type: String = TYPE,
    val origin: String = ORIGIN,
    val schema: Int = BUNDLE_SCHEMA_PRIVATE,
    @SerialName("backup_id") val backupId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("app_version") val appVersion: String,
    val counts: BackupCounts,
) {
    companion object {
        const val TYPE = "backup"
        const val ORIGIN = "backup"
    }
}

@Serializable
data class BackupCounts(val conversations: Int = 0, val messages: Int = 0, val tombstones: Int = 0, val contacts: Int = 0)

/** §22.5 `conversation` (`backup_entry_conversation.json`). */
@Serializable
data class BackupConversationLine(
    val type: String = TYPE,
    @SerialName("conversation_id") val conversationId: String,
    /** "dm" | "group" */
    val kind: String,
    val e2ee: Boolean,
    val peer: String? = null,
    val group: BackupGroupInfo? = null,
    val chat: BackupChatInfo = BackupChatInfo(),
    /** §24.10 (v1.24): the chat; absent = Private with `chat_id = conversation_id` (written for Official lines only). */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("chat_id") val chatId: String? = null,
    /** "private" | "official"; absent = Private. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val tab: String? = null,
    /** "dm" | "group"; absent = [kind]. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("chat_kind") val chatKind: String? = null,
) {
    val official: Boolean get() = tab == lk.codegen.risime.data.db.ChatTabEntity.TAB_OFFICIAL

    companion object {
        const val TYPE = "conversation"
    }
}

@Serializable
data class BackupGroupInfo(
    val name: String? = null,
    /** The §14.4 group-icon object (blob reference with its key), or null. */
    val icon: JsonObject? = null,
    val admins: List<String> = emptyList(),
    @SerialName("created_by") val createdBy: String? = null,
    /** "active" | "left" | "removed" */
    val state: String = "active",
)

@Serializable
data class BackupChatInfo(
    @SerialName("cleared_upto") val clearedUpto: String? = null,
    val hidden: Boolean = false,
    @SerialName("muted_until") val mutedUntil: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    @SerialName("last_read") val lastRead: String? = null,
)

/** §22.5 `message` (`backup_entry_message.json`): the §17.7 entry plus local state. */
@Serializable
data class BackupMessageLine(
    val type: String = TYPE,
    @SerialName("conversation_id") val conversationId: String,
    /** Null only for a local call line the server never saw (decision 054 "Missed call", a group call's local end). */
    @SerialName("message_id") val messageId: String?,
    @SerialName("client_msg_id") val clientMsgId: String,
    val from: String,
    @SerialName("from_device") val fromDevice: String? = null,
    @SerialName("server_ts") val serverTs: String?,
    val payload: JsonObject,
    /** null | "shared" | "own_device" | "backup" */
    val origin: String? = null,
    @SerialName("shared_by") val sharedBy: String? = null,
    /** Outgoing rows only: "sent" | "delivered" | "read". */
    val status: String? = null,
) {
    companion object {
        const val TYPE = "message"
    }
}

/** §22.5 `tombstone` (`backup_entry_tombstone.json`): visible and hidden tombstones (deletes keep winning). */
@Serializable
data class BackupTombstoneLine(
    val type: String = TYPE,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String,
    /** A visible tombstone's sender; a hidden one's deleter. */
    val from: String,
    @SerialName("server_ts") val serverTs: String? = null,
    /** "everyone" | "me" */
    val scope: String,
    val hidden: Boolean,
) {
    companion object {
        const val TYPE = "tombstone"
    }
}

/** §22.5 `group_event` (`backup_entry_group_event.json`): the §12.7 system lines. */
@Serializable
data class BackupGroupEventLine(
    val type: String = TYPE,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("event_id") val eventId: String,
    val generation: Long? = null,
    val action: String,
    val actor: String,
    val targets: List<String> = emptyList(),
    val role: String? = null,
    @SerialName("server_ts") val serverTs: String? = null,
) {
    companion object {
        const val TYPE = "group_event"
    }
}

/** §22.5 `contact` (`backup_entry_contact.json`): a name cache; never overrides server data. */
@Serializable
data class BackupContactLine(
    val type: String = TYPE,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val phone: String? = null,
) {
    companion object {
        const val TYPE = "contact"
    }
}

/** Timestamps and the TimeUUID watermark (100-ns ticks since 1582) in the bundle's ISO form, exactly. */
object BackupTime {
    private const val GREGORIAN_OFFSET = 0x01B21DD213814000L

    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString().let { if (it.length == 20) it.dropLast(1) + ".000Z" else it }

    /** Ticks → ISO with full 100-ns precision (the watermark never moves earlier through a backup). */
    fun ticksToIso(ticks: Long): String {
        val unix100ns = ticks - GREGORIAN_OFFSET
        return Instant.ofEpochSecond(Math.floorDiv(unix100ns, 10_000_000L), Math.floorMod(unix100ns, 10_000_000L) * 100).toString()
    }

    fun isoToTicks(iso: String?): Long? = iso?.let {
        runCatching {
            val i = Instant.parse(it)
            i.epochSecond * 10_000_000L + i.nano / 100 + GREGORIAN_OFFSET
        }.getOrNull()
    }

    fun ms(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun encodeLine(s: kotlinx.serialization.KSerializer<*>, v: Any): ByteArray {
    @Suppress("UNCHECKED_CAST")
    val text = ProtocolJson.encodeToString(s as kotlinx.serialization.KSerializer<Any>, v)
    return (text + "\n").toByteArray(Charsets.UTF_8)
}
