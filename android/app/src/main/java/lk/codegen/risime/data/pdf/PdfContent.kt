package lk.codegen.risime.data.pdf

import lk.codegen.risime.data.tabs.LedgerItemView
import lk.codegen.risime.net.RisiItemStates
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.RisiNote
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * v1.34 §33.14 the content mapping: what a PDF holds for each source. The text is the content's own
 * (the phone's copy of the source); headings are the app's labels ([Labels], localised with the app).
 * Pure: JVM-tested (PdfContentTest).
 */
object PdfContent {
    /** The headings and fixed lines (English; the localised set comes with §30.9's strings). */
    data class Labels(
        val keyPoints: String = "Key points",
        val agreed: String = "Agreed",
        val meetings: String = "Meetings",
        val decisions: String = "Decisions",
        val actionItems: String = "Action items",
        val openQuestions: String = "Open questions",
        val sources: String = "Sources",
        val writtenByRisi: String = "Written by Risi",
        val participants: String = "Participants",
        val generated: String = "Generated",
        val riseNote: String = "Risi note",
        val summary: String = "Summary",
        val report: String = "Report",
        val answer: String = "Risi answer",
        val digest: String = "Digest",
        val calendar: String = "Calendar",
        val footer: String = "Made on this phone with RisiMe",
        val page: String = "Page %1\$d of %2\$d",
        val with: String = "with",
        val allDay: String = "All day",
    )

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
    private val DAY_SHORT = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val STAMP = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    private fun at(iso: String?, zone: ZoneId) = iso?.let { runCatching { Instant.parse(it).atZone(zone) }.getOrNull() }

    fun generatedLine(now: Long, zone: ZoneId, locale: Locale, l: Labels = Labels()): String =
        "${l.generated} " + STAMP.withLocale(locale).format(Instant.ofEpochMilli(now).atZone(zone))

    /** "Participants: Harsha, Shenika, Kamal"; more than 20 as "+n more"; null when there are none. */
    fun participants(names: List<String>, l: Labels = Labels()): String? {
        val n = names.filter { it.isNotBlank() }.distinct()
        if (n.isEmpty()) return null
        val shown = n.take(20)
        return "${l.participants}: " + shown.joinToString(", ") + if (n.size > 20) " +${n.size - 20} more" else ""
    }

    /** §33.14 Note: key points, then "Agreed" (tick boxes with owner and due), then "Meetings". */
    fun note(
        title: String, note: RisiNote, items: List<LedgerItemView>, ownerDue: (LedgerItemView) -> String, eventLine: (lk.codegen.risime.net.RisiNoteEvent) -> String,
        participantNames: List<String>, madeBy: String?, now: Long, zone: ZoneId, locale: Locale, l: Labels = Labels(),
    ): PdfDoc {
        val date = at(note.endedAt ?: note.createdAt, zone)?.let { DAY.withLocale(locale).format(it) }
        val blocks = buildList {
            if (note.keyPoints.isNotEmpty()) {
                add(PdfBlock.Heading(l.keyPoints))
                note.keyPoints.forEach { add(PdfBlock.Bullet(it)) }
            }
            if (items.isNotEmpty()) {
                add(PdfBlock.Heading(l.agreed))
                items.forEach { add(PdfBlock.Tick(it.text, it.state == RisiItemStates.DONE, ownerDue(it).takeIf { s -> s.isNotBlank() })) }
            }
            if (note.events.isNotEmpty()) {
                add(PdfBlock.Heading(l.meetings))
                note.events.forEach { add(PdfBlock.Bullet(eventLine(it))) }
            }
        }
        return doc(title, listOfNotNull(l.riseNote, date).joinToString(" · "), participantNames, madeBy, blocks, now, zone, locale, l)
    }

    /**
     * §33.14 a Risi message: summary (the summary, then "Decisions", "Action items", "Open questions"),
     * report (`sections`), answer (the answer, then "Sources": names and times only), digest (its items).
     */
    fun message(
        r: RisiMeta, body: String, sentAt: Long, sourceLines: List<String>, participantNames: List<String>, madeBy: String?,
        now: Long, zone: ZoneId, locale: Locale, l: Labels = Labels(),
    ): PdfDoc {
        val date = DAY.withLocale(locale).format(Instant.ofEpochMilli(sentAt).atZone(zone))
        val kindLabel: String
        val title: String
        val blocks = mutableListOf<PdfBlock>()
        when (r.kind) {
            "summary" -> {
                kindLabel = l.summary
                title = r.title?.takeIf { it.isNotBlank() } ?: r.topic?.takeIf { it.isNotBlank() } ?: l.summary
                (r.summary ?: body).takeIf { it.isNotBlank() }?.let { blocks += PdfBlock.Paragraph(it) }
                section(blocks, l.decisions, r.decisions)
                if (r.actionItems.isNotEmpty()) {
                    blocks += PdfBlock.Heading(l.actionItems)
                    r.actionItems.forEach { blocks += PdfBlock.Tick(it, false) }
                }
                section(blocks, l.openQuestions, r.openQuestions)
            }
            "report" -> {
                kindLabel = l.report
                title = r.title?.takeIf { it.isNotBlank() } ?: l.report
                if (r.sections.isEmpty()) blocks += PdfBlock.Paragraph(body)
                r.sections.forEach { s -> blocks += PdfBlock.Heading(s.heading); blocks += PdfBlock.Paragraph(s.body) }
            }
            "answer" -> {
                kindLabel = l.answer
                title = r.question?.takeIf { it.isNotBlank() } ?: l.answer
                blocks += PdfBlock.Paragraph(r.answer?.takeIf { it.isNotBlank() } ?: body)
                section(blocks, l.sources, sourceLines)
            }
            "digest" -> {
                kindLabel = l.digest
                title = r.title?.takeIf { it.isNotBlank() } ?: l.digest
                body.takeIf { it.isNotBlank() && r.items.isEmpty() }?.let { blocks += PdfBlock.Paragraph(it) }
                r.items.forEach { blocks += PdfBlock.Tick(it.text, it.state == RisiItemStates.DONE) }
            }
            else -> {
                // discussion_summary and anything else exportable: its key points and items, else the body.
                kindLabel = l.summary
                title = r.topic?.takeIf { it.isNotBlank() } ?: r.title?.takeIf { it.isNotBlank() } ?: l.summary
                if (r.keyPoints.isEmpty() && r.items.isEmpty()) blocks += PdfBlock.Paragraph(body)
                section(blocks, l.keyPoints, r.keyPoints)
                if (r.items.isNotEmpty()) {
                    blocks += PdfBlock.Heading(l.agreed)
                    r.items.forEach { blocks += PdfBlock.Tick(it.text, it.state == RisiItemStates.DONE) }
                }
            }
        }
        return doc(title, "$kindLabel · $date", participantNames, madeBy, blocks, now, zone, locale, l)
    }

    /** One calendar event as the PDF lists it. */
    data class CalEvent(val title: String, val start: Long, val end: Long, val allDay: Boolean, val with: List<String> = emptyList())

    /** §33.14 Calendar: one heading per day; each event is "10:00–11:00 Title · with …". ≤ 31 days. */
    fun calendar(from: Long, to: Long, events: List<CalEvent>, now: Long, zone: ZoneId, locale: Locale, l: Labels = Labels()): PdfDoc {
        val a = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val b = Instant.ofEpochMilli(to - 1).atZone(zone).toLocalDate()
        val range = if (a == b) DAY_SHORT.withLocale(locale).format(a) else "${a.dayOfMonth}–${DAY_SHORT.withLocale(locale).format(b)}"
        val blocks = mutableListOf<PdfBlock>()
        for ((day, list) in events.filter { it.end > from && it.start < to }.sortedBy { it.start }.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }) {
            blocks += PdfBlock.Heading(DAY.withLocale(locale).format(day))
            list.forEach { e ->
                val time = if (e.allDay) l.allDay else TIME.format(Instant.ofEpochMilli(e.start).atZone(zone)) + "–" + TIME.format(Instant.ofEpochMilli(e.end).atZone(zone))
                val with = if (e.with.isEmpty()) "" else " · ${l.with} " + e.with.joinToString(", ")
                blocks += PdfBlock.Bullet("$time ${e.title}$with")
            }
        }
        return doc("${l.calendar} · $range", "${l.calendar} · $range", emptyList(), null, blocks, now, zone, locale, l)
    }

    private fun section(blocks: MutableList<PdfBlock>, heading: String, lines: List<String>) {
        if (lines.isEmpty()) return
        blocks += PdfBlock.Heading(heading)
        lines.forEach { blocks += PdfBlock.Bullet(it) }
    }

    private fun doc(
        title: String, kindLine: String, participantNames: List<String>, madeBy: String?, blocks: List<PdfBlock>,
        now: Long, zone: ZoneId, locale: Locale, l: Labels,
    ) = PdfDoc(
        title = title,
        kindLine = kindLine,
        generatedLine = generatedLine(now, zone, locale, l),
        participants = participants(participantNames, l),
        writtenBy = if (madeBy == null && kindLine.startsWith(l.calendar)) null else listOfNotNull(l.writtenByRisi, madeBy).joinToString(" · "),
        blocks = blocks,
        footer = l.footer,
        pageFormat = l.page,
    )
}
