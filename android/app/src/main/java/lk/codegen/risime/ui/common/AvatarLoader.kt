package lk.codegen.risime.ui.common

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lk.codegen.risime.data.media.AndroidBitmapOps
import lk.codegen.risime.data.media.DisplayCheck
import lk.codegen.risime.data.profile.PhotoRef
import lk.codegen.risime.data.profile.ProfilePhotos

/**
 * §18.2 decode (crypto K8): only verified plaintext, the format sniffed from the bytes (JPEG for a
 * profile photo; JPEG/PNG/WebP for a group icon), header size equal to `w`/`h` and ≤ 512 before any
 * pixel allocation, software decode off the main thread with a timeout, to the display size.
 * Bitmaps live in memory only; a failure means initials and is logged without bytes.
 */
class AvatarLoader(private val photos: ProfilePhotos) : AvatarSource {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.Default.limitedParallelism(2)

    private val cache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** subject → the blob currently shown (so a newer photo replaces the cached one). */
    private val current = java.util.concurrent.ConcurrentHashMap<String, String>()

    override val revision: StateFlow<Long> get() = photos.revision

    override fun cached(key: String, sizePx: Int): ImageBitmap? {
        val blob = current[key.lowercase()] ?: return null
        return cache.get(k(key, blob, sizePx))?.asImageBitmap()
    }

    private fun k(key: String, blob: String, px: Int) = "${key.lowercase()}|$blob@$px"

    override suspend fun load(key: String, sizePx: Int): ImageBitmap? {
        photos.shownBlob(key)?.let { blob -> cache.get(k(key, blob, sizePx))?.let { current[key.lowercase()] = blob; return it.asImageBitmap() } }
        val plain = withContext(decodeDispatcher) { photos.plaintext(key) }
        if (plain == null) {
            current.remove(key.lowercase())
            return null
        }
        cache.get(k(key, plain.blobId, sizePx))?.let {
            plain.bytes.fill(0)
            current[key.lowercase()] = plain.blobId
            return it.asImageBitmap()
        }
        val ok = DisplayCheck.check(plain.bytes, plain.mime, plain.w, plain.h, PhotoRef.MAX_SIDE, sizePx.coerceAtLeast(32)) as? DisplayCheck.Result.Ok
        val bmp = ok?.let {
            withTimeoutOrNull(DECODE_TIMEOUT_MS) { runInterruptible(decodeDispatcher) { AndroidBitmapOps.decodeForDisplay(plain.bytes, it.sampleSize) } }
        }
        plain.bytes.fill(0)
        if (bmp == null) {
            photos.failed(key, plain.blobId)
            current.remove(key.lowercase())
            return null
        }
        cache.put(k(key, plain.blobId, sizePx), bmp)
        current[key.lowercase()] = plain.blobId
        return bmp.asImageBitmap()
    }

    private companion object {
        const val DECODE_TIMEOUT_MS = 5_000L
    }
}
