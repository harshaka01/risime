package lk.codegen.risime.ui.phone

import lk.codegen.risime.net.ApiResult

/**
 * Shared rules for every one-time-code screen (dev OTP login §1, SMS phone verification §7):
 * deadlines are wall-clock timestamps (they survive rotation and process death when saved), they
 * only ever move later (never a client guess shorter than the server's), and a 429 without a
 * `Retry-After` falls back to [RESEND_FALLBACK_SEC] (contract §7.1).
 */
object CodeLimits {
    /** Seconds left until [untilMs] (rounded up), 0 when passed. */
    fun secondsLeft(untilMs: Long, nowMs: Long): Int =
        if (untilMs <= nowMs) 0 else ((untilMs - nowMs + 999) / 1000).toInt()

    /** "0:42", "14:05". */
    fun countdown(sec: Int): String = "%d:%02d".format(sec.coerceAtLeast(0) / 60, sec.coerceAtLeast(0) % 60)

    /** A new deadline [sec] from [nowMs], never earlier than [current]. */
    fun later(current: Long, nowMs: Long, sec: Long): Long = maxOf(current, nowMs + sec.coerceAtLeast(0) * 1000)

    /** The server's wait for a 429 (Retry-After), else the contract's 60 s fallback. */
    fun retryAfterSec(r: ApiResult.Error): Long = r.retryAfterSec?.takeIf { it > 0 } ?: RESEND_FALLBACK_SEC.toLong()

    /** The warning shown with a wrong code: "Wrong code — 1 attempt left" etc. */
    fun wrongCode(attemptsLeft: Int?): String = "Wrong code" + when (attemptsLeft) {
        null -> ""
        0 -> " — no attempts left. Send a new code."
        1 -> " — 1 attempt left"
        else -> " — $attemptsLeft attempts left"
    }
}
