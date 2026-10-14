package ai.risicloud.risime.voice

/**
 * Phone TTS (2026-10-14, console Voices page "Phone TTS: on the phone only"): the platform-free part of the developer
 * "Phone voice (TTS) test". **On-device voices only**, the same rule as the phone recogniser: a voice that needs the
 * network, or whose data isn't installed, is rejected, and there is never a fallback to network synthesis.
 */
object PhoneTts {
    /** One `android.speech.tts.Voice`, flattened so the choice is unit-testable. */
    data class VoiceInfo(
        val name: String,
        /** BCP-47 tag of the voice's locale, e.g. "ta-IN". */
        val localeTag: String,
        val networkRequired: Boolean,
        /** The voice lists `TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED` (data not on the phone yet). */
        val notInstalled: Boolean,
        val quality: Int = 0,
        val latency: Int = 0,
    )

    /** A language of the test: its code, label, the locales that count, and a prefilled sentence. */
    data class Lang(val code: String, val label: String, val locales: List<String>, val sample: String)

    val LANGS = listOf(
        Lang("si", "Sinhala", listOf("si-LK", "si"), "ආයුබෝවන්, මම රිසි."),
        Lang("ta", "Tamil", listOf("ta-IN", "ta-LK", "ta"), "வணக்கம், நான் ரிசி."),
        Lang("en", "English", listOf("en-US", "en-GB", "en"), "Hello, I'm Risi. How can I help you today?"),
    )

    fun lang(code: String): Lang? = LANGS.firstOrNull { it.code == code }

    /** Why a voice is rejected, or null when it's a usable on-device voice. */
    fun rejection(v: VoiceInfo): String? = when {
        v.networkRequired -> "network only (rejected)"
        v.notInstalled -> "voice data not installed"
        else -> null
    }

    /** True when [v]'s locale belongs to [lang] (exact tag first in [pick]; a bare language also matches). */
    fun matches(v: VoiceInfo, lang: Lang): Boolean {
        val tag = v.localeTag.replace('_', '-')
        val language = tag.substringBefore('-').lowercase()
        return lang.locales.any { it.equals(tag, ignoreCase = true) } || language == lang.code
    }

    /** All voices of [lang] (on-device or not), for the list. */
    fun voicesFor(voices: List<VoiceInfo>, lang: Lang): List<VoiceInfo> = voices.filter { matches(it, lang) }

    /**
     * The on-device voice to speak [langCode] with, or null (then the test refuses: "No on-device voice for <lang>").
     * Preference: the earliest locale in [Lang.locales] that has one, then higher quality, then lower latency.
     */
    fun pickOnDeviceVoice(voices: List<VoiceInfo>, langCode: String): VoiceInfo? {
        val lang = lang(langCode) ?: return null
        val ok = voicesFor(voices, lang).filter { rejection(it) == null }
        if (ok.isEmpty()) return null
        fun rank(v: VoiceInfo): Int {
            val i = lang.locales.indexOfFirst { it.equals(v.localeTag.replace('_', '-'), ignoreCase = true) }
            return if (i < 0) lang.locales.size else i
        }
        return ok.sortedWith(compareBy<VoiceInfo>({ rank(it) }, { -it.quality }, { it.latency }, { it.name })).first()
    }

    fun refusal(langCode: String): String = "No on-device voice for ${lang(langCode)?.label ?: langCode}"

    /** "ta-IN · ta-in-x-tad-local · quality 400 · latency 200 · on-device" (no text, ever). */
    fun line(v: VoiceInfo): String =
        "${v.localeTag} · ${v.name} · quality ${v.quality} · latency ${v.latency} · ${rejection(v) ?: "on-device"}"

    /** "Tamil · ta-in-x-tad-local · first audio 0.31 s". */
    fun result(langCode: String, voice: String, firstAudioMs: Long): String =
        "${lang(langCode)?.label ?: langCode} · $voice · first audio ${"%.2f".format(java.util.Locale.ROOT, firstAudioMs / 1000.0)} s"
}
