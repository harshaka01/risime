package lk.codegen.risime.ui.chats

import lk.codegen.risime.data.db.ChatTabEntity
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.db.LastMessage
import lk.codegen.risime.data.db.UnreadCount
import lk.codegen.risime.data.tabs.Tab
import lk.codegen.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** §24.9 the chats list: one row per chat_id with the newest message of either tab, its tab icon and the unread sum. */
class ChatListTabsTest {
    private val me = "aaaa0000-0000-4000-8000-00000000000a"
    private val kamal = "bbbb0000-0000-4000-8000-00000000000b"
    private val nimal = "cccc0000-0000-4000-8000-00000000000c"
    private val dm = dmConversationId(me, kamal)
    private val grp = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val grpOfficial = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val dmOfficial = "grp:11111111-8c9d-4e0f-9a1b-2c3d4e5f6a7b"

    private val contacts = listOf(
        ContactEntity("+1", "Kamal", "Co", kamal, true, friend = true),
        ContactEntity("+2", "Nimal", "Co", nimal, true, friend = true),
    )

    private fun group(conv: String, name: String) = GroupEntity(conv, name, "admin", "active", me, null, 1, 1, null, null, 1)

    private fun rows(lasts: List<LastMessage>, unread: List<UnreadCount>, tabs: Map<String, ChatTabEntity>?, pending: Map<String, String> = emptyMap(), tabsOn: Boolean = true): List<ChatRow> {
        val groups = buildGroupRows(me, listOf(group(grp, "Site team"), group(grpOfficial, "Site team"), group(dmOfficial, "")), emptyList(), lasts, unread)
        return mergeTabRows(buildChatRows(me, contacts, lasts, unread, groupRows = groups), tabs, pending, me, tabsOn)
    }

    private val officialTabs = mapOf(
        grpOfficial to ChatTabEntity(grpOfficial, grp, "official", "group"),
        dmOfficial to ChatTabEntity(dmOfficial, dm, "official", "dm"),
    )

    @Test fun oneRowPerChatWithTheNewestOfBothTabsAndTheUnreadSum() {
        val lasts = listOf(
            LastMessage(dm, "private hi", 100, false, "DELIVERED", from = kamal),
            LastMessage(dmOfficial, "official newer", 300, false, "DELIVERED", from = kamal),
            LastMessage(grp, "private newest", 500, false, "DELIVERED", from = kamal),
            LastMessage(grpOfficial, "official older", 200, false, "DELIVERED", from = kamal),
        )
        val unread = listOf(UnreadCount(dm, 1), UnreadCount(dmOfficial, 2), UnreadCount(grp, 3), UnreadCount(grpOfficial, 4))
        val r = rows(lasts, unread, officialTabs)
        assertEquals(listOf("Site team", "Kamal", "Nimal"), r.map { it.name })
        val site = r[0]
        assertEquals(grp, site.conversationId)
        assertEquals("private newest", site.last!!.body)
        assertEquals(Tab.PRIVATE, site.lastTab)
        assertEquals(7, site.unread)
        assertEquals(grpOfficial, site.officialConversationId)
        val k = r[1]
        assertEquals(kamal, k.userId)
        assertEquals("official newer", k.last!!.body)
        assertEquals(Tab.OFFICIAL, k.lastTab)
        assertEquals(3, k.unread)
        assertEquals(dmOfficial, k.officialConversationId)
        assertNull(r[2].lastTab) // no Official conversation: no icon
        // The chat row still opens the chat (its Private anchor id).
        assertEquals(dm, k.conversationOf(me))
    }

    @Test fun anOfficialConversationWithoutMessagesNeverWinsTheRow() {
        val r = rows(listOf(LastMessage(grp, "hello", 50, false, "READ", from = kamal)), emptyList(), officialTabs)
        val site = r.single { it.name == "Site team" }
        assertEquals("hello", site.last!!.body)
        assertEquals(Tab.PRIVATE, site.lastTab)
        assertEquals(3, r.size) // Site team, Kamal, Nimal: no Official row of its own
    }

    @Test fun anOfficialGroupIsNeverASeparateChatAndPendingOnesHideToo() {
        // MLS state not read yet, server says Official: hidden (not shown separately, not Official either).
        val r = rows(emptyList(), emptyList(), tabs = emptyMap(), pending = mapOf(grpOfficial to grp, dmOfficial to dm))
        assertEquals(listOf(grp), r.filter { it.group }.map { it.conversationId })
        // Without any tab knowledge (pre-v1.24 data): every group is its own chat, exactly as before.
        val before = rows(emptyList(), emptyList(), tabs = emptyMap())
        assertEquals(3, before.count { it.group })
    }

    @Test fun mlsPrivateWinsOverTheServersWord() {
        val tabs = mapOf(grpOfficial to ChatTabEntity(grpOfficial, grpOfficial, "private", "group"), dmOfficial to ChatTabEntity(dmOfficial, dm, "official", "dm"))
        val r = rows(emptyList(), emptyList(), tabs, pending = mapOf(grpOfficial to grp))
        assertEquals(setOf(grp, grpOfficial), r.filter { it.group }.map { it.conversationId }.toSet())
    }

    @Test fun tabsOffShowsNoTabIcon() {
        val lasts = listOf(LastMessage(dm, "a", 100, false, "READ", from = kamal), LastMessage(dmOfficial, "b", 300, false, "READ", from = kamal))
        val r = rows(lasts, emptyList(), officialTabs, tabsOn = false)
        assertNull(r.single { it.userId == kamal }.lastTab)
    }

    @Test fun withoutOfficialConversationsTheListIsUnchanged() {
        val lasts = listOf(LastMessage(dm, "a", 100, false, "READ", from = kamal), LastMessage(grp, "b", 300, false, "READ", from = kamal))
        val groups = buildGroupRows(me, listOf(group(grp, "Site team")), emptyList(), lasts, emptyList())
        val plain = buildChatRows(me, contacts, lasts, emptyList(), groupRows = groups)
        val tabs = mapOf(grp to ChatTabEntity(grp, grp, "private", "group"), dm to ChatTabEntity(dm, dm, "private", "dm"))
        assertEquals(plain, mergeTabRows(plain, tabs, emptyMap(), me, showTabIcon = true))
        assertEquals(plain, mergeTabRows(plain, null, emptyMap(), me, showTabIcon = false))
    }
}
