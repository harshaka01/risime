package lk.codegen.risime.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

/** P0-1 A: the download resumes with Range, and a cancel stops it at once and keeps the bytes. */
class ResumableDownloadTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val apkBytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val ranges = mutableListOf<String?>()

    /** Serves [apkBytes] with Range support; [slow] throttles the body; [ignoreRange] answers 200 always. */
    private fun serve(slow: Boolean = false, ignoreRange: Boolean = false, code: Int? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                synchronized(ranges) { ranges += range }
                if (code != null) return MockResponse().setResponseCode(code)
                val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                val r = if (from == null || ignoreRange) {
                    MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes))
                } else {
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $from-${apkBytes.size - 1}/${apkBytes.size}")
                        .setBody(Buffer().write(apkBytes, from, apkBytes.size - from))
                }
                return if (slow) r.throttleBody(16 * 1024, 100, TimeUnit.MILLISECONDS) else r
            }
        }
    }

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.shutdown()

    private val url get() = server.url("/app/risime/risime-x.apk").toString()
    private val part get() = tmp.root.resolve("x.apk.part")
    private val apk get() = tmp.root.resolve("x.apk")

    @Test fun resumesAPartialFileWithRange() = runBlocking {
        serve()
        part.writeBytes(apkBytes.copyOfRange(0, 120_000))
        val seen = mutableListOf<Int?>()
        ResumableDownload(OkHttpClient()).fetch(url, part, apk) { seen += it }
        assertEquals(listOf<String?>("bytes=120000-"), ranges)
        assertArrayEquals(apkBytes, apk.readBytes())
        assertFalse(part.exists())
        assertEquals(40, seen.first()) // continues from 40 %
        assertEquals(100, seen.last())
        assertEquals(sha256Hex(apk), java.security.MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) })
    }

    @Test fun serverIgnoringRangeRewritesTheWholeFile() = runBlocking {
        serve(ignoreRange = true)
        part.writeBytes(ByteArray(5_000) { 9 }) // junk that must not survive
        ResumableDownload(OkHttpClient()).fetch(url, part, apk) {}
        assertArrayEquals(apkBytes, apk.readBytes())
    }

    @Test fun httpErrorKeepsThePartialAndCarriesTheCode() = runBlocking {
        serve(code = 503)
        part.writeBytes(apkBytes.copyOfRange(0, 1_000))
        try {
            ResumableDownload(OkHttpClient()).fetch(url, part, apk) {}
            fail("expected HTTP 503")
        } catch (e: UpdateHttpException) {
            assertEquals(503, e.code)
            assertEquals("Couldn't download the update: the update server answered HTTP 503.", downloadFailureMessage(e))
        }
        assertEquals(1_000L, part.length())
        assertFalse(apk.exists())
    }

    @Test fun cancelStopsAtOnceKeepsBytesAndResumes() = runBlocking {
        serve(slow = true) // ~160 KB/s: the whole file takes ~2 s
        val job = launch(Dispatchers.IO) { ResumableDownload(OkHttpClient()).fetch(url, part, apk) {} }
        delay(700)
        val t0 = System.nanoTime()
        withTimeout(1_500) { job.cancelAndJoinQuietly() }
        val stopMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("cancel took $stopMs ms", stopMs < 1_000)
        val kept = part.length()
        assertTrue("partial kept ($kept bytes)", kept in 1 until apkBytes.size)
        assertFalse(apk.exists())
        // The next run continues from the kept bytes.
        serve()
        ranges.clear()
        ResumableDownload(OkHttpClient()).fetch(url, part, apk) {}
        assertEquals(listOf<String?>("bytes=$kept-"), ranges)
        assertArrayEquals(apkBytes, apk.readBytes())
    }

    private suspend fun kotlinx.coroutines.Job.cancelAndJoinQuietly() {
        cancel()
        join()
    }
}
