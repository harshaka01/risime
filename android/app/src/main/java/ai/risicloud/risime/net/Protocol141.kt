package ai.risicloud.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * v1.41 §37 voice notes (decision 081): the `voice` capability, the `played` ack, the Risi `voice_transcript`
 * kind, the `transcript_delete` action and the sender's setting (`/me/risi/voice`). Additive only.
 */

/** §37.1: advertised once the app sends/receives/plays voice notes, renders `voice_transcript` and understands `played`. */
const val CAPABILITY_VOICE = "voice"

/** §37.4 the third `msg:ack` status (`delivered` < `read` < `played`). */
const val ACK_PLAYED = "played"

/** §37.7 `GET` / `PUT /api/v1/me/risi/voice` (both default true). */
@Serializable
data class RisiVoiceSettings(
    val transcribe: Boolean = true,
    @SerialName("action_items") val actionItems: Boolean = true,
)

object RisiKinds141 {
    /** §37.6 Risi's transcript of one Official voice note (attached under the note's bubble, collapsed). */
    const val VOICE_TRANSCRIPT = "voice_transcript"

    /** §37.6 `risi_action` by the note's sender: delete only Risi's transcript of it. */
    const val ACTION_TRANSCRIPT_DELETE = "transcript_delete"

    const val STATUS_OK = "ok"
    const val STATUS_UNCLEAR = "unclear"
    const val STATUS_FAILED = "failed"
}

/**
 * §37.6 the `risi` object of a `voice_transcript` (lenient: unknown fields ignored). The script check and the
 * model are never shown to normal users (decision 081; no model names in the UI).
 */
@Serializable
data class RisiVoiceTranscript(
    val kind: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    /** The note's message id. */
    @SerialName("message_id") val messageId: String? = null,
    val speaker: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val status: String = RisiKinds141.STATUS_FAILED,
    val text: String? = null,
    val language: String? = null,
    val script: RisiScriptCheck? = null,
    val romanised: Boolean = false,
    val model: String? = null,
) {
    val ok: Boolean get() = status == RisiKinds141.STATUS_OK && !text.isNullOrBlank()

    companion object {
        /** The transcript in a Risi row's [systemJson], or null when the row is something else. */
        fun parse(systemJson: String?): RisiVoiceTranscript? = systemJson?.let { j ->
            runCatching { ProtocolJson.decodeFromString(serializer(), j) }.getOrNull()?.takeIf { it.kind == RisiKinds141.VOICE_TRANSCRIPT }
        }
    }
}
