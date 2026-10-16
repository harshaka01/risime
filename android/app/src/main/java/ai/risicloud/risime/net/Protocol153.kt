package ai.risicloud.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Contract v1.53 §41.10: the caller's English speech recognised on the phone during a Call Risi, sent to Risi as
 * text over the call's LiveKit data channel (topic `risi.stt`, reliable, to `risi_identity` only).
 */
object CallRisiStt153 {
    /** The device capability (valid only together with `call_risi`). */
    const val CAPABILITY = "call_risi_stt"
    const val TOPIC = "risi.stt"

    const val T_PARTIAL = "partial"
    const val T_FINAL = "final"
    const val T_OFF = "off"
    const val T_MODE = "mode"
    const val T_SPEAKING = "speaking"

    const val MODE_PHONE = "phone"
    const val MODE_SERVER = "server"

    /** `off` reasons. */
    const val OFF_UNAVAILABLE = "unavailable"
    const val OFF_ERROR = "error"
    const val OFF_BUSY = "busy"
    const val OFF_LANGUAGE = "language"
    val OFF_REASONS = setOf(OFF_UNAVAILABLE, OFF_ERROR, OFF_BUSY, OFF_LANGUAGE)

    const val MAX_TEXT = 1_000
    const val LANG_EN = "en"

    /** What Risi sends the phone on `risi.stt`. */
    sealed interface Incoming {
        /** `{"t":"mode","mode":"phone"|"server"}`. */
        data class Mode(val mode: String) : Incoming

        /** `{"t":"speaking","on":true|false}`. */
        data class Speaking(val on: Boolean) : Incoming
    }

    /** The wire bytes of a partial (`{"t","seq","text"}`). */
    fun partial(seq: Long, text: String): ByteArray =
        buildJsonObject { put("t", T_PARTIAL); put("seq", seq); put("text", text.take(MAX_TEXT)) }.toString().encodeToByteArray()

    /**
     * The wire bytes of a final: `conf` 0–1 or `null`, `lang` "en", and `during_risi: true` only while Risi's speech is
     * on (absent otherwise, as in `call_risi_stt_final.json`).
     */
    fun final(seq: Long, text: String, conf: Float?, duringRisi: Boolean): ByteArray = buildJsonObject {
        put("t", T_FINAL)
        put("seq", seq)
        put("text", text.take(MAX_TEXT))
        // -1 and 0.0 are what recognisers report when they give no confidence: `null`, never a "low" 0.
        val c = conf?.takeIf { !it.isNaN() && it > 0f }?.coerceAtMost(1f)
        if (c == null) put("conf", JsonNull) else put("conf", Math.round(c * 1000.0) / 1000.0)
        put("lang", LANG_EN)
        if (duringRisi) put("during_risi", true)
    }.toString().encodeToByteArray()

    /** The wire bytes of `{"t":"off","reason":…}`. */
    fun off(reason: String): ByteArray =
        buildJsonObject { put("t", T_OFF); put("reason", reason.takeIf { it in OFF_REASONS } ?: OFF_ERROR) }.toString().encodeToByteArray()

    /** Parses one incoming packet; null for anything malformed or unknown (ignored). */
    fun parseIncoming(payload: ByteArray): Incoming? {
        if (payload.isEmpty() || payload.size > 4_096) return null
        val o = runCatching { ProtocolJson.parseToJsonElement(payload.decodeToString()) as? JsonObject }.getOrNull() ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return when (str("t")) {
            T_MODE -> str("mode")?.takeIf { it == MODE_PHONE || it == MODE_SERVER }?.let { Incoming.Mode(it) }
            T_SPEAKING -> (o["on"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let { Incoming.Speaking(it) }
            else -> null
        }
    }
}

/** §41.10 phone → Risi: a partial of utterance [seq]. */
@Serializable
data class SttPartial(
    @SerialName("t") val t: String = CallRisiStt153.T_PARTIAL,
    @SerialName("seq") val seq: Long,
    @SerialName("text") val text: String,
)

/** §41.10 phone → Risi: the final text of utterance [seq]. `during_risi` only while Risi's speech is on. */
@Serializable
data class SttFinal(
    @SerialName("t") val t: String = CallRisiStt153.T_FINAL,
    @SerialName("seq") val seq: Long,
    @SerialName("text") val text: String,
    @SerialName("conf") val conf: Double?,
    @SerialName("lang") val lang: String = CallRisiStt153.LANG_EN,
    @SerialName("during_risi") val duringRisi: Boolean? = null,
)

/** §41.10 phone → Risi: on-device recognition not available or stopped. */
@Serializable
data class SttOff(
    @SerialName("t") val t: String = CallRisiStt153.T_OFF,
    @SerialName("reason") val reason: String,
)

/** §41.10 Risi → phone (decoded for the contract examples; the app reads them with [CallRisiStt153.parseIncoming]). */
@Serializable
data class SttMode(@SerialName("t") val t: String, @SerialName("mode") val mode: String)

@Serializable
data class SttSpeaking(@SerialName("t") val t: String, @SerialName("on") val on: Boolean)
