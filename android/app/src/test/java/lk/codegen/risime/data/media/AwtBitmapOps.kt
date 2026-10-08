package lk.codegen.risime.data.media

import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * [BitmapOps] over java.awt for JVM tests (Robolectric has no native graphics on linux-aarch64).
 * Like the platform encoders it stands in for, its encoders are **hostile**: every JPEG/PNG they
 * produce carries EXIF, XMP, an ICC profile, MPF and text chunks, so the tests prove the pipeline
 * strips whatever an encoder writes. Its decoder ignores EXIF orientation (BitmapFactory path).
 */
class AwtBitmapOps(private val leakMetadata: Boolean = true) : BitmapOps<BufferedImage> {
    val calls = mutableListOf<String>()

    init {
        System.setProperty("java.awt.headless", "true")
    }

    override fun decode(bytes: ByteArray, minSide: Int): BitmapOps.Decoded<BufferedImage>? {
        // ImageIO chokes on some foreign APP segments (a fake ICC profile); a device decoder doesn't.
        val clean = runCatching { ImageBytes.stripMetadata(bytes) }.getOrNull() ?: return null
        val img = ImageIO.read(ByteArrayInputStream(clean)) ?: return null
        val argb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
        argb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        return BitmapOps.Decoded(argb, orientationApplied = false)
    }

    override fun width(b: BufferedImage) = b.width

    override fun height(b: BufferedImage) = b.height

    override fun transform(b: BufferedImage, orientation: Int, maxSide: Int): BufferedImage {
        calls += "transform $orientation $maxSide"
        val swap = orientation in 5..8
        val ow = if (swap) b.height else b.width
        val oh = if (swap) b.width else b.height
        val s = minOf(1.0, maxSide.toDouble() / maxOf(ow, oh))
        val tw = maxOf(1, Math.round(ow * s).toInt())
        val th = maxOf(1, Math.round(oh * s).toInt())
        val t = AffineTransform()
        t.scale(tw.toDouble() / ow, th.toDouble() / oh)
        when (orientation) {
            2 -> { t.translate(ow.toDouble(), 0.0); t.scale(-1.0, 1.0) }
            3 -> { t.translate(ow.toDouble(), oh.toDouble()); t.rotate(Math.PI) }
            4 -> { t.translate(0.0, oh.toDouble()); t.scale(1.0, -1.0) }
            5 -> { t.rotate(Math.PI / 2); t.scale(1.0, -1.0) }
            6 -> { t.translate(ow.toDouble(), 0.0); t.rotate(Math.PI / 2) }
            7 -> { t.translate(ow.toDouble(), oh.toDouble()); t.rotate(-Math.PI / 2); t.scale(-1.0, 1.0); t.translate(-b.width.toDouble(), 0.0) }
            8 -> { t.translate(0.0, oh.toDouble()); t.rotate(-Math.PI / 2) }
        }
        val out = BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(b, t, null)
            dispose()
        }
        return out
    }

    override fun hasTransparency(b: BufferedImage): Boolean {
        for (y in 0 until b.height) for (x in 0 until b.width) if ((b.getRGB(x, y) ushr 24) != 0xFF) return true
        return false
    }

    override fun flattenOnWhite(b: BufferedImage): BufferedImage {
        val out = BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, b.width, b.height); drawImage(b, 0, 0, null); dispose() }
        return out
    }

    override fun cropSquare(b: BufferedImage, x: Int, y: Int, side: Int): BufferedImage {
        calls += "crop $x $y $side"
        val out = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply { drawImage(b.getSubimage(x, y, side, side), 0, 0, null); dispose() }
        return out
    }

    override fun encodeJpeg(b: BufferedImage, quality: Int): ByteArray {
        calls += "jpeg ${b.width}x${b.height} q$quality"
        val rgb = BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(b, 0, 0, null); dispose() }
        val w = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            w.output = ios
            val p = w.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality / 100f }
            w.write(null, IIOImage(rgb, null, null), p)
        }
        w.dispose()
        val jpeg = out.toByteArray()
        return if (leakMetadata) Fixtures.withJpegMetadata(jpeg, orientation = 1) else jpeg
    }

    override fun encodePng(b: BufferedImage): ByteArray {
        calls += "png ${b.width}x${b.height}"
        val out = ByteArrayOutputStream()
        ImageIO.write(b, "png", out)
        return if (leakMetadata) Fixtures.withPngMetadata(out.toByteArray()) else out.toByteArray()
    }

    override fun recycle(b: BufferedImage) = Unit
}

/** Test images with every kind of metadata the re-encode must remove. */
object Fixtures {
    val GPS_MARKER = "GPS-6.9271N-79.8612E".toByteArray()
    val XMP = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><hdrgm:Version>1.0</hdrgm:Version></x:xmpmeta>".toByteArray(Charsets.ISO_8859_1)
    val ICC = "ICC_PROFILE\u0000\u0001\u0001Display P3 profile bytes".toByteArray(Charsets.ISO_8859_1)
    val MPF = "MPF\u0000MM\u0000*\u0000\u0000\u0000\u0008 gain map follows".toByteArray(Charsets.ISO_8859_1)

    fun image(w: Int, h: Int, alpha: Boolean = false, noise: Boolean = false): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val r = java.util.Random(7)
        for (y in 0 until h) for (x in 0 until w) {
            val a = if (alpha && x < w / 2) 0x80 else 0xFF
            val rgb = if (noise) r.nextInt(0xFFFFFF) else ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 0x40
            img.setRGB(x, y, (a shl 24) or rgb)
        }
        return img
    }

    fun jpeg(img: BufferedImage): ByteArray = AwtBitmapOps(leakMetadata = false).encodeJpeg(img, 90)

    fun png(img: BufferedImage): ByteArray = AwtBitmapOps(leakMetadata = false).encodePng(img)

    private fun seg(marker: Int, data: ByteArray): ByteArray {
        val len = data.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + data
    }

    /** A big-endian EXIF block: IFD0 {Orientation, GPS pointer} → GPS IFD → IFD1 with an embedded JPEG thumbnail. */
    fun exif(orientation: Int, thumbnail: ByteArray): ByteArray {
        val t = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { t.write(v shr 8); t.write(v) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        fun entry(tag: Int, type: Int, count: Int, value: Int, short: Boolean = false) { u16(tag); u16(type); u32(count); if (short) { u16(value); u16(0) } else u32(value) }
        t.write("MM".toByteArray()); u16(42); u32(8)
        // IFD0 at 8 (30 bytes) → GPS IFD at 38 (30) → GPS data at 68 (24 + marker) → IFD1 after it.
        val gpsData = 68
        val ifd1 = gpsData + 24 + GPS_MARKER.size
        val thumbAt = ifd1 + 30
        u16(2); entry(0x0112, 3, 1, orientation, short = true); entry(0x8825, 4, 1, 38); u32(ifd1)
        u16(2); entry(0x0001, 2, 2, 'N'.code shl 24); entry(0x0002, 5, 3, gpsData); u32(0)
        u32(6); u32(1); u32(55); u32(1); u32(1234); u32(100); t.write(GPS_MARKER)
        u16(2); entry(0x0201, 4, 1, thumbAt); entry(0x0202, 4, 1, thumbnail.size); u32(0)
        t.write(thumbnail)
        return "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + t.toByteArray()
    }

    /** Inserts EXIF (orientation, GPS, thumbnail), XMP, ICC, MPF, IPTC and a comment after SOI. */
    fun withJpegMetadata(jpeg: ByteArray, orientation: Int, thumbnail: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())): ByteArray =
        jpeg.copyOfRange(0, 2) +
            seg(0xE1, exif(orientation, thumbnail)) + seg(0xE1, XMP) + seg(0xE2, ICC) + seg(0xE2, MPF) +
            seg(0xED, "Photoshop 3.0\u0000IPTC".toByteArray()) + seg(0xFE, "camera comment".toByteArray()) +
            jpeg.copyOfRange(2, jpeg.size)

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val c = java.util.zip.CRC32().apply { update(type.toByteArray()); update(data) }.value
        return java.nio.ByteBuffer.allocate(12 + data.size).putInt(data.size).put(type.toByteArray()).put(data).putInt(c.toInt()).array()
    }

    /** Inserts iCCP, eXIf, tEXt, iTXt after IHDR. */
    fun withPngMetadata(png: ByteArray): ByteArray {
        val ihdrEnd = 8 + 12 + 13
        return png.copyOfRange(0, ihdrEnd) +
            chunk("iCCP", "Display P3\u0000\u0000xx".toByteArray(Charsets.ISO_8859_1)) + chunk("eXIf", exif(1, ByteArray(0)).copyOfRange(6, 6 + 60)) +
            chunk("tEXt", "Comment\u0000GPS".toByteArray(Charsets.ISO_8859_1)) + chunk("iTXt", XMP) +
            png.copyOfRange(ihdrEnd, png.size)
    }

    fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
