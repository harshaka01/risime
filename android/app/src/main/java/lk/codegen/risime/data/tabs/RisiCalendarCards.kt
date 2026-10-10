package lk.codegen.risime.data.tabs

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.RisiSkillIds
import lk.codegen.risime.net.RisiToolCall
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * P0 (Harsha's phone, contract §25.4/§26.5): the calendar action card's rules. The card shows the event
 * (title, date, start–end, calendar) with [Add] [Edit] [Cancel]; [Add] sends `confirm_write` directly
 * (the phone then runs the tool; no extra model turn); after it, the card becomes "Added to your Google
 * Calendar: … [Open] [Undo]" from the phone's own record. Pure functions; the composables draw them.
 */

/** What the phone's calendar offers the cards and Settings (null in tests and on builds without it). */
interface RisiCalendarPort {
    /** write_id (lowercase) → what this phone added (or why it couldn't). */
    val records: StateFlow<Map<String, CalendarAddRecord>>

    /** The remembered calendar id (DataStore). */
    val chosenId: StateFlow<Long?>

    fun hasPermission(): Boolean

    /** The picker's calendars (off the main thread). */
    suspend fun options(): List<PhoneCalendarInfo>

    /** The remembered pick while it still exists and is writable. */
    suspend fun chosen(): PhoneCalendarInfo?

    /** Remembers the pick (and reports it to the server for the next card's hint). */
    suspend fun choose(id: Long)

    /** The card's `confirm.calendar` hint as a writable calendar on this phone (null: none). */
    suspend fun matchHint(hint: lk.codegen.risime.net.RisiCalendarRef?): PhoneCalendarInfo? = null

    /** The calendar app at that event. */
    fun open(eventId: Long)

    /** Settings → Calendar → Details: the calendars this phone can see (null: no read permission). */
    suspend fun overview(): CalendarOverview? = null

    /** v1.32 Details "Open sync settings": `Settings.ACTION_SYNC_SETTINGS` with `EXTRA_ACCOUNT_TYPES` for [accountType] (null: all). */
    fun openSyncSettings(accountType: String?) {}

    /** v1.32 Details "Refresh": `requestSync` (manual, expedited) for the account (null: every calendar account). */
    suspend fun refreshSync(accountName: String?, accountType: String?) {}
}

/** The event a `calendar_add` card proposes. */
data class CalendarProposal(val title: String, val start: Instant, val end: Instant, val allDay: Boolean)

object RisiCalendarCards {
    private val DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val DATE_Y = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    fun isCalendarAdd(r: RisiMeta): Boolean = r.tool == RisiToolCall.TOOL_CALENDAR_ADD

    private fun ts(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** From the v1.26 `args`, else the v1.25 `text` + `when`. */
    fun proposal(r: RisiMeta): CalendarProposal? {
        r.args?.let { a ->
            val title = (a["title"] as? JsonPrimitive)?.contentOrNull
            val s = ts((a["start"] as? JsonPrimitive)?.contentOrNull)
            val e = ts((a["end"] as? JsonPrimitive)?.contentOrNull)
            val allDay = (a["all_day"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (title != null && s != null && e != null) return CalendarProposal(title, s, e, allDay)
        }
        val w = r.confirmWhen() ?: return null
        val s = ts(w.start) ?: return null
        val e = ts(w.end) ?: s.plusSeconds(3600)
        return CalendarProposal(r.text ?: return null, s, e, w.allDay)
    }

    private fun hour(t: ZonedDateTime): String {
        val h = (t.hour % 12).let { if (it == 0) 12 else it }
        return if (t.minute == 0) "$h" else "$h:%02d".format(t.minute)
    }

    private fun ampm(t: ZonedDateTime) = if (t.hour < 12) "AM" else "PM"

    fun dateText(i: Instant, zone: ZoneId): String = DATE.format(i.atZone(zone))

    /** "2–3 PM", "11 AM–12:30 PM". */
    fun timeRange(start: Instant, end: Instant, zone: ZoneId): String {
        val s = start.atZone(zone)
        val e = end.atZone(zone)
        if (s.toLocalDate() != e.toLocalDate()) return "${hour(s)} ${ampm(s)} – ${DATE.format(e)}, ${hour(e)} ${ampm(e)}"
        return if (ampm(s) == ampm(e)) "${hour(s)}–${hour(e)} ${ampm(e)}" else "${hour(s)} ${ampm(s)}–${hour(e)} ${ampm(e)}"
    }

    /** "Mon 12 Oct, 2–3 PM" / "Mon 12 Oct, all day" (the phone's dates, as [PhoneCalendar] stores an all-day event). */
    fun whenText(p: CalendarProposal, zone: ZoneId): String {
        if (p.allDay) {
            val d0 = p.start.atZone(zone).toLocalDate()
            val e = p.end.atZone(zone)
            val d1 = if (e.toLocalTime() == LocalTime.MIDNIGHT) e.toLocalDate().minusDays(1) else e.toLocalDate()
            val f = { d: LocalDate -> DATE.format(d) }
            return if (!d1.isAfter(d0)) "${f(d0)}, all day" else "${f(d0)} – ${f(d1)}, all day"
        }
        return "${dateText(p.start, zone)}, ${timeRange(p.start, p.end, zone)}"
    }

    /** "Added to your Google Calendar: Interview with Shenika · Mon 12 Oct, 2–3 PM". */
    fun addedText(rec: CalendarAddRecord, zone: ZoneId): String {
        val p = CalendarProposal(rec.title, Instant.parse(rec.start), Instant.parse(rec.end), rec.allDay)
        val where = if (rec.accountType == CalendarSelection.GOOGLE) "your Google Calendar" else (rec.calendarName ?: "your calendar")
        return "Added to $where: ${rec.title} · ${whenText(p, zone)}"
    }

    /** The `skill_done` for this card's request (its [Undo] token lives there), if this phone has it. */
    fun skillDoneFor(r: RisiMeta, messages: List<MessageEntity>): RisiMeta? {
        val rid = r.requestId ?: return null
        return messages.asSequence().mapNotNull { RisiMessages.meta(it) }
            .firstOrNull { it.kind == RisiKinds.SKILL_DONE && it.skillId == RisiSkillIds.CALENDAR && it.requestId.equals(rid, true) }
    }

    /** Whether a calendar `skill_done` belongs to an action card on screen (then the card carries Open/Undo). */
    fun hasActionCard(done: RisiMeta, messages: List<MessageEntity>): Boolean {
        val rid = done.requestId ?: return false
        return messages.any { m -> RisiMessages.meta(m)?.let { it.kind == RisiKinds.CONFIRM && isCalendarAdd(it) && it.requestId.equals(rid, true) } == true }
    }

    /** The phone's record for an allowed add (no card): matched by request id. */
    fun recordForRequest(requestId: String?, records: Map<String, CalendarAddRecord>): CalendarAddRecord? =
        requestId?.let { rid -> records.values.filter { it.requestId.equals(rid, true) && it.added }.maxByOrNull { it.at } }

    private val WIRE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).withZone(java.time.ZoneOffset.UTC)

    /**
     * Proposal 2026-10-09-risi-action-loop §4: the `confirm_write` `edit` of [Edit] — the corrected
     * `title`, `start`, `end`, `all_day` (wire timestamps; an all-day event spans the phone's day).
     */
    fun editArgs(title: String, date: LocalDate, start: LocalTime, durationMin: Int, allDay: Boolean, zone: ZoneId): kotlinx.serialization.json.JsonObject {
        val s = if (allDay) date.atStartOfDay(zone) else date.atTime(start).atZone(zone)
        val e = if (allDay) date.plusDays(1).atStartOfDay(zone) else s.plusMinutes(durationMin.toLong())
        return kotlinx.serialization.json.buildJsonObject {
            put("title", JsonPrimitive(title.trim()))
            put("start", JsonPrimitive(WIRE_TS.format(s.toInstant())))
            put("end", JsonPrimitive(WIRE_TS.format(e.toInstant())))
            put("all_day", JsonPrimitive(allDay))
        }
    }

    /**
     * [Edit] as a new request (fallback before the `edit` field; kept for reference/tests) (the contract's `confirm_write` has no edited args): the card is
     * cancelled and Risi gets the exact event to propose again.
     */
    fun editRequest(title: String, date: LocalDate, start: LocalTime, durationMin: Int, allDay: Boolean): String {
        val t = title.trim().replace("\"", "'")
        if (allDay) return "Add \"$t\" to my calendar on ${DATE_Y.format(date)}, all day"
        val end = start.plusMinutes(durationMin.toLong())
        return "Add \"$t\" to my calendar on ${DATE_Y.format(date)} from ${HM.format(start)} to ${HM.format(end)}"
    }
}
