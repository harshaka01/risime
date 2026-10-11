package ai.risicloud.risime.live

import ai.risicloud.risime.crypto.UniffiMediaCrypto
import ai.risicloud.risime.data.FakeMessageDao
import ai.risicloud.risime.data.TransactionRunner
import ai.risicloud.risime.data.media.AwtBitmapOps
import ai.risicloud.risime.data.media.Decrypted
import ai.risicloud.risime.data.media.DownloadOutcome
import ai.risicloud.risime.data.media.FakeMediaDao
import ai.risicloud.risime.data.media.Fixtures
import ai.risicloud.risime.data.media.ImageBytes
import ai.risicloud.risime.data.media.ImageDownloader
import ai.risicloud.risime.data.media.ImagePipeline
import ai.risicloud.risime.data.media.ImageRepository
import ai.risicloud.risime.data.media.ImageUploader
import ai.risicloud.risime.data.media.MediaFiles
import ai.risicloud.risime.data.media.MediaSealer
import ai.risicloud.risime.data.media.UploadOutcome
import ai.risicloud.risime.data.mls.KvSealer
import ai.risicloud.risime.net.ApiClient
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.security.MessageDigest

/**
 * One device's §14 image stack for the live interop tests: the app's ImageRepository, uploader and
 * downloader over the REAL media core (UniffiMediaCrypto) and the real server; in-memory DAOs.
 */
class LiveImageKit(api: ApiClient, messages: FakeMessageDao, name: String) {
    val media = FakeMediaDao(messages)
    val files = MediaFiles(Files.createTempDirectory("risime-media-$name").toFile())
    private val crypto = UniffiMediaCrypto()
    private val sealer = MediaSealer { KvSealer(ByteArray(32) { name.hashCode().toByte() }) }
    private val tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() }
    val repo = ImageRepository(media, messages, tx, sealer, files, { crypto }, api)
    val uploader = ImageUploader(api, media, messages, files)

    /** Every upload progress value seen per client_msg_id (the bubble's "Uploading N%"). */
    val progressSeen = java.util.concurrent.ConcurrentHashMap<String, MutableList<Float>>()
    private val progressWatch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
        uploader.progress.flow.collect { m -> m.forEach { (id, f) -> progressSeen.getOrPut(id) { java.util.Collections.synchronizedList(mutableListOf()) }.let { l -> if (l.lastOrNull() != f) l += f } } }
    }
    val downloader = ImageDownloader(api, media, files, sealer, { crypto })

    /** Pick → re-encode → encrypt → commit → upload, like the app; returns (client_msg_id, SHA-256 of the sent plaintext). */
    suspend fun send(conv: String, me: String, to: String, caption: String, w: Int = 900, h: Int = 700): Pair<String, String> {
        val src = Fixtures.withJpegMetadata(Fixtures.jpeg(Fixtures.image(w, h, noise = true)), orientation = 6)
        val p = repo.prepare(src, ImagePipeline(AwtBitmapOps(leakMetadata = true)))
        val row = repo.commit(p, conv, me, to, caption)
        val up = uploader.run(row.clientMsgId)
        if (up !is UploadOutcome.Done) throw AssertionError("upload: $up")
        val plain = (repo.decrypt(row.clientMsgId) as? Decrypted.Ok)?.bytes ?: throw AssertionError("sender can't decrypt its own blob")
        if (ImageBytes.findMetadata(plain).isNotEmpty()) throw AssertionError("metadata in the sent image: ${ImageBytes.findMetadata(plain)}")
        return row.clientMsgId to sha(plain)
    }

    /** Download + decrypt; the plaintext's SHA-256. */
    suspend fun fetch(id: String): String {
        val o = downloader.download(id)
        if (o != DownloadOutcome.Cached) throw AssertionError("download $id: $o")
        val d = repo.decrypt(id) as? Decrypted.Ok ?: throw AssertionError("decrypt $id failed")
        return sha(d.bytes)
    }

    fun close() {
        progressWatch.cancel()
        files.wipe()
    }

    companion object {
        fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
