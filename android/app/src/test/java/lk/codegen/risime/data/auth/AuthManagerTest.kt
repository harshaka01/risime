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

        /** A Keystore that doesn't answer now (keystore2/StrongBox busy after boot). */
        var failure: Exception? = null
        var reads = 0
        override fun get(): SecretKey? {
            reads++
            failure?.let { throw it }
            return key
        }
        override fun getOrCreate(): SecretKey? {
            get()
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

    /** Review of 064: a Keystore error is not a missing key: nothing is deleted, the read is retried with backoff. */
    @Test fun aTransientKeystoreErrorKeepsTheSessionAndRetries() = runTest {
        assertTrue(manager().adopt("iss", "risime", OidcTokens("a1", 300, "r1", null)))
        val keyBefore = newKey.key
        newKey.failure = java.security.ProviderException("Keystore busy") // a push-started process right after boot
        val m = manager()
        assertEquals(SessionState.RESTORING, m.restore())
        assertTrue(vault.exists()) // blob kept
        assertEquals(keyBefore, newKey.key) // key kept
        assertNull(m.restoreProblem)
        assertEquals(1_000L, m.restoreRetryInMs())
        val reads = newKey.reads
        assertNull(m.bearer()) // no session yet, and no Keystore hammering inside the backoff
        assertEquals(SessionState.RESTORING, m.restore())
        assertEquals(reads, newKey.reads)
        now += 1_000
        assertEquals(SessionState.RESTORING, m.restore()) // still busy: the next wait doubles
        assertEquals(2_000L, m.restoreRetryInMs())
        newKey.failure = null
        now += 2_000
        assertEquals("a2", m.bearer()) // the next bearer reads it again: signed in, no sign-out
        assertEquals(SessionState.READY, m.state.value)
        assertFalse(m.signInNeeded.value)
    }

    @Test fun onlyAPermanentFailureMakesTheVaultUnreadable() {
        assertTrue(SessionVault.classify(javax.crypto.AEADBadTagException()) is SessionVault.Load.Unreadable)
        assertTrue(SessionVault.classify(android.security.keystore.KeyPermanentlyInvalidatedException()) is SessionVault.Load.Unreadable)
        assertTrue(SessionVault.classify(IllegalArgumentException("unknown blob format")) is SessionVault.Load.Unreadable)
        assertTrue(SessionVault.classify(java.io.IOException("EIO")) is SessionVault.Load.Transient)
        assertTrue(SessionVault.classify(java.security.ProviderException("keystore2")) is SessionVault.Load.Transient)
        assertTrue(SessionVault.classify(java.security.UnrecoverableKeyException()) is SessionVault.Load.Transient)
        assertTrue(SessionVault.classify(java.security.InvalidKeyException()) is SessionVault.Load.Transient)
        // A blob whose key is really gone: unreadable, cleared, sign in again (chats kept).
        assertTrue(vault.store(StoredTokens("r1", null, "iss", "risime")))
        newKey.key = null
        assertEquals(SessionVault.Load.Unreadable("key missing"), vault.load())
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 32_000L, 60_000L, 60_000L), listOf(1, 2, 3, 6, 7, 30).map(AuthManager::restoreBackoffMs))
    }

    /** Review of 064: a rotated refresh token whose vault write failed is written again later. */
    @Test fun aFailedVaultWriteOnRotationIsRetried() = runTest {
        val m = manager()
        m.adopt("iss", "risime", OidcTokens("a1", 300, "r1", null))
        newKey.failure = java.security.ProviderException("busy")
        assertEquals("a2", m.bearer(forceRefresh = true)) // r1 → r2 at Keycloak
        assertTrue(m.hasUnsavedTokens())
        newKey.failure = null
        assertEquals("r1", (vault.load() as SessionVault.Load.Tokens).tokens.refreshToken) // the old blob is intact
        assertEquals("a2", m.bearer()) // within the retry interval: not tried yet
        assertTrue(m.hasUnsavedTokens())
        now += AuthManager.SAVE_RETRY_MS
        assertEquals("a2", m.bearer())
        assertFalse(m.hasUnsavedTokens())
        assertEquals("r2", (vault.load() as SessionVault.Load.Tokens).tokens.refreshToken)
        // A failed write at sign-in is retried too.
        val m2 = manager()
        newKey.failure = java.security.ProviderException("busy")
        assertFalse(m2.adopt("iss", "risime", OidcTokens("b1", 300, "s1", null)))
        newKey.failure = null
        gw.next = RefreshResult.Ok(OidcTokens("b2", 300, null, null)) // no rotation this time
        assertEquals("b2", m2.bearer(forceRefresh = true))
        assertEquals("s1", (vault.load() as SessionVault.Load.Tokens).tokens.refreshToken)
    }

    @Test fun vaultWritesAreAtomicAndLeaveNoTempFile() {
        assertTrue(vault.store(StoredTokens("r1", null, "iss", "risime")))
        assertTrue(vault.store(StoredTokens("r2", null, "iss", "risime")))
        assertFalse(File(tmp.root, "session.bin.tmp").exists())
        assertEquals("r2", (vault.load() as SessionVault.Load.Tokens).tokens.refreshToken)
        // A write that fails before the rename leaves the previous blob as it was.
        newKey.failure = java.security.ProviderException("busy")
        assertFalse(vault.store(StoredTokens("r3", null, "iss", "risime")))
        newKey.failure = null
        assertEquals("r2", (vault.load() as SessionVault.Load.Tokens).tokens.refreshToken)
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
