package lk.codegen.risime.ui.chats

import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.LastMessage
import lk.codegen.risime.data.db.UnreadCount
import lk.codegen.risime.net.Presence
import lk.codegen.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRowsTest {
    private val me = "aaaa"
    private val kamal = ContactEntity("+1", "Kamal", "Rise", "BBBB", true)
    private val nimal = ContactEntity("+2", "Nimal", "CodeGen", "cccc", true)
    private val ghost = ContactEntity("+3", "Ghost", "Rise", null, false)

    @Test fun unreadCountsAttachToTheRightDmAndSortingIsKept() {
        val convK = dmConversationId(me, "BBBB")
        val convN = dmConversationId(me, "cccc")
        val rows = buildChatRows(
            me,
            listOf(ghost, kamal, nimal),
            listOf(LastMessage(convK, "hi", 10, false, "DELIVERED"), LastMessage(convN, "yo", 20, true, "READ")),
            listOf(UnreadCount(convK, 3)),
            presence = mapOf("bbbb" to Presence("bbbb", true)),
            typing = setOf("cccc"),
        )
        assertEquals(listOf("Nimal", "Kamal", "Ghost"), rows.map { it.name })
        assertEquals(listOf(0, 3, 0), rows.map { it.unread })
        assertTrue(rows[1].presence!!.online) // case-insensitive id match
        assertEquals(listOf(true, false, false), rows.map { it.typing })
    }

    @Test fun noUnreadWhenEverythingIsRead() {
        val rows = buildChatRows(me, listOf(kamal), emptyList(), emptyList())
        assertEquals(0, rows.single().unread)
    }
}
