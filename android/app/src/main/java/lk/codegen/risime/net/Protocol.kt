package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Wire models for contract/v1/PROTOCOL.md. Field names match the contract exactly. */

/** The PROTOCOL.md version this client implements (shown in Settings → About; checked by a test). */
const val PROTOCOL_VERSION = "1.3"

val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true // §0: clients must ignore unknown fields
    explicitNulls = true
    encodeDefaults = true
}

// ---- REST (§1) ----

@Serializable
data class AuthRequest(val phone: String, val email: String)

@Serializable
data class AuthRequestReply(val status: String, @SerialName("expires_in") val expiresIn: Int)

@Serializable
data class AuthVerify(
    val phone: String,
    val code: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class User(
    val id: String,
    val phone: String,
    @SerialName("display_name") val displayName: String,
    val company: String,
)

@Serializable
data class AuthVerifyReply(val token: String, val user: User)

@Serializable
data class MeReply(val user: User)

@Serializable
data class PatchMe(@SerialName("display_name") val displayName: String)

@Serializable
data class Contact(
    val phone: String,
    @SerialName("display_name") val displayName: String,
    val company: String,
    @SerialName("user_id") val userId: String? = null,
    val registered: Boolean,
)

@Serializable
data class ContactsReply(val contacts: List<Contact>)

@Serializable
data class ApiErrorBody(val code: String, val message: String = "")

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody)

// ---- Realtime (§2) ----

@Serializable
data class JoinPayload(val since: String?, val limit: Int = 500)

@Serializable
data class SyncPayload(val since: String?)

/** Reply to join and to `sync`. */
@Serializable
data class EventsPage(
    val events: List<Event>,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("server_time") val serverTime: String? = null,
)

@Serializable
data class MsgSend(
    @SerialName("client_msg_id") val clientMsgId: String,
    val to: String,
    val body: String,
    @SerialName("client_ts") val clientTs: String,
)

@Serializable
data class MsgSendReply(
    @SerialName("message_id") val messageId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("server_ts") val serverTs: String,
)

@Serializable
data class MsgAck(
    @SerialName("message_ids") val messageIds: List<String>,
    val status: String,
)

@Serializable
data class ErrorReason(val reason: String)

/** §2.3 generic server push. `data` is decoded according to `kind`. */
@Serializable
data class Event(
    @SerialName("event_id") val eventId: String,
    val kind: String,
    val data: JsonObject,
) {
    fun messageData(): MessageData? =
        if (kind == KIND_MESSAGE) ProtocolJson.decodeFromJsonElement<MessageData>(data) else null

    fun statusData(): StatusData? =
        if (kind == KIND_STATUS) ProtocolJson.decodeFromJsonElement<StatusData>(data) else null

    companion object {
        const val KIND_MESSAGE = "message"
        const val KIND_STATUS = "status"
    }
}

@Serializable
data class MessageData(
    @SerialName("message_id") val messageId: String,
    @SerialName("client_msg_id") val clientMsgId: String,
    @SerialName("conversation_id") val conversationId: String,
    val from: String,
    val to: String,
    val body: String,
    @SerialName("server_ts") val serverTs: String,
)

@Serializable
data class StatusData(
    @SerialName("message_id") val messageId: String,
    @SerialName("client_msg_id") val clientMsgId: String,
    @SerialName("conversation_id") val conversationId: String,
    val status: String,
    val by: String,
    val at: String,
)

/** §2.4 */
fun dmConversationId(a: String, b: String): String {
    val x = a.lowercase()
    val y = b.lowercase()
    return if (x <= y) "dm:${x}_$y" else "dm:${y}_$x"
}

// ---- Presence and typing (§2.3 signal, §2.5, §2.6; v1.2). Ephemeral: never stored, no cursor. ----

@Serializable
data class PresenceWatch(@SerialName("user_ids") val userIds: List<String>)

@Serializable
data class Presence(
    @SerialName("user_id") val userId: String,
    val online: Boolean,
    /** Null while online, and for a user who has never connected. May be arbitrarily old. */
    @SerialName("last_seen") val lastSeen: String? = null,
)

@Serializable
data class PresenceWatchReply(val presences: List<Presence>)

@Serializable
data class TypingPush(val to: String, val typing: Boolean)

@Serializable
data class TypingData(
    val from: String,
    @SerialName("conversation_id") val conversationId: String,
    val typing: Boolean,
)

/** §2.3 `signal` push. Clients ignore unknown kinds (the decoders return null). */
@Serializable
data class Signal(val kind: String, val data: JsonObject) {
    fun presence(): Presence? =
        if (kind == KIND_PRESENCE) runCatching { ProtocolJson.decodeFromJsonElement<Presence>(data) }.getOrNull() else null

    fun typing(): TypingData? =
        if (kind == KIND_TYPING) runCatching { ProtocolJson.decodeFromJsonElement<TypingData>(data) }.getOrNull() else null

    companion object {
        const val KIND_PRESENCE = "presence"
        const val KIND_TYPING = "typing"
    }
}

/** Max ids per `presence:watch` (§2.5). */
const val PRESENCE_WATCH_MAX = 200
