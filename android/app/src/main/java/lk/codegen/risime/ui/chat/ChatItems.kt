package lk.codegen.risime.ui.chat

import lk.codegen.risime.data.db.MessageEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One row of the chat list: a day separator or a message. */
sealed interface ChatItem {
    val key: String

    data class Day(val date: LocalDate, val label: String) : ChatItem {
        override val key get() = "day:$date"
    }

    data class Msg(val m: MessageEntity) : ChatItem {
        override val key get() = m.clientMsgId
    }
}

private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

/** "Today", "Yesterday", a weekday within the last 6 days, "6 October", or "6 October 2025". */
fun dayLabel(date: LocalDate, today: LocalDate): String {
    val ago = ChronoUnit.DAYS.between(date, today)
    return when {
        ago <= 0L -> "Today"
        ago == 1L -> "Yesterday"
        ago < 7L -> WEEKDAY.format(date)
        date.year == today.year -> DAY_MONTH.format(date)
        else -> DAY_MONTH_YEAR.format(date)
    }
}

/** Inserts a [ChatItem.Day] before the first message of each local calendar day (input in display order). */
fun withDaySeparators(messages: List<MessageEntity>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<ChatItem> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val out = ArrayList<ChatItem>(messages.size + 4)
    var last: LocalDate? = null
    for (m in messages) {
        val d = Instant.ofEpochMilli(m.localTs).atZone(zone).toLocalDate()
        if (d != last) {
            out += ChatItem.Day(d, dayLabel(d, today))
            last = d
        }
        out += ChatItem.Msg(m)
    }
    return out
}
