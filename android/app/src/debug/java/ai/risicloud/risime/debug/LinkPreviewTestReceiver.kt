package ai.risicloud.risime.debug

import ai.risicloud.risime.BuildConfig
import ai.risicloud.risime.data.linkpreview.LinkPreviewTestRoute
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/*
 * v1.44 §39.3 the link-preview test seam (DEBUG source set only; release builds have neither the class nor the
 * action, and LinkPreviewTestRoute ignores everything unless BuildConfig.DEBUG). The device gate serves an Open Graph
 * page on the gate host's 127.0.0.1 (adb reverse) and routes ONE test host to it, so `https://example.org/…` in a
 * message gets that page's preview. Protected by android.permission.DUMP: only `adb shell am broadcast` can send it.
 *
 *   adb shell am broadcast -a ai.risicloud.risime.debug.LINK_PREVIEW_TEST -p ai.risicloud.risime.debug --es host example.org --es origin http://127.0.0.1:8781
 *   adb shell am broadcast -a ai.risicloud.risime.debug.LINK_PREVIEW_TEST -p ai.risicloud.risime.debug --ez stop true
 */
class LinkPreviewTestReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "ai.risicloud.risime.debug.LINK_PREVIEW_TEST"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) return
        if (intent.getBooleanExtra("stop", false)) {
            LinkPreviewTestRoute.clear()
            Log.i("RisiMe", "RisiMe debug: link preview route cleared")
            return
        }
        val host = intent.getStringExtra("host") ?: return
        val origin = intent.getStringExtra("origin") ?: return
        val ok = LinkPreviewTestRoute.arm(host, origin)
        Log.i("RisiMe", "RisiMe debug: link preview route ${if (ok) "armed" else "refused"}")
    }
}
