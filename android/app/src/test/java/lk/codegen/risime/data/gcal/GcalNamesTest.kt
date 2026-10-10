package lk.codegen.risime.data.gcal

import lk.codegen.risime.data.calendar.RisiCalendarTimeline
import lk.codegen.risime.data.calendar.TimelineGoogle
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiEvent
import lk.codegen.risime.net.RisiTextEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GcalNamesTest {
    private fun example(n: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$n")!!.readText()
    private val risi get() = ProtocolJson.decodeFromString<RisiTextEnvelope>(example("envelope_risi_answer_calendar_sources_google.json")).risi!!
    private val names = mapOf("g4k2m7qa" to "Work", "g9t3b8rc" to "Personal")

    @Test fun theCheckedLineShowsTheLocalNamesInRefOrder() {
        val out = GcalNames.render(risi.answer!!, risi.sources, names)
        assertTrue(out, out.contains("Checked: Risi Calendar · Google Calendar (Work, Personal). Not checked: Phone calendar (not connected)."))
        assertTrue(out.startsWith("You're not free then: your calendars have 1 busy time"))
    }

    @Test fun anyUnknownRefOrNoNamesKeepsTheServersCounts() {
        val a = risi.answer!!
        assertEquals(a, GcalNames.render(a, risi.sources, mapOf("g4k2m7qa" to "Work")))
        assertEquals(a, GcalNames.render(a, risi.sources, emptyMap()))
    }

    @Test fun onlyTheLastParagraphThatStartsWithCheckedChanges() {
        val a = "Google Calendar (2 calendars) is mentioned here.\n\nNot a checked line: Google Calendar (2 calendars)"
        assertEquals(a, GcalNames.render(a, risi.sources, names))
    }

    @Test fun aSourceThatWasNotReadKeepsTheText() {
        val noAnswer = ProtocolJson.decodeFromString<RisiTextEnvelope>(example("envelope_risi_answer_calendar_google_no_answer.json")).risi!!
        assertEquals(noAnswer.answer, GcalNames.render(noAnswer.answer!!, noAnswer.sources, names))
    }

    private val zone = ZoneId.of("UTC")
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun googleBusyBlocksAreDrawnAndCountAsClashes() {
        val g = TimelineGoogle(listOf(ms("2026-10-12T09:30:00Z") to ms("2026-10-12T10:30:00Z"), ms("2026-10-12T15:00:00Z") to ms("2026-10-12T16:00:00Z")))
        val t = RisiCalendarTimeline.compute(null, "2026-10-12T10:00:00Z", "2026-10-12T11:00:00Z", false, emptyList<RisiEvent>(), zone, g)!!
        assertEquals(2, t.blocks.count { it.google })
        assertEquals(1, t.clashCount)
        assertEquals("Clashes with 1 event", t.clashText)
        assertNull(t.googleNote)
    }

    @Test fun aFailedReadAddsTheCaptionAndNeverShowsGoogleAsFree() {
        val t = RisiCalendarTimeline.compute(null, "2026-10-12T10:00:00Z", "2026-10-12T11:00:00Z", false, emptyList(), zone, TimelineGoogle(note = "Google Calendar not checked"))!!
        assertEquals("Google Calendar not checked", t.googleNote)
        assertEquals(0, t.blocks.count { it.google })
        val other = RisiCalendarTimeline.compute(null, "2026-10-12T10:00:00Z", "2026-10-12T11:00:00Z", false, emptyList(), zone, TimelineGoogle(note = "Google Calendar is checked on Pixel 8"))!!
        assertEquals("Google Calendar is checked on Pixel 8", other.googleNote)
    }

    @Test fun withoutGoogleTheTimelineIsExactlyTheV129One() {
        val t = RisiCalendarTimeline.compute(null, "2026-10-12T10:00:00Z", "2026-10-12T11:00:00Z", false, emptyList(), zone)!!
        assertNull(t.googleNote)
        assertEquals(1, t.blocks.size)
    }
}
