package ai.risicloud.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * Contract v1.39 §36 (decision 080): the call-transcription fast path. Each phone uploads only its own
 * microphone in 10–15 s Ogg/Opus chunks while every participant of a 1:1 Official call has allowed.
 * Additive only: the capability, the `/auth/config` switch, the push `call:transcribe` with its signal,
 * the two REST endpoints, three Risi kinds, `call_offer.tab`, the optional `lang` and `User.risi_dev`.
 */

/** §36.1: advertised once the app implements §36.2–§36.7 and renders the §36.5 kinds (only while the switch is on). */
const val CAPABILITY_CALL_TRANSCRIBE = "call_transcribe"

/** §36.1 `call_offer.tab`: set by the caller on a call placed from a 1:1's Official tab; absent = Private. */
const val CALL_TAB_OFFICIAL = "official"

/** §36.3 the client → server push on the inbox topic. */
const val PUSH_CALL_TRANSCRIBE = "call:transcribe"

/** §36.2 one participant's consent. */
@Serializable
data class CallTranscribeConsent(
    @SerialName("user_id") val userId: String,
    val consent: String,
    val at: String? = null,
) {
    companion object {
        const val ALLOWED = "allowed"
        const val PENDING = "pending"
        const val DECLINED = "declined"
    }
}

/** §36.2 the chunk limits the server announces with the session. */
@Serializable
data class CallTranscribeChunkLimits(
    @SerialName("min_ms") val minMs: Int = 5000,
    @SerialName("max_ms") val maxMs: Int = 20000,
    @SerialName("max_bytes") val maxBytes: Int = 262144,
    /** Proposal 2026-10-15-live-call-latency: `"vad"` = a fast session (1–4 s chunks cut at pauses). Absent: 10–15 s. */
    val mode: String? = null,
    @SerialName("overlap_ms") val overlapMs: Int? = null,
) {
    val vad: Boolean get() = mode == MODE_VAD

    companion object {
        const val MODE_VAD = "vad"
    }
}

/** §36.2 the session of one call attempt. */
@Serializable
data class CallTranscribeSession(
    @SerialName("session_id") val sessionId: String,
    @SerialName("call_id") val callId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("chat_id") val chatId: String? = null,
    val state: String,
    @SerialName("requested_by") val requestedBy: String? = null,
    @SerialName("requested_at") val requestedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val participants: List<CallTranscribeConsent> = emptyList(),
    val compare: Boolean = false,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    val by: String? = null,
    val reason: String? = null,
    val discard: Boolean = false,
    val chunk: CallTranscribeChunkLimits = CallTranscribeChunkLimits(),
    @SerialName("server_ts") val serverTs: String? = null,
) {
    val final: Boolean get() = state in CallTranscribeStates.FINAL
    fun consentOf(userId: String): String? = participants.firstOrNull { it.userId == userId }?.consent
}

/** §36.2 states and reasons. */
object CallTranscribeStates {
    const val REQUESTED = "requested"
    const val ON = "on"
    const val DECLINED = "declined"
    const val EXPIRED = "expired"
    const val STOPPED = "stopped"
    const val ENDED = "ended"

    /** v1.45 §40.9 a group call's joiner hasn't allowed yet: not final, uploads are refused. */
    const val PAUSED = "paused"
    val FINAL = setOf(DECLINED, EXPIRED, STOPPED, ENDED)

    const val REASON_STOPPED = "stopped"
    const val REASON_ENDED = "ended"
    const val REASON_PRIVATE_CALL = "private_call"
}

/** §36.3 push actions. */
object CallTranscribeActions {
    const val REQUEST = "request"
    const val ALLOW = "allow"
    const val DECLINE = "decline"
    const val STOP = "stop"
}

/**
 * §36.3 one `call:transcribe` push. Encoded with [toJson] in exactly the example shapes: `request` has no
 * `session_id`; `decline` carries `reason` (null or "private_call"); `stop` carries `reason` and `discard`.
 */
data class CallTranscribePush(
    val action: String,
    val callId: String,
    val conversationId: String,
    val sessionId: String? = null,
    val reason: String? = null,
    val discard: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("action", action)
        put("call_id", callId)
        put("conversation_id", conversationId)
        if (action != CallTranscribeActions.REQUEST) put("session_id", sessionId)
        when (action) {
            CallTranscribeActions.DECLINE -> put("reason", reason?.let { JsonPrimitive(it) } ?: JsonNull)
            CallTranscribeActions.STOP -> {
                put("reason", reason ?: CallTranscribeStates.REASON_STOPPED)
                put("discard", discard)
            }
        }
    }

    companion object {
        fun request(callId: String, conv: String) = CallTranscribePush(CallTranscribeActions.REQUEST, callId, conv)
        fun allow(callId: String, conv: String, session: String) = CallTranscribePush(CallTranscribeActions.ALLOW, callId, conv, session)
        fun decline(callId: String, conv: String, session: String, reason: String? = null) =
            CallTranscribePush(CallTranscribeActions.DECLINE, callId, conv, session, reason)
        fun stop(callId: String, conv: String, session: String, reason: String = CallTranscribeStates.REASON_STOPPED, discard: Boolean = false) =
            CallTranscribePush(CallTranscribeActions.STOP, callId, conv, session, reason, discard)

        fun parse(o: JsonObject): CallTranscribePush? {
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val a = s("action") ?: return null
            return CallTranscribePush(
                a, s("call_id") ?: return null, s("conversation_id") ?: return null, s("session_id"), s("reason"),
                (o["discard"] as? JsonPrimitive)?.contentOrNull == "true",
            )
        }
    }
}

/** §36.3 the ok reply of the push, and `GET /api/v1/calls/{call_id}/transcribe` (session null = none yet). */
@Serializable
data class CallTranscribeReply(val session: CallTranscribeSession? = null)

/** §36.4 `202` reply of a chunk upload. */
@Serializable
data class CallTranscribeChunkReply(val seq: Int, val accepted: Boolean = true, val duplicate: Boolean = false)

/** §36.3 / §36.4 error codes. */
object CallTranscribeErrors {
    const val TRANSCRIBE_OFF = "transcribe_off"
    const val NOT_OFFICIAL = "not_official"
    const val UNKNOWN_CALL = "unknown_call"
    const val NOT_PARTICIPANT = "not_participant"
    const val INVALID_DEVICE = "invalid_device"
    const val PEER_NOT_READY = "peer_not_ready"
    const val BUSY = "busy"
    const val BAD_STATE = "bad_state"
    const val RATE_LIMITED = "rate_limited"
    const val TRANSCRIBE_UNAVAILABLE = "transcribe_unavailable"
    const val NOT_FOUND = "not_found"
    const val NOT_CONSENTED = "not_consented"
    const val STOPPED = "stopped"
    const val TOO_LARGE = "too_large"
    const val BAD_REQUEST = "bad_request"
    const val NOT_DEVELOPER = "not_developer"
}

/** §36.1 `GET` / `PUT /api/v1/me/risi/dev` (developers only; others get `403 not_developer`). */
@Serializable
data class RisiDevSettings(@SerialName("transcribe_compare") val transcribeCompare: Boolean = false)

/** §36.5 the new Risi kinds. */
object RisiKinds139 {
    const val CALL_TRANSCRIPT = "call_transcript"
    const val CALL_TRANSCRIBE = "call_transcribe"
    const val CALL_TRANSCRIPT_COMPARE = "call_transcript_compare"
    val ALL = setOf(CALL_TRANSCRIPT, CALL_TRANSCRIBE, CALL_TRANSCRIPT_COMPARE)

    /** §36.7: the reader-language hint on `discussion_summary` / `discussion_card` (ignored when unknown). */
    val LANGS = setOf("si", "ta", "en")
}

/** §36.5 `call_transcript`: one speaker's turn. */
@Serializable
data class RisiCallTranscript(
    val kind: String,
    @SerialName("call_id") val callId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val turn: Int = 0,
    val speaker: String? = null,
    val start: String? = null,
    val end: String? = null,
    val text: String = "",
    val language: String? = null,
    val model: String? = null,
    @SerialName("made_by") val madeBy: RisiMadeBy? = null,
    /** v1.40 §36.11: a Sinhala chunk of this turn stayed out of Sinhala script after the service's retry. Not rendered. */
    val romanised: Boolean = false,
)

/** §36.5 `call_transcribe`: the consent system line (once on `on`, once per final transition). */
@Serializable
data class RisiCallTranscribeLine(
    val kind: String,
    @SerialName("call_id") val callId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val state: String,
    @SerialName("requested_by") val requestedBy: String? = null,
    val consents: List<CallTranscribeConsent> = emptyList(),
    val by: String? = null,
    val reason: String? = null,
    val discard: Boolean = false,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    val compare: Boolean = false,
)

/** v1.40 §36.11 the speech service's Sinhala script check of one compare result. */
@Serializable
data class RisiScriptCheck(
    val expected: String = "sinh",
    @SerialName("sinhala_chars") val sinhalaChars: Int = 0,
    @SerialName("latin_chars") val latinChars: Int = 0,
    val ok: Boolean = true,
)

/** §36.5 one model's result in compare mode; v1.40 §36.11 `script` / `romanised` on a Sinhala chunk. */
@Serializable
data class RisiCompareResult(
    val model: String,
    val text: String? = null,
    val language: String? = null,
    @SerialName("elapsed_ms") val elapsedMs: Long = 0,
    val error: String? = null,
    val script: RisiScriptCheck? = null,
    val romanised: Boolean? = null,
)

/** §36.5 `call_transcript_compare` (developer compare mode, one post per chunk with speech). */
@Serializable
data class RisiCallTranscriptCompare(
    val kind: String,
    @SerialName("call_id") val callId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val seq: Int = 0,
    val speaker: String? = null,
    val start: String? = null,
    val end: String? = null,
    @SerialName("language_detected") val languageDetected: String? = null,
    val chosen: String? = null,
    val results: List<RisiCompareResult> = emptyList(),
    @SerialName("made_by") val madeBy: RisiMadeBy? = null,
) {
    /** `chosen` first, then the others in the server's order. */
    val ordered: List<RisiCompareResult> get() = results.sortedBy { if (it.model == chosen) 0 else 1 }
}

/** Parses a stored `risi` JSON object (or a whole envelope) into the §36.5 kinds; null for any other kind. */
object RisiTranscriptKinds {
    private fun risiObj(json: String?): JsonObject? {
        if (json == null) return null
        val o = runCatching { ProtocolJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        return (o["risi"] as? JsonObject) ?: o
    }

    private fun kindOf(o: JsonObject) = (o["kind"] as? JsonPrimitive)?.contentOrNull

    fun transcript(json: String?): RisiCallTranscript? = risiObj(json)?.takeIf { kindOf(it) == RisiKinds139.CALL_TRANSCRIPT }
        ?.let { runCatching { ProtocolJson.decodeFromJsonElement(RisiCallTranscript.serializer(), it) }.getOrNull() }

    fun consentLine(json: String?): RisiCallTranscribeLine? = risiObj(json)?.takeIf { kindOf(it) == RisiKinds139.CALL_TRANSCRIBE }
        ?.let { runCatching { ProtocolJson.decodeFromJsonElement(RisiCallTranscribeLine.serializer(), it) }.getOrNull() }

    fun compare(json: String?): RisiCallTranscriptCompare? = risiObj(json)?.takeIf { kindOf(it) == RisiKinds139.CALL_TRANSCRIPT_COMPARE }
        ?.let { runCatching { ProtocolJson.decodeFromJsonElement(RisiCallTranscriptCompare.serializer(), it) }.getOrNull() }
}
