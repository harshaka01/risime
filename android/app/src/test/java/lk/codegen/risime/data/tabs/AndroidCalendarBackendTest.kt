package lk.codegen.risime.data.tabs

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId

/** A fake `com.android.calendar` provider: calendars, events and instances as column maps. */
class FakeCalendarProvider : ContentProvider() {
    companion object {
        val calendars = mutableListOf<Map<String, Any?>>()
        val events = linkedMapOf<Long, MutableMap<String, Any?>>()
        var nextId = 500L
        var lastInsert: ContentValues? = null

        fun reset() {
            calendars.clear()
            events.clear()
            nextId = 500L
            lastInsert = null
        }
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = null

    private fun cursor(projection: Array<out String>?, rows: List<Map<String, Any?>>): Cursor {
        val cols = projection ?: rows.firstOrNull()?.keys?.toTypedArray() ?: emptyArray()
        val c = MatrixCursor(cols)
        rows.forEach { r -> c.addRow(cols.map { r[it] }) }
        return c
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val segs = uri.pathSegments
        return when {
            segs.firstOrNull() == "calendars" -> cursor(projection, calendars)
            segs.firstOrNull() == "events" && segs.size == 2 -> cursor(projection, listOfNotNull(events[segs[1].toLong()]))
            segs.firstOrNull() == "instances" -> {
                val begin = segs[2].toLong()
                val end = segs[3].toLong()
                val rows = events.values.filter { (it[CalendarContract.Events.DTEND] as Long) > begin && (it[CalendarContract.Events.DTSTART] as Long) < end }.map { e ->
                    val cal = calendars.first { it[CalendarContract.Calendars._ID] == e[CalendarContract.Events.CALENDAR_ID] }
                    mapOf(
                        CalendarContract.Instances.BEGIN to e[CalendarContract.Events.DTSTART],
                        CalendarContract.Instances.END to e[CalendarContract.Events.DTEND],
                        CalendarContract.Instances.ALL_DAY to e[CalendarContract.Events.ALL_DAY],
                        CalendarContract.Instances.AVAILABILITY to e[CalendarContract.Events.AVAILABILITY],
                        CalendarContract.Instances.STATUS to e[CalendarContract.Events.STATUS],
                        CalendarContract.Instances.SELF_ATTENDEE_STATUS to e[CalendarContract.Events.SELF_ATTENDEE_STATUS],
                        CalendarContract.Instances.VISIBLE to cal[CalendarContract.Calendars.VISIBLE],
                        CalendarContract.Instances.TITLE to e[CalendarContract.Events.TITLE],
                        CalendarContract.Instances.CALENDAR_ID to e[CalendarContract.Events.CALENDAR_ID],
                    )
                }
                cursor(projection, rows)
            }
            else -> MatrixCursor(projection ?: emptyArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uri.pathSegments.firstOrNull() != "events" || values == null) return null
        lastInsert = values
        val id = nextId++
        val row = mutableMapOf<String, Any?>(CalendarContract.Events._ID to id, CalendarContract.Events.DELETED to 0)
        values.keySet().forEach { k -> row[k] = values.get(k) }
        // The provider's types: longs for times and ids, ints for flags.
        row[CalendarContract.Events.CALENDAR_ID] = values.getAsLong(CalendarContract.Events.CALENDAR_ID)
        events[id] = row
        return ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (uri.pathSegments.firstOrNull() == "events" && uri.pathSegments.size == 2 && events.remove(uri.pathSegments[1].toLong()) != null) 1 else 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/**
 * P0 [AndroidCalendarBackend] against a fake CalendarContract provider: the calendar list (account type,
 * access level, visible), the insert into the Google calendar and the read-back, free/busy without titles,
 * delete, and `no_permission` without the runtime permissions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidCalendarBackendTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun cal(id: Long, name: String, account: String, type: String, access: Int, visible: Int = 1, primary: Int? = null) = mapOf(
        CalendarContract.Calendars._ID to id,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to name,
        CalendarContract.Calendars.ACCOUNT_NAME to account,
        CalendarContract.Calendars.ACCOUNT_TYPE to type,
        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL to access,
        CalendarContract.Calendars.VISIBLE to visible,
        CalendarContract.Calendars.OWNER_ACCOUNT to account,
        CalendarContract.Calendars.IS_PRIMARY to primary,
    )

    @Before fun setUp() {
        FakeCalendarProvider.reset()
        Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
        FakeCalendarProvider.calendars += cal(1, "Phone", "Phone", CalendarContract.ACCOUNT_TYPE_LOCAL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
        FakeCalendarProvider.calendars += cal(2, "Holidays in Sri Lanka", "uitest.risime@gmail.com", "com.google", CalendarContract.Calendars.CAL_ACCESS_READ)
        FakeCalendarProvider.calendars += cal(3, "uitest.risime@gmail.com", "uitest.risime@gmail.com", "com.google", CalendarContract.Calendars.CAL_ACCESS_OWNER, primary = 1)
    }

    @Test fun withoutPermissionNothingIsReadOrWritten() = runBlocking {
        val be = AndroidCalendarBackend(app)
        assertTrue(!be.canRead() && !be.canWrite())
        val pc = PhoneCalendar(be, MemoryCalendarChoice(), CalendarWriteLog())
        assertEquals(CalendarAddOutcome.Failed(CalendarFailure.NO_PERMISSION), pc.add("w", null, "T", 0, 3_600_000, false))
        assertNull(pc.check(0, 1))
        assertTrue(pc.options().isEmpty())
    }

    @Test fun listsInsertsIntoGoogleReadsBackChecksAndDeletes() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val be = AndroidCalendarBackend(app)
        val all = be.calendars()
        assertEquals(3, all.size)
        assertEquals("com.google", all[2].accountType)
        assertTrue(all[2].isPrimary)
        val pc = PhoneCalendar(be, MemoryCalendarChoice(), CalendarWriteLog(), zone = { ZoneId.of("Asia/Colombo") })
        assertEquals(listOf(3L), pc.options().map { it.id })
        val start = java.time.Instant.parse("2026-10-12T08:30:00Z").toEpochMilli()
        val r = pc.add("w1", "r1", "Interview with Shenika", start, start + 3_600_000, false) as CalendarAddOutcome.Added
        val ins = FakeCalendarProvider.lastInsert!!
        assertEquals(3L, ins.getAsLong(CalendarContract.Events.CALENDAR_ID))
        assertEquals("Interview with Shenika", ins.getAsString(CalendarContract.Events.TITLE))
        assertEquals("Asia/Colombo", ins.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        assertEquals(EventRow(r.record.eventId!!, 3, "Interview with Shenika", start, start + 3_600_000, false), be.event(r.record.eventId!!))
        val blocks = pc.check(start - 3_600_000, start + 7_200_000)!!
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].busy)
        assertTrue(pc.remove("w1", r.record.eventId!!))
        assertNull(be.event(r.record.eventId!!))
    }

    @Test fun instancesQueryIsTheInstancesTableWithMillisecondWindow() {
        val from = java.time.Instant.parse("2026-10-12T08:30:00Z").toEpochMilli()
        val uri = AndroidCalendarBackend.instancesUri(from, from + 3_600_000)
        assertEquals("content://com.android.calendar/instances/when/1791793800000/1791797400000", uri.toString())
        assertTrue(CalendarContract.Instances.CALENDAR_ID in AndroidCalendarBackend.INSTANCES_PROJECTION)
        assertTrue(CalendarContract.Instances.BEGIN in AndroidCalendarBackend.INSTANCES_PROJECTION)
    }

    @Test fun checkReportsEveryVisibleCalendarWithItsCountAndSyncState() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val start = java.time.Instant.parse("2026-10-12T08:30:00Z").toEpochMilli()
        // An event on the primary Google calendar, another on a hidden one (left out of free/busy and the report).
        FakeCalendarProvider.calendars += cal(4, "Hidden", "uitest.risime@gmail.com", "com.google", CalendarContract.Calendars.CAL_ACCESS_OWNER, visible = 0)
        listOf(3L to "A", 4L to "B").forEachIndexed { i, (c, t) ->
            FakeCalendarProvider.events[900L + i] = mutableMapOf(
                CalendarContract.Events._ID to 900L + i, CalendarContract.Events.CALENDAR_ID to c, CalendarContract.Events.TITLE to t,
                CalendarContract.Events.DTSTART to start, CalendarContract.Events.DTEND to start + 3_600_000, CalendarContract.Events.ALL_DAY to 0,
            )
        }
        val pc = PhoneCalendar(AndroidCalendarBackend(app), MemoryCalendarChoice(), CalendarWriteLog())
        val r = pc.read(start, start + 3_600_000)!!
        assertEquals(1, r.blocks.size)
        assertTrue(r.source.readOk)
        assertEquals("phone_provider", r.source.source)
        assertEquals(listOf("Holidays in Sri Lanka" to 0, "Primary calendar" to 1, "Phone" to 0), r.source.calendars.map { it.name to it.events })
        assertEquals(listOf("phone_provider"), r.wire.connectedSources)
    }
}
