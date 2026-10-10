package lk.codegen.risime.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import lk.codegen.risime.data.pdf.PdfBlock
import lk.codegen.risime.data.pdf.PdfDoc
import lk.codegen.risime.data.pdf.PdfFonts
import lk.codegen.risime.data.pdf.PdfWriter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * §33.20 gate 4 (the PDF risk): Sinhala and Tamil PDFs from PdfDocument with the embedded Noto fonts,
 * through the API 29+ CustomFallbackBuilder and the API 26–28 script runs. The host checks them with
 * `pdffonts` and `pdftotext` after `adb pull /sdcard/Android/data/<pkg>/files/pdf-gate`.
 */
@RunWith(AndroidJUnit4::class)
class PdfScriptsDeviceTest {

    private fun doc(lang: String) = when (lang) {
        "si" -> PdfDoc(
            title = "ශ්‍රී ලංකා සංචාරය සැලසුම",
            kindLine = "Risi note · Fri 9 Oct 2026",
            generatedLine = "Generated 10/10/26, 07:26",
            participants = "Participants: Harsha, Shenika, Kamal",
            writtenBy = "Written by Risi",
            blocks = listOf(
                PdfBlock.Heading("ප්‍රධාන කරුණු"),
                PdfBlock.Bullet("ශ්‍රී ලංකා කණ්ඩායම සඳුදා හමුවේ"),
                PdfBlock.Paragraph("අපි ක්‍රියාත්මක කිරීමේ සැලැස්ම ප්‍රගතිය සමාලෝචනය කළෙමු."),
                PdfBlock.Heading("Agreed"),
                PdfBlock.Tick("වාර්තාව සකස් කිරීම", done = false, meta = "Kamal · Mon 12 Oct"),
                PdfBlock.Tick("Budget sign-off", done = true, meta = "Harsha"),
            ),
        )
        else -> PdfDoc(
            title = "க்ஷேத்திர பயணத் திட்டம்",
            kindLine = "Risi note · Fri 9 Oct 2026",
            generatedLine = "Generated 10/10/26, 07:26",
            participants = "Participants: Harsha, Shenika",
            writtenBy = "Written by Risi",
            blocks = listOf(
                PdfBlock.Heading("முக்கிய குறிப்புகள்"),
                PdfBlock.Bullet("க்ஷ ஸ்ரீ இலங்கை குழு திங்கள் சந்திக்கும்"),
                PdfBlock.Paragraph("திட்டத்தின் முன்னேற்றத்தை நாங்கள் மதிப்பாய்வு செய்தோம்."),
                PdfBlock.Tick("அறிக்கை தயாரித்தல்", done = false, meta = "Kamal · Mon 12 Oct"),
            ),
        )
    }

    @Test
    fun sinhalaAndTamilPdfs() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "pdf-gate").apply { deleteRecursively(); mkdirs() }
        val now = java.time.ZonedDateTime.now()
        for (runs in listOf(false, true)) {
            val fonts = PdfFonts.load(ctx.assets, useFallbackBuilder = !runs)
            for (lang in listOf("si", "ta")) {
                val out = PdfWriter(fonts).render(doc(lang), "RisiMe test", now)
                File(dir, "$lang-${if (runs) "runs" else "fallback"}.pdf").writeBytes(out.bytes)
                assertTrue(out.pages >= 1)
                org.junit.Assert.assertEquals("ActualText on every page", out.pages, out.annotatedPages)
            }
        }
        // A long document: pagination, "Page n of N".
        val long = doc("si").copy(blocks = (1..120).map { PdfBlock.Bullet("අයිතමය $it — ශ්‍රී ලංකා item $it") })
        val out = PdfWriter(PdfFonts.load(ctx.assets)).render(long, "RisiMe test", now)
        File(dir, "si-long.pdf").writeBytes(out.bytes)
        assertTrue("pages ${out.pages}", out.pages >= 2)
        org.junit.Assert.assertEquals(out.pages, out.annotatedPages)
    }
}
