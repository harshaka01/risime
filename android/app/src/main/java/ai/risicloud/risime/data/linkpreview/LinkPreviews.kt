package ai.risicloud.risime.data.linkpreview

import ai.risicloud.risime.net.LinkPreview
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v1.44 §39.0 Settings → Chats → "Link previews" (default on), device-local, never sent to the server. Off: the
 * phone fetches nothing and sends no preview; received cards are still shown (they come from the message alone).
 */
class LinkPreviews(private val prefs: SharedPreferences?, private val fetcher: LinkPreviewFetcher?) {
    private val _on = MutableStateFlow(prefs?.getBoolean(KEY_ON, true) ?: true)
    val on: StateFlow<Boolean> = _on

    fun set(on: Boolean) {
        _on.value = on
        runCatching { prefs?.edit()?.putBoolean(KEY_ON, on)?.apply() }
        runCatching { android.util.Log.i("RisiMe", "link previews: ${if (on) "on" else "off"}") }
    }

    /** §39.3 the sender's fetch, only while the switch is on (null otherwise or on any failure). */
    suspend fun fetchIfOn(url: String): LinkPreview? = if (_on.value) fetcher?.fetch(url) else null

    companion object {
        private const val KEY_ON = "link_previews_on"
        const val SETTING = "Link previews"
        const val SETTING_SUB = "Your phone loads a preview of links you send. The website sees your phone's address."
    }
}
