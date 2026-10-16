package ai.risicloud.risime.voice

import android.content.SharedPreferences
import java.util.Locale

/**
 * Architect 2026-10-16 14:05 item 3 (Harsha): the Translate picker. Sinhala / English / Tamil first (the text's own
 * language hidden), then "More languages…", a searchable list of every language the server translates to, with this
 * user's recent picks at the top. The same picker serves voice-note transcripts, typed messages and the
 * "Always translate voice notes to" setting.
 *
 * Which targets exist is the server's to say (`translate_languages` in `/me/risi/voice`, proposal
 * 2026-10-16-translate-languages); until a server sends that list the targets are §37.13's en / si / ta and
 * "More languages…" isn't offered (it would only list languages that fail).
 */
object TranslateLanguages {
    /** The default buttons, in this order. */
    val DEFAULTS = listOf("si", "en", "ta")

    /** §37.13's targets: what a server without `translate_languages` accepts. */
    val BASE = setOf("en", "si", "ta")

    const val MORE = "More languages…"
    const val RECENT = "Recent"
    const val MAX_RECENT = 5

    /** The language's English name ("Sinhala", "Tamil", "French"); the code itself when unknown. */
    fun name(code: String): String = when (code.lowercase()) {
        "si" -> "Sinhala"
        "ta" -> "Tamil"
        "en" -> "English"
        else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && !it.equals(code, true) } ?: code
    }

    /** What the server translates to: its `translate_languages` (normalised), else §37.13's three. */
    fun supported(server: List<String>?): List<String> {
        val list = server.orEmpty().map { it.trim().lowercase() }.filter { it.matches(Regex("[a-z]{2,3}")) }.distinct()
        return if (list.isEmpty()) DEFAULTS else (DEFAULTS.filter { it in list } + list.filter { it !in DEFAULTS })
    }

    /** The buttons under a text in [source]: the defaults the server takes, the source's own hidden. */
    fun buttons(source: String?, supported: List<String>): List<String> = DEFAULTS.filter { it in supported && it != source?.lowercase() }

    /** "More languages…" is offered only when the server translates to more than the buttons show. */
    fun hasMore(supported: List<String>): Boolean = supported.any { it !in DEFAULTS }

    /**
     * The "More languages…" list for [query]: the recent picks first (as [recent] lists them), then every other
     * supported language by name; the text's own language and unsupported codes never appear. A query matches the
     * name's start, any word of it, or the code.
     */
    fun list(query: String, supported: List<String>, recent: List<String>, source: String?): Pair<List<String>, List<String>> {
        val q = query.trim().lowercase()
        fun matches(c: String): Boolean {
            if (q.isEmpty()) return true
            val n = name(c).lowercase()
            return c == q || n.startsWith(q) || n.split(' ', '-', '(').any { it.startsWith(q) }
        }
        val skip = source?.lowercase()
        val rec = recent.filter { it in supported && it != skip && matches(it) }
        val rest = supported.filter { it !in rec && it != skip && matches(it) }.sortedBy { name(it) }
        return rec to rest
    }
}

/** This user's recent Translate picks on this phone, most recent first (at most [TranslateLanguages.MAX_RECENT]). */
class RecentTranslateLanguages(private val prefs: SharedPreferences?) {
    fun list(): List<String> = runCatching { prefs?.getString(KEY, null) }.getOrNull()?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    fun record(code: String) {
        val next = (listOf(code.lowercase()) + list().filter { it != code.lowercase() }).take(TranslateLanguages.MAX_RECENT)
        runCatching { prefs?.edit()?.putString(KEY, next.joinToString(","))?.apply() }
    }

    companion object {
        private const val KEY = "translate_recent"
    }
}
