package lk.codegen.risime.calls

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lk.codegen.risime.net.ProtocolJson
import java.time.Instant
import java.util.UUID

/**
 * The MLS call envelopes of contract §16.2 (v1.13). Ephemeral ones go with `call:signal`, the
 * durable `call_end` with the normal e2ee `msg:send`. [decode] is the strict front door: anything
 * that fails a rule is dropped (null) before it rings or is applied.
 */
object CallEnvelope {
    const val VERSION = 1
    const val OFFER = "call_offer"
    const val RINGING = "call_ringing"
    const val ANSWER = "call_answer"
    const val ACCEPTED = "call_accepted"
    const val ICE = "call_ice"
    const val BUSY = "call_busy"
    const val CANCEL = "call_cancel"
    const val END = "call_end"

    /** Every call envelope type (the `call_` namespace). */
    val TYPES = setOf(OFFER, RINGING, ANSWER, ACCEPTED, ICE, BUSY, CANCEL, END)

    const val MEDIA_AUDIO = "audio"

    /** §16.2: every call envelope is at most 20 480 bytes. */
    const val MAX_BYTES = 20_480

    /** §16.2: at most 20 candidates per `call_ice`, each a `candidate:` line of at most 512 bytes. */
    const val MAX_CANDIDATES = 20
    const val MAX_CANDIDATE_BYTES = 512

    const val CANCEL_GLARE = "glare"

    /** §16.2 `call_end` reasons; an unknown one renders as "Voice call". */
    const val R_HANGUP = "hangup"
    const val R_CANCELLED = "cancelled"
    const val R_TIMEOUT = "timeout"
    const val R_DECLINED = "declined"
    const val R_BUSY = "busy"
    const val R_FAILED = "failed"
    val END_REASONS = setOf(R_HANGUP, R_CANCELLED, R_TIMEOUT, R_DECLINED, R_BUSY, R_FAILED)

    data class Candidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)

    sealed interface Env {
        val callId: String
        val type: String
    }

    data class Offer(
        override val callId: String,
        val sdp: String,
        val sentAt: String,
        val restart: Boolean = false,
        /** Only on a restart: the selected peer device. */
        val toDevice: String? = null,
        val media: String = MEDIA_AUDIO,
    ) : Env {
        override val type get() = OFFER
    }

    data class Ringing(override val callId: String) : Env {
        override val type get() = RINGING
    }

    data class Answer(override val callId: String, val toDevice: String, val sdp: String) : Env {
        override val type get() = ANSWER
    }

    data class Accepted(override val callId: String, val deviceId: String) : Env {
        override val type get() = ACCEPTED
    }

    data class Ice(override val callId: String, val toDevice: String?, val candidates: List<Candidate>, val done: Boolean) : Env {
        override val type get() = ICE
    }

    data class Busy(override val callId: String) : Env {
        override val type get() = BUSY
    }

    data class Cancel(override val callId: String, val reason: String = CANCEL_GLARE) : Env {
        override val type get() = CANCEL
    }

    data class End(
        override val callId: String,
        val reason: String,
        val connectedAt: String? = null,
        val durationS: Long? = null,
        val media: String = MEDIA_AUDIO,
    ) : Env {
        override val type get() = END
    }

    /** §16.3 binding: the cleartext `ring` flag the server must carry for this envelope. */
    fun ringFor(env: Env): Boolean = env is Offer && !env.restart

    // ---- encode (UTF-8 JSON, non-ASCII unescaped, §10.3 envelope) ----

    fun encode(env: Env): ByteArray = ProtocolJson.encodeToString(JsonObject.serializer(), toJson(env)).toByteArray(Charsets.UTF_8)

    fun toJson(env: Env): JsonObject = buildJsonObject {
        put("v", VERSION)
        put("type", env.type)
        put("call_id", env.callId)
        when (env) {
            is Offer -> {
                put("media", env.media)
                put("sdp", env.sdp)
                put("restart", env.restart)
                if (env.restart) put("to_device", env.toDevice)
                put("sent_at", env.sentAt)
            }
            is Ringing, is Busy -> Unit
            is Answer -> {
                put("to_device", env.toDevice)
                put("sdp", env.sdp)
            }
            is Accepted -> put("device_id", env.deviceId)
            is Ice -> {
                put("to_device", env.toDevice?.let { JsonPrimitive(it) } ?: JsonNull)
                put(
                    "candidates",
                    buildJsonArray {
                        env.candidates.forEach { c ->
                            add(
                                buildJsonObject {
                                    put("candidate", c.candidate)
                                    put("sdp_mid", c.sdpMid?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put("sdp_m_line_index", c.sdpMLineIndex)
                                },
                            )
                        }
                    },
                )
                put("done", env.done)
            }
            is Cancel -> put("reason", env.reason)
            is End -> {
                put("media", env.media)
                put("reason", env.reason)
                put("connected_at", env.connectedAt?.let { JsonPrimitive(it) } ?: JsonNull)
                put("duration_s", env.durationS?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }
    }

    // ---- decode (strict, §16.2) ----

    /** A lowercase canonical UUID (the `call_id`, device ids). */
    fun isLowerUuid(s: String?): Boolean {
        if (s == null || s.length != 36 || s != s.lowercase()) return false
        return runCatching { UUID.fromString(s).toString() == s }.getOrDefault(false)
    }

    /** Is this decoded JSON a call envelope (by type)? Then [decode] decides valid or drop. */
    fun isCallType(type: String?): Boolean = type != null && type in TYPES

    /**
     * Strict validation (§16.2, §16.10): null = drop and log. [size] is the plaintext byte length
     * (envelopes over [MAX_BYTES] are dropped). [reason] receives why, for the log.
     */
    fun decode(obj: JsonObject, size: Int = 0, reason: (String) -> Unit = {}): Env? {
        fun drop(r: String): Env? {
            reason(r)
            return null
        }
        if (size > MAX_BYTES) return drop("over $MAX_BYTES bytes")
        val v = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull()
        if (v != VERSION) return drop("v")
        fun str(k: String): String? = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun bool(k: String): Boolean? = (obj[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val type = str("type") ?: return drop("type")
        val callId = str("call_id")
        if (!isLowerUuid(callId)) return drop("call_id")
        callId!!
        return when (type) {
            OFFER -> {
                if (str("media") != MEDIA_AUDIO) return drop("media")
                val sentAt = str("sent_at") ?: return drop("sent_at")
                if (runCatching { Instant.parse(sentAt) }.isFailure) return drop("sent_at")
                val restart = when (val r = obj["restart"]) {
                    null, JsonNull -> false
                    else -> bool("restart") ?: return drop("restart")
                }
                val to = str("to_device")
                if (restart && !isLowerUuid(to)) return drop("to_device")
                val sdp = str("sdp") ?: return drop("sdp")
                SdpRules.validate(sdp, SdpRules.Role.OFFER)?.let { return drop("sdp: $it") }
                Offer(callId, sdp, sentAt, restart, if (restart) to else null)
            }
            RINGING -> Ringing(callId)
            ANSWER -> {
                val to = str("to_device")
                if (!isLowerUuid(to)) return drop("to_device")
                val sdp = str("sdp") ?: return drop("sdp")
                SdpRules.validate(sdp, SdpRules.Role.ANSWER)?.let { return drop("sdp: $it") }
                Answer(callId, to!!, sdp)
            }
            ACCEPTED -> {
                val d = str("device_id")
                if (!isLowerUuid(d)) return drop("device_id")
                Accepted(callId, d!!)
            }
            ICE -> {
                val to = when (obj["to_device"]) {
                    null, JsonNull -> null
                    else -> str("to_device")?.takeIf { isLowerUuid(it) } ?: return drop("to_device")
                }
                val arr = obj["candidates"] as? JsonArray ?: return drop("candidates")
                if (arr.size > MAX_CANDIDATES) return drop("too many candidates")
                val cands = arr.map { el ->
                    val o = el as? JsonObject ?: return drop("candidate")
                    val c = (o["candidate"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return drop("candidate")
                    if (!c.startsWith("candidate:") || c.toByteArray().size > MAX_CANDIDATE_BYTES || c.any { it == '\r' || it == '\n' }) return drop("candidate line")
                    val mid = (o["sdp_mid"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    val idx = (o["sdp_m_line_index"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.toInt() ?: 0
                    Candidate(c, mid, idx)
                }
                val done = when (obj["done"]) {
                    null, JsonNull -> false
                    else -> bool("done") ?: return drop("done")
                }
                if (cands.isEmpty() && !done) return drop("empty batch")
                Ice(callId, to, cands, done)
            }
            BUSY -> Busy(callId)
            CANCEL -> Cancel(callId, str("reason") ?: CANCEL_GLARE)
            END -> {
                val reason = str("reason") ?: return drop("reason")
                val connectedAt = str("connected_at")?.takeIf { runCatching { Instant.parse(it) }.isSuccess }
                val dur = (obj["duration_s"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
                End(callId, reason, connectedAt, dur)
            }
            else -> drop("unknown call type $type")
        }
    }

    /** Parse bytes (the decrypted MLS plaintext). */
    fun decode(plaintext: ByteArray, reason: (String) -> Unit = {}): Env? {
        val obj = runCatching { ProtocolJson.parseToJsonElement(plaintext.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: run { reason("not a JSON object"); return null }
        return decode(obj, plaintext.size, reason)
    }
}
