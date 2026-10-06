package lk.codegen.risime.data.auth

import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthConfig
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.MeReply
import lk.codegen.risime.net.User

/** Pure sign-in rules from contract v1.3 §6 and decision 014 (JVM-tested). */

enum class SignInOption { OIDC, DEV }

/** Debug-only override in Settings (ignored in release builds). */
enum class AuthOverride { AUTO, FORCE_OIDC, FORCE_DEV }

/** What the login screen offers, from `GET /auth/config`. */
sealed interface SignInChoice {
    data class Options(val options: List<SignInOption>, val config: AuthConfig) : SignInChoice

    /** Couldn't reach the server (or it answered garbage): show an error with "Retry". */
    data class Unreachable(val message: String) : SignInChoice
}

/**
 * - Release: RisiCloud when `oidc` is listed; the dev OTP login only while `oidc` is absent.
 * - Debug: RisiCloud when listed, plus "Developer sign-in (OTP)" whenever `dev` is listed; the
 *   Settings override can force either one.
 * - A pre-v1.3 server answers 404: it only has the dev login.
 */
fun signInChoice(result: ApiResult<AuthConfig>, debug: Boolean, override: AuthOverride = AuthOverride.AUTO): SignInChoice {
    val config = when (result) {
        is ApiResult.Ok -> result.value
        is ApiResult.Error ->
            if (result.httpStatus == 404) AuthConfig(listOf(AuthConfig.MODE_DEV)) else return SignInChoice.Unreachable("Server error (${result.code})")
        is ApiResult.NetworkError -> return SignInChoice.Unreachable("Can't reach the server")
    }
    val oidc = AuthConfig.MODE_OIDC in config.modes && !config.issuer.isNullOrBlank() && !config.clientId.isNullOrBlank()
    val dev = AuthConfig.MODE_DEV in config.modes
    val options = if (debug) {
        when (override) {
            AuthOverride.FORCE_OIDC -> listOf(SignInOption.OIDC)
            AuthOverride.FORCE_DEV -> listOf(SignInOption.DEV)
            AuthOverride.AUTO -> listOfNotNull(SignInOption.OIDC.takeIf { oidc }, SignInOption.DEV.takeIf { dev })
        }
    } else {
        when {
            oidc -> listOf(SignInOption.OIDC)
            dev -> listOf(SignInOption.DEV)
            else -> emptyList()
        }
    }
    if (options.isEmpty()) return SignInChoice.Unreachable("This server offers no sign-in this app supports")
    return SignInChoice.Options(options, config)
}

/** Why the server refused this account (§6.1); the message may be shown verbatim. */
enum class BlockKind { NOT_ALLOWLISTED, IDENTITY_CONFLICT }

data class Blocked(val kind: BlockKind, val message: String, val email: String? = null)

sealed interface MeOutcome {
    data class Ok(val user: User) : MeOutcome

    /** 401: refresh once (OIDC) and retry, then a browser sign-in; dev tokens go back to login. Data kept. */
    data object Unauthorized : MeOutcome

    data class Refused(val blocked: Blocked) : MeOutcome

    /** Network or server trouble: keep going (reconnect with backoff). */
    data class Transient(val reason: String) : MeOutcome
}

fun meOutcome(r: ApiResult<MeReply>): MeOutcome = when (r) {
    is ApiResult.Ok -> MeOutcome.Ok(r.value.user)
    is ApiResult.NetworkError -> MeOutcome.Transient("network")
    is ApiResult.Error -> when {
        r.httpStatus == 401 -> MeOutcome.Unauthorized
        r.httpStatus == 403 && r.code == AuthErrors.NOT_ALLOWLISTED ->
            MeOutcome.Refused(Blocked(BlockKind.NOT_ALLOWLISTED, r.message.ifBlank { "This email is not on the RisiMe allowlist" }))
        r.httpStatus == 409 && r.code == AuthErrors.IDENTITY_CONFLICT ->
            MeOutcome.Refused(Blocked(BlockKind.IDENTITY_CONFLICT, r.message.ifBlank { "This phone number is linked to another RisiCloud account" }))
        r.httpStatus == 403 -> MeOutcome.Unauthorized
        else -> MeOutcome.Transient(r.code)
    }
}

/**
 * §6.2: refresh about [marginMs] before expiry, computed from `expires_in` at receipt on the
 * monotonic clock (never the device wall clock against `exp`). Never sooner than [minDelayMs]
 * (very short tokens), never negative.
 */
object RefreshTiming {
    const val MARGIN_MS = 60_000L
    const val MIN_DELAY_MS = 5_000L

    /** Monotonic deadline (elapsedRealtime ms) after which the access token is considered expired. */
    fun expiresAt(receivedAtElapsedMs: Long, expiresInSec: Long): Long = receivedAtElapsedMs + expiresInSec * 1_000

    fun refreshDelayMs(
        expiresAtElapsedMs: Long,
        nowElapsedMs: Long,
        marginMs: Long = MARGIN_MS,
        minDelayMs: Long = MIN_DELAY_MS,
    ): Long {
        val lifetimeLeft = expiresAtElapsedMs - nowElapsedMs
        // For tokens shorter than twice the margin, refresh at half their remaining life instead.
        val target = if (lifetimeLeft < 2 * marginMs) lifetimeLeft / 2 else lifetimeLeft - marginMs
        return target.coerceAtLeast(minDelayMs.coerceAtMost(lifetimeLeft.coerceAtLeast(0)))
    }

    /** Is the token too close to expiry to start a new request/connection with? */
    fun needsRefresh(expiresAtElapsedMs: Long, nowElapsedMs: Long, marginMs: Long = MARGIN_MS): Boolean =
        expiresAtElapsedMs - nowElapsedMs <= marginMs
}
