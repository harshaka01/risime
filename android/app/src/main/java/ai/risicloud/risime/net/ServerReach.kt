package ai.risicloud.risime.net

import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * P0 2026-10-12 (bug B, Galaxy A15 on Wi-Fi): the sign-in screen's server check was ONE request with a 5-s
 * connect timeout and no retry, so a slow first connection (or a stale DNS answer after the server's IP moved)
 * ended in "Can't reach the server" while Chrome, with its own resolver and retries, loaded the page. The check
 * now retries with backoff ([ReachRetry]), resolves afresh on retries, and names the cause in one line.
 */
enum class ReachCause { OFFLINE, DNS, TIMEOUT, TLS, REFUSED, NETWORK, HTTP }

data class ReachFailure(val cause: ReachCause, val httpStatus: Int? = null) {
    /** The one small line under "Can't reach the server". */
    val line: String
        get() = when (cause) {
            ReachCause.OFFLINE -> "This phone is offline"
            ReachCause.DNS -> "Cause: DNS lookup failed"
            ReachCause.TIMEOUT -> "Cause: the server didn't answer in time"
            ReachCause.TLS -> "Cause: secure connection (TLS) failed"
            ReachCause.REFUSED -> "Cause: connection refused"
            ReachCause.NETWORK -> "Cause: network error"
            ReachCause.HTTP -> "Cause: HTTP $httpStatus"
        }
}

/** Why [e] happened. [online] false (no default network with internet) wins: that is the cause the user can fix. */
fun classifyReach(e: IOException, online: Boolean): ReachCause {
    if (!online) return ReachCause.OFFLINE
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth < 8) {
        when (t) {
            is UnknownHostException -> return ReachCause.DNS
            // SocketTimeoutException (connect/read) and OkHttp's call timeout ("timeout", "Canceled" after one).
            is InterruptedIOException -> return ReachCause.TIMEOUT
            is SSLException, is CertificateException -> return ReachCause.TLS
            is NoRouteToHostException -> return ReachCause.NETWORK
            is ConnectException -> return if (t.message.orEmpty().contains("timed out", ignoreCase = true)) ReachCause.TIMEOUT else ReachCause.REFUSED
        }
        t = t.cause
        depth++
    }
    return ReachCause.NETWORK
}

/** The failure an [ApiResult] stands for, or null when the server answered usefully (2xx, or 404 = a pre-v1.3 server). */
fun reachFailureOf(r: ApiResult<*>, online: Boolean): ReachFailure? = when (r) {
    is ApiResult.Ok -> null
    is ApiResult.Error -> if (r.httpStatus == 404) null else ReachFailure(ReachCause.HTTP, r.httpStatus)
    is ApiResult.NetworkError -> ReachFailure(classifyReach(r.cause, online))
}

/**
 * The retry policy: [delaysMs] between attempts (so `delaysMs.size + 1` attempts). Offline is not retried on a
 * timer (the caller retries when a network comes up); a 4xx other than 408/429 is an answer, not a reach problem.
 */
data class ReachRetry(val delaysMs: List<Long> = listOf(1_000, 2_000, 4_000)) {
    val attempts: Int get() = delaysMs.size + 1

    fun shouldRetry(f: ReachFailure, attempt: Int): Boolean {
        if (attempt >= attempts) return false
        return when (f.cause) {
            ReachCause.OFFLINE -> false
            ReachCause.HTTP -> f.httpStatus == 408 || f.httpStatus == 429 || (f.httpStatus ?: 0) >= 500
            else -> true
        }
    }

    fun delayAfter(attempt: Int): Long = delaysMs[(attempt - 1).coerceIn(0, delaysMs.lastIndex)]
}

data class ReachOutcome<T>(val result: ApiResult<T>, val failure: ReachFailure?, val attempts: Int)

/**
 * Runs [attempt] (1-based) until the server answers, the policy gives up, or the phone is offline. [onRetry] is
 * told about each failed attempt that will be retried (for the "still trying" line and the log).
 */
suspend fun <T> reachWithRetry(
    policy: ReachRetry,
    online: () -> Boolean,
    sleep: suspend (Long) -> Unit = { delay(it) },
    onRetry: (attempt: Int, failure: ReachFailure) -> Unit = { _, _ -> },
    attempt: suspend (n: Int) -> ApiResult<T>,
): ReachOutcome<T> {
    var n = 1
    while (true) {
        val r = attempt(n)
        val f = reachFailureOf(r, online()) ?: return ReachOutcome(r, null, n)
        if (!policy.shouldRetry(f, n)) return ReachOutcome(r, f, n)
        onRetry(n, f)
        sleep(policy.delayAfter(n))
        n++
    }
}
