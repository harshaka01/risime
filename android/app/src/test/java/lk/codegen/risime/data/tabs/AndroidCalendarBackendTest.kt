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

        /** Columns the Instances query refuses (IllegalArgumentException, like a strict projection map). */
        val refuse = mutableSetOf<String>()

        /** Every Instances query throws this. */
        var instancesError: RuntimeException? = null

        fun reset() {
            calendars.clear()
            events.clear()
            nextId = 500L
            lastInsert = null
            refuse.clear()
            instancesError = null
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
            segs.firstOrNull() == "events" -> {
                // The two selections the backend sends: "deleted = 0" and "calendar_id IN (?,…) AND deleted = 0 AND dtstart < ?".
                var rows = events.values.filter { ((it[CalendarContract.Events.DELETED] as? Int) ?: 0) == 0 }
                if (selection?.contains("IN (") == true) {
                    val a = selectionArgs!!.toList()
                    val ids = a.dropLast(1).map { it.toLong() }.toSet()
                    rows = rows.filter { it[CalendarContract.Events.CALENDAR_ID] in ids && (it[CalendarContract.Events.DTSTART] as Long) < a.last().toLong() }
                }
                cursor(projection, rows)
            }
            segs.firstOrNull() == "instances" -> {
                instancesError?.let { throw it }
                projection?.firstOrNull { it in refuse }?.let { throw IllegalArgumentException("Invalid column $it") }
                val begin = segs[2].toLong()
                val end = segs[3].toLong()
                // AOSP expands only calendars with sync_events != 0 (CalendarInstancesHelper.getEntries).
                val expanded = calendars.filter { ((it[CalendarContract.Calendars.SYNC_EVENTS] as? Int) ?: 1) != 0 }.map { it[CalendarContract.Calendars._ID] }.toSet()
                val rows = events.values.filter { it[CalendarContract.Events.CALENDAR_ID] in expanded && (it[CalendarContract.Events.DTEND] as Long) >= begin && (it[CalendarContract.Events.DTSTART] as Long) <= end }.map { e ->
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
                        CalendarContract.Events._SYNC_ID to e[CalendarContract.Events._SYNC_ID],
                        CalendarContract.Events.DELETED to e[CalendarContract.Events.DELETED],
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
        assertEquals(CalendarFailure.NO_PERMISSION, (pc.add("w", null, "T", 0, 3_600_000, false) as CalendarAddOutcome.Failed).reason)
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

    @Test fun checkReportsEveryCalendarWithItsCountAndSyncState() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val start = java.time.Instant.parse("2026-10-12T08:30:00Z").toEpochMilli()
        // An event on the primary Google calendar, another on a hidden one: P0 2026-10-10, both count (VISIBLE is not a filter).
        FakeCalendarProvider.calendars += cal(4, "Hidden", "uitest.risime@gmail.com", "com.google", CalendarContract.Calendars.CAL_ACCESS_OWNER, visible = 0)
        listOf(3L to "A", 4L to "B").forEachIndexed { i, (c, t) ->
            FakeCalendarProvider.events[900L + i] = mutableMapOf(
                CalendarContract.Events._ID to 900L + i, CalendarContract.Events.CALENDAR_ID to c, CalendarContract.Events.TITLE to t,
                CalendarContract.Events.DTSTART to start, CalendarContract.Events.DTEND to start + 3_600_000, CalendarContract.Events.ALL_DAY to 0,
            )
        }
        syncOn()
        val pc = PhoneCalendar(AndroidCalendarBackend(app), MemoryCalendarChoice(), CalendarWriteLog())
        val r = pc.read(start, start + 3_600_000)!!
        assertEquals(1, r.blocks.size)
        assertTrue(r.source.readOk)
        assertEquals("phone_provider", r.source.source)
        assertEquals(listOf("Holidays in Sri Lanka" to 0, "Primary calendar" to 1, "Hidden" to 1, "Phone" to 0), r.source.calendars.map { it.name to it.events })
        assertEquals(listOf("phone_provider"), r.wire.connectedSources)
    }

    private fun syncOn() {
        android.content.ContentResolver.setMasterSyncAutomatically(true)
        android.content.ContentResolver.setSyncAutomatically(android.accounts.Account("uitest.risime@gmail.com", "com.google"), CalendarContract.AUTHORITY, true)
    }

    // v1.32 §29.7: the real ContentResolver facade.
    @Test fun syncOffWhenTheGoogleAccountSyncIsOffAndOkWhenOn() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val start = java.time.Instant.parse("2026-10-12T08:30:00Z").toEpochMilli()
        val pc = PhoneCalendar(AndroidCalendarBackend(app), MemoryCalendarChoice(), CalendarWriteLog())
        val acct = android.accounts.Account("uitest.risime@gmail.com", "com.google")
        android.content.ContentResolver.setMasterSyncAutomatically(true)
        android.content.ContentResolver.setSyncAutomatically(acct, CalendarContract.AUTHORITY, false)
        val off = pc.read(start, start + 3_600_000)!!
        assertEquals("sync_off", off.source.reason)
        assertTrue(!off.source.readOk && off.source.calendars.isNotEmpty())
        android.content.ContentResolver.setSyncAutomatically(acct, CalendarContract.AUTHORITY, true)
        assertTrue(pc.read(start, start + 3_600_000)!!.source.readOk)
        android.content.ContentResolver.setMasterSyncAutomatically(false)
        assertEquals("sync_off", pc.read(start, start + 3_600_000)!!.source.reason)
        android.content.ContentResolver.setMasterSyncAutomatically(true)
    }

    // ---- P0 2026-10-10: "0 events in the next 7 days" ----

    private fun event(id: Long, cal: Long, start: Long, end: Long, extra: Map<String, Any?> = emptyMap()) {
        FakeCalendarProvider.events[id] = (mutableMapOf<String, Any?>(
            CalendarContract.Events._ID to id, CalendarContract.Events.CALENDAR_ID to cal, CalendarContract.Events.TITLE to "t$id",
            CalendarContract.Events.DTSTART to start, CalendarContract.Events.DTEND to end, CalendarContract.Events.ALL_DAY to 0, CalendarContract.Events.DELETED to 0,
        ) + extra).toMutableMap()
    }

    private val now = java.time.Instant.parse("2026-10-10T06:00:00Z").toEpochMilli()

    private fun pc() = PhoneCalendar(AndroidCalendarBackend(app), MemoryCalendarChoice(), CalendarWriteLog(), zone = { ZoneId.of("Asia/Colombo") }, now = { now })

    @Test fun detailsCountsFromTheRealQueryLeavingOutDeletedCancelledDeclined() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val h = 3_600_000L
        event(1, 3, now + h, now + 2 * h)
        event(2, 3, now + 6 * 86_400_000L, now + 6 * 86_400_000L + h) // 6 days out
        event(3, 3, now + 2 * h, now + 3 * h, mapOf(CalendarContract.Events.DELETED to 1))
        event(4, 3, now + 3 * h, now + 4 * h, mapOf(CalendarContract.Events.STATUS to CalendarContract.Events.STATUS_CANCELED))
        event(5, 3, now + 4 * h, now + 5 * h, mapOf(CalendarContract.Events.SELF_ATTENDEE_STATUS to CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED))
        event(6, 2, now + 5 * h, now + 6 * h, mapOf(CalendarContract.Events.SELF_ATTENDEE_STATUS to null, CalendarContract.Events.STATUS to null))
        val o = pc().overview()!!
        assertEquals(mapOf(2L to 1, 3L to 2, 1L to 0), o.calendars.associate { it.first.id to it.second })
        val d = pc().diagnostics()
        val primary = d.calendars.first { it.info.id == 3L }
        assertEquals(5, primary.instances)
        assertEquals(2, primary.counted)
        assertEquals(4, primary.events) // raw Events not deleted: 1, 2, 4, 5
        assertTrue(d.errors.isEmpty())
    }

    @Test fun aRefusedColumnFallsBackAndBothRefusedIsAnErrorNotZero() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        event(1, 3, now + 3_600_000, now + 7_200_000)
        FakeCalendarProvider.refuse += CalendarContract.Events._SYNC_ID
        assertEquals(1, pc().overview()!!.calendars.first { it.first.id == 3L }.second)
        FakeCalendarProvider.refuse += CalendarContract.Instances.BEGIN
        val o = pc().overview()!!
        assertEquals(listOf<Int?>(null, null, null), o.calendars.map { it.second })
        assertEquals("query_failed", o.reason)
        assertTrue(o.error!!.contains("Invalid column begin"))
        assertTrue(pc().diagnostics().text().contains("Error: instances: Instances query failed"))
        val r = pc().read(now, now + 86_400_000)!!
        assertEquals("api_error", r.wire.sources!!.first().reason)
    }

    @Test fun aSecurityExceptionIsAReadErrorNotZero() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        FakeCalendarProvider.instancesError = SecurityException("Permission Denial")
        val o = pc().overview()!!
        assertEquals(false, o.readOk)
        assertTrue(o.calendars.all { it.second == null })
        assertTrue(o.error!!.contains("SecurityException"))
    }

    @Test fun aCalendarWithSyncEventsOffIsReadFromEvents() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        FakeCalendarProvider.calendars[0] = FakeCalendarProvider.calendars[0] + (CalendarContract.Calendars.SYNC_EVENTS to 0) + (CalendarContract.Calendars.VISIBLE to 0)
        event(1, 1, now + 3_600_000, now + 7_200_000)
        event(2, 1, now + 3_600_000, now + 7_200_000, mapOf(CalendarContract.Events.RRULE to "FREQ=WEEKLY", CalendarContract.Events.DTEND to null, CalendarContract.Events.DURATION to "P3600S"))
        // The provider expands nothing for it (AOSP: sync_events != 0); the non-recurring event is read from Events.
        assertEquals(1, pc().overview()!!.calendars.first { it.first.id == 1L }.second)
    }

    @Test fun anAllDayMondayIsMondayInColomboThroughTheProvider() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        syncOn()
        val mon = java.time.Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()
        event(1, 3, mon, mon + 86_400_000, mapOf(CalendarContract.Events.ALL_DAY to 1))
        val from = java.time.Instant.parse("2026-10-11T18:30:00Z").toEpochMilli() // Monday 00:00 in Colombo
        val b = pc().check(from, from + 86_400_000)!!
        assertEquals(listOf("2026-10-11T18:30:00Z" to "2026-10-12T18:30:00Z"), b.map { it.start to it.end })
        assertTrue(b.single().allDay)
    }
}
