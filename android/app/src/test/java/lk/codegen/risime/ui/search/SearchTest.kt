package lk.codegen.risime.ui.search

import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    @Test fun likePatternEscapesWildcards() {
        assertEquals("%hello%", likePattern("  hello "))
        assertEquals("%50\\%\\_off\\\\x%", likePattern("50%_off\\x"))
    }

    @Test fun resultsMapMessagesToPeers() {
        val kamal = ContactEntity("+1", "Kamal", "Rise", "bbbb", true)
        val ghost = ContactEntity("+2", "Kamala", "Rise", null, false)
        val m1 = MessageEntity("c1", "m1", "dm:a_bbbb", "BBBB", "a", "lunch?", null, 1, "READ", false)
        val m2 = MessageEntity("c2", "m2", "dm:a_bbbb", "a", "bbbb", "lunch at 1", null, 2, "SENT", true)
        val orphan = MessageEntity("c3", "m3", "dm:a_zz", "zz", "a", "lunch", null, 3, "READ", false)
        val r = searchResults("kam", listOf(kamal, ghost), listOf(m2, m1, orphan))
        assertEquals(listOf("Kamal"), r.contacts.map { it.displayName }) // unregistered aren't openable
        assertEquals(listOf("c2", "c1"), r.messages.map { it.message.clientMsgId })
        assertEquals(setOf("bbbb"), r.messages.map { it.peerId }.toSet())
        assertEquals(SearchResults(""), searchResults("  ", listOf(kamal), listOf(m1)))
    }
}
