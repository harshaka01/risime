package lk.codegen.risime.ui.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * §14.7 Receiving 8 / android R7: the only way plaintext leaves the app, on a user action. The
 * bytes are a fresh re-encode of the decrypted pixels (no metadata). API 29+: MediaStore into
 * `Pictures/RisiMe` (IS_PENDING while writing); API 26–28: the system Save dialog
 * (`ACTION_CREATE_DOCUMENT`) gives the Uri. No storage permission on any version; DATE_TAKEN is
 * not set (the capture time was stripped on purpose).
 */
object GallerySaver {
    fun displayName(now: LocalDateTime = LocalDateTime.now()): String = "RisiMe_" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".jpg"

    /** True when [saveToMediaStore] works on this device; otherwise use the Save dialog. */
    val mediaStoreWithoutPermission: Boolean get() = Build.VERSION.SDK_INT >= 29

    fun saveToMediaStore(context: Context, jpeg: ByteArray, name: String = displayName()): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RisiMe")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: throw java.io.IOException("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** API 26–28: writes into the Uri the user picked in the system Save dialog. */
    fun saveToUri(context: Context, uri: Uri, jpeg: ByteArray): Boolean =
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) } != null }.getOrDefault(false)
}
