package lk.codegen.risime.data.auth

import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthConfig
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.MeReply
import lk.codegen.risime.net.User
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

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
enum class BlockKind {
    NOT_ALLOWLISTED,
    IDENTITY_CONFLICT,

    /** §21 (v1.20): open sign-up is on and nothing maps: show "Create your RisiMe account". */
    SIGNUP_REQUIRED,
}

data class Blocked(val kind: BlockKind, val message: String, val email: String? = null)

sealed interface MeOutcome {
    data class Ok(val user: User) : MeOutcome

    /** 401: refresh once (OIDC) and retry, then a browser sign-in; dev tokens go back to login. Data kept. */
    data object Unauthorized : MeOutcome

    data class Refused(val blocked: Blocked) : MeOutcome

    /**
     * §7: signed in, but the allowlisted phone isn't confirmed yet (GET /me with
     * `phone_verified: false`, or `403 phone_unverified` anywhere). Show "Confirm your phone",
     * don't connect, keep data. [user] is null when only the 403 is known.
     */
    data class NeedsPhone(val user: User?) : MeOutcome

    /** Network or server trouble: keep going (reconnect with backoff). */
    data class Transient(val reason: String) : MeOutcome
}

fun meOutcome(r: ApiResult<MeReply>): MeOutcome = when (r) {
    is ApiResult.Ok -> if (r.value.user.phoneVerified) MeOutcome.Ok(r.value.user) else MeOutcome.NeedsPhone(r.value.user)
    is ApiResult.NetworkError -> MeOutcome.Transient("network")
    is ApiResult.Error -> when {
        r.httpStatus == 401 -> MeOutcome.Unauthorized
        r.httpStatus == 403 && r.code == AuthErrors.PHONE_UNVERIFIED -> MeOutcome.NeedsPhone(null)
        r.httpStatus == 403 && r.code == AuthErrors.SIGNUP_REQUIRED ->
            MeOutcome.Refused(Blocked(BlockKind.SIGNUP_REQUIRED, r.message.ifBlank { "Create your RisiMe account to continue" }))
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

/** Top-level screen, in priority order (decision 020 / contract §7). */
enum class AppGate { LOADING, UPDATE_REQUIRED, BLOCKED, SIGNED_OUT, LOCKED, CONFIRM_PHONE, CHATS }

/**
 * update required → blocked (403/409) → locked (fingerprint) → confirm phone → chats.
 * [session] is null when signed out; [sessionLoaded] false while DataStore hasn't answered yet.
 */
fun appGate(
    sessionLoaded: Boolean,
    session: lk.codegen.risime.data.Session?,
    unlocked: Boolean,
    blocked: Blocked?,
    updateRequired: Boolean,
): AppGate = when {
    updateRequired -> AppGate.UPDATE_REQUIRED
    blocked != null -> AppGate.BLOCKED
    !sessionLoaded -> AppGate.LOADING
    session == null -> AppGate.SIGNED_OUT
    session.kind == lk.codegen.risime.data.AuthKind.OIDC && !unlocked -> AppGate.LOCKED
    !session.user.phoneVerified -> AppGate.CONFIRM_PHONE
    else -> AppGate.CHATS
}

/** The socket runs only for a signed-in, unlocked, verified, unblocked session in the foreground. */
fun shouldConnect(foreground: Boolean, session: lk.codegen.risime.data.Session?, unlocked: Boolean, blocked: Blocked?): Boolean =
    foreground && blocked == null && session != null && session.user.phoneVerified &&
        (session.kind == lk.codegen.risime.data.AuthKind.DEV || unlocked)

// ---- §21 open sign-up (v1.20) ----

private val E164 = Regex("^\\+[1-9]\\d{6,14}$")

/**
 * The sign-up phone as E.164, or null. [number] may already be international (`+…` or `00…`);
 * otherwise it is national: spaces, dashes, dots and brackets are dropped, then one trunk `0`,
 * and [countryCode] (`+94`, `94`) goes in front.
 */
fun normalisePhone(countryCode: String, number: String): String? {
    val digits = number.trim().replace(Regex("[\\s().-]"), "")
    if (digits.isEmpty()) return null
    val e164 = when {
        digits.startsWith("+") -> digits
        digits.startsWith("00") -> "+" + digits.drop(2)
        else -> {
            val cc = countryCode.trim().replace(Regex("[\\s().-]"), "").removePrefix("+")
            if (cc.isEmpty() || !cc.all(Char::isDigit)) return null
            "+" + cc + digits.removePrefix("0")
        }
    }
    return e164.takeIf { E164.matches(it) }
}

/** Local checks before `POST /auth/signup`; the server validates again. Null = OK. */
fun signupFormError(displayName: String, phoneE164: String?): String? = when {
    displayName.trim().isEmpty() || displayName.trim().length > 64 -> "Enter your name (1–64 characters)"
    phoneE164 == null -> "Enter a valid mobile number"
    else -> null
}

/** What the sign-up screen does with an answer (§21.3, §21.8). */
sealed interface SignupOutcome {
    data class Created(val user: User) : SignupOutcome
    data class FieldError(val message: String, val onPhone: Boolean) : SignupOutcome
    data class Failed(val message: String) : SignupOutcome

    /** 403 signup_closed: switch to the not-allowlisted screen. */
    data class Closed(val message: String) : SignupOutcome

    /** Another 403/409 from mapping, or 401: show it as a blocked account / sign in again. */
    data object Unauthorized : SignupOutcome
}

fun signupOutcome(r: ApiResult<MeReply>): SignupOutcome = when (r) {
    is ApiResult.Ok -> SignupOutcome.Created(r.value.user)
    is ApiResult.NetworkError -> SignupOutcome.Failed("Can't reach the RisiMe server. Try again.")
    is ApiResult.Error -> when {
        r.code == AuthErrors.PHONE_TAKEN ->
            SignupOutcome.FieldError("This phone number can't be used for a new account. If it's yours, ask the person who invited you or an admin.", onPhone = true)
        r.code == AuthErrors.BAD_REQUEST -> SignupOutcome.FieldError("Check your name and phone number", onPhone = false)
        r.httpStatus == 429 -> SignupOutcome.Failed("Too many sign-up attempts. Try again in ${waitLabel(r.retryAfterSec ?: 60L)}.")
        r.code == AuthErrors.SIGNUP_CLOSED -> SignupOutcome.Closed(r.message.ifBlank { "RisiMe sign-up is by invitation only right now" })
        r.httpStatus == 401 || r.httpStatus == 403 || r.httpStatus == 409 -> SignupOutcome.Unauthorized
        else -> SignupOutcome.Failed("Something went wrong (${r.code}). Try again.")
    }
}

/** "45 s", "12 min", "3 h" (a Retry-After). */
fun waitLabel(seconds: Long): String = when {
    seconds < 60 -> "${seconds.coerceAtLeast(1)} s"
    seconds < 3600 -> "${(seconds + 59) / 60} min"
    else -> "${(seconds + 3599) / 3600} h"
}

private val ClaimsJson = Json { ignoreUnknownKeys = true }

/** The `name` claim of a JWT (ID or access token), to prefill the display name. Null when absent or unreadable. */
fun nameClaim(jwt: String?): String? = runCatching {
    val payload = jwt!!.split('.')[1]
    val bytes = java.util.Base64.getUrlDecoder().decode(payload.trimEnd('='))
    val obj: JsonObject = ClaimsJson.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
    (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
}.getOrNull()
