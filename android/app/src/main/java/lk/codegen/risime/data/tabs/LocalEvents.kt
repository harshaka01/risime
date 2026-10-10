package lk.codegen.risime.data.tabs

import lk.codegen.risime.net.RisiEvent
import lk.codegen.risime.net.RisiEventStatus
import lk.codegen.risime.net.RisiLocalEventsRange
import lk.codegen.risime.net.RisiMeta
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Contract v1.32 §29.7 "the user's own events, listed on the phone": an `answer` with `local_events: {from, to}`
 * shows "Your events" under its text on the asker's own phone, in the asker's Risi chat only. The list is built when
 * the message is shown, from the phone's provider (the same calendars and exclusions as the busy read) plus the
 * Risi Calendar events cached on this phone. Titles never leave the phone and are never logged.
 */

/** One event in "Your events". [begin]/[end] are instants (all-day ones at local midnights). */
data class LocalEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val risi: Boolean = false)

sealed interface LocalEventsResult {
    /** Calendar permission is off: "Allow calendar access to see your events". */
    data object NoPermission : LocalEventsResult

    data class Events(val events: List<LocalEvent>, val fromMs: Long, val toMs: Long) : LocalEventsResult

    /** The provider couldn't be read (never shown as "no events"). */
    data object Failed : LocalEventsResult

    /** Not on this screen / device. */
    data object Unavailable : LocalEventsResult
}

object LocalEvents {
    const val HEADER = "Your events"
    const val NO_PERMISSION = "Allow calendar access to see your events"
    const val ALLOW = "Allow"
    const val FAILED = "Your phone's calendar couldn't be read."
    const val ALL_DAY = "All day"
    const val NO_TITLE = "(No title)"
    const val MAX_ROWS = 30
    const val MAX_RANGE_MS = 14L * 86_400_000

    /** "Mon 12 Oct" in English; [dayPattern] is the locale's best "EEEdMMM" pattern in the app. */
    const val DEFAULT_DAY_PATTERN = "EEE d MMM"

    /** The answer's range as epoch ms (null: none, unreadable, empty, or over 14 days). */
    fun range(r: RisiLocalEventsRange?): Pair<Long, Long>? {
        r ?: return null
        val f = runCatching { Instant.parse(r.from).toEpochMilli() }.getOrNull() ?: return null
        val t = runCatching { Instant.parse(r.to).toEpochMilli() }.getOrNull() ?: return null
        return if (t > f && t - f <= MAX_RANGE_MS) f to t else null
    }

    /**
     * Only on the asker's own device, in the Risi chat: the answer names [me] in `notify` (the asker) and the
     * screen is the Risi chat. Never in an Official chat or a group.
     */
    fun shows(r: RisiMeta, me: String, risiChat: Boolean): Boolean =
        risiChat && range(r.localEvents) != null && r.notify.any { it.equals(me, true) }

    /** The Risi Calendar events (cached here) overlapping the range: cancelled and declined ones left out. */
    fun risiEvents(events: List<RisiEvent>, fromMs: Long, toMs: Long): List<LocalEvent> = events.mapNotNull { e ->
        if (e.cancelled || e.myStatus == RisiEventStatus.DECLINED) return@mapNotNull null
        val b = runCatching { Instant.parse(e.start).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
        val en = runCatching { Instant.parse(e.end).toEpochMilli() }.getOrNull() ?: b
        if (!InstanceFilter.overlaps(b, maxOf(en, b + 1), fromMs, toMs)) null else LocalEvent(e.title, b, en, e.allDay, risi = true)
    }

    /** Phone and Risi Calendar events, sorted by start (then end, then title). */
    fun merge(phone: List<LocalEvent>, risi: List<LocalEvent>): List<LocalEvent> =
        (phone + risi).sortedWith(compareBy<LocalEvent>({ it.begin }, { it.end }, { it.title.lowercase() }))

    private fun day(ms: Long, zone: ZoneId, f: DateTimeFormatter) = Instant.ofEpochMilli(ms).atZone(zone).format(f)

    /**
     * One row: "Mon 12 Oct · 10:00–11:00 · Standup", "Thu 15 Oct · All day · Poya", a multi-day all-day event
     * "Thu 15 Oct – Sat 17 Oct · All day · Trip". The title is last, so the ellipsis cuts the title, never the time.
     */
    fun rowText(e: LocalEvent, zone: ZoneId, locale: Locale = Locale.getDefault(), use24h: Boolean = true, dayPattern: String = DEFAULT_DAY_PATTERN): String {
        val df = DateTimeFormatter.ofPattern(dayPattern, locale)
        val tf = DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", locale)
        val title = e.title.trim().ifEmpty { NO_TITLE }
        val start = day(e.begin, zone, df)
        val whenText = if (e.allDay) {
            val lastDay = day(maxOf(e.begin, e.end - 1), zone, df)
            (if (lastDay != start) "$start – $lastDay" else start) + " · " + ALL_DAY
        } else {
            val endDay = day(e.end, zone, df)
            val t0 = Instant.ofEpochMilli(e.begin).atZone(zone).format(tf)
            val t1 = Instant.ofEpochMilli(e.end).atZone(zone).format(tf)
            if (endDay != start && e.end - e.begin >= 86_400_000) "$start $t0 – $endDay $t1" else "$start · $t0–$t1"
        }
        return "$whenText · $title"
    }

    /** "No events in your phone calendars from Mon 12 Oct to Sun 18 Oct" (`to` is exclusive: its previous day). */
    fun emptyText(fromMs: Long, toMs: Long, zone: ZoneId, locale: Locale = Locale.getDefault(), dayPattern: String = DEFAULT_DAY_PATTERN): String {
        val df = DateTimeFormatter.ofPattern(dayPattern, locale)
        return "No events in your phone calendars from ${day(fromMs, zone, df)} to ${day(maxOf(fromMs, toMs - 1), zone, df)}"
    }

    /** The rows shown (at most [MAX_ROWS]) and the "+N more" line (null when everything fits). */
    fun capped(events: List<LocalEvent>): Pair<List<LocalEvent>, String?> =
        if (events.size <= MAX_ROWS) events to null else events.take(MAX_ROWS) to "+${events.size - MAX_ROWS} more"

    /** Builds the list for the range: null phone events mean no permission; a provider error is [LocalEventsResult.Failed]. */
    suspend fun build(fromMs: Long, toMs: Long, phone: suspend () -> List<LocalEvent>?, risi: suspend () -> List<RisiEvent>): LocalEventsResult {
        val p = try {
            phone() ?: return LocalEventsResult.NoPermission
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return LocalEventsResult.Failed
        }
        val r = try {
            risiEvents(risi(), fromMs, toMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return LocalEventsResult.Events(merge(p, r), fromMs, toMs)
    }
}
