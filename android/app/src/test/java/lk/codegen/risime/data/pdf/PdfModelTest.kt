package lk.codegen.risime.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

class PdfModelTest {

    @Test
    fun scriptRunsKeepJoinersInsideTheirRun() {
        val s = "ශ්‍රී ලංකා and க்ஷ"
        val runs = ScriptRuns.split(s)
        assertEquals(ScriptRuns.Script.SINHALA, runs[0].script)
        // ZWJ (U+200D) stays inside the Sinhala run: ශ්‍රී is one run with the following Sinhala word.
        assertTrue(s.substring(runs[0].start, runs[0].end).startsWith("ශ්‍රී"))
        assertEquals(listOf(ScriptRuns.Script.SINHALA, ScriptRuns.Script.LATIN, ScriptRuns.Script.SINHALA, ScriptRuns.Script.LATIN, ScriptRuns.Script.TAMIL), runs.map { it.script })
        assertEquals("க்ஷ", s.substring(runs.last().start, runs.last().end))
        // Tamil Supplement (U+11FC0..) counts as Tamil.
        assertEquals(ScriptRuns.Script.TAMIL, ScriptRuns.scriptOf(0x11FC0))
        assertEquals(emptyList<ScriptRuns.Run>(), ScriptRuns.split(""))
    }

    @Test
    fun segmentsSplitAtSpacesAndScripts() {
        val s = "Budget ශ්‍රීlanka  க்ஷ"
        val seg = PdfWriter.segments(s, 0, s.length).map { s.substring(it.first, it.second) }
        assertEquals(listOf("Budget", "ශ්‍රී", "lanka", "க்ஷ"), seg)
    }

    @Test
    fun paginatorBreaksPagesAndKeepsHeadingsWithNext() {
        val items = listOf(
            Paginator.Item(listOf(30f), 0f),
            Paginator.Item(List(10) { 10f }, 5f),
            Paginator.Item(listOf(20f), 10f, keepWithNext = true),
            Paginator.Item(listOf(10f, 10f), 4f),
        )
        val s = Paginator.paginate(items, pageHeight = 150f)
        // 30 + 5 + 100 = 135; the heading (10 + 20) + next first line (10) doesn't fit → page 2.
        assertEquals(0, s.first { it.item == 2 }.page - 1)
        assertEquals(1, s.first { it.item == 2 }.page)
        assertEquals(0f, s.first { it.item == 2 }.y)
        assertEquals(2, Paginator.pageCount(s))
        // A long item splits across pages by lines.
        val long = Paginator.paginate(listOf(Paginator.Item(List(25) { 10f }, 0f)), pageHeight = 100f)
        assertEquals(listOf(0 to 10, 10 to 20, 20 to 25), long.map { it.from to it.to })
        assertEquals(3, Paginator.pageCount(long))
    }

    @Test(expected = PdfTooLongException::class)
    fun moreThan200PagesIsTooLong() {
        Paginator.paginate(listOf(Paginator.Item(List(2_100) { 10f }, 0f)), pageHeight = 100f)
    }

    @Test
    fun fileNameIsSanitisedAndCut() {
        val d = java.time.LocalDate.of(2026, 10, 9)
        assertEquals("Interview planning – 2026-10-09.pdf", PdfNames.fileName("Interview planning", d))
        assertEquals("a-b- – 2026-10-09.pdf", PdfNames.fileName("a/b\\", d))
        assertEquals("RisiMe – 2026-10-09.pdf", PdfNames.fileName("  ", d))
        val long = PdfNames.fileName("x".repeat(300), d)
        assertEquals(124, long.length)
        assertTrue(long.endsWith(".pdf"))
        assertTrue(lk.codegen.risime.data.media.FileEnvelope.validName(long))
        assertEquals("– 2026-10-09.pdf", PdfNames.fileName("..", d))
    }

    @Test
    fun pdfStringsAndDates() {
        assertEquals("<FEFF0041>", PdfNames.pdfString("A"))
        assertEquals("<FEFF0DC1>", PdfNames.pdfString("ශ"))
        val t = java.time.ZonedDateTime.of(2026, 10, 9, 7, 26, 0, 0, java.time.ZoneId.of("Asia/Colombo"))
        assertEquals("D:20261009072600+05'30'", PdfNames.pdfDate(t))
    }

    @Test
    fun annotateWrapsEveryTextObject() {
        val content = "q\nBT\n/F1 11 Tf\n<0001> Tj\nET\n0 0 m\nBT\n/F2 11 Tf\n<0002> Tj\nET\nQ\n"
        val out = PdfPost.annotate(content, listOf("ශ්‍රී", "ok"))!!
        assertTrue(out.contains("/Span <</ActualText ${PdfNames.pdfString("ශ්‍රී")}>> BDC\nBT\n/F1 11 Tf\n<0001> Tj\nET\nEMC"))
        assertTrue(out.contains("/Span <</ActualText ${PdfNames.pdfString("ok")}>> BDC\nBT"))
        // A count mismatch leaves the page alone.
        assertNull(PdfPost.annotate(content, listOf("one")))
    }

    /** A minimal classic-xref PDF shaped like Skia's output (2 pages, a nested page tree, Flate contents). */
    private fun skiaLike(): ByteArray {
        val objs = linkedMapOf<Int, ByteArray>()
        fun z(s: String): ByteArray { val d = Deflater(); d.setInput(s.toByteArray(Charsets.ISO_8859_1)); d.finish(); val o = ByteArrayOutputStream(); val b = ByteArray(1024); while (!d.finished()) o.write(b, 0, d.deflate(b)); return o.toByteArray() }
        fun stream(n: Int, s: String) { val data = z(s); objs[n] = "$n 0 obj\n<</Filter /FlateDecode\n/Length ${data.size}>> stream\n".toByteArray(Charsets.ISO_8859_1) + data + "\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1) }
        fun obj(n: Int, s: String) { objs[n] = "$n 0 obj\n$s\nendobj\n".toByteArray(Charsets.ISO_8859_1) }
        obj(1, "<</Producer (Skia/PDF m112)>>")
        stream(2, "BT\n<0001> Tj\nET\nBT\n<0002> Tj\nET\n")
        obj(3, "<</Type /Page\n/Resources <</Font <</F1 9 0 R>>>>\n/MediaBox [0 0 595 842]\n/Contents 2 0 R\n/Parent 6 0 R>>")
        stream(4, "BT\n<0003> Tj\nET\n")
        obj(5, "<</Type /Page\n/Resources <</Font <</F1 9 0 R>>>>\n/Contents 4 0 R\n/Parent 6 0 R>>")
        obj(6, "<</Type /Pages\n/Count 2\n/Kids [3 0 R 5 0 R]>>")
        obj(7, "<</Type /Catalog\n/Pages 6 0 R>>")
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray())
        val off = HashMap<Int, Int>()
        for ((n, b) in objs) { off[n] = out.size(); out.write(b) }
        val x = out.size()
        val sb = StringBuilder("xref\n0 8\n0000000000 65535 f \n")
        for (n in 1..7) sb.append("%010d 00000 n \n".format(off[n]))
        sb.append("trailer\n<</Size 8\n/Root 7 0 R\n/Info 1 0 R>>\nstartxref\n$x\n%%EOF")
        out.write(sb.toString().toByteArray())
        return out.toByteArray()
    }

    @Test
    fun finishAddsActualTextAndInfoAsAnIncrementalUpdate() {
        val pdf = skiaLike()
        val r = PdfPost.finish(pdf, listOf(listOf("ශ්‍රී", "ලංකා"), listOf("க்ஷ")), "Title ශ", "RisiMe 0.3.0", java.time.ZonedDateTime.of(2026, 10, 9, 7, 26, 0, 0, java.time.ZoneOffset.UTC))
        assertNotNull(r)
        r!!
        assertEquals(2, r.pages)
        assertEquals(2, r.annotatedPages)
        val s = String(r.bytes, Charsets.ISO_8859_1)
        // The original bytes are kept; the update follows.
        assertTrue(s.startsWith(String(pdf, Charsets.ISO_8859_1)))
        val tail = s.substring(pdf.size)
        assertTrue(tail.contains("8 0 obj\n<< /Title ${PdfNames.pdfString("Title ශ")} /Producer ${PdfNames.pdfString("RisiMe 0.3.0")} /CreationDate (D:20261009072600+00'00') >>"))
        assertTrue(!tail.contains("/Author"))
        assertTrue(tail.contains("/Size 9 /Root 7 0 R /Info 8 0 R /Prev "))
        // The new content stream of page 1 carries both spans.
        val m = Regex("2 0 obj\n<</Filter /FlateDecode\n/Length (\\d+)>>\nstream\n").find(tail)!!
        val len = m.groupValues[1].toInt()
        val start = pdf.size + m.range.last + 1
        val inf = Inflater(); inf.setInput(r.bytes, start, len); val buf = ByteArray(4096); val n = inf.inflate(buf)
        val content = String(buf, 0, n, Charsets.ISO_8859_1)
        assertEquals(2, Regex("/ActualText").findAll(content).count())
        // The xref entry for object 2 points at the new object.
        val xref = Regex("xref\n((?:\\d+ 1\n\\d{10} 00000 n \n)+)trailer").find(tail)!!.groupValues[1]
        val entry = Regex("2 1\n(\\d{10})").find(xref)!!.groupValues[1].toInt()
        assertTrue(s.startsWith("2 0 obj", entry))
        val sx = Regex("startxref\n(\\d+)\n%%EOF\n$").find(s)!!.groupValues[1].toInt()
        assertTrue(s.startsWith("xref", sx))
    }

    @Test
    fun finishLeavesAMismatchedPageAlone() {
        val r = PdfPost.finish(skiaLike(), listOf(listOf("only one"), listOf("க்ஷ")), "t", "p", java.time.ZonedDateTime.now())!!
        assertEquals(1, r.annotatedPages)
        assertNull(PdfPost.finish("not a pdf".toByteArray(), emptyList(), "t", "p", java.time.ZonedDateTime.now()))
    }
}
