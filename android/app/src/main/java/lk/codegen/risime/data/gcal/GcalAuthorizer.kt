package lk.codegen.risime.data.gcal

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/*
 * v1.31 §31.2: the Google Authorization API on the phone. The token is kept IN MEMORY ONLY (never on disk, a
 * log, a backup, a push or a request to RisiMe); `authorize()` is called again before each use and returns a
 * token silently while Play services holds the grant. No `requestOfflineAccess`, no web client id.
 */

/** What an `authorize()` gave. */
sealed interface AuthResult {
    /** A usable bearer token. [expiresAt] is epoch ms (Play services does not say; the cache assumes < 1 hour). */
    class Token(val value: String, val expiresAt: Long) : AuthResult {
        // Never print the token (a data class's toString would).
        override fun toString() = "Token(redacted)"
    }

    /** The user must consent (again): only the Settings screen may launch [pendingIntent] (null: no consent is possible, e.g. a scope is missing). */
    class NeedsResolution(val pendingIntent: PendingIntent?) : AuthResult

    /** Cannot authorize at all (no Play services). */
    data class Unavailable(val reason: String) : AuthResult

    /** The user backed out of the consent screen (not an error: the section stays as it was). */
    data object Cancelled : AuthResult
}

/** §31.2 the Google sign-in, replaceable (the debug seam installs a fake; tests too). */
interface GcalAuthorizer {
    /** A token, silently when the grant is valid; [NeedsResolution] when the user must consent again. */
    suspend fun authorize(): AuthResult

    /**
     * The Settings screen's consent flow (§31.2 step 1): like [authorize], but a needed consent is shown through
     * [launch] (the screen runs the `PendingIntent` and returns the reply `Intent`, null if the user backed out).
     * Only the Settings screen may call this.
     */
    suspend fun authorizeInteractive(launch: suspend (PendingIntent) -> android.content.Intent?): AuthResult = authorize()

    /** Drops the cached token (a `401`, Disconnect). */
    fun invalidate()

    /** §31.8 revoke this app's grant for the account (`revokeAccess`); false: could not. */
    suspend fun revoke(account: String?, token: String?): Boolean

    /** Whether Google Play services can authorize on this phone. */
    fun available(): Boolean = true

    /** The signed-in account's email, if the authorizer knows it (shown only in the section). */
    fun accountName(): String? = null
}

/** §31.2 the two scopes, exactly. */
object GcalScopes {
    const val CAL_EVENTS = "https://www.googleapis.com/auth/calendar.events"
    const val CAL_LIST_RO = "https://www.googleapis.com/auth/calendar.calendarlist.readonly"
    val ALL = listOf(CAL_EVENTS, CAL_LIST_RO)
}

/** [GcalAuthorizer] over `Identity.getAuthorizationClient`. */
class PlayAuthorizer(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) : GcalAuthorizer {
    @Volatile private var cached: AuthResult.Token? = null
    @Volatile private var lastAccount: String? = null

    /** The request is built the same way for `authorize` (here) and the Settings screen's consent flow. */
    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(GcalScopes.ALL.map { Scope(it) }).build()

    override fun available(): Boolean =
        runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }.getOrDefault(false)

    override suspend fun authorize(): AuthResult {
        cached?.takeIf { it.expiresAt - now() > 60_000 }?.let { return it }
        if (!available()) return AuthResult.Unavailable("no_play_services")
        val r = runCatching { Identity.getAuthorizationClient(context).authorize(request()).await() }.getOrElse { return AuthResult.Unavailable("no_play_services") }
        return result(r)
    }

    override suspend fun authorizeInteractive(launch: suspend (PendingIntent) -> android.content.Intent?): AuthResult {
        val first = authorize()
        if (first !is AuthResult.NeedsResolution) return first
        val pi = first.pendingIntent ?: return first
        val reply = launch(pi) ?: return AuthResult.Cancelled
        val r = runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(reply) }.getOrElse { return AuthResult.Cancelled }
        return result(r)
    }

    /** Turns an `AuthorizationResult` (also the Settings screen's, from `getAuthorizationResultFromIntent`) into an [AuthResult]. */
    fun result(r: AuthorizationResult): AuthResult {
        if (r.hasResolution()) return AuthResult.NeedsResolution(r.pendingIntent)
        val token = r.accessToken ?: return AuthResult.NeedsResolution(null)
        // Both scopes must be granted (the user can untick one on the consent screen).
        if (!r.grantedScopes.containsAll(GcalScopes.ALL)) return AuthResult.NeedsResolution(null)
        lastAccount = runCatching { r.toGoogleSignInAccount()?.email }.getOrNull() ?: lastAccount
        return AuthResult.Token(token, now() + 50 * 60_000L).also { cached = it }
    }

    override fun invalidate() {
        cached = null
    }

    override fun accountName(): String? = lastAccount

    override suspend fun revoke(account: String?, token: String?): Boolean {
        cached = null
        if (account != null && available()) {
            val ok = runCatching {
                val req = RevokeAccessRequest.builder()
                    .setAccount(android.accounts.Account(account, "com.google"))
                    .setScopes(GcalScopes.ALL.map { Scope(it) })
                    .build()
                Identity.getAuthorizationClient(context).revokeAccess(req).await()
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.cancel(it) }
    addOnCanceledListener { c.cancel() }
}
