package lk.codegen.risime.data.media

import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.CachedMedia
import lk.codegen.risime.data.db.MediaDao
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.db.MessageDao
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.BlobRef
import java.io.File
import java.util.Base64
import java.util.UUID

/** What the chat engine needs from the image store (null in builds/tests without images). */
interface ImageHooks {
    /** §14.7 Receiving 1: inside the event's transaction, with the message row. Nothing is fetched here. */
    suspend fun stored(row: MessageEntity, envelope: ImageEnvelope)

    /** After the events' transactions: schedule downloads. */
    fun received()

    /** The envelope to encrypt at send time (null: not ready / gone). */
    suspend fun envelope(clientMsgId: String): ByteArray?

    /** The message row was deleted locally: its key and cached ciphertext go too. */
    suspend fun deleted(clientMsgId: String)

    /**
     * §15.6 (android R10), inside the delete's transaction: the media row (sealed key, thumbnail,
     * blob reference) goes; returns the files to unlink **after** the commit.
     */
    suspend fun purgeRow(clientMsgId: String): List<java.io.File> {
        deleted(clientMsgId)
        return emptyList()
    }

    /** After the commit: unlink [files], cancel the transfer jobs of [clientMsgId]. */
    fun afterPurge(clientMsgId: String, files: List<java.io.File>) {
        files.forEach { it.delete() }
    }
}

/** Auto-download policy (§14.7 Receiving 7). */
enum class NetKind { UNMETERED, METERED, RESTRICTED, OFFLINE }

fun autoDownload(net: NetKind, visibleInOpenChat: Boolean): Boolean =
    net == NetKind.UNMETERED || (net == NetKind.METERED && visibleInOpenChat)

/**
 * Android R4: the LRU (≈ 500 MiB) evicts only images whose blob is still fetchable
 * (`expires_at_est` in the future) and whose message the server has accepted (never an unsent
 * outgoing image); the least recently used go first. Older images are kept until the user acts.
 */
fun evictionPlan(cached: List<CachedMedia>, now: Long, budgetBytes: Long): List<CachedMedia> {
    var total = cached.sumOf { it.blobSize }
    if (total <= budgetBytes) return emptyList()
    val out = mutableListOf<CachedMedia>()
    val unsent = setOf(MessageStatus.PENDING.name, MessageStatus.FAILED.name)
    for (c in cached.filter { (it.expiresAtEst ?: 0) > now && !(it.outgoing && it.messageStatus in unsent) }.sortedBy { it.lastAccess }) {
        if (total <= budgetBytes) break
        out += c
        total -= c.blobSize
    }
    return out
}

/** The attach sheet's steps while a picked photo is prepared in-process. */
enum class PrepareStep { REENCODING, ENCRYPTING }

/** An image re-encoded and encrypted in-process (§14.7 Sending 4), not yet committed as a message. */
class PreparedSend(
    val clientMsgId: String,
    val fileName: String,
    val sealed: SealedBlob,
    val mime: String,
    val w: Int,
    val h: Int,
    val thumb: ImageThumb?,
)

sealed interface Decrypted {
    class Ok(val bytes: ByteArray, val mime: String, val w: Int, val h: Int) : Decrypted

    /** Not on the device: download first. */
    data object Missing : Decrypted

    /** Decrypt failed (logged without bytes); one re-download is allowed, then CORRUPT. */
    data object Failed : Decrypted
}

/**
 * §14 on the device: stores received envelopes (sealed key and thumbnail), prepares and commits
 * sends, builds the send-time envelope from the sender's complete stored copy (R8), retries,
 * deletes, decrypts on display, and runs the cache rules (R4).
 */
class ImageRepository(
    private val media: MediaDao,
    private val messages: MessageDao,
    private val tx: TransactionRunner,
    private val sealer: MediaSealer,
    val files: MediaFiles,
    private val crypto: () -> MediaCrypto?,
    private val api: ApiClient?,
    private val enqueueUpload: (String) -> Unit = {},
    /** Re-run the upload job now, replacing one that waits in its backoff ("Retry" on a waiting photo). */
    private val restartUpload: (String) -> Unit = enqueueUpload,
    private val cancelUpload: (String) -> Unit = {},
    private val enqueueDownloads: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = {},
) : ImageHooks {
    private val b64 = Base64.getEncoder()

    // ---- receiving ----

    override suspend fun stored(row: MessageEntity, envelope: ImageEnvelope) {
        val sent = row.serverTs?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: clock()
        media.insert(
            MediaEntity(
                clientMsgId = row.clientMsgId,
                conversationId = row.conversationId,
                outgoing = row.outgoing,
                state = MediaState.NONE.name,
                blobId = envelope.blob.blobId,
                blobSize = envelope.blob.size,
                blobSha256 = envelope.blob.sha256,
                clientBlobId = null,
                sealedEnc = sealer.sealEnc(row.clientMsgId, envelope.enc),
                sealedThumb = envelope.thumb?.let { sealer.sealThumb(row.clientMsgId, it) },
                mime = envelope.mime,
                w = envelope.w,
                h = envelope.h,
                fileName = null,
                expiresAtEst = sent + ImageUploader.FETCHABLE_MS,
                lastAccess = clock(),
            ),
        )
    }

    override fun received() = enqueueDownloads()

    fun thumb(clientMsgId: String, row: MediaEntity): ImageThumb? =
        row.sealedThumb?.let { runCatching { sealer.openThumb(clientMsgId, it) }.getOrNull() }

    /** Decrypt-on-display: plaintext only in memory, after every check in the core. */
    suspend fun decrypt(clientMsgId: String): Decrypted {
        val core = crypto() ?: return Decrypted.Missing
        val row = media.get(clientMsgId) ?: return Decrypted.Missing
        val f = row.fileName?.let(files::file)?.takeIf { it.isFile } ?: return Decrypted.Missing
        return try {
            val enc = sealer.openEnc(clientMsgId, row.sealedEnc)
            val bytes = core.decryptFile(f, enc.key, enc.alg, enc.plainSize, row.blobSize, Base64.getDecoder().decode(row.blobSha256))
            touch(row)
            Decrypted.Ok(bytes, row.mime, row.w, row.h)
        } catch (e: Exception) {
            log("image $clientMsgId: decrypt failed (${e.javaClass.simpleName})")
            Decrypted.Failed
        }
    }

    /** A decrypt or decode failure: drop the file; one re-download, then CORRUPT. */
    suspend fun failedToOpen(clientMsgId: String) {
        val row = media.get(clientMsgId) ?: return
        if (row.outgoing && row.state != MediaState.CACHED.name && row.state != MediaState.NONE.name) return // my unsent original: never deleted
        row.fileName?.let { files.file(it).delete() }
        media.update(
            if (row.attempts == 0) row.copy(state = MediaState.NONE.name, fileName = null, attempts = 1)
            else row.copy(state = MediaState.CORRUPT.name, fileName = null),
        )
        enqueueDownloads()
    }

    private suspend fun touch(row: MediaEntity) {
        if (clock() - row.lastAccess > 60_000) media.update(row.copy(lastAccess = clock()))
    }

    // ---- sending ----

    /**
     * §14.7 Sending 2–4, in-process (the picker grant dies with the process): re-encode, then
     * encrypt into the blob file. The plaintext encrypt input lives only in `media/tmp` for the
     * duration of the encrypt call (the core streams file to file) and is deleted right after.
     */
    fun <B> prepare(source: ByteArray, pipeline: ImagePipeline<B>, onStep: (PrepareStep) -> Unit = {}): PreparedSend {
        val core = crypto() ?: throw ImageRejected("Photos need end-to-end encryption on this phone")
        if (source.size > ImagePipeline.MAX_SOURCE_BYTES) throw ImageRejected("This photo is too large")
        onStep(PrepareStep.REENCODING)
        val p = pipeline.prepare(source)
        onStep(PrepareStep.ENCRYPTING)
        val id = newId()
        val name = files.nameFor(id)
        val plain = File(files.tmp, "$name.plain")
        try {
            plain.writeBytes(p.bytes)
            val sealed = core.encryptFile(plain, files.file(name))
            if (sealed.cipherSize > MediaFormat.MAX_MEDIA_CIPHER) {
                files.file(name).delete()
                throw ImageRejected("This photo is too large")
            }
            return PreparedSend(id, name, sealed, p.mime, p.w, p.h, p.thumb)
        } finally {
            plain.delete()
        }
    }

    fun discard(p: PreparedSend) {
        files.file(p.fileName).delete()
    }

    /** One transaction: the PENDING image message and its media row (ENCRYPTED); then the upload job. */
    suspend fun commit(p: PreparedSend, conversationId: String, me: String, to: String, caption: String): MessageEntity {
        val row = MessageEntity(
            clientMsgId = p.clientMsgId, messageId = null, conversationId = conversationId, from = me, to = to,
            body = caption.trim(), serverTs = null, localTs = clock(), status = MessageStatus.PENDING.name, outgoing = true,
            kind = MessageEntity.KIND_IMAGE,
        )
        val enc = ImageEnc(p.sealed.alg, p.sealed.key, p.sealed.plainSize)
        tx.run {
            messages.insert(row)
            media.insert(
                MediaEntity(
                    clientMsgId = p.clientMsgId, conversationId = conversationId, outgoing = true, state = MediaState.ENCRYPTED.name,
                    blobId = null, blobSize = p.sealed.cipherSize, blobSha256 = b64.encodeToString(p.sealed.sha256),
                    clientBlobId = newId(), sealedEnc = sealer.sealEnc(p.clientMsgId, enc),
                    sealedThumb = p.thumb?.let { sealer.sealThumb(p.clientMsgId, it) }, mime = p.mime, w = p.w, h = p.h,
                    fileName = p.fileName, expiresAtEst = null, lastAccess = clock(),
                ),
            )
        }
        p.sealed.key.fill(0)
        enqueueUpload(p.clientMsgId)
        return row
    }

    override suspend fun envelope(clientMsgId: String): ByteArray? {
        val row = media.get(clientMsgId)?.takeIf { it.state == MediaState.UPLOADED.name || it.state == MediaState.CACHED.name } ?: return null
        val msg = messages.byClientMsgId(clientMsgId) ?: return null
        val blobId = row.blobId ?: return null
        return ImageEnvelope(
            BlobRef(blobId, row.blobSize, row.blobSha256), sealer.openEnc(clientMsgId, row.sealedEnc), row.mime, row.w, row.h,
            thumb(clientMsgId, row), msg.body.takeIf { it.isNotBlank() },
        ).encode()
    }

    /**
     * Retry on an image bubble (the tap target and the menu):
     * - a failed upload restarts with the **same** `client_blob_id` (§14.2: a completed upload whose
     *   reply was lost replays `200`, even at the quota); a new one only after a digest mismatch,
     *   or a `404` / `400` that the same id would get again (a deleted blob's id, a changed length);
     * - an upload waiting in its backoff runs now;
     * - a failed send (uploaded) just resends.
     */
    suspend fun retry(clientMsgId: String): Boolean {
        val row = media.get(clientMsgId) ?: return false
        if (row.state == MediaState.FAILED.name) {
            if (row.fileName == null || !files.file(row.fileName).isFile) return false
            val id = if (row.clientBlobId == null || row.failReason in NEW_ID_AFTER) newId() else row.clientBlobId
            media.update(row.copy(state = MediaState.ENCRYPTED.name, clientBlobId = id, attempts = 0, nextAt = 0, failReason = null))
            messages.retryFailed(clientMsgId)
            enqueueUpload(clientMsgId)
            return true
        }
        if (row.state == MediaState.ENCRYPTED.name && row.attempts > 0) {
            media.update(row.copy(nextAt = 0))
            restartUpload(clientMsgId)
            return true
        }
        return messages.retryFailed(clientMsgId) > 0
    }

    /** Delete / cancel an image the server never accepted: the upload stops, an uploaded blob is deleted (§14.2). */
    suspend fun deleteUnsent(clientMsgId: String): Boolean {
        val msg = messages.byClientMsgId(clientMsgId) ?: return false
        if (msg.messageId != null || (msg.status != MessageStatus.FAILED.name && msg.status != MessageStatus.PENDING.name)) return false
        cancelUpload(clientMsgId)
        val row = media.get(clientMsgId)
        tx.run {
            messages.delete(clientMsgId)
            media.delete(clientMsgId)
        }
        row?.fileName?.let { files.file(it).delete() }
        files.part(clientMsgId).delete()
        row?.blobId?.let { id -> runCatching { api?.deleteBlob(id) } }
        return true
    }

    override suspend fun purgeRow(clientMsgId: String): List<File> {
        val row = media.get(clientMsgId) ?: return listOf(files.part(clientMsgId))
        media.delete(clientMsgId)
        return listOfNotNull(row.fileName?.let { files.file(it) }, files.part(clientMsgId))
    }

    override fun afterPurge(clientMsgId: String, files: List<File>) {
        cancelUpload(clientMsgId)
        files.forEach { it.delete() }
        purgedIds.tryEmit(clientMsgId)
    }

    /** §15.6: purged images (the UI drops in-memory bitmaps and closes a viewer showing one). */
    val purgedIds = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 64)

    override suspend fun deleted(clientMsgId: String) {
        val row = media.get(clientMsgId) ?: return
        media.delete(clientMsgId)
        row.fileName?.let { files.file(it).delete() }
        files.part(clientMsgId).delete()
    }

    // ---- housekeeping ----

    /** On start: temp plaintext and orphans go; an unsent image whose file is lost fails (Delete only); owed work resumes. */
    suspend fun startup() {
        val rows = media.owedUploads()
        for (r in rows) {
            if (r.fileName == null || !files.file(r.fileName).isFile) {
                media.update(r.copy(state = MediaState.FAILED.name, failReason = "file_lost"))
                messages.failPending(r.clientMsgId, "file_lost")
            } else {
                enqueueUpload(r.clientMsgId)
            }
        }
        val live = (media.fileNames() + media.downloadable().map { files.nameFor(it.clientMsgId) }).toSet()
        files.cleanup(live)
        evict()
        enqueueDownloads()
    }

    suspend fun evict(budget: Long = CACHE_BUDGET) {
        for (c in evictionPlan(media.cached(), clock(), budget)) {
            val row = media.get(c.clientMsgId) ?: continue
            files.file(c.fileName).delete()
            media.update(row.copy(state = MediaState.NONE.name, fileName = null, bytesHave = 0))
        }
    }

    companion object {
        const val CACHE_BUDGET = 500L * 1024 * 1024
        /** Upload failures the same client_blob_id would hit again. */
        val NEW_ID_AFTER = setOf("digest_mismatch", "not_found", "bad_request")
    }
}
