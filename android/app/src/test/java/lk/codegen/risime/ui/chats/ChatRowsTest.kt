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

    @Test fun formerFriendsStayOnlyWithHistoryAndAreReadOnly() {
        val former = ContactEntity("+4", "Old Pal", "Rise", "dddd", registered = false, friend = false)
        val formerNoChat = ContactEntity("+5", "Stranger", "Rise", "eeee", registered = false, friend = false)
        val vouched = ContactEntity("+6", "New Pal", "", "ffff", registered = true, friend = true, vouchedByName = "Kamal")
        val rows = buildChatRows(
            me, listOf(former, formerNoChat, vouched),
            listOf(LastMessage(dmConversationId(me, "dddd"), "bye", 5, false, "READ")), emptyList(),
        )
        assertEquals(listOf("New Pal", "Old Pal"), rows.map { it.name }) // friends first; no-history ex-friend hidden
        val old = rows.single { it.name == "Old Pal" }
        assertEquals(false, old.friend)
        assertTrue(old.openable) // history stays visible, read-only
        assertEquals("Kamal", rows.single { it.name == "New Pal" }.vouchedBy)
        val pending = buildChatRows(me, listOf(ContactEntity("+7", "No Id", "", null, false)), emptyList(), emptyList()).single()
        assertEquals(false, pending.openable)
    }
}
