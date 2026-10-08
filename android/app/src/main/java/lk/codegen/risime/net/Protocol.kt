package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Wire models for contract/v1/PROTOCOL.md. Field names match the contract exactly. */

/** The PROTOCOL.md version this client implements (shown in Settings → About; checked by a test). */
const val PROTOCOL_VERSION = "1.22"

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
    /** §21.4 (v1.20): false while the phone was self-asserted at open sign-up and isn't SMS-verified; absent = true. */
    @SerialName("phone_confirmed") val phoneConfirmed: Boolean = true,
)

/** §21.3 (v1.20) `POST /auth/signup`. */
@Serializable
data class SignupRequest(val phone: String, @SerialName("display_name") val displayName: String)

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
    /** §10.2: the group's current epoch on 409 epoch_conflict. */
    val epoch: Long? = null,
    /** §10.2: why a conversation isn't ready (409 not_ready). */
    val missing: List<MlsMissing>? = null,
    /** §12.8: the current generation on 409 generation_conflict. */
    val generation: Long? = null,
    /** §13.4: blob quota numbers on 413 quota_exceeded. */
    val used: Long? = null,
    val limit: Long? = null,
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
    /** §13.2 (v1.10): this device's first census time; null = the epoch rule only. Same on every page of a join. */
    @SerialName("history_before") val historyBefore: String? = null,
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

    fun reaction(): ReactionEvent? = if (kind == KIND_REACTION) ProtocolJson.decodeFromJsonElement<ReactionEvent>(data) else null

    fun mlsCommit(): MlsCommitEvent? = if (kind == KIND_MLS_COMMIT) ProtocolJson.decodeFromJsonElement<MlsCommitEvent>(data) else null

    fun mlsWelcome(): MlsWelcomeEvent? = if (kind == KIND_MLS_WELCOME) ProtocolJson.decodeFromJsonElement<MlsWelcomeEvent>(data) else null

    fun mlsMembership(): MlsMembershipEvent? =
        if (kind == KIND_MLS_MEMBERSHIP) ProtocolJson.decodeFromJsonElement<MlsMembershipEvent>(data) else null

    fun groupEvent(): GroupEvent? = if (kind == KIND_GROUP_EVENT) ProtocolJson.decodeFromJsonElement<GroupEvent>(data) else null

    /** v1.16 a DM `devices` op naming a committer. */
    fun mlsDmOp(): MlsDmOpEvent? = if (kind == KIND_MLS_DM_OP) ProtocolJson.decodeFromJsonElement<MlsDmOpEvent>(data) else null

    fun groupOp(): GroupOpEvent? = if (kind == KIND_GROUP_OP) ProtocolJson.decodeFromJsonElement<GroupOpEvent>(data) else null

    fun groupReceipt(): GroupReceiptEvent? =
        if (kind == KIND_GROUP_RECEIPT) ProtocolJson.decodeFromJsonElement<GroupReceiptEvent>(data) else null

    /** §15.5 (v1.12) a `delete` event. */
    fun deleteData(): DeleteEvent? = if (kind == KIND_DELETE) ProtocolJson.decodeFromJsonElement<DeleteEvent>(data) else null

    /** §16.3 (v1.13) a `call_signal` event (ephemeral, its own store). */
    fun callSignal(): CallSignalEvent? = if (kind == KIND_CALL_SIGNAL) ProtocolJson.decodeFromJsonElement<CallSignalEvent>(data) else null

    /** §17.5 (v1.15) history sharing events (inbox, 48 h TTL, `to_devices`). */
    fun historyRequest(): HistoryRequestEvent? = if (kind == KIND_HISTORY_REQUEST) ProtocolJson.decodeFromJsonElement<HistoryRequestEvent>(data) else null

    fun historyRequestClosed(): HistoryRequestClosedEvent? =
        if (kind == KIND_HISTORY_REQUEST_CLOSED) ProtocolJson.decodeFromJsonElement<HistoryRequestClosedEvent>(data) else null

    fun historyStatus(): HistoryStatusEvent? = if (kind == KIND_HISTORY_STATUS) ProtocolJson.decodeFromJsonElement<HistoryStatusEvent>(data) else null

    fun historyShare(): HistoryShareEvent? = if (kind == KIND_HISTORY_SHARE) ProtocolJson.decodeFromJsonElement<HistoryShareEvent>(data) else null

    companion object {
        const val KIND_MESSAGE = "message"
        const val KIND_STATUS = "status"
        const val KIND_REACTION = "reaction"
        const val KIND_MLS_COMMIT = "mls_commit"
        const val KIND_MLS_WELCOME = "mls_welcome"
        const val KIND_MLS_MEMBERSHIP = "mls_membership"
        const val KIND_MLS_DM_OP = "mls_dm_op"
        const val KIND_GROUP_EVENT = "group_event"
        const val KIND_GROUP_OP = "group_op"
        const val KIND_GROUP_RECEIPT = "group_receipt"
        const val KIND_DELETE = "delete"
        const val KIND_CALL_SIGNAL = "call_signal"
        const val KIND_HISTORY_REQUEST = "history_request"
        const val KIND_HISTORY_REQUEST_CLOSED = "history_request_closed"
        const val KIND_HISTORY_STATUS = "history_status"
        const val KIND_HISTORY_SHARE = "history_share"
    }
}

@Serializable
data class MessageData(
    @SerialName("message_id") val messageId: String,
    @SerialName("client_msg_id") val clientMsgId: String,
    @SerialName("conversation_id") val conversationId: String,
    val from: String,
    /** DMs only: group messages (§12.7) have no `to`. */
    val to: String? = null,
    /** Plaintext conversations. Null for e2ee (§10.3), which carry ciphertext instead. */
    val body: String? = null,
    @SerialName("server_ts") val serverTs: String,
    @SerialName("from_device") val fromDevice: String? = null,
    /** §10.3 e2ee: base64 MLS PrivateMessage. */
    val ciphertext: String? = null,
    val generation: Long? = null,
    val epoch: Long? = null,
    /** §18.4 a control message the server didn't push (a profile photo); absent = false. */
    val silent: Boolean = false,
) {
    val encrypted: Boolean get() = ciphertext != null
}

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

/** §12.1: `grp:<uuid>` conversations. */
fun isGroupConversation(conversationId: String): Boolean = conversationId.startsWith(GROUP_PREFIX)

const val GROUP_PREFIX = "grp:"

/** The other member of a `dm:` conversation (yourself for a note-to-self); null for anything else. */
fun dmPeer(conversationId: String, me: String): String? {
    if (!conversationId.startsWith("dm:")) return null
    val ids = conversationId.removePrefix("dm:").split('_')
    return ids.firstOrNull { !it.equals(me, true) } ?: ids.firstOrNull()
}

/** A conversation id (`dm:`/`grp:`) as is; anything else is a DM peer's user id. */
fun conversationFor(me: String, target: String): String =
    if (target.startsWith("dm:") || isGroupConversation(target)) target else dmConversationId(me, target)

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

    fun keyPackagesLow(): MlsKeyPackagesLow? =
        if (kind == KIND_MLS_KEY_PACKAGES_LOW) runCatching { ProtocolJson.decodeFromJsonElement<MlsKeyPackagesLow>(data) }.getOrNull() else null

    fun friend(): FriendSignal? =
        if (kind == KIND_FRIEND) runCatching { ProtocolJson.decodeFromJsonElement<FriendSignal>(data) }.getOrNull() else null

    companion object {
        const val KIND_PRESENCE = "presence"
        const val KIND_TYPING = "typing"
        const val KIND_FRIEND = "friend"
        const val KIND_MLS_KEY_PACKAGES_LOW = "mls_key_packages_low"
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
    /** §21.1 (v1.20): "open" | "invite"; absent = invite. */
    val signup: String? = null,
) {
    val phoneVerificationRequired: Boolean get() = phoneVerification == PHONE_REQUIRED
    val signupOpen: Boolean get() = signup == SIGNUP_OPEN

    companion object {
        const val MODE_OIDC = "oidc"
        const val MODE_DEV = "dev"
        const val PHONE_REQUIRED = "required"
        const val SIGNUP_OPEN = "open"
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

    // §21 (v1.20) open sign-up
    const val SIGNUP_REQUIRED = "signup_required"
    const val SIGNUP_CLOSED = "signup_closed"
    const val PHONE_TAKEN = "phone_taken"
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
    const val MLS_UNAVAILABLE = "mls_unavailable"
    const val EPOCH_CONFLICT = "epoch_conflict"
    const val NOT_READY = "not_ready"
    const val E2EE_REQUIRED = "e2ee_required"
    const val STALE_EPOCH = "stale_epoch"
    const val UNKNOWN_TARGET = "unknown_target"
    const val INVALID_EMOJI = "invalid_emoji"
    const val TOO_LONG = "too_long"

    // §12 groups
    const val NOT_MEMBER = "not_member"
    const val NOT_ADMIN = "not_admin"
    const val LAST_ADMIN = "last_admin"
    const val TOO_MANY_MEMBERS = "too_many_members"
    const val TOO_MANY_DEVICES = "too_many_devices"
    const val INVALID_ROLE = "invalid_role"
    const val LOG_EXPIRED = "log_expired"
    const val GENERATION_CONFLICT = "generation_conflict"
    const val TOO_LARGE = "too_large"

    // §14.6 (v1.11)
    const val NOT_E2EE = "not_e2ee"
    const val BAD_MEDIA_TYPE = "bad_media_type"
    const val STORAGE_FULL = "storage_full"
    const val QUOTA_EXCEEDED = "quota_exceeded"

    // §15.2 (v1.12) msg:delete refusals.
    const val NOT_SENDER = "not_sender"
    const val TOO_OLD = "too_old"
    const val BAD_REQUEST = "bad_request"
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
    /** §10.1: optional when `mls` is present (an app without Firebase still registers). */
    @SerialName("push_token") val pushToken: String?,
    @SerialName("app_version") val appVersion: String,
    /** Omitted (not null) when absent: a push-only registration stays exactly v1.5. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val mls: DeviceMls? = null,
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
    val isCall: Boolean get() = type == TYPE_CALL

    companion object {
        const val TYPE_INBOX = "inbox"

        /** §16.8 (v1.13): a call wake-up (high priority, no caller, no call id). */
        const val TYPE_CALL = "call"

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
    /** §12.1 (v1.9): can be added to a group now; absent = false ("needs to update"). */
    @SerialName("group_ready") val groupReady: Boolean = false,
    /** §21.4 (v1.20): absent = true. */
    @SerialName("phone_confirmed") val phoneConfirmed: Boolean = true,
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
    /** §21.4 (v1.20): incoming requests only; absent = true. */
    @SerialName("phone_confirmed") val phoneConfirmed: Boolean = true,
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
    /** §21.4 (v1.20): absent = true. */
    @SerialName("phone_confirmed") val phoneConfirmed: Boolean = true,
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

// ---- End-to-end encryption with MLS (§10, v1.7). Binary fields are standard base64. ----

@Serializable
data class DeviceMls(
    @SerialName("signature_key") val signatureKey: String,
    /** §12.1: `["groups"]` once this app can really decrypt groups; omitted before (v1.7 shape). */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val capabilities: List<String>? = null,
) {
    companion object {
        const val CAP_GROUPS = "groups"

        /** §14.1: advertised only once the app can receive and render images. */
        const val CAP_IMAGES = "images"

        /** §15.1: advertised once the app can receive and apply `delete` events (whether or not its send UI is on). */
        const val CAP_DELETES = "deletes"

        /** §16.1: advertised only once the app can ring, answer and play a call (WebRTC loaded, Telecom registered, notifications allowed). */
        const val CAP_CALLS = "calls"

        /** v1.14 §12.1/§12.4a: advertised only when the MLS core reports it (`core_capabilities()`). */
        const val CAP_MEMBER_DEVICES = "member_devices"

        /** v1.15 §17.1: advertised only when the app can both provide and receive history bundles (the core's §17.3 functions). */
        const val CAP_HISTORY_SHARE = "history_share"

        /** v1.18 §19.1: advertised only together with `calls` and when the VP8 encoder and decoder load. */
        const val CAP_VIDEO = "video"

        /**
         * v1.19 §20.1: advertised only together with `groups`, `calls` and `video`, once the LiveKit
         * SDK and its frame encryption load and the core exports call keys (§20.6).
         */
        const val CAP_GROUP_CALLS = "group_calls"
    }
}

@Serializable
data class DevicePutReply(val attestation: String)

@Serializable
data class AttestationKeys(val keys: List<JsonObject>)

@Serializable
data class KeyPackagesUpload(
    @SerialName("key_packages") val keyPackages: List<String>,
    @SerialName("last_resort") val lastResort: String? = null,
    /** §12.1: delete the device's stored normal key packages first (once, when `groups` is first advertised). */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val replace: Boolean? = null,
)

@Serializable
data class KeyPackageCount(val count: Int)

@Serializable
data class KeyPackagesClaim(
    @SerialName("user_ids") val userIds: List<String>,
    /** §12.5: claim co-members of this group (`not_member` otherwise). */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("conversation_id") val conversationId: String? = null,
)

@Serializable
data class ClaimedDevice(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String? = null,
    val mls: Boolean,
    val attestation: String? = null,
    @SerialName("key_package") val keyPackage: String? = null,
)

@Serializable
data class KeyPackagesClaimReply(val devices: List<ClaimedDevice>)

@Serializable
data class MlsMissing(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String? = null,
    val reason: String,
) {
    companion object {
        const val NO_MLS = "no_mls"
        const val LEGACY_APP = "legacy_app"
    }
}

@Serializable
data class MlsDeviceRef(@SerialName("user_id") val userId: String, @SerialName("device_id") val deviceId: String)

@Serializable
data class MlsGroup(
    val e2ee: Boolean,
    val generation: Long = 1,
    val epoch: Long? = null,
    val ready: Boolean = false,
    val missing: List<MlsMissing> = emptyList(),
    val devices: List<MlsDeviceRef> = emptyList(),
    /** §14.1: every app instance of every member advertises `images` (absent = false). */
    @SerialName("images_ready") val imagesReady: Boolean = false,
    @SerialName("missing_images") val missingImages: List<MissingImages> = emptyList(),
    /** §15.1: every app instance of every member advertises `deletes` (absent = false). A hint only. */
    @SerialName("deletes_ready") val deletesReady: Boolean = false,
    @SerialName("missing_deletes") val missingDeletes: List<MissingImages> = emptyList(),
    /** §16.1 (DMs): each of the two users has at least one recent instance that advertises `calls` (absent = false). */
    @SerialName("calls_ready") val callsReady: Boolean = false,
    @SerialName("missing_calls") val missingCalls: List<MissingImages> = emptyList(),
    /** §19.1 (DMs): each user has a recent instance that advertises `video` (absent = false). */
    @SerialName("video_ready") val videoReady: Boolean = false,
    @SerialName("missing_video") val missingVideo: List<MissingImages> = emptyList(),
    /** §20.1 (groups): my user and at least one other active member have a `group_calls` device (absent = false). */
    @SerialName("group_calls_ready") val groupCallsReady: Boolean = false,
    /** §20.1 members' instances seen in 30 days without `group_calls` (information only). */
    @SerialName("missing_group_calls") val missingGroupCalls: List<MissingImages> = emptyList(),
)

/** §14.1 an app instance that doesn't advertise `images` (`device_id` null: an old app without one). */
@Serializable
data class MissingImages(@SerialName("user_id") val userId: String, @SerialName("device_id") val deviceId: String? = null)

/** §14.2 `GET /blobs/usage`. */
@Serializable
data class BlobUsageReply(val media: MediaUsage, val mls: MlsUsage? = null, val history: HistoryUsage? = null)

@Serializable
data class MediaUsage(
    val used: Long,
    val limit: Long,
    @SerialName("uploads_last_hour") val uploadsLastHour: Int = 0,
    @SerialName("hourly_limit") val hourlyLimit: Int = 0,
    @SerialName("uploads_last_day") val uploadsLastDay: Int = 0,
    @SerialName("daily_limit") val dailyLimit: Int = 0,
)

@Serializable
data class MlsUsage(val used: Long, val limit: Long)

@Serializable
data class MlsCommitRequest(
    val generation: Long,
    val epoch: Long,
    val commit: String,
    val welcome: String? = null,
    val added: List<MlsDeviceRef> = emptyList(),
    val removed: List<MlsDeviceRef> = emptyList(),
    /** v1.16 (proposal 2026-10-07-dm-device-readd §2.2): the DM `devices` op this commit completes, or null. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("op_id") val opId: String? = null,
)

@Serializable
data class MlsCommitReply(val epoch: Long)

/** v1.16 `mls_dm_op`: the server named this device to commit a DM `devices` op. */
@Serializable
data class MlsDmOpEvent(@SerialName("conversation_id") val conversationId: String, val generation: Long, val op: PendingOp)

/** v1.16 `POST /mls/groups/{dm}/rejoin` → 202. `candidates` 0 = nobody can re-add this device (reset). */
@Serializable
data class DmRejoinReply(val op: PendingOp? = null, val candidates: Int = 0)

@Serializable
data class MlsLoggedCommit(
    val epoch: Long,
    val commit: String? = null,
    @SerialName("from_device") val fromDevice: String,
    /** §12.8: a commit over 64 KiB, by blob reference. */
    @SerialName("commit_ref") val commitRef: BlobRef? = null,
)

@Serializable
data class MlsCommitsReply(
    val commits: List<MlsLoggedCommit>,
    /** §12.8: absent = false. */
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** §10.3 msg:send for an e2ee conversation (no body). */
@Serializable
data class MsgSendE2ee(
    @SerialName("client_msg_id") val clientMsgId: String,
    val to: String,
    val ciphertext: String,
    val generation: Long,
    val epoch: Long,
    @SerialName("client_ts") val clientTs: String,
    /** §18.4 only on a `profile_photo` (crypto K7): no push; omitted when false. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val silent: Boolean? = null,
)

@Serializable
data class MlsCommitEvent(
    @SerialName("conversation_id") val conversationId: String,
    val generation: Long,
    val epoch: Long,
    /** Null when [commitRef] is set (§12.7). */
    val commit: String? = null,
    @SerialName("from_device") val fromDevice: String,
    @SerialName("commit_ref") val commitRef: BlobRef? = null,
)

@Serializable
data class MlsWelcomeEvent(
    @SerialName("conversation_id") val conversationId: String,
    val generation: Long,
    val epoch: Long,
    /** Null when [welcomeRef] is set (§12.7). */
    val welcome: String? = null,
    @SerialName("to_devices") val toDevices: List<String>,
    @SerialName("welcome_ref") val welcomeRef: BlobRef? = null,
)

@Serializable
data class MlsMembershipEvent(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    val change: String,
)

@Serializable
data class MlsKeyPackagesLow(val count: Int)

// ---- Emoji and reactions (§11, v1.8) ----

@Serializable
data class ReactionBody(val target: String, val emoji: String, val op: String) {
    companion object {
        const val ADD = "add"
        const val REMOVE = "remove"
    }
}

/** Plaintext reaction: `msg:send` with `reaction` instead of `body` (exactly one content field). */
@Serializable
data class MsgSendReaction(
    @SerialName("client_msg_id") val clientMsgId: String,
    val to: String,
    val reaction: ReactionBody,
    @SerialName("client_ts") val clientTs: String,
)

/** §11.2 inbox event kind `reaction` (plaintext conversations). */
@Serializable
data class ReactionEvent(
    @SerialName("message_id") val messageId: String,
    @SerialName("client_msg_id") val clientMsgId: String,
    @SerialName("conversation_id") val conversationId: String,
    val from: String,
    val to: String,
    val target: String,
    val emoji: String,
    val op: String,
    @SerialName("server_ts") val serverTs: String,
)

// ---- Groups with MLS (§12, v1.9) ----

/** §12.6 a blob reference: clients verify size and SHA-256 after download. */
@Serializable
data class BlobRef(@SerialName("blob_id") val blobId: String, val size: Long, val sha256: String)

@Serializable
data class BlobUploadReply(
    @SerialName("blob_id") val blobId: String,
    val size: Long,
    val sha256: String,
    @SerialName("expires_at") val expiresAt: String? = null,
) {
    fun ref() = BlobRef(blobId, size, sha256)
}

@Serializable
data class GroupMember(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val phone: String? = null,
    val role: String = ROLE_MEMBER,
    val kind: String = KIND_USER,
    val state: String = STATE_ACTIVE,
    @SerialName("joined_at") val joinedAt: String? = null,
) {
    val admin: Boolean get() = role == ROLE_ADMIN

    companion object {
        const val ROLE_ADMIN = "admin"
        const val ROLE_MEMBER = "member"
        const val KIND_USER = "user"
        const val KIND_AGENT = "agent"
        const val STATE_ACTIVE = "active"
        const val STATE_PENDING_ADD = "pending_add"
        const val STATE_PENDING_REMOVE = "pending_remove"
    }
}

@Serializable
data class PendingOp(
    @SerialName("op_id") val opId: String,
    val type: String,
    val actor: String? = null,
    @SerialName("user_ids") val userIds: List<String> = emptyList(),
    val role: String? = null,
    val added: List<MlsDeviceRef> = emptyList(),
    val removed: List<MlsDeviceRef> = emptyList(),
    val committer: MlsDeviceRef? = null,
    @SerialName("committer_until") val committerUntil: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    companion object {
        const val ADD = "add"
        const val REMOVE = "remove"
        const val ROLE = "role"
        const val DEVICES = "devices"
        const val REBUILD = "rebuild"
    }
}

@Serializable
data class Group(
    val id: String,
    val state: String = STATE_ACTIVE,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val generation: Long = 1,
    val epoch: Long? = null,
    @SerialName("my_role") val myRole: String = GroupMember.ROLE_MEMBER,
    val members: List<GroupMember> = emptyList(),
    val pending: List<PendingOp> = emptyList(),
) {
    companion object {
        const val STATE_CREATING = "creating"
        const val STATE_ACTIVE = "active"
    }
}

@Serializable
data class GroupReply(val group: Group)

@Serializable
data class GroupsReply(val groups: List<Group>)

@Serializable
data class GroupCreate(
    @SerialName("client_group_id") val clientGroupId: String,
    @SerialName("member_ids") val memberIds: List<String>,
)

@Serializable
data class GroupMembersAdd(@SerialName("user_ids") val userIds: List<String>)

@Serializable
data class GroupRolePatch(val role: String)

@Serializable
data class GroupReset(val generation: Long)

@Serializable
data class GroupResetReply(val generation: Long)

/** §12.2 the encrypted GroupContext extension `risime.group_meta` (0xFA01), UTF-8 JSON. */
@Serializable
data class GroupMeta(
    val v: Int = 1,
    val name: String,
    /** §14.4 group icon (a blob reference with its key), kept verbatim; v1.11 apps still show the default avatar. */
    val icon: kotlinx.serialization.json.JsonElement? = null,
    val admins: List<String> = emptyList(),
) {
    fun encode(): ByteArray = ProtocolJson.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): GroupMeta? = runCatching { ProtocolJson.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull()
    }
}

/** §12.4 commit request for `grp:` (extends §10.2). */
@Serializable
data class GroupCommitRequest(
    val generation: Long,
    val epoch: Long,
    val commit: String? = null,
    @SerialName("commit_ref") val commitRef: BlobRef? = null,
    val welcome: String? = null,
    @SerialName("welcome_ref") val welcomeRef: BlobRef? = null,
    val added: List<MlsDeviceRef> = emptyList(),
    val removed: List<MlsDeviceRef> = emptyList(),
    @SerialName("op_id") val opId: String? = null,
    @SerialName("meta_changed") val metaChanged: Boolean = false,
)

/** §12.7 `group_event`. */
@Serializable
data class GroupEvent(
    @SerialName("group_id") val groupId: String,
    val generation: Long,
    val epoch: Long? = null,
    val action: String,
    val actor: String,
    val targets: List<String> = emptyList(),
    val role: String? = null,
    val members: List<GroupMember>? = null,
    val rebuilder: MlsDeviceRef? = null,
    @SerialName("server_ts") val serverTs: String? = null,
) {
    companion object {
        const val CREATED = "created"
        const val ADDED = "added"
        const val REMOVED = "removed"
        const val LEFT = "left"
        const val ROLE_CHANGED = "role_changed"
        const val METADATA_CHANGED = "metadata_changed"
        const val ADD_EXPIRED = "add_expired"
        const val RESET = "reset"
    }
}

/** §12.7 `group_op`: a committer was named; only the device in `op.committer` acts. */
@Serializable
data class GroupOpEvent(@SerialName("group_id") val groupId: String, val generation: Long, val op: PendingOp)

/** §12.7 aggregated receipts (to the sender's user only). */
@Serializable
data class GroupReceiptEvent(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("client_msg_id") val clientMsgId: String? = null,
    val delivered: Int,
    val read: Int,
    val of: Int,
    @SerialName("all_delivered") val allDelivered: Boolean,
    @SerialName("all_read") val allRead: Boolean,
    val at: String? = null,
)

@Serializable
data class GroupReceipt(
    @SerialName("user_id") val userId: String,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
)

@Serializable
data class GroupReceiptsReply(val of: Int, val receipts: List<GroupReceipt>)

/** §12.9 group msg:send (`conversation_id` instead of `to`; always ciphertext). */
@Serializable
data class MsgSendGroup(
    @SerialName("client_msg_id") val clientMsgId: String,
    @SerialName("conversation_id") val conversationId: String,
    val ciphertext: String,
    val generation: Long,
    val epoch: Long,
    @SerialName("client_ts") val clientTs: String,
    /** §18.4 only on a `profile_photo` (crypto K7): no push; omitted when false. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val silent: Boolean? = null,
)

/** §12.9 typing in a group. */
@Serializable
data class TypingGroupPush(@SerialName("conversation_id") val conversationId: String, val typing: Boolean)

// ---- Deleting messages and chats (§15, v1.12) ----

/** §15.2 the push `msg:delete` (scope `me` or `everyone`). Absent optional fields are omitted, as in the examples. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class MsgDelete(
    @SerialName("client_msg_id") val clientMsgId: String,
    @SerialName("conversation_id") val conversationId: String,
    val scope: String,
    val targets: List<String>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("blob_ids") val blobIds: List<String>? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val ciphertext: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val generation: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val epoch: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("client_ts") val clientTs: String? = null,
) {
    companion object {
        const val SCOPE_ME = "me"
        const val SCOPE_EVERYONE = "everyone"

        /** §15.2: 1..100 targets per request. */
        const val MAX_TARGETS = 100
    }
}

/** §15.2 reply ok. `message_id`/`server_ts` are null for `me` and when every target is `gone`. */
@Serializable
data class MsgDeleteReply(
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("server_ts") val serverTs: String? = null,
    val deleted: List<String> = emptyList(),
    val gone: List<String> = emptyList(),
)

/** §15.2 reply error: all-or-nothing, with the per-target failures. */
@Serializable
data class DeleteError(val reason: String, val failures: List<DeleteFailure> = emptyList())

@Serializable
data class DeleteFailure(val target: String, val reason: String)

/** §15.5 one target of a `delete` event (`from`/`server_ts` null unless this user had it and it was deleted). */
@Serializable
data class DeleteTarget(
    @SerialName("message_id") val messageId: String,
    val from: String? = null,
    @SerialName("server_ts") val serverTs: String? = null,
)

/** §15.5 the event kind `delete`. */
@Serializable
data class DeleteEvent(
    @SerialName("message_id") val messageId: String,
    @SerialName("client_msg_id") val clientMsgId: String,
    @SerialName("conversation_id") val conversationId: String,
    val from: String,
    val to: String? = null,
    @SerialName("from_device") val fromDevice: String? = null,
    val targets: List<DeleteTarget>,
    val ciphertext: String? = null,
    val generation: Long? = null,
    val epoch: Long? = null,
    @SerialName("server_ts") val serverTs: String,
) {
    val encrypted: Boolean get() = ciphertext != null
}

/** §15.9 the push `chat:clear`. */
@Serializable
data class ChatClear(@SerialName("conversation_id") val conversationId: String, val upto: String)

// ---- Voice calls, 1:1 (§16, v1.13) ----

/** §16.3 the push `call:signal` (an ephemeral MLS call envelope; never stored as a message). */
@Serializable
data class CallSignalPush(
    @SerialName("client_msg_id") val clientMsgId: String,
    /** §16.3 the DM peer; null for a group signal (§20.3: exactly one of `to` and `conversation_id`). */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val to: String? = null,
    @SerialName("call_id") val callId: String,
    val ring: Boolean,
    val ciphertext: String,
    val generation: Long,
    val epoch: Long,
    @SerialName("client_ts") val clientTs: String,
    /** §19.2 the call's media on every signal (absent = audio; omitted for voice calls). */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val media: String? = null,
    /** §20.3 the group (`grp:`) of a group call signal, instead of `to`. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("conversation_id") val conversationId: String? = null,
)

/** §16.3 reply ok. */
@Serializable
data class CallSignalReply(
    @SerialName("message_id") val messageId: String,
    @SerialName("server_ts") val serverTs: String,
)

/** §16.3 the event kind `call_signal` (cleartext `call_id`/`ring` are checked against the envelope). */
@Serializable
data class CallSignalEvent(
    @SerialName("message_id") val messageId: String,
    @SerialName("conversation_id") val conversationId: String,
    val from: String,
    /** §16.3 the DM peer; absent in a group signal (§20.3, `conversation_id` is the group). */
    val to: String? = null,
    @SerialName("from_device") val fromDevice: String,
    @SerialName("call_id") val callId: String,
    val ring: Boolean,
    val ciphertext: String,
    val generation: Long,
    val epoch: Long,
    @SerialName("server_ts") val serverTs: String,
    /** §19.2 `audio` or `video` (absent = audio), bound to the envelope (crypto K6). */
    val media: String? = null,
)

/** §16.7 one ICE server of `GET /calls/turn`. */
@Serializable
data class IceServerDto(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

/** §16.7 `GET /api/v1/calls/turn` (never persisted). */
@Serializable
data class CallsTurnReply(
    @SerialName("ice_servers") val iceServers: List<IceServerDto>,
    val ttl: Long,
    @SerialName("expires_at") val expiresAt: String,
)

/** §16 error codes. */
object CallErrors {
    const val CALLS_NOT_READY = "calls_not_ready"
    const val CALLS_UNAVAILABLE = "calls_unavailable"

    /** §19.2: `ring: true` with `media: "video"` to a user with no `video` device. */
    const val VIDEO_NOT_READY = "video_not_ready"

    /** §20.2 `join` of a room that no longer exists (404). */
    const val CALL_ENDED = "call_ended"

    /** §20.2 `join` of a full room (409). */
    const val CALL_FULL = "call_full"

    /** §20.3 a group signal from someone who isn't an active member. */
    const val NOT_MEMBER = "not_member"
}

// ---- Group calls with LiveKit (§20, v1.19) ----

/** §20.2 `POST /api/v1/calls/rooms` (with `X-Device-Id`). */
@Serializable
data class CallsRoomRequest(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("call_id") val callId: String,
    val media: String,
    val action: String,
) {
    companion object {
        const val START = "start"
        const val JOIN = "join"
        const val STATUS = "status"
    }
}

/** §20.2 `start`/`join` → a LiveKit URL and a 10-minute token for `<user_id>/<device_id>` (never persisted or logged). */
@Serializable
data class CallsRoomReply(
    val url: String,
    val room: String,
    val identity: String,
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("max_participants") val maxParticipants: Int,
) {
    override fun toString() = "CallsRoomReply(room=$room, identity=$identity, max=$maxParticipants)" // never the token
}

/** §20.2 `status` (no token): for the chat line's Join (§20.4). */
@Serializable
data class CallsRoomStatusReply(
    val active: Boolean,
    val participants: Int = 0,
    @SerialName("max_participants") val maxParticipants: Int = 0,
)
