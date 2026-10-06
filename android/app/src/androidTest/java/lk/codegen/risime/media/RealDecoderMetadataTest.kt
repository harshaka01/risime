package lk.codegen.risime.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import lk.codegen.risime.data.media.AndroidBitmapOps
import lk.codegen.risime.data.media.ImageBytes
import lk.codegen.risime.data.media.ImageEnvelope
import lk.codegen.risime.data.media.ImagePipeline
import lk.codegen.risime.data.media.PreparedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * §14.7 Sending 2–3 / android R2 on the device's REAL decoders and encoders (ImageDecoder, Skia):
 * the bytes the app would encrypt carry no GPS, EXIF (or its thumbnail), XMP, ICC or MPF, the
 * orientation is in the pixels, the longest side is ≤ 2048, the colours are sRGB, and the
 * thumbnail is a downscale of the re-encoded pixels.
 */
@RunWith(AndroidJUnit4::class)
class RealDecoderMetadataTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().context
    private val pipeline = ImagePipeline(AndroidBitmapOps)

    private fun asset(name: String) = ctx.assets.open(name).use { it.readBytes() }

    private fun contains(hay: ByteArray, needle: String): Boolean = String(hay, Charsets.ISO_8859_1).contains(needle)

    /** Every check on the produced bytes, parsed by the platform ExifInterface and a raw marker scan. */
    private fun assertClean(b: ByteArray, what: String) {
        val exif = ExifInterface(ByteArrayInputStream(b))
        assertFalse("$what: GPS lat/long", exif.getLatLong(FloatArray(2)))
        for (tag in listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_XMP)) {
            assertNull("$what: EXIF $tag", exif.getAttribute(tag))
        }
        assertFalse("$what: EXIF thumbnail", exif.hasThumbnail())
        assertEquals("$what: EXIF orientation", ExifInterface.ORIENTATION_UNDEFINED, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals("$what: metadata containers", emptyList<String>(), ImageBytes.findMetadata(b))
        for (needle in listOf("Exif\u0000", "http://ns.adobe.com", "hdrgm", "ICC_PROFILE", "MPF\u0000", "Photoshop", "Colombo", "GPS", "iCCP", "eXIf", "tEXt", "zTXt", "iTXt")) {
            assertFalse("$what: raw bytes contain \"$needle\"", contains(b, needle))
        }
        if (ImageBytes.sniff(b) == ImageBytes.Format.JPEG) {
            // Raw marker scan: no APP1..APP15 or COM segment before the scan.
            var p = 2
            while (p + 4 <= b.size && b[p] == 0xFF.toByte()) {
                val m = b[p + 1].toInt() and 0xFF
                if (m == 0xDA) break
                assertFalse("$what: marker FF%02X".format(m), m in 0xE1..0xEF || m == 0xFE)
                p += 2 + (((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF))
            }
        }
    }

    private fun decode(b: ByteArray): Bitmap = BitmapFactory.decodeByteArray(b, 0, b.size)!!

    private fun isRed(c: Int) = Color.red(c) > 200 && Color.green(c) < 60 && Color.blue(c) < 60

    private fun isBlue(c: Int) = Color.blue(c) > 200 && Color.red(c) < 60 && Color.green(c) < 60

    @Test
    fun gpsOrientationXmpMpfAndExifThumbnailAreGoneAndTheOrientationIsInThePixels() {
        val src = asset("gps_orient6.jpg")
        // The fixture really carries all of it (as read by the platform).
        val inExif = ExifInterface(ByteArrayInputStream(src))
        assertTrue(inExif.getLatLong(FloatArray(2)))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, inExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertTrue(inExif.hasThumbnail())

        val out: PreparedImage = pipeline.prepare(src)
        assertEquals(ImageEnvelope.MIME_JPEG, out.mime)
        assertClean(out.bytes, "image")
        // 3000×2000 stored, orientation 6: displayed 2000×3000, bounded to 1365×2048.
        assertEquals(2048, maxOf(out.w, out.h))
        assertTrue("portrait after rotation: ${out.w}x${out.h}", out.h > out.w)
        assertEquals(out.w to out.h, ImageBytes.dimensions(out.bytes))
        val bmp = decode(out.bytes)
        assertEquals(out.w to out.h, bmp.width to bmp.height)
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), bmp.colorSpace)
        // The red block (stored top-left) is top-right after a 90° clockwise rotation.
        assertTrue("red at top-right", isRed(bmp.getPixel(bmp.width - 20, 20)))
        assertFalse("not red at top-left", isRed(bmp.getPixel(20, 20)))

        val t = out.thumb!!
        assertTrue(t.data.size <= ImageEnvelope.MAX_THUMB_BYTES)
        assertEquals(ImageEnvelope.MIME_JPEG, t.mime)
        assertClean(t.data, "thumbnail")
        val tb = decode(t.data)
        assertEquals(t.w to t.h, tb.width to tb.height)
        assertEquals(128, maxOf(t.w, t.h))
        assertTrue("thumbnail portrait like the re-encode", t.h > t.w)
        // From the re-encoded pixels: the red corner is top-right; never the blue EXIF thumbnail.
        assertTrue("thumbnail red at top-right", isRed(tb.getPixel(tb.width - 2, 2)))
        assertFalse("thumbnail is the embedded EXIF one", isBlue(tb.getPixel(tb.width / 2, tb.height / 2)))
    }

    @Test
    fun displayP3JpegIsConvertedToSrgbWithoutAnIccProfile() {
        // A real platform-encoded Display P3 JPEG (Skia writes the ICC profile, as cameras do).
        val p3Color = Color.pack(0.80f, 0.40f, 0.20f, 1f, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        val p3 = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        Canvas(p3).drawColor(p3Color)
        val src = ByteArrayOutputStream().also { p3.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        assertTrue("fixture carries an ICC profile", contains(src, "ICC_PROFILE"))
        assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), BitmapFactory.decodeByteArray(src, 0, src.size).colorSpace)

        val out = pipeline.prepare(src)
        assertClean(out.bytes, "image")
        assertClean(out.thumb!!.data, "thumbnail")
        val bmp = decode(out.bytes)
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), bmp.colorSpace)
        // Colour-managed: the pixel is the P3 colour converted to sRGB, not the raw P3 numbers.
        val expected = Color.valueOf(Color.convert(p3Color, ColorSpace.get(ColorSpace.Named.SRGB)))
        val got = Color.valueOf(bmp.getPixel(200, 150))
        for ((e, g) in listOf(expected.red() to got.red(), expected.green() to got.green(), expected.blue() to got.blue())) {
            assertTrue("sRGB value $g vs expected $e", kotlin.math.abs(e - g) < 0.04f)
        }
        assertTrue("differs from the unconverted P3 numbers", kotlin.math.abs(got.red() - 0.80f) > 0.02f || kotlin.math.abs(got.blue() - 0.20f) > 0.02f)
    }

    @Test
    fun pngTextIccAndExifChunksAreGoneAndTransparencyKept() {
        val src = asset("text_alpha.png")
        assertTrue(contains(src, "tEXt") && contains(src, "iTXt") && contains(src, "iCCP") && contains(src, "eXIf"))
        val out = pipeline.prepare(src)
        assertEquals(ImageEnvelope.MIME_PNG, out.mime)
        assertClean(out.bytes, "png")
        assertEquals(600 to 400, out.w to out.h)
        val bmp = decode(out.bytes)
        assertTrue(bmp.hasAlpha())
        assertTrue("half-transparent half kept", Color.alpha(bmp.getPixel(10, 10)) in 60..120)
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), bmp.colorSpace)
        assertClean(out.thumb!!.data, "thumbnail")
    }

    @Test
    fun saveToGalleryReencodesDecryptedBytesClean() {
        val saved = pipeline.reencodeForSave(asset("gps_orient6.jpg"))
        assertClean(saved, "saved")
        assertTrue(maxOf(decode(saved).width, decode(saved).height) <= 2048)
    }
}
