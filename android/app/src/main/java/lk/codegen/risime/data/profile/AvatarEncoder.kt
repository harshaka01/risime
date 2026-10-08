package lk.codegen.risime.data.profile

import lk.codegen.risime.data.media.BitmapOps
import lk.codegen.risime.data.media.ImageBytes
import lk.codegen.risime.data.media.ImageRejected
import lk.codegen.risime.data.media.MediaFormat

/**
 * The square the user chose on the crop screen, in fractions of the oriented image: [left] of its
 * width, [top] of its height, [side] of its shorter side (so it is scale-independent: the preview
 * and the full decode give the same square).
 */
data class CropSquare(val left: Float, val top: Float, val side: Float) {
    companion object {
        /** The centred largest square (the crop screen's start). */
        fun centred(w: Int, h: Int): CropSquare {
            val m = minOf(w, h).toFloat()
            return CropSquare((w - m) / 2f / w, (h - m) / 2f / h, 1f)
        }
    }
}

/** A cropped, re-encoded photo: JPEG, no metadata segments, [side]×[side]. */
class AvatarJpeg(val bytes: ByteArray, val side: Int)

/**
 * §18.6 / §14.4 group icons: decode, orient, crop the square the user saw, scale to 512 px (or the
 * crop's side if smaller), re-encode as a software sRGB JPEG with no metadata at q85, stepped down
 * (q75, q65) until the ciphertext fits 512 KiB. A crop under 64 px: "Choose a larger photo".
 */
class AvatarEncoder<B>(private val ops: BitmapOps<B>) {
    fun encode(source: ByteArray, crop: CropSquare): AvatarJpeg {
        if (source.isEmpty() || source.size > MAX_SOURCE_BYTES) throw ImageRejected("This photo is too large")
        val decoded = ops.decode(source, DECODE_SIDE) ?: throw ImageRejected("This photo format isn't supported on this phone")
        val orientation = if (decoded.orientationApplied) 1 else ImageBytes.exifOrientation(source)
        val oriented = ops.transform(decoded.bitmap, orientation, DECODE_SIDE)
        ops.recycle(decoded.bitmap)
        try {
            val w = ops.width(oriented)
            val h = ops.height(oriented)
            val side = (crop.side.coerceIn(0f, 1f) * minOf(w, h)).toInt().coerceIn(1, minOf(w, h))
            if (side < PhotoRef.MIN_SIDE) throw ImageRejected(TOO_SMALL)
            val x = (crop.left * w).toInt().coerceIn(0, w - side)
            val y = (crop.top * h).toInt().coerceIn(0, h - side)
            val square = ops.cropSquare(oriented, x, y, side)
            val out = if (side > PhotoRef.MAX_SIDE) ops.transform(square, 1, PhotoRef.MAX_SIDE).also { ops.recycle(square) } else square
            val flat = if (ops.hasTransparency(out)) ops.flattenOnWhite(out).also { ops.recycle(out) } else out
            try {
                val outSide = ops.width(flat)
                var bytes = ByteArray(0)
                for (q in QUALITIES) {
                    bytes = ImageBytes.stripMetadata(ops.encodeJpeg(flat, q))
                    if (fits(bytes.size.toLong())) break
                }
                if (!fits(bytes.size.toLong())) throw ImageRejected("This photo is too large")
                check(ImageBytes.findMetadata(bytes).isEmpty()) { "metadata left after stripping" }
                return AvatarJpeg(bytes, outSide)
            } finally {
                ops.recycle(flat)
            }
        } finally {
            ops.recycle(oriented)
        }
    }

    private fun fits(plain: Long): Boolean = (MediaFormat.cipherSize(plain) ?: Long.MAX_VALUE) <= PhotoRef.MAX_CIPHER

    companion object {
        const val DECODE_SIDE = 2048
        const val MAX_SOURCE_BYTES = 64 * 1024 * 1024
        val QUALITIES = listOf(85, 75, 65)
        const val TOO_SMALL = "Choose a larger photo"
    }
}
