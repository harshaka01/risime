package ai.risicloud.risime.ui.chat

import ai.risicloud.risime.data.db.MessageEntity
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

/**
 * Architect 2026-10-16 14:05 item 2: a voice note's Risi action-items card is drawn right after the note (whose
 * transcript, with Translate, is drawn under the note's bubble): voice note → transcript → card, never between them
 * and never further down. [messages] are oldest first. Without the note on the phone the card follows the note's
 * standalone transcript line; without either it stays where it is. Nothing else moves.
 */
fun voiceCardsAfterTheirNote(messages: List<MessageEntity>): List<MessageEntity> {
    val kind = "\"" + ai.risicloud.risime.net.RisiKinds142.ACTION_ITEMS + "\""
    val cards = messages.mapNotNull { m ->
        if (m.systemJson?.contains(kind) != true) return@mapNotNull null
        val card = ai.risicloud.risime.net.RisiActionCard.parse(m.systemJson) ?: return@mapNotNull null
        if (card.kind != ai.risicloud.risime.net.RisiKinds142.ACTION_ITEMS || card.source != ai.risicloud.risime.net.RisiKinds142.SOURCE_VOICE_NOTE) return@mapNotNull null
        card.sourceRef?.messageId?.lowercase()?.let { m to it }
    }
    if (cards.isEmpty()) return messages
    fun transcriptOf(r: MessageEntity): String? =
        if (r.systemJson?.contains(ai.risicloud.risime.net.RisiKinds141.VOICE_TRANSCRIPT) != true) null
        else ai.risicloud.risime.net.RisiVoiceTranscript.parse(r.systemJson)?.messageId?.lowercase()
    val cardRows = cards.map { it.first }.toSet()
    val out = messages.toMutableList()
    for ((card, noteId) in cards) {
        val pos = out.indexOf(card)
        out.removeAt(pos)
        val note = out.indexOfFirst { it.messageId?.lowercase() == noteId && transcriptOf(it) == null && it !in cardRows }
        val anchor = if (note >= 0) note else out.indexOfLast { transcriptOf(it) == noteId }
        if (anchor < 0) {
            out.add(pos, card)
            continue
        }
        // After the anchor, the note's (hidden) transcript rows and any card already placed for the same note.
        var at = anchor + 1
        while (at < out.size && (transcriptOf(out[at]) == noteId || out[at] in cardRows && cards.any { (c, n) -> c === out[at] && n == noteId })) at++
        out.add(at, card)
    }
    return out
}
