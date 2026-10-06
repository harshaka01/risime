package lk.codegen.risime.push

import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §12 notifications: one per group conversation, sender names, suppression by conversation id. */
class GroupNotificationsTest {
    private val grp = "grp:g1"
    private val dm = "dm:k1_me"

    private fun inc(id: String, conv: String, from: String, ts: Long, body: String = "hi $id") =
        MessageEntity(id, "m$id", conv, from, if (conv == grp) grp else "me", body, null, ts, "DELIVERED", false)

    private val contacts = listOf(ContactEntity("+1", "Kamal", "Rise", "k1", true))

    @Test fun groupMessagesGetTheGroupTitleAndSenderLines() {
        val msgs = listOf(inc("1", grp, "k1", 10), inc("2", grp, "n1", 11), inc("3", dm, "k1", 12))
        val plan = planChatNotifications(
            msgs, contacts, 0,
            groupNames = mapOf(grp to "Pilot team"), memberNames = mapOf(grp to mapOf("n1" to "Nimal", "k1" to "Kamal")),
        )
        val g = plan.first { it.conversationId == grp }
        assertTrue(g.group)
        assertEquals("Pilot team", g.title)
        assertEquals(listOf("Kamal: hi 1", "Nimal: hi 2"), g.lines)
        assertEquals(listOf("Kamal", "Nimal"), g.messages.map { it.sender })
        val d = plan.first { it.conversationId == dm }
        assertEquals("Kamal", d.title)
        assertEquals(listOf("hi 3"), d.lines)
    }

    @Test fun anOpenDmDoesNotSuppressTheSamePersonsGroupMessages() {
        val msgs = listOf(inc("1", grp, "k1", 10), inc("2", dm, "k1", 11))
        val plan = planChatNotifications(msgs, contacts, 0, suppressConversation = dm)
        assertEquals(listOf(grp), plan.map { it.conversationId })
        assertEquals("New group", plan.single().title)
        assertEquals(listOf(dm), planChatNotifications(msgs, contacts, 0, suppressConversation = grp).map { it.conversationId })
    }

    @Test fun addedToGroupText() {
        assertEquals("Kamal added you to Pilot team", addedToGroupText("Kamal", "Pilot team"))
        assertEquals("Kamal added you to a group", addedToGroupText("Kamal", null))
    }
}
