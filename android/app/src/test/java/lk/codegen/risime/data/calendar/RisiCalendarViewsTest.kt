package lk.codegen.risime.data.calendar

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.net.CardParticipant
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiCalendarCard
import lk.codegen.risime.net.RisiCalendarEventsReply
import lk.codegen.risime.net.RisiEvent
import lk.codegen.risime.ui.calendar.RisiCalendarEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * §29 the Calendar tab's views (Agenda, Day, Week, Month), the mini day timeline's clash computation and
 * the cards' state and buttons. All pure, over the local cache, in the viewer's zone.
 */
class RisiCalendarViewsTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val shenika = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val ev = "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b"
    private val colombo = ZoneId.of("Asia/Colombo")
    private val today = LocalDate.of(2026, 10, 9)
    private val now = Instant.parse("2026-10-09T15:11:00Z").toEpochMilli()

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/risi_calendar/$name")!!.readText()
    private val cache: List<RisiEvent> by lazy { ProtocolJson.decodeFromString(RisiCalendarEventsReply.serializer(), fixture("risi_calendar_events_reply.json")).events }
    private val interview get() = cache[0]
    private val standup get() = cache[1]

    private var n = 0
    private fun row(name: String): MessageEntity {
        val env = ProtocolJson.parseToJsonElement(fixture(name)) as JsonObject
        return MessageEntity(
            clientMsgId = "r${n++}", messageId = "m$n", conversationId = "grp:x", from = "risi", to = "grp:x",
            body = (env["body"] as JsonPrimitive).content, serverTs = "2026-10-09T15:10:00.000Z", localTs = n.toLong(),
            status = "READ", outgoing = false, systemJson = RisiMessages.encode(env["risi"] as JsonObject),
        )
    }
    private fun card(name: String) = RisiCalendarCard.parse(row(name).systemJson)!!

    private fun ev(id: String, start: String, end: String, my: String = "accepted", allDay: Boolean = false, state: String = "active") =
        RisiEvent(eventId = id, start = start, end = end, myStatus = my, allDay = allDay, state = state, title = id)

    // ---- the timeline ----

    @Test fun anOverlappingEventIsAClash() {
        // Interview 14:00–15:00 Colombo; Standup 14:30–15:30 (accepted) overlaps.
        val t = RisiCalendarTimeline.compute(ev, interview.start, interview.end, false, cache, colombo)!!
        assertEquals(LocalDate.of(2026, 10, 12), t.date)
        assertEquals(1, t.clashCount)
        assertEquals("Clashes with 1 event", t.clashText)
        assertEquals(7 * 60, t.fromMin)
        assertEquals(21 * 60, t.toMin)
        val self = t.blocks.single { it.self }
        assertEquals(14 * 60, self.startMin)
        assertEquals(15 * 60, self.endMin)
        assertTrue(self.clash)
        val other = t.blocks.single { !it.self }
        assertTrue(other.clash)
        assertEquals(14 * 60 + 30, other.startMin)
    }

    @Test fun declinedCancelledAllDayAndOtherDaysAreLeftOut() {
        val others = listOf(
            ev("declined", "2026-10-12T08:45:00.000Z", "2026-10-12T09:15:00.000Z", my = "declined"),
            ev("cancelled", "2026-10-12T08:45:00.000Z", "2026-10-12T09:15:00.000Z", state = "cancelled"),
            ev("allday", "2026-10-11T18:30:00.000Z", "2026-10-12T18:30:00.000Z", allDay = true),
            ev("tomorrow", "2026-10-13T08:30:00.000Z", "2026-10-13T09:30:00.000Z"),
            ev("morning", "2026-10-12T03:30:00.000Z", "2026-10-12T04:30:00.000Z", my = "proposed"),
        )
        val t = RisiCalendarTimeline.compute(ev, interview.start, interview.end, false, others, colombo)!!
        assertEquals(0, t.clashCount)
        assertNull(t.clashText)
        val o = t.blocks.single { !it.self }
        assertTrue(o.proposed && !o.clash)
        assertEquals(9 * 60, o.startMin)
    }

    @Test fun theWindowWidensToALateOrEarlyEvent() {
        val t = RisiCalendarTimeline.compute(null, "2026-10-12T16:00:00.000Z", "2026-10-12T17:15:00.000Z", false, emptyList(), colombo)!!
        // 21:30–22:45 Colombo → 07:00–23:00.
        assertEquals(7 * 60, t.fromMin)
        assertEquals(23 * 60, t.toMin)
        val early = RisiCalendarTimeline.compute(null, "2026-10-12T00:45:00.000Z", "2026-10-12T01:15:00.000Z", false, emptyList(), colombo)!!
        assertEquals(6 * 60, early.fromMin)
    }

    @Test fun twoClashesAreCounted() {
        val others = listOf(ev("a", "2026-10-12T08:00:00.000Z", "2026-10-12T08:45:00.000Z"), ev("b", "2026-10-12T09:15:00.000Z", "2026-10-12T10:00:00.000Z"),
            ev("touching", "2026-10-12T09:30:00.000Z", "2026-10-12T10:30:00.000Z"))
        val t = RisiCalendarTimeline.compute(ev, interview.start, interview.end, false, others, colombo)!!
        assertEquals(2, t.clashCount)
        assertEquals("Clashes with 2 events", t.clashText)
    }

    @Test fun anAllDayEventNeverClashes() {
        val t = RisiCalendarTimeline.compute("x", "2026-10-11T18:30:00.000Z", "2026-10-12T18:30:00.000Z", true, cache, colombo)!!
        assertEquals(0, t.clashCount)
        assertTrue(t.allDay)
        assertTrue(t.blocks.none { it.self })
    }

    // ---- the views ----

    @Test fun declinedIsHiddenUnlessShown() {
        assertEquals(2, RisiCalendarViews.visible(cache, false).size)
        assertEquals(3, RisiCalendarViews.visible(cache, true).size)
    }

    @Test fun agendaGroupsByDayFromToday() {
        val past = ev("past", "2026-10-01T08:00:00.000Z", "2026-10-01T09:00:00.000Z")
        val a = RisiCalendarViews.agenda(RisiCalendarViews.visible(cache, false) + past, today, colombo)
        assertEquals(listOf(LocalDate.of(2026, 10, 12)), a.map { it.date })
        assertEquals(listOf("Interview", "Standup"), a[0].items.map { it.event.title })
        // An event over midnight shows on both days.
        val night = ev("night", "2026-10-14T17:30:00.000Z", "2026-10-14T19:30:00.000Z")
        assertEquals(listOf(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15)), RisiCalendarViews.agenda(listOf(night), today, colombo).map { it.date })
    }

    @Test fun dayWeekAndMonth() {
        val d = RisiCalendarViews.day(cache, LocalDate.of(2026, 10, 12), colombo)
        assertEquals(listOf("Interview", "Standup"), d.map { it.event.title })
        assertTrue(RisiCalendarViews.day(cache, LocalDate.of(2026, 10, 11), colombo).isEmpty())
        val all = ev("allday", "2026-10-11T18:30:00.000Z", "2026-10-12T18:30:00.000Z", allDay = true)
        assertEquals("allday", RisiCalendarViews.day(cache + all, LocalDate.of(2026, 10, 12), colombo).first().event.eventId)

        val w = RisiCalendarViews.week(cache, LocalDate.of(2026, 10, 14), colombo)
        assertEquals(7, w.size)
        assertEquals(LocalDate.of(2026, 10, 12), w[0].date) // Monday first
        assertEquals(2, w[0].items.size)
        assertEquals(1, w[1].items.size) // the declined lunch (not filtered here)

        val m = RisiCalendarViews.month(cache, YearMonth.of(2026, 10), colombo, today)
        assertTrue(m.all { it.size == 7 })
        val cells = m.flatten()
        assertEquals(3, cells.indexOfFirst { it.date != null }) // 1 Oct 2026 is a Thursday
        assertEquals(2, cells.first { it.date == LocalDate.of(2026, 10, 12) }.count)
        assertTrue(cells.first { it.date == today }.today)
    }

    @Test fun labels() {
        assertEquals("14:00–15:00", RisiCalendarViews.timeLabel(interview, colombo))
        assertEquals("Mon 12 Oct · 14:00–15:00", RisiCalendarViews.whenLabel(interview.start, interview.end, false, colombo))
        assertEquals("Proposed", RisiCalendarViews.eventStatus(interview))
        assertEquals("Cancelled", RisiCalendarViews.eventStatus(interview.copy(state = "cancelled")))
        assertEquals("With: Shenika ?", RisiCalendarViews.withLine(RisiCalendarViews.participantsOf(interview), me) { "Shenika" })
        assertEquals("With: Shenika ✓, Kamal ✗", RisiCalendarViews.withLine(listOf(CardParticipant(shenika, "accepted"), CardParticipant("k", "declined"), CardParticipant(me, "accepted")), me) { if (it == shenika) "Shenika" else "Kamal" })
        assertNull(RisiCalendarViews.withLine(listOf(CardParticipant(me, "accepted")), me) { "x" })
        assertEquals("30 min before", RisiCalendarViews.reminderLabel(30))
        assertEquals("No reminder", RisiCalendarViews.reminderLabel(null))
        assertEquals("1 day before", RisiCalendarViews.reminderLabel(1440))
    }

    @Test fun navigationSteps() {
        val d = CalendarNav(RisiCalendarViews.Mode.DAY, LocalDate.of(2026, 10, 12))
        assertEquals(LocalDate.of(2026, 10, 13), d.next().anchor)
        assertEquals("Monday 12 October", d.title())
        val w = CalendarNav(RisiCalendarViews.Mode.WEEK, LocalDate.of(2026, 10, 14))
        assertEquals("12 Oct – 18 Oct", w.title())
        assertEquals(LocalDate.of(2026, 10, 7), w.prev().anchor)
        val m = CalendarNav(RisiCalendarViews.Mode.MONTH, LocalDate.of(2026, 10, 14))
        assertEquals(LocalDate.of(2026, 11, 1), m.next().anchor)
        assertEquals("October 2026", m.title())
        val (a, b) = m.range(colombo)
        assertEquals(Instant.parse("2026-09-30T18:30:00Z").toEpochMilli(), a)
        assertEquals(Instant.parse("2026-10-31T18:30:00Z").toEpochMilli(), b)
        assertEquals(CalendarNav(RisiCalendarViews.Mode.AGENDA, today), CalendarNav(RisiCalendarViews.Mode.AGENDA, today).next())
    }

    @Test fun anEditBecomesAPatchOfWhatChanged() {
        val same = buildJsonObject { put("title", "Standup"); put("start", standup.start); put("end", standup.end); put("all_day", false) }
        assertNull(RisiCalendarEdits.patchOf(standup, same))
        val renamed = RisiCalendarEdits.patchOf(standup, buildJsonObject { put("title", "Standup (B)"); put("start", standup.start); put("end", standup.end); put("all_day", false) })!!
        assertEquals(setOf("version", "title"), renamed.keys)
        val moved = RisiCalendarEdits.patchOf(standup, buildJsonObject { put("title", "Standup"); put("start", "2026-10-12T10:00:00.000Z"); put("end", "2026-10-12T11:00:00.000Z"); put("all_day", false) })!!
        assertEquals(setOf("version", "start", "end", "all_day", "tz"), moved.keys)
    }

    // ---- the cards ----

    @Test fun aCardFollowsLaterUpdatesAndTheNewerCache() {
        val inv = card("envelope_risi_calendar_invite.json")
        val upd = card("envelope_risi_event_update.json")
        val v1 = RisiEventCards.view(inv, emptyList(), null)!!
        assertEquals("proposed", v1.statusOf(shenika))
        val v2 = RisiEventCards.view(inv, listOf(upd), null)!!
        assertEquals(2, v2.version)
        assertEquals("accepted", v2.statusOf(shenika))
        val cached = interview.copy(version = 3, state = "cancelled")
        assertTrue(RisiEventCards.view(inv, listOf(upd), cached)!!.cancelled)
        // An older cache doesn't override the update.
        assertEquals("accepted", RisiEventCards.view(inv, listOf(upd), interview)!!.statusOf(shenika))
    }

    @Test fun inviteButtonsOnlyForMyProposedActiveUnexpiredEvent() {
        val inv = card("envelope_risi_calendar_invite.json")
        val v = RisiEventCards.view(inv, emptyList(), null)!!
        assertEquals(listOf("accept", "decline", "suggest"), RisiEventCards.answerButtons(inv, v, me, now))
        // The owner never suggests to themselves.
        assertEquals(listOf("accept", "decline"), RisiEventCards.answerButtons(inv, v, shenika, now))
        // Answered, expired (at the start), cancelled, or not a participant: none.
        assertTrue(RisiEventCards.answerButtons(inv, v.copy(participants = listOf(CardParticipant(me, "accepted"))), me, now).isEmpty())
        assertTrue(RisiEventCards.answerButtons(inv, v, me, Instant.parse("2026-10-12T08:30:00Z").toEpochMilli()).isEmpty())
        assertTrue(RisiEventCards.answerButtons(inv, v.copy(cancelled = true), me, now).isEmpty())
        assertTrue(RisiEventCards.answerButtons(inv, v, "stranger", now).isEmpty())
    }

    @Test fun addedCardButtonsForTheOwner() {
        val c = card("envelope_risi_event_card_added.json")
        val v = RisiEventCards.view(c, emptyList(), null)!!
        assertEquals(listOf("open", "edit", "delete"), RisiEventCards.addedButtons(c, v, me))
        assertEquals(listOf("open"), RisiEventCards.addedButtons(c, v, shenika))
        assertEquals(listOf("open"), RisiEventCards.addedButtons(c, v.copy(cancelled = true), me))
    }

    @Test fun suggestionButtonsForTheOwnerUntilAnswered() {
        val s = card("envelope_risi_calendar_suggestion.json")
        assertEquals(listOf("use", "keep"), RisiEventCards.suggestionButtons(s, me, emptySet(), standup))
        assertTrue(RisiEventCards.suggestionButtons(s, shenika, emptySet(), null).isEmpty())
        assertTrue(RisiEventCards.suggestionButtons(s, me, setOf(s.suggestionId!!), standup).isEmpty())
        // Already moved to the suggested time.
        assertTrue(RisiEventCards.suggestionButtons(s, me, emptySet(), standup.copy(start = s.start!!)).isEmpty())
    }

    @Test fun anUpdateShowsNoBubbleNextToItsCardElseALine() {
        val upd = card("envelope_risi_event_update.json")
        assertTrue(RisiEventCards.updateHasCard(upd, listOf(row("envelope_risi_calendar_invite.json"), row("envelope_risi_event_update.json"))))
        assertFalse(RisiEventCards.updateHasCard(upd, listOf(row("envelope_risi_event_update.json"), row("envelope_risi_calendar_reminder.json"))))
        assertEquals("Shenika accepted 'Interview'", RisiEventCards.updateLine(upd, me) { "Shenika" })
        assertEquals("Shenika cancelled 'Interview'", RisiEventCards.updateLine(card("envelope_risi_event_update_cancelled.json"), me) { "Shenika" })
    }

    @Test fun reminderTextAndSuggestDefault() {
        assertEquals("In 30 min: Interview (14:00)", RisiEventCards.reminderText(card("envelope_risi_calendar_reminder.json"), colombo))
        val (s, dur) = RisiEventCards.suggestDefault(interview.start, interview.end, colombo)
        assertEquals(15, s.hour)
        assertEquals(60, dur)
    }
}
