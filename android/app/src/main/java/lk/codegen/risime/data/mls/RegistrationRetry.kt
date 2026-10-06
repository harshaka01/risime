package lk.codegen.risime.data.mls

import kotlinx.coroutines.delay

/**
 * `PUT /me/devices` until it succeeds (P0 nightly.10: 15 of 21 registrations got 401 because they
 * ran while the OIDC session was still locked, and nothing retried). Exponential backoff from
 * [FIRST_MS] to [MAX_MS]; the caller cancels it when the session changes. A failure never logs
 * out or wipes anything.
 */
object RegistrationRetry {
    const val FIRST_MS = 5_000L
    const val MAX_MS = 10 * 60_000L

    /** Runs [attempt] until it returns true; returns the number of attempts made. */
    suspend fun untilDone(sleep: suspend (Long) -> Unit = { delay(it) }, attempt: suspend () -> Boolean): Int {
        var wait = FIRST_MS
        var n = 0
        while (true) {
            n++
            if (runCatching { attempt() }.getOrDefault(false)) return n
            sleep(wait)
            wait = (wait * 2).coerceAtMost(MAX_MS)
        }
    }
}

/** A registration that needs no retry: done, or not applicable (no Firebase, E2EE off). */
fun Registration.settled(): Boolean = this !is Registration.Failed
