package lk.codegen.risime.data.media

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.mls.KvSealer
import lk.codegen.risime.net.ApiClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.Base64

/** §14.2 upload state machine and resumable, verify-before-use downloads, against a fake HTTP server. */
class ImageTransferTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var api: ApiClient
    private val messages = FakeMessageDao()
    private val media = FakeMediaDao(messages)
    private lateinit var files: MediaFiles
    private val sealer = MediaSealer { KvSealer(ByteArray(32) { 9 }) }
    private val crypto = FakeMediaCrypto()
    private var ids = 0
    private var now = 1_000L
    private lateinit var uploader: ImageUploader
    private lateinit var downloader: ImageDownloader

    private val blob = ByteArray(200_000).also { java.util.Random(1).nextBytes(it) }
    private val sha = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    private val key = ByteArray(32) { 5 }

    @Before
    fun setUp() {
        server.start()
        api = ApiClient(OkHttpClient(), { server.url("/").toString() }, { "tok" })
        files = MediaFiles(tmp.newFolder("media"))
        uploader = ImageUploader(api, media, messages, files, clock = { now }, newBlobId = { "cb${++ids}" })
        downloader = ImageDownloader(api, media, files, sealer, { crypto }, clock = { 1_000 })
        crypto.register(key, blob)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun outgoing(): MediaEntity = runBlocking {
        messages.insert(MessageEntity("c1", null, "dm:a_b", "a", "b", "", null, 1, "PENDING", true, kind = MessageEntity.KIND_IMAGE))
        val name = files.nameFor("c1")
        files.file(name).writeBytes(blob)
        MediaEntity(
            "c1", "dm:a_b", true, MediaState.ENCRYPTED.name, null, blob.size.toLong(), sha, "cb0",
            sealer.sealEnc("c1", ImageEnc(MediaFormat.ALG, key, 1000)), null, "image/jpeg", 10, 10, name, 0, null, 1,
        ).also { media.insert(it) }
    }

    private fun reply(size: Long = blob.size.toLong(), s: String = sha, code: Int = 201) =
        MockResponse().setResponseCode(code).setBody("""{"blob_id":"b1","size":$size,"sha256":"$s","expires_at":"2026-11-05T08:15:30.456Z"}""")

    @Test
    fun uploadReportsDeterminateProgressAndClearsItWhenTheAttemptEnds() = runBlocking {
        outgoing()
        val seen = mutableListOf<Float>()
        val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            uploader.progress.flow.collect { m -> m["c1"]?.let { seen += it } }
        }
        server.enqueue(reply())
        assertEquals(UploadOutcome.Done("b1"), uploader.run("c1"))
        watcher.cancel()
        assertTrue("progress steps: $seen", seen.size >= 3 && seen.first() == 0f && seen.last() == 1f)
        assertEquals(seen.sorted(), seen) // never goes back within one attempt
        assertNull(uploader.progress.flow.value["c1"]) // the row's state takes over
        // A failed attempt clears it too.
        val row = media.get("c1")!!
        media.update(row.copy(state = MediaState.ENCRYPTED.name))
        server.enqueue(MockResponse().setResponseCode(415).setBody("""{"error":"bad_media_type","message":"x"}"""))
        uploader.attempt("c1")
        assertNull(uploader.progress.flow.value["c1"])
    }

    @Test
    fun uploadStoresTheReferenceAndTheOutboxCanSend() = runBlocking {
        outgoing()
        assertTrue(messages.pendingOutbox().isEmpty()) // never blocks: not in the outbox yet
        server.enqueue(reply())
        assertEquals(UploadOutcome.Done("b1"), uploader.run("c1"))
        val req = server.takeRequest()
        assertEquals("/api/v1/blobs?purpose=media&conversation_id=dm:a_b&client_blob_id=cb0", req.path)
        assertEquals("application/octet-stream", req.getHeader("Content-Type"))
        assertEquals(blob.size.toString(), req.getHeader("Content-Length"))
        assertArrayEquals(blob, req.body.readByteArray())
        val row = media.get("c1")!!
        assertEquals(MediaState.UPLOADED.name, row.state)
        assertEquals("b1", row.blobId)
        assertEquals("b1", messages.rows["c1"]!!.blobId)
        assertEquals(listOf("c1"), messages.pendingOutbox().map { it.clientMsgId })
        // Done is idempotent: no second request.
        assertEquals(UploadOutcome.Done("b1"), uploader.attempt("c1"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun lostReplyRetriesTheSameIdAndGets200() = runBlocking {
        outgoing()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(reply(code = 200))
        val sleeps = mutableListOf<Long>()
        assertEquals(UploadOutcome.Done("b1"), uploader.run("c1") { sleeps += it; now += it })
        assertEquals(listOf(1000L), sleeps)
        val first = server.takeRequest().path
        assertEquals(first, server.takeRequest().path) // same client_blob_id
    }

    @Test
    fun digestMismatchRetriesWithANewClientBlobId() = runBlocking {
        outgoing()
        server.enqueue(reply(s = Base64.getEncoder().encodeToString(ByteArray(32))))
        server.enqueue(reply())
        assertEquals(UploadOutcome.Done("b1"), uploader.run("c1") {})
        assertTrue(server.takeRequest().path!!.endsWith("client_blob_id=cb0"))
        assertTrue(server.takeRequest().path!!.endsWith("client_blob_id=cb1"))
    }

    @Test
    fun refusalsFailWithTheirTextAndNeverRetry() = runBlocking {
        for ((code, body) in listOf(
            413 to """{"error":{"code":"quota_exceeded","message":"","used":1,"limit":2}}""",
            413 to """{"error":{"code":"too_large","message":""}}""",
            409 to """{"error":{"code":"not_e2ee","message":""}}""",
            404 to """{"error":{"code":"not_found","message":""}}""",
        )) {
            media.rows.value = emptyMap(); messages.rows.clear()
            outgoing()
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            val o = uploader.run("c1") { error("no retry on $code") }
            assertTrue("$o", o is UploadOutcome.Failed)
            assertEquals("FAILED", messages.rows["c1"]!!.status)
            assertEquals(MediaState.FAILED.name, media.get("c1")!!.state)
        }
        assertEquals("You've reached your photo storage limit. Older photos free up space after 30 days.", uploadFailureText("quota_exceeded"))
        assertEquals("This photo is too large", uploadFailureText("too_large"))
        assertEquals("Couldn't send: this chat isn't end-to-end encrypted yet.", uploadFailureText("not_e2ee"))
        assertEquals("Couldn't send the photo. Try again later.", uploadFailureText("storage_full"))
    }

    @Test
    fun rateLimitHonoursRetryAfterAndStorageFullWaitsAnHour() = runBlocking {
        outgoing()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7200"))
        assertEquals(UploadOutcome.Retry(7_200_000), uploader.run("c1") { error("too long to wait in-run") })
        // Before Retry-After nothing is sent.
        assertEquals(UploadOutcome.Retry(7_200_000), uploader.attempt("c1"))
        assertEquals(1, server.requestCount)
        media.update(media.get("c1")!!.copy(nextAt = 0))
        server.enqueue(MockResponse().setResponseCode(507).setBody("""{"error":{"code":"storage_full","message":""}}"""))
        assertEquals(UploadOutcome.Retry(ImageUploader.HOUR_MS), uploader.run("c1") { error("hourly") })
        assertEquals(MediaState.ENCRYPTED.name, media.get("c1")!!.state)
        assertEquals("PENDING", messages.rows["c1"]!!.status)
    }

    @Test
    fun backoffIsCapped() {
        assertEquals(listOf(1000L, 2000, 4000, 8000, 16000, 32000, 60000, 60000), (0..7).map { ImageUploader.backoff(it) })
    }

    // ---- downloads ----

    private fun incoming(): MediaEntity = runBlocking {
        messages.insert(MessageEntity("r1", "m1", "dm:a_b", "b", "a", "", "2026-10-06T08:00:00Z", 1, "DELIVERED", false, kind = MessageEntity.KIND_IMAGE))
        MediaEntity(
            "r1", "dm:a_b", false, MediaState.NONE.name, "b9", blob.size.toLong(), sha, null,
            sealer.sealEnc("r1", ImageEnc(MediaFormat.ALG, key, 1000)), null, "image/jpeg", 10, 10, null, 0, null, 1,
        ).also { media.insert(it) }
    }

    @Test
    fun downloadVerifiesAndCaches() = runBlocking {
        incoming()
        server.enqueue(MockResponse().setBody(Buffer().write(blob)))
        assertEquals(DownloadOutcome.Cached, downloader.download("r1"))
        val row = media.get("r1")!!
        assertEquals(MediaState.CACHED.name, row.state)
        assertArrayEquals(blob, files.file(row.fileName!!).readBytes())
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test
    fun resumeFromVerifiedSegmentsWithRangeAndIfRange() = runBlocking {
        incoming()
        // 100 000 bytes arrived before: only the first whole segment (65 552) verifies and is kept.
        files.part("r1").writeBytes(blob.copyOf(100_000))
        server.enqueue(
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 65552-${blob.size - 1}/${blob.size}")
                .setBody(Buffer().write(blob.copyOfRange(65_552, blob.size))),
        )
        assertEquals(DownloadOutcome.Cached, downloader.download("r1"))
        val req = server.takeRequest()
        assertEquals("bytes=65552-", req.getHeader("Range"))
        assertEquals("\"" + b64ToHex(sha) + "\"", req.getHeader("If-Range"))
        assertArrayEquals(blob, files.file(media.get("r1")!!.fileName!!).readBytes())
    }

    @Test
    fun ifRangeMismatchGivesAFull200() = runBlocking {
        incoming()
        files.part("r1").writeBytes(blob.copyOf(70_000))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(blob)))
        assertEquals(DownloadOutcome.Cached, downloader.download("r1"))
        assertArrayEquals(blob, files.file(media.get("r1")!!.fileName!!).readBytes())
    }

    @Test
    fun digestIsCheckedBeforeDecryptAndCorruptAfterOneRedownload() = runBlocking {
        incoming()
        val bad = blob.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bad)))
        val before = crypto.verifyCalls
        assertEquals(DownloadOutcome.Retry(0), downloader.download("r1"))
        // The digest failed first: the AEAD check never ran on those bytes.
        assertEquals(before, crypto.verifyCalls)
        assertEquals(MediaState.NONE.name, media.get("r1")!!.state)
        server.enqueue(MockResponse().setBody(Buffer().write(bad)))
        assertEquals(DownloadOutcome.Corrupt, downloader.download("r1"))
        assertEquals(MediaState.CORRUPT.name, media.get("r1")!!.state)
        assertTrue(files.dir.listFiles()!!.none { it.isFile })
    }

    @Test
    fun notFoundIsGoneAndTheThumbnailStays() = runBlocking {
        incoming()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"not_found","message":""}}"""))
        assertEquals(DownloadOutcome.Gone, downloader.download("r1"))
        assertEquals(MediaState.GONE.name, media.get("r1")!!.state)
        assertEquals(DownloadOutcome.Gone, downloader.download("r1"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun networkErrorKeepsTheRowDownloadable() = runBlocking {
        incoming()
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(downloader.download("r1") is DownloadOutcome.Retry)
        assertEquals(MediaState.NONE.name, media.get("r1")!!.state)
    }

    @Test
    fun cleanupRemovesPlaintextTempsAndOrphans() {
        files.tmp.resolve("x.plain").writeBytes(byteArrayOf(1))
        files.file("orphan.enc").writeBytes(byteArrayOf(1))
        files.file("live.enc").writeBytes(byteArrayOf(1))
        files.file("live.enc.part").writeBytes(byteArrayOf(1))
        files.cleanup(setOf("live.enc"))
        assertEquals(setOf("live.enc", "live.enc.part"), files.dir.listFiles()!!.filter { it.isFile }.map { it.name }.toSet())
        assertTrue(files.tmp.listFiles()!!.isEmpty())
    }
}
