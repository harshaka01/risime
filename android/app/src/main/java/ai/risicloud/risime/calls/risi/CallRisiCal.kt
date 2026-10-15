package ai.risicloud.risime.calls.risi

import ai.risicloud.risime.data.tabs.TitledEvent
import ai.risicloud.risime.net.CallRisiCal152
import ai.risicloud.risime.net.ProtocolJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Where §41.8 reads the phone's events (the container: [ai.risicloud.risime.data.tabs.PhoneCalendar]; a fake in tests). */
interface CallCalSource {
    /** READ_CALENDAR is granted. */
    fun canRead(): Boolean

    /** The provider lists at least one calendar. */
    fun hasCalendars(): Boolean

    /** The events overlapping [fromMs, toMs) (the §29 rows and exclusions). Throws on a provider error. */
    fun events(fromMs: Long, toMs: Long): List<TitledEvent>
}

/**
 * Contract v1.52 §41.8 (proposal 2026-10-15-call-risi-calendar-titles): during a Call Risi, Risi may ask this phone for
 * the events of a range so its **spoken** answer can name the meetings. The phone answers only:
 * - while the Risi call is up (the machine checks it) and only a `risi.cal` packet from `risi_identity`;
 * - a well-formed `cal_req` (an id, `from` < `to`, at most 8 days); everything else is ignored, never answered;
 * - with ≤ 100 events by start, titles ≤ 200 characters or null, from the calendars the §29 tool reads;
 * - `ok: false` with `off` when the user's setting "Risi can say my meeting titles on calls" is off,
 *   `no_permission` without READ_CALENDAR, `no_calendars` when there is none, `error` when the read failed.
 *
 * It never sends anything unasked. Nothing here is logged except counts.
 */
class CallRisiCal(
    private val source: CallCalSource,
    /** The user's setting (default on). */
    private val titlesOn: () -> Boolean,
    private val log: (String) -> Unit = {},
) {
    /** A valid request: [offset] is the one `from` was written in (the reply uses it). */
    data class Request(val id: String, val fromMs: Long, val toMs: Long, val offset: ZoneOffset)

    companion object {
        private const val MAX_PAYLOAD = 4_096
        private const val MAX_ID = 64

        /** The packet is one this phone may answer: the topic, Risi's identity, and the call is up. */
        fun accepts(topic: String?, sender: String?, risiIdentity: String?, callUp: Boolean): Boolean =
            callUp && topic == CallRisiCal152.TOPIC && !risiIdentity.isNullOrBlank() && sender != null &&
                CallRisiRules.allowedRemote(sender, risiIdentity)

        /** A `cal_req` packet, or null when it is malformed (ignored, never answered). */
        fun parseRequest(payload: ByteArray): Request? {
            if (payload.isEmpty() || payload.size > MAX_PAYLOAD) return null
            val o = runCatching { ProtocolJson.parseToJsonElement(payload.decodeToString()) as? JsonObject }.getOrNull() ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (str("t") != CallRisiCal152.T_REQUEST) return null
            val id = str("id")?.takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: return null
            val from = str("from")?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return null
            val to = str("to")?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return null
            val f = from.toInstant().toEpochMilli()
            val t = to.toInstant().toEpochMilli()
            if (t <= f || t - f > CallRisiCal152.MAX_RANGE_MS) return null
            return Request(id, f, t, from.offset)
        }

        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        fun time(ms: Long, offset: ZoneOffset): String =
            OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), offset).truncatedTo(ChronoUnit.SECONDS).format(FORMAT)

        /** A title as sent: control characters out, trimmed, ≤ 200 characters; blank → null. */
        fun title(t: String?): String? =
            t?.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(CallRisiCal152.MAX_TITLE)?.takeIf { it.isNotEmpty() }

        fun ok(req: Request, events: List<TitledEvent>): JsonObject = buildJsonObject {
            put("t", CallRisiCal152.T_REPLY)
            put("id", req.id)
            put("ok", true)
            put("events", buildJsonArray {
                events.filter { it.end > req.fromMs && it.begin < req.toMs && it.end >= it.begin }
                    .sortedWith(compareBy({ it.begin }, { it.end }))
                    .take(CallRisiCal152.MAX_EVENTS)
                    .forEach { e ->
                        add(buildJsonObject {
                            put("start", time(e.begin, req.offset))
                            put("end", time(e.end, req.offset))
                            put("all_day", e.allDay)
                            val t = title(e.title)
                            if (t != null) put("title", t) else put("title", JsonNull)
                            put("busy", e.busy)
                        })
                    }
            })
        }

        fun error(id: String, reason: String): JsonObject = buildJsonObject {
            put("t", CallRisiCal152.T_REPLY)
            put("id", id)
            put("ok", false)
            put("reason", if (reason in CallRisiCal152.REASONS) reason else CallRisiCal152.ERROR)
        }
    }

    /**
     * The reply to one data packet, or null to ignore it. Reads the calendar provider (blocking): call it off the
     * main thread. [callUp]: the Risi call is connected and its screen's call is the current one.
     */
    fun respond(topic: String?, sender: String?, payload: ByteArray, risiIdentity: String?, callUp: Boolean): ByteArray? {
        if (!accepts(topic, sender, risiIdentity, callUp)) return null
        val req = parseRequest(payload) ?: return null
        val reply = when {
            !titlesOn() -> error(req.id, CallRisiCal152.OFF)
            !source.canRead() -> error(req.id, CallRisiCal152.NO_PERMISSION)
            else -> runCatching {
                if (!source.hasCalendars()) error(req.id, CallRisiCal152.NO_CALENDARS) else ok(req, source.events(req.fromMs, req.toMs))
            }.getOrElse { error(req.id, CallRisiCal152.ERROR) }
        }
        val n = (reply["events"] as? JsonArray)?.size
        log("risi call: calendar reply ${if (n != null) "$n events" else (reply["reason"] as? JsonPrimitive)?.contentOrNull}")
        return reply.toString().encodeToByteArray()
    }
}
