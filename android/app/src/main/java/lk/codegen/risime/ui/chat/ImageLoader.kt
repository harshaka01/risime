package lk.codegen.risime.ui.chat

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.media.AndroidBitmapOps
import lk.codegen.risime.data.media.Decrypted
import lk.codegen.risime.data.media.DisplayCheck
import lk.codegen.risime.data.media.ImageEnvelope
import lk.codegen.risime.data.media.ImageRepository

/** What a bubble or the viewer gets for an image. */
sealed interface ImageLoad {
    class Ok(val bitmap: Bitmap) : ImageLoad

    /** Not on the device yet (download first). */
    data object Missing : ImageLoad

    /** "Couldn't open this photo". */
    data object Failed : ImageLoad
}

/**
 * Decrypt-on-display with memory caches only (no image library, no disk cache, no temp files):
 * bubble-sized bitmaps in an LRU of 1/8 of the heap, thumbnails in a small one. Decrypt and
 * decode run on at most 2 threads, with a per-image timeout, after [DisplayCheck].
 */
class ImageLoader(private val repo: ImageRepository) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.Default.limitedParallelism(2)

    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024)
    private val bitmaps = object : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val thumbs = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun cachedThumb(id: String): Bitmap? = thumbs.get(id)

    fun cached(id: String, side: Int): Bitmap? = bitmaps.get("$id@$side")

    /** The sealed inline thumbnail, decoded under the same strict checks (≤ 128 px). */
    suspend fun thumb(row: MediaEntity): Bitmap? {
        thumbs.get(row.clientMsgId)?.let { return it }
        val t = repo.thumb(row.clientMsgId, row) ?: return null
        val ok = DisplayCheck.check(t.data, t.mime, t.w, t.h, ImageEnvelope.MAX_THUMB_SIDE, ImageEnvelope.MAX_THUMB_SIDE) as? DisplayCheck.Result.Ok ?: return null
        val bmp = decode(t.data, ok.sampleSize) ?: return null
        thumbs.put(row.clientMsgId, bmp)
        return bmp
    }

    /** The full image decoded to about [side] px (the bubble slot, or 2048 for the viewer). */
    suspend fun image(id: String, side: Int): ImageLoad {
        bitmaps.get("$id@$side")?.let { return ImageLoad.Ok(it) }
        return when (val d = kotlinx.coroutines.withContext(decodeDispatcher) { repo.decrypt(id) }) {
            Decrypted.Missing -> ImageLoad.Missing
            Decrypted.Failed -> {
                repo.failedToOpen(id)
                ImageLoad.Failed
            }
            is Decrypted.Ok -> {
                val ok = DisplayCheck.check(d.bytes, d.mime, d.w, d.h, ImageEnvelope.MAX_SIDE, side) as? DisplayCheck.Result.Ok
                val bmp = ok?.let { decode(d.bytes, it.sampleSize) }
                d.bytes.fill(0)
                if (bmp == null) {
                    repo.failedToOpen(id)
                    ImageLoad.Failed
                } else {
                    bitmaps.put("$id@$side", bmp)
                    ImageLoad.Ok(bmp)
                }
            }
        }
    }

    /** The decrypted bytes for "Save to gallery" (re-encoded by the caller, never written as received). */
    suspend fun decrypted(id: String): ByteArray? =
        (kotlinx.coroutines.withContext(decodeDispatcher) { repo.decrypt(id) } as? Decrypted.Ok)?.bytes

    private suspend fun decode(bytes: ByteArray, sample: Int): Bitmap? = withTimeoutOrNull(DECODE_TIMEOUT_MS) {
        runInterruptible(decodeDispatcher) { AndroidBitmapOps.decodeForDisplay(bytes, sample) }
    }

    fun forget(id: String) {
        thumbs.remove(id)
        bitmaps.snapshot().keys.filter { it.startsWith("$id@") }.forEach { bitmaps.remove(it) }
    }

    companion object {
        const val DECODE_TIMEOUT_MS = 10_000L
    }
}
