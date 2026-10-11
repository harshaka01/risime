package lk.codegen.risime.data.messaging

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lk.codegen.risime.data.db.MediaDao
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.db.MessageDao
import lk.codegen.risime.data.media.ImageEnc
import lk.codegen.risime.data.media.ImageThumb
import lk.codegen.risime.data.media.MediaCrypto
import lk.codegen.risime.data.media.MediaFiles
import lk.codegen.risime.data.media.MediaFormat
import lk.codegen.risime.data.media.MediaSealer
import lk.codegen.risime.data.media.PreparedImage
import java.io.File
import java.util.Base64

/**
 * v1.34 §33.5: a forwarded photo or file is **always** a fresh upload with a fresh key per target, so
 * the server learns no forward graph. One job per target row (state REENCRYPT, [MediaEntity.forwardFrom]):
 * 1. the source's plaintext: decrypt its cached, verified ciphertext (or download and verify first while
 *    it is fetchable); plaintext only in memory or in `media/tmp`, deleted after the job;
 * 2. an image is re-encoded as a new photo (fresh thumbnail); a file is encrypted byte for byte (its
 *    thumbnail copied);
 * 3. a new encrypt call (a fresh key, never the source's key or ciphertext);
 * 4. the row becomes ENCRYPTED and the normal upload job takes it (purpose=media into the target).
 */
class ForwardMedia(
    private val media: MediaDao,
    private val messages: MessageDao,
    private val sealer: MediaSealer,
    private val files: MediaFiles,
    private val crypto: () -> MediaCrypto?,
    /** Download (and verify) the source first; true when its ciphertext is now cached. */
    private val download: suspend (String) -> Boolean,
    /** §14.7 Sending 2–3 for a forwarded image (re-encode, fresh thumbnail). */
    private val reencodeImage: (ByteArray) -> PreparedImage,
    private val enqueueUpload: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    sealed interface Outcome {
        data object Encrypted : Outcome

        data class Failed(val reason: String) : Outcome

        data object Skipped : Outcome
    }

    private val lock = Mutex()

    /** Every REENCRYPT row (process start: a forward interrupted by a crash or a kill resumes). */
    suspend fun resumeAll() {
        for (r in media.reencryptJobs()) run(r.clientMsgId)
    }

    suspend fun run(clientMsgId: String): Outcome = lock.withLock { runLocked(clientMsgId) }

    private suspend fun runLocked(clientMsgId: String): Outcome {
        val row = media.get(clientMsgId)?.takeIf { it.state == MediaState.REENCRYPT.name } ?: return Outcome.Skipped
        val core = crypto() ?: return fail(row, SOURCE_GONE)
        val srcId = row.forwardFrom ?: return fail(row, SOURCE_GONE)
        var src = media.get(srcId) ?: return fail(row, SOURCE_GONE)
        if (src.fileName == null || !files.file(src.fileName!!).isFile) {
            val fetchable = src.blobId != null && (src.expiresAtEst ?: Long.MAX_VALUE) > clock() && src.state in setOf(MediaState.NONE.name, MediaState.DOWNLOADING.name)
            if (!fetchable || !download(srcId)) return fail(row, SOURCE_GONE)
            src = media.get(srcId) ?: return fail(row, SOURCE_GONE)
        }
        val srcFile = src.fileName?.let(files::file)?.takeIf { it.isFile } ?: return fail(row, SOURCE_GONE)
        val enc = runCatching { sealer.openEnc(srcId, src.sealedEnc) }.getOrElse { return fail(row, SOURCE_GONE) }
        val sha = Base64.getDecoder().decode(src.blobSha256)
        val name = files.nameFor(clientMsgId)
        val dst = files.file(name)
        val plain = File(files.tmp, "$name.plain")
        try {
            val isImage = row.w > 0 || src.w > 0
            var thumb: ImageThumb? = null
            var mime = src.mime
            var w = 0
            var h = 0
            if (isImage) {
                val bytes = core.decryptFile(srcFile, enc.key, enc.alg, enc.plainSize, src.blobSize, sha)
                val p = runCatching { reencodeImage(bytes) }.getOrElse { return fail(row, "reencode") }
                bytes.fill(0)
                plain.writeBytes(p.bytes)
                thumb = p.thumb
                mime = p.mime
                w = p.w
                h = p.h
            } else {
                core.decryptFileToFile(srcFile, plain, enc.key, enc.alg, enc.plainSize, src.blobSize, sha)
                thumb = src.sealedThumb?.let { runCatching { sealer.openThumb(srcId, it) }.getOrNull() }
            }
            val sealed = core.encryptFile(plain, dst)
            if (sealed.cipherSize > MediaFormat.MAX_MEDIA_CIPHER) {
                dst.delete()
                return fail(row, lk.codegen.risime.net.AuthErrors.TOO_LARGE)
            }
            val cur = media.get(clientMsgId) ?: return Outcome.Skipped.also { dst.delete() } // deleted meanwhile
            media.update(
                cur.copy(
                    state = MediaState.ENCRYPTED.name, blobSize = sealed.cipherSize, blobSha256 = Base64.getEncoder().encodeToString(sealed.sha256),
                    sealedEnc = sealer.sealEnc(clientMsgId, ImageEnc(sealed.alg, sealed.key, sealed.plainSize)),
                    sealedThumb = thumb?.let { sealer.sealThumb(clientMsgId, it) }, mime = mime, w = w, h = h, fileName = name,
                    forwardFrom = null, attempts = 0, nextAt = 0,
                ),
            )
            sealed.key.fill(0)
            enqueueUpload(clientMsgId)
            return Outcome.Encrypted
        } catch (e: Exception) {
            log("forward $clientMsgId: ${e.javaClass.simpleName}")
            dst.delete()
            return fail(row, SOURCE_GONE)
        } finally {
            plain.delete()
        }
    }

    private suspend fun fail(row: MediaEntity, reason: String): Outcome {
        media.update(row.copy(state = MediaState.FAILED.name, failReason = reason))
        messages.failPending(row.clientMsgId, reason)
        return Outcome.Failed(reason)
    }

    companion object {
        /** The source is past its horizon, gone (404) or no longer on the phone. */
        const val SOURCE_GONE = "source_gone"
    }
}
