package ai.risicloud.risime.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** P0 2026-10-12 (bug B): the sign-in check's cause line and retry policy. */
class ServerReachTest {
    private val cfg = AuthConfig(listOf("dev"))

    @Test fun causesAreClassified() {
        assertEquals(ReachCause.DNS, classifyReach(UnknownHostException("risime.risicloud.ai"), true))
        assertEquals(ReachCause.TIMEOUT, classifyReach(SocketTimeoutException("connect timed out"), true))
        assertEquals(ReachCause.TIMEOUT, classifyReach(InterruptedIOException("timeout"), true)) // OkHttp callTimeout
        assertEquals(ReachCause.TIMEOUT, classifyReach(ConnectException("Connection timed out"), true))
        assertEquals(ReachCause.REFUSED, classifyReach(ConnectException("Failed to connect to /203.0.113.1:443"), true))
        assertEquals(ReachCause.TLS, classifyReach(SSLHandshakeException("handshake"), true))
        assertEquals(ReachCause.TLS, classifyReach(SSLPeerUnverifiedException("Hostname not verified"), true))
        assertEquals(ReachCause.TLS, classifyReach(IOException("wrapped", CertificateException("bad chain")), true))
        assertEquals(ReachCause.DNS, classifyReach(IOException("wrapped", UnknownHostException("x")), true))
        assertEquals(ReachCause.NETWORK, classifyReach(NoRouteToHostException(), true))
        assertEquals(ReachCause.NETWORK, classifyReach(IOException("unexpected end of stream"), true))
    }

    @Test fun offlineWinsOverTheException() {
        assertEquals(ReachCause.OFFLINE, classifyReach(UnknownHostException("x"), online = false))
        assertEquals(ReachCause.OFFLINE, classifyReach(SocketTimeoutException(), online = false))
    }

    @Test fun oneShortLinePerCause() {
        assertEquals("This phone is offline", ReachFailure(ReachCause.OFFLINE).line)
        assertEquals("Cause: DNS lookup failed", ReachFailure(ReachCause.DNS).line)
        assertEquals("Cause: the server didn't answer in time", ReachFailure(ReachCause.TIMEOUT).line)
        assertEquals("Cause: secure connection (TLS) failed", ReachFailure(ReachCause.TLS).line)
        assertEquals("Cause: HTTP 502", ReachFailure(ReachCause.HTTP, 502).line)
        ReachCause.entries.forEach { assertTrue(it.name, ReachFailure(it, 500).line.length <= 45) }
    }

    @Test fun answersAreNotFailures() {
        assertNull(reachFailureOf(ApiResult.Ok(cfg), true))
        assertNull(reachFailureOf(ApiResult.Error(404, "http_404", ""), true)) // a pre-v1.3 server: dev login only
        assertEquals(ReachFailure(ReachCause.HTTP, 503), reachFailureOf(ApiResult.Error(503, "http_503", ""), true))
        assertEquals(ReachFailure(ReachCause.OFFLINE), reachFailureOf(ApiResult.NetworkError(UnknownHostException()), false))
    }

    @Test fun policyRetriesReachProblemsNotAnswersOrOffline() {
        val p = ReachRetry(listOf(1_000, 2_000, 4_000))
        assertEquals(4, p.attempts)
        assertTrue(p.shouldRetry(ReachFailure(ReachCause.TIMEOUT), 1))
        assertTrue(p.shouldRetry(ReachFailure(ReachCause.DNS), 3))
        assertFalse(p.shouldRetry(ReachFailure(ReachCause.DNS), 4)) // the last attempt
        assertTrue(p.shouldRetry(ReachFailure(ReachCause.TLS), 1))
        assertTrue(p.shouldRetry(ReachFailure(ReachCause.HTTP, 503), 1))
        assertTrue(p.shouldRetry(ReachFailure(ReachCause.HTTP, 429), 1))
        assertFalse(p.shouldRetry(ReachFailure(ReachCause.HTTP, 403), 1))
        assertFalse(p.shouldRetry(ReachFailure(ReachCause.OFFLINE), 1)) // retried when a network comes up
        assertEquals(listOf(1_000L, 2_000L, 4_000L), (1..3).map(p::delayAfter))
    }

    @Test fun aSlowFirstAttemptIsRetriedWithBackoff() = runBlocking {
        val slept = mutableListOf<Long>()
        val retried = mutableListOf<Pair<Int, ReachCause>>()
        val results = ArrayDeque(
            listOf<ApiResult<AuthConfig>>(
                ApiResult.NetworkError(SocketTimeoutException("timeout")),
                ApiResult.NetworkError(UnknownHostException("x")),
                ApiResult.Ok(cfg),
            ),
        )
        val o = reachWithRetry(ReachRetry(), { true }, { slept += it }, { n, f -> retried += n to f.cause }) { results.removeFirst() }
        assertEquals(ApiResult.Ok(cfg), o.result)
        assertNull(o.failure)
        assertEquals(3, o.attempts)
        assertEquals(listOf(1_000L, 2_000L), slept)
        assertEquals(listOf(1 to ReachCause.TIMEOUT, 2 to ReachCause.DNS), retried)
    }

    @Test fun givesUpAfterTheLastAttemptWithTheLastCause() = runBlocking {
        val calls = mutableListOf<Int>()
        val o = reachWithRetry<AuthConfig>(ReachRetry(listOf(1, 1)), { true }, {}) { n -> calls += n; ApiResult.NetworkError(SocketTimeoutException()) }
        assertEquals(listOf(1, 2, 3), calls)
        assertEquals(ReachFailure(ReachCause.TIMEOUT), o.failure)
    }

    @Test fun offlineStopsAtOnceAndAnHttpAnswerIsNotRetried() = runBlocking {
        var n = 0
        val off = reachWithRetry<AuthConfig>(ReachRetry(), { false }, {}) { n++; ApiResult.NetworkError(UnknownHostException()) }
        assertEquals(1, n)
        assertEquals("This phone is offline", off.failure!!.line)
        val forbidden = reachWithRetry<AuthConfig>(ReachRetry(), { true }, {}) { ApiResult.Error(403, "forbidden", "") }
        assertEquals(1, forbidden.attempts)
        assertEquals("Cause: HTTP 403", forbidden.failure!!.line)
    }

    /** End to end over HTTP: the first answer stalls past the call timeout, the retry gets the config. */
    @Test fun aStalledFirstResponseStillReachesTheServer() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("""{"modes":["dev"]}"""))
        server.start()
        try {
            val client = OkHttpClient.Builder().callTimeout(500, TimeUnit.MILLISECONDS).build()
            val api = ApiClient(client, { server.url("/").toString() }, { null })
            val o = reachWithRetry(ReachRetry(listOf(10, 10)), { true }, {}) { api.authConfig(client) }
            assertEquals(2, o.attempts)
            assertEquals(listOf("dev"), (o.result as ApiResult.Ok).value.modes)
        } finally {
            server.shutdown()
        }
    }
}
