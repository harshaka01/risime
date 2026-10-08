package lk.codegen.risime.data.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

/** Where the OIDC session of this process stands (decision 064). */
enum class SessionState {
    /** The vault hasn't been read yet (process start). */
    RESTORING,

    /** A token set is in memory: REST, the socket, push syncs and workers have a bearer. */
    READY,

    /**
     * Only the pre-064 fingerprint-bound vault exists: one last prompt at the next app open moves
     * it to the new vault. Until then the app behaves as before (no bearer in the background).
     */
    NEEDS_MIGRATION,

    /** No OIDC session (dev session, signed out, or memory-only before 064). */
    NONE,
}

sealed interface MigrationResult {
    /** Moved to the new vault; the old key and blob are gone. */
    data object Migrated : MigrationResult

    /** Opened (session usable now) but the new vault couldn't be written: the old one is kept, asked again next open. */
    data object KeptOld : MigrationResult

    /** The old blob couldn't be opened: sign in again (chats kept). */
    data object Unreadable : MigrationResult
}

/**
 * Owns OIDC tokens (decisions 014, 064): the access token in memory only, the token set sealed in
 * [vault] (hardware key, no user authentication), so a process started by a push restores the
 * session with no UI. [legacy] is the pre-064 fingerprint-bound vault, only read to migrate it.
 * Thread-safe; every network refresh is single-flight.
 */
class AuthManager(
    private val gateway: OidcGateway,
    private val vault: SessionVault,
    private val legacy: TokenVault?,
    private val elapsed: () -> Long,
    /** Called with every new access token (pushes `auth:refresh` on the socket). */
    private val onNewAccessToken: suspend (String) -> Unit = {},
    private val diagnostics: AuthDiagnostics = AuthDiagnostics.None,
) {
    private val lock = Mutex()
    private var live: Live? = null
    private var transientFailure = false

    private val _state = MutableStateFlow(SessionState.RESTORING)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Set when a refresh found the offline session gone: the UI goes back to sign-in (data kept). */
    private val _signInNeeded = MutableStateFlow(false)
    val signInNeeded: StateFlow<Boolean> = _signInNeeded.asStateFlow()

    /** Why restoring found nothing usable (null: nothing was stored). */
    @Volatile var restoreProblem: SignOutTrigger? = null
        private set

    /** Reads the vault once per process (no UI, no prompt). Idempotent. */
    suspend fun restore(): SessionState {
        if (_state.value != SessionState.RESTORING) return _state.value
        return lock.withLock { restoreLocked() }
    }

    private suspend fun restoreLocked(): SessionState {
        if (_state.value != SessionState.RESTORING) return _state.value
        val next = withContext(Dispatchers.IO) {
            when (val l = vault.load()) {
                is SessionVault.Load.Tokens -> {
                    val t = l.tokens
                    live = Live(t.issuer, t.clientId, null, 0, t.refreshToken, t.idToken)
                    SessionState.READY
                }
                is SessionVault.Load.Unreadable -> {
                    Log.w("RisiMe", "RisiMe auth: vault unreadable (${l.reason})")
                    vault.clear()
                    if (legacy?.hasTokens() == true) {
                        SessionState.NEEDS_MIGRATION
                    } else {
                        restoreProblem = SignOutTrigger.VAULT_UNREADABLE
                        SessionState.NONE
                    }
                }
                SessionVault.Load.Empty -> when {
                    legacy?.hasTokens() == true -> SessionState.NEEDS_MIGRATION
                    legacy?.hasOrphanBlob() == true -> {
                        // A blob whose auth-bound key is gone (a new enrolment invalidated it earlier).
                        legacy.clear()
                        restoreProblem = SignOutTrigger.KEY_INVALIDATED
                        SessionState.NONE
                    }
                    else -> SessionState.NONE
                }
            }
        }
        Log.i("RisiMe", "RisiMe auth: session restored state=$next")
        _state.value = next
        return next
    }

    /** True when a token set is in memory (restores first). */
    suspend fun hasSession(): Boolean = restore() == SessionState.READY

    /** The pre-064 vault's Cipher to authenticate with one BiometricPrompt; null: the old key is gone or invalidated. */
    fun migrationCipher(): Cipher? = legacy?.unlockCipher()

    /** Monotonic time at which the current access token should be refreshed (null: none). */
    fun refreshDueInMs(): Long? = live?.let { RefreshTiming.refreshDelayMs(it.expiresAtElapsed, elapsed()) }

    fun idToken(): String? = live?.idToken

    fun issuerAndClient(): Pair<String, String>? = live?.let { it.issuer to it.clientId }

    /** The last refresh failed for a network/server reason (not invalid_grant): a 401 now is no reason to sign out. */
    fun refreshFailingTransiently(): Boolean = transientFailure && live != null

    /** After the browser sign-in and a successful `GET /me`: keep in memory and seal for next time. */
    suspend fun adopt(issuer: String, clientId: String, t: OidcTokens): Boolean = lock.withLock {
        val rt = t.refreshToken ?: return@withLock false
        live = Live(issuer, clientId, t.accessToken, RefreshTiming.expiresAt(elapsed(), t.expiresInSec), rt, t.idToken)
        transientFailure = false
        _signInNeeded.value = false
        _state.value = SessionState.READY
        restoreProblem = null
        val stored = withContext(Dispatchers.IO) {
            legacy?.clear()
            vault.store(StoredTokens(rt, t.idToken, issuer, clientId))
        }
        diagnostics.signedIn(refreshTokenInfo(rt))
        if (!stored) Log.w("RisiMe", "RisiMe auth: no vault key on this phone: the session lasts until the process ends")
        stored
    }

    /** The migration prompt succeeded: open the old vault, re-seal under the new key, delete the old key and blob. */
    suspend fun migrate(authenticated: Cipher): MigrationResult {
        val old = legacy ?: return MigrationResult.Unreadable
        val stored = withContext(Dispatchers.IO) { old.open(authenticated) }
        if (stored == null) {
            withContext(Dispatchers.IO) { old.clear() }
            lock.withLock { _state.value = SessionState.NONE }
            return MigrationResult.Unreadable
        }
        val moved = withContext(Dispatchers.IO) { vault.store(stored) }
        lock.withLock {
            live = Live(stored.issuer, stored.clientId, null, 0, stored.refreshToken, stored.idToken)
            _state.value = SessionState.READY
        }
        if (moved) withContext(Dispatchers.IO) { old.clear() }
        Log.i("RisiMe", "RisiMe auth: migration ${if (moved) "done: old fingerprint-bound key deleted" else "deferred: new vault unavailable, old vault kept"}")
        diagnostics.signedIn(refreshTokenInfo(stored.refreshToken))
        bearer(forceRefresh = true)
        return if (moved) MigrationResult.Migrated else MigrationResult.KeptOld
    }

    /**
     * The access token for REST and the socket, refreshed when within the margin of expiry or when
     * [forceRefresh]. Null when there's no session or the offline session is gone.
     */
    suspend fun bearer(forceRefresh: Boolean = false): String? {
        restore()
        var pushed: String? = null
        val token = lock.withLock {
            val l = live ?: return@withLock null
            val fresh = l.accessToken != null && !RefreshTiming.needsRefresh(l.expiresAtElapsed, elapsed())
            if (fresh && !forceRefresh) return@withLock l.accessToken
            when (val r = gateway.refresh(l.issuer, l.clientId, l.refreshToken)) {
                is RefreshResult.Ok -> {
                    transientFailure = false
                    l.accessToken = r.tokens.accessToken
                    l.expiresAtElapsed = RefreshTiming.expiresAt(elapsed(), r.tokens.expiresInSec)
                    r.tokens.idToken?.let { l.idToken = it }
                    val rotated = r.tokens.refreshToken
                    if (rotated != null && rotated != l.refreshToken) {
                        l.refreshToken = rotated
                        persist(StoredTokens(rotated, l.idToken, l.issuer, l.clientId))
                    }
                    pushed = l.accessToken
                    l.accessToken
                }
                RefreshResult.InvalidGrant -> {
                    Log.w("RisiMe", "RisiMe auth: refresh refused (invalid_grant)")
                    live = null
                    withContext(Dispatchers.IO) {
                        vault.clear()
                        legacy?.clear()
                    }
                    _state.value = SessionState.NONE
                    _signInNeeded.value = true
                    null
                }
                // Network trouble: keep using the old token while it's still valid.
                is RefreshResult.Failed -> {
                    transientFailure = true
                    Log.i("RisiMe", "RisiMe auth: refresh failed (${r.reason}): session kept")
                    l.accessToken?.takeIf { l.expiresAtElapsed > elapsed() }
                }
            }
        }
        pushed?.let { onNewAccessToken(it) }
        return token
    }

    /** A rotated refresh token: the new vault (no prompt); while unmigrated with no new key, the old one (public-key wrap). */
    private suspend fun persist(t: StoredTokens) = withContext(Dispatchers.IO) {
        if (!vault.store(t) && legacy?.hasTokens() == true) legacy.store(t)
    }

    /** Logout: revoke the offline token (if in memory), forget memory, delete both vaults. */
    suspend fun signOut() {
        val l = lock.withLock { live.also { live = null } }
        _state.value = SessionState.NONE
        l?.let { runCatching { gateway.revoke(it.issuer, it.clientId, it.refreshToken) } }
        clearVaults()
    }

    /** Local half of a logout, no network: forget memory and the sealed sets (idempotent). */
    suspend fun forgetLocally() {
        lock.withLock { live = null }
        _state.value = SessionState.NONE
        clearVaults()
    }

    /** Migration screen → Sign out: revoke if the prompt opened the old set; forget both vaults either way. */
    suspend fun revokeStored(authenticated: Cipher?) {
        authenticated?.let { c ->
            withContext(Dispatchers.IO) { legacy?.open(c) }?.let { runCatching { gateway.revoke(it.issuer, it.clientId, it.refreshToken) } }
        }
        lock.withLock { live = null }
        _state.value = SessionState.NONE
        clearVaults()
    }

    private suspend fun clearVaults() = withContext(Dispatchers.IO) {
        vault.clear()
        legacy?.clear()
    }

    fun acknowledgeSignInNeeded() {
        _signInNeeded.value = false
    }
}
