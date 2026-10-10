package lk.codegen.risime.data.calendar

import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.net.CardParticipant
import lk.codegen.risime.net.RisiCalendarCard
import lk.codegen.risime.net.RisiEvent
import lk.codegen.risime.net.RisiEventStatus
import lk.codegen.risime.net.RisiKinds129
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/*
 * §29 the Calendar tab's views (Agenda, Day, Week, Month), the event cards' state and the mini day timeline.
 * Pure functions over the local cache; nothing computed here is sent anywhere (§29.9).
 */

/** One event on one day, with what the rows show. */
data class DayItem(val event: RisiEvent, val startMs: Long, val endMs: Long)

data class AgendaDay(val date: LocalDate, val items: List<DayItem>)

data class MonthCell(val date: LocalDate?, val count: Int, val today: Boolean)

object RisiCalendarViews {
    enum class Mode { AGENDA, DAY, WEEK, MONTH }

    private fun ms(iso: String) = RisiEventRows.ms(iso)

    /** §29.2: declined events are hidden unless "Show declined"; cancelled ones stay (shown "Cancelled"). */
    fun visible(events: List<RisiEvent>, showDeclined: Boolean): List<RisiEvent> =
        events.filter { showDeclined || it.myStatus != RisiEventStatus.DECLINED }

    private fun item(e: RisiEvent): DayItem? {
        val s = ms(e.start) ?: return null
        val end = ms(e.end) ?: return null
        return DayItem(e, s, maxOf(end, s))
    }

    private fun dayBounds(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
        date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** The events touching [date] in [zone]: all-day first, then by start, then by title. */
    fun day(events: List<RisiEvent>, date: LocalDate, zone: ZoneId): List<DayItem> {
        val (a, b) = dayBounds(date, zone)
        return events.mapNotNull(::item)
            .filter { it.startMs < b && (it.endMs > a || (it.endMs == it.startMs && it.startMs >= a)) }
            .sortedWith(compareBy<DayItem>({ !it.event.allDay }, { it.startMs }, { it.event.title }))
    }

    /** Agenda: from [today] on, every day with events (an event over several days on each), at most [days] ahead. */
    fun agenda(events: List<RisiEvent>, today: LocalDate, zone: ZoneId, days: Int = 92): List<AgendaDay> {
        val items = events.mapNotNull(::item)
        if (items.isEmpty()) return emptyList()
        val out = ArrayList<AgendaDay>()
        val last = today.plusDays(days.toLong())
        val dates = sortedSetOf<LocalDate>()
        for (it in items) {
            var d = Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate()
            val endD = Instant.ofEpochMilli(maxOf(it.startMs, it.endMs - 1)).atZone(zone).toLocalDate()
            if (d.isBefore(today)) d = today
            while (!d.isAfter(endD) && !d.isAfter(last)) { dates += d; d = d.plusDays(1) }
        }
        for (d in dates) day(events, d, zone).takeIf { it.isNotEmpty() }?.let { out += AgendaDay(d, it) }
        return out
    }

    /** The 7 days (Monday first) of the week holding [date]. */
    fun week(events: List<RisiEvent>, date: LocalDate, zone: ZoneId): List<AgendaDay> {
        val mon = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0L until 7L).map { mon.plusDays(it) }.map { AgendaDay(it, day(events, it, zone)) }
    }

    /** Month grid, Monday first: leading/trailing cells are empty; each day counts its events. */
    fun month(events: List<RisiEvent>, month: YearMonth, zone: ZoneId, today: LocalDate): List<List<MonthCell>> {
        val first = month.atDay(1)
        val lead = (first.dayOfWeek.value - 1)
        val cells = ArrayList<MonthCell>()
        repeat(lead) { cells += MonthCell(null, 0, false) }
        for (d in 1..month.lengthOfMonth()) {
            val date = month.atDay(d)
            cells += MonthCell(date, day(events, date, zone).size, date == today)
        }
        while (cells.size % 7 != 0) cells += MonthCell(null, 0, false)
        return cells.chunked(7)
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun dateLabel(d: LocalDate): String = DATE.format(d)

    /** "All day", "14:00–15:00", or "Mon 12 Oct 23:00 – Tue 13 Oct 01:00" over midnight. */
    fun timeLabel(e: RisiEvent, zone: ZoneId): String {
        if (e.allDay) return "All day"
        val s = ms(e.start)?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return ""
        val end = ms(e.end)?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return HM.format(s)
        return if (s.toLocalDate() == end.toLocalDate()) "${HM.format(s)}–${HM.format(end)}"
        else "${DATE.format(s)} ${HM.format(s)} – ${DATE.format(end)} ${HM.format(end)}"
    }

    /** "Mon 12 Oct · 14:00–15:00". */
    fun whenLabel(start: String?, end: String?, allDay: Boolean, zone: ZoneId): String {
        val s = start?.let(::ms)?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return ""
        if (allDay) return "${DATE.format(s)} · All day"
        val e = end?.let(::ms)?.let { Instant.ofEpochMilli(it).atZone(zone) }
        return when {
            e == null -> "${DATE.format(s)} · ${HM.format(s)}"
            e.toLocalDate() == s.toLocalDate() -> "${DATE.format(s)} · ${HM.format(s)}–${HM.format(e)}"
            else -> "${DATE.format(s)} ${HM.format(s)} – ${DATE.format(e)} ${HM.format(e)}"
        }
    }

    /** The status words of §29.13 (localised by code in the UI). */
    fun statusLabel(status: String?): String = when (status) {
        RisiEventStatus.PROPOSED -> "Proposed"
        RisiEventStatus.ACCEPTED -> "Accepted"
        RisiEventStatus.DECLINED -> "Declined"
        RisiEvent.STATE_CANCELLED -> "Cancelled"
        null -> ""
        else -> status.replaceFirstChar { it.uppercase() }
    }

    fun eventStatus(e: RisiEvent): String = if (e.cancelled) statusLabel(RisiEvent.STATE_CANCELLED) else statusLabel(e.myStatus)

    fun mark(status: String): String = when (status) {
        RisiEventStatus.ACCEPTED -> "✓"
        RisiEventStatus.DECLINED -> "✗"
        else -> "?"
    }

    /** "With: Shenika ✓, Kamal ?" (everyone but me; names from the phone's own data). Null when only me. */
    fun withLine(participants: List<CardParticipant>, me: String?, nameOf: (String) -> String): String? {
        val others = participants.filter { !it.userId.equals(me, true) && it.userId.isNotBlank() }
        if (others.isEmpty()) return null
        return "With: " + others.joinToString(", ") { "${nameOf(it.userId)} ${mark(it.status)}" }
    }

    fun participantsOf(e: RisiEvent): List<CardParticipant> = e.participants.map { CardParticipant(it.userId, it.status) }

    /** Minutes for the reminder pickers (null = none). */
    val REMINDER_CHOICES: List<Int?> = listOf(null, 0, 5, 10, 15, 30, 60, 120, 1440)

    fun reminderLabel(min: Int?): String = when {
        min == null -> "No reminder"
        min == 0 -> "At start"
        min < 60 -> "$min min before"
        min % 1440 == 0 -> "${min / 1440} day${if (min / 1440 > 1) "s" else ""} before"
        min % 60 == 0 -> "${min / 60} h before"
        else -> "$min min before"
    }
}

/** The mini day timeline of an event card (§29.9). Minutes of the viewer's day. */
data class TimelineBlock(val startMin: Int, val endMin: Int, val self: Boolean, val proposed: Boolean, val clash: Boolean, val google: Boolean = false)

/**
 * v1.31 §31.7 the Google side of a day's timeline (the Google phone only): the day's Google busy blocks (epoch ms; copies
 * are never in them, no titles) and/or a [note] ("Google Calendar not checked", "Google Calendar is checked on Pixel 8").
 */
data class TimelineGoogle(val blocks: List<Pair<Long, Long>> = emptyList(), val note: String? = null)

data class DayTimeline(
    val date: LocalDate, val fromMin: Int, val toMin: Int, val blocks: List<TimelineBlock>, val clashCount: Int, val allDay: Boolean,
    /** §31.7 the caption under the timeline about Google (null: nothing to say). */
    val googleNote: String? = null,
) {
    val clashText: String? get() = when (clashCount) {
        0 -> null
        1 -> "Clashes with 1 event"
        else -> "Clashes with $clashCount events"
    }
}

object RisiCalendarTimeline {
    const val DAY_FROM_MIN = 7 * 60
    const val DAY_TO_MIN = 21 * 60

    /**
     * 07:00–21:00 of the event's day in [zone], widened to the event; the viewer's own events of that day
     * from the local cache (cancelled, declined and all-day ones left out; proposed hatched), this event in
     * the accent, and every other event overlapping it marked as a clash. No titles.
     */
    fun compute(eventId: String?, start: String, end: String, allDay: Boolean, cache: List<RisiEvent>, zone: ZoneId, google: TimelineGoogle? = null): DayTimeline? {
        val s = RisiEventRows.ms(start) ?: return null
        val e = (RisiEventRows.ms(end) ?: (s + 3_600_000L)).coerceAtLeast(s)
        val date = Instant.ofEpochMilli(s).atZone(zone).toLocalDate()
        val day0 = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val day1 = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        fun minOf(ms: Long) = (((ms.coerceIn(day0, day1)) - day0) / 60_000L).toInt()
        val selfFrom = minOf(s)
        val selfTo = if (e >= day1) ((day1 - day0) / 60_000L).toInt() else minOf(e)
        val others = cache.filter {
            !it.eventId.equals(eventId, true) && !it.cancelled && it.myStatus != RisiEventStatus.DECLINED && !it.allDay
        }.mapNotNull { o ->
            val os = RisiEventRows.ms(o.start) ?: return@mapNotNull null
            val oe = RisiEventRows.ms(o.end) ?: return@mapNotNull null
            if (oe <= day0 || os >= day1) return@mapNotNull null
            Triple(os, oe, o.myStatus == RisiEventStatus.PROPOSED)
        }
        val blocks = ArrayList<TimelineBlock>()
        var clashes = 0
        // §31.7 Google busy blocks: grey with a dotted edge; they count in "Clashes with N events".
        for ((gs, ge) in google?.blocks.orEmpty()) {
            if (ge <= day0 || gs >= day1 || ge <= gs) continue
            val clash = !allDay && gs < e && ge > s
            if (clash) clashes++
            blocks += TimelineBlock(minOf(gs), if (ge >= day1) ((day1 - day0) / 60_000L).toInt() else minOf(ge), self = false, proposed = false, clash = clash, google = true)
        }
        for ((os, oe, proposed) in others) {
            val clash = !allDay && os < e && oe > s
            if (clash) clashes++
            blocks += TimelineBlock(minOf(os), if (oe >= day1) ((day1 - day0) / 60_000L).toInt() else minOf(oe), self = false, proposed = proposed, clash = clash)
        }
        val from = if (allDay) DAY_FROM_MIN else kotlin.math.min(DAY_FROM_MIN, (selfFrom / 60) * 60)
        val to = if (allDay) DAY_TO_MIN else kotlin.math.max(DAY_TO_MIN, ((selfTo + 59) / 60) * 60).coerceAtMost(24 * 60)
        if (!allDay) blocks += TimelineBlock(selfFrom, selfTo, self = true, proposed = false, clash = clashes > 0)
        return DayTimeline(date, from, to, blocks.sortedBy { it.startMin }, clashes, allDay, google?.note)
    }
}

/** What an event card shows now: the card, then later `event_update`s and the newer cached event. */
data class EventCardView(
    val eventId: String,
    val version: Int,
    val title: String,
    val start: String?,
    val end: String?,
    val allDay: Boolean,
    val owner: String?,
    val participants: List<CardParticipant>,
    val cancelled: Boolean,
) {
    fun statusOf(user: String?): String? = participants.firstOrNull { it.userId.equals(user, true) }?.status
}

object RisiEventCards {
    fun card(m: MessageEntity): RisiCalendarCard? {
        if (RisiMessages.meta(m) == null) return null
        return RisiCalendarCard.parse(m.systemJson)?.takeIf { it.kind in RisiKinds129.ALL }
    }

    /** The card's event as it stands: [updates] (`event_update`s of the chat) and [cached] when newer. */
    fun view(card: RisiCalendarCard, updates: List<RisiCalendarCard>, cached: RisiEvent?): EventCardView? {
        val id = card.eventId ?: return null
        var v = EventCardView(id, card.version ?: 0, card.title.orEmpty(), card.start, card.end, card.allDay, card.owner, card.participants, card.state == RisiEvent.STATE_CANCELLED)
        for (u in updates) {
            if (u.kind != RisiKinds129.EVENT_UPDATE || !u.eventId.equals(id, true)) continue
            if ((u.version ?: 0) < v.version) continue
            v = v.copy(
                version = u.version ?: v.version, title = u.title ?: v.title, start = u.start ?: v.start, end = u.end ?: v.end,
                allDay = if (u.start != null) u.allDay else v.allDay,
                participants = u.participants.ifEmpty { v.participants },
                cancelled = u.state == RisiEvent.STATE_CANCELLED || u.change == "cancelled" || v.cancelled,
            )
        }
        if (cached != null && cached.version >= v.version) {
            v = v.copy(
                version = cached.version, title = cached.title.ifBlank { v.title }, start = cached.start, end = cached.end, allDay = cached.allDay,
                owner = cached.owner ?: v.owner, participants = RisiCalendarViews.participantsOf(cached).ifEmpty { v.participants }, cancelled = cached.cancelled,
            )
        }
        return v
    }

    /** Every `event_update` among [messages] (oldest first). */
    fun updates(messages: List<MessageEntity>): List<RisiCalendarCard> =
        messages.mapNotNull { m -> card(m)?.takeIf { it.kind == RisiKinds129.EVENT_UPDATE } }

    /** An `event_update` shows no bubble when the chat has a card of that event (invite, event card). */
    fun updateHasCard(update: RisiCalendarCard, messages: List<MessageEntity>): Boolean {
        val id = update.eventId ?: return false
        return messages.any { m ->
            card(m)?.let { c -> c.kind != RisiKinds129.EVENT_UPDATE && c.kind != RisiKinds129.CALENDAR_REMINDER && c.eventId.equals(id, true) } == true
        }
    }

    /** The small line for an `event_update` without a card ("Shenika accepted 'Interview'"). */
    fun updateLine(u: RisiCalendarCard, me: String?, nameOf: (String) -> String): String {
        val who = u.by?.let { if (it.equals(me, true)) "You" else nameOf(it) } ?: "Risi"
        val t = u.title?.let { "'$it'" } ?: "an event"
        return when (u.change) {
            "status" -> {
                val st = u.participants.firstOrNull { it.userId.equals(u.by, true) }?.status
                when (st) {
                    RisiEventStatus.ACCEPTED -> "$who accepted $t"
                    RisiEventStatus.DECLINED -> "$who declined $t"
                    else -> "$who answered $t"
                }
            }
            "time" -> "$who moved $t"
            "title" -> "$who renamed $t"
            "participants" -> "$who changed who's invited to $t"
            "cancelled" -> "$who cancelled $t"
            else -> "$t was updated"
        }
    }

    fun expired(card: RisiCalendarCard, nowMs: Long): Boolean {
        val at = RisiEventRows.ms(card.expiresAt) ?: RisiEventRows.ms(card.start) ?: return false
        return nowMs >= at
    }

    /**
     * [Accept] [Decline] [Suggest another time] for me while my status on the event is `proposed`, the
     * card hasn't expired and the event is active (an invite; an Official event card only for proposed participants).
     */
    fun answerButtons(card: RisiCalendarCard, view: EventCardView, me: String?, nowMs: Long): List<String> {
        if (me == null || view.cancelled || expired(card, nowMs)) return emptyList()
        if (view.statusOf(me) != RisiEventStatus.PROPOSED) return emptyList()
        val allowed = card.buttons.ifEmpty { listOf("accept", "decline", "suggest") }
        return allowed.filter { it == "accept" || it == "decline" || (it == "suggest" && !view.owner.equals(me, true)) }
    }

    /** The added card: [Open] for everyone, [Edit] [Delete] for the owner of an active event. */
    fun addedButtons(card: RisiCalendarCard, view: EventCardView, me: String?): List<String> {
        val owner = view.owner.equals(me, true)
        return card.buttons.ifEmpty { listOf("open", "edit", "delete") }.filter {
            it == "open" || (!view.cancelled && owner && (it == "edit" || it == "delete"))
        }
    }

    /** [Use] [Keep] for the owner while the suggestion is open (this phone hasn't answered it). */
    fun suggestionButtons(card: RisiCalendarCard, me: String?, answered: Set<String>, cached: RisiEvent?): List<String> {
        val sid = card.suggestionId?.lowercase() ?: return emptyList()
        if (sid in answered || me == null) return emptyList()
        if (cached?.cancelled == true) return emptyList()
        // Already moved to the suggested time.
        if (cached != null && card.start != null && RisiCalendar.sameInstant(card.start!!, cached.start)) return emptyList()
        val mine = card.notify.any { it.equals(me, true) } || cached?.owner.equals(me, true)
        return if (mine) card.buttons.ifEmpty { listOf("use", "keep") }.filter { it == "use" || it == "keep" } else emptyList()
    }

    /** "In 30 min: Interview (14:00)" from the card. */
    fun reminderText(card: RisiCalendarCard, zone: ZoneId): String {
        val start = RisiEventRows.ms(card.start)?.let { Instant.ofEpochMilli(it).atZone(zone) }
        val lead = when (val m = card.reminderMin) {
            null -> "Coming up"
            0 -> "Now"
            in 1..59 -> "In $m min"
            else -> if (m % 60 == 0 && m < 1440) "In ${m / 60} h" else if (m % 1440 == 0) "In ${m / 1440} day${if (m / 1440 > 1) "s" else ""}" else "In $m min"
        }
        val at = when {
            start == null -> ""
            card.allDay -> " (all day)"
            else -> " (${DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).format(start)})"
        }
        return "$lead: ${card.title.orEmpty()}$at"
    }

    /** [Suggest another time] prefills the card's time + 1 h. */
    fun suggestDefault(start: String?, end: String?, zone: ZoneId): Pair<ZonedDateTime, Int> {
        val s = RisiEventRows.ms(start)?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: ZonedDateTime.now(zone)
        val dur = RisiEventRows.ms(end)?.let { e -> ((e - (RisiEventRows.ms(start) ?: e)) / 60_000L).toInt() }?.takeIf { it > 0 } ?: 60
        return s.plusHours(1) to dur
    }
}

/** The Calendar tab's position: the view and the day it shows (Agenda starts today). */
data class CalendarNav(val mode: RisiCalendarViews.Mode = RisiCalendarViews.Mode.AGENDA, val anchor: LocalDate) {
    fun next(): CalendarNav = step(1)

    fun prev(): CalendarNav = step(-1)

    private fun step(n: Long): CalendarNav = when (mode) {
        RisiCalendarViews.Mode.AGENDA -> this
        RisiCalendarViews.Mode.DAY -> copy(anchor = anchor.plusDays(n))
        RisiCalendarViews.Mode.WEEK -> copy(anchor = anchor.plusWeeks(n))
        RisiCalendarViews.Mode.MONTH -> copy(anchor = anchor.plusMonths(n).withDayOfMonth(1))
    }

    fun title(): String = when (mode) {
        RisiCalendarViews.Mode.AGENDA -> "Upcoming"
        RisiCalendarViews.Mode.DAY -> DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH).format(anchor)
        RisiCalendarViews.Mode.WEEK -> {
            val mon = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val sun = mon.plusDays(6)
            val f = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
            "${f.format(mon)} – ${f.format(sun)}"
        }
        RisiCalendarViews.Mode.MONTH -> DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH).format(anchor)
    }

    /** The span this view shows (epoch ms in [zone]): what a range list must cover. */
    fun range(zone: ZoneId): Pair<Long, Long> {
        val (a, b) = when (mode) {
            RisiCalendarViews.Mode.AGENDA -> anchor to anchor.plusDays(62)
            RisiCalendarViews.Mode.DAY -> anchor to anchor.plusDays(1)
            RisiCalendarViews.Mode.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(7) }
            RisiCalendarViews.Mode.MONTH -> anchor.withDayOfMonth(1).let { it to it.plusMonths(1) }
        }
        return a.atStartOfDay(zone).toInstant().toEpochMilli() to b.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
