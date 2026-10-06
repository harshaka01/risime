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
        assertEquals(240_000, RefreshTiming.refreshDelayMs(exp, nowElapsedMs = 1_000)) // 60 s early
        assertEquals(45_000, RefreshTiming.refreshDelayMs(exp, nowElapsedMs = 211_000)) // 90 s left → half
        assertEquals(5_000, RefreshTiming.refreshDelayMs(exp, nowElapsedMs = 295_000)) // floor
        assertEquals(0, RefreshTiming.refreshDelayMs(exp, nowElapsedMs = 400_000)) // already expired: now
        assertFalse(RefreshTiming.needsRefresh(exp, 1_000))
        assertTrue(RefreshTiming.needsRefresh(exp, 241_000))
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
        // update required beats everything, then blocked, then locked, then phone.
        assertEquals(AppGate.UPDATE_REQUIRED, appGate(true, unverifiedS, false, blocked, true))
        assertEquals(AppGate.BLOCKED, appGate(true, unverifiedS, false, blocked, false))
        assertEquals(AppGate.BLOCKED, appGate(true, null, false, blocked, false))
        assertEquals(AppGate.LOADING, appGate(false, null, false, null, false))
        assertEquals(AppGate.SIGNED_OUT, appGate(true, null, false, null, false))
        assertEquals(AppGate.LOCKED, appGate(true, unverifiedS, false, null, false))
        assertEquals(AppGate.CONFIRM_PHONE, appGate(true, unverifiedS, true, null, false))
        assertEquals(AppGate.CHATS, appGate(true, verified, true, null, false))
        assertEquals(AppGate.CHATS, appGate(true, dev, false, null, false)) // dev sessions never lock
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
}
