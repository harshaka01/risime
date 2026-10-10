package lk.codegen.risime.data.pdf

import lk.codegen.risime.data.media.FileEnvelope

/*
 * v1.34 §33.14 phone-only PDF export: the document model, script runs, pagination, the file name and
 * the Info dictionary update. Pure Kotlin (JVM-tested); the Android drawing is PdfWriter.kt.
 */

/** One PDF document: page 1's header block and the body. Strings are already localised. */
data class PdfDoc(
    val title: String,
    /** "Risi note · Fri 9 Oct 2026", "Calendar · 12–18 Oct 2026". */
    val kindLine: String,
    /** "Generated <date, time>". */
    val generatedLine: String,
    /** "Participants: Harsha, Shenika, Kamal" or null (calendar, Risi-chat-only answer). */
    val participants: String? = null,
    /** "Written by Risi" plus the §27.1 made_by label, or null. */
    val writtenBy: String? = null,
    val blocks: List<PdfBlock>,
    val footer: String = "Made on this phone with RisiMe",
    /** "Page %1$d of %2$d". */
    val pageFormat: String = "Page %1\$d of %2\$d",
)

sealed interface PdfBlock {
    data class Heading(val text: String) : PdfBlock

    data class Paragraph(val text: String) : PdfBlock

    data class Bullet(val text: String) : PdfBlock

    /** An item with a drawn tick box; [meta] = "owner · due". */
    data class Tick(val text: String, val done: Boolean, val meta: String? = null) : PdfBlock
}

/** The §33.14 page geometry (A4 in points). */
object PdfPage {
    const val WIDTH = 595
    const val HEIGHT = 842
    const val SIDE = 48f
    const val TOP = 76f
    const val BOTTOM = 64f
    const val MAX_PAGES = 200
    val contentHeight: Float get() = HEIGHT - TOP - BOTTOM
    val contentWidth: Float get() = WIDTH - 2 * SIDE
}

class PdfTooLongException : Exception("more than ${PdfPage.MAX_PAGES} pages")

/**
 * Pagination over measured items: each item is a list of line heights with the space before it.
 * A heading keeps with the first line of the next item. Lines are never split.
 */
object Paginator {
    /** A measured item. */
    data class Item(val lines: List<Float>, val spaceBefore: Float, val keepWithNext: Boolean = false)

    /** A slice of item [item] lines [from, to) placed at [y] (from the content top) on page [page]. */
    data class Slice(val page: Int, val item: Int, val from: Int, val to: Int, val y: Float)

    fun paginate(items: List<Item>, pageHeight: Float = PdfPage.contentHeight, maxPages: Int = PdfPage.MAX_PAGES): List<Slice> {
        val out = mutableListOf<Slice>()
        var page = 0
        var y = 0f
        for ((i, it) in items.withIndex()) {
            if (it.lines.isEmpty()) continue
            var before = if (y == 0f) 0f else it.spaceBefore
            // keep-with-next: a heading and the next item's first line go together.
            val need = it.lines.sum() + (if (it.keepWithNext) items.getOrNull(i + 1)?.lines?.firstOrNull() ?: 0f else 0f)
            if (y > 0f && y + before + need > pageHeight && need <= pageHeight) {
                page++; y = 0f; before = 0f
            }
            var from = 0
            var sliceY = y + before
            var cur = sliceY
            for ((li, h) in it.lines.withIndex()) {
                if (cur + h > pageHeight && cur > 0f) {
                    if (li > from) out += Slice(page, i, from, li, sliceY)
                    page++
                    if (page >= maxPages) throw PdfTooLongException()
                    from = li; sliceY = 0f; cur = 0f
                }
                cur += h
            }
            out += Slice(page, i, from, it.lines.size, sliceY)
            y = cur
            if (page >= maxPages) throw PdfTooLongException()
        }
        return out
    }

    fun pageCount(slices: List<Slice>): Int = (slices.maxOfOrNull { it.page } ?: 0) + 1
}

/** §33.14 API 26–28: split text into runs by script, each drawn with its own typeface. */
object ScriptRuns {
    enum class Script { LATIN, SINHALA, TAMIL }

    data class Run(val start: Int, val end: Int, val script: Script)

    fun scriptOf(cp: Int): Script? = when (cp) {
        in 0x0D80..0x0DFF -> Script.SINHALA
        in 0x0B80..0x0BFF, in 0x11FC0..0x11FFF -> Script.TAMIL
        // Joiners and combining marks stay with the run they're in (ශ්‍රී needs the ZWJ in the Sinhala run).
        0x200C, 0x200D, in 0x0300..0x036F, in 0xFE00..0xFE0F -> null
        else -> Script.LATIN
    }

    fun split(text: String): List<Run> {
        val out = mutableListOf<Run>()
        var i = 0
        var start = 0
        var cur: Script? = null
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = scriptOf(cp)
            if (s != null && cur != null && s != cur) {
                out += Run(start, i, cur)
                start = i
            }
            if (s != null) cur = s
            i += Character.charCount(cp)
        }
        if (text.isNotEmpty()) out += Run(start, text.length, cur ?: Script.LATIN)
        return out
    }
}

/** §33.14 file name and Info. */
object PdfNames {
    /** `<title> – <yyyy-MM-dd>.pdf`, sanitised as §33.13, at most 120 graphemes before `.pdf`. */
    fun fileName(title: String, date: java.time.LocalDate): String {
        val base = FileEnvelope.sanitizeName("${title.trim().ifEmpty { "RisiMe" }} – $date").trimStart('.').trim()
        return FileEnvelope.cutGraphemes(base, 120).trimEnd() + ".pdf"
    }

    /** A PDF text string as UTF-16BE hex with a BOM (safe for any script). */
    fun pdfString(s: String): String {
        val b = StringBuilder("<FEFF")
        for (c in s) b.append(String.format(java.util.Locale.ROOT, "%04X", c.code))
        return b.append('>').toString()
    }

    fun pdfDate(t: java.time.ZonedDateTime): String {
        val off = t.offset.totalSeconds
        val sign = if (off < 0) '-' else '+'
        val a = kotlin.math.abs(off)
        return String.format(java.util.Locale.ROOT, "D:%04d%02d%02d%02d%02d%02d%c%02d'%02d'", t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second, sign, a / 3600, (a % 3600) / 60)
    }
}
