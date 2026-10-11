package lk.codegen.risime.ui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.media.Decrypted
import lk.codegen.risime.data.media.FileEnvelope
import lk.codegen.risime.data.media.FileMeta
import lk.codegen.risime.data.messaging.CopyFormat
import lk.codegen.risime.data.messaging.SelectionRules

/**
 * v1.34 §33.10 Share: text only → `ACTION_SEND text/plain` (one: its content; more: the §33.3 lines);
 * photos and files → `ACTION_SEND` / `ACTION_SEND_MULTIPLE` (≤ 10) with their mime (`*` when mixed)
 * and any text items in `EXTRA_TEXT`. Each item is a pipe of [ShareProvider]; a photo is re-encoded
 * as for Save. Media not downloaded yet is downloaded (and verified) first.
 */
object ShareIntents {
    sealed interface Result {
        class Ready(val intent: Intent) : Result

        class Refused(val why: String) : Result
    }

    fun stampName(ms: Long): String =
        "RisiMe-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())) + ".jpg"

    suspend fun build(context: Context, c: AppContainer, rows: List<MessageEntity>, me: String, ownName: String, nameOf: (String) -> String?): Result {
        if (rows.isEmpty() || rows.size > SelectionRules.MAX_SHARE) return Result.Refused("Share up to ${SelectionRules.MAX_SHARE} items at a time")
        if (rows.any { !SelectionRules.selectable(it) }) return Result.Refused("Can't be shared")
        val textRows = rows.filter { !it.media }
        val mediaRows = rows.filter { it.media }
        val text = if (textRows.isEmpty()) null else CopyFormat.format(
            textRows, me, ownName, nameOf, java.util.Locale.getDefault(), android.text.format.DateFormat.is24HourFormat(context),
            java.time.ZoneId.systemDefault(), CopyFormat.IcuDates,
        )
        if (mediaRows.isEmpty()) {
            return Result.Ready(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text ?: return Result.Refused("Nothing to share")))
        }
        val uris = mutableListOf<Uri>()
        val mimes = mutableSetOf<String>()
        val expires = ShareRegistry.clock() + ShareRegistry.TTL_MS
        for (m in mediaRows) {
            // Only downloaded and verified media: download first.
            if (c.db.media().get(m.clientMsgId)?.fileName == null) c.downloadImage(m.clientMsgId)
            val item = if (m.image) {
                val plain = (c.images.decrypt(m.clientMsgId) as? Decrypted.Ok)?.bytes ?: return Result.Refused(SelectionRules.goneText(m))
                val jpeg = runCatching { lk.codegen.risime.data.media.ImagePipeline(lk.codegen.risime.data.media.AndroidBitmapOps).reencodeForSave(plain) }.getOrNull()
                    ?: return Result.Refused("Couldn't open this photo")
                plain.fill(0)
                ShareItem(stampName(CopyFormat.timeOf(m)), "image/jpeg", jpeg.size.toLong(), producer = { out -> out.write(jpeg) }, expiresAt = expires)
            } else {
                val meta = FileMeta.decode(m.systemJson) ?: return Result.Refused(SelectionRules.goneText(m))
                val size = c.mediaPlainSize(m.clientMsgId) ?: return Result.Refused(SelectionRules.goneText(m))
                val id = m.clientMsgId
                ShareItem(
                    FileEnvelope.displayName(meta.name), FileEnvelope.sanitizeMime(meta.mime), size,
                    producer = { out ->
                        val bytes = kotlinx.coroutines.runBlocking { (c.images.decrypt(id) as? Decrypted.Ok)?.bytes } ?: throw java.io.IOException("unavailable")
                        try { out.write(bytes) } finally { bytes.fill(0) }
                    },
                    expiresAt = expires,
                )
            }
            mimes += item.mime
            uris += ShareRegistry.register(context.packageName, item)
        }
        val type = mimes.singleOrNull() ?: "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        text?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        val clip = android.content.ClipData.newRawUri(null, uris.first())
        uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scheduleRevoke(context, uris)
        return Result.Ready(intent)
    }

    /** After 10 minutes the grants go (the registry also refuses expired tokens). */
    fun scheduleRevoke(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            uris.forEach { runCatching { app.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            ShareRegistry.prune()
        }, ShareRegistry.TTL_MS)
    }
}
