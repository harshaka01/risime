package lk.codegen.risime.data.tabs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** An in-memory calendar provider: calendars, events, instances (one per event), permissions. */
class FakeCalendarBackend : CalendarBackend {
    var read = true
    var write = true
    var refuseInsert = false

    /** Simulates a provider that stores something else than it was given (verify must catch it). */
    var corrupt: ((EventRow) -> EventRow)? = null
    val cals = mutableListOf<PhoneCalendarInfo>()
    val events = linkedMapOf<Long, EventRow>()
    val extra = mutableListOf<BusyRow>()
    val tz = mutableMapOf<Long, String>()
    private var next = 100L

    override fun canRead() = read

    override fun canWrite() = write

    override fun calendars() = cals.toList()

    var masterSync = true
    val syncOffAccounts = mutableSetOf<String>()
    val syncRequests = mutableListOf<String>()

    override fun masterSyncOn() = masterSync

    override fun accountSyncOn(accountName: String, accountType: String) = accountName !in syncOffAccounts

    override fun requestSync(accountName: String, accountType: String) { syncRequests += accountName }

    override fun instances(fromMs: Long, toMs: Long) =
        events.values.filter { it.dtEnd > fromMs && it.dtStart < toMs }.map { e -> BusyRow(e.dtStart, e.dtEnd, e.allDay, true, e.calendarId, cals.firstOrNull { it.id == e.calendarId }?.visible ?: true) } + extra

    override fun insert(calendarId: Long, title: String, startMs: Long, endMs: Long, allDay: Boolean, timeZone: String): Long? {
        if (refuseInsert || cals.none { it.id == calendarId }) return null
        val id = next++
        var row = EventRow(id, calendarId, title, startMs, endMs, allDay)
        corrupt?.let { row = it(row) }
        events[id] = row
        tz[id] = timeZone
        return id
    }

    override fun event(id: Long) = events[id]

    override fun delete(id: Long) = events.remove(id) != null
}

/**
 * P0 (Harsha's phone): which calendar an event goes to (a synced Google calendar; a local one only by an
 * explicit pick; read-only ones never), the remembered pick, insert + read-back verify and every failure
 * reason, free/busy merging, and undo.
 */
class PhoneCalendarTest {
    private val google = PhoneCalendarInfo(1, "harsha@example.com", "harsha@example.com", "com.google", 700, true, "harsha@example.com", true)
    private val googleWork = PhoneCalendarInfo(2, "Work", "harsha@example.com", "com.google", 700, true, "work@group.calendar.google.com")
    private val holidays = PhoneCalendarInfo(3, "Holidays in Sri Lanka", "harsha@example.com", "com.google", 200, true, "en.lk#holiday@group.v.calendar.google.com")
    private val hidden = PhoneCalendarInfo(4, "Hidden", "harsha@example.com", "com.google", 700, false)
    private val local = PhoneCalendarInfo(5, "Phone", "Phone", "LOCAL", 700, true, "Phone")
    private val zone = ZoneId.of("Asia/Colombo")

    private val be = FakeCalendarBackend()
    private val choice = MemoryCalendarChoice()
    private val log = CalendarWriteLog()
    private val cal = PhoneCalendar(be, choice, log, zone = { zone }, now = { 1000L })

    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    // Monday 12 Oct 2026, 14:00–15:00 in Colombo.
    private val start = ms("2026-10-12T08:30:00Z")
    private val end = ms("2026-10-12T09:30:00Z")

    @Test fun googlePreferredReadOnlyAndHiddenExcludedLocalOnlyWithoutGoogle() {
        val all = listOf(local, holidays, googleWork, hidden, google)
        assertEquals(listOf(google, googleWork), CalendarSelection.pickerOptions(all))
        assertEquals(google, CalendarSelection.defaultGoogle(all))
        assertEquals(google, CalendarSelection.target(all, null))
        // Without a Google calendar the picker lists the local one, but nothing is chosen for the user.
        assertEquals(listOf(local), CalendarSelection.pickerOptions(listOf(local, holidays.copy(accountType = "LOCAL"))))
        assertNull(CalendarSelection.target(listOf(local), null))
        // …only the user's explicit pick sends events there.
        assertEquals(local, CalendarSelection.target(listOf(local), local.id))
        // A remembered read-only calendar is never written to.
        assertEquals(google, CalendarSelection.target(all, holidays.id))
        assertEquals("harsha@example.com · Google", CalendarSelection.label(google))
        assertEquals("Work", CalendarSelection.subLabel(googleWork))
        assertEquals("Phone · Phone only", CalendarSelection.label(local))
    }

    @Test fun thePickIsRememberedAndUsed() = runBlocking {
        be.cals += listOf(google, googleWork)
        assertNull(cal.chosen())
        cal.choose(googleWork.id)
        assertEquals(googleWork, cal.chosen())
        val r = cal.add("w1", "r1", "Interview with Shenika", start, end, false) as CalendarAddOutcome.Added
        assertEquals(googleWork.id, be.events[r.record.eventId]!!.calendarId)
    }

    @Test fun insertsIntoGoogleAndReadsItBack() = runBlocking {
        be.cals += listOf(local, google, holidays)
        val r = cal.add("w1", "r1", "Interview with Shenika", start, end, false)
        r as CalendarAddOutcome.Added
        val ev = be.events[r.record.eventId]!!
        assertEquals(EventRow(ev.id, google.id, "Interview with Shenika", start, end, false), ev)
        assertEquals("Asia/Colombo", be.tz[ev.id])
        val rec = log.get("W1")!!
        assertEquals(ev.id, rec.eventId)
        assertEquals("harsha@example.com · Google", rec.calendarName)
        assertEquals("com.google", rec.accountType)
        assertEquals("r1", rec.requestId)
        assertEquals("Added to your Google Calendar: Interview with Shenika · Mon 12 Oct, 2–3 PM", RisiCalendarCards.addedText(rec, zone))
    }

    @Test fun allDayEventsAreUtcMidnightsOfThePhonesDates() = runBlocking {
        be.cals += google
        // The server's all-day "Mon 12 Oct" as local midnights in Colombo.
        val r = cal.add("w1", null, "Holiday", ms("2026-10-11T18:30:00Z"), ms("2026-10-12T18:30:00Z"), true) as CalendarAddOutcome.Added
        val ev = be.events[r.record.eventId]!!
        assertEquals(ms("2026-10-12T00:00:00Z"), ev.dtStart)
        assertEquals(ms("2026-10-13T00:00:00Z"), ev.dtEnd)
        assertEquals("UTC", be.tz[ev.id])
    }

    @Test fun failureReasons() = runBlocking {
        // No permission.
        be.write = false
        assertEquals(CalendarAddOutcome.Failed(CalendarFailure.NO_PERMISSION), cal.add("w1", null, "T", start, end, false))
        be.write = true
        // Only a local calendar and no pick: no writable Google calendar (never silently local).
        be.cals += listOf(local, holidays)
        assertEquals(CalendarAddOutcome.Failed(CalendarFailure.NO_GOOGLE_CALENDAR), cal.add("w2", null, "T", start, end, false))
        assertTrue(be.events.isEmpty())
        assertEquals("NO_GOOGLE_CALENDAR", log.get("w2")!!.failure)
        // The provider refuses the insert.
        be.cals += google
        be.refuseInsert = true
        assertEquals(CalendarAddOutcome.Failed(CalendarFailure.INSERT_FAILED), cal.add("w3", null, "T", start, end, false))
        // It stores something else: verify mismatch, and the wrong event is removed again.
        be.refuseInsert = false
        be.corrupt = { it.copy(dtStart = it.dtStart + 3_600_000) }
        assertEquals(CalendarAddOutcome.Failed(CalendarFailure.VERIFY_MISMATCH), cal.add("w4", null, "T", start, end, false))
        assertTrue(be.events.isEmpty())
        assertTrue(log.get("w4")!!.failureText!!.contains("didn't read back"))
    }

    @Test fun removeOnlyWhatIsStillThere() = runBlocking {
        be.cals += google
        val r = cal.add("w1", null, "T", start, end, false) as CalendarAddOutcome.Added
        assertTrue(cal.remove("w1", r.record.eventId!!))
        assertTrue(be.events.isEmpty())
        assertTrue(log.get("w1")!!.removed)
        assertEquals(false, cal.remove("w1", r.record.eventId!!))
    }

    @Test fun freeBusyMergedClippedAndWithoutDetails() {
        be.cals += google
        be.extra += listOf(
            BusyRow(ms("2026-10-12T03:00:00Z"), ms("2026-10-12T04:00:00Z"), false, true),
            BusyRow(ms("2026-10-12T03:30:00Z"), ms("2026-10-12T05:00:00Z"), false, true),
            BusyRow(ms("2026-10-12T05:00:00Z"), ms("2026-10-12T05:30:00Z"), false, true), // touching: merged
            BusyRow(ms("2026-10-12T07:00:00Z"), ms("2026-10-12T08:00:00Z"), false, false), // free
            BusyRow(ms("2026-10-11T00:00:00Z"), ms("2026-10-13T00:00:00Z"), true, true), // all day, clipped
        )
        val b = cal.check(ms("2026-10-12T00:00:00Z"), ms("2026-10-12T12:00:00Z"))!!
        assertEquals(3, b.size)
        assertEquals("2026-10-12T00:00:00Z", b[0].start)
        assertEquals(true, b[0].allDay)
        assertEquals("2026-10-12T12:00:00Z", b[0].end)
        assertEquals("2026-10-12T03:00:00Z" to "2026-10-12T05:30:00Z", b[1].start to b[1].end)
        assertEquals(false, b[2].busy)
        be.read = false
        assertNull(cal.check(0, 1))
    }

    @Test fun theLogSurvivesARestart() = runBlocking {
        var stored = ""
        val l1 = CalendarWriteLog(persist = { stored = it })
        val c1 = PhoneCalendar(be.also { it.cals += google }, choice, l1, zone = { zone })
        c1.add("w1", "r1", "T", start, end, false)
        val l2 = CalendarWriteLog()
        l2.restore(stored)
        assertEquals(l1.get("w1"), l2.get("w1"))
    }

    @Test fun cardTexts() {
        val z = zone
        assertEquals("2–3 PM", RisiCalendarCards.timeRange(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end), z))
        assertEquals("11 AM–12:30 PM", RisiCalendarCards.timeRange(Instant.parse("2026-10-12T05:30:00Z"), Instant.parse("2026-10-12T07:00:00Z"), z))
        assertEquals(
            "Add \"Interview with Shenika\" to my calendar on Mon 12 Oct 2026 from 14:30 to 15:15",
            RisiCalendarCards.editRequest("Interview with Shenika", java.time.LocalDate.of(2026, 10, 12), java.time.LocalTime.of(14, 30), 45, false),
        )
    }

    // ---- P0 2026-10-09 honesty: what a check read ----

    @Test fun checkCountsPerVisibleCalendarAndLeavesHiddenOnesOut() {
        be.cals += listOf(google, googleWork, hidden)
        be.events[1] = EventRow(1, google.id, "x", start, end, false)
        be.events[2] = EventRow(2, googleWork.id, "y", start + 600_000, end, false)
        be.events[3] = EventRow(3, hidden.id, "z", end, end + 3_600_000, false)
        val r = cal.read(start, end + 3_600_000)!!
        assertTrue(r.source.readOk)
        assertNull(r.source.reason)
        assertEquals(listOf("Primary calendar" to 1, "Work" to 1), r.source.calendars.map { it.name to it.events })
        // Only the visible calendars' busy time; the hidden calendar's hour is free.
        assertEquals(1, r.blocks.size)
        assertEquals("2026-10-12T09:30:00Z", r.blocks[0].end)
        assertEquals(listOf("phone_provider"), r.wire.connectedSources)
    }

    @Test fun anEmptyReadOfASyncedGoogleCalendarIsTrustworthy() {
        be.cals += google
        val r = cal.read(start, end)!!
        assertTrue(r.blocks.isEmpty())
        assertTrue(r.source.readOk)
        assertEquals(listOf("Primary calendar" to 0), r.source.calendars.map { it.name to it.events })
    }

    @Test fun untrustworthyReadsSayWhy() {
        assertEquals(false to "no_calendars", CalendarRead.verdict(emptyList()))
        assertEquals(false to "no_google_calendar", CalendarRead.verdict(listOf(local)))
        assertEquals(false to "google_sync_off", CalendarRead.verdict(listOf(local, google.copy(syncEvents = false))))
        assertEquals(false to "google_calendars_hidden", CalendarRead.verdict(listOf(hidden)))
        assertEquals(true to null, CalendarRead.verdict(listOf(hidden, google)))
        be.cals += google.copy(syncEvents = false)
        val r = cal.read(start, end)!!
        assertEquals(false, r.source.readOk)
        assertEquals("google_sync_off", r.source.reason)
        assertEquals(emptyList<String>(), r.wire.connectedSources)
    }

    @Test fun permissionMissingIsNotAnEmptyCalendar() {
        be.cals += google
        be.read = false
        assertNull(cal.read(start, end))
        assertNull(cal.check(start, end))
        assertNull(cal.overview())
    }

    @Test fun aFailingProviderIsNotAnEmptyCalendar() {
        val failing = object : CalendarBackend by be {
            override fun instances(fromMs: Long, toMs: Long): List<BusyRow> = error("provider died")
        }
        be.cals += google
        val r = PhoneCalendar(failing, choice, log).read(start, end)!!
        assertEquals(false, r.source.readOk)
        assertEquals("query_failed", r.source.reason)
    }

    @Test fun overviewCountsTheNextSevenDaysForEveryCalendar() {
        be.cals += listOf(google, hidden)
        be.events[1] = EventRow(1, hidden.id, "x", 2000L, 3000L, false)
        val o = cal.overview()!!
        assertEquals(listOf(google to 0, hidden to 1), o.calendars)
        assertTrue(o.readOk)
    }
    // ---- v1.32 §29.7 sync_off ----
    @Test fun syncOffWhenEveryNonLocalAccountHasSyncOff() {
        be.cals += listOf(google, googleWork)
        be.syncOffAccounts += "harsha@example.com"
        val r = cal.read(start, end)!!
        assertEquals(false, r.source.readOk)
        assertEquals("sync_off", r.source.reason)
        assertEquals(2, r.source.calendars.size) // still listed
        assertEquals("sync_off", r.wire.sources!!.first().reason)
        assertEquals(listOf("harsha@example.com" to "com.google"), cal.overview()!!.syncOffAccounts)
    }

    @Test fun masterSyncOffIsSyncOff() {
        be.cals += google
        be.masterSync = false
        assertEquals("sync_off", cal.read(start, end)!!.source.reason)
        assertTrue(cal.overview()!!.masterSyncOff)
    }

    @Test fun oneSyncedAccountKeepsTheReadOk() {
        val other = google.copy(id = 9, accountName = "b@example.com", displayName = "b@example.com")
        be.cals += listOf(google, other)
        be.syncOffAccounts += "harsha@example.com"
        val r = cal.read(start, end)!!
        assertTrue(r.source.readOk)
        assertNull(r.source.reason)
        assertEquals(listOf("harsha@example.com" to "com.google"), cal.overview()!!.syncOffAccounts)
    }

    @Test fun localCalendarsNeverCauseSyncOff() {
        be.cals += local
        be.masterSync = false
        assertTrue(cal.read(start, end)!!.source.reason != "sync_off")
        be.cals += google
        be.syncOffAccounts += "harsha@example.com"
        assertEquals("sync_off", cal.read(start, end)!!.source.reason) // the Google account has sync off
        be.syncOffAccounts.clear()
        be.masterSync = true
        assertTrue(cal.read(start, end)!!.source.readOk)
    }

    @Test fun hiddenCalendarsDoNotCountForSyncOff() {
        be.cals += listOf(google, hidden.copy(accountName = "x@example.com"))
        be.syncOffAccounts += "x@example.com"
        assertTrue(cal.read(start, end)!!.source.readOk)
    }

    @Test fun refreshRequestsASyncForTheAccount() {
        be.cals += google
        cal.requestSync("harsha@example.com", "com.google")
        assertEquals(listOf("harsha@example.com"), be.syncRequests)
    }
}
