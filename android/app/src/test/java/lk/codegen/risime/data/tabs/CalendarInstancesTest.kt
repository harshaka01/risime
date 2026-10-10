package lk.codegen.risime.data.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * P0 2026-10-10 (Harsha's Pixel: every calendar at "0 events in the next 7 days"): which instance rows are busy
 * time, all-day rows on the phone's local dates, every calendar counted whatever VISIBLE / SYNC_EVENTS say, a
 * query error never shown as 0, and the diagnostics text.
 */
class CalendarInstancesTest {
    private val colombo = ZoneId.of("Asia/Colombo")

    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    private val google = PhoneCalendarInfo(1, "harsha@example.com", "harsha@example.com", "com.google", 700, true, "harsha@example.com", true)
    private val hiddenUnsynced = PhoneCalendarInfo(2, "Team", "harsha@example.com", "com.google", 700, visible = false, syncEvents = false)

    /** A provider that returns raw rows (as the Android backend does). */
    private class RawBackend(val cals: List<PhoneCalendarInfo>, val rows: () -> List<InstanceRow>) : CalendarBackend {
        override fun canRead() = true
        override fun canWrite() = true
        override fun calendars() = cals
        override fun instances(fromMs: Long, toMs: Long): List<BusyRow> = error("not used")
        override fun instanceRows(fromMs: Long, toMs: Long) = rows()
        override fun eventCounts(): Map<Long, Int> = mapOf(1L to 12)
        override fun syncState(accountName: String, accountType: String) = "idle"
        override fun insert(calendarId: Long, title: String, startMs: Long, endMs: Long, allDay: Boolean, timeZone: String): Long? = null
        override fun event(id: Long): EventRow? = null
        override fun delete(id: Long) = false
    }

    private val now = ms("2026-10-10T06:00:00Z") // Saturday 10 Oct 2026, 11:30 in Colombo

    private fun pc(be: CalendarBackend) = PhoneCalendar(be, MemoryCalendarChoice(), CalendarWriteLog(), zone = { colombo }, now = { now })

    @Test fun deletedCancelledAndDeclinedAreNotBusyNullsCount() {
        val base = InstanceRow(ms("2026-10-12T04:00:00Z"), ms("2026-10-12T05:00:00Z"), false, calendarId = 1)
        assertNull(InstanceFilter.exclusion(base))
        assertNull(InstanceFilter.exclusion(base.copy(status = 1, selfStatus = 1))) // confirmed, accepted
        assertNull(InstanceFilter.exclusion(base.copy(selfStatus = 0))) // ATTENDEE_STATUS_NONE: counts
        assertEquals("deleted", InstanceFilter.exclusion(base.copy(deleted = true)))
        assertEquals("cancelled", InstanceFilter.exclusion(base.copy(status = InstanceFilter.STATUS_CANCELED)))
        assertEquals("declined", InstanceFilter.exclusion(base.copy(selfStatus = InstanceFilter.ATTENDEE_STATUS_DECLINED)))
        assertEquals(true, InstanceFilter.busy(base, colombo)!!.busy)
        assertEquals(false, InstanceFilter.busy(base.copy(availability = InstanceFilter.AVAILABILITY_FREE), colombo)!!.busy)
    }

    @Test fun anAllDayEventOnMondayIsMondayInColombo() {
        // The provider stores an all-day Monday as UTC midnights: 12 Oct 00:00Z → 13 Oct 00:00Z.
        val r = InstanceRow(ms("2026-10-12T00:00:00Z"), ms("2026-10-13T00:00:00Z"), allDay = true, calendarId = 1)
        val b = InstanceFilter.busy(r, colombo)!!
        assertEquals(java.time.LocalDate.of(2026, 10, 12), Instant.ofEpochMilli(b.begin).atZone(colombo).toLocalDate())
        assertEquals(java.time.LocalTime.MIDNIGHT, Instant.ofEpochMilli(b.begin).atZone(colombo).toLocalTime())
        assertEquals("2026-10-11T18:30:00Z", Instant.ofEpochMilli(b.begin).toString())
        assertEquals("2026-10-12T18:30:00Z", Instant.ofEpochMilli(b.end).toString())
        // Its block in a Monday check: the whole local Monday, not Monday 05:30 → Tuesday 05:30.
        val monday = ms("2026-10-11T18:30:00Z") to ms("2026-10-12T18:30:00Z")
        val blocks = PhoneCalendar.merge(InstanceFilter.busyRows(listOf(r), colombo, monday.first, monday.second), monday.first, monday.second)
        assertEquals(listOf("2026-10-11T18:30:00Z" to "2026-10-12T18:30:00Z"), blocks.map { it.start to it.end })
        // …and it is not in a Tuesday check, where its raw UTC times (05:30 Tuesday) would have put it.
        val tuesday = ms("2026-10-12T18:30:00Z") to ms("2026-10-13T18:30:00Z")
        assertTrue(InstanceFilter.busyRows(listOf(r), colombo, tuesday.first, tuesday.second).isEmpty())
    }

    @Test fun theProviderWindowIsOneDayWiderAndInMilliseconds() {
        val (f, t) = InstanceFilter.queryWindow(now, now + 7 * InstanceFilter.DAY_MS)
        assertEquals(now - 86_400_000L, f)
        assertEquals(now + 8 * 86_400_000L, t)
        assertTrue(f < t)
    }

    @Test fun everyCalendarCountsWhateverVisibleOrSyncEventsSay() {
        val rows = listOf(
            InstanceRow(now + 3_600_000, now + 7_200_000, false, calendarId = 1),
            InstanceRow(now + 86_400_000, now + 90_000_000, false, calendarId = 2), // hidden + sync off
            InstanceRow(now + 2 * 86_400_000L, now + 2 * 86_400_000L + 1, false, calendarId = 2, selfStatus = InstanceFilter.ATTENDEE_STATUS_DECLINED),
            InstanceRow(now + 6 * 86_400_000L, now + 6 * 86_400_000L + 3_600_000, false, calendarId = 1), // 6 days out
            InstanceRow(now + 8 * 86_400_000L, now + 8 * 86_400_000L + 3_600_000, false, calendarId = 1), // outside the 7 days
        )
        val c = pc(RawBackend(listOf(google, hiddenUnsynced)) { rows })
        val o = c.overview()!!
        assertEquals(listOf(google to 2, hiddenUnsynced to 1), o.calendars)
        val r = c.read(now, now + 7 * InstanceFilter.DAY_MS)!!
        assertEquals(listOf(2, 1), r.source.calendars.map { it.events })
        assertTrue(r.source.readOk)
    }

    @Test fun aQueryErrorIsNeverZeroEvents() {
        val c = pc(RawBackend(listOf(google)) { throw CalendarQueryException("Instances query failed (IllegalArgumentException, then IllegalArgumentException: Invalid column x)") })
        val o = c.overview()!!
        assertEquals(listOf(google to null), o.calendars) // listed, but no count
        assertEquals(false, o.readOk)
        assertEquals(CalendarRead.QUERY_FAILED, o.reason)
        assertTrue(o.error!!.startsWith("instances: "))
        assertEquals("events couldn't be read", lk.codegen.risime.ui.settings.calendarCountText(null))
        assertEquals("0 events in the next 7 days", lk.codegen.risime.ui.settings.calendarCountText(0))
        assertEquals("1 event in the next 7 days", lk.codegen.risime.ui.settings.calendarCountText(1))
        val r = c.read(now, now + 1)!!
        assertEquals(false, r.source.readOk)
        assertEquals("api_error", r.wire.sources!!.first().reason)
        val d = c.diagnostics()
        assertEquals(listOf<Int?>(null), d.calendars.map { it.instances })
        assertTrue(d.text().contains("Error: instances: Instances query failed"))
    }

    @Test fun durations() {
        assertEquals(3_600_000L, InstanceFilter.durationMs("P3600S"))
        assertEquals(3_600_000L, InstanceFilter.durationMs("PT1H"))
        assertEquals(86_400_000L, InstanceFilter.durationMs("P1D"))
        assertEquals(5_400_000L, InstanceFilter.durationMs("PT1H30M"))
        assertEquals(7 * 86_400_000L, InstanceFilter.durationMs("P1W"))
        assertNull(InstanceFilter.durationMs("P"))
        assertNull(InstanceFilter.durationMs("soon"))
    }

    @Test fun diagnosticsTextFormat() {
        val rows = listOf(
            InstanceRow(now + 3_600_000, now + 7_200_000, false, calendarId = 1),
            InstanceRow(now + 3_600_000, now + 7_200_000, false, calendarId = 1, status = InstanceFilter.STATUS_CANCELED),
        )
        val d = pc(RawBackend(listOf(google, hiddenUnsynced)) { rows }).diagnostics(writeGranted = false)
        assertEquals(
            """
            Calendar diagnostics
            READ_CALENDAR: granted
            WRITE_CALENDAR: not granted
            Auto-sync: on
            Window: next 7 days
            Calendars: 2

            harsha@example.com
              Account: harsha@example.com
              Account type: com.google
              VISIBLE: 1 · SYNC_EVENTS: 1
              Account sync: on · Sync state: idle · Last sync: unknown
              Events (raw): 12 · Instances next 7 days: 2 · Counted: 1

            Team
              Account: harsha@example.com
              Account type: com.google
              VISIBLE: 0 · SYNC_EVENTS: 0
              Account sync: on · Sync state: idle · Last sync: unknown
              Events (raw): 0 · Instances next 7 days: 0 · Counted: 0

            Errors: none
            """.trimIndent(),
            d.text(),
        )
    }

    @Test fun diagnosticsWithoutPermissionSaySo() {
        val be = object : CalendarBackend by RawBackend(listOf(google), { emptyList() }) {
            override fun canRead() = false
        }
        val d = pc(be).diagnostics(writeGranted = false)
        assertTrue(d.text().contains("READ_CALENDAR: not granted"))
        assertTrue(d.text().contains("Error: READ_CALENDAR is not granted: nothing can be read"))
        assertEquals(0, d.calendars.size)
    }
}
