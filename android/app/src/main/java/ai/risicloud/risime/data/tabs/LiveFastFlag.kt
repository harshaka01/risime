package ai.risicloud.risime.data.tabs

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Proposal 2026-10-15-live-call-latency: the developer flag "Fast live call transcript". The server decides which
 * sessions are fast (`RISI_LIVE_FAST` off | canary | on, announced as `chunk.mode: "vad"`); this phone follows it
 * unless a developer turned the flag off here (then it keeps the 10–15 s chunks, which a fast session accepts too).
 * Only developer accounts see the switch; for everyone else it stays on, so nothing changes until the server says so.
 */
class LiveFastFlag(private val prefs: SharedPreferences?) {
    private val _on = MutableStateFlow(runCatching { prefs?.getBoolean(KEY, true) }.getOrNull() ?: true)
    val on: StateFlow<Boolean> = _on

    fun set(on: Boolean) {
        runCatching { prefs?.edit()?.putBoolean(KEY, on)?.apply() }
        _on.value = on
    }

    companion object {
        const val KEY = "dev_live_fast_on"
        const val LABEL = "Fast live call transcript"
        const val HELP = "When the server turns it on, Official call transcripts are cut at pauses into 2–4 s pieces, so lines show about 2 s after they are said. Off: the 12-s pieces as before."
    }
}
