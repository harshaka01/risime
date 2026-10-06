package lk.codegen.risime.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import lk.codegen.risime.data.mls.E2EE_INFO_TEXT
import lk.codegen.risime.data.mls.E2eeState
import lk.codegen.risime.data.mls.e2eeStripText
import lk.codegen.risime.net.MlsMissing
import lk.codegen.risime.ui.group.GroupInfoContent
import lk.codegen.risime.ui.group.GroupInfoUi
import lk.codegen.risime.ui.settings.ABOUT_E2EE_TEXT
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Decision 048: no global banner; each chat shows its real encryption state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EncryptionUiTest {
    @get:Rule val rule = createComposeRule()

    private val nameOf = { id: String -> if (id == "b") "Kamal" else id }

    /** As in ChatScreen: the header lock + chat info dialog, and the strip. */
    private fun chat(state: E2eeState) {
        rule.setContent {
            RisiMeTheme {
                val encrypted = state is E2eeState.Encrypted
                val strip = e2eeStripText(state, nameOf, { it == "a" }, "d-a")
                var info by remember { mutableStateOf(false) }
                Column {
                    E2eeHeaderLock(encrypted) { info = true }
                    E2eeStrip(strip)
                }
                if (info) DmChatInfoDialog("Kamal", encrypted, strip) { info = false }
            }
        }
    }

    @Test fun encryptedChatShowsLockAndInfoLineButNoStrip() {
        chat(E2eeState.Encrypted(4))
        rule.onNodeWithTag(E2EE_STRIP_TAG).assertDoesNotExist()
        rule.onNodeWithContentDescription(E2EE_LOCK_DESCRIPTION).assertIsDisplayed().performClick()
        rule.onNodeWithText(E2EE_INFO_TEXT).assertIsDisplayed()
    }

    @Test fun oldAppShowsNeedsToUpdate() {
        chat(E2eeState.NotReady(listOf(MlsMissing("b", null, MlsMissing.LEGACY_APP))))
        rule.onNodeWithContentDescription(E2EE_LOCK_DESCRIPTION).assertDoesNotExist()
        rule.onNodeWithText("Not end-to-end encrypted yet: Kamal needs to update RisiMe").assertIsDisplayed()
    }

    @Test fun phoneNotOnlineIsNotCalledAnUpdate() {
        chat(E2eeState.NotReady(listOf(MlsMissing("b", null, MlsMissing.NO_MLS))))
        rule.onNodeWithText("Not end-to-end encrypted yet: waiting for Kamal's phone to come online").assertIsDisplayed()
    }

    @Test fun setupNotFinishedIsNotCalledAnUpdate() {
        chat(E2eeState.NotReady(listOf(MlsMissing("b", "d-b", MlsMissing.NO_MLS))))
        rule.onNodeWithText("Not end-to-end encrypted yet: Kamal's phone hasn't finished setting up encryption").assertIsDisplayed()
    }

    @Test fun groupInfoSaysMessagesAreEncrypted() {
        rule.setContent {
            RisiMeTheme {
                GroupInfoContent(GroupInfoUi(name = "Team"), {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
            }
        }
        rule.onNodeWithText(E2EE_INFO_TEXT).assertIsDisplayed()
    }

    @Test fun aboutTextIsAccurate() {
        assertEquals(
            "Chats are end-to-end encrypted (MLS) once everyone in them runs a current RisiMe. Each chat shows its state.",
            ABOUT_E2EE_TEXT,
        )
        assertFalse(ABOUT_E2EE_TEXT.contains("0.3"))
    }
}

/** Decision 050: plain "Log out" says the chats stay; the labelled delete is its own dialog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LogoutDialogsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun plainLogoutKeepsChats() {
        var got: Boolean? = null
        rule.setContent { RisiMeTheme { lk.codegen.risime.ui.common.LogoutConfirmDialog({ got = it.deleteChats }, {}) } }
        rule.onNodeWithText(lk.codegen.risime.ui.common.LOGOUT_CONFIRM_TEXT).assertIsDisplayed()
        rule.onNodeWithText("Log out").performClick()
        assertEquals(false, got)
    }

    @Test fun deleteChatsIsLabelledAndCarriesTheChoice() {
        var got: Boolean? = null
        rule.setContent { RisiMeTheme { lk.codegen.risime.ui.common.LogoutConfirmDialog({ got = it.deleteChats }, {}, deleteChats = true) } }
        rule.onNodeWithText(lk.codegen.risime.ui.common.LOGOUT_DELETE_TITLE).assertIsDisplayed()
        rule.onNodeWithText(lk.codegen.risime.ui.common.LOGOUT_DELETE_TEXT).assertIsDisplayed()
        rule.onNodeWithText("Delete chats and log out").performClick()
        assertEquals(true, got)
    }
}
