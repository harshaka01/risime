package lk.codegen.risime.data.media

/**
 * The platform's bitmap operations ([AndroidBitmapOps] on devices, an AWT fake in JVM tests).
 * Every bitmap the pipeline encodes comes out of [transform]: a fresh software, 8-bit sRGB
 * bitmap with no gain map (android R2).
 */
interface BitmapOps<B> {
    /** A decoded source, and whether the decoder already applied the EXIF orientation (ImageDecoder does). */
    class Decoded<B>(val bitmap: B, val orientationApplied: Boolean)

    /** Decodes [bytes] to a software bitmap whose longest side is at least [minSide] where possible (sampled, not exact). Null = can't decode. */
    fun decode(bytes: ByteArray, minSide: Int): Decoded<B>?

    fun width(b: B): Int

    fun height(b: B): Int

    /** EXIF [orientation] (1–8) applied to the pixels, scaled so the longest side is ≤ [maxSide] (never upscaled), into a fresh sRGB ARGB_8888 software bitmap. */
    fun transform(b: B, orientation: Int, maxSide: Int): B

    /** Any pixel with alpha < 255. */
    fun hasTransparency(b: B): Boolean

    fun flattenOnWhite(b: B): B

    fun encodeJpeg(b: B, quality: Int): ByteArray

    /** §18.6: the [side]×[side] square at ([x], [y]) of [b], into a fresh sRGB ARGB_8888 software bitmap. */
    fun cropSquare(b: B, x: Int, y: Int, side: Int): B

    fun encodePng(b: B): ByteArray

    fun recycle(b: B)
}

/** A re-encoded image ready to encrypt: no metadata, oriented pixels, longest side ≤ 2048. */
class PreparedImage(val bytes: ByteArray, val mime: String, val w: Int, val h: Int, val thumb: ImageThumb?)

class ImageRejected(message: String) : Exception(message)

/**
 * §14.7 Sending 2–3: decode, orient, bound to 2048 px, re-encode (JPEG q85 → q75 → q65 under
 * 6 MiB; PNG only with transparency and ≤ 4 MiB, else flattened onto white), strip every metadata
 * container, and make the thumbnail from the same bitmap (q60 → 50 → 40, then 96 px, then 64 px,
 * ≤ 4096 bytes). The original bytes never leave the device.
 */
class ImagePipeline<B>(private val ops: BitmapOps<B>) {
    fun prepare(source: ByteArray): PreparedImage {
        if (source.isEmpty()) throw ImageRejected("empty")
        val decoded = ops.decode(source, MAX_SIDE) ?: throw ImageRejected("This photo format isn't supported on this phone")
        val orientation = if (decoded.orientationApplied) 1 else ImageBytes.exifOrientation(source)
        var img = ops.transform(decoded.bitmap, orientation, MAX_SIDE)
        ops.recycle(decoded.bitmap)
        try {
            var bytes: ByteArray? = null
            var mime = ImageEnvelope.MIME_JPEG
            if (ops.hasTransparency(img)) {
                val png = ImageBytes.stripMetadata(ops.encodePng(img))
                if (png.size <= MAX_PNG_BYTES) {
                    bytes = png
                    mime = ImageEnvelope.MIME_PNG
                } else {
                    val flat = ops.flattenOnWhite(img)
                    ops.recycle(img)
                    img = flat
                }
            }
            if (bytes == null) bytes = encodeJpegUnder(img, JPEG_QUALITIES, MAX_JPEG_BYTES)
            check(ImageBytes.findMetadata(bytes).isEmpty()) { "metadata left after stripping" }
            val thumb = thumbnail(img)
            return PreparedImage(bytes, mime, ops.width(img), ops.height(img), thumb)
        } finally {
            ops.recycle(img)
        }
    }

    /** Save to gallery (§14.7 Receiving 8): re-encode decrypted pixels, never the sender's bytes. */
    fun reencodeForSave(decrypted: ByteArray): ByteArray {
        val decoded = ops.decode(decrypted, MAX_SIDE) ?: throw ImageRejected("Couldn't open this photo")
        val img0 = ops.transform(decoded.bitmap, 1, MAX_SIDE)
        ops.recycle(decoded.bitmap)
        val img = if (ops.hasTransparency(img0)) ops.flattenOnWhite(img0).also { ops.recycle(img0) } else img0
        try {
            return encodeJpegUnder(img, JPEG_QUALITIES, MAX_JPEG_BYTES)
        } finally {
            ops.recycle(img)
        }
    }

    private fun encodeJpegUnder(img: B, qualities: List<Int>, max: Int): ByteArray {
        var out = ByteArray(0)
        for (q in qualities) {
            out = ImageBytes.stripMetadata(ops.encodeJpeg(img, q))
            if (out.size <= max) break
        }
        return out
    }

    /** The thumbnail is a downscale of the re-encoded bitmap (never an embedded or MediaStore thumbnail). */
    internal fun thumbnail(img: B): ImageThumb? {
        val flat = if (ops.hasTransparency(img)) ops.flattenOnWhite(img) else null
        val base = flat ?: img
        try {
            for (side in THUMB_SIDES) {
                val t = ops.transform(base, 1, side)
                try {
                    for (q in if (side == THUMB_SIDES.first()) THUMB_QUALITIES else listOf(THUMB_QUALITIES.last())) {
                        val bytes = ImageBytes.stripMetadata(ops.encodeJpeg(t, q))
                        if (bytes.size <= ImageEnvelope.MAX_THUMB_BYTES) return ImageThumb(ImageEnvelope.MIME_JPEG, ops.width(t), ops.height(t), bytes)
                    }
                } finally {
                    ops.recycle(t)
                }
            }
            return null
        } finally {
            flat?.let(ops::recycle)
        }
    }

    companion object {
        const val MAX_SIDE = 2048
        const val MAX_JPEG_BYTES = 6 * 1024 * 1024
        const val MAX_PNG_BYTES = 4 * 1024 * 1024
        val JPEG_QUALITIES = listOf(85, 75, 65)
        val THUMB_QUALITIES = listOf(60, 50, 40)
        val THUMB_SIDES = listOf(128, 96, 64)

        /** Picker input cap: anything larger is refused before decoding. */
        const val MAX_SOURCE_BYTES = 64 * 1024 * 1024
    }
}

/**
 * §14.7 Receiving 4: checks on untrusted, already-verified plaintext before a decoder sees it.
 * The format is sniffed from the bytes (JPEG, PNG, WebP only) and must match [mime]; the header
 * size must be ≤ [maxSide] and match the envelope's `w`×`h` (±1 for rounding). Returns the
 * power-of-two sample size that decodes to about [targetSide] (decode to the slot size).
 */
object DisplayCheck {
    sealed interface Result {
        data class Ok(val sampleSize: Int, val w: Int, val h: Int) : Result

        data class Refused(val why: String) : Result
    }

    fun check(bytes: ByteArray, mime: String, w: Int, h: Int, maxSide: Int, targetSide: Int): Result {
        val fmt = ImageBytes.sniff(bytes)
        if (fmt.mime == null) return Result.Refused("format ${fmt.name}")
        if (fmt.mime != mime) return Result.Refused("format ${fmt.mime} != $mime")
        val (rw, rh) = ImageBytes.dimensions(bytes) ?: return Result.Refused("no header size")
        if (rw > maxSide || rh > maxSide) return Result.Refused("too large ${rw}x$rh")
        if (kotlin.math.abs(rw - w) > 1 || kotlin.math.abs(rh - h) > 1) return Result.Refused("size ${rw}x$rh != ${w}x$h")
        var sample = 1
        while (maxOf(rw, rh) / (sample * 2) >= targetSide) sample *= 2
        return Result.Ok(sample, rw, rh)
    }
}
