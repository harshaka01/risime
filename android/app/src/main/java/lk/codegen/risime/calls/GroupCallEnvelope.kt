package lk.codegen.risime.calls

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lk.codegen.risime.net.ProtocolJson
import java.time.Instant

/**
 * §20.4 the durable, silent `group_call` envelope (a normal group `msg:send` with `silent: true`):
 * `started` by the starter, `ended` by the last device to leave (or the starter's 45-s timeout).
 * The group's call history; [validate] is the strict front door (anything else is dropped and logged).
 */
data class GroupCallEnvelope(
    val callId: String,
    val media: String,
    val state: String,
    val reason: String? = null,
    val connectedAt: String? = null,
    val durationS: Long? = null,
    /** v1.27 §27.7: `"listen"` on a `started` whose starter chose Risi listening; null = absent ("off"). */
    val risi: String? = null,
) {
    val video: Boolean get() = media == CallEnvelope.MEDIA_VIDEO

    fun toJson(): JsonObject = buildJsonObject {
        put("v", 1)
        put("type", TYPE)
        put("call_id", callId)
        put("media", media)
        put("state", state)
        if (state == STARTED) risi?.let { put("risi", it) }
        if (state == ENDED) {
            put("reason", reason)
            put("connected_at", connectedAt?.let { JsonPrimitive(it) } ?: JsonNull)
            put("duration_s", durationS?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }

    fun encode(): ByteArray = ProtocolJson.encodeToString(JsonObject.serializer(), toJson()).toByteArray(Charsets.UTF_8)

    companion object {
        const val TYPE = "group_call"
        const val STARTED = "started"
        const val ENDED = "ended"
        const val R_HANGUP = "hangup"
        const val R_TIMEOUT = "timeout"

        fun started(callId: String, media: String) = GroupCallEnvelope(callId, media, STARTED)

        /** §20.4: `connected_at`/`duration_s` are null for `timeout`. */
        fun ended(callId: String, media: String, reason: String, connectedAt: String?, durationS: Long?) =
            if (reason == R_TIMEOUT) GroupCallEnvelope(callId, media, ENDED, R_TIMEOUT) else GroupCallEnvelope(callId, media, ENDED, reason, connectedAt, durationS)

        /** Strict validation (§20.4): null = drop and log; [why] gets the reason. */
        fun validate(obj: JsonObject, why: (String) -> Unit = {}): GroupCallEnvelope? {
            fun drop(r: String): GroupCallEnvelope? {
                why(r)
                return null
            }
            fun str(k: String): String? = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val v = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull()
            if (v != 1) return drop("v")
            if (str("type") != TYPE) return drop("type")
            val callId = str("call_id")
            if (!CallEnvelope.isLowerUuid(callId)) return drop("call_id")
            val media = str("media")
            if (media !in CallEnvelope.MEDIAS) return drop("media")
            return when (str("state")) {
                STARTED -> GroupCallEnvelope(callId!!, media!!, STARTED, risi = str("risi")?.takeIf { it.length <= 32 })
                ENDED -> {
                    val reason = str("reason")
                    if (reason != R_HANGUP && reason != R_TIMEOUT) return drop("reason")
                    // Display-only claims of the sender: a malformed one is ignored, not fatal.
                    val connectedAt = str("connected_at")?.takeIf { runCatching { Instant.parse(it) }.isSuccess }
                    val dur = (obj["duration_s"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..(24 * 3600L) }
                    if (reason == R_TIMEOUT) GroupCallEnvelope(callId!!, media!!, ENDED, R_TIMEOUT) else GroupCallEnvelope(callId!!, media!!, ENDED, reason, connectedAt, dur)
                }
                else -> drop("state")
            }
        }

        fun decode(json: String?): GroupCallEnvelope? = json?.let {
            runCatching { ProtocolJson.parseToJsonElement(it) as? JsonObject }.getOrNull()?.let { o -> validate(o) }
        }
    }
}

/**
 * §20.4 the group call's history line (one row per `call_id`, `kind = 'call'`): created by
 * `started`, updated by the first `ended`; later duplicates are ignored. [running] is the last
 * `status` answer (null = not checked yet: a fresh line counts as running).
 */
object GroupCallLines {
    /** A local-only flag in the stored line JSON: `status` said the room is gone (never sent). */
    const val LOCAL_OVER = "x_over"

    /** The stored line's JSON says `status` found the room gone. */
    fun over(systemJson: String?): Boolean = systemJson != null && runCatching {
        ((ProtocolJson.parseToJsonElement(systemJson) as? JsonObject)?.get(LOCAL_OVER) as? JsonPrimitive)?.contentOrNull == "true"
    }.getOrDefault(false)
    /** §20.4: a `started` line without `ended` is checked with `status` only while under 4 h old. */
    const val RUNNING_CHECK_MS = 4 * 3_600_000L

    fun startedText(starterName: String, video: Boolean): String = "$starterName started a ${if (video) "video" else "voice"} call"

    fun text(env: GroupCallEnvelope, starterName: String, starterIsMe: Boolean, running: Boolean): String {
        val call = if (env.video) CallLines.VIDEO_CALL else CallLines.VOICE_CALL
        return when {
            env.state == GroupCallEnvelope.STARTED && running -> startedText(if (starterIsMe) "You" else starterName, env.video)
            env.state == GroupCallEnvelope.STARTED -> "$call ended"
            env.reason == GroupCallEnvelope.R_TIMEOUT -> if (starterIsMe) "$call · No answer" else if (env.video) CallLines.MISSED_VIDEO else CallLines.MISSED
            else -> env.durationS?.let { "$call · ${CallLines.duration(it)}" } ?: call
        }
    }

    /** The line shows Join (a `started` line whose call is running). */
    fun joinable(env: GroupCallEnvelope?, running: Boolean): Boolean = env?.state == GroupCallEnvelope.STARTED && running
}
