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
}
