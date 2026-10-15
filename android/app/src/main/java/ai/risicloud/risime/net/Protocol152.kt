package ai.risicloud.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Contract v1.52 §41.8 (proposal 2026-10-15-call-risi-calendar-titles): meeting titles from this phone during a Call
 * Risi, over the room's LiveKit data channel (topic `risi.cal`), for Risi's spoken answer only. The packets never touch
 * the server's storage; the logic that answers them is calls/risi/CallRisiCal.kt.
 */

/** Risi → phone: the events of [from, to) (RFC 3339, at most 8 days apart). */
@Serializable
data class CallRisiCalRequest(
    val t: String,
    val id: String,
    val from: String,
    val to: String,
)

/** One event of the reply: [title] ≤ 200 characters, or null for a private or untitled event. */
@Serializable
data class CallRisiCalEvent(
    val start: String,
    val end: String,
    @SerialName("all_day") val allDay: Boolean,
    val title: String?,
    val busy: Boolean,
)

/** Phone → Risi: `ok` with [events] (≤ 100, by start), or not `ok` with [reason]. */
@Serializable
data class CallRisiCalReply(
    val t: String = CallRisiCal152.T_REPLY,
    val id: String,
    val ok: Boolean,
    val events: List<CallRisiCalEvent>? = null,
    val reason: String? = null,
)

object CallRisiCal152 {
    /** The device capability (advertised only with `call_risi`). */
    const val CAP = "call_risi_cal"

    /** The LiveKit data topic of both packets. */
    const val TOPIC = "risi.cal"
    const val T_REQUEST = "cal_req"
    const val T_REPLY = "cal"

    const val NO_PERMISSION = "no_permission"
    const val NO_CALENDARS = "no_calendars"
    const val OFF = "off"
    const val ERROR = "error"
    val REASONS = setOf(NO_PERMISSION, NO_CALENDARS, OFF, ERROR)

    const val MAX_EVENTS = 100
    const val MAX_TITLE = 200
    const val MAX_RANGE_MS = 8L * 24 * 3600_000
    const val REPLY_MS = 2_000L
}
