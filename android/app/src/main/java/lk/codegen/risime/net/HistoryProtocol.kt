package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- History sharing between devices (§17, v1.15). Binary fields inside envelopes are base64. ----

/** §17.4 a time range (`from`/`to`, ISO-8601 server times). */
@Serializable
data class HistoryRange(val from: String, val to: String)

/** §17.4 the push `history:request` (`history_request_push.json`). */
@Serializable
data class HistoryRequestPush(
    @SerialName("request_id") val requestId: String,
    @SerialName("conversation_id") val conversationId: String,
    /** "own" | "any" */
    val sources: String,
    val range: HistoryRange,
    @SerialName("gap_count") val gapCount: Int,
    val ciphertext: String,
    val generation: Long,
    val epoch: Long,
) {
    companion object {
        const val SOURCES_OWN = "own"
        const val SOURCES_ANY = "any"
    }
}

/** §17.4 one of the caller's own candidate devices ("Open RisiMe on <device name>"). */
@Serializable
data class HistoryOwnDevice(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    val online: Boolean = false,
)

/** §17.4 `history:request` reply ok (`history_request_reply.json`). */
@Serializable
data class HistoryRequestReply(
    @SerialName("request_id") val requestId: String,
    val state: String,
    @SerialName("own_devices") val ownDevices: List<HistoryOwnDevice> = emptyList(),
)

/** §17.4 `request_open` (`error_request_open.json`): this device's open request for the conversation. */
@Serializable
data class HistoryRequestOpenError(val reason: String, @SerialName("request_id") val requestId: String? = null)

/** §17.4 the push `history:refresh` (`history_refresh.json`). */
@Serializable
data class HistoryRefresh(
    @SerialName("request_id") val requestId: String,
    val ciphertext: String,
    val generation: Long,
    val epoch: Long,
)

/** §17.4 the push `history:respond` (`history_respond.json`, `history_respond_stale.json`). */
@Serializable
data class HistoryRespond(
    @SerialName("request_id") val requestId: String,
    /** accept | decline | unable */
    val decision: String,
    /** null | stale | no_data */
    val reason: String? = null,
) {
    companion object {
        const val ACCEPT = "accept"
        const val DECLINE = "decline"
        const val UNABLE = "unable"
        const val REASON_STALE = "stale"
        const val REASON_NO_DATA = "no_data"
    }
}

/** §17.4 the push `history:deliver` (`history_deliver.json`): the `history_share` envelope of one part. */
@Serializable
data class HistoryDeliver(
    @SerialName("request_id") val requestId: String,
    val ciphertext: String,
    val generation: Long,
    val epoch: Long,
    val part: Int,
    val parts: Int,
)

/** §17.4 the push `history:ack` (`history_ack.json`). */
@Serializable
data class HistoryAck(
    @SerialName("request_id") val requestId: String,
    val part: Int,
    /** imported | rejected */
    val result: String,
) {
    companion object {
        const val IMPORTED = "imported"
        const val REJECTED = "rejected"
    }
}

/** §17.4 `history:escalate` / `history:cancel` (`{"request_id"}`). */
@Serializable
data class HistoryRequestRef(@SerialName("request_id") val requestId: String)

/** §17.5 the event kind `history_request` (`event_history_request.json`), to the named devices. */
@Serializable
data class HistoryRequestEvent(
    @SerialName("request_id") val requestId: String,
    @SerialName("conversation_id") val conversationId: String,
    val from: String,
    @SerialName("from_device") val fromDevice: String,
    val range: HistoryRange,
    val intervals: List<HistoryRange> = emptyList(),
    @SerialName("gap_count") val gapCount: Int = 0,
    val ciphertext: String,
    val generation: Long,
    val epoch: Long,
    /** A display hint only (§17.8): own vs member comes from the MLS sender. */
    val consent: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("to_devices") val toDevices: List<String> = emptyList(),
    @SerialName("server_ts") val serverTs: String? = null,
) {
    companion object {
        const val CONSENT_OWN = "own"
        const val CONSENT_MEMBER = "member"
    }
}

/** §17.5 `history_request_closed` (`event_history_request_closed.json`), to every user ever named. */
@Serializable
data class HistoryRequestClosedEvent(
    @SerialName("request_id") val requestId: String,
    @SerialName("conversation_id") val conversationId: String,
    /** accepted_elsewhere | cancelled | expired | done */
    val reason: String,
    @SerialName("server_ts") val serverTs: String? = null,
)

@Serializable
data class HistoryProviderRef(@SerialName("user_id") val userId: String, @SerialName("device_id") val deviceId: String)

/** §17.5 `history_status` to the requesting device (`event_history_status*.json`). */
@Serializable
data class HistoryStatusEvent(
    @SerialName("request_id") val requestId: String,
    @SerialName("conversation_id") val conversationId: String,
    val state: String,
    /** A display hint, from `accepted` on; never the label source (§17.7). */
    val provider: HistoryProviderRef? = null,
    @SerialName("to_devices") val toDevices: List<String> = emptyList(),
    @SerialName("server_ts") val serverTs: String? = null,
)

/** §17.4 the request states (`history_status.state`). */
object HistoryState {
    const val NEW = "new"
    const val SEARCHING = "searching"
    const val WAITING_FOR_MEMBER = "waiting_for_member"
    const val REFRESH = "refresh"
    const val ACCEPTED = "accepted"
    const val RECEIVING = "receiving"
    const val DONE = "done"
    const val UNAVAILABLE = "unavailable"
    const val CANCELLED = "cancelled"
    const val EXPIRED = "expired"

    /** Local only: the request couldn't be sent (refused, no key). */
    const val FAILED = "failed"

    val TERMINAL = setOf(DONE, UNAVAILABLE, CANCELLED, EXPIRED, FAILED)
}

/** §17.5 `history_share` to the requesting device (`event_history_share.json`). */
@Serializable
data class HistoryShareEvent(
    @SerialName("request_id") val requestId: String,
    @SerialName("conversation_id") val conversationId: String,
    val from: String,
    @SerialName("from_device") val fromDevice: String,
    val ciphertext: String,
    val generation: Long,
    val epoch: Long,
    val part: Int,
    val parts: Int,
    @SerialName("to_devices") val toDevices: List<String> = emptyList(),
    @SerialName("server_ts") val serverTs: String? = null,
)

/** §17.9 the `history` line of `GET /blobs/usage` (`blob_usage_reply_history.json`). */
@Serializable
data class HistoryUsage(
    val used: Long,
    val limit: Long,
    @SerialName("uploads_last_hour") val uploadsLastHour: Int? = null,
    @SerialName("hourly_limit") val hourlyLimit: Int? = null,
)

/** §17.7 the bundle's first JSON line (`history_bundle_header.json`). */
@Serializable
data class HistoryBundleHeader(
    val v: Int,
    val type: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("conversation_id") val conversationId: String,
    /** Informative only: the requester takes the provider from the MLS credential. */
    val provider: String,
    val part: Int,
    val parts: Int,
    val count: Int,
) {
    companion object {
        const val TYPE = "history_bundle"
    }
}

/** §17.7 one bundle entry (`history_bundle_entry.json`): `payload` is the original application envelope. */
@Serializable
data class HistoryBundleEntry(
    @SerialName("message_id") val messageId: String,
    @SerialName("client_msg_id") val clientMsgId: String,
    val from: String,
    @SerialName("from_device") val fromDevice: String? = null,
    @SerialName("server_ts") val serverTs: String,
    val payload: kotlinx.serialization.json.JsonObject,
)
