package lk.codegen.risime.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.crypto.Cipher

/** Fresh tokens from Keycloak (code exchange or refresh). Secrets: never logged. */
class OidcTokens(
    val accessToken: String,
    /** Seconds, from the token response at receipt. */
    val expiresInSec: Long,
    val refreshToken: String?,
    val idToken: String?,
) {
    override fun toString() = "OidcTokens(expiresIn=$expiresInSec, <secrets hidden>)"
}

sealed interface RefreshResult {
    data class Ok(val tokens: OidcTokens) : RefreshResult

    /** invalid_grant: the offline session is gone (expired, revoked, user disabled). Sign in again. */
    data object InvalidGrant : RefreshResult

    data class Failed(val reason: String) : RefreshResult
}

/** The Keycloak calls the app makes outside the browser (AppAuth in the app, a fake in tests). */
interface OidcGateway {
    suspend fun refresh(issuer: String, clientId: String, refreshToken: String): RefreshResult

    /** RFC 7009 revocation of the offline refresh token (best effort). */
    suspend fun revoke(issuer: String, clientId: String, refreshToken: String)
}

/** In-memory OIDC state for this process. */
private class Live(
    val issuer: String,
    val clientId: String,
    var accessToken: String?,
    var expiresAtElapsed: Long,
    var refreshToken: String,
    var idToken: String?,
)

/**
 * Owns OIDC tokens (decision 014): access token and the unlocked refresh token in memory only,
 * the token set sealed in [vault]. Thread-safe; every network refresh is single-flight.
 */
class AuthManager(
    private val gateway: OidcGateway,
    private val vault: TokenVault,
    private val elapsed: () -> Long,
    /** Called with every new access token (pushes `auth:refresh` on the socket). */
    private val onNewAccessToken: suspend (String) -> Unit = {},
) {
    private val lock = Mutex()
    private var live: Live? = null

    private val _unlocked = MutableStateFlow(false)

    /** True while an OIDC token set is in memory (signed in, not locked). */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Set when a refresh found the offline session gone: the UI goes back to sign-in (data kept). */
    private val _signInNeeded = MutableStateFlow(false)
    val signInNeeded: StateFlow<Boolean> = _signInNeeded.asStateFlow()

    /** Is there a sealed token set that a fingerprint could unlock? */
    fun canUnlock(): Boolean = vault.hasTokens()

    fun unlockCipher(): Cipher? = vault.unlockCipher()

    /** Monotonic time at which the current access token should be refreshed (null: none). */
    fun refreshDueInMs(): Long? = live?.let { RefreshTiming.refreshDelayMs(it.expiresAtElapsed, elapsed()) }

    fun idToken(): String? = live?.idToken

    fun issuerAndClient(): Pair<String, String>? = live?.let { it.issuer to it.clientId }

    /** After the browser sign-in and a successful `GET /me`: keep in memory and seal for next time. */
    suspend fun adopt(issuer: String, clientId: String, t: OidcTokens): Boolean = lock.withLock {
        val rt = t.refreshToken ?: return@withLock false
        live = Live(issuer, clientId, t.accessToken, RefreshTiming.expiresAt(elapsed(), t.expiresInSec), rt, t.idToken)
        _signInNeeded.value = false
        _unlocked.value = true
        // False when the device has no secure lock screen: memory-only, browser sign-in next start.
        vault.store(StoredTokens(rt, t.idToken, issuer, clientId))
    }

    /** BiometricPrompt succeeded: open the vault, then get a fresh access token. */
    suspend fun unlock(authenticated: Cipher): Boolean {
        val stored = vault.open(authenticated) ?: run {
            vault.clear()
            _signInNeeded.value = true
            return false
        }
        lock.withLock {
            live = Live(stored.issuer, stored.clientId, null, 0, stored.refreshToken, stored.idToken)
            _unlocked.value = true
        }
        return bearer(forceRefresh = true) != null || live != null
    }

    /**
     * The access token for REST and the socket, refreshed when within the margin of expiry or when
     * [forceRefresh]. Null when locked or when the offline session is gone.
     */
    suspend fun bearer(forceRefresh: Boolean = false): String? {
        var pushed: String? = null
        val token = lock.withLock {
            val l = live ?: return@withLock null
            val fresh = l.accessToken != null && !RefreshTiming.needsRefresh(l.expiresAtElapsed, elapsed())
            if (fresh && !forceRefresh) return@withLock l.accessToken
            when (val r = gateway.refresh(l.issuer, l.clientId, l.refreshToken)) {
                is RefreshResult.Ok -> {
                    l.accessToken = r.tokens.accessToken
                    l.expiresAtElapsed = RefreshTiming.expiresAt(elapsed(), r.tokens.expiresInSec)
                    r.tokens.idToken?.let { l.idToken = it }
                    val rotated = r.tokens.refreshToken
                    if (rotated != null && rotated != l.refreshToken) {
                        l.refreshToken = rotated
                        vault.store(StoredTokens(rotated, l.idToken, l.issuer, l.clientId)) // public-key wrap: no prompt
                    }
                    pushed = l.accessToken
                    l.accessToken
                }
                RefreshResult.InvalidGrant -> {
                    live = null
                    vault.clear()
                    _unlocked.value = false
                    _signInNeeded.value = true
                    null
                }
                // Network trouble: keep using the old token while it's still valid.
                is RefreshResult.Failed -> l.accessToken?.takeIf { l.expiresAtElapsed > elapsed() }
            }
        }
        pushed?.let { onNewAccessToken(it) }
        return token
    }

    /** Logout: revoke the offline token (if unlocked), forget memory, delete the key pair. */
    suspend fun signOut() {
        val l = lock.withLock { live.also { live = null } }
        _unlocked.value = false
        l?.let { runCatching { gateway.revoke(it.issuer, it.clientId, it.refreshToken) } }
        vault.clear()
    }

    /** Locked-screen sign out after the prompt unlocked the set (so it can be revoked first). */
    suspend fun revokeStored(authenticated: Cipher?) {
        authenticated?.let { c -> vault.open(c)?.let { runCatching { gateway.revoke(it.issuer, it.clientId, it.refreshToken) } } }
        vault.clear()
    }

    fun acknowledgeSignInNeeded() {
        _signInNeeded.value = false
    }
}
