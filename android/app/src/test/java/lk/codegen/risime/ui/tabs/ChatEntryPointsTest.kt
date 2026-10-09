package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import lk.codegen.risime.data.mls.E2EE_INFO_TEXT
import lk.codegen.risime.data.tabs.Tab
import lk.codegen.risime.data.tabs.TabUnread
import lk.codegen.risime.ui.chats.ChatSelectionBar
import lk.codegen.risime.ui.chats.HIDE_LOCKED_CHATS
import lk.codegen.risime.ui.chats.LockedChatsSettingsContent
import lk.codegen.risime.ui.chats.SECRET_CODE
import lk.codegen.risime.ui.lock.ChatLockControl
import lk.codegen.risime.ui.lock.LOCK_CHAT_LABEL
import lk.codegen.risime.ui.lock.UNLOCK_CHAT_LABEL
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The nightly.39 real-phone report: no way to lock a chat, no tab row. The entry points as the user
 * sees them: chat info's "Lock chat" (tabs on and off), the chat's ⋮, the long-press selection bar's
 * ⋮, Chat lock settings, and the tab row with "Start Official" while Official is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatEntryPointsTest {
    @get:Rule val rule = createComposeRule()

    private fun info(official: OfficialInfoUi?, lock: ChatLockControl, encrypted: Boolean = true, reason: String? = null) {
        rule.setContent {
            RisiMeTheme {
                DmChatInfoContent(
                    "Kumu", "kumu", encrypted, reason, official, TabMedia(), onBack = {},
                    onToggle = {}, onConfirmOff = {}, onCancelOff = {}, onHistory = {}, onDismissError = {}, thumb = { Text("thumb") },
                    lock = lock,
                )
            }
        }
    }

    @Test fun chatInfoHasLockChatUnderTheOfficialSectionWithTabsOn() {
        var toggles = 0
        info(OfficialInfoUi(on = true, canToggle = true), ChatLockControl(false) { toggles++ })
        rule.onNodeWithTag("official_switch_row").assertIsDisplayed()
        rule.onNodeWithText(LOCK_CHAT_LABEL).assertIsDisplayed()
        rule.onNodeWithText(CHAT_LOCK_INFO_TEXT).assertIsDisplayed()
        rule.onNodeWithTag("chat_lock_row").assertIsOff()
        // Under the Official section (below the switch).
        val sw = rule.onNodeWithTag("official_switch_row").fetchSemanticsNode().boundsInRoot.top
        val lock = rule.onNodeWithTag("chat_lock_row").fetchSemanticsNode().boundsInRoot.top
        assert(lock > sw) { "Lock chat ($lock) must be under the Official switch ($sw)" }
        rule.onNodeWithTag("chat_lock_row").performClick()
        assertEquals(1, toggles)
        rule.onNodeWithText(E2EE_INFO_TEXT).assertIsDisplayed()
    }

    @Test fun chatInfoHasLockChatWithTabsOffToo() {
        var toggles = 0
        info(null, ChatLockControl(true) { toggles++ }, encrypted = false, reason = "Not end-to-end encrypted yet: Kumu needs to update RisiMe")
        rule.onNodeWithTag("official_switch_row").assertDoesNotExist()
        rule.onNodeWithText(LOCK_CHAT_LABEL).assertIsDisplayed()
        rule.onNodeWithTag("chat_lock_row").assertIsOn()
        rule.onNodeWithText(CHAT_UNLOCK_INFO_TEXT).assertIsDisplayed()
        rule.onNodeWithText(MEDIA_HEADER).assertIsDisplayed()
        rule.onNodeWithText(PRIVATE_MEDIA_HEADER).assertDoesNotExist()
        rule.onNodeWithText("Not end-to-end encrypted yet: Kumu needs to update RisiMe").assertIsDisplayed()
        rule.onNodeWithText("Checking encryption…").assertDoesNotExist()
        rule.onNodeWithTag("chat_lock_row").performClick()
        assertEquals(1, toggles)
    }

    @Test fun theChatsOverflowMenuHasLockAndUnlock() {
        var locked by androidx.compose.runtime.mutableStateOf(false)
        var toggles = 0
        rule.setContent {
            RisiMeTheme {
                lk.codegen.risime.ui.chat.ChatOverflowMenu(onClear = {}, onDelete = {}, lock = ChatLockControl(locked) { toggles++ })
            }
        }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText(LOCK_CHAT_LABEL).performClick()
        assertEquals(1, toggles)
        locked = true
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText(UNLOCK_CHAT_LABEL).assertIsDisplayed()
    }

    @Test fun theIntroScreensMenuHasOnlyLockChat() {
        var toggles = 0
        rule.setContent { RisiMeTheme { lk.codegen.risime.ui.chat.ChatOverflowMenu(onClear = null, onDelete = null, lock = ChatLockControl(false) { toggles++ }) } }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Clear chat").assertDoesNotExist()
        rule.onNodeWithText(LOCK_CHAT_LABEL).performClick()
        assertEquals(1, toggles)
    }

    @Test fun longPressSelectionBarOverflowHasLockChat() {
        var locks = 0
        var deletes = 0
        var closes = 0
        rule.setContent { RisiMeTheme { ChatSelectionBar(onClose = { closes++ }, onDelete = { deletes++ }, onClear = {}, onLock = { locks++ }) } }
        rule.onNodeWithText("1").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete chat").performClick()
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Clear chat").assertIsDisplayed()
        rule.onNodeWithText(LOCK_CHAT_LABEL).performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf(1, 1, 1), listOf(locks, deletes, closes))
    }

    @Test fun chatLockSettingsHideAndSecretCode() {
        val hides = mutableListOf<Boolean>()
        var code = 0
        var has by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent { RisiMeTheme { LockedChatsSettingsContent(has, onBack = {}, onHide = { hides += it }, onSecretCode = { code++ }) } }
        rule.onNodeWithText(HIDE_LOCKED_CHATS).assertIsDisplayed().performClick()
        rule.onNodeWithText(SECRET_CODE).assertIsDisplayed().performClick()
        has = true
        rule.onNodeWithText(HIDE_LOCKED_CHATS).performClick()
        assertEquals(listOf(true, false), hides)
        assertEquals(1, code)
    }

    // ---- the tab row ----

    private fun bar(state: TabBarState, onStart: () -> Unit = {}) {
        rule.setContent { RisiMeTheme { LazyColumn { item { ChatTabBar(state, onSelect = {}, onHistory = {}, onStartOfficial = onStart) } } } }
    }

    @Test fun theTabRowShowsBothTabs() {
        bar(TabBarState(Tab.PRIVATE, showOfficial = true, unread = TabUnread(0, 2), historyAvailable = false, canStartOfficial = true))
        rule.onNodeWithText(PRIVATE_TAB_LABEL).assertIsDisplayed()
        rule.onNodeWithText(OFFICIAL_TAB_LABEL).assertIsDisplayed()
        rule.onNodeWithTag("tab_start_official").assertDoesNotExist()
    }

    @Test fun officialOffShowsPrivateWithStartOfficial() {
        var starts = 0
        bar(TabBarState(Tab.PRIVATE, showOfficial = false, unread = TabUnread(0, 0), historyAvailable = true, canStartOfficial = true)) { starts++ }
        rule.onNodeWithText(PRIVATE_TAB_LABEL).assertIsDisplayed()
        rule.onNodeWithText(OFFICIAL_TAB_LABEL).assertDoesNotExist()
        rule.onNodeWithText(OFFICIAL_HISTORY_LABEL).assertIsDisplayed()
        rule.onNodeWithTag("tab_start_official").assertIsEnabled().performClick()
        assertEquals(1, starts)
    }

    @Test fun aGroupMemberWhoIsNotAnAdminCantStartOfficial() {
        bar(TabBarState(Tab.PRIVATE, showOfficial = false, unread = TabUnread(0, 0), historyAvailable = false, canStartOfficial = false))
        rule.onNodeWithTag("tab_start_official").assertIsNotEnabled()
        rule.onNodeWithText(START_OFFICIAL_ADMINS_ONLY).assertIsDisplayed()
    }
}
