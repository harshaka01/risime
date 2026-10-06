package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Wire models for contract/v1/PROTOCOL.md. Field names match the contract exactly. */

/** The PROTOCOL.md version this client implements (shown in Settings → About; checked by a test). */
const val PROTOCOL_VERSION = "1.7"

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
    /** §7: absent means true (pre-v1.4 servers have no gate). */
    @SerialName("phone_verified") val phoneVerified: Boolean = true,
    /** §9.1: set while the user joined by invite and isn't SMS-verified. */
    @SerialName("vouched_by") val vouchedBy: VouchedBy? = null,
)

@Serializable
data class VouchedBy(@SerialName("user_id") val userId: String, @SerialName("display_name") val displayName: String)

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
data class ApiErrorBody(
    val code: String,
    val message: String = "",
    /** §7.1: optional on 401 invalid_code from the phone confirm. */
    @SerialName("attempts_left") val attemptsLeft: Int? = null,
)

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

    fun friend(): FriendSignal? =
        if (kind == KIND_FRIEND) runCatching { ProtocolJson.decodeFromJsonElement<FriendSignal>(data) }.getOrNull() else null

    companion object {
        const val KIND_PRESENCE = "presence"
        const val KIND_TYPING = "typing"
        const val KIND_FRIEND = "friend"
    }
}

/** Max ids per `presence:watch` (§2.5). */
const val PRESENCE_WATCH_MAX = 200

// ---- Authentication via RisiCloud Keycloak (§6, v1.3) ----

/** `GET /api/v1/auth/config` (unauthenticated). `modes` ⊆ {"oidc", "dev"}. */
@Serializable
data class AuthConfig(
    val modes: List<String>,
    val issuer: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    /** §7.1 (v1.4): "required" | "off"; absent = off. */
    @SerialName("phone_verification") val phoneVerification: String? = null,
) {
    val phoneVerificationRequired: Boolean get() = phoneVerification == PHONE_REQUIRED

    companion object {
        const val MODE_OIDC = "oidc"
        const val MODE_DEV = "dev"
        const val PHONE_REQUIRED = "required"
    }
}

/** §6.2 client → server push. */
@Serializable
data class AuthRefresh(val token: String)

@Serializable
data class AuthRefreshReply(@SerialName("expires_at") val expiresAt: String)

/** §6.1 error codes. Messages for NOT_ALLOWLISTED and IDENTITY_CONFLICT may be shown verbatim. */
object AuthErrors {
    const val INVALID_TOKEN = "invalid_token"
    const val NOT_ALLOWLISTED = "not_allowlisted"
    const val IDENTITY_CONFLICT = "identity_conflict"
    const val IDENTITY_MISMATCH = "identity_mismatch"
    const val PHONE_UNVERIFIED = "phone_unverified"
    const val ALREADY_VERIFIED = "already_verified"
    const val SMS_UNAVAILABLE = "sms_unavailable"
    const val INVALID_CODE = "invalid_code"
    const val EXPIRED = "expired"
    const val RATE_LIMITED = "rate_limited"
    const val TOO_MANY_ATTEMPTS = "too_many_attempts"
    const val INVALID_DEVICE = "invalid_device"
    const val NOT_FRIENDS = "not_friends"
    const val NOT_FOUND = "not_found"
    const val INVALID_PHONE = "invalid_phone"
    const val INVALID_EMAIL = "invalid_email"
    const val INVALID_NAME = "invalid_name"
}

// ---- One-time phone verification (§7, v1.4) ----

@Serializable
data class PhoneVerifyRequestReply(
    val status: String,
    @SerialName("expires_in") val expiresIn: Int,
    /** Masked allowlisted phone, e.g. "+9477•••••22" (U+2022). */
    val to: String,
)

@Serializable
data class PhoneVerifyConfirm(val code: String)

// ---- Push notifications (§8, v1.5) ----

/** `PUT /me/devices/{device_id}` body. */
@Serializable
data class DevicePut(
    val platform: String,
    @SerialName("push_token") val pushToken: String,
    @SerialName("app_version") val appVersion: String,
) {
    override fun toString() = "DevicePut(platform=$platform, appVersion=$appVersion, <token hidden>)"

    companion object {
        const val PLATFORM_ANDROID = "android"
    }
}

/** §8.2 FCM data payload: a wake-up only, never content. Unknown types are ignored. */
@Serializable
data class PushPayload(val type: String, val v: String? = null) {
    val isInbox: Boolean get() = type == TYPE_INBOX

    companion object {
        const val TYPE_INBOX = "inbox"

        /** From FCM's `RemoteMessage.data` map. */
        fun fromData(data: Map<String, String>): PushPayload? = data["type"]?.let { PushPayload(it, data["v"]) }
    }
}

// ---- Invites and friends (§9, v1.6) ----

@Serializable
data class InviteCreate(val phone: String, val email: String, val name: String)

@Serializable
data class Invite(
    val id: String,
    val phone: String,
    val email: String,
    val name: String,
    val status: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("inserted_at") val insertedAt: String,
    val subject: String = "Join me on RisiMe",
    @SerialName("share_text") val shareText: String,
    val link: String,
) {
    val pending: Boolean get() = status == "pending"
}

@Serializable
data class InviteReply(val invite: Invite)

@Serializable
data class InvitesReply(val invites: List<Invite>)

@Serializable
data class FriendRequestCreate(val phone: String)

@Serializable
data class FriendRequestReply(val status: String)

@Serializable
data class Friend(
    @SerialName("user_id") val userId: String,
    val phone: String,
    @SerialName("display_name") val displayName: String,
    val company: String = "",
    @SerialName("vouched_by") val vouchedBy: VouchedBy? = null,
    val since: String? = null,
)

/** Incoming: user_id/display_name/company set. Outgoing: only the phone you entered (never reveals registration). */
@Serializable
data class FriendRequest(
    val id: String,
    val phone: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val company: String? = null,
    @SerialName("inserted_at") val insertedAt: String? = null,
)

@Serializable
data class BlockedUser(
    @SerialName("user_id") val userId: String,
    val phone: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class FriendsReply(
    val friends: List<Friend> = emptyList(),
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
    val blocked: List<BlockedUser> = emptyList(),
)

@Serializable
data class FriendAcceptReply(val friend: Friend)

@Serializable
data class BlockCreate(@SerialName("user_id") val userId: String)

@Serializable
data class FriendSignalUser(
    @SerialName("user_id") val userId: String,
    val phone: String,
    @SerialName("display_name") val displayName: String,
    val company: String = "",
)

/** §9.3 `signal` kind `friend`. */
@Serializable
data class FriendSignal(
    val action: String,
    @SerialName("request_id") val requestId: String,
    val user: FriendSignalUser,
) {
    companion object {
        const val REQUEST_RECEIVED = "request_received"
        const val REQUEST_ACCEPTED = "request_accepted"
    }
}
