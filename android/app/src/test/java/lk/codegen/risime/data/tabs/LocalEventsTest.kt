package lk.codegen.risime.data.tabs

import kotlinx.coroutines.runBlocking
import lk.codegen.risime.net.RisiEvent
import lk.codegen.risime.net.RisiEventStatus
import lk.codegen.risime.net.RisiLocalEventsRange
import lk.codegen.risime.net.RisiMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** v1.32 §29.7 "Your events": range, who sees it, rows, empty text, the cap, Risi Calendar events, and the phone read. */
class LocalEventsTest {
    private val colombo = ZoneId.of("Asia/Colombo")
    private val me = "ff03ab6f-6457-46b1-a53b-9efd937920df"
    private val range = RisiLocalEventsRange("2026-10-11T18:30:00.000Z", "2026-10-18T18:30:00.000Z")
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()
    private val from = ms("2026-10-11T18:30:00Z")
    private val to = ms("2026-10-18T18:30:00Z")

    private fun answer(notify: List<String> = listOf(me), r: RisiLocalEventsRange? = range) = RisiMeta(kind = "answer", notify = notify, localEvents = r)

    @Test fun onlyTheAskerInTheRisiChat() {
        assertTrue(LocalEvents.shows(answer(), me, risiChat = true))
        assertTrue(!LocalEvents.shows(answer(), me, risiChat = false)) // Official / group: never
        assertTrue(!LocalEvents.shows(answer(notify = listOf("someone-else")), me, risiChat = true))
        assertTrue(!LocalEvents.shows(answer(notify = emptyList()), me, risiChat = true))
        assertTrue(!LocalEvents.shows(answer(r = null), me, risiChat = true))
        assertNull(LocalEvents.range(RisiLocalEventsRange("2026-10-01T00:00:00Z", "2026-10-20T00:00:00Z"))) // > 14 days
        assertNull(LocalEvents.range(RisiLocalEventsRange("2026-10-02T00:00:00Z", "2026-10-01T00:00:00Z")))
        assertNull(LocalEvents.range(RisiLocalEventsRange("soon", "later")))
        assertEquals(from to to, LocalEvents.range(range))
    }

    @Test fun rowTexts() {
        val timed = LocalEvent("Standup", ms("2026-10-12T04:30:00Z"), ms("2026-10-12T05:30:00Z"), false)
        assertEquals("Mon 12 Oct · 10:00–11:00 · Standup", LocalEvents.rowText(timed, colombo, Locale.UK))
        assertTrue(LocalEvents.rowText(timed, colombo, Locale.UK, use24h = false).matches(Regex("Mon 12 Oct · 10:00\\W?[aA]\\.?[mM]\\.?–11:00\\W?[aA]\\.?[mM]\\.? · Standup")))
        // All-day (already on local midnights): Thursday in Colombo.
        val allDay = LocalEvent("Poya", ms("2026-10-14T18:30:00Z"), ms("2026-10-15T18:30:00Z"), true)
        assertEquals("Thu 15 Oct · All day · Poya", LocalEvents.rowText(allDay, colombo, Locale.UK))
        val trip = LocalEvent("Trip", ms("2026-10-14T18:30:00Z"), ms("2026-10-17T18:30:00Z"), true)
        assertEquals("Thu 15 Oct – Sat 17 Oct · All day · Trip", LocalEvents.rowText(trip, colombo, Locale.UK))
        assertEquals("Mon 12 Oct · 10:00–11:00 · (No title)", LocalEvents.rowText(timed.copy(title = " "), colombo, Locale.UK))
        assertEquals("No events in your phone calendars from Mon 12 Oct to Sun 18 Oct", LocalEvents.emptyText(from, to, colombo, Locale.UK))
    }

    @Test fun capAtThirtyWithMore() {
        val many = (0 until 34).map { LocalEvent("e$it", from + it * 60_000L, from + it * 60_000L + 1, false) }
        val (rows, more) = LocalEvents.capped(many)
        assertEquals(30, rows.size)
        assertEquals("+4 more", more)
        assertNull(LocalEvents.capped(many.take(30)).second)
    }

    @Test fun risiCalendarEventsJoinSortedLeavingOutCancelledAndDeclined() = runBlocking {
        val risi = listOf(
            RisiEvent("a", title = "Lunch with Kumu", start = "2026-10-13T06:30:00Z", end = "2026-10-13T07:30:00Z"),
            RisiEvent("b", title = "Cancelled", start = "2026-10-13T06:30:00Z", end = "2026-10-13T07:30:00Z", state = RisiEvent.STATE_CANCELLED),
            RisiEvent("c", title = "Declined", start = "2026-10-13T06:30:00Z", end = "2026-10-13T07:30:00Z", myStatus = RisiEventStatus.DECLINED),
            RisiEvent("d", title = "Next month", start = "2026-11-13T06:30:00Z", end = "2026-11-13T07:30:00Z"),
        )
        val phone = listOf(LocalEvent("Standup", ms("2026-10-14T04:30:00Z"), ms("2026-10-14T05:00:00Z"), false), LocalEvent("Review", ms("2026-10-12T04:30:00Z"), ms("2026-10-12T05:00:00Z"), false))
        val r = LocalEvents.build(from, to, phone = { phone }, risi = { risi }) as LocalEventsResult.Events
        assertEquals(listOf("Review", "Lunch with Kumu", "Standup"), r.events.map { it.title })
        assertEquals(LocalEventsResult.NoPermission, LocalEvents.build(from, to, phone = { null }, risi = { risi }))
        assertEquals(LocalEventsResult.Failed, LocalEvents.build(from, to, phone = { throw CalendarQueryException("x") }, risi = { risi }))
    }

    /** The phone read: same rows and exclusions as the busy read, titles kept, RisiMe's own copies left out. */
    @Test fun phoneEventsUseTheFixedRead() {
        val rows = listOf(
            InstanceRow(ms("2026-10-12T04:30:00Z"), ms("2026-10-12T05:30:00Z"), false, calendarId = 2, visible = false, title = "Hidden cal meeting"),
            InstanceRow(ms("2026-10-12T00:00:00Z"), ms("2026-10-13T00:00:00Z"), true, calendarId = 1, title = "Holiday"),
            InstanceRow(ms("2026-10-13T04:30:00Z"), ms("2026-10-13T05:30:00Z"), false, calendarId = 1, selfStatus = InstanceFilter.ATTENDEE_STATUS_DECLINED, title = "Declined"),
            InstanceRow(ms("2026-10-13T04:30:00Z"), ms("2026-10-13T05:30:00Z"), false, calendarId = 1, status = InstanceFilter.STATUS_CANCELED, title = "Cancelled"),
            InstanceRow(ms("2026-10-13T04:30:00Z"), ms("2026-10-13T05:30:00Z"), false, calendarId = 1, deleted = true, title = "Deleted"),
            InstanceRow(ms("2026-10-13T04:30:00Z"), ms("2026-10-13T05:30:00Z"), false, calendarId = 1, syncId = "risi" + "0".repeat(32), title = "Risi copy"),
            InstanceRow(ms("2026-10-19T00:00:00Z"), ms("2026-10-20T00:00:00Z"), true, calendarId = 1, title = "After the range"),
        )
        val be = object : CalendarBackend by FakeCalendarBackend() {
            override fun instanceRows(fromMs: Long, toMs: Long) = rows
        }
        val pc = PhoneCalendar(be, MemoryCalendarChoice(), CalendarWriteLog(), zone = { colombo })
        val ev = pc.localEvents(from, to)!!
        assertEquals(listOf("Hidden cal meeting", "Holiday"), ev.map { it.title })
        assertEquals("Mon 12 Oct · All day · Holiday", LocalEvents.rowText(ev[1], colombo, Locale.UK))
        val denied = object : CalendarBackend by be {
            override fun canRead() = false
        }
        assertNull(PhoneCalendar(denied, MemoryCalendarChoice(), CalendarWriteLog(), zone = { colombo }).localEvents(from, to))
    }
}
