package lk.codegen.risime.data.pdf

import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.MetricAffectingSpan
import androidx.annotation.RequiresApi

/**
 * §33.14 the embedded fonts: Noto Sans (Latin), Noto Sans Sinhala and Noto Sans Tamil, Regular and Bold,
 * from the APK's assets (SIL OFL 1.1). System fonts are never used for Sinhala or Tamil.
 */
class PdfFonts private constructor(
    /** API 29+: one typeface per weight with the custom fallback chain (Noto Sans → Sinhala → Tamil → system). */
    private val fallback: Map<Boolean, Typeface>?,
    /** One typeface per script and weight (API 26–28: the text is split into runs by script). */
    private val perScript: Map<Pair<ScriptRuns.Script, Boolean>, Typeface>,
) {
    val usesFallbackBuilder: Boolean get() = fallback != null

    /** For measuring (StaticLayout): the fallback typeface (29+), else script runs as spans. */
    fun styled(text: String, bold: Boolean, paint: TextPaint): CharSequence {
        val fb = fallback?.get(bold)
        if (fb != null) {
            paint.typeface = fb
            return text
        }
        paint.typeface = face(ScriptRuns.Script.LATIN, bold)
        val sp = SpannableString(text)
        for (r in ScriptRuns.split(text)) {
            if (r.script == ScriptRuns.Script.LATIN) continue
            sp.setSpan(FaceSpan(face(r.script, bold)), r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    /** For drawing one single-script segment. */
    fun drawFace(script: ScriptRuns.Script, bold: Boolean): Typeface = when (script) {
        // Latin runs may hold characters outside Noto Sans (other scripts, symbols): the fallback chain draws them.
        ScriptRuns.Script.LATIN -> fallback?.get(bold) ?: face(script, bold)
        else -> face(script, bold)
    }

    fun face(script: ScriptRuns.Script, bold: Boolean): Typeface = perScript.getValue(script to bold)

    private class FaceSpan(val face: Typeface) : MetricAffectingSpan() {
        override fun updateMeasureState(p: TextPaint) { p.typeface = face }

        override fun updateDrawState(p: TextPaint) { p.typeface = face }
    }

    companion object {
        const val DIR = "fonts"
        private fun file(script: ScriptRuns.Script, bold: Boolean): String {
            val fam = when (script) {
                ScriptRuns.Script.LATIN -> "NotoSans"
                ScriptRuns.Script.SINHALA -> "NotoSansSinhala"
                ScriptRuns.Script.TAMIL -> "NotoSansTamil"
            }
            return "$DIR/$fam-${if (bold) "Bold" else "Regular"}.ttf"
        }

        fun load(assets: AssetManager, useFallbackBuilder: Boolean = Build.VERSION.SDK_INT >= 29): PdfFonts {
            val per = buildMap {
                for (s in ScriptRuns.Script.entries) for (b in listOf(false, true)) put(s to b, Typeface.createFromAsset(assets, file(s, b)))
            }
            val fb = if (useFallbackBuilder && Build.VERSION.SDK_INT >= 29) mapOf(false to fallbackFace(assets, false), true to fallbackFace(assets, true)) else null
            return PdfFonts(fb, per)
        }

        @RequiresApi(29)
        private fun fallbackFace(assets: AssetManager, bold: Boolean): Typeface {
            fun fam(s: ScriptRuns.Script) = android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(assets, file(s, bold)).build()).build()
            return Typeface.CustomFallbackBuilder(fam(ScriptRuns.Script.LATIN))
                .addCustomFallback(fam(ScriptRuns.Script.SINHALA))
                .addCustomFallback(fam(ScriptRuns.Script.TAMIL))
                .setSystemFallback("sans-serif")
                .build()
        }
    }
}

/**
 * §33.14 draws a [PdfDoc] with `android.graphics.pdf.PdfDocument`: StaticLayout breaks the lines, and
 * each word is drawn as its own single-script segment whose text is recorded, so [PdfPost] can add
 * `/ActualText` (Sinhala and Tamil conjuncts and reordered vowel signs extract correctly). Two passes
 * give "Page n of N".
 */
class PdfWriter(private val fonts: PdfFonts) {

    class Output(val bytes: ByteArray, val pages: Int, val annotatedPages: Int)

    private class Measured(val text: String, val layout: StaticLayout, val paint: TextPaint, val bold: Boolean, val indent: Float, val block: Any?, val spaceBefore: Float, val keep: Boolean)

    /** Renders [doc] and finishes it ([PdfPost]: ActualText and Info). Throws [PdfTooLongException]. */
    fun render(doc: PdfDoc, producer: String, created: java.time.ZonedDateTime): Output {
        val (raw, texts) = draw(doc)
        val done = PdfPost.finish(raw, texts, doc.title, producer, created)
        return if (done != null) Output(done.bytes, texts.size, done.annotatedPages) else Output(raw, texts.size, 0)
    }

    /** The raw Skia PDF and, per page, the recorded text of every draw call. */
    fun draw(doc: PdfDoc): Pair<ByteArray, List<List<String>>> {
        val width = PdfPage.contentWidth
        val items = mutableListOf<Measured>()
        fun add(text: String, size: Float, bold: Boolean, color: Int, indent: Float, block: Any?, before: Float, keep: Boolean = false) {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color }
            val cs = fonts.styled(text, bold, p)
            val l = StaticLayout.Builder.obtain(cs, 0, cs.length, p, (width - indent).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).setIncludePad(true).build()
            items += Measured(text, l, p, bold, indent, block, before, keep)
        }
        add(doc.title, 20f, true, Color.BLACK, 0f, null, 0f)
        add(doc.kindLine, 10f, false, GREY, 0f, null, 6f)
        add(doc.generatedLine, 10f, false, GREY, 0f, null, 2f)
        doc.participants?.let { add(it, 10f, false, GREY, 0f, null, 2f) }
        doc.writtenBy?.let { add(it, 10f, false, GREY, 0f, null, 2f) }
        for (b in doc.blocks) when (b) {
            is PdfBlock.Heading -> add(b.text, 14f, true, Color.BLACK, 0f, b, 16f, keep = true)
            is PdfBlock.Paragraph -> add(b.text, 11f, false, Color.BLACK, 0f, b, 8f)
            is PdfBlock.Bullet -> add(b.text, 11f, false, Color.BLACK, 16f, b, 4f)
            is PdfBlock.Tick -> {
                add(b.text, 11f, false, Color.BLACK, 20f, b, 6f)
                b.meta?.takeIf { it.isNotBlank() }?.let { add(it, 9f, false, GREY, 20f, null, 0f) }
            }
        }
        val slices = Paginator.paginate(items.map { m -> Paginator.Item((0 until m.layout.lineCount).map { (m.layout.getLineBottom(it) - m.layout.getLineTop(it)).toFloat() }, m.spaceBefore, m.keep) })
        val pages = Paginator.pageCount(slices)
        val texts = List(pages) { mutableListOf<String>() }
        val pdf = PdfDocument()
        try {
            for (pg in 0 until pages) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(PdfPage.WIDTH, PdfPage.HEIGHT, pg + 1).create())
                val c = page.canvas
                val rec = texts[pg]
                chrome(c, rec, doc, pg + 1, pages)
                for (s in slices.filter { it.page == pg }) {
                    val m = items[s.item]
                    val top = m.layout.getLineTop(s.from).toFloat()
                    val x = PdfPage.SIDE + m.indent
                    val y = PdfPage.TOP + s.y
                    if (s.from == 0) decorate(c, m, y)
                    for (line in s.from until s.to) {
                        drawLine(c, rec, m.text, m.layout, line, m.paint, m.bold, x, y + (m.layout.getLineBaseline(line) - top))
                    }
                }
                pdf.finishPage(page)
            }
            val out = java.io.ByteArrayOutputStream()
            pdf.writeTo(out)
            return out.toByteArray() to texts
        } finally {
            pdf.close()
        }
    }

    /** Draws one laid-out line word by word, one script per draw call, recording each segment's text. */
    private fun drawLine(c: Canvas, rec: MutableList<String>, text: String, layout: Layout, line: Int, base: TextPaint, bold: Boolean, x: Float, baseline: Float) {
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        for ((s, e, script) in segments(text, start, end)) {
            val p = TextPaint(base).apply { typeface = fonts.drawFace(script, bold) }
            c.drawText(text, s, e, x + layout.getPrimaryHorizontal(s), baseline, p)
            rec += text.substring(s, e)
        }
    }

    /** Bullets and tick boxes are drawn as vector shapes, never glyphs. */
    private fun decorate(c: Canvas, m: Measured, y: Float) {
        val mid = y + (m.layout.getLineBaseline(0) - m.layout.getLineTop(0)) - 3.6f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        when (val b = m.block) {
            is PdfBlock.Bullet -> c.drawCircle(PdfPage.SIDE + 5f, mid, 2.2f, p)
            is PdfBlock.Tick -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 0.9f
                val l = PdfPage.SIDE + 1f
                c.drawRect(l, mid - 4.5f, l + 9f, mid + 4.5f, p)
                if (b.done) {
                    p.strokeWidth = 1.4f
                    c.drawPath(android.graphics.Path().apply { moveTo(l + 1.8f, mid); lineTo(l + 3.8f, mid + 2.6f); lineTo(l + 7.6f, mid - 3f) }, p)
                }
            }
            else -> Unit
        }
    }

    /** Header (wordmark, ellipsised title, hairline) and footer ("Made on this phone with RisiMe", "Page n of N"). */
    private fun chrome(c: Canvas, rec: MutableList<String>, doc: PdfDoc, n: Int, total: Int) {
        val word = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = ACCENT; typeface = fonts.face(ScriptRuns.Script.LATIN, true) }
        c.drawText(WORDMARK, PdfPage.SIDE, 40f, word)
        rec += WORDMARK
        val avail = PdfPage.contentWidth - word.measureText(WORDMARK) - 24f
        single(c, rec, doc.title, 9f, PdfPage.WIDTH - PdfPage.SIDE - avail, 40f, avail, Layout.Alignment.ALIGN_OPPOSITE)
        val hair = Paint().apply { color = GREY; strokeWidth = 0.5f }
        c.drawLine(PdfPage.SIDE, 50f, PdfPage.WIDTH - PdfPage.SIDE, 50f, hair)
        val fy = PdfPage.HEIGHT - 30f
        single(c, rec, doc.footer, 8.5f, PdfPage.SIDE, fy, 180f, Layout.Alignment.ALIGN_NORMAL)
        single(c, rec, String.format(java.util.Locale.ROOT, doc.pageFormat, n, total), 8.5f, PdfPage.WIDTH / 2f - 90f, fy, 180f, Layout.Alignment.ALIGN_CENTER)
    }

    /** One ellipsised line (any script) with its baseline at [baseline]. */
    private fun single(c: Canvas, rec: MutableList<String>, text: String, size: Float, x: Float, baseline: Float, w: Float, align: Layout.Alignment) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = GREY }
        val shown = TextUtils.ellipsize(fonts.styled(text, false, p), p, w, TextUtils.TruncateAt.END).toString()
        val cs = fonts.styled(shown, false, p)
        val l = StaticLayout.Builder.obtain(cs, 0, cs.length, p, w.toInt().coerceAtLeast(1)).setAlignment(align).setMaxLines(1).build()
        drawLine(c, rec, shown, l, 0, p, false, x, baseline)
    }

    companion object {
        const val WORDMARK = "RisiMe"
        private const val GREY = 0xFF5F6368.toInt()
        private const val ACCENT = 0xFF0B6E4F.toInt()

        /** Word segments of [text] in [start, end): split at whitespace, then by script (ZWJ/ZWNJ stay inside). */
        fun segments(text: String, start: Int, end: Int): List<Triple<Int, Int, ScriptRuns.Script>> {
            val out = mutableListOf<Triple<Int, Int, ScriptRuns.Script>>()
            var i = start
            while (i < end) {
                while (i < end && Character.isWhitespace(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
                var j = i
                while (j < end && !Character.isWhitespace(text.codePointAt(j))) j += Character.charCount(text.codePointAt(j))
                if (j > i) for (r in ScriptRuns.split(text.substring(i, j))) out += Triple(i + r.start, i + r.end, r.script)
                i = j
            }
            return out
        }
    }
}
