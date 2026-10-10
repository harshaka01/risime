package lk.codegen.risime.debug

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import lk.codegen.risime.BuildConfig
import lk.codegen.risime.RisiMeApp
import lk.codegen.risime.data.gcal.AuthResult
import lk.codegen.risime.data.gcal.GcalAuthorizer

/*
 * v1.31 §31.11 the Google Calendar test seam (DEBUG source set only: the release APK has neither this class nor
 * the action string, which the `checkNoGcalSeamInRelease` Gradle task proves). Sent with
 *   adb shell am broadcast -a lk.codegen.risime.debug.GCAL_TEST -p <package> \
 *       --es base_url http://127.0.0.1:8198/ --es access_token T --es account a@test --es state connected
 * and protected by android.permission.DUMP (only adb shell holds it). It installs a fake authorizer and the base
 * URL so that Redroid, which has no Play services, can run the whole flow, picker included.
 */

/** The fake sign-in: [state] `connected` returns [token]; `reauth_needed` "needs a resolution" (the user must consent again). */
class FakeGcalAuthorizer(private val token: String, private val account: String, private val state: String) : GcalAuthorizer {
    override suspend fun authorize(): AuthResult =
        if (state == GcalTest.CONNECTED) AuthResult.Token(token, Long.MAX_VALUE) else AuthResult.NeedsResolution(null)

    override suspend fun authorizeInteractive(launch: suspend (PendingIntent) -> Intent?): AuthResult = authorize()

    override fun invalidate() {}

    /** The fake grant is dropped with the revoke (nothing to call). */
    override suspend fun revoke(account: String?, token: String?): Boolean = true

    override fun available() = true

    override fun accountName(): String = account
}

object GcalTest {
    const val ACTION = "lk.codegen.risime.debug.GCAL_TEST"
    const val CONNECTED = "connected"
    const val REAUTH_NEEDED = "reauth_needed"
    const val NONE = "none"

    private val ALLOWED = Regex("^http://(127\\.0\\.0\\.1|10\\.0\\.2\\.2):[0-9]{1,5}/$")

    /** §31.11: only `http://127.0.0.1:<port>/` or `http://10.0.2.2:<port>/`. */
    fun baseUrlAllowed(url: String?): Boolean = url != null && ALLOWED.matches(url) && url.substringAfterLast(':').trimEnd('/').toIntOrNull()?.let { it in 1..65535 } == true

    /** The authorizer and base URL the broadcast asks for, or null when it asks for none / something refused. */
    fun hooks(baseUrl: String?, token: String?, account: String?, state: String?): Pair<GcalAuthorizer, String>? {
        if (state == NONE || state == null) return null
        if (!baseUrlAllowed(baseUrl) || token.isNullOrEmpty()) return null
        if (state != CONNECTED && state != REAUTH_NEEDED) return null
        return FakeGcalAuthorizer(token, account.orEmpty(), state) to baseUrl!!
    }
}

class GcalTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != GcalTest.ACTION) return
        val app = context.applicationContext as? RisiMeApp ?: return
        val state = intent.getStringExtra("state")
        val baseUrl = intent.getStringExtra("base_url")
        val h = GcalTest.hooks(baseUrl, intent.getStringExtra("access_token"), intent.getStringExtra("account"), state)
        if (state == GcalTest.NONE || state == null) {
            app.container.gcal.installTestHooks(null, null)
            Log.i("RisiMe", "RisiMe debug: gcal test seam removed")
        } else if (h == null) {
            // Never log the URL's host/port or any extra: just that it was refused.
            Log.w("RisiMe", "RisiMe debug: gcal test seam refused (base_url must be http://127.0.0.1:<port>/ or http://10.0.2.2:<port>/, with an access_token)")
        } else {
            app.container.gcal.installTestHooks(h.first, h.second)
            Log.i("RisiMe", "RisiMe debug: gcal test seam installed ($state)")
        }
    }
}
