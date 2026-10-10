package lk.codegen.risime.debug

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import lk.codegen.risime.BuildConfig

/*
 * P0 2026-10-10 the calendar seam for redroid (DEBUG source set only; `checkNoGcalSeamInRelease` proves the release
 * APK has neither the class nor the action). It writes a local calendar (ACCOUNT_TYPE_LOCAL, as its sync adapter:
 * CALLER_IS_SYNCADAPTER) and events into the real CalendarContract provider, so the end-to-end check reads them
 * back through Instances exactly as on a phone. Needs WRITE_CALENDAR granted to the app (pm grant). Protected by
 * android.permission.DUMP: only `adb shell am broadcast` can send it.
 *
 *   adb shell am broadcast -a lk.codegen.risime.debug.CALENDAR_SEED -p lk.codegen.risime.debug --es op calendar \
 *       [--es name "RisiMe Seed"] [--ez sync_events true]
 *   adb shell am broadcast -a lk.codegen.risime.debug.CALENDAR_SEED -p lk.codegen.risime.debug --es op event \
 *       --es title "Standup" --es start 2026-10-12T09:00:00+05:30 --es end 2026-10-12T09:30:00+05:30 \
 *       [--es rrule "FREQ=WEEKLY;COUNT=4"] [--es tz Asia/Colombo]
 *   adb shell am broadcast -a lk.codegen.risime.debug.CALENDAR_SEED -p lk.codegen.risime.debug --es op event \
 *       --es title "Poya" --es date 2026-10-12 --ez all_day true [--ei days 1]
 *   adb shell am broadcast -a lk.codegen.risime.debug.CALENDAR_SEED -p lk.codegen.risime.debug --es op clear
 *
 * The result is the broadcast's result data ("ok calendar_id=7", "ok event_id=12", "ok cleared=1", "error: …"),
 * which `am broadcast` prints as `data="…"`; the log line has ids only, never a title.
 */
object CalendarSeed {
    const val ACTION = "lk.codegen.risime.debug.CALENDAR_SEED"
    const val ACCOUNT = "RisiMe Seed"

    fun asSyncAdapter(uri: Uri, account: String = ACCOUNT): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    /** Start/end of a timed event (ISO-8601 with an offset, or epoch ms), or of an all-day one (UTC midnights of the dates). */
    fun span(start: String?, end: String?, date: String?, allDay: Boolean, days: Int): Pair<Long, Long>? {
        if (allDay) {
            val d = runCatching { java.time.LocalDate.parse(date ?: start ?: return null) }.getOrNull() ?: return null
            val b = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            return b to d.plusDays(days.coerceAtLeast(1).toLong()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        fun ms(s: String?): Long? = s?.toLongOrNull() ?: runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        val b = ms(start) ?: return null
        val e = ms(end) ?: (b + 3_600_000)
        return if (e > b) b to e else null
    }
}

class CalendarSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != CalendarSeed.ACTION) return
        val r = runCatching { run(context, intent) }.getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
        Log.i("RisiMe", "RisiMe debug: calendar seed ${intent.getStringExtra("op")} -> ${r.substringBefore(':').take(80)}")
        resultData = r
    }

    private fun calendarId(context: Context): Long? {
        val cr = context.contentResolver
        cr.query(
            CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(CalendarSeed.ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL), null,
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }

    private fun run(context: Context, intent: Intent): String {
        val cr = context.contentResolver
        return when (intent.getStringExtra("op")) {
            "calendar" -> {
                calendarId(context)?.let { return "ok calendar_id=$it" }
                val name = intent.getStringExtra("name") ?: CalendarSeed.ACCOUNT
                val v = ContentValues().apply {
                    put(CalendarContract.Calendars.ACCOUNT_NAME, CalendarSeed.ACCOUNT)
                    put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    put(CalendarContract.Calendars.NAME, name)
                    put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
                    put(CalendarContract.Calendars.CALENDAR_COLOR, -14069085)
                    put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                    put(CalendarContract.Calendars.OWNER_ACCOUNT, CalendarSeed.ACCOUNT)
                    put(CalendarContract.Calendars.VISIBLE, 1)
                    put(CalendarContract.Calendars.SYNC_EVENTS, if (intent.getBooleanExtra("sync_events", true)) 1 else 0)
                    put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
                }
                val uri = cr.insert(CalendarSeed.asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), v) ?: return "error: calendar insert refused"
                "ok calendar_id=${ContentUris.parseId(uri)}"
            }
            "event" -> {
                val cal = calendarId(context) ?: return "error: no seed calendar (send op=calendar first)"
                val allDay = intent.getBooleanExtra("all_day", false)
                val (b, e) = CalendarSeed.span(intent.getStringExtra("start"), intent.getStringExtra("end"), intent.getStringExtra("date"), allDay, intent.getIntExtra("days", 1))
                    ?: return "error: bad start/end/date"
                val rrule = intent.getStringExtra("rrule")
                val v = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, cal)
                    put(CalendarContract.Events.TITLE, intent.getStringExtra("title") ?: "Seed event")
                    put(CalendarContract.Events.DTSTART, b)
                    put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else intent.getStringExtra("tz") ?: java.util.TimeZone.getDefault().id)
                    put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
                    put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                    if (rrule.isNullOrBlank()) {
                        put(CalendarContract.Events.DTEND, e)
                    } else {
                        // A recurring event has a DURATION instead of a DTEND (the provider's rule).
                        put(CalendarContract.Events.RRULE, rrule)
                        put(CalendarContract.Events.DURATION, if (allDay) "P${(e - b) / 86_400_000}D" else "P${(e - b) / 1000}S")
                    }
                }
                val uri = cr.insert(CalendarSeed.asSyncAdapter(CalendarContract.Events.CONTENT_URI), v) ?: return "error: event insert refused"
                "ok event_id=${ContentUris.parseId(uri)}"
            }
            "clear" -> {
                val n = cr.delete(
                    CalendarSeed.asSyncAdapter(CalendarContract.Calendars.CONTENT_URI),
                    "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                    arrayOf(CalendarSeed.ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL),
                )
                "ok cleared=$n"
            }
            else -> "error: op must be calendar, event or clear"
        }
    }
}
