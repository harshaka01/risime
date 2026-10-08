package lk.codegen.risime.ui.search

import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Test

/** Locked chats never appear in search results. */
class LockedSearchTest {
    private fun contact(id: String, name: String) = ContactEntity(
        phone = "+9477000000$id", displayName = name, company = "", registered = true, userId = id,
    )

    private fun msg(peer: String, body: String) = MessageEntity(
        clientMsgId = "c-$peer", messageId = "m-$peer", conversationId = dmConversationId("me", peer), from = peer, to = "me",
        body = body, serverTs = null, localTs = 1L, status = "READ", outgoing = false,
    )

    @Test fun lockedPeopleAndTheirMessagesAreDropped() {
        val contacts = listOf(contact("a", "Amal Perera"), contact("b", "Amali Silva"))
        val raw = searchResults("ama", contacts, listOf(msg("a", "ama one"), msg("b", "ama two")))
        assertEquals(2, raw.contacts.size)
        assertEquals(2, raw.messages.size)
        val r = withoutLocked(raw, setOf(dmConversationId("me", "b")), "me")
        assertEquals(listOf("Amal Perera"), r.contacts.map { it.displayName })
        assertEquals(listOf("a"), r.messages.map { it.peerId })
        // Nothing locked: unchanged. Unknown list: no results at all.
        assertEquals(raw, withoutLocked(raw, emptySet(), "me"))
        assertEquals(SearchResults("ama"), withoutLocked(raw, null, "me"))
    }
}
