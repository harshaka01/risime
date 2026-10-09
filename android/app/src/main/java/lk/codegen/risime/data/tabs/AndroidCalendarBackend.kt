package lk.codegen.risime.data.tabs

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

/** [CalendarBackend] over Android's `CalendarContract` (runs off the main thread). */
class AndroidCalendarBackend(private val context: Context) : CalendarBackend {
    private val cr get() = context.contentResolver

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    override fun canRead(): Boolean = granted(Manifest.permission.READ_CALENDAR)

    override fun canWrite(): Boolean = granted(Manifest.permission.WRITE_CALENDAR)

    override fun calendars(): List<PhoneCalendarInfo> {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val out = ArrayList<PhoneCalendarInfo>()
        cr.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { q ->
            while (q.moveToNext()) {
                out += PhoneCalendarInfo(
                    id = q.getLong(0),
                    displayName = q.getString(1).orEmpty(),
                    accountName = q.getString(2).orEmpty(),
                    accountType = q.getString(3).orEmpty(),
                    accessLevel = q.getInt(4),
                    visible = q.getInt(5) != 0,
                    ownerAccount = q.getString(6),
                    isPrimary = !q.isNull(7) && q.getInt(7) != 0,
                )
            }
        }
        return out
    }

    override fun instances(fromMs: Long, toMs: Long): List<BusyRow> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.VISIBLE,
        )
        val out = ArrayList<BusyRow>()
        cr.query(uri, proj, null, null, null)?.use { q ->
            while (q.moveToNext()) {
                if (q.getInt(6) == 0) continue
                if (!q.isNull(4) && q.getInt(4) == CalendarContract.Events.STATUS_CANCELED) continue
                if (!q.isNull(5) && q.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val avail = if (q.isNull(3)) CalendarContract.Events.AVAILABILITY_BUSY else q.getInt(3)
                out += BusyRow(q.getLong(0), q.getLong(1), q.getInt(2) != 0, busy = avail != CalendarContract.Events.AVAILABILITY_FREE)
            }
        }
        return out
    }

    override fun insert(calendarId: Long, title: String, startMs: Long, endMs: Long, allDay: Boolean, timeZone: String): Long? {
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
        }
        val uri = cr.insert(CalendarContract.Events.CONTENT_URI, v) ?: return null
        return runCatching { ContentUris.parseId(uri) }.getOrNull()?.takeIf { it > 0 }
    }

    override fun event(id: Long): EventRow? {
        val proj = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DELETED,
        )
        cr.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), proj, null, null, null)?.use { q ->
            if (!q.moveToFirst()) return null
            if (!q.isNull(6) && q.getInt(6) != 0) return null
            return EventRow(q.getLong(0), q.getLong(1), q.getString(2), q.getLong(3), q.getLong(4), q.getInt(5) != 0)
        }
        return null
    }

    override fun delete(id: Long): Boolean = cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
}

/** [RisiCalendarPort] for the cards and Settings: provider reads off the main thread; [open] shows the event. */
class AndroidCalendarPort(private val context: Context, private val cal: PhoneCalendar) : RisiCalendarPort {
    override val records = cal.writes.records
    override val chosenId = cal.choice.chosen

    override fun hasPermission(): Boolean = cal.canRead() && cal.canWrite()

    override suspend fun options(): List<PhoneCalendarInfo> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cal.options() }

    override suspend fun chosen(): PhoneCalendarInfo? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cal.chosen() }

    override suspend fun choose(id: Long) = cal.choose(id)

    override fun open(eventId: Long) {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure {
            // No calendar app for the event: the calendar app at its day.
            runCatching {
                val day = cal.writes.records.value.values.firstOrNull { it.eventId == eventId }?.start?.let { java.time.Instant.parse(it).toEpochMilli() } ?: System.currentTimeMillis()
                val u = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { b -> ContentUris.appendId(b, day) }.build()
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
