package lk.codegen.risime.ui.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.34 §33.10 / §33.13: the app's own provider (`exported=false`, `grantUriPermissions=true`) for
 * Share and Open-with. A Share item is a pipe ([ParcelFileDescriptor.createReliablePipe]) fed off the
 * main thread from decrypted bytes, so **plaintext never touches the disk while sharing**; an Open-with
 * item is the short-lived decrypted file in `noBackupFilesDir/open/<uuid>/` (viewers need to seek).
 * URIs live 10 minutes or until the process dies; every write mode is refused.
 */
class ShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = ShareRegistry.get(uri)?.mime

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val item = ShareRegistry.get(uri) ?: return null
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map<String, Any?> { if (it == OpenableColumns.DISPLAY_NAME) item.displayName else item.size }.toTypedArray())
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read only")
        val item = ShareRegistry.get(uri) ?: throw java.io.FileNotFoundException("expired")
        item.file?.let { return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        val produce = item.producer ?: throw java.io.FileNotFoundException("nothing to read")
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        Thread({
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                try {
                    produce(out)
                } catch (e: Exception) {
                    runCatching { write.closeWithError(e.message ?: "failed") }
                }
            }
        }, "risime-share").start()
        return read
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("read only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("read only")

    companion object {
        fun authority(packageName: String) = "$packageName.share"
    }
}

/** One shared or opened item. [producer] writes the plaintext into the pipe; [file] is an Open-with temp. */
class ShareItem(
    val displayName: String,
    val mime: String,
    val size: Long,
    val producer: ((java.io.OutputStream) -> Unit)? = null,
    val file: File? = null,
    val expiresAt: Long,
)

/** The in-memory registry (process lifetime only). */
object ShareRegistry {
    const val TTL_MS = 10 * 60_000L
    private val items = ConcurrentHashMap<String, ShareItem>()
    var clock: () -> Long = System::currentTimeMillis

    fun register(packageName: String, item: ShareItem): Uri {
        val token = java.util.UUID.randomUUID().toString()
        items[token] = item
        return Uri.Builder().scheme("content").authority(ShareProvider.authority(packageName))
            .appendPath(token).appendPath(item.displayName).build()
    }

    fun get(uri: Uri): ShareItem? {
        val token = uri.pathSegments.firstOrNull() ?: return null
        val item = items[token] ?: return null
        if (clock() > item.expiresAt) {
            items.remove(token)
            return null
        }
        return item
    }

    /** Expired items go (their URIs are revoked by the caller's timer). */
    fun prune() {
        val now = clock()
        items.entries.removeAll { it.value.expiresAt < now }
    }

    fun clear() = items.clear()
}
