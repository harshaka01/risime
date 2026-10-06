package lk.codegen.risime.push

import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.FriendRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushLogicTest {
    private fun inc(id: String, from: String, ts: Long, body: String = "hi $id", status: String = "DELIVERED") =
        MessageEntity(id, "m$id", "dm:$from", from, "me", body, null, ts, status, outgoing = false)

    private val contacts = listOf(ContactEntity("+1", "Kamal", "Rise", "K1", true), ContactEntity("+2", "Nimal", "CG", "n1", true))

    @Test fun wakeUpPayload() {
        assertTrue(isInboxWakeUp(mapOf("type" to "inbox", "v" to "1")))
        assertFalse(isInboxWakeUp(mapOf("type" to "other")))
        assertFalse(isInboxWakeUp(emptyMap()))
    }

    @Test fun groupsPerChatWithLocalNamesAndOnlyNewOnes() {
        val msgs = listOf(
            inc("1", "k1", 10), inc("2", "k1", 20), inc("3", "n1", 15),
            inc("4", "n1", 5, status = "READ"), // read: ignored
            inc("5", "zz", 30), // unknown sender: generic title
        )
        val plan = planChatNotifications(msgs, contacts, notifiedUpTo = 12)
        assertEquals(listOf("dm:zz", "dm:k1", "dm:n1"), plan.map { it.conversationId }) // newest chat first
        val k = plan.single { it.peerId == "k1" }
        assertEquals("Kamal", k.title) // case-insensitive id match, name from the local DB
        assertEquals(listOf("hi 1", "hi 2"), k.lines) // older unread lines stay in the chat's notification
        assertEquals(2, k.count)
        assertEquals("New message", plan.single { it.peerId == "zz" }.title)
        // Nothing newer than what was notified → nothing to post.
        assertTrue(planChatNotifications(msgs, contacts, notifiedUpTo = 30).isEmpty())
        // The chat on screen is never notified.
        assertTrue(planChatNotifications(msgs, contacts, 0, suppressConversation = msgs.first { it.from == "k1" }.conversationId).none { it.peerId == "k1" })
    }

    @Test fun linesAreCappedAndPreviewed() {
        val many = (1..8).map { inc("$it", "k1", it.toLong(), body = "line $it") }
        val n = planChatNotifications(many, contacts, 0).single()
        assertEquals(8, n.count)
        assertEquals((4..8).map { "line $it" }, n.lines)
        assertEquals("a b", preview(" a\n\n b "))
        assertEquals(PREVIEW_CHARS, preview("x".repeat(500)).length)
    }

    @Test fun friendRequests() {
        val a = FriendRequest("r1", "+941", "u1", "Kamal", "Rise", null)
        val b = FriendRequest("r2", "+942", "u2", null, null, null)
        assertEquals(listOf(b), newRequests(listOf(a, b), setOf("r1")))
        assertNull(requestNotificationText(emptyList()))
        assertEquals("New friend request" to "Kamal wants to be friends on RisiMe", requestNotificationText(listOf(a)))
        assertEquals("2 new friend requests" to "Kamal, +942", requestNotificationText(listOf(a, b)))
    }

    @Test fun registrationAndPromptRules() {
        assertTrue(shouldRegisterDevice(true, true, true, "tok"))
        assertFalse(shouldRegisterDevice(false, true, true, "tok")) // no google-services.json
        assertFalse(shouldRegisterDevice(true, false, true, "tok"))
        assertFalse(shouldRegisterDevice(true, true, false, "tok")) // phone gate: socket/REST refused
        assertFalse(shouldRegisterDevice(true, true, true, ""))
        assertTrue(shouldPromptNotifications(33, granted = false, alreadyAsked = false, signedIn = true))
        assertFalse(shouldPromptNotifications(32, false, false, true)) // granted at install before 13
        assertFalse(shouldPromptNotifications(33, false, alreadyAsked = true, signedIn = true)) // denial respected
        assertFalse(shouldPromptNotifications(34, granted = true, alreadyAsked = false, signedIn = true))
        assertFalse(shouldPromptNotifications(34, false, false, signedIn = false))
    }

    @Test fun reactionsNotifyOnlyForEffectiveAddsOnMyMessages() {
        fun r(target: String, reactor: String, emoji: String, op: String = "add", pending: Boolean = false, ts: Long = 50) =
            lk.codegen.risime.data.db.ReactionEntity("dm:k1", target, reactor, emoji, op, op, "t", "mid", pending, null, ts)
        val mine = MessageEntity("c-mine", "m-mine", "dm:k1", "me", "k1", "lunch at 1?", null, 10, "READ", outgoing = true)
        val theirs = inc("t1", "k1", 11)
        val adds = listOf(
            r("m-mine", "k1", "👍"),
            r("m-mine", "k1", "😂", op = "remove"), // a remove never notifies
            r("m-theirs", "me2", "❤️"), // not my message
            r("m-mine", "me", "🙏"), // my own reaction
            r("m-mine", "k1", "😮", pending = true),
        )
        val lookup = mapOf("m-mine" to mine, "m-theirs" to theirs.copy(messageId = "m-theirs"))
        val out = mergeReactionNotifications(emptyList(), adds, { lookup[it] }, { if (it == "k1") "Kamal" else it }, "me")
        val n = out.single()
        assertEquals(listOf("Kamal reacted 👍 to: lunch at 1?"), n.lines)
        assertEquals("Kamal", n.title)
        // Merged into an existing chat notification.
        val existing = planChatNotifications(listOf(inc("1", "k1", 60)), contacts, 0)
        val merged = mergeReactionNotifications(existing, adds, { lookup[it] }, { "Kamal" }, "me").single()
        assertEquals(listOf("hi 1", "Kamal reacted 👍 to: lunch at 1?"), merged.lines)
        assertEquals(2, merged.count)
        // The open chat never notifies.
        assertTrue(mergeReactionNotifications(emptyList(), adds, { lookup[it] }, { "Kamal" }, "me", suppressConversation = adds.first().conversationId).isEmpty())
    }
}
