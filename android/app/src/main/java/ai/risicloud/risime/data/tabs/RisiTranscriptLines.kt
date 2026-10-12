package ai.risicloud.risime.data.tabs

import ai.risicloud.risime.net.CallTranscribeConsent
import ai.risicloud.risime.net.CallTranscribeStates
import ai.risicloud.risime.net.RisiCallTranscribeLine
import ai.risicloud.risime.net.RisiCallTranscript
import ai.risicloud.risime.net.RisiCallTranscriptCompare
import ai.risicloud.risime.net.RisiCompareResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * v1.39 §36.5 the Official chat's transcription lines, rendered from the phone's own data: names from this
 * phone's contacts, times in this phone's zone. Pure (names and zone come in); the server's `body` is the
 * fallback when a field is missing.
 */
object RisiTranscriptLines {
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    fun hm(iso: String?, zone: ZoneId): String? = iso?.let { runCatching { HM.format(Instant.parse(it).atZone(zone)) }.getOrNull() }

    /** "Harsha 17:52: <text>". */
    fun transcript(t: RisiCallTranscript, nameOf: (String) -> String, zone: ZoneId, body: String): String {
        val who = t.speaker?.let(nameOf) ?: return body
        val at = hm(t.start, zone) ?: return body
        return "$who $at: ${t.text}"
    }

    /** The §36.5 consent line (the table's wording), with this phone's names and zone. */
    fun consent(l: RisiCallTranscribeLine, nameOf: (String) -> String, zone: ZoneId, body: String): String {
        fun n(id: String?) = id?.let(nameOf)
        return when (l.state) {
            CallTranscribeStates.ON -> {
                val asker = n(l.requestedBy) ?: return body
                val allowed = l.consents.filter { it.consent == CallTranscribeConsent.ALLOWED && !it.userId.equals(l.requestedBy, true) }
                val names = allowed.map { nameOf(it.userId) }.joinToString(", ").ifEmpty { return body }
                val at = hm(l.startedAt ?: allowed.mapNotNull { it.at }.maxOrNull(), zone)
                "$asker asked Risi to transcribe this call; $names allowed" + (at?.let { " ($it)" } ?: "") + ". Audio is sent to Risi for transcription." +
                    if (l.compare) " Developer model comparison is on." else ""
            }
            CallTranscribeStates.DECLINED -> {
                val who = l.consents.firstOrNull { it.consent == CallTranscribeConsent.DECLINED } ?: return body
                "${nameOf(who.userId)} declined transcription" + (hm(who.at, zone)?.let { " ($it)" } ?: "") + "."
            }
            CallTranscribeStates.EXPIRED -> "No answer to the transcription request" + (hm(l.endedAt, zone)?.let { " ($it)" } ?: "") + "."
            CallTranscribeStates.STOPPED -> {
                val at = hm(l.endedAt, zone)?.let { " ($it)" } ?: ""
                val who = n(l.by) ?: return body
                if (l.discard) "$who stopped transcription and deleted the transcript$at." else "$who stopped transcription$at."
            }
            CallTranscribeStates.ENDED -> {
                val from = hm(l.startedAt, zone)
                val to = hm(l.endedAt, zone)
                "Transcription ended with the call" + (if (from != null && to != null) " ($from–$to)" else "") + "."
            }
            else -> body
        }
    }

    /** Compare mode: `chosen` first, one row per model ("timeout" / "failed" when it had no text). */
    data class CompareRow(val model: String, val chosen: Boolean, val elapsedMs: Long, val text: String, val failed: Boolean)

    fun compareRows(c: RisiCallTranscriptCompare): List<CompareRow> = c.ordered.map { r: RisiCompareResult ->
        CompareRow(r.model, r.model == c.chosen, r.elapsedMs, r.text ?: r.error ?: "no text", r.text == null)
    }

    /** "Kamal 17:52 · Sinhala detected". */
    fun compareHeader(c: RisiCallTranscriptCompare, nameOf: (String) -> String, zone: ZoneId): String =
        listOfNotNull(
            c.speaker?.let(nameOf)?.let { n -> n + (hm(c.start, zone)?.let { " $it" } ?: "") },
            c.languageDetected?.let { languageName(it) + " detected" },
        ).joinToString(" · ")

    fun languageName(code: String): String = when (code) {
        "si" -> "Sinhala"
        "ta" -> "Tamil"
        "en" -> "English"
        else -> code
    }
}
