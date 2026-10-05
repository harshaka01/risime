package lk.codegen.risime.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("d MMM")

fun timeOf(epochMs: Long): String =
    TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** Today → "14:05", otherwise "3 Oct". */
fun shortStamp(epochMs: Long): String {
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return if (t.toLocalDate() == LocalDate.now()) TIME.format(t) else DAY.format(t)
}
