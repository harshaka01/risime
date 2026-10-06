package lk.codegen.risime.ui.group

import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.db.GroupMemberEntity
import lk.codegen.risime.data.db.LastMessage
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.UnreadCount
import lk.codegen.risime.ui.chat.withDaySeparators
import lk.codegen.risime.ui.chats.buildChatRows
import lk.codegen.risime.ui.chats.buildGroupRows
import lk.codegen.risime.ui.chats.groupTypingLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Pure logic behind the group screens and the mixed chats list. */
class GroupLogicTest {
    private val me = "u-me"
    private val conv = "grp:g1"

    private fun member(id: String, name: String, role: String = "member", state: String = "active", joined: String? = "2026-10-06T08:00:00.000Z") =
        GroupMemberEntity(conv, id, name, null, role, "user", state, joined)

    @Test fun groupNamesAre1To100Graphemes() {
        val count = { s: String -> s.length }
        assertEquals("Enter a group name", groupNameError("   ", count))
        assertNull(groupNameError("Pilot team", count))
        assertNull(groupNameError("x".repeat(100), count))
        assertEquals("At most 100 characters", groupNameError("x".repeat(101), count))
    }

    @Test fun typingLabels() {
        assertNull(groupTypingLabel(emptyList()))
        assertEquals("Kamal is typing…", groupTypingLabel(listOf("Kamal")))
        assertEquals("Kamal and Nimal are typing…", groupTypingLabel(listOf("Kamal", "Nimal")))
        assertEquals("3 people are typing…", groupTypingLabel(listOf("a", "b", "c")))
    }

    @Test fun groupRowsMixWithDmsByActivityWithSenderPrefixTypingAndUnread() {
        val groups = listOf(GroupEntity(conv, "Pilot team", "member", "active", null, null, 1, 3, null, null, 10))
        val members = listOf(member("u-kamal", "Kamal"), member(me, "Me"))
        val lasts = listOf(LastMessage(conv, "hello all", 500, false, "DELIVERED", "u-kamal", "text"))
        val rows = buildGroupRows(me, groups, members, lasts, listOf(UnreadCount(conv, 2)), mapOf(conv to setOf("u-kamal", me)))
        val g = rows.single()
        assertTrue(g.group && g.openable)
        assertEquals(conv, g.target)
        assertEquals("Kamal", g.lastSender)
        assertEquals("Kamal is typing…", g.typingLabel) // my own typing never shows
        assertEquals(2, g.unread)
        val dm = ContactEntity("+1", "Sunil", "Rise", "u-sunil", true)
        val dmLast = LastMessage("dm:u-me_u-sunil", "older", 100, true, "SENT", me, "text")
        val all = buildChatRows(me, listOf(dm), listOf(dmLast), emptyList(), groupRows = rows)
        assertEquals(listOf("Pilot team", "Sunil"), all.map { it.name })
    }

    @Test fun newAndLeftGroupsShowStateLines() {
        val creating = GroupEntity(conv, null, "admin", "creating", null, null, 1, null, null, null, 10)
        assertEquals("Creating…", buildGroupRows(me, listOf(creating), emptyList(), emptyList(), emptyList()).single().stateLine)
        assertEquals("New group", buildGroupRows(me, listOf(creating), emptyList(), emptyList(), emptyList()).single().name)
        val left = creating.copy(name = "x", state = "left")
        val last = LastMessage(conv, "You left", 20, false, "READ", me, "system")
        val row = buildGroupRows(me, listOf(left), emptyList(), listOf(last), emptyList()).single()
        assertEquals("You left", row.stateLine)
        assertNull(row.lastSender) // system lines have no "Name: " prefix
    }

    @Test fun senderNamesOnlyAtTheStartOfARun() {
        fun m(id: String, from: String, out: Boolean = false, kind: String = "text", ts: Long) =
            MessageEntity(id, id, conv, from, conv, "b", null, ts, "READ", out, kind = kind)
        val items = withDaySeparators(
            listOf(m("1", "k", ts = 1), m("2", "k", ts = 2), m("3", "n", ts = 3), m("4", me, out = true, ts = 4), m("5", "n", ts = 5), m("s", "k", kind = "system", ts = 6), m("6", "k", ts = 7)),
            nowMs = 10, zone = ZoneOffset.UTC,
        )
        val shown = items.indices.filter { showSenderAt(items, it) }.map { (items[it] as lk.codegen.risime.ui.chat.ChatItem.Msg).m.clientMsgId }
        assertEquals(listOf("1", "3", "5", "6"), shown)
    }

    @Test fun infoUiOrdersMembersAndSuggestsTheLongestStandingForLastAdmin() {
        val g = GroupEntity(conv, "Pilot team", "admin", "active", null, null, 1, null, null, null, 1)
        val ms = listOf(
            member(me, "Me", "admin"), member("u-new", "Zed", joined = "2026-10-06T09:00:00.000Z"),
            member("u-old", "Amal", joined = "2026-10-01T09:00:00.000Z"), member("u-gone", "Gone", state = "removed"),
        )
        val contacts = listOf(ContactEntity("+1", "Amal", "", "u-old", true, groupReady = true), ContactEntity("+2", "Kamal", "", "u-kamal", true, groupReady = false))
        val ui = groupInfoUi(me, g, ms, contacts)
        assertEquals(listOf("Me", "Amal", "Zed"), ui.members.map { it.name })
        assertTrue(ui.iAmAdmin)
        assertEquals("Amal", ui.suggestedAdmin!!.name)
        assertEquals(listOf("u-kamal"), ui.addCandidates.map { it.userId }) // members aren't offered again
        assertFalse(ui.addCandidates.single().ready)
        val left = groupInfoUi(me, g.copy(state = "left"), ms, contacts)
        assertTrue(left.readOnly)
        assertFalse(left.iAmAdmin)
        assertEquals("You left this group", left.stateLine)
    }
}
