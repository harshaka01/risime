package lk.codegen.risime.data.tabs

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/*
 * P0 2026-10-10 (Harsha's Pixel, "0 events in the next 7 days" on every calendar): the instance rows as the
 * provider returns them, the filters that decide which of them are busy time, and the Calendar diagnostics
 * that show what the provider really holds. Pure Kotlin, so every rule is unit-tested without a device.
 */

/** A provider query failed: never "0 events" (Details and `calendar_check` show it as a read error). */
class CalendarQueryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * One raw `CalendarContract.Instances` row (or a non-recurring `Events` row of a calendar the provider doesn't
 * expand), before any filter. Times are epoch ms; an all-day row's [begin]/[end] are UTC midnights.
 */
data class InstanceRow(
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val availability: Int? = null,
    val status: Int? = null,
    val selfStatus: Int? = null,
    val deleted: Boolean = false,
    val calendarId: Long = 0,
    val visible: Boolean = true,
    val syncId: String? = null,
    /** Only for "Your events" on this phone (v1.32 §29.7); never sent. */
    val title: String? = null,
)

object InstanceFilter {
    /** `CalendarContract.Events.STATUS_CANCELED`. */
    const val STATUS_CANCELED = 2

    /** `CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED`. */
    const val ATTENDEE_STATUS_DECLINED = 2

    /** `CalendarContract.Events.AVAILABILITY_FREE`. */
    const val AVAILABILITY_FREE = 1

    /** Why a row is not busy time ("deleted", "cancelled", "declined"), or null when it counts. Null fields count. */
    fun exclusion(r: InstanceRow): String? = when {
        r.deleted -> "deleted"
        r.status == STATUS_CANCELED -> "cancelled"
        r.selfStatus == ATTENDEE_STATUS_DECLINED -> "declined"
        else -> null
    }

    /** An all-day instant (a UTC midnight) as the same date's local midnight in [zone]. */
    fun localAllDay(utcMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** The row as busy time (null when excluded); all-day rows on the phone's own dates (Monday stays Monday). */
    fun busy(r: InstanceRow, zone: ZoneId): BusyRow? {
        if (exclusion(r) != null) return null
        val (b, e) = if (r.allDay) localAllDay(r.begin, zone) to localAllDay(r.end, zone) else r.begin to r.end
        return BusyRow(b, e, r.allDay, busy = r.availability != AVAILABILITY_FREE, calendarId = r.calendarId, visible = r.visible, syncId = r.syncId)
    }

    /** The rows that count, localized, overlapping [fromMs, toMs). */
    fun busyRows(rows: List<InstanceRow>, zone: ZoneId, fromMs: Long, toMs: Long): List<BusyRow> =
        rows.mapNotNull { busy(it, zone) }.filter { overlaps(it.begin, it.end, fromMs, toMs) }

    fun overlaps(b: Long, e: Long, fromMs: Long, toMs: Long): Boolean = e > fromMs && b < toMs && e >= b

    /**
     * The provider's query window for [fromMs, toMs): one day wider on both sides, because an all-day row's
     * UTC midnights differ from the local day by the zone offset (Asia/Colombo: 5 h 30 min).
     */
    fun queryWindow(fromMs: Long, toMs: Long): Pair<Long, Long> = (fromMs - DAY_MS) to (toMs + DAY_MS)

    const val DAY_MS = 86_400_000L

    /** RFC 2445 `DURATION` as the provider stores it ("P3600S", "PT1H", "P1D", "P1W", "-P…" never). Null: unreadable. */
    fun durationMs(d: String?): Long? {
        val t = d?.trim()?.removePrefix("+") ?: return null
        // "P3600S" (seconds, no T) is what the provider writes for a timed event.
        Regex("^P(\\d+)S$").matchEntire(t)?.let { return it.groupValues[1].toLong() * 1000 }
        val m = Regex("^P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").matchEntire(t) ?: return null
        val g = m.groupValues.drop(1)
        if (g.all { it.isEmpty() }) return null
        val (w, dd, h, mi, s) = g.map { it.toLongOrNull() ?: 0L }
        return ((w * 7 + dd) * 86_400 + h * 3600 + mi * 60 + s) * 1000
    }
}

/** One calendar in the diagnostics. Null: unknown or not readable. */
data class CalendarDiag(
    val info: PhoneCalendarInfo,
    val accountSync: Boolean?,
    val syncState: String?,
    val lastSync: String?,
    /** Raw `Events` rows of the calendar (not deleted), any date. */
    val events: Int?,
    /** Raw `Instances` rows in the window (before the filters). */
    val instances: Int?,
    /** What Details and Risi count (after the deleted / cancelled / declined filters). */
    val counted: Int?,
)

/** Settings → Risi skills → Calendar → Details → "Calendar diagnostics". Kept on the phone; never logged. */
data class CalendarDiagnostics(
    val readGranted: Boolean,
    val writeGranted: Boolean,
    val masterSync: Boolean?,
    val days: Int,
    val calendars: List<CalendarDiag>,
    val errors: List<String>,
) {
    companion object {
        const val TITLE = "Calendar diagnostics"
        const val COPY = "Copy diagnostics"
        const val COPIED = "Copied"
        const val UNKNOWN = "unknown"

        private fun yn(b: Boolean?) = when (b) { null -> UNKNOWN; true -> "on"; false -> "off" }

        private fun n(i: Int?) = i?.toString() ?: UNKNOWN
    }

    /** The lines of the section (also what "Copy diagnostics" copies, one per line). */
    fun lines(): List<String> = buildList {
        add(TITLE)
        add("READ_CALENDAR: ${if (readGranted) "granted" else "not granted"}")
        add("WRITE_CALENDAR: ${if (writeGranted) "granted" else "not granted"}")
        add("Auto-sync: ${yn(masterSync)}")
        add("Window: next $days days")
        add("Calendars: ${calendars.size}")
        calendars.forEach { c ->
            add("")
            add(c.info.displayName.ifBlank { "(no name)" })
            add("  Account: ${c.info.accountName.ifBlank { "(none)" }}")
            add("  Account type: ${c.info.accountType.ifBlank { "(none)" }}")
            add("  VISIBLE: ${if (c.info.visible) 1 else 0} · SYNC_EVENTS: ${if (c.info.syncEvents) 1 else 0}")
            add("  Account sync: ${yn(c.accountSync)} · Sync state: ${c.syncState ?: UNKNOWN} · Last sync: ${c.lastSync ?: UNKNOWN}")
            add("  Events (raw): ${n(c.events)} · Instances next $days days: ${n(c.instances)} · Counted: ${n(c.counted)}")
        }
        add("")
        if (errors.isEmpty()) add("Errors: none") else errors.forEach { add("Error: $it") }
    }

    fun text(): String = lines().joinToString("\n")
}
