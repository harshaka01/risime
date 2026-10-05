package lk.codegen.risime.ui.common

import lk.codegen.risime.net.Presence
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * "online", "last seen today at 14:05", "last seen yesterday at 09:12", "last seen 3 Oct",
 * "last seen 3 Oct 2025"; null when unknown (not watched, not registered, never connected, offline).
 */
fun presenceLabel(p: Presence?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (p == null) return null
    if (p.online) return "online"
    val seen = p.lastSeen?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val t = seen.atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val day = t.toLocalDate()
    return when {
        day == today || day.isAfter(today) -> "last seen today at ${HM.format(t)}"
        day == today.minusDays(1) -> "last seen yesterday at ${HM.format(t)}"
        day.year == today.year -> "last seen ${DAY_MONTH.format(t)}"
        else -> "last seen ${DAY_MONTH_YEAR.format(t)}"
    }
}

const val TYPING_LABEL = "typing…"
