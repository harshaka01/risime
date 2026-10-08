package lk.codegen.risime.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * [BitmapOps] on Android (android R2): ImageDecoder on API 28+ (applies EXIF orientation, decodes
 * HEIF), BitmapFactory on 26–27; always software allocation and an sRGB target; every bitmap that
 * gets encoded is redrawn into a fresh sRGB ARGB_8888 bitmap, which also drops an Ultra HDR gain map.
 */
object AndroidBitmapOps : BitmapOps<Bitmap> {
    private val srgb: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)

    override fun decode(bytes: ByteArray, minSide: Int): BitmapOps.Decoded<Bitmap>? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(srgb)
                val (w, h) = info.size.width to info.size.height
                val longest = maxOf(w, h)
                if (longest > minSide) {
                    val s = minSide.toDouble() / longest
                    decoder.setTargetSize(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()))
                }
            }
            BitmapOps.Decoded(bmp, orientationApplied = true)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= minSide) sample *= 2
            val o = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = srgb
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)?.let { BitmapOps.Decoded(it, orientationApplied = false) }
        }
    }.getOrNull()

    override fun width(b: Bitmap) = b.width

    override fun height(b: Bitmap) = b.height

    override fun transform(b: Bitmap, orientation: Int, maxSide: Int): Bitmap {
        val swap = orientation in 5..8
        val ow = if (swap) b.height else b.width
        val oh = if (swap) b.width else b.height
        val s = minOf(1.0, maxSide.toDouble() / maxOf(ow, oh))
        val tw = maxOf(1, Math.round(ow * s).toInt())
        val th = maxOf(1, Math.round(oh * s).toInt())
        val m = Matrix()
        // EXIF orientation as a transform of the source, around the origin, then translated back.
        when (orientation) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(-90f)
        }
        val r = android.graphics.RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        m.mapRect(r)
        m.postTranslate(-r.left, -r.top)
        m.postScale(tw / r.width(), th / r.height())
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888, true, srgb)
        Canvas(out).drawBitmap(b, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        if (Build.VERSION.SDK_INT >= 34 && out.hasGainmap()) out.gainmap = null
        return out
    }

    override fun hasTransparency(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        val row = IntArray(b.width)
        for (y in 0 until b.height) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            if (row.any { (it ushr 24) != 0xFF }) return true
        }
        return false
    }

    override fun flattenOnWhite(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888, false, srgb)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    override fun encodeJpeg(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    override fun cropSquare(b: Bitmap, x: Int, y: Int, side: Int): Bitmap {
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888, false, srgb)
        Canvas(out).drawBitmap(b, android.graphics.Rect(x, y, x + side, y + side), android.graphics.Rect(0, 0, side, side), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    override fun encodePng(b: Bitmap): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    override fun recycle(b: Bitmap) = b.recycle()

    /**
     * Receiving (§14.7 Receiving 4): decodes verified plaintext that passed [DisplayCheck], with
     * BitmapFactory only (never a HEIF/GIF/video path), software ARGB_8888 in sRGB, sampled to the slot.
     */
    fun decodeForDisplay(bytes: ByteArray, sampleSize: Int): Bitmap? = runCatching {
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = srgb
                inMutable = false
            },
        )
    }.getOrNull()
}
