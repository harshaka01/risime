package lk.codegen.risime.data.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * §33.14 / §33.20 gate 4: Android's PdfDocument (Skia) draws Sinhala and Tamil correctly, but its
 * glyph-to-Unicode map can't express conjuncts, ligatures ("ff") or reordered vowel signs (ෙ, ே), so
 * `pdftotext` loses them. This post-pass wraps every text object Skia wrote (one `BT … ET` per draw
 * call of one font run; PdfWriter draws one word-segment per call and records its text) in a marked
 * content span with `/ActualText`, the PDF way to give extractors the real text. It is appended as an
 * incremental update together with the Info dictionary (Title, Producer, CreationDate; no Author).
 *
 * If a page's text objects don't match its records one to one, that page is left as Skia wrote it
 * (still correct on screen and in print) and [Result.annotatedPages] says so.
 */
object PdfPost {
    data class Result(val bytes: ByteArray, val annotatedPages: Int, val pages: Int)

    private val L1 = Charsets.ISO_8859_1

    /** [texts]: per page (in page order), the text of every recorded draw, in draw order. */
    fun finish(pdf: ByteArray, texts: List<List<String>>, title: String, producer: String, created: java.time.ZonedDateTime): Result? {
        val s = String(pdf, L1)
        val startxref = Regex("startxref\\s+(\\d+)\\s+%%EOF\\s*$").find(s.takeLast(2048))?.groupValues?.get(1)?.toInt() ?: return null
        val offsets = xref(s, startxref) ?: return null
        val trailer = Regex("trailer\\s*<<(.*?)>>\\s*startxref", RegexOption.DOT_MATCHES_ALL).findAll(s, startxref).lastOrNull()?.groupValues?.get(1) ?: return null
        val size = Regex("/Size\\s+(\\d+)").find(trailer)?.groupValues?.get(1)?.toInt() ?: return null
        val root = Regex("/Root\\s+(\\d+)\\s+0\\s+R").find(trailer)?.groupValues?.get(1)?.toInt() ?: return null
        fun dict(n: Int): String? = offsets[n]?.let { o -> Regex("$n\\s+0\\s+obj\\s*<<(.*?)>>\\s*(?:stream|endobj)", RegexOption.DOT_MATCHES_ALL).matchAt(s, o)?.groupValues?.get(1) }
        val pagesRoot = dict(root)?.let { Regex("/Pages\\s+(\\d+)\\s+0\\s+R").find(it)?.groupValues?.get(1)?.toInt() } ?: return null
        val pages = mutableListOf<Int>()
        fun walk(n: Int, depth: Int) {
            if (depth > 32) return
            val d = dict(n) ?: return
            val kids = Regex("/Kids\\s*\\[([^\\]]*)\\]").find(d)?.groupValues?.get(1)
            if (kids == null) { pages += n; return }
            Regex("(\\d+)\\s+0\\s+R").findAll(kids).forEach { walk(it.groupValues[1].toInt(), depth + 1) }
        }
        walk(pagesRoot, 0)
        val updates = linkedMapOf<Int, ByteArray>()
        var annotated = 0
        for ((i, p) in pages.withIndex()) {
            val records = texts.getOrNull(i) ?: continue
            val cn = dict(p)?.let { Regex("/Contents\\s+(\\d+)\\s+0\\s+R").find(it)?.groupValues?.get(1)?.toInt() } ?: continue
            val raw = stream(s, pdf, cn, offsets[cn] ?: continue) ?: continue
            val content = String(raw, L1)
            val fixed = annotate(content, records) ?: continue
            val z = deflate(fixed.toByteArray(L1))
            val head = "$cn 0 obj\n<</Filter /FlateDecode\n/Length ${z.size}>>\nstream\n".toByteArray(L1)
            updates[cn] = head + z + "\nendstream\nendobj\n".toByteArray(L1)
            annotated++
        }
        val info = size
        updates[info] = "$info 0 obj\n<< /Title ${PdfNames.pdfString(title)} /Producer ${PdfNames.pdfString(producer)} /CreationDate (${PdfNames.pdfDate(created)}) >>\nendobj\n".toByteArray(L1)
        val out = ByteArrayOutputStream(pdf.size + updates.values.sumOf { it.size } + 1024)
        out.write(pdf)
        if (pdf.last() != '\n'.code.toByte()) out.write('\n'.code)
        val at = linkedMapOf<Int, Int>()
        for ((n, b) in updates) { at[n] = out.size(); out.write(b) }
        val xrefAt = out.size()
        val x = StringBuilder("xref\n")
        for ((n, o) in at.entries.sortedBy { it.key }) x.append("$n 1\n").append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", o))
        x.append("trailer\n<< /Size ${size + 1} /Root $root 0 R /Info $info 0 R /Prev $startxref >>\nstartxref\n$xrefAt\n%%EOF\n")
        out.write(x.toString().toByteArray(L1))
        return Result(out.toByteArray(), annotated, pages.size)
    }

    /**
     * Wraps the k-th `BT … ET` of [content] with `/Span <</ActualText …>> BDC … EMC` for record k.
     * Null when the counts differ (the page is then left alone).
     */
    fun annotate(content: String, records: List<String>): String? {
        val bt = Regex("(?m)^BT$")
        val et = Regex("(?m)^ET$")
        val starts = bt.findAll(content).map { it.range.first }.toList()
        val ends = et.findAll(content).map { it.range.last + 1 }.toList()
        if (starts.size != records.size || ends.size != records.size) return null
        for (k in starts.indices) if (ends[k] <= starts[k] || (k + 1 < starts.size && ends[k] > starts[k + 1])) return null
        val sb = StringBuilder(content.length + records.sumOf { it.length * 4 + 40 })
        var pos = 0
        for (k in starts.indices) {
            sb.append(content, pos, starts[k])
            sb.append("/Span <</ActualText ").append(PdfNames.pdfString(records[k])).append(">> BDC\n")
            sb.append(content, starts[k], ends[k])
            sb.append("\nEMC")
            pos = ends[k]
        }
        sb.append(content, pos, content.length)
        return sb.toString()
    }

    /** The classic xref table at [at] (Skia writes one section, no object streams). */
    private fun xref(s: String, at: Int): Map<Int, Int>? {
        if (!s.startsWith("xref", at)) return null
        val out = HashMap<Int, Int>()
        var i = at + 4
        val sub = Regex("\\s*(\\d+)\\s+(\\d+)[ \\t]*\\r?\\n")
        while (true) {
            val m = sub.matchAt(s, i) ?: break
            val first = m.groupValues[1].toInt()
            val count = m.groupValues[2].toInt()
            i = m.range.last + 1
            for (k in 0 until count) {
                val line = s.substring(i, minOf(s.length, i + 20))
                val off = line.substring(0, 10).trim().toIntOrNull() ?: return null
                if (line.length > 17 && line[17] == 'n') out[first + k] = off
                i += 20
            }
        }
        return out
    }

    private fun stream(s: String, b: ByteArray, n: Int, at: Int): ByteArray? {
        val m = Regex("$n\\s+0\\s+obj\\s*<<(.*?)>>\\s*stream\\r?\\n", RegexOption.DOT_MATCHES_ALL).matchAt(s, at) ?: return null
        val d = m.groupValues[1]
        val len = Regex("/Length\\s+(\\d+)(?!\\s+0\\s+R)").find(d)?.groupValues?.get(1)?.toInt() ?: return null
        val start = m.range.last + 1
        if (start + len > b.size) return null
        val data = b.copyOfRange(start, start + len)
        if (!d.contains("/Filter")) return data
        if (!Regex("/Filter\\s*/FlateDecode").containsMatchIn(d)) return null
        return inflate(data)
    }

    private fun inflate(z: ByteArray): ByteArray? = runCatching {
        val inf = Inflater()
        inf.setInput(z)
        val out = ByteArrayOutputStream(z.size * 4)
        val buf = ByteArray(16384)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        out.toByteArray()
    }.getOrNull()

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(b)
        d.finish()
        val out = ByteArrayOutputStream(b.size / 2 + 64)
        val buf = ByteArray(16384)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }
}
