package lk.codegen.risime.data.pdf

import lk.codegen.risime.data.tabs.LedgerItemView
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.RisiNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** §33.14 the content mapping (note, summary, report, answer, calendar) and the page-1 lines. */
class PdfContentTest {
    private val zone = ZoneId.of("Asia/Colombo")
    private val now = java.time.Instant.parse("2026-10-10T01:56:00Z").toEpochMilli()

    @Test fun participantsCapAt20() {
        assertEquals("Participants: Harsha, Shenika, Kamal", PdfContent.participants(listOf("Harsha", "Shenika", "Kamal")))
        assertEquals("Participants: " + (1..20).joinToString(", ") { "P$it" } + " +5 more", PdfContent.participants((1..25).map { "P$it" }))
        assertNull(PdfContent.participants(emptyList()))
    }

    @Test fun noteKeyPointsThenAgreedThenMeetings() {
        val note = RisiNote(
            noteId = "n1", topic = "interview planning", endedAt = "2026-10-09T10:00:00Z", keyPoints = listOf("ශ්‍රී ලංකා කණ්ඩායම"),
            events = listOf(lk.codegen.risime.net.RisiNoteEvent("e1", "Interview", "2026-10-12T08:30:00Z", "2026-10-12T09:30:00Z")),
        )
        val items = listOf(
            LedgerItemView("i1", "n1", "u-k", emptyList(), "Send the quote", null, false, null, "confirmed", null),
            LedgerItemView("i2", "n1", "u-me", emptyList(), "Book the room", null, false, null, "done", null),
        )
        val d = PdfContent.note("Harsha × Shenika · interview planning", note, items, { "Kamal" }, { "Interview · Mon 12 Oct, 14:00" }, listOf("Harsha", "Shenika"), null, now, zone, Locale.UK)
        assertEquals("Harsha × Shenika · interview planning", d.title)
        assertTrue(d.kindLine.startsWith("Risi note · Fri 9 Oct 2026"))
        assertEquals("Participants: Harsha, Shenika", d.participants)
        assertEquals("Written by Risi", d.writtenBy)
        assertEquals(
            listOf(
                PdfBlock.Heading("Key points"), PdfBlock.Bullet("ශ්‍රී ලංකා කණ්ඩායම"),
                PdfBlock.Heading("Agreed"), PdfBlock.Tick("Send the quote", false, "Kamal"), PdfBlock.Tick("Book the room", true, "Kamal"),
                PdfBlock.Heading("Meetings"), PdfBlock.Bullet("Interview · Mon 12 Oct, 14:00"),
            ),
            d.blocks,
        )
        assertTrue(d.generatedLine.startsWith("Generated 10 Oct 2026, 07:26"))
    }

    private fun meta(json: String) = ProtocolJson.decodeFromString(RisiMeta.serializer(), json)

    @Test fun summaryReportAndAnswer() {
        val s = PdfContent.message(
            meta("""{"kind":"summary","summary":"We agreed the plan.","decisions":["Go"],"action_items":["Kamal: quote"],"open_questions":["Budget?"]}"""),
            "body", now, emptyList(), listOf("A", "B"), "RisiMe model (risi-l1)", now, zone, Locale.UK,
        )
        assertEquals(
            listOf(
                PdfBlock.Paragraph("We agreed the plan."), PdfBlock.Heading("Decisions"), PdfBlock.Bullet("Go"),
                PdfBlock.Heading("Action items"), PdfBlock.Tick("Kamal: quote", false), PdfBlock.Heading("Open questions"), PdfBlock.Bullet("Budget?"),
            ),
            s.blocks,
        )
        assertEquals("Written by Risi · RisiMe model (risi-l1)", s.writtenBy)
        val r = PdfContent.message(meta("""{"kind":"report","title":"Weekly","sections":[{"heading":"Done","body":"x"}]}"""), "b", now, emptyList(), emptyList(), null, now, zone, Locale.UK)
        assertEquals("Weekly", r.title)
        assertEquals(listOf(PdfBlock.Heading("Done"), PdfBlock.Paragraph("x")), r.blocks)
        assertNull(r.participants)
        val a = PdfContent.message(meta("""{"kind":"answer","answer":"Yes."}"""), "b", now, listOf("Kamal · 10 Oct, 09:30"), emptyList(), null, now, zone, Locale.UK)
        assertEquals(listOf(PdfBlock.Paragraph("Yes."), PdfBlock.Heading("Sources"), PdfBlock.Bullet("Kamal · 10 Oct, 09:30")), a.blocks)
    }

    @Test fun calendarHeadingPerDayAndEventLines() {
        val day = java.time.LocalDate.of(2026, 10, 12).atStartOfDay(zone).toInstant().toEpochMilli()
        val ev = listOf(
            PdfContent.CalEvent("Interview", day + 10 * 3_600_000L, day + 11 * 3_600_000L, false, listOf("Shenika")),
            PdfContent.CalEvent("Holiday", day + 86_400_000L, day + 2 * 86_400_000L, true),
        )
        val d = PdfContent.calendar(day, day + 7 * 86_400_000L, ev, now, zone, Locale.UK)
        assertEquals("Calendar · 12–18 Oct 2026", d.kindLine)
        assertNull(d.participants)
        assertEquals(
            listOf(
                PdfBlock.Heading("Mon 12 Oct 2026"), PdfBlock.Bullet("10:00–11:00 Interview · with Shenika"),
                PdfBlock.Heading("Tue 13 Oct 2026"), PdfBlock.Bullet("All day Holiday"),
            ),
            d.blocks,
        )
    }
}
