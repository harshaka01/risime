package lk.codegen.risime.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format sniffing, header sizes and the strict display checks (§14.7 Receiving 4, android R3). */
class ImageBytesTest {
    private val jpeg = Fixtures.jpeg(Fixtures.image(40, 30))
    private val png = Fixtures.png(Fixtures.image(20, 10))

    // A 2-pixel lossless WebP (VP8L) and a 1x1 lossy one (VP8), byte for byte.
    private val webpLossless = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x1a, 0, 0, 0, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x4c, 0x0d, 0, 0, 0,
        0x2f, 0x01, 0x40, 0x00, 0x00, 0x00, 0, 0, 0, 0, 0, 0, 0, 0,
    )

    @Test
    fun sniffAndDimensions() {
        assertEquals(ImageBytes.Format.JPEG, ImageBytes.sniff(jpeg))
        assertEquals(40 to 30, ImageBytes.dimensions(jpeg))
        assertEquals(ImageBytes.Format.PNG, ImageBytes.sniff(png))
        assertEquals(20 to 10, ImageBytes.dimensions(png))
        assertEquals(ImageBytes.Format.WEBP, ImageBytes.sniff(webpLossless))
        assertEquals(2 to 2, ImageBytes.dimensions(webpLossless))
        assertEquals(ImageBytes.Format.GIF, ImageBytes.sniff("GIF89a....".toByteArray()))
        assertEquals(ImageBytes.Format.HEIF, ImageBytes.sniff(byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray()))
        assertNull(ImageBytes.dimensions("GIF89a....".toByteArray()))
        assertNull(ImageBytes.dimensions(jpeg.copyOf(10)))
    }

    @Test
    fun displayCheckAcceptsMatchingImagesAndSamplesToTheSlot() {
        val big = Fixtures.jpeg(Fixtures.image(2048, 1024))
        val ok = DisplayCheck.check(big, "image/jpeg", 2048, 1024, 2048, 600) as DisplayCheck.Result.Ok
        assertEquals(2, ok.sampleSize) // 2048 → 1024 ≥ 600, → 512 < 600
        assertTrue(DisplayCheck.check(big, "image/jpeg", 2047, 1025, 2048, 2048) is DisplayCheck.Result.Ok) // rounding
    }

    @Test
    fun displayCheckRefusesMismatchesAndBombs() {
        fun refused(r: DisplayCheck.Result) = assertTrue(r.toString(), r is DisplayCheck.Result.Refused)
        // The declared mime must match the sniffed bytes.
        refused(DisplayCheck.check(png, "image/jpeg", 20, 10, 2048, 600))
        // A decompression bomb: declares 100×100, is 3000×10.
        val bomb = Fixtures.jpeg(Fixtures.image(3000, 10))
        refused(DisplayCheck.check(bomb, "image/jpeg", 100, 100, 2048, 600))
        refused(DisplayCheck.check(bomb, "image/jpeg", 3000, 10, 2048, 600))
        // Envelope size differs beyond rounding.
        refused(DisplayCheck.check(jpeg, "image/jpeg", 40, 20, 2048, 600))
        // Thumbnails: at most 128 px.
        refused(DisplayCheck.check(Fixtures.jpeg(Fixtures.image(200, 100)), "image/jpeg", 200, 100, 128, 128))
        // Never routed to HEIF/GIF decoders, never unknown bytes.
        refused(DisplayCheck.check("GIF89a....".toByteArray(), "image/jpeg", 1, 1, 2048, 600))
        refused(DisplayCheck.check(byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray(), "image/jpeg", 1, 1, 2048, 600))
        refused(DisplayCheck.check(ByteArray(100), "image/png", 1, 1, 2048, 600))
    }

    @Test
    fun stripKeepsAPlainJfifAndTheScan() {
        val src = Fixtures.withJpegMetadata(jpeg, orientation = 3)
        val out = ImageBytes.stripMetadata(src)
        assertEquals(jpeg.size, out.size)
        assertTrue(out.contentEquals(jpeg))
        assertEquals(emptyList<String>(), ImageBytes.findMetadata(out))
    }

    @Test
    fun webpMetadataChunksAreRemoved() {
        // VP8X with ICC + EXIF + XMP flags, then the three chunks, then the image chunk.
        fun chunk(t: String, d: ByteArray) = t.toByteArray() + byteArrayOf(d.size.toByte(), 0, 0, 0) + d + (if (d.size % 2 == 1) byteArrayOf(0) else byteArrayOf())
        val vp8x = chunk("VP8X", byteArrayOf(0x2C, 0, 0, 0, 1, 0, 0, 1, 0, 0))
        val image = webpLossless.copyOfRange(12, webpLossless.size)
        val body = vp8x + chunk("ICCP", "P3".toByteArray()) + chunk("EXIF", "Exif".toByteArray()) + chunk("XMP ", "<x/>".toByteArray()) + image
        val size = body.size + 4
        val src = "RIFF".toByteArray() + byteArrayOf(size.toByte(), (size shr 8).toByte(), 0, 0) + "WEBP".toByteArray() + body
        assertEquals(listOf("ICCP", "EXIF", "XMP "), ImageBytes.findMetadata(src))
        val out = ImageBytes.stripMetadata(src)
        assertEquals(emptyList<String>(), ImageBytes.findMetadata(out))
        assertEquals(0, out[20].toInt()) // VP8X flags cleared
        assertEquals(2 to 2, ImageBytes.dimensions(out))
    }
}
