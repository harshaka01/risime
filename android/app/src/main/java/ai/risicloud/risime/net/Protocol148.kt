package ai.risicloud.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Proposal 2026-10-14-transcribe-progress (to be applied as v1.48 §37.11): the ephemeral `voice_transcribing` signal
 * (sent only to the note's sender) and the sender's Stop / Try again (`POST /me/risi/voice/cancel|retry`). Additive:
 * old servers never send the signal, so nothing changes against them.
 */

/** The `voice_transcribing` signal data. */
@Serializable
data class VoiceTranscribing(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String,
    val state: String,
    val language: String? = null,
    val seq: Int = 0,
    @SerialName("can_cancel") val canCancel: Boolean = false,
    @SerialName("can_retry") val canRetry: Boolean = false,
    @SerialName("server_ts") val serverTs: String? = null,
) {
    companion object {
        const val WORKING = "working"
        const val DONE = "done"
        const val CANCELLED = "cancelled"
        const val FAILED = "failed"
        val STATES = setOf(WORKING, DONE, CANCELLED, FAILED)
    }
}

/** Body of `POST /api/v1/me/risi/voice/cancel` and `/retry`. */
@Serializable
data class VoiceTranscribeTarget(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String,
)

/** `200 {"state": "cancelled", "can_retry"}` (cancel) / `202 {"state": "working"}` (retry). */
@Serializable
data class VoiceTranscribeReply(
    val state: String? = null,
    @SerialName("can_retry") val canRetry: Boolean = false,
)
