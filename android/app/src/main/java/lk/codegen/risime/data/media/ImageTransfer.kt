package lk.codegen.risime.data.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import lk.codegen.risime.data.db.MediaDao
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.db.MessageDao
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * The ciphertext cache (§14.7 Receiving 2): app-private, not backed up, only blobs as served (no
 * plaintext image is ever written here, except the sender's short-lived encrypt input in [tmp]).
 */
class MediaFiles(val dir: File) {
    val tmp: File get() = File(dir, "tmp").also { it.mkdirs() }

    /** The file name for a message (client_msg_ids come from other devices: never used as a path). */
    fun nameFor(clientMsgId: String): String =
        MessageDigest.getInstance("SHA-256").digest(clientMsgId.toByteArray()).joinToString("") { "%02x".format(it) }.take(40) + ".enc"

    fun file(name: String): File = File(dir.also { it.mkdirs() }, name)

    fun part(clientMsgId: String): File = file(nameFor(clientMsgId) + ".part")

    /** Plaintext encrypt inputs and parts of rows that no longer exist (crash, process death). */
    fun cleanup(liveNames: Set<String>) {
        File(dir, "tmp").listFiles()?.forEach { it.delete() }
        dir.listFiles()?.filter { it.isFile }?.forEach { f ->
            val base = f.name.removeSuffix(".part")
            if (base !in liveNames) f.delete()
        }
    }

    fun wipe() {
        dir.deleteRecursively()
    }
}

fun sha256B64(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return Base64.getEncoder().encodeToString(md.digest())
}

fun b64ToHex(b64: String): String = Base64.getDecoder().decode(b64).joinToString("") { "%02x".format(it) }

/** One upload attempt's verdict. */
sealed interface UploadOutcome {
    data class Done(val blobId: String) : UploadOutcome

    /** Network, 5xx, 429 (Retry-After), 507 (hourly), or a digest mismatch (new client_blob_id). */
    data class Retry(val afterMs: Long) : UploadOutcome

    /** A refusal no retry fixes (§14.7 Sending 7): the bubble shows Retry / Delete. */
    data class Failed(val reason: String) : UploadOutcome
}

/** §14.7 Sending 7 failure texts. */
fun uploadFailureText(reason: String?): String = when (reason) {
    AuthErrors.QUOTA_EXCEEDED -> "You've reached your photo storage limit. Older photos free up space after 30 days."
    AuthErrors.TOO_LARGE -> "This photo is too large"
    AuthErrors.NOT_E2EE -> "Couldn't send: this chat isn't end-to-end encrypted yet."
    AuthErrors.STORAGE_FULL -> "Couldn't send the photo. Try again later."
    else -> "Couldn't send the photo"
}

/**
 * §14.2 upload state machine for one image: the same stored ciphertext file, idempotent by
 * `client_blob_id`; the server's size and SHA-256 must equal the sender's own (else a new
 * `client_blob_id` and another attempt); retries on network, 5xx and 429 with backoff (1 s, 2 s,
 * 4 s … 60 s, honouring Retry-After), 507 at most hourly, never on another 4xx.
 */
class ImageUploader(
    private val api: ApiClient,
    private val media: MediaDao,
    private val messages: MessageDao,
    private val files: MediaFiles,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newBlobId: () -> String = { UUID.randomUUID().toString() },
) {
    private val locks = HashMap<String, Mutex>()

    private fun lockFor(id: String) = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    suspend fun attempt(clientMsgId: String): UploadOutcome = lockFor(clientMsgId).withLock {
        val row = media.get(clientMsgId) ?: return UploadOutcome.Failed("deleted")
        when (row.state) {
            MediaState.UPLOADED.name -> return UploadOutcome.Done(row.blobId!!)
            MediaState.FAILED.name -> return UploadOutcome.Failed(row.failReason ?: "failed")
            MediaState.ENCRYPTED.name, MediaState.UPLOADING.name -> Unit
            else -> return UploadOutcome.Failed("not an upload")
        }
        val file = row.fileName?.let(files::file)?.takeIf { it.isFile && it.length() == row.blobSize }
            ?: return fail(row, "file_lost")
        val cbid = row.clientBlobId ?: newBlobId()
        media.update(row.copy(state = MediaState.UPLOADING.name, clientBlobId = cbid))
        val r = api.uploadMediaBlob(row.conversationId, cbid, file)
        val cur = media.get(clientMsgId) ?: return UploadOutcome.Failed("deleted") // cancelled meanwhile
        when (r) {
            is ApiResult.Ok -> {
                if (r.value.size != row.blobSize || r.value.sha256 != row.blobSha256) {
                    // Never send this reference: retry the same file under a new id (§14.2).
                    val n = cur.attempts + 1
                    if (n >= MAX_MISMATCHES) return fail(cur, "digest_mismatch")
                    media.update(cur.copy(state = MediaState.ENCRYPTED.name, clientBlobId = newBlobId(), attempts = n))
                    return UploadOutcome.Retry(0)
                }
                val expires = r.value.expiresAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() - DAY_MS }.getOrNull() }
                media.update(
                    cur.copy(
                        state = MediaState.UPLOADED.name, blobId = r.value.blobId, attempts = 0, nextAt = 0, failReason = null,
                        expiresAtEst = expires ?: (clock() + FETCHABLE_MS),
                    ),
                )
                messages.setBlobId(clientMsgId, r.value.blobId)
                return UploadOutcome.Done(r.value.blobId)
            }
            is ApiResult.NetworkError -> return retry(cur, backoff(cur.attempts))
            is ApiResult.Error -> return when {
                r.httpStatus == 429 -> retry(cur, r.retryAfterSec?.let { it * 1000 } ?: backoff(cur.attempts))
                r.httpStatus == 507 || r.code == AuthErrors.STORAGE_FULL -> retry(cur, maxOf(HOUR_MS, (r.retryAfterSec ?: 0) * 1000))
                r.httpStatus >= 500 -> retry(cur, r.retryAfterSec?.let { it * 1000 } ?: backoff(cur.attempts))
                r.httpStatus == 401 -> retry(cur, backoff(cur.attempts)) // token refresh failed: try later, never FAILED
                else -> fail(cur, r.code)
            }
        }
    }

    private suspend fun retry(row: MediaEntity, afterMs: Long): UploadOutcome {
        media.update(row.copy(state = MediaState.ENCRYPTED.name, attempts = row.attempts + 1, nextAt = clock() + afterMs))
        return UploadOutcome.Retry(afterMs)
    }

    private suspend fun fail(row: MediaEntity, reason: String): UploadOutcome {
        media.update(row.copy(state = MediaState.FAILED.name, failReason = reason))
        messages.failPending(row.clientMsgId, reason)
        return UploadOutcome.Failed(reason)
    }

    /** Attempts until done, failed, or a wait longer than [maxWaitMs] (then the job is rescheduled). */
    suspend fun run(clientMsgId: String, maxWaitMs: Long = 60_000, sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }): UploadOutcome {
        var tries = 0
        while (true) {
            val o = attempt(clientMsgId)
            if (o !is UploadOutcome.Retry || o.afterMs > maxWaitMs || ++tries >= MAX_IN_RUN) return o
            sleep(o.afterMs)
        }
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val HOUR_MS = 60 * 60 * 1000L

        /** §14.5: a media blob may be treated as fetchable until server_ts + 29 days. */
        const val FETCHABLE_MS = 29 * DAY_MS
        const val MAX_MISMATCHES = 3
        const val MAX_IN_RUN = 8

        fun backoff(attempts: Int): Long = minOf(60_000L, 1000L shl attempts.coerceIn(0, 6))
    }
}

/** One download's verdict. */
sealed interface DownloadOutcome {
    data object Cached : DownloadOutcome

    data object Gone : DownloadOutcome

    data object Corrupt : DownloadOutcome

    data class Retry(val afterMs: Long) : DownloadOutcome
}

/**
 * §14.7 Receiving 3: streamed, resumable (`Range` + `If-Range`) download into a `.part` file,
 * then size → SHA-256 → every segment and the final flag (the core's verified prefix) before the
 * file counts as cached; decrypt and decode happen later, on display. At most 3 at a time. A
 * digest or AEAD failure gets one re-download, then CORRUPT; a 404 is GONE.
 */
class ImageDownloader(
    private val api: ApiClient,
    private val media: MediaDao,
    private val files: MediaFiles,
    private val sealer: MediaSealer,
    private val crypto: () -> MediaCrypto?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val slots = Semaphore(3)
    private val locks = HashMap<String, Mutex>()

    private fun lockFor(id: String) = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    suspend fun download(clientMsgId: String): DownloadOutcome = lockFor(clientMsgId).withLock {
        slots.withPermit { downloadLocked(clientMsgId) }
    }

    private suspend fun downloadLocked(clientMsgId: String): DownloadOutcome {
        val core = crypto() ?: return DownloadOutcome.Retry(NEVER)
        val row = media.get(clientMsgId) ?: return DownloadOutcome.Gone
        when (row.state) {
            MediaState.GONE.name -> return DownloadOutcome.Gone
            MediaState.CORRUPT.name -> return DownloadOutcome.Corrupt
        }
        if (row.fileName != null && files.file(row.fileName).isFile) return DownloadOutcome.Cached
        val blobId = row.blobId ?: return DownloadOutcome.Retry(NEVER)
        val enc = runCatching { sealer.openEnc(clientMsgId, row.sealedEnc) }.getOrElse { return markCorrupt(row, "unseal") }
        val part = files.part(clientMsgId)
        // Resume only from whole segments that verify in order; anything after them is cut.
        val have = if (part.isFile) runCatching { core.verifiedPrefix(part, enc.key, enc.alg, row.blobSize) }.getOrDefault(0L) else 0L
        if (part.isFile && part.length() != have) java.io.RandomAccessFile(part, "rw").use { it.setLength(have) }
        media.update(row.copy(state = MediaState.DOWNLOADING.name, bytesHave = have))
        val r = if (have >= row.blobSize) ApiResult.Ok(have) else api.downloadBlobTo(blobId, part, have, b64ToHex(row.blobSha256))
        val cur = media.get(clientMsgId) ?: return DownloadOutcome.Gone.also { part.delete() }
        when (r) {
            is ApiResult.NetworkError -> return retry(cur, part, BACKOFF_MS)
            is ApiResult.Error -> return when {
                r.httpStatus == 404 -> {
                    part.delete()
                    media.update(cur.copy(state = MediaState.GONE.name, bytesHave = 0))
                    DownloadOutcome.Gone
                }
                r.httpStatus == 429 || r.httpStatus >= 500 || r.httpStatus == 401 -> retry(cur, part, (r.retryAfterSec ?: 2) * 1000)
                else -> retry(cur, part, BACKOFF_MS)
            }
            is ApiResult.Ok -> Unit
        }
        // Size → SHA-256 → AEAD (every segment and the final flag), before the file is used.
        val ok = part.length() == row.blobSize && sha256B64(part) == row.blobSha256 &&
            runCatching { core.verifiedPrefix(part, enc.key, enc.alg, row.blobSize) }.getOrDefault(-1L) == row.blobSize
        if (!ok) {
            part.delete()
            return markCorrupt(cur, "verify")
        }
        val name = files.nameFor(clientMsgId)
        val dst = files.file(name)
        if (!part.renameTo(dst)) return retry(cur, part, BACKOFF_MS)
        media.update(cur.copy(state = MediaState.CACHED.name, fileName = name, bytesHave = row.blobSize, lastAccess = clock()))
        return DownloadOutcome.Cached
    }

    private suspend fun retry(row: MediaEntity, part: File, afterMs: Long): DownloadOutcome {
        media.update(row.copy(state = MediaState.NONE.name, bytesHave = if (part.isFile) part.length() else 0, nextAt = clock() + afterMs))
        return DownloadOutcome.Retry(afterMs)
    }

    /** First failure: drop the bytes and allow one re-download; second: CORRUPT ("Couldn't open this photo"). */
    suspend fun markCorrupt(row: MediaEntity, why: String): DownloadOutcome {
        row.fileName?.let { files.file(it).delete() }
        files.part(row.clientMsgId).delete()
        return if (row.attempts == 0) {
            media.update(row.copy(state = MediaState.NONE.name, fileName = null, bytesHave = 0, attempts = 1))
            DownloadOutcome.Retry(0)
        } else {
            media.update(row.copy(state = MediaState.CORRUPT.name, fileName = null, bytesHave = 0, failReason = why))
            DownloadOutcome.Corrupt
        }
    }

    private companion object {
        const val NEVER = Long.MAX_VALUE
        const val BACKOFF_MS = 5_000L
    }
}
