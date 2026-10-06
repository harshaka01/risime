package lk.codegen.risime.data.mls

import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.auth.AuthManager
import lk.codegen.risime.data.auth.Envelope
import lk.codegen.risime.data.auth.OidcGateway
import lk.codegen.risime.data.auth.OidcTokens
import lk.codegen.risime.data.auth.RefreshResult
import lk.codegen.risime.data.auth.TokenVault
import lk.codegen.risime.data.auth.WrappingKey
import lk.codegen.risime.net.ApiClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import javax.crypto.Cipher

/**
 * P0 nightly.10: `PUT /me/devices` got 401 for most testers. Wired exactly like AppContainer
 * (bearer = the unlocked OIDC token, a 401 refreshes and retries once), against a server that
 * accepts only the current access token and records registered devices.
 */
class RegistrationAuthTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Key : WrappingKey {
        var pair: KeyPair? = null
        override fun ensure(): Boolean {
            if (pair == null) pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            return true
        }
        override fun exists() = pair != null
        override fun wrap(dataKey: ByteArray): ByteArray = Cipher.getInstance(Envelope.RSA_TRANSFORMATION)
            .run { init(Cipher.ENCRYPT_MODE, pair!!.public, Envelope.OAEP_SPEC); doFinal(dataKey) }
        override fun unwrapCipher(): Cipher? = pair?.let {
            Cipher.getInstance(Envelope.RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, it.private, Envelope.OAEP_SPEC) }
        }
        override fun delete() { pair = null }
    }

    private val refreshedWith = mutableListOf<String>()
    private val gateway = object : OidcGateway {
        override suspend fun refresh(issuer: String, clientId: String, refreshToken: String): RefreshResult {
            refreshedWith += refreshToken
            return RefreshResult.Ok(OidcTokens("a2", 300, "r2", null))
        }
        override suspend fun revoke(issuer: String, clientId: String, refreshToken: String) = Unit
    }

    /** The server: only the current access token ("a2") is valid; a PUT creates the device row. */
    private val devices = mutableSetOf<String>()
    private val auths = mutableListOf<String?>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val bearer = request.getHeader("Authorization")
                auths += bearer
                if (bearer != "Bearer a2") {
                    return MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                        .setBody("""{"error":{"code":"invalid_token","message":"Missing, invalid or expired token"}}""")
                }
                devices += request.path!!.substringAfterLast('/')
                return MockResponse().setResponseCode(204)
            }
        }
        start()
    }

    @After fun stop() = server.shutdown()

    private val auth by lazy { AuthManager(gateway, TokenVault(File(tmp.root, "t.bin"), Key()), { 0L }) }
    private val api by lazy {
        ApiClient(
            OkHttpClient(), { server.url("/").toString() },
            { if (auth.unlocked.value) auth.bearer() else null },
            onUnauthorized = { auth.unlocked.value && auth.bearer(forceRefresh = true) != null },
        )
    }
    private val registrar by lazy { DeviceRegistrar(api, { "dev-1" }, "0.2.0-nightly.11", { null }) }

    @Test fun expiredAccessTokenWithAValidRefreshTokenRegistersAfterTheRefresh() = runBlocking {
        // The access token is still "fresh" on the phone's clock but the server rejects it.
        auth.adopt("iss", "risime", OidcTokens("a1", 300, "r1", "id1"))
        assertEquals(Registration.PushOnly, registrar.register("fcm-token"))
        assertEquals(listOf("r1"), refreshedWith)
        assertEquals(listOf("Bearer a1", "Bearer a2"), auths)
        assertTrue("device row exists", "dev-1" in devices)
    }

    @Test fun lockedSessionSendsNoTokenSoTheLoopWaitsForTheUnlockThenRegisters() = runBlocking {
        // nightly.10: a locked OIDC session has no bearer → the PUT went out without one → 401, no retry.
        assertEquals(Registration.Failed("invalid_token"), registrar.register("fcm-token"))
        assertEquals(listOf<String?>(null), auths)
        // The fix: the loop retries with backoff; after the unlock the same attempt succeeds.
        val sleeps = mutableListOf<Long>()
        var attempt = 0
        val n = RegistrationRetry.untilDone(sleep = { sleeps += it }) {
            attempt++
            if (attempt == 3) auth.adopt("iss", "risime", OidcTokens("a2", 300, "r2", "id")) // unlocked
            auth.unlocked.value && registrar.register("fcm-token").settled()
        }
        assertEquals(3, n)
        assertEquals(listOf(5_000L, 10_000L), sleeps)
        assertTrue("device row exists", "dev-1" in devices)
    }

    @Test fun backoffIsCapped() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var left = 12
        RegistrationRetry.untilDone(sleep = { sleeps += it }) { --left == 0 }
        assertEquals(RegistrationRetry.MAX_MS, sleeps.last())
        assertTrue(sleeps.zipWithNext().all { (a, b) -> b >= a })
    }
}
