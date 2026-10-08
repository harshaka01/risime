package lk.codegen.risime.data.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Decision 064: the token vault without user authentication, the one-time migration, sign-out triggers. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class AuthManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The pre-064 auth-bound RSA key (a plain JVM pair: "authenticated" = we hand out the cipher). */
    private class OldKey : WrappingKey {
        var pair: KeyPair? = null
        var invalidated = false
        override fun ensure(): Boolean {
            if (pair == null) pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            return true
        }
        override fun exists() = pair != null
        override fun wrap(dataKey: ByteArray): ByteArray = Cipher.getInstance(Envelope.RSA_TRANSFORMATION)
            .run { init(Cipher.ENCRYPT_MODE, pair!!.public, Envelope.OAEP_SPEC); doFinal(dataKey) }
        override fun unwrapCipher(): Cipher? {
            if (invalidated) { // KeyPermanentlyInvalidatedException: the key is deleted
                pair = null
                return null
            }
            return pair?.let { Cipher.getInstance(Envelope.RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, it.private, Envelope.OAEP_SPEC) } }
        }
        override fun delete() { pair = null }
    }

    /** The 064 AES key (software here; Keystore StrongBox/TEE on the phone). No authentication. */
    private class NewKey : VaultKey {
        override val alias = "risime_session_aes"
        var key: SecretKey? = null
        var canCreate = true
        override fun get() = key
        override fun getOrCreate(): SecretKey? {
            if (key == null && canCreate) key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            return key
        }
        override fun delete() { key = null }
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

    private class Diag : AuthDiagnostics {
        val signedIn = mutableListOf<RefreshTokenInfo?>()
        override fun signedIn(info: RefreshTokenInfo?) { signedIn += info }
        override fun signedOut(trigger: SignOutTrigger) = Unit
    }

    private var now = 0L
    private val gw = Gateway()
    private val pushed = mutableListOf<String>()
    private val oldKey = OldKey()
    private val newKey = NewKey()
    private val diag = Diag()
    private val vault by lazy { SessionVault(File(tmp.root, "session.bin"), newKey) }
    private val legacy by lazy { TokenVault(File(tmp.root, "tokens.bin"), oldKey) }

    /** A new process: nothing in memory, the same files and keys. */
    private fun manager() = AuthManager(gw, vault, legacy, { now }, { pushed += it }, diag)

    private fun jwt(payload: String) = "h." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".s"

    @Test fun vaultRoundTripNeedsNoAuthenticationAndRejectsTamperingAndOtherKeys() {
        val t = StoredTokens("r1", "id1", "iss", "risime")
        assertTrue(vault.store(t))
        assertEquals(SessionVault.Load.Tokens(t), vault.load())
        // Fresh IV per write: the same tokens never give the same blob.
        val f = File(tmp.root, "session.bin")
        val first = f.readBytes()
        vault.store(t)
        assertFalse(first.contentEquals(f.readBytes()))
        // Flipped bit → GCM refuses.
        val blob = f.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        f.writeBytes(blob)
        assertTrue(vault.load() is SessionVault.Load.Unreadable)
        // AAD binds the alias + version: a blob sealed under another alias doesn't open.
        val other = SessionVault.seal("x".toByteArray(), newKey.key!!, "other_alias")
        f.writeBytes(other)
        assertTrue(vault.load() is SessionVault.Load.Unreadable)
        vault.clear()
        assertEquals(SessionVault.Load.Empty, vault.load())
        assertNull(newKey.key)
    }

    @Test fun aSessionSurvivesProcessDeathWithNoPromptAndRefreshesInTheBackground() = runTest {
        val m = manager()
        assertTrue(m.adopt("iss", "risime", OidcTokens("a1", 300, jwt("""{"typ":"Offline","exp":0}"""), "id1")))
        assertEquals(SessionState.READY, m.state.value)
        assertEquals("Offline", diag.signedIn.single()!!.typ)
        assertEquals("a1", m.bearer())
        // New process (a push wake-up): no UI, no cipher, the session is there.
        val fresh = manager()
        assertEquals(SessionState.RESTORING, fresh.state.value)
        assertTrue(fresh.hasSession())
        assertEquals("a2", fresh.bearer()) // the first bearer refreshes (no access token in the vault)
        assertEquals("id1", fresh.idToken())
        assertEquals(listOf("a2"), pushed)
        // The rotated refresh token is sealed for the next process.
        assertEquals("r2", (vault.load() as SessionVault.Load.Tokens).tokens.refreshToken)
        assertEquals(SessionState.READY, manager().restore())
    }

    @Test fun networkFailureKeepsTheSessionInvalidGrantEndsIt() = runTest {
        val m = manager()
        m.adopt("iss", "risime", OidcTokens("a1", 300, "r1", null))
        gw.next = RefreshResult.Failed("offline")
        assertEquals("a1", m.bearer(forceRefresh = true))
        assertTrue(m.refreshFailingTransiently()) // a 401 now must not sign out
        now = 400_000 // expired and still offline
        assertNull(m.bearer())
        assertEquals(SessionState.READY, m.state.value)
        assertTrue(vault.exists())
        assertEquals(SessionState.READY, manager().restore()) // still signed in after a restart
        gw.next = RefreshResult.InvalidGrant
        assertNull(m.bearer(forceRefresh = true))
        assertEquals(SessionState.NONE, m.state.value)
        assertTrue(m.signInNeeded.value)
        assertFalse(vault.exists())
        assertFalse(m.refreshFailingTransiently())
    }

    @Test fun oldVaultMigratesWithOnePromptThenNeverAgain() = runTest {
        assertTrue(legacy.store(StoredTokens(jwt("""{"typ":"Offline"}"""), "id0", "iss", "risime")))
        val m = manager()
        assertEquals(SessionState.NEEDS_MIGRATION, m.restore())
        assertNull(m.bearer()) // unmigrated: as before (no bearer without the prompt)
        assertEquals(MigrationResult.Migrated, m.migrate(m.migrationCipher()!!))
        assertEquals(SessionState.READY, m.state.value)
        assertEquals("a2", m.bearer())
        assertFalse(legacy.hasTokens()) // old key and blob deleted
        assertNull(oldKey.pair)
        assertFalse(File(tmp.root, "tokens.bin").exists())
        assertEquals("Offline", diag.signedIn.last()!!.typ)
        // The next process restores silently.
        val next = manager()
        assertEquals(SessionState.READY, next.restore())
        assertFalse(next.signInNeeded.value)
    }

    @Test fun cancelledMigrationKeepsTheOldVaultAndAsksAgainNeverSignsOut() = runTest {
        legacy.store(StoredTokens("r0", null, "iss", "risime"))
        val m = manager()
        assertEquals(SessionState.NEEDS_MIGRATION, m.restore())
        m.migrationCipher() // the prompt was shown and cancelled: nothing else happens
        assertEquals(SessionState.NEEDS_MIGRATION, m.state.value)
        assertFalse(m.signInNeeded.value)
        assertTrue(legacy.hasTokens())
        assertEquals(SessionState.NEEDS_MIGRATION, manager().restore()) // asked again at the next open
    }

    @Test fun migrationWithoutANewKeyKeepsTheOldVaultButWorksForThisProcess() = runTest {
        legacy.store(StoredTokens("r0", null, "iss", "risime"))
        newKey.canCreate = false
        val m = manager()
        m.restore()
        assertEquals(MigrationResult.KeptOld, m.migrate(m.migrationCipher()!!))
        assertEquals(SessionState.READY, m.state.value)
        assertTrue(legacy.hasTokens())
        // A rotated token goes to the old vault (public-key wrap, no prompt) so nothing is lost.
        val stored = legacy.open(legacy.unlockCipher()!!)!!
        assertEquals("r2", stored.refreshToken)
    }

    @Test fun invalidatedOldKeyMeansSignInWithDataKept() = runTest {
        legacy.store(StoredTokens("r0", null, "iss", "risime"))
        oldKey.invalidated = true // a new fingerprint was enrolled
        val m = manager()
        assertEquals(SessionState.NEEDS_MIGRATION, m.restore())
        assertNull(m.migrationCipher()) // → the UI signs in again (chats kept, trigger key_invalidated)
        // A later process sees the orphaned blob: no session, trigger key_invalidated.
        val next = manager()
        assertEquals(SessionState.NONE, next.restore())
        assertEquals(SignOutTrigger.KEY_INVALIDATED, next.restoreProblem)
        assertFalse(File(tmp.root, "tokens.bin").exists())
    }

    @Test fun memoryOnlyInstallSignsInOnceThenStays() = runTest {
        val m = manager()
        assertEquals(SessionState.NONE, m.restore()) // the start-up check signs in again (chats kept)
        assertNull(m.restoreProblem) // nothing was stored: trigger no_stored_session
        assertTrue(m.adopt("iss", "risime", OidcTokens("a1", 300, "r1", null)))
        assertEquals(SessionState.READY, manager().restore())
    }

    @Test fun signOutRevokesAndForgetsBothVaults() = runTest {
        legacy.store(StoredTokens("r0", null, "iss", "risime"))
        val m = manager()
        m.adopt("iss", "risime", OidcTokens("a1", 300, "r1", null))
        assertFalse(legacy.hasTokens()) // a fresh sign-in replaces the old vault
        m.signOut()
        assertEquals(listOf("r1"), gw.revoked)
        assertEquals(SessionState.NONE, m.state.value)
        assertFalse(vault.exists())
        assertNull(m.bearer())
        assertEquals(SessionState.NONE, manager().restore())
    }

    @Test fun migrationScreenSignOutRevokesOnlyWhenTheOldSetOpens() = runTest {
        legacy.store(StoredTokens("r0", null, "iss", "risime"))
        val m = manager()
        m.restore()
        m.revokeStored(null) // prompt cancelled: forgotten anyway
        assertTrue(gw.revoked.isEmpty())
        assertFalse(legacy.hasTokens())
        legacy.store(StoredTokens("r9", null, "iss", "risime"))
        manager().revokeStored(legacy.unlockCipher())
        assertEquals(listOf("r9"), gw.revoked)
        assertNotEquals(SessionState.READY, manager().restore())
    }
}
