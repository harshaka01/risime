package lk.codegen.risime.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.media.DownloadOutcome
import lk.codegen.risime.data.media.FileEnvelope
import lk.codegen.risime.data.media.FileMeta
import lk.codegen.risime.ui.share.ShareItem
import lk.codegen.risime.ui.share.ShareRegistry
import lk.codegen.risime.ui.theme.Spacing
import java.io.File

/** v1.34 §33.13 file UI strings. */
object FileStrings {
    const val OPEN = "Open"
    const val SAVE = "Save to Downloads"
    const val APK_WARNING = "This is an app installer. Only install apps you trust."
    const val GONE = "This file is no longer available"
    const val CORRUPT = "Couldn't open this file"
    const val UPDATE_TO_OPEN = "Update RisiMe to open this file"
    const val SAVED = "Saved to Download/RisiMe"
    const val NO_APP = "No app on this phone can open this file"
    const val DOWNLOADING = "Downloading…"
    const val TAP_TO_DOWNLOAD = "Tap to download"
    const val SENDING = "Sending file…"
    fun pages(n: Int) = if (n == 1) "1 page" else "$n pages"
}

/** A human size from `plain_size` ("196 KB", "2.4 MB"). */
fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** The card's state line, or null when the file is on the phone. */
fun fileStatusText(m: MessageEntity, row: MediaEntity?): String? {
    val meta = FileMeta.decode(m.systemJson)
    if (meta?.parts == true) return FileStrings.UPDATE_TO_OPEN
    return when (row?.state) {
        null -> FileStrings.GONE
        MediaState.REENCRYPT.name, MediaState.ENCRYPTED.name, MediaState.UPLOADING.name -> FileStrings.SENDING
        MediaState.FAILED.name -> if (row.failReason == lk.codegen.risime.data.messaging.ForwardMedia.SOURCE_GONE) FileStrings.GONE else "Couldn't send file"
        MediaState.NONE.name -> FileStrings.TAP_TO_DOWNLOAD
        MediaState.DOWNLOADING.name -> FileStrings.DOWNLOADING
        MediaState.GONE.name -> FileStrings.GONE
        MediaState.CORRUPT.name -> FileStrings.CORRUPT
        else -> null
    }
}

/**
 * §33.13 the file bubble's card: a type icon (PDF, DOC, XLS, ZIP, generic), the name, a human size, the
 * page count and the thumbnail if present. Received bytes are never rendered here: only `thumb`.
 */
@Composable
fun FileCard(c: AppContainer, m: MessageEntity, row: MediaEntity?) {
    val meta = FileMeta.decode(m.systemJson) ?: return
    val name = FileEnvelope.displayName(meta.name)
    val size by produceState<Long?>(null, m.clientMsgId, row?.state) { value = c.mediaPlainSize(m.clientMsgId) }
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m.clientMsgId, row?.sealedThumb?.size) {
        value = withContext(Dispatchers.Default) {
            c.mediaThumb(m.clientMsgId)?.let { t -> runCatching { android.graphics.BitmapFactory.decodeByteArray(t.data, 0, t.data.size)?.asImageBitmap() }.getOrNull() }
        }
    }
    Row(Modifier.widthIn(min = 200.dp).padding(vertical = Spacing.xxs).testTag("file_card"), verticalAlignment = Alignment.CenterVertically) {
        val t = thumb
        if (t != null) {
            Image(t, null, Modifier.size(width = 44.dp, height = 56.dp))
        } else {
            Box(Modifier.size(width = 44.dp, height = 56.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                Text(FileEnvelope.iconKind(meta.mime, meta.name), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val info = listOfNotNull(size?.let(::humanSize), meta.pages?.let(FileStrings::pages)).joinToString(" · ")
            if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.labelSmall)
            fileStatusText(m, row)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

/**
 * §33.13 open and save. Open decrypts to `noBackupFilesDir/open/<random uuid>/<display name>` and hands
 * it to another app (`ACTION_VIEW`, a read grant through [lk.codegen.risime.ui.share.ShareProvider], the
 * sanitised mime). The file goes when the user returns to the app, after 1 hour and on the next start.
 * An APK has no Open, only Save with a warning. Save: MediaStore `Download/RisiMe` (API 29+).
 */
class FileActions(private val c: AppContainer, private val scope: CoroutineScope) {
    val toast = MutableStateFlow<String?>(null)

    /** The APK warning before a Save (null: none). */
    val apkWarning = MutableStateFlow<MessageEntity?>(null)

    /** API 26–28: the save that waits for the system Save dialog's Uri. */
    val pendingSave = MutableStateFlow<MessageEntity?>(null)

    fun tap(context: Context, m: MessageEntity, row: MediaEntity?) {
        val meta = FileMeta.decode(m.systemJson) ?: return
        if (meta.parts) { toast.value = FileStrings.UPDATE_TO_OPEN; return }
        if (FileEnvelope.isApk(meta.mime, meta.name)) { apkWarning.value = m; return }
        scope.launch { open(context, m, meta, row) }
    }

    private suspend fun ensureLocal(m: MessageEntity, row: MediaEntity?): Boolean {
        if (row?.fileName != null) return true
        val o = c.downloadImage(m.clientMsgId)
        if (o == DownloadOutcome.Cached) return true
        toast.value = if (o == DownloadOutcome.Gone) FileStrings.GONE else FileStrings.CORRUPT
        return false
    }

    private suspend fun open(context: Context, m: MessageEntity, meta: FileMeta, row: MediaEntity?) {
        if (!ensureLocal(m, row)) return
        val dir = File(File(context.noBackupFilesDir, OPEN_DIR), java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val name = FileEnvelope.displayName(meta.name)
        val f = File(dir, name)
        val ok = withContext(Dispatchers.IO) { c.decryptMediaTo(m.clientMsgId, f) }
        if (!ok) {
            dir.deleteRecursively()
            // A verify failure: one re-download, then give up.
            c.images.failedToOpen(m.clientMsgId)
            toast.value = FileStrings.CORRUPT
            return
        }
        val mime = FileEnvelope.sanitizeMime(meta.mime)
        val uri = ShareRegistry.register(context.packageName, ShareItem(name, mime, f.length(), file = f, expiresAt = ShareRegistry.clock() + ShareRegistry.TTL_MS))
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast.value = FileStrings.NO_APP
        }
        lk.codegen.risime.ui.share.ShareIntents.scheduleRevoke(context, listOf(uri))
    }

    /** Save to `Download/RisiMe` (API 29+, no permission); 26–28 go through the system Save dialog. */
    fun save(context: Context, m: MessageEntity) {
        if (Build.VERSION.SDK_INT < 29) { pendingSave.value = m; return }
        scope.launch {
            val meta = FileMeta.decode(m.systemJson) ?: return@launch
            if (!ensureLocal(m, c.db.media().get(m.clientMsgId))) return@launch
            val ok = withContext(Dispatchers.IO) { saveToDownloads(context, m, meta) }
            toast.value = if (ok) FileStrings.SAVED else FileStrings.CORRUPT
        }
    }

    fun saveToUri(context: Context, m: MessageEntity, uri: Uri) {
        pendingSave.value = null
        scope.launch {
            if (!ensureLocal(m, c.db.media().get(m.clientMsgId))) return@launch
            val bytes = (c.images.decrypt(m.clientMsgId) as? lk.codegen.risime.data.media.Decrypted.Ok)?.bytes
            val ok = bytes != null && withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false) }
            bytes?.fill(0)
            toast.value = if (ok) FileStrings.SAVED else FileStrings.CORRUPT
        }
    }

    private suspend fun saveToDownloads(context: Context, m: MessageEntity, meta: FileMeta): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val bytes = (c.images.decrypt(m.clientMsgId) as? lk.codegen.risime.data.media.Decrypted.Ok)?.bytes ?: return false
        return saveBytesToDownloads(context, bytes, FileEnvelope.displayName(meta.name), FileEnvelope.sanitizeMime(meta.mime)).also { bytes.fill(0) }
    }

    companion object {
        const val OPEN_DIR = "open"
        const val OPEN_TTL_MS = 60 * 60_000L

        /** Opened-file temporaries: all of them (return to the app, next start) or those older than an hour. */
        fun cleanupOpened(context: Context, olderThanMs: Long? = null) {
            val root = File(context.noBackupFilesDir, OPEN_DIR)
            val now = System.currentTimeMillis()
            root.listFiles()?.forEach { d -> if (olderThanMs == null || now - d.lastModified() > olderThanMs) d.deleteRecursively() }
        }

        /** MediaStore `Download/RisiMe` (API 29+). */
        fun saveBytesToDownloads(context: Context, bytes: ByteArray, name: String, mime: String): Boolean {
            if (Build.VERSION.SDK_INT < 29) return false
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/RisiMe")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            return try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw java.io.IOException("no stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                true
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        }
    }
}
