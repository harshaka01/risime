package lk.codegen.risime.data.auth

import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthConfig
import lk.codegen.risime.net.MeReply
import lk.codegen.risime.net.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AuthLogicTest {
    private val both = ApiResult.Ok(AuthConfig(listOf("oidc", "dev"), "https://risicloud.ai/realms/aoa", "risime"))
    private val oidcOnly = ApiResult.Ok(AuthConfig(listOf("oidc"), "https://risicloud.ai/realms/aoa", "risime"))
    private val devOnly = ApiResult.Ok(AuthConfig(listOf("dev")))

    private fun opts(r: ApiResult<AuthConfig>, debug: Boolean, o: AuthOverride = AuthOverride.AUTO) =
        (signInChoice(r, debug, o) as SignInChoice.Options).options

    @Test fun releaseShowsDevOnlyWhileOidcIsAbsent() {
        assertEquals(listOf(SignInOption.OIDC), opts(both, debug = false))
        assertEquals(listOf(SignInOption.OIDC), opts(oidcOnly, debug = false))
        assertEquals(listOf(SignInOption.DEV), opts(devOnly, debug = false))
        // The override is debug-only.
        assertEquals(listOf(SignInOption.OIDC), opts(both, debug = false, o = AuthOverride.FORCE_DEV))
    }

    @Test fun debugOffersBothAndHonoursOverride() {
        assertEquals(listOf(SignInOption.OIDC, SignInOption.DEV), opts(both, debug = true))
        assertEquals(listOf(SignInOption.DEV), opts(devOnly, debug = true))
        assertEquals(listOf(SignInOption.OIDC), opts(oidcOnly, debug = true))
        assertEquals(listOf(SignInOption.DEV), opts(both, debug = true, o = AuthOverride.FORCE_DEV))
        assertEquals(listOf(SignInOption.OIDC), opts(devOnly, debug = true, o = AuthOverride.FORCE_OIDC))
    }

    @Test fun oldServerAndFailures() {
        assertEquals(listOf(SignInOption.DEV), opts(ApiResult.Error(404, "http_404", ""), debug = false))
        assertTrue(signInChoice(ApiResult.NetworkError(IOException()), false) is SignInChoice.Unreachable)
        assertTrue(signInChoice(ApiResult.Error(500, "http_500", ""), false) is SignInChoice.Unreachable)
        // oidc listed without issuer/client_id is unusable; release then has nothing.
        val broken = ApiResult.Ok(AuthConfig(listOf("oidc")))
        assertTrue(signInChoice(broken, false) is SignInChoice.Unreachable)
    }

    @Test fun meErrorMapping() {
        val u = User("u", "+94", "H", "C")
        assertEquals(MeOutcome.Ok(u), meOutcome(ApiResult.Ok(MeReply(u))))
        assertEquals(MeOutcome.Unauthorized, meOutcome(ApiResult.Error(401, "invalid_token", "x")))
        val na = meOutcome(ApiResult.Error(403, "not_allowlisted", "Not on the list")) as MeOutcome.Refused
        assertEquals(Blocked(BlockKind.NOT_ALLOWLISTED, "Not on the list"), na.blocked)
        val ic = meOutcome(ApiResult.Error(409, "identity_conflict", "")) as MeOutcome.Refused
        assertEquals(BlockKind.IDENTITY_CONFLICT, ic.blocked.kind)
        assertTrue(ic.blocked.message.isNotBlank()) // fallback text when the server sends none
        assertEquals(MeOutcome.Unauthorized, meOutcome(ApiResult.Error(403, "forbidden", "")))
        assertTrue(meOutcome(ApiResult.Error(503, "http_503", "")) is MeOutcome.Transient)
        assertTrue(meOutcome(ApiResult.NetworkError(IOException())) is MeOutcome.Transient)
    }

    @Test fun refreshTimingFromExpiresIn() {
        val exp = RefreshTiming.expiresAt(receivedAtElapsedMs = 1_000, expiresInSec = 300) // 5 min token
        assertEquals(301_000, exp)
        // margin = max(120 s, 300 s / 3 = 100 s) = 120 s: refresh with 120 s left (at 181 s)
        assertEquals(180_000, RefreshTiming.refreshDelayMs(exp, nowElapsedMs = 1_000, lifetimeMs = 300_000))
        assertEquals(180_000, RefreshTiming.refreshDelayMs(exp, nowElapsedMs = 1_000)) // lifetime unknown: 120 s
        assertEquals(60_000, RefreshTiming.refreshDelayMs(exp, nowElapsedMs = 181_000, lifetimeMs = 300_000)) // 120 s left < 240 s: half
        assertEquals(5_000, RefreshTiming.refreshDelayMs(exp, nowElapsedMs = 295_000, lifetimeMs = 300_000)) // floor
        assertEquals(0, RefreshTiming.refreshDelayMs(exp, nowElapsedMs = 400_000, lifetimeMs = 300_000)) // already expired: now
        assertFalse(RefreshTiming.needsRefresh(exp, 1_000, 300_000))
        assertFalse(RefreshTiming.needsRefresh(exp, 180_000, 300_000)) // 121 s left
        assertTrue(RefreshTiming.needsRefresh(exp, 181_000, 300_000)) // 120 s left
    }

    @Test fun refreshMarginIsTheLargerOfTwoMinutesAndAThirdOfTheLifetime() {
        assertEquals(120_000, RefreshTiming.marginMs(300_000)) // 100 s < 120 s
        assertEquals(120_000, RefreshTiming.marginMs(360_000)) // exactly 120 s
        assertEquals(200_000, RefreshTiming.marginMs(600_000)) // 10 min token: a third
        assertEquals(1_200_000, RefreshTiming.marginMs(3_600_000)) // 1 h token: 20 min
        // 10-min token: refresh at 400 s, leaving 200 s for three retries
        val exp = RefreshTiming.expiresAt(0, 600)
        assertEquals(400_000, RefreshTiming.refreshDelayMs(exp, 0, 600_000))
        // the margin leaves room for the whole retry ladder plus a 5-s connect timeout each
        val worstCase = RefreshRetry.QUICK_MS.sum() + 4 * 5_000L
        assertTrue(RefreshTiming.marginMs(300_000) > worstCase)
    }

    @Test fun refreshRetryLadder() {
        assertEquals(listOf(1_000L, 3_000L, 7_000L, 15_000L, 30_000L, 30_000L, 30_000L), (1..7).map { RefreshRetry.delayMs(it) })
        assertEquals(1_000L, RefreshRetry.delayMs(0))
    }

    @Test fun reconnectBackoffStartsAtOneSecondCapsAtThirtyWithJitter() {
        // no jitter draw at the extremes: [base/2, base]
        for (attempt in 0..8) {
            val base = ReconnectBackoff.BASE_MS[attempt.coerceAtMost(ReconnectBackoff.BASE_MS.lastIndex)]
            assertEquals(base / 2, ReconnectBackoff.delayMs(attempt, random = { 0.0 }))
            val hi = ReconnectBackoff.delayMs(attempt, random = { 1.0 })
            assertEquals(base, hi)
            val rnd = java.util.Random(7)
            repeat(200) {
                val d = ReconnectBackoff.delayMs(attempt, random = { rnd.nextDouble() })
                assertTrue("attempt $attempt delay $d", d in base / 2..base)
            }
        }
        assertTrue(ReconnectBackoff.delayMs(0, random = { 1.0 }) <= 1_000)
        assertTrue(ReconnectBackoff.delayMs(99, random = { 1.0 }) <= 30_000)
    }

    @Test fun keycloakTimeouts() {
        assertEquals(5_000, KeycloakTimeouts.CONNECT_MS)
        assertEquals(10_000, KeycloakTimeouts.READ_MS)
    }

    @Test fun phoneVerificationMapping() {
        val unverified = User("u", "+94770000001", "H", "C", phoneVerified = false)
        assertEquals(MeOutcome.NeedsPhone(unverified), meOutcome(ApiResult.Ok(MeReply(unverified))))
        assertEquals(MeOutcome.NeedsPhone(null), meOutcome(ApiResult.Error(403, "phone_unverified", "Confirm your phone number to continue")))
        // Other 403s keep their meaning.
        assertTrue(meOutcome(ApiResult.Error(403, "not_allowlisted", "x")) is MeOutcome.Refused)
    }

    private val verified = lk.codegen.risime.data.Session("s", null, User("u", "+94", "H", "C"), lk.codegen.risime.data.AuthKind.OIDC)
    private val unverifiedS = verified.copy(user = verified.user.copy(phoneVerified = false))
    private val dev = verified.copy(token = "t", kind = lk.codegen.risime.data.AuthKind.DEV)
    private val blocked = Blocked(BlockKind.NOT_ALLOWLISTED, "x")

    @Test fun gateOrder() {
        val ready = SessionState.READY
        val none = SessionState.NONE
        // update required beats everything, then blocked, then the migration, then the app lock, then phone.
        assertEquals(AppGate.UPDATE_REQUIRED, appGate(true, unverifiedS, none, blocked, true))
        assertEquals(AppGate.BLOCKED, appGate(true, unverifiedS, none, blocked, false))
        assertEquals(AppGate.BLOCKED, appGate(true, null, none, blocked, false))
        assertEquals(AppGate.LOADING, appGate(false, null, none, null, false))
        assertEquals(AppGate.SIGNED_OUT, appGate(true, null, none, null, false))
        assertEquals(AppGate.MIGRATE, appGate(true, unverifiedS, SessionState.NEEDS_MIGRATION, null, false))
        assertEquals(AppGate.LOADING, appGate(true, verified, SessionState.RESTORING, null, false))
        assertEquals(AppGate.SIGNED_OUT, appGate(true, verified, none, null, false)) // never stuck: sign in (chats kept)
        assertEquals(AppGate.CONFIRM_PHONE, appGate(true, unverifiedS, ready, null, false))
        assertEquals(AppGate.CHATS, appGate(true, verified, ready, null, false))
        assertEquals(AppGate.CHATS, appGate(true, dev, none, null, false)) // dev sessions have no vault
        // Decision 064: the optional app lock is a UI gate for every session kind; unknown → wait.
        assertEquals(AppGate.APP_LOCKED, appGate(true, verified, ready, null, false, appLocked = true))
        assertEquals(AppGate.APP_LOCKED, appGate(true, dev, none, null, false, appLocked = true))
        assertEquals(AppGate.LOADING, appGate(true, verified, ready, null, false, appLocked = null))
        assertEquals(AppGate.SIGNED_OUT, appGate(true, null, none, null, false, appLocked = true))
        // Default off: after an update the app opens straight to the chats.
        assertEquals(AppGate.CHATS, appGate(true, verified, ready, null, false, appLocked = false))
    }

    @Test fun refreshTokenTypeAndExpiryAreDecodedWithoutTheToken() {
        fun jwt(payload: String) = "eyJhbGciOiJIUzI1NiJ9." +
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".sig"
        val off = refreshTokenInfo(jwt("""{"typ":"Offline","exp":0,"sub":"x"}"""))!!
        assertTrue(off.offline)
        assertEquals("typ=Offline exp=none", off.toString())
        val short = refreshTokenInfo(jwt("""{"typ":"Refresh","exp":1760000000}"""))!!
        assertFalse(short.offline)
        assertEquals("typ=Refresh exp=2025-10-09T08:53:20Z", short.toString())
        assertEquals(null, refreshTokenInfo("opaque-token"))
        assertEquals(null, refreshTokenInfo(null))
    }

    @Test fun socketOnlyWhenVerified() {
        assertTrue(shouldConnect(true, verified, true, null))
        assertFalse(shouldConnect(true, unverifiedS, true, null))
        assertFalse(shouldConnect(true, verified, false, null))
        assertFalse(shouldConnect(false, verified, true, null))
        assertFalse(shouldConnect(true, verified, true, blocked))
        assertTrue(shouldConnect(true, dev, false, null))
        assertFalse(shouldConnect(true, null, true, null))
    }

    // ---- §21 open sign-up (v1.20) ----

    @Test fun signupRequiredIsItsOwnRefusal() {
        val o = meOutcome(ApiResult.Error(403, "signup_required", "")) as MeOutcome.Refused
        assertEquals(BlockKind.SIGNUP_REQUIRED, o.blocked.kind)
        assertTrue(o.blocked.message.isNotBlank())
        // not_allowlisted is unchanged.
        assertEquals(BlockKind.NOT_ALLOWLISTED, (meOutcome(ApiResult.Error(403, "not_allowlisted", "x")) as MeOutcome.Refused).blocked.kind)
    }

    @Test fun normalisesSignupPhones() {
        assertEquals("+94771234567", normalisePhone("+94", "077 123 4567"))
        assertEquals("+94771234567", normalisePhone("+94", "771234567"))
        assertEquals("+94771234567", normalisePhone("94", "(077) 123-4567"))
        assertEquals("+447700900123", normalisePhone("+94", "+44 7700 900123"))
        assertEquals("+447700900123", normalisePhone("+94", "0044 7700 900123"))
        assertEquals(null, normalisePhone("+94", ""))
        assertEquals(null, normalisePhone("+94", "12"))
        assertEquals(null, normalisePhone("", "0771234567"))
        assertEquals(null, normalisePhone("+94", "07712abc67"))
        assertEquals(null, normalisePhone("+94", "+0771234567"))
    }

    @Test fun signupFormChecks() {
        assertEquals(null, signupFormError("Kamal", "+94771234567"))
        assertTrue(signupFormError("  ", "+94771234567")!!.contains("name"))
        assertTrue(signupFormError("x".repeat(65), "+94771234567") != null)
        assertTrue(signupFormError("Kamal", null)!!.contains("number"))
    }

    @Test fun signupOutcomes() {
        val u = User("u", "+94771234567", "Kamal", "", phoneConfirmed = false)
        assertEquals(SignupOutcome.Created(u), signupOutcome(ApiResult.Ok(MeReply(u))))
        assertTrue((signupOutcome(ApiResult.Error(409, "phone_taken", "m")) as SignupOutcome.FieldError).onPhone)
        assertFalse((signupOutcome(ApiResult.Error(400, "bad_request", "m")) as SignupOutcome.FieldError).onPhone)
        assertTrue((signupOutcome(ApiResult.Error(429, "rate_limited", "", retryAfterSec = 1800)) as SignupOutcome.Failed).message.contains("30 min"))
        assertTrue((signupOutcome(ApiResult.Error(429, "rate_limited", "")) as SignupOutcome.Failed).message.contains("1 min"))
        assertEquals(SignupOutcome.Closed("closed"), signupOutcome(ApiResult.Error(403, "signup_closed", "closed")))
        assertEquals(SignupOutcome.Unauthorized, signupOutcome(ApiResult.Error(401, "invalid_token", "")))
        assertEquals(SignupOutcome.Unauthorized, signupOutcome(ApiResult.Error(409, "identity_conflict", "")))
        assertTrue(signupOutcome(ApiResult.NetworkError(IOException("x"))) is SignupOutcome.Failed)
    }

    @Test fun waitLabels() {
        assertEquals("45 s", waitLabel(45))
        assertEquals("2 min", waitLabel(61))
        assertEquals("3 h", waitLabel(3 * 3600))
    }

    private fun jwt(payload: String): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{\"alg\":\"RS256\"}".toByteArray()) + "." + enc.encodeToString(payload.toByteArray()) + ".sig"
    }

    @Test fun nameClaimPrefill() {
        assertEquals("Kamal Perera", nameClaim(jwt("{\"name\":\" Kamal Perera \",\"email\":\"k@example.com\"}")))
        assertEquals("ශ්‍රී ලංකා", nameClaim(jwt("{\"name\":\"ශ්‍රී ලංකා\"}")))
        assertEquals(64, nameClaim(jwt("{\"name\":\"${"n".repeat(100)}\"}"))!!.length)
        assertEquals(null, nameClaim(jwt("{\"email\":\"k@example.com\"}")))
        assertEquals(null, nameClaim(jwt("{\"name\":42}")))
        assertEquals(null, nameClaim(jwt("{\"name\":\"  \"}")))
        assertEquals(null, nameClaim(null))
        assertEquals(null, nameClaim("garbage"))
        assertEquals(null, nameClaim("a.%%%.c"))
    }
}
