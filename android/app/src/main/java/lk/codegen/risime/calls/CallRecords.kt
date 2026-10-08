package lk.codegen.risime.calls

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.ProtocolJson

/** How a call ended, from this user's side (WhatsApp's set). */
enum class CallOutcome { ANSWERED, MISSED, DECLINED, NO_ANSWER, CANCELLED, FAILED }

/** The Calls tab's three direction classes (consecutive calls of one class on one day are grouped). */
enum class CallDirClass { INCOMING, OUTGOING, MISSED }

/** One call-history line (`kind = 'call'`, one per call id) read as a call record. */
data class CallRecord(
    val clientMsgId: String,
    val callId: String,
    val conversationId: String,
    val video: Boolean,
    /** This user placed the call. */
    val outgoing: Boolean,
    val outcome: CallOutcome,
    val durationS: Long?,
    val atMs: Long,
) {
    val missed: Boolean get() = outcome == CallOutcome.MISSED
    val dirClass: CallDirClass get() = if (missed) CallDirClass.MISSED else if (outgoing) CallDirClass.OUTGOING else CallDirClass.INCOMING

    /** The chat row's / info list's label: "Missed voice call", "Voice call · 3 min", "Cancelled video call", … */
    val label: String get() = CallRecords.label(this)
}

object CallRecords {
    /**
     * A local-only key in the stored `call_end` JSON: `"out"` / `"in"` = the direction of the call on
     * this phone. Never sent or exported ([strip]); old rows without it are inferred from the reason.
     */
    const val DIR = "x_dir"

    fun withDir(json: String, outgoing: Boolean?): String {
        if (outgoing == null) return json
        val obj = runCatching { ProtocolJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return json
        return ProtocolJson.encodeToString(JsonObject.serializer(), JsonObject(obj + (DIR to JsonPrimitive(if (outgoing) "out" else "in"))))
    }

    /** The stored envelope without local keys: what goes on the wire or into a bundle. */
    fun strip(json: String): String {
        val obj = runCatching { ProtocolJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return json
        if (DIR !in obj) return json
        return ProtocolJson.encodeToString(JsonObject.serializer(), JsonObject(obj - DIR))
    }

    fun strip(obj: JsonObject): JsonObject = if (DIR in obj) JsonObject(obj - DIR) else obj

    /** Direction by what only the caller (`cancelled`/`timeout`/`busy`) or only the callee (`declined`) sends; null for `hangup`/`failed`. */
    fun inferOutgoing(reason: String, sentByMe: Boolean): Boolean? = when (reason) {
        CallEnvelope.R_CANCELLED, CallEnvelope.R_TIMEOUT, CallEnvelope.R_BUSY -> sentByMe
        CallEnvelope.R_DECLINED -> !sentByMe
        else -> null
    }

    /** [m] (a DM `kind = call` row) as a record; null if it isn't a 1:1 call_end line. */
    fun of(m: MessageEntity): CallRecord? {
        if (!m.call) return null
        val obj = runCatching { ProtocolJson.parseToJsonElement(m.systemJson ?: return null) as? JsonObject }.getOrNull() ?: return null
        if ((obj["type"] as? JsonPrimitive)?.contentOrNull != CallEnvelope.END) return null
        val reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull ?: return null
        val video = (obj["media"] as? JsonPrimitive)?.contentOrNull == CallEnvelope.MEDIA_VIDEO || CallLines.isVideo(m.body)
        val duration = (obj["duration_s"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val out = when ((obj[DIR] as? JsonPrimitive)?.contentOrNull) {
            "out" -> true
            "in" -> false
            else -> inferOutgoing(reason, m.outgoing) ?: m.outgoing
        }
        val outcome = when (reason) {
            CallEnvelope.R_HANGUP -> CallOutcome.ANSWERED
            CallEnvelope.R_CANCELLED -> if (out) CallOutcome.CANCELLED else CallOutcome.MISSED
            CallEnvelope.R_TIMEOUT, CallEnvelope.R_BUSY -> if (out) CallOutcome.NO_ANSWER else CallOutcome.MISSED
            CallEnvelope.R_DECLINED -> CallOutcome.DECLINED
            CallEnvelope.R_FAILED -> if (!out && CallLines.isMissed(m.body)) CallOutcome.MISSED else CallOutcome.FAILED
            else -> CallOutcome.ANSWERED
        }
        val id = m.callId ?: (obj["call_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return CallRecord(m.clientMsgId, id, m.conversationId, video, out, outcome, duration, m.localTs)
    }

    fun label(r: CallRecord): String {
        val kind = if (r.video) "video" else "voice"
        val call = if (r.video) CallLines.VIDEO_CALL else CallLines.VOICE_CALL
        return when (r.outcome) {
            CallOutcome.MISSED -> "Missed $kind call"
            CallOutcome.ANSWERED -> r.durationS?.let { "$call · ${durationText(it)}" } ?: call
            CallOutcome.DECLINED -> if (r.outgoing) "$call · Declined" else "Declined $kind call"
            CallOutcome.NO_ANSWER -> "$call · No answer"
            CallOutcome.CANCELLED -> "Cancelled $kind call"
            CallOutcome.FAILED -> "$call · Couldn't connect"
        }
    }

    /** "12 s", "3 min", "1 h 5 min" (rounded down). */
    fun durationText(s: Long): String = when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> (s % 3600 / 60).let { m -> if (m == 0L) "${s / 3600} h" else "${s / 3600} h $m min" }
    }

    /** "↙" incoming / missed, "↗" outgoing. */
    fun arrow(r: CallRecord): String = if (r.outgoing) "↗" else "↙"

    /**
     * The Calls tab's "Today 14:05", "Yesterday 09:12", else "8 October, 14:05" with [locale]'s
     * month names (a 24-hour clock, as in the chat).
     */
    fun stamp(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val t = Instant.ofEpochMilli(atMs).atZone(zone)
        val time = DateTimeFormatter.ofPattern("HH:mm", locale).format(t)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when (t.toLocalDate()) {
            today -> "Today $time"
            today.minusDays(1) -> "Yesterday $time"
            else -> DateTimeFormatter.ofPattern("d MMMM, HH:mm", locale).format(t)
        }
    }
}

/** A Calls-tab row: consecutive calls with one person, of one direction class, on one day. [calls] newest first. */
data class CallGroup(val conversationId: String, val calls: List<CallRecord>) {
    val latest: CallRecord get() = calls.first()
    val count: Int get() = calls.size
}

/** [records] newest first (any conversations) → rows: "Name (3)" for runs of the same person, class and day. */
fun groupCalls(records: List<CallRecord>, zone: ZoneId = ZoneId.systemDefault()): List<CallGroup> {
    val out = ArrayList<MutableList<CallRecord>>()
    var prevDay: LocalDate? = null
    for (r in records) {
        val day = Instant.ofEpochMilli(r.atMs).atZone(zone).toLocalDate()
        val last = out.lastOrNull()
        if (last != null && last[0].conversationId == r.conversationId && last[0].dirClass == r.dirClass && prevDay == day) last += r
        else out += mutableListOf(r)
        prevDay = day
    }
    return out.map { CallGroup(it[0].conversationId, it) }
}
