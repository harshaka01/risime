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

    /** Transient vault reads in a row (Keystore busy): the next read waits [restoreBackoffMs]. */
    private var restoreFailures = 0

    @Volatile private var nextRestoreAt = Long.MIN_VALUE

    /** A token set not yet sealed (the vault write failed): written again on the next bearer/refresh. */
    private var unsaved: StoredTokens? = null
    private var nextSaveAt = Long.MIN_VALUE

    private val _state = MutableStateFlow(SessionState.RESTORING)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Set when a refresh found the offline session gone: the UI goes back to sign-in (data kept). */
    private val _signInNeeded = MutableStateFlow(false)
    val signInNeeded: StateFlow<Boolean> = _signInNeeded.asStateFlow()

    /** Why restoring found nothing usable (null: nothing was stored). */
    @Volatile var restoreProblem: SignOutTrigger? = null
        private set

    /**
     * Reads the vault once per process (no UI, no prompt). Idempotent. A transient Keystore/I/O
     * failure keeps [SessionState.RESTORING] (nothing deleted): the next call after the backoff
     * ([restoreRetryInMs]) reads again.
     */
    suspend fun restore(): SessionState {
        if (_state.value != SessionState.RESTORING) return _state.value
        if (elapsed() < nextRestoreAt) return SessionState.RESTORING
        return lock.withLock { restoreLocked() }
    }

    /** Ms until a retried vault read is allowed (0: now, or no retry pending). */
    fun restoreRetryInMs(): Long = (nextRestoreAt - elapsed()).coerceAtLeast(0)

    private suspend fun restoreLocked(): SessionState {
        if (_state.value != SessionState.RESTORING) return _state.value
        if (elapsed() < nextRestoreAt) return SessionState.RESTORING
        val next = withContext(Dispatchers.IO) {
            when (val l = vault.load()) {
                is SessionVault.Load.Transient -> {
                    restoreFailures++
                    val wait = restoreBackoffMs(restoreFailures)
                    nextRestoreAt = elapsed() + wait
                    Log.w("RisiMe", "RisiMe auth: vault not readable now (${l.reason}): session kept, retry #$restoreFailures in ${wait}ms")
                    SessionState.RESTORING
                }
                is SessionVault.Load.Tokens -> {
                    val t = l.tokens
                    live = Live(t.issuer, t.clientId, null, 0, t.refreshToken, t.idToken)
                    SessionState.READY
                }
                is SessionVault.Load.Unreadable -> {
                    Log.w("RisiMe", "RisiMe auth: vault unreadable for good (${l.reason}): cleared")
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
        if (next == SessionState.RESTORING) return next
        restoreFailures = 0
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
        val set = StoredTokens(rt, t.idToken, issuer, clientId)
        val stored = withContext(Dispatchers.IO) {
            legacy?.clear()
            vault.store(set)
        }
        diagnostics.signedIn(refreshTokenInfo(rt))
        if (stored) {
            unsaved = null
        } else {
            unsaved = set
            nextSaveAt = elapsed() + SAVE_RETRY_MS
            Log.w("RisiMe", "RisiMe auth: vault write failed: the session is in memory; written again on the next refresh")
        }
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
            retryUnsavedLocked(force = forceRefresh)
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
                    unsaved = null
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

    /**
     * A rotated refresh token: the new vault (no prompt); while unmigrated with no new key, the old
     * one (public-key wrap). When neither write lands, the set is kept as [unsaved] and written again
     * on a later bearer/refresh: the next process must not wake up with an already-rotated token.
     */
    private suspend fun persist(t: StoredTokens) {
        val ok = withContext(Dispatchers.IO) {
            vault.store(t) || (legacy?.hasTokens() == true && legacy.store(t))
        }
        if (ok) {
            if (unsaved != null) Log.i("RisiMe", "RisiMe auth: the pending token set is sealed now")
            unsaved = null
        } else {
            unsaved = t
            nextSaveAt = elapsed() + SAVE_RETRY_MS
            Log.w("RisiMe", "RisiMe auth: rotated token set not saved (vault write failed): retried on the next refresh")
        }
    }

    /** Under [lock]: writes a pending set again (on every forced refresh, else at most every [SAVE_RETRY_MS]). */
    private suspend fun retryUnsavedLocked(force: Boolean) {
        val t = unsaved ?: return
        if (!force && elapsed() < nextSaveAt) return
        persist(t)
    }

    /** True while a token set waits to be written to the vault (tests, diagnostics). */
    fun hasUnsavedTokens(): Boolean = unsaved != null

    /** Logout: revoke the offline token (if in memory), forget memory, delete both vaults. */
    suspend fun signOut() {
        val l = lock.withLock { live.also { live = null; unsaved = null } }
        _state.value = SessionState.NONE
        l?.let { runCatching { gateway.revoke(it.issuer, it.clientId, it.refreshToken) } }
        clearVaults()
    }

    /** Local half of a logout, no network: forget memory and the sealed sets (idempotent). */
    suspend fun forgetLocally() {
        lock.withLock { live = null; unsaved = null }
        _state.value = SessionState.NONE
        clearVaults()
    }

    /** Migration screen → Sign out: revoke if the prompt opened the old set; forget both vaults either way. */
    suspend fun revokeStored(authenticated: Cipher?) {
        authenticated?.let { c ->
            withContext(Dispatchers.IO) { legacy?.open(c) }?.let { runCatching { gateway.revoke(it.issuer, it.clientId, it.refreshToken) } }
        }
        lock.withLock { live = null; unsaved = null }
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

    companion object {
        /** A failed vault write is tried again at most this often (a forced refresh always tries). */
        const val SAVE_RETRY_MS = 30_000L

        /** 1 s, 2 s, 4 s … capped at a minute: a Keystore busy after boot answers within seconds. */
        fun restoreBackoffMs(failures: Int): Long = (1_000L shl (failures - 1).coerceIn(0, 6)).coerceAtMost(60_000L)
    }
}
