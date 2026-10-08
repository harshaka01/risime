package lk.codegen.risime.data.tabs

import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import lk.codegen.risime.data.db.AppDatabase
import lk.codegen.risime.data.db.ChatPrefEntity
import lk.codegen.risime.data.db.ChatTabEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.data.groups.systemText
import lk.codegen.risime.net.ChatEventActions
import lk.codegen.risime.net.ChatEventData
import lk.codegen.risime.net.GroupMeta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §24.1/§24.8/§24.9: tab mapping from MLS only, the default tab, unread per tab, `chat_event`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatTabsTest {
    private val me = "aaaa0000-0000-4000-8000-00000000000a"
    private val kamal = "bbbb0000-0000-4000-8000-00000000000b"
    private val risi = "9e1f0000-0000-4000-8000-000000000001"
    private val dm = "dm:${me}_$kamal"
    private val grp = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val off = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
        .setDriver(BundledSQLiteDriver()).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun tabs(serverOn: Boolean = false) = ChatTabs(db.chatTabs(), scope, persistedServerOn = serverOn).also { t ->
        runBlocking { withTimeout(5_000) { t.rows.first { it != null } } }
    }

    // ---- mapping (pure) ----

    @Test fun aDmIsAlwaysItsOwnPrivateTab() {
        assertEquals(ChatTabEntity(dm, dm, "private", "dm"), tabFromMls(dm, null))
        assertEquals(ChatTabEntity(dm, dm, "private", "dm"), tabFromMls(dm, GroupMeta(name = "x", tab = "official", chatId = grp)))
    }

    @Test fun aGroupWithoutMlsStateIsUnknownAndCountsAsPrivate() {
        assertNull(tabFromMls(grp, null))
        assertTrue(isPrivate(grp, emptyMap()))
        assertTrue(isPrivate(grp, null))
        assertEquals(grp, chatIdOf(grp, null))
    }

    @Test fun preV124MetaIsPrivateWithItsOwnId() {
        assertEquals(ChatTabEntity(grp, grp, "private", "group"), tabFromMls(grp, GroupMeta(name = "Team", admins = listOf(me))))
        // A Private meta naming another chat never joins that chat.
        assertEquals(ChatTabEntity(grp, grp, "private", "group"), tabFromMls(grp, GroupMeta(name = "Team", tab = "private", chatId = "grp:other")))
        assertEquals(ChatTabEntity(grp, grp, "private", "group"), tabFromMls(grp, GroupMeta(name = "Team", tab = "nonsense")))
    }

    @Test fun officialMetaMapsToItsChat() {
        assertEquals(ChatTabEntity(off, dm, "official", "dm"), tabFromMls(off, GroupMeta(name = "", admins = listOf(me, kamal), tab = "official", chatId = dm, agents = listOf(risi))))
        assertEquals(ChatTabEntity(off, grp, "official", "group"), tabFromMls(off, GroupMeta(name = "Team", tab = "official", chatId = grp, agents = listOf(risi))))
        assertEquals(ChatTabEntity(off, off, "official", "group"), tabFromMls(off, GroupMeta(name = "Team", tab = "official", chatId = " ")))
        val rows = mapOf(off to ChatTabEntity(off, grp, "official", "group"))
        assertFalse(isPrivate(off, rows))
        assertEquals(grp, chatIdOf(off, rows))
        assertEquals(off, officialConversationOf(grp, rows))
        assertNull(officialConversationOf(dm, rows))
    }

    @Test fun defaultTabIsTheLastUsedElsePrivate() {
        assertEquals(Tab.PRIVATE, defaultTab(null)) // a new 1:1 (and every migrated chat)
        assertEquals(Tab.PRIVATE, defaultTab(ChatPrefEntity(dm, null, "on")))
        assertEquals(Tab.OFFICIAL, defaultTab(ChatPrefEntity(grp, "official", "on"))) // a new group records Official at creation
        assertEquals(Tab.PRIVATE, defaultTab(ChatPrefEntity(grp, "private", "on")))
        assertEquals(Tab.OFFICIAL, defaultTab(ChatPrefEntity(grp, "official", null)))
        assertEquals("Official is off: the tab bar shows Private only", Tab.PRIVATE, defaultTab(ChatPrefEntity(grp, "official", "off")))
    }

    @Test fun unreadIsPerTabAndSummed() {
        val u = tabUnread(grp, off, mapOf(grp to 2, off to 3, dm to 7))
        assertEquals(TabUnread(2, 3), u)
        assertEquals(5, u.total)
        assertEquals(TabUnread(7, 0), tabUnread(dm, null, mapOf(grp to 2, off to 3, dm to 7)))
    }

    // ---- the store ----

    @Test fun recordedRowsComeFromMlsAndOfficialNeverTurnsPrivate() = runBlocking {
        val t = tabs()
        t.recordMls(off, null) // unknown: nothing recorded
        assertNull(db.chatTabs().get(off))
        t.noteServerTab(off, "official", grp)
        assertEquals(mapOf(off to grp), t.pendingOfficial.value)
        assertTrue("the server's word never makes it Official", t.private(off))
        t.recordMls(off, GroupMeta(name = "Team", tab = "official", chatId = grp, agents = listOf(risi)))
        assertEquals(ChatTabEntity(off, grp, "official", "group"), db.chatTabs().get(off))
        assertEquals(emptyMap<String, String>(), t.pendingOfficial.value)
        assertFalse(t.private(off))
        assertEquals(grp, t.chatId(off))
        assertEquals(off, t.officialOf(grp))
        // §24.1 rule 1: a later read can't move it back to Private.
        t.recordMls(off, GroupMeta(name = "Team"))
        assertEquals(ChatTabEntity(off, grp, "official", "group"), db.chatTabs().get(off))
        // A server hint for a group whose MLS state is known is ignored.
        t.recordMls(grp, GroupMeta(name = "Team"))
        t.noteServerTab(grp, "official", dm)
        assertTrue(t.private(grp))
        assertEquals(emptyMap<String, String>(), t.pendingOfficial.value)
    }

    @Test fun uiNeedsTheServerSwitchAndTheAdvertisedCapability() = runBlocking {
        val t = tabs()
        assertFalse(t.uiOn.value)
        t.setServerOn(true)
        assertFalse(withTimeout(1_000) { t.uiOn.first() })
        t.setAdvertised(true)
        assertTrue(withTimeout(1_000) { t.uiOn.first { it } })
        t.setServerOn(false)
        assertFalse(withTimeout(1_000) { t.uiOn.first { !it } })
    }

    @Test fun chatEventsSetTheStateAndAddLinesToBothTabs() = runBlocking {
        val t = tabs()
        t.recordMls(off, GroupMeta(name = "Team", tab = "official", chatId = grp, agents = listOf(risi)))
        val lines = mutableListOf<MessageEntity>()
        suspend fun apply(id: String, action: String, actor: String = kamal) =
            t.applyChatEvent(id, ChatEventData(grp, action, actor, off, "2026-10-08T09:15:30.456Z"), me, { if (it == kamal) "Kamal" else "Someone" }, { lines += it }, now = 5)

        apply("e1", ChatEventActions.OFFICIAL_CREATED)
        assertEquals("on", db.chatTabs().pref(grp)!!.officialState)
        assertEquals(emptyList<MessageEntity>(), lines)

        apply("e2", ChatEventActions.OFFICIAL_OFF)
        assertEquals("off", db.chatTabs().pref(grp)!!.officialState)
        assertEquals(listOf(grp, off), lines.map { it.conversationId })
        assertEquals(listOf("Kamal turned Official off", "Kamal turned Official off"), lines.map { it.body })
        assertTrue(lines.all { it.kind == MessageEntity.KIND_SYSTEM && it.status == "READ" })
        // The group screen re-renders the line from its JSON: the same text.
        assertEquals("Kamal turned Official off", systemText(SystemLine.decode(lines[0].systemJson)!!, me) { "Kamal" })

        lines.clear()
        apply("e3", ChatEventActions.OFFICIAL_ON, actor = me)
        assertEquals("on", db.chatTabs().pref(grp)!!.officialState)
        assertEquals(listOf("You turned Official on", "You turned Official on"), lines.map { it.body })

        // An Official conversation this device doesn't hold gets no line; unknown actions change nothing.
        lines.clear()
        t.applyChatEvent("e4", ChatEventData(dm, ChatEventActions.OFFICIAL_OFF, kamal, "grp:unknown", null), me, { "Kamal" }, { lines += it }, 6)
        assertEquals(listOf(dm), lines.map { it.conversationId })
        t.applyChatEvent("e5", ChatEventData(dm, "renamed", kamal, null, null), me, { "Kamal" }, { lines += it }, 7)
        assertEquals("off", db.chatTabs().pref(dm)!!.officialState)
        assertEquals(1, lines.size)
    }

    @Test fun lastTabIsKeptPerChat() = runBlocking {
        val t = tabs()
        t.setLastTab(grp, Tab.OFFICIAL)
        t.setOfficialState(grp, "on")
        assertEquals(ChatPrefEntity(grp, "official", "on"), db.chatTabs().pref(grp))
        assertEquals(Tab.OFFICIAL, defaultTab(t.prefs.value[grp]))
        assertEquals(Tab.PRIVATE, defaultTab(t.prefs.value[dm]))
    }
}
