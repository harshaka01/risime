package lk.codegen.risime.data.auth

import android.content.Context
import android.util.Log

/** What the health screen shows about the account (decision 064). */
data class AccountDiagnostics(
    /** The refresh token's `typ` at the last OIDC sign-in ("Offline" / "Refresh"); null: no OIDC sign-in recorded. */
    val tokenType: String?,
    val tokenExp: Long?,
    /** The last sign-out trigger ([SignOutTrigger.value]) and when (epoch ms). */
    val lastSignOut: String?,
    val lastSignOutAt: Long?,
)

/**
 * Sign-in/sign-out facts in app-private preferences, plus the log lines
 * `RisiMe auth: sign-out trigger=<…>` and `RisiMe auth: signed in, refresh token typ=… exp=…`.
 * Never stores or logs a token.
 */
class AuthDiagnosticsStore(context: Context) : AuthDiagnostics {
    private val prefs = context.getSharedPreferences("risime_auth_diag", Context.MODE_PRIVATE)

    override fun signedIn(info: RefreshTokenInfo?) {
        Log.i("RisiMe", "RisiMe auth: signed in, refresh token ${info ?: "typ=opaque exp=?"}")
        prefs.edit().putString(TYP, info?.typ ?: "opaque").putLong(EXP, info?.exp ?: 0L).apply()
    }

    override fun signedOut(trigger: SignOutTrigger) {
        Log.w("RisiMe", "RisiMe auth: sign-out trigger=${trigger.value}")
        prefs.edit().putString(LAST, trigger.value).putLong(LAST_AT, System.currentTimeMillis()).apply()
    }

    fun current(): AccountDiagnostics = AccountDiagnostics(
        tokenType = prefs.getString(TYP, null),
        tokenExp = prefs.getLong(EXP, 0L).takeIf { it > 0 },
        lastSignOut = prefs.getString(LAST, null),
        lastSignOutAt = prefs.getLong(LAST_AT, 0L).takeIf { it > 0 },
    )

    private companion object {
        const val TYP = "refresh_typ"
        const val EXP = "refresh_exp"
        const val LAST = "last_signout"
        const val LAST_AT = "last_signout_at"
    }
}
