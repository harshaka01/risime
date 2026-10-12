package ai.risicloud.risime.data.tabs

import ai.risicloud.risime.net.RisiMadeBy
import ai.risicloud.risime.net.RisiMadeByAlso
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * §27.1 the "Made by" sheet's text. Users only ever see "Made by Risi" ([title]): no model or vendor names in the
 * normal UI (Harsha 2026-10-12). The model/provider strings ([label], [developerTitle], [alsoLines]) are for a
 * Developer view only; the server's learning log is unaffected.
 */
object MadeByLabels {
    const val MADE_BY_RISI = "Made by Risi"
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

    /** What users see: "Made by Risi · 09:14" ("Made by Risi" when the server sent no `made_by`). Never a model name. */
    fun title(m: RisiMadeBy?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        MADE_BY_RISI + (time(m?.at, nowMs, zone)?.let { " · $it" } ?: "")

    /** Developer view only: "Made by: RisiMe model (risi-l1) · 09:14" (or [NOT_RECORDED] for a message from an older server). */
    fun developerTitle(m: RisiMadeBy?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        m ?: return NOT_RECORDED
        val label = label(m.model, m.provider)
        return "Made by: $label" + (time(m.at, nowMs, zone)?.let { " · $it" } ?: "")
    }

    /** Developer view only. One line per `also` entry: "Transcribed by: RisiMe model (faster-whisper-large-v3)"; unknown tasks "Also used: …". */
    fun alsoLines(m: RisiMadeBy?): List<String> = m?.also.orEmpty().map { "${taskLabel(it.task)}: ${label(it.model, it.provider)}" }

    fun taskLabel(task: String): String = when (task) {
        RisiMadeByAlso.TASK_TRANSCRIBE -> "Transcribed by"
        else -> "Also used"
    }
}
