package lk.codegen.risime.data.tabs

import lk.codegen.risime.net.RisiMadeBy
import lk.codegen.risime.net.RisiMadeByAlso
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** §27.1 the "Made by" sheet's text. */
object MadeByLabels {
    const val NOT_RECORDED = "Made by: not recorded"
    const val NO_MODEL = "RisiMe (no AI model)"

    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

    /** "RisiMe model (risi-l1)", "RisiMe (no AI model)" or "<Provider> (<model>)" (a commercial model is always named). */
    fun label(model: String?, provider: String): String = when {
        model == null -> NO_MODEL
        provider.equals(RisiMadeByAlso.PROVIDER_RISIME, true) -> "RisiMe model ($model)"
        else -> "$provider ($model)"
    }

    /** `at` in the phone's zone: "09:14" today, "Tue 09:14" otherwise; null when it doesn't parse. */
    fun time(at: String?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val t = at?.let { runCatching { Instant.parse(it).atZone(zone) }.getOrNull() } ?: return null
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return if (t.toLocalDate() == today) TIME.format(t) else DAY_TIME.format(t)
    }

    /** "Made by: RisiMe model (risi-l1) · 09:14" (or [NOT_RECORDED] for a message from an older server). */
    fun title(m: RisiMadeBy?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        m ?: return NOT_RECORDED
        val label = label(m.model, m.provider)
        return "Made by: $label" + (time(m.at, nowMs, zone)?.let { " · $it" } ?: "")
    }

    /** One line per `also` entry: "Transcribed by: RisiMe model (faster-whisper-large-v3)"; unknown tasks "Also used: …". */
    fun alsoLines(m: RisiMadeBy?): List<String> = m?.also.orEmpty().map { "${taskLabel(it.task)}: ${label(it.model, it.provider)}" }

    fun taskLabel(task: String): String = when (task) {
        RisiMadeByAlso.TASK_TRANSCRIBE -> "Transcribed by"
        else -> "Also used"
    }
}
