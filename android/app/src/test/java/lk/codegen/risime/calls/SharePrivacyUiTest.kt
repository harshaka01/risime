package lk.codegen.risime.calls

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import lk.codegen.risime.ui.lock.AppLockScreen
import lk.codegen.risime.ui.lock.SecureScreen
import lk.codegen.risime.ui.lock.SecureScreens
import lk.codegen.risime.ui.lock.SecureWindow
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Harsha's report: an "Entire screen" share showed black while RisiMe was on screen. Now the share
 * starts after "Your whole screen, including notifications, will be visible" [Start] [Cancel], and only
 * the secure screens (app lock, Locked chats folder, an open locked chat) register FLAG_SECURE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharePrivacyUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun thePreShareDialogSaysTheWholeScreenIsVisibleStartAndCancel() {
        var starts = 0
        var cancels = 0
        var shown by mutableStateOf(true)
        rule.setContent {
            RisiMeTheme {
                if (shown) ShareConfirmDialog(dnd = false, onStart = { starts++; shown = false }, onDnd = {}, onCancel = { cancels++; shown = false })
            }
        }
        rule.onNodeWithText(SHARE_DIALOG_TITLE).assertIsDisplayed()
        rule.onNodeWithText("Your whole screen, including notifications, will be visible.").assertIsDisplayed()
        rule.onNodeWithText("Do Not Disturb").assertDoesNotExist()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Start").performClick()
        assertEquals(1 to 0, starts to cancels)
        shown = true
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1 to 1, starts to cancels)
    }

    @Test fun onAndroid14AndOlderTheDialogAlsoOffersDoNotDisturb() {
        assertTrue(shareDialogShowsDnd(34))
        assertTrue(shareDialogShowsDnd(29))
        assertFalse(shareDialogShowsDnd(35))
        var dnd = 0
        rule.setContent { RisiMeTheme { ShareConfirmDialog(dnd = true, onStart = {}, onDnd = { dnd++ }, onCancel = {}) } }
        rule.onNodeWithText("$SHARE_DIALOG_TEXT $SHARE_DIALOG_DND_TEXT").assertIsDisplayed()
        rule.onNodeWithText("Do Not Disturb").performClick()
        assertEquals(1, dnd)
    }

    @Test fun theShareButtonAlwaysAsksFirst() {
        // On every Android version, not only ≤ 14 (CallActivity.share → the dialog → the system consent).
        val src = java.io.File("src/main/java/lk/codegen/risime/calls/CallActivity.kt").readText()
        val share = src.substringAfter("private fun share()").substringBefore("private fun launchConsent()")
        assertTrue(share.contains("shareWarning = true"))
        assertFalse("no SDK branch skips the dialog", share.contains("launchConsent()"))
    }

    @Test fun theAppLockScreenIsSecureOnlyWhileShown() {
        var show by mutableStateOf(true)
        rule.setContent { RisiMeTheme { if (show) AppLockScreen(onUnlock = {}, autoPrompt = false) } }
        rule.waitForIdle()
        assertEquals(listOf(SecureScreen.APP_LOCK), SecureScreens.held.value.map { it.second })
        show = false
        rule.waitForIdle()
        assertFalse(SecureScreens.active)
    }

    @Test fun secureWindowsStackAndAnOffOneHoldsNothing() {
        var folder by mutableStateOf(true)
        var chatLocked by mutableStateOf(false)
        rule.setContent {
            if (folder) SecureWindow(SecureScreen.LOCKED_CHATS_FOLDER)
            SecureWindow(SecureScreen.LOCKED_CHAT, on = chatLocked)
        }
        rule.waitForIdle()
        assertEquals(listOf(SecureScreen.LOCKED_CHATS_FOLDER), SecureScreens.held.value.map { it.second })
        chatLocked = true
        rule.waitForIdle()
        folder = false
        rule.waitForIdle()
        assertEquals("the locked chat keeps the window secure", listOf(SecureScreen.LOCKED_CHAT), SecureScreens.held.value.map { it.second })
        chatLocked = false
        rule.waitForIdle()
        assertFalse("a normal chat is never secure", SecureScreens.active)
    }

    @Test fun onlyTheThreeSecureScreensRegister() {
        // The FLAG_SECURE screens are exactly these, and normal screens (the chat list, a normal chat) never register.
        assertEquals(setOf("APP_LOCK", "LOCKED_CHATS_FOLDER", "LOCKED_CHAT"), SecureScreen.entries.map { it.name }.toSet())
        val ui = java.io.File("src/main/java/lk/codegen/risime/ui")
        val users = ui.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("SecureWindow(") }.map { it.relativeTo(ui).path }.toSet()
        assertEquals(setOf("RisiMeRoot.kt", "chats/LockedChatsScreen.kt", "lock/AppLockUi.kt", "lock/LockPrivacy.kt"), users)
        val root = java.io.File(ui, "RisiMeRoot.kt").readText()
        assertEquals("the locked chat (and its info screens) only while locked", 3, Regex("SecureScreen.LOCKED_CHAT, on = ").findAll(root).count())
    }
}
