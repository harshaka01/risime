package lk.codegen.risime.data.tabs

import lk.codegen.risime.net.RisiPeriod
import lk.codegen.risime.net.RisiSummaryDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Proposal 2026-10-09-risi-30day-summaries (server): "Summarise" for today / 7 days / 30 days / a date
 * range, and the period a summary covers. All fields optional: a v1.27 server without it answers 24 h.
 */
object SummaryPeriods {
    /** The Summarise choices in the Official menu: label to `scope.period` (null = "Date range…"). */
    val CHOICES = listOf("Today" to "today", "Last 7 days" to "7d", "Last 30 days" to "30d", "Date range…" to null)

    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    private fun date(s: String?, zone: ZoneId): LocalDate? = s?.let {
        runCatching { LocalDate.parse(it) }.getOrNull() ?: runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull()
    }

    /** "3–9 Oct", "28 Sep–4 Oct", or one day "9 Oct". */
    fun span(from: LocalDate, to: LocalDate): String = when {
        from == to -> DAY.format(from)
        from.month == to.month -> "${from.dayOfMonth}–${DAY.format(to)}"
        else -> "${DAY.format(from)}–${DAY.format(to)}"
    }

    /** "Last 7 days (3–9 Oct)", "Today (9 Oct)", "12–20 Sep"; null without a period (a 24-h summary). */
    fun periodLine(p: RisiPeriod?, zone: ZoneId = ZoneId.systemDefault()): String? {
        p ?: return null
        val from = date(p.from, zone) ?: return null
        // `to` is exclusive when it is midnight of the next day; inclusive dates otherwise.
        val toInstant = runCatching { Instant.parse(p.to) }.getOrNull()
        val to = (date(p.to, zone) ?: return null).let { d ->
            if (toInstant != null && toInstant.atZone(zone).toLocalTime() == java.time.LocalTime.MIDNIGHT && d.isAfter(from)) d.minusDays(1) else d
        }
        val dates = span(from, to)
        return when (p.scope) {
            "today" -> "Today ($dates)"
            "7d" -> "Last 7 days ($dates)"
            "30d" -> "Last 30 days ($dates)"
            else -> dates
        }
    }

    /** A linked stored summary: "Mon 5 Oct" (a day) or "week 28 Sep–4 Oct". */
    fun dayLabel(d: RisiSummaryDay, zone: ZoneId = ZoneId.systemDefault()): String {
        val from = date(d.date, zone) ?: return d.date
        return if (d.scope == "week") "week " + span(from, date(d.to, zone) ?: from) else WEEKDAY.format(from)
    }

    /** "What Risi knows about me" → Summaries: "Day · 5 Oct" / "Week · 28 Sep–4 Oct". */
    fun factLabel(scope: String?, p: RisiPeriod?, zone: ZoneId = ZoneId.systemDefault()): String? {
        p ?: return null
        val from = date(p.from, zone) ?: return null
        val to = date(p.to, zone) ?: from
        return (if (scope == "week") "Week · " else "Day · ") + span(from, to)
    }
}
