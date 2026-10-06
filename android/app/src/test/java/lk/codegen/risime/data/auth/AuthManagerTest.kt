package lk.codegen.risime.data.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import javax.crypto.Cipher

class AuthManagerTest {
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

    private class Gateway : OidcGateway {
        var next: RefreshResult = RefreshResult.Ok(OidcTokens("a2", 300, "r2", null))
        val refreshedWith = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        override suspend fun refresh(issuer: String, clientId: String, refreshToken: String): RefreshResult {
            refreshedWith += refreshToken
            return next
        }
        override suspend fun revoke(issuer: String, clientId: String, refreshToken: String) {
            revoked += refreshToken
        }
    }

    private var now = 0L
    private val gw = Gateway()
    private val pushed = mutableListOf<String>()
    private val vault by lazy { TokenVault(File(tmp.root, "t.bin"), Key()) }
    private fun manager() = AuthManager(gw, vault, { now }, { pushed += it })

    @Test fun adoptKeepsAccessTokenUntilMarginThenRefreshesAndPushes() = runTest {
        val m = manager()
        assertTrue(m.adopt("iss", "risime", OidcTokens("a1", 300, "r1", "id1")))
        assertTrue(m.unlocked.value)
        assertEquals("a1", m.bearer())
        assertEquals(240_000L, m.refreshDueInMs())
        now = 241_000 // inside the 60 s margin
        assertEquals("a2", m.bearer())
        assertEquals(listOf("r1"), gw.refreshedWith)
        assertEquals(listOf("a2"), pushed) // → auth:refresh on the socket
        assertTrue(vault.hasTokens())
        // The rotated refresh token was sealed without a prompt; opening needs the key.
        assertEquals("r2", vault.open(vault.unlockCipher()!!)!!.refreshToken)
    }

    @Test fun networkFailureKeepsAValidTokenInvalidGrantEndsTheSession() = runTest {
        val m = manager()
        m.adopt("iss", "risime", OidcTokens("a1", 300, "r1", null))
        gw.next = RefreshResult.Failed("offline")
        assertEquals("a1", m.bearer(forceRefresh = true))
        now = 400_000 // expired and still offline
        assertNull(m.bearer())
        gw.next = RefreshResult.InvalidGrant
        assertNull(m.bearer(forceRefresh = true))
        assertFalse(m.unlocked.value)
        assertTrue(m.signInNeeded.value)
        assertFalse(vault.hasTokens())
    }

    @Test fun unlockAfterProcessRestartNeedsTheAuthenticatedCipher() = runTest {
        manager().adopt("iss", "risime", OidcTokens("a1", 300, "r1", "id1"))
        val fresh = manager() // new process: nothing in memory
        assertFalse(fresh.unlocked.value)
        assertNull(fresh.bearer())
        assertTrue(fresh.canUnlock())
        assertTrue(fresh.unlock(fresh.unlockCipher()!!))
        assertEquals("a2", fresh.bearer())
        assertEquals("id1", fresh.idToken())
    }

    @Test fun signOutRevokesAndForgets() = runTest {
        val m = manager()
        m.adopt("iss", "risime", OidcTokens("a1", 300, "r1", null))
        m.signOut()
        assertEquals(listOf("r1"), gw.revoked)
        assertFalse(m.unlocked.value)
        assertFalse(vault.hasTokens())
        assertNull(m.bearer())
    }

    @Test fun lockedSignOutRevokesOnlyWhenUnlocked() = runTest {
        manager().adopt("iss", "risime", OidcTokens("a1", 300, "r1", null))
        val m = manager()
        m.revokeStored(null) // prompt cancelled: key deleted anyway
        assertTrue(gw.revoked.isEmpty())
        assertFalse(vault.hasTokens())
    }
}
