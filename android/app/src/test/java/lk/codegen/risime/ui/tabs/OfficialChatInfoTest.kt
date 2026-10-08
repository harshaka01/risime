package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import lk.codegen.risime.data.db.AppDatabase
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.db.GroupMemberEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.ChatTabs
import lk.codegen.risime.data.tabs.OfficialState
import lk.codegen.risime.data.tabs.Tab
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.Chat
import lk.codegen.risime.net.ChatOfficial
import lk.codegen.risime.net.ChatPrivate
import lk.codegen.risime.net.Group
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.ui.group.groupInfoUi
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §24 A4: the per-chat Official switch in chat info (1:1 either person, group admins only, "Turn
 * Official off?" first), "Official history (read-only)", Risi in the Official member list with an
 * "AI agent" badge and no actions, both tabs' media, the Official banner on the first open — and
 * nothing Risi-related anywhere in Private.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfficialChatInfoTest {
    @get:Rule val rule = createComposeRule()

    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val kamal = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val risi = "9e1f0000-0000-4000-8000-000000000001"
    private val dm = "dm:0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e_7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val grp = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val official = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
        .setDriver(BundledSQLiteDriver()).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = mutableSetOf<String>()
    private val tabs = ChatTabs(db.chatTabs(), scope, persistBannerSeen = { seen.clear(); seen.addAll(it) })
        .also { t -> runBlocking { withTimeout(5_000) { t.rows.first { it != null } } } }

    private val patches = mutableListOf<Pair<String, Boolean>>()
    private var reply: (String, Boolean) -> ApiResult<Chat> = { id, on ->
        ApiResult.Ok(Chat(id, if (id.startsWith("dm:")) "dm" else "group", ChatPrivate(id), ChatOfficial(if (on) "on" else "off", official), canToggle = true))
    }
    private val toggler = OfficialToggler { id, on -> patches += id to on; reply(id, on) }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun <T> StateFlow<T>.await(p: (T) -> Boolean): T = runBlocking { withTimeout(5_000) { first(p) } }

    private fun member(conv: String, user: String, name: String, role: String = GroupMember.ROLE_MEMBER, kind: String = GroupMember.KIND_USER) =
        GroupMemberEntity(conv, user, name, null, role, kind, GroupMember.STATE_ACTIVE, null)

    private suspend fun officialReadable(chat: String) = tabs.recordMls(official, GroupMeta(name = "", tab = "official", chatId = chat, agents = listOf(risi)))

    private fun switch(chat: String, admin: Boolean = false) = OfficialSwitchController(
        chat, tabs, toggler, scope, iAmAdmin = MutableStateFlow(admin),
        officialMembers = { conv -> db.groups().observeMembers(conv) }, agentUsers = { setOf(risi) }, me = me,
    )

    // ---- who may toggle ----

    @Test fun eitherPersonTogglesA1to1AndOnlyAdminsAGroup() {
        assertTrue(canToggleOfficial(dm, iAmAdmin = false, serverCanToggle = null))
        assertFalse(canToggleOfficial(grp, iAmAdmin = false, serverCanToggle = null))
        assertTrue(canToggleOfficial(grp, iAmAdmin = true, serverCanToggle = null))
        // The server's can_toggle wins once known.
        assertFalse(canToggleOfficial(grp, iAmAdmin = true, serverCanToggle = false))
        val nonAdmin = switch(grp, admin = false).ui.await { it.disabledReason != null }
        assertFalse(nonAdmin.canToggle)
        assertEquals(ONLY_ADMINS_OFF_TEXT, nonAdmin.disabledReason)
        assertNull(switch(dm).ui.await { it.canToggle }.disabledReason)
        assertTrue(switch(grp, admin = true).ui.await { it.canToggle }.canToggle)
    }

    @Test fun aDisabledSwitchNeverPatches(): Unit = runBlocking {
        val s = switch(grp, admin = false)
        s.ui.await { !it.canToggle }
        s.request(false)
        Thread.sleep(200)
        assertFalse(s.ui.value.confirmingOff)
        assertTrue(patches.isEmpty())
    }

    // ---- turning off and on ----

    @Test fun turningOffAsksFirstThenPatchesAndKeepsTheHistoryReadOnly(): Unit = runBlocking {
        officialReadable(dm)
        val s = switch(dm)
        s.ui.await { it.canToggle && it.officialConversation == official }
        s.request(false)
        assertTrue(s.ui.await { it.confirmingOff }.confirmingOff)
        assertTrue("nothing is sent before the confirmation", patches.isEmpty())
        s.cancelOff()
        s.ui.await { !it.confirmingOff }
        assertTrue(patches.isEmpty())
        s.request(false)
        s.ui.await { it.confirmingOff }
        s.confirmOff()
        val off = s.ui.await { !it.on && !it.busy }
        assertEquals(listOf(dm to false), patches)
        assertEquals(OfficialState.OFF, db.chatTabs().pref(dm)!!.officialState)
        assertTrue("Official history (read-only)", off.historyAvailable)
        // Hard rule 9: the Official conversation is still this chat's (nothing deleted or re-tabbed).
        assertEquals(official, tabs.officialOf(dm))
        // On again: no question, one PATCH.
        s.request(true)
        s.ui.await { it.on && !it.busy }
        assertEquals(listOf(dm to false, dm to true), patches)
        assertFalse(s.ui.value.historyAvailable)
    }

    @Test fun aRefusedToggleShowsWhy(): Unit = runBlocking {
        reply = { _, _ -> ApiResult.Error(403, "not_admin", "") }
        val s = switch(grp, admin = true)
        s.ui.await { it.canToggle }
        s.request(false)
        s.ui.await { it.confirmingOff }
        s.confirmOff()
        assertEquals(ONLY_ADMINS_OFF_TEXT, s.ui.await { it.error != null }.error)
        assertEquals(null, db.chatTabs().pref(grp)?.officialState) // unchanged
        assertEquals("Official was changed too often today. Try again tomorrow.", officialToggleErrorText("rate_limited", false))
        assertEquals("You're offline. Try again when you're connected.", officialToggleErrorText(null, true))
    }

    // ---- members: Risi only in Official, with a badge and no actions ----

    @Test fun risiIsAnOfficialMemberWithABadgeAndNeverInThePrivateList(): Unit = runBlocking {
        officialReadable(grp)
        db.groups().upsertMembers(
            listOf(
                member(official, me, "Me", GroupMember.ROLE_ADMIN), member(official, kamal, "Kamal"),
                member(official, risi, "Risi", kind = GroupMember.KIND_AGENT),
            ),
        )
        val ui = switch(grp, admin = true).ui.await { it.officialMembers.size == 3 }
        assertEquals(listOf(false, false, true), ui.officialMembers.map { it.agent })
        assertEquals("Risi", ui.officialMembers.last().name)
        // The attested kind alone is enough (the server JSON may lag).
        val attested = officialMembersUi(listOf(member(official, risi, "")), setOf(risi.uppercase()), me)
        assertTrue(attested.single().agent)
        assertEquals("Risi", attested.single().name)
        // The Private member list never shows an agent, even if one were ever listed.
        val privateUi = groupInfoUi(
            me, GroupEntity(grp, "Site team", GroupMember.ROLE_ADMIN, GroupEntity.STATE_ACTIVE, null, null, 1, null, null, null, 0),
            listOf(member(grp, me, "Me", GroupMember.ROLE_ADMIN), member(grp, kamal, "Kamal"), member(grp, risi, "Risi", kind = GroupMember.KIND_AGENT)),
            emptyList(),
        )
        assertEquals(listOf(me, kamal), privateUi.members.map { it.userId })

        rule.setContent { RisiMeTheme { LazyColumn { officialInfoItems(ui, {}, {}, {}) } } }
        rule.onNodeWithText(AI_AGENT_BADGE).assertIsDisplayed()
        rule.onNodeWithTag("official_member_agent").assertHasNoClickAction()
    }

    // ---- Compose: the switch, the confirmation, history, media ----

    @Test fun theSwitchConfirmsOffWithTheContractText() {
        runBlocking { officialReadable(dm) }
        val s = switch(dm)
        s.ui.await { it.canToggle && it.officialConversation != null }
        var history = 0
        rule.setContent {
            RisiMeTheme {
                val ui = s.ui.collectAsStateValue()
                DmChatInfoContent(
                    "Kamal", kamal, true, null, ui, TabMedia(), onBack = {},
                    onToggle = s::request, onConfirmOff = s::confirmOff, onCancelOff = s::cancelOff,
                    onHistory = { history++ }, onDismissError = s::dismissError, thumb = { Text("thumb") },
                )
            }
        }
        rule.onNodeWithTag("official_switch_row").assertIsOn()
        rule.onNodeWithTag("official_switch_row").performClick()
        rule.onNodeWithText(OFFICIAL_OFF_CONFIRM_TEXT).assertIsDisplayed()
        rule.onNodeWithText(OFFICIAL_OFF_CONFIRM_BUTTON).performClick()
        rule.waitUntil(5_000) { patches.isNotEmpty() && !s.ui.value.busy }
        rule.waitForIdle()
        rule.onNodeWithTag("official_switch_row").assertIsOff()
        rule.onNodeWithText(OFFICIAL_HISTORY_LABEL).performClick()
        assertEquals(1, history)
    }

    @Test fun aNonAdminSeesTheSwitchDisabledWithTheReason() {
        val s = switch(grp, admin = false)
        s.ui.await { it.disabledReason != null }
        rule.setContent {
            RisiMeTheme {
                val ui = s.ui.collectAsStateValue()
                LazyColumn { officialInfoItems(ui, s::request, {}, {}) }
            }
        }
        rule.onNodeWithTag("official_switch_row").assertIsNotEnabled()
        rule.onNodeWithText(ONLY_ADMINS_OFF_TEXT).assertIsDisplayed()
    }

    @Test fun mediaHasOneSectionPerTab() {
        fun img(conv: String, i: Int) = MessageEntity("cm-$conv-$i", "m$i", conv, kamal, me, "", null, i.toLong(), "READ", false, kind = MessageEntity.KIND_IMAGE)
        val media = TabMedia(listOf(img(grp, 1), img(grp, 2)), listOf(img(official, 3)))
        rule.setContent {
            RisiMeTheme {
                LazyColumn { tabMediaItems(media, showOfficial = true) { m -> Text("photo ${m.conversationId}", Modifier.size(40.dp)) } }
            }
        }
        rule.onNodeWithText(PRIVATE_MEDIA_HEADER).assertIsDisplayed()
        rule.onNodeWithText(OFFICIAL_MEDIA_HEADER).assertIsDisplayed()
        assertEquals(2, rule.onAllNodes(hasText("photo $grp")).fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodes(hasText("photo $official")).fetchSemanticsNodes().size)
    }

    @Test fun withoutAnOfficialConversationOnlyPrivateMediaShows() {
        rule.setContent { RisiMeTheme { LazyColumn { tabMediaItems(TabMedia(), showOfficial = false) { Text("x") } } } }
        rule.onNodeWithText(PRIVATE_MEDIA_HEADER).assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(hasText(OFFICIAL_MEDIA_HEADER)).fetchSemanticsNodes().size)
    }

    // ---- the banner, and nothing Risi in Private ----

    @Test fun theOfficialBannerShowsOnTheFirstOpenOnly(): Unit = runBlocking {
        officialReadable(dm)
        val starter = OfficialStarter { ApiResult.Ok(Group(official)) }
        val first = ChatTabsController(dm, tabs, starter, scope, Tab.OFFICIAL)
        assertTrue(first.banner.await { it })
        assertEquals(setOf(dm), seen) // persisted at once
        first.dismissBanner()
        assertFalse(first.banner.await { !it })
        val second = ChatTabsController(dm, tabs, starter, scope, Tab.OFFICIAL)
        second.content.await { it is TabContent.Official }
        Thread.sleep(200)
        assertFalse(second.banner.value)
        // A read-only (off) Official never shows it.
        tabs.setOfficialState(grp, OfficialState.OFF)
    }

    @Test fun theBannerRendersInOfficialAndNothingRisiAppearsInPrivate() {
        runBlocking { officialReadable(grp) }
        val c = ChatTabsController(grp, tabs, { ApiResult.Ok(Group(official)) }, scope, Tab.PRIVATE)
        rule.setContent {
            RisiMeTheme {
                TabbedChatContent(
                    c, "Site team", onBack = {},
                    privateScreen = { tabBar -> Column { tabBar?.invoke(); Text("private screen") } },
                    officialScreen = { conv, _, tabBar -> Column { tabBar(); Text("official $conv") } },
                )
            }
        }
        rule.onNodeWithText("private screen").assertIsDisplayed()
        // §24.9: Private never offers any Risi affordance (no card, chip, banner or "Risi is listening").
        assertEquals(0, rule.onAllNodes(hasText("Risi", substring = true)).fetchSemanticsNodes().size)
        rule.onNodeWithTag("tab_official").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("official $official")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(OFFICIAL_BANNER_TEXT).assertIsDisplayed()
        rule.onNodeWithTag("official_banner_ok").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(OFFICIAL_BANNER_TEXT)).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("tab_private").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("private screen")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, rule.onAllNodes(hasText("Risi", substring = true)).fetchSemanticsNodes().size)
    }
}

@androidx.compose.runtime.Composable
private fun <T> StateFlow<T>.collectAsStateValue(): T = collectAsState().value
