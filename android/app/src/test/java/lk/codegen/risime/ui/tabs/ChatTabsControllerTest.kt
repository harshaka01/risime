package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import lk.codegen.risime.data.db.AppDatabase
import lk.codegen.risime.data.tabs.ChatTabs
import lk.codegen.risime.data.tabs.OfficialState
import lk.codegen.risime.data.tabs.Tab
import lk.codegen.risime.data.tabs.TabUnread
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.Group
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §24.2/§24.4/§24.9 one chat's tabs: default tab, lazy creation with the intro card, read-only history, per-tab unread; tabs off = the old screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatTabsControllerTest {
    @get:Rule val rule = createComposeRule()

    private val dm = "dm:aaaa_bbbb"
    private val grp = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val official = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val risi = "9e1f0000-0000-4000-8000-000000000001"

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
        .setDriver(BundledSQLiteDriver()).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tabs = ChatTabs(db.chatTabs(), scope).also { t -> runBlocking { withTimeout(5_000) { t.rows.first { it != null } } } }
    private val unread = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** The fake `POST …/official`: records calls; answers [reply]. */
    private val starts = mutableListOf<String>()
    private var reply: ApiResult<*> = ApiResult.Ok(Group(official, state = "creating"))
    private val starter = OfficialStarter { id ->
        starts += id
        reply.also { if (it is ApiResult.Ok) tabs.markStarting(id) }
    }

    @After fun tearDown() {
        // Cancel AND join every collector before closing: a Room flow still collecting on a closed
        // connection throws "connection is closed".
        runBlocking { scope.coroutineContext[Job]!!.let { it.cancel(); it.join() } }
        db.close()
    }

    private fun controller(chat: String = dm, initial: Tab? = null) = ChatTabsController(chat, tabs, starter, scope, initial, unread)

    private fun <T> StateFlow<T>.await(p: (T) -> Boolean): T = runBlocking { withTimeout(5_000) { first(p) } }

    private suspend fun officialReadable(chat: String) = tabs.recordMls(official, GroupMeta(name = "", tab = "official", chatId = chat, agents = listOf(risi)))

    @Test fun aNew1to1OpensOnPrivateAndCreatesOfficialOnTheFirstTap() = runBlocking {
        val c = controller()
        assertEquals(TabContent.Private, c.content.await { it == TabContent.Private })
        c.select(Tab.OFFICIAL)
        assertEquals(TabContent.Intro(busy = false, error = null), c.content.await { it is TabContent.Intro })
        assertTrue(starts.isEmpty()) // nothing is created until the tap (lazy)
        c.startOfficial()
        c.content.await { it == TabContent.Starting }
        assertEquals(listOf(dm), starts)
        // The Welcome lands: the MLS meta says Official of this chat.
        officialReadable(dm)
        assertEquals(TabContent.Official(official, readOnly = false), c.content.await { it is TabContent.Official })
        assertEquals("official", db.chatTabs().pref(dm)!!.lastTab) // remembered as the last tab
        // The next open starts on Official.
        assertEquals(Tab.OFFICIAL, controller().tab.await { it == Tab.OFFICIAL })
    }

    @Test fun notReadyKeepsTheIntroCardWithTheReason() = runBlocking {
        reply = ApiResult.Error(409, "not_ready", "")
        val c = controller(grp, initial = Tab.OFFICIAL)
        c.content.await { it is TabContent.Intro }
        c.startOfficial()
        val intro = c.content.await { it is TabContent.Intro && it.error != null } as TabContent.Intro
        assertEquals(OFFICIAL_NOT_READY_TEXT, intro.error)
        assertFalse(intro.busy)
        reply = ApiResult.Error(503, "agent_unavailable", "")
        c.startOfficial()
        assertEquals("Risi isn't available right now. Try again later.", (c.content.await { it is TabContent.Intro && it.error?.startsWith("Risi") == true } as TabContent.Intro).error)
    }

    @Test fun aNewGroupOpensOnOfficialOnceItsCreationRecordedIt(): Unit = runBlocking {
        tabs.setLastTab(grp, Tab.OFFICIAL) // what startOfficialForNewGroup records on success
        tabs.markStarting(grp)
        val c = controller(grp)
        assertEquals(TabContent.Starting, c.content.await { it == TabContent.Starting })
        officialReadable(grp)
        c.content.await { it == TabContent.Official(official, false) }
    }

    @Test fun anOfficialConversationIdOpensTheOfficialTab() = runBlocking {
        officialReadable(dm)
        val c = controller(initial = Tab.OFFICIAL)
        assertEquals(TabContent.Official(official, false), c.content.await { it is TabContent.Official })
    }

    @Test fun officialOffShowsPrivateOnlyWithItsReadOnlyHistory() = runBlocking {
        officialReadable(grp)
        tabs.setLastTab(grp, Tab.OFFICIAL)
        tabs.setOfficialState(grp, OfficialState.OFF)
        val c = controller(grp)
        assertEquals(TabContent.Private, c.content.await { it == TabContent.Private })
        val bar = c.bar.await { !it.showOfficial }
        assertTrue(bar.historyAvailable)
        c.openHistory()
        assertEquals(TabContent.Official(official, readOnly = true), c.content.await { it is TabContent.Official })
        // Off and never created: no history, Private only.
        tabs.setOfficialState(dm, OfficialState.OFF)
        val d = controller(dm, initial = Tab.OFFICIAL)
        assertEquals(TabContent.Private, d.content.await { it == TabContent.Private })
        assertFalse(d.bar.await { !it.showOfficial }.historyAvailable)
    }

    @Test fun unreadBadgesArePerTab() = runBlocking {
        officialReadable(grp)
        unread.value = mapOf(grp to 2, official to 5, dm to 9)
        assertEquals(TabUnread(2, 5), controller(grp).bar.await { it.unread.total > 0 }.unread)
    }

    // ---- Compose ----

    @Test fun tabsOffShowsExactlyTheOldScreen() {
        var barPassed: Any? = "unset"
        rule.setContent {
            RisiMeTheme {
                TabbedChat(
                    tabsOn = false,
                    vm = { error("no tab state when tabs are off") },
                    onBack = {},
                    privateScreen = { tabBar -> barPassed = tabBar; Text("the v1.23 chat") },
                    officialScreen = { _, _, _ -> error("no Official when tabs are off") },
                )
            }
        }
        rule.onNodeWithText("the v1.23 chat").assertIsDisplayed()
        assertEquals(null, barPassed)
        assertEquals(0, rule.onAllNodesWithTag("chat_tabs").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText(PRIVATE_TAB_LABEL).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText(OFFICIAL_TAB_LABEL).fetchSemanticsNodes().size)
    }

    @Test fun tabsOnShowTheBarUnderTheHeaderAndTheIntroCardStartsOfficial() {
        val c = controller(grp)
        rule.setContent {
            RisiMeTheme {
                TabbedChatContent(
                    c, "Site team", onBack = {},
                    privateScreen = { tabBar -> androidx.compose.foundation.layout.Column { tabBar?.invoke(); Text("private screen") } },
                    officialScreen = { conv, _, tabBar -> androidx.compose.foundation.layout.Column { tabBar(); Text("official $conv") } },
                )
            }
        }
        rule.onNodeWithText("private screen").assertIsDisplayed()
        rule.onNodeWithText(PRIVATE_TAB_LABEL).assertIsDisplayed()
        rule.onNodeWithText(OFFICIAL_TAB_LABEL).assertIsDisplayed()
        rule.onNodeWithTag("tab_official").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("start_official").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(OFFICIAL_INTRO_TEXT).assertIsDisplayed()
        rule.onNodeWithTag("start_official").performClick()
        rule.waitUntil(5_000) { starts.isNotEmpty() }
        assertEquals(listOf(grp), starts)
        runBlocking { officialReadable(grp) }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("official $official").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(RISI_LISTENING).assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithText(text: String) =
    onAllNodes(androidx.compose.ui.test.hasText(text))
