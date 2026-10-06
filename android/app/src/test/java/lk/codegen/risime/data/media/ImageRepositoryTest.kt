package lk.codegen.risime.data.media

import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.CachedMedia
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.mls.KvSealer
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.net.ApiClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The sender side (§14.7 Sending 4–7, R5, R8), deletes and the cache rules (R4). */
class ImageRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val messages = FakeMessageDao()
    private val media = FakeMediaDao(messages)
    private val crypto = FakeMediaCrypto()
    private val sealer = MediaSealer { KvSealer(ByteArray(32) { 2 }) }
    private val log = mutableListOf<String>()
    private var ids = 0
    private lateinit var files: MediaFiles
    private lateinit var repo: ImageRepository
    private val tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() }

    @Before
    fun setUp() {
        server.start()
        files = MediaFiles(tmp.newFolder("media"))
        repo = ImageRepository(
            media, messages, tx, sealer, files, { crypto }, ApiClient(OkHttpClient(), { server.url("/").toString() }, { "t" }),
            enqueueUpload = { log += "upload $it" }, cancelUpload = { log += "cancel $it" }, enqueueDownloads = { log += "downloads" },
            clock = { 5_000 }, newId = { "id${++ids}" },
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun prepareReencodesEncryptsAndLeavesNoPlaintextThenCommitIsOneTransaction() = runBlocking {
        val src = Fixtures.withJpegMetadata(Fixtures.jpeg(Fixtures.image(300, 200)), orientation = 6)
        val p = repo.prepare(src, ImagePipeline(AwtBitmapOps()))
        assertEquals(200 to 300, p.w to p.h)
        assertTrue(files.file(p.fileName).isFile)
        assertTrue(files.tmp.listFiles()!!.isEmpty()) // the plaintext encrypt input is gone
        assertTrue(messages.rows.isEmpty()) // nothing committed yet

        val row = repo.commit(p, "dm:a_b", "a", "b", "  Site visit  ")
        assertEquals("Site visit", row.body)
        assertEquals(MessageEntity.KIND_IMAGE, messages.rows[row.clientMsgId]!!.kind)
        val m = media.get(row.clientMsgId)!!
        assertEquals(MediaState.ENCRYPTED.name, m.state)
        assertTrue(m.clientBlobId != null)
        assertEquals(listOf("upload ${row.clientMsgId}"), log)
        // Not sendable before its upload (R5).
        assertTrue(messages.pendingOutbox().isEmpty())
        assertNull(repo.envelope(row.clientMsgId))

        // After the upload the sender's own copy is the complete envelope (R8).
        media.update(m.copy(state = MediaState.UPLOADED.name, blobId = "b1"))
        val env = (MlsPayload.decode(repo.envelope(row.clientMsgId)!!) as MlsPayload.Decoded.Image).envelope
        assertEquals("b1", env.blob.blobId)
        assertEquals(m.blobSize, env.blob.size)
        assertEquals(p.mime, env.mime)
        assertEquals(200 to 300, env.w to env.h)
        assertEquals("Site visit", env.caption)
        assertTrue(env.thumb != null)
        assertTrue(env.enc.key.size == 32)
    }

    @Test
    fun cancelAfterUploadDeletesTheBlobAndEverythingLocal() = runBlocking {
        val p = repo.prepare(Fixtures.jpeg(Fixtures.image(40, 30)), ImagePipeline(AwtBitmapOps()))
        val row = repo.commit(p, "dm:a_b", "a", "b", "")
        media.update(media.get(row.clientMsgId)!!.copy(state = MediaState.UPLOADED.name, blobId = "b7"))
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(repo.deleteUnsent(row.clientMsgId))
        assertEquals("DELETE", server.takeRequest().let { assertEquals("/api/v1/blobs/b7", it.path); it.method })
        assertNull(media.get(row.clientMsgId))
        assertNull(messages.rows[row.clientMsgId])
        assertFalse(files.file(p.fileName).exists())
        assertTrue(log.contains("cancel ${row.clientMsgId}"))
    }

    @Test
    fun retryAfterAFailedUploadUsesANewClientBlobId() = runBlocking {
        val p = repo.prepare(Fixtures.jpeg(Fixtures.image(40, 30)), ImagePipeline(AwtBitmapOps()))
        val row = repo.commit(p, "dm:a_b", "a", "b", "")
        val before = media.get(row.clientMsgId)!!
        media.update(before.copy(state = MediaState.FAILED.name, failReason = "quota_exceeded"))
        messages.failPending(row.clientMsgId, "quota_exceeded")
        log.clear()
        assertTrue(repo.retry(row.clientMsgId))
        val after = media.get(row.clientMsgId)!!
        assertEquals(MediaState.ENCRYPTED.name, after.state)
        assertTrue(after.clientBlobId != before.clientBlobId)
        assertEquals("PENDING", messages.rows[row.clientMsgId]!!.status)
        assertEquals(listOf("upload ${row.clientMsgId}"), log)
    }

    @Test
    fun startupFailsAnUnsentImageWhoseFileIsLostAndResumesTheOthers() = runBlocking {
        val a = repo.commit(repo.prepare(Fixtures.jpeg(Fixtures.image(40, 30)), ImagePipeline(AwtBitmapOps())), "dm:a_b", "a", "b", "")
        val b = repo.commit(repo.prepare(Fixtures.jpeg(Fixtures.image(40, 30)), ImagePipeline(AwtBitmapOps())), "dm:a_b", "a", "b", "")
        files.file(media.get(a.clientMsgId)!!.fileName!!).delete()
        files.tmp.resolve("left.plain").writeBytes(byteArrayOf(1))
        log.clear()
        repo.startup()
        assertEquals(MediaState.FAILED.name, media.get(a.clientMsgId)!!.state)
        assertEquals("FAILED", messages.rows[a.clientMsgId]!!.status)
        assertTrue(log.contains("upload ${b.clientMsgId}"))
        assertTrue(files.tmp.listFiles()!!.isEmpty())
    }

    private fun cached(id: String, size: Long, access: Long, expires: Long?, outgoing: Boolean = false, status: String = "READ") =
        CachedMedia(id, "$id.enc", size, access, expires, outgoing, MediaState.CACHED.name, status)

    @Test
    fun evictionNeverDestroysTheLastCopy() {
        val now = 100_000L
        val rows = listOf(
            cached("old-expired", 300, 1, expires = now - 1), // past the TTL: kept until the user acts
            cached("unsent", 300, 2, expires = now + 10, outgoing = true, status = "PENDING"), // my unsent original
            cached("failed", 300, 3, expires = now + 10, outgoing = true, status = "FAILED"),
            cached("live-old", 300, 4, expires = now + 10),
            cached("live-new", 300, 9, expires = now + 10),
            cached("unknown-ttl", 300, 0, expires = null),
        )
        assertEquals(listOf("live-old"), evictionPlan(rows, now, budgetBytes = 1_500).map { it.clientMsgId })
        assertEquals(listOf("live-old", "live-new"), evictionPlan(rows, now, budgetBytes = 0).map { it.clientMsgId })
        assertTrue(evictionPlan(rows, now, budgetBytes = 10_000).isEmpty())
    }

    @Test
    fun autoDownloadPolicy() {
        assertTrue(autoDownload(NetKind.UNMETERED, visibleInOpenChat = false))
        assertTrue(autoDownload(NetKind.METERED, visibleInOpenChat = true))
        assertFalse(autoDownload(NetKind.METERED, visibleInOpenChat = false))
        assertFalse(autoDownload(NetKind.RESTRICTED, visibleInOpenChat = true)) // Data Saver / roaming: tap only
        assertFalse(autoDownload(NetKind.OFFLINE, visibleInOpenChat = true))
    }
}
