package lk.codegen.risime.ui.chat

import lk.codegen.risime.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ChatItemsTest {
    private val zone = ZoneId.of("Asia/Colombo")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun msg(id: String, ts: Long) = MessageEntity(id, null, "dm:a_b", "a", "b", "x", null, ts, "READ", true)

    @Test fun separatorBeforeFirstMessageOfEachLocalDay() {
        val now = at(2026, 10, 6, 15)
        val items = withDaySeparators(
            listOf(
                msg("1", at(2025, 12, 31, 23, 59)),
                msg("2", at(2026, 9, 1, 9)),
                msg("3", at(2026, 10, 2, 9)),
                msg("4", at(2026, 10, 5, 23, 59)),
                msg("5", at(2026, 10, 6, 0, 1)), // local midnight boundary, not UTC
                msg("6", at(2026, 10, 6, 14)),
            ),
            now, zone,
        )
        assertEquals(
            listOf("31 December 2025", "1", "1 September", "2", "Friday", "3", "Yesterday", "4", "Today", "5", "6"),
            items.map { if (it is ChatItem.Day) it.label else (it as ChatItem.Msg).m.clientMsgId },
        )
    }

    @Test fun emptyAndLabels() {
        assertEquals(emptyList<ChatItem>(), withDaySeparators(emptyList(), 0, zone))
        val today = LocalDate.of(2026, 10, 6)
        assertEquals("Today", dayLabel(today, today))
        assertEquals("Wednesday", dayLabel(today.minusDays(6), today))
        assertEquals("29 September", dayLabel(today.minusDays(7), today))
    }
}
