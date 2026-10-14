package ai.risicloud.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * Proposal 2026-10-14-voice-translate (v1.50 §37.13; server flag RISI_VOICE_TRANSLATE): "Wrong language?" under a Risi
 * transcript (`risi_action` `transcript_language`), per-viewer Translate (`POST /api/v1/risi/translate`) and the
 * "Always translate voice notes to" setting. Official only. Additive: shown only while `/me/risi/voice` says
 * `translate_available: true`.
 */

/** §37.13.1 a re-transcription: the language it was ([from]) and is now ([to]), and who asked ([by]). */
@Serializable
data class RisiRelang(val from: String? = null, val to: String, val by: String? = null)

/** Body of `POST /api/v1/risi/translate`. */
@Serializable
data class TranslateRequest(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String,
    val target: String,
)

/** `200` of `POST /api/v1/risi/translate`: shown only on this phone, labelled [label]. Never stored. */
@Serializable
data class TranslateReply(
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    val target: String,
    @SerialName("source_language") val sourceLanguage: String? = null,
    val text: String,
    val label: String = VoiceTranslate150.LABEL,
)

object VoiceTranslate150 {
    const val ACTION_TRANSCRIPT_LANGUAGE = "transcript_language"
    const val LABEL = "Translated by Risi"
    const val WRONG_LANGUAGE = "Wrong language?"
    const val ALWAYS_SETTING = "Always translate voice notes to"
    const val OFF = "off"

    /** The order the choices are offered in (§37.13.1, §37.13.2). */
    val LANGUAGES = listOf("ta", "si", "en")

    fun name(lang: String): String = when (lang) {
        "si" -> "Sinhala"
        "ta" -> "Tamil"
        "en" -> "English"
        else -> lang
    }

    /** "Wrong language?" / Translate choices: every language except the text's own. */
    fun choices(current: String?): List<String> = LANGUAGES.filter { it != current }

    fun translateLabel(lang: String) = "Translate to ${name(lang)}"

    fun relangLine(r: RisiRelang) = "Re-transcribed in ${name(r.to)}"

    /** §37.13.3 a text's language by its letters: Sinhala → si, Tamil → ta (the one with more), else en. */
    fun textLanguage(text: String): String {
        var si = 0
        var ta = 0
        text.codePoints().forEach { c ->
            when (c) {
                in 0x0D80..0x0DFF -> si++
                in 0x0B80..0x0BFF -> ta++
            }
        }
        return when {
            si == 0 && ta == 0 -> "en"
            si >= ta -> "si"
            else -> "ta"
        }
    }

    /** §37.13.2 a typed message gets the Translate link only with Sinhala or Tamil letters (Latin Singlish: none). */
    fun typedTranslatable(text: String?): Boolean = !text.isNullOrBlank() && textLanguage(text) != "en"

    /** The "Always translate to" target for a text in [language], or null (off, or already in it). */
    fun autoTarget(setting: String?, language: String?): String? =
        setting?.takeIf { it in LANGUAGES && it != language }

    /**
     * §37.13.1 the `risi_action` for "Wrong language?": [voiceEnvelope] is the note's own §37.2 envelope (its `blob`,
     * `enc` and `duration_ms` are copied: Risi deleted the key after transcribing). Null when the envelope lacks them.
     */
    fun transcriptLanguageAction(target: String, language: String, voiceEnvelope: JsonObject): JsonObject? {
        val blob = voiceEnvelope["blob"] as? JsonObject ?: return null
        val enc = voiceEnvelope["enc"] as? JsonObject ?: return null
        val dur = voiceEnvelope["duration_ms"] as? JsonPrimitive ?: return null
        if (language !in LANGUAGES) return null
        return buildJsonObject {
            put("v", JsonPrimitive(1))
            put("type", JsonPrimitive("risi_action"))
            put("action", JsonPrimitive(ACTION_TRANSCRIPT_LANGUAGE))
            put("target", JsonPrimitive(target))
            put("edit", JsonNull)
            put("language", JsonPrimitive(language))
            put("voice", buildJsonObject { put("blob", blob); put("enc", enc); put("duration_ms", dur) })
        }
    }

    /**
     * §37.13.1 the transcript shown for a note among several of the same `message_id`: the later one wins (a `relang`
     * transcript replaces the earlier even before Risi's delete arrives). [candidates] are (local time, transcript).
     */
    fun current(candidates: List<Pair<Long, RisiVoiceTranscript>>): RisiVoiceTranscript? =
        candidates.maxWithOrNull(compareBy<Pair<Long, RisiVoiceTranscript>>({ if (it.second.relang != null) 1 else 0 }, { it.first }))?.second
}
