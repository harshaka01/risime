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

    /** §19.3 the camera turned on or off in a connected video call (ephemeral). */
    const val MEDIA = "call_media"

    /** §20.3 (android A2) a member's device joined or declined a group call (ephemeral, `ring: false`). */
    const val MEMBER = "call_member"

    /** §23.2 a 1:1 voice↔video switch (ephemeral, `ring: false`). */
    const val SWITCH = "call_switch"

    /** Every call envelope type (the `call_` namespace). */
    val TYPES = setOf(OFFER, RINGING, ANSWER, ACCEPTED, ICE, BUSY, CANCEL, END, MEDIA, MEMBER, SWITCH)

    /** §23.1 per-call `features` on `call_offer`/`call_answer`. */
    const val FEATURE_SWITCH = "switch"
    const val FEATURE_SCREEN = "screen"
    const val MAX_FEATURES = 8
    const val MAX_FEATURE_BYTES = 32

    /** §23.2 `call_switch` actions and request sources. */
    const val SW_REQUEST = "request"
    const val SW_ACCEPT = "accept"
    const val SW_DECLINE = "decline"
    const val SW_CANCEL = "cancel"
    const val SW_VOICE = "voice"
    val SWITCH_ACTIONS = setOf(SW_REQUEST, SW_ACCEPT, SW_DECLINE, SW_CANCEL, SW_VOICE)
    const val SOURCE_CAMERA = "camera"
    const val SOURCE_SCREEN = "screen"
    val SOURCES = setOf(SOURCE_CAMERA, SOURCE_SCREEN)
    const val MAX_SEQ = 65_535

    /** §23.4 `call_media` `video`: what the sender sends now. */
    const val VIDEO_OFF = "off"
    const val VIDEO_CAMERA = "camera"
    const val VIDEO_SCREEN = "screen"
    val VIDEO_STATES = setOf(VIDEO_OFF, VIDEO_CAMERA, VIDEO_SCREEN)

    /** §20.3 `call_offer` `mode` of a group call through the SFU (the only mode value). */
    const val MODE_SFU = "sfu"

    /** §20.3 `call_member` states. */
    const val MEMBER_JOINED = "joined"
    const val MEMBER_DECLINED = "declined"

    /** §20.4 the starter left before anyone joined: devices stop ringing. */
    const val CANCEL_ENDED = "ended"

    const val MEDIA_AUDIO = "audio"
    const val MEDIA_VIDEO = "video"
    val MEDIAS = setOf(MEDIA_AUDIO, MEDIA_VIDEO)

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
        /** Only on a restart or a renegotiation: the selected peer device. */
        val toDevice: String? = null,
        val media: String = MEDIA_AUDIO,
        /** §23.1 what the sending device can do in this call (first offer only). */
        val features: List<String> = emptyList(),
        /** §23.3 the caller's one re-offer that adds `m=video` to a voice session. */
        val renegotiate: Boolean = false,
    ) : Env {
        override val type get() = OFFER
    }

    /** §20.3 a group call's ring: no SDP (the media goes through LiveKit), sent by the starter once it is in the room. */
    data class SfuOffer(override val callId: String, val media: String, val sentAt: String) : Env {
        override val type get() = OFFER
    }

    /** §20.3 `call_member`: the sender's device joined or declined (it names only its sender). */
    data class Member(override val callId: String, val state: String) : Env {
        override val type get() = MEMBER
    }

    data class Ringing(override val callId: String) : Env {
        override val type get() = RINGING
    }

    data class Answer(override val callId: String, val toDevice: String, val sdp: String, val features: List<String> = emptyList()) : Env {
        override val type get() = ANSWER
    }

    data class Accepted(override val callId: String, val deviceId: String) : Env {
        override val type get() = ACCEPTED
    }

    data class Ice(override val callId: String, val toDevice: String?, val candidates: List<Candidate>, val done: Boolean) : Env {
        override val type get() = ICE
    }

    /**
     * §19.3 `call_media`: the sender's camera is on or off (sent only in a connected video call, to
     * the selected peer device). §23.4 [video] `off|camera|screen` (null = absent: derived from [camera]).
     */
    data class Media(override val callId: String, val toDevice: String, val camera: Boolean, val video: String? = null) : Env {
        override val type get() = MEDIA

        /** The sender's video state (§23.4: absent `video` = derived from `camera`). */
        val state: String get() = video ?: if (camera) VIDEO_CAMERA else VIDEO_OFF
    }

    /** §23.2 `call_switch` (1:1): request / accept / decline / cancel / voice, with the sender's [seq]. */
    data class Switch(override val callId: String, val toDevice: String, val seq: Int, val action: String, val source: String? = null) : Env {
        override val type get() = SWITCH
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
        /** Local only, never encoded: did THIS device place the call (null = not known here). Stored in the history line as [CallRecords.DIR]. */
        val outgoing: Boolean? = null,
    ) : Env {
        override val type get() = END
    }

    /** §16.3 binding: the cleartext `ring` flag the server must carry for this envelope. */
    fun ringFor(env: Env): Boolean = (env is Offer && !env.restart && !env.renegotiate) || env is SfuOffer

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
                if (env.renegotiate) put("renegotiate", true)
                if (env.restart || env.renegotiate) put("to_device", env.toDevice)
                if (env.features.isNotEmpty() && !env.restart && !env.renegotiate) put("features", buildJsonArray { env.features.forEach { add(JsonPrimitive(it)) } })
                put("sent_at", env.sentAt)
            }
            is SfuOffer -> {
                put("mode", MODE_SFU)
                put("media", env.media)
                put("sent_at", env.sentAt)
            }
            is Member -> put("state", env.state)
            is Ringing, is Busy -> Unit
            is Answer -> {
                put("to_device", env.toDevice)
                put("sdp", env.sdp)
                if (env.features.isNotEmpty()) put("features", buildJsonArray { env.features.forEach { add(JsonPrimitive(it)) } })
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
            is Media -> {
                put("to_device", env.toDevice)
                put("camera", env.camera)
                env.video?.let { put("video", it) }
            }
            is Switch -> {
                put("to_device", env.toDevice)
                put("seq", env.seq)
                put("action", env.action)
                env.source?.let { put("source", it) }
            }
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
        // §23.1: at most 8 strings of at most 32 bytes; absent = []; unknown values are kept (and ignored by the machine).
        fun features(): List<String>? = when (val f = obj["features"]) {
            null, JsonNull -> emptyList()
            is JsonArray -> if (f.size > MAX_FEATURES) null else f.map { el ->
                val v = (el as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
                if (v.toByteArray(Charsets.UTF_8).size > MAX_FEATURE_BYTES) return null
                v
            }
            else -> null
        }
        val callId = str("call_id")
        if (!isLowerUuid(callId)) return drop("call_id")
        callId!!
        return when (type) {
            OFFER -> {
                val media = str("media")
                if (media !in MEDIAS) return drop("media")
                val sentAt = str("sent_at") ?: return drop("sent_at")
                if (runCatching { Instant.parse(sentAt) }.isFailure) return drop("sent_at")
                // §20.3: `mode` absent = a 1:1 offer; present it must be exactly "sfu" (no sdp, no restart).
                when (val m = obj["mode"]) {
                    null -> Unit
                    else -> {
                        if (str("mode") != MODE_SFU) return drop("mode ${(m as? JsonPrimitive)?.contentOrNull}")
                        if (obj["sdp"] != null) return drop("sdp in an sfu offer")
                        if (obj["restart"].let { it != null && it != JsonNull && bool("restart") != false }) return drop("restart in an sfu offer")
                        return SfuOffer(callId, media!!, sentAt)
                    }
                }
                val restart = when (val r = obj["restart"]) {
                    null, JsonNull -> false
                    else -> bool("restart") ?: return drop("restart")
                }
                // §23.3: `renegotiate` and `restart` are never true together; both name the selected device.
                val renegotiate = when (obj["renegotiate"]) {
                    null, JsonNull -> false
                    else -> bool("renegotiate") ?: return drop("renegotiate")
                }
                if (restart && renegotiate) return drop("restart and renegotiate")
                val to = str("to_device")
                if ((restart || renegotiate) && !isLowerUuid(to)) return drop("to_device")
                val sdp = str("sdp") ?: return drop("sdp")
                // A re-offer (and a restart in a renegotiated call) carries m=audio + m=video under the §19.4 rules.
                if (renegotiate && SdpRules.mLineCount(sdp) != 2) return drop("renegotiate without m=video")
                val twoLines = media == MEDIA_VIDEO || renegotiate || (restart && SdpRules.mLineCount(sdp) == 2)
                SdpRules.validate(sdp, SdpRules.Role.OFFER, video = twoLines)?.let { return drop("sdp: $it") }
                val feats = features() ?: return drop("features")
                Offer(callId, sdp, sentAt, restart, if (restart || renegotiate) to else null, media!!, feats, renegotiate)
            }
            RINGING -> Ringing(callId)
            ANSWER -> {
                val to = str("to_device")
                if (!isLowerUuid(to)) return drop("to_device")
                val sdp = str("sdp") ?: return drop("sdp")
                // §19.4: a two-m-line answer is judged by the video rules; the call machine checks it matches the offer's media.
                SdpRules.validate(sdp, SdpRules.Role.ANSWER, video = SdpRules.mLineCount(sdp) == 2)?.let { return drop("sdp: $it") }
                val feats = features() ?: return drop("features")
                Answer(callId, to!!, sdp, feats)
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
            MEDIA -> {
                val to = str("to_device")
                if (!isLowerUuid(to)) return drop("to_device")
                val camera = bool("camera") ?: return drop("camera")
                // §23.4: `video` optional; when present it must agree with `camera`.
                val video = when (obj["video"]) {
                    null, JsonNull -> null
                    else -> str("video")?.takeIf { it in VIDEO_STATES } ?: return drop("video")
                }
                if (video != null && camera != (video == VIDEO_CAMERA)) return drop("video $video disagrees with camera $camera")
                Media(callId, to!!, camera, video)
            }
            SWITCH -> {
                val to = str("to_device")
                if (!isLowerUuid(to)) return drop("to_device")
                val seq = (obj["seq"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull()
                if (seq == null || seq < 1 || seq > MAX_SEQ) return drop("seq")
                val action = str("action")
                if (action !in SWITCH_ACTIONS) return drop("action")
                val source = when (obj["source"]) {
                    null, JsonNull -> null
                    else -> str("source") ?: return drop("source")
                }
                if ((action == SW_REQUEST) != (source != null)) return drop("source with action $action")
                if (source != null && source !in SOURCES) return drop("source $source")
                Switch(callId, to!!, seq, action!!, source)
            }
            MEMBER -> {
                val state = str("state")
                if (state != MEMBER_JOINED && state != MEMBER_DECLINED) return drop("state")
                Member(callId, state)
            }
            CANCEL -> Cancel(callId, str("reason") ?: CANCEL_GLARE)
            END -> {
                val reason = str("reason") ?: return drop("reason")
                val connectedAt = str("connected_at")?.takeIf { runCatching { Instant.parse(it) }.isSuccess }
                val dur = (obj["duration_s"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
                // §19.3: a video call stays "video" in its call_end; absent (a v1.13 shape) = audio.
                val media = when (obj["media"]) {
                    null, JsonNull -> MEDIA_AUDIO
                    else -> str("media")?.takeIf { it in MEDIAS } ?: return drop("media")
                }
                End(callId, reason, connectedAt, dur, media)
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
