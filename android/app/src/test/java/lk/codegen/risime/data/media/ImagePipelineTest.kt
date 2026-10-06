package lk.codegen.risime.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** §14.7 Sending 2–3 and android R2, on the bytes: the output is re-read and must carry no metadata. */
class ImagePipelineTest {
    private val ops = AwtBitmapOps()
    private val pipeline = ImagePipeline(ops)

    private fun reread(b: ByteArray) = ImageIO.read(ByteArrayInputStream(b))!!

    private fun assertClean(b: ByteArray) {
        assertEquals(emptyList<String>(), ImageBytes.findMetadata(b))
        for (needle in listOf("Exif", "http://ns.adobe.com/xap", "ICC_PROFILE", "Display P3", "MPF", "hdrgm", "Photoshop", "camera comment", "iCCP", "eXIf", "tEXt", "iTXt")) {
            assertFalse("found $needle", Fixtures.contains(b, needle.toByteArray()))
        }
        assertFalse("found GPS", Fixtures.contains(b, Fixtures.GPS_MARKER))
    }

    @Test
    fun jpegWithExifOrientationGpsXmpIccAndThumbnailComesOutRotatedAndClean() {
        val embeddedThumb = Fixtures.jpeg(Fixtures.image(16, 12))
        val src = Fixtures.withJpegMetadata(Fixtures.jpeg(Fixtures.image(300, 200)), orientation = 6, thumbnail = embeddedThumb)
        assertEquals(6, ImageBytes.exifOrientation(src))
        assertTrue(ImageBytes.findMetadata(src).containsAll(listOf("APP1 EXIF", "APP1 XMP", "APP2 ICC", "APP2 MPF", "APP13", "COM")))

        val out = pipeline.prepare(src)
        assertEquals(ImageEnvelope.MIME_JPEG, out.mime)
        // Orientation 6 applied to the pixels: 300×200 becomes 200×300, and nothing says "rotate" any more.
        assertEquals(200 to 300, out.w to out.h)
        assertEquals(200 to 300, ImageBytes.dimensions(out.bytes))
        assertEquals(1, ImageBytes.exifOrientation(out.bytes))
        val img = reread(out.bytes)
        assertEquals(200 to 300, img.width to img.height)
        assertClean(out.bytes)
        assertFalse(Fixtures.contains(out.bytes, embeddedThumb))

        // The thumbnail is a downscale of the same oriented bitmap, clean too.
        val t = assertNotNull(out.thumb).let { out.thumb!! }
        assertEquals(ImageEnvelope.MIME_JPEG, t.mime)
        assertEquals(85 to 128, t.w to t.h)
        assertTrue(t.data.size <= ImageEnvelope.MAX_THUMB_BYTES)
        assertClean(t.data)
        assertEquals(85 to 128, reread(t.data).let { it.width to it.height })
        assertFalse(t.data.contentEquals(embeddedThumb))
    }

    @Test
    fun longestSideIsBoundedTo2048AndNeverUpscaled() {
        val big = pipeline.prepare(Fixtures.jpeg(Fixtures.image(4000, 1000)))
        assertEquals(2048 to 512, big.w to big.h)
        assertEquals(2048 to 512, ImageBytes.dimensions(big.bytes))
        val small = pipeline.prepare(Fixtures.jpeg(Fixtures.image(50, 40)))
        assertEquals(50 to 40, small.w to small.h)
        assertEquals(50 to 40, small.thumb!!.w to small.thumb!!.h)
    }

    @Test
    fun transparentPngStaysPngAndClean() {
        val out = pipeline.prepare(Fixtures.withPngMetadata(Fixtures.png(Fixtures.image(120, 80, alpha = true))))
        assertEquals(ImageEnvelope.MIME_PNG, out.mime)
        assertClean(out.bytes)
        assertEquals(120 to 80, ImageBytes.dimensions(out.bytes))
        assertTrue(reread(out.bytes).colorModel.hasAlpha())
        // The thumbnail is a JPEG flattened onto white.
        assertEquals(ImageEnvelope.MIME_JPEG, out.thumb!!.mime)
    }

    @Test
    fun opaquePngBecomesJpeg() {
        val out = pipeline.prepare(Fixtures.png(Fixtures.image(64, 64)))
        assertEquals(ImageEnvelope.MIME_JPEG, out.mime)
        assertClean(out.bytes)
    }

    @Test
    fun largeTransparentPngIsFlattenedToJpeg() {
        val out = pipeline.prepare(Fixtures.png(Fixtures.image(1400, 1400, alpha = true, noise = true)))
        assertEquals(ImageEnvelope.MIME_JPEG, out.mime)
        assertClean(out.bytes)
        assertTrue(ops.calls.any { it.startsWith("png") })
    }

    @Test
    fun thumbnailLadderFitsIn4096Bytes() {
        val out = pipeline.prepare(Fixtures.jpeg(Fixtures.image(1024, 1024, noise = true)))
        val t = out.thumb!!
        assertTrue(t.data.size <= ImageEnvelope.MAX_THUMB_BYTES)
        // Noise doesn't fit at 128 px q60: the ladder steps down (q50, q40, then 96 px …).
        val thumbCalls = ops.calls.filter { it.startsWith("jpeg") && !it.startsWith("jpeg 1024") }
        assertEquals("jpeg 128x128 q60", thumbCalls.first())
        assertTrue(thumbCalls.size > 1)
        assertTrue(t.w < 128 || thumbCalls.size in 2..3)
    }

    @Test
    fun undecodableInputIsRejected() {
        assertTrue(runCatching { pipeline.prepare("not an image".toByteArray()) }.exceptionOrNull() is ImageRejected)
        assertTrue(runCatching { pipeline.prepare(ByteArray(0)) }.exceptionOrNull() is ImageRejected)
    }

    @Test
    fun saveReencodesDecryptedBytesClean() {
        val decrypted = Fixtures.withJpegMetadata(Fixtures.jpeg(Fixtures.image(100, 60)), orientation = 1)
        val saved = pipeline.reencodeForSave(decrypted)
        assertClean(saved)
        assertFalse(saved.contentEquals(decrypted))
        assertEquals(100 to 60, ImageBytes.dimensions(saved))
    }
}
