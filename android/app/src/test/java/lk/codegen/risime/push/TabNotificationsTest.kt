package lk.codegen.risime.push

import lk.codegen.risime.data.db.ChatTabEntity
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §24.9 notification labels per tab ("Kamal · Official", "Kamal · 🔒 Private", "Team · Official"),
 * decision 064 hide-content and locked chats staying generic, and the §24.11 `risi.notify` filter
 * (a Risi message for someone else is silent: no notification, still unread).
 */
class TabNotificationsTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val kamal = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val risi = "9e1f0000-0000-4000-8000-000000000001"
    private val dm = "dm:${kamal}_$me"
    private val grp = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val grpOfficial = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val dmOfficial = "grp:6a7b8c9d-0e1f-4a2b-8c3d-4e5f6a7b8c9d"

    private val contacts = listOf(ContactEntity("+94771234568", "Kamal", "Rise", kamal, true))
    private val groupNames = mapOf(grp to "Team", grpOfficial to "Team", dmOfficial to "Group")
    private val rows = mapOf(
        grpOfficial to ChatTabEntity(grpOfficial, grp, ChatTabEntity.TAB_OFFICIAL, ChatTabEntity.KIND_GROUP),
        dmOfficial to ChatTabEntity(dmOfficial, dm, ChatTabEntity.TAB_OFFICIAL, ChatTabEntity.KIND_DM),
    )

    private var n = 0
    private fun inc(conv: String, from: String = kamal, body: String = "hi", risiJson: String? = null) =
        MessageEntity("c${++n}", "m$n", conv, from, if (conv.startsWith("grp:")) conv else me, body, null, n.toLong(), "DELIVERED", false, systemJson = risiJson)

    private fun plan(msgs: List<MessageEntity>, tabsOn: Boolean = true) =
        planChatNotifications(msgs, contacts, 0, groupNames = groupNames, me = me, tabsOn = tabsOn, tabRows = rows)
            .associateBy { it.conversationId }

    private fun risiNotifying(vararg users: String) =
        """{"v":1,"kind":"reminder","commitment_id":"5c0e1d2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f","due":"2026-10-09T11:30:00.000Z","call_ref":null,"notify":[${users.joinToString(",") { "\"$it\"" }}]}"""

    @Test fun titlesCarryTheTab() {
        val p = plan(listOf(inc(dm), inc(dmOfficial), inc(grp), inc(grpOfficial)))
        assertEquals("Kamal · 🔒 Private", p.getValue(dm).title)
        assertEquals("Kamal · Official", p.getValue(dmOfficial).title) // a 1:1 Official has no name: the peer's
        assertEquals("Team · 🔒 Private", p.getValue(grp).title)
        assertEquals("Team · Official", p.getValue(grpOfficial).title)
    }

    @Test fun tabsOffKeepsTheOldTitles() {
        val p = plan(listOf(inc(dm), inc(grp)), tabsOn = false)
        assertEquals("Kamal", p.getValue(dm).title)
        assertEquals("Team", p.getValue(grp).title)
    }

    @Test fun hiddenContentAndLockedChatsStayGeneric() {
        val p = plan(listOf(inc(dmOfficial), inc(grp)))
        val hidden = redactForLock(p.getValue(dmOfficial))
        assertEquals("RisiMe", hidden.title)
        assertTrue(hidden.lines.none { it.contains("Official") || it.contains("Kamal") })
        val locked = redactLockedChat(p.getValue(grp))
        assertEquals("RisiMe", locked.title)
        assertEquals(listOf(LOCKED_CONTENT_TEXT), locked.lines)
    }

    @Test fun aRisiMessageNotifiesOnlyTheUsersInNotify() {
        val forMe = inc(grpOfficial, from = risi, body = "Reminder for you", risiJson = risiNotifying(me))
        val forKamal = inc(grpOfficial, from = risi, body = "Reminder for Kamal", risiJson = risiNotifying(kamal))
        val nobody = inc(dmOfficial, from = risi, body = "Digest", risiJson = risiNotifying())
        val p = plan(listOf(forMe, forKamal, nobody))
        assertEquals(listOf("Risi: Reminder for you".substringAfter(": ")), p.getValue(grpOfficial).lines.map { it.substringAfter(": ") })
        assertEquals(1, p.getValue(grpOfficial).count)
        assertTrue("silent for everyone else", dmOfficial !in p)
        // A plain message in the same chat still notifies; unread is untouched (rows stay DELIVERED).
        val withHuman = plan(listOf(forKamal, inc(grpOfficial, body = "human")))
        assertEquals(1, withHuman.getValue(grpOfficial).count)
        assertEquals("DELIVERED", forKamal.status)
    }
}
