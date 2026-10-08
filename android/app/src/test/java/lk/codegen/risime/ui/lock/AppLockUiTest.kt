package lk.codegen.risime.ui.lock

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import lk.codegen.risime.data.lock.AppLockSettings
import lk.codegen.risime.data.lock.AutoLock
import lk.codegen.risime.ui.auth.MIGRATION_TITLE
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Decision 064: Settings → Privacy → Fingerprint lock, and the locked screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppLockUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun privacyRowsAreHiddenWithoutBiometrics() {
        rule.setContent {
            RisiMeTheme { Column { FingerprintLockContent(false, AppLockSettings(), {}, {}, {}) } }
        }
        rule.onNodeWithText(FINGERPRINT_LOCK).assertDoesNotExist()
        rule.onNodeWithText(AUTO_LOCK_TITLE).assertDoesNotExist()
    }

    @Test fun offByDefaultThenTheOptionsAppearWhenOn() {
        val toggles = mutableListOf<Boolean>()
        var picked: AutoLock? = null
        var content: Boolean? = null
        rule.setContent {
            var s by remember { mutableStateOf(AppLockSettings()) }
            RisiMeTheme {
                Column {
                    FingerprintLockContent(
                        true, s,
                        onToggle = { toggles += it; s = s.copy(enabled = it) },
                        onAutoLock = { picked = it; s = s.copy(autoLock = it) },
                        onShowContent = { content = it; s = s.copy(showContent = it) },
                    )
                }
            }
        }
        rule.onNodeWithText(FINGERPRINT_LOCK).assertIsDisplayed()
        rule.onNodeWithText(AUTO_LOCK_TITLE).assertDoesNotExist() // off by default
        rule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOff()
        rule.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        assertEquals(listOf(true), toggles)
        rule.onNodeWithText(AUTO_LOCK_TITLE).assertIsDisplayed()
        rule.onNodeWithText("Immediately").assertIsDisplayed()
        rule.onNodeWithText("After 1 minute").assertIsDisplayed()
        rule.onNodeWithText("After 30 minutes").performClick()
        assertEquals(AutoLock.THIRTY_MINUTES, picked)
        rule.onNodeWithText(SHOW_CONTENT_TITLE).assertIsDisplayed().performClick()
        assertEquals(false, content)
    }

    @Test fun lockScreenShowsTheLogoAndPromptsOnShow() {
        var prompts = 0
        rule.setContent { RisiMeTheme { AppLockScreen(onUnlock = { prompts++ }) } }
        rule.waitForIdle()
        assertEquals(1, prompts) // auto-prompt on show
        rule.onNodeWithContentDescription("RisiMe logo").assertIsDisplayed()
        rule.onNodeWithText(UNLOCK_WITH_FINGERPRINT).assertIsDisplayed().performClick()
        assertEquals(2, prompts)
        // Nothing on it can sign out or wipe.
        rule.onNodeWithText("Sign out", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Log out", substring = true).assertDoesNotExist()
        rule.onNodeWithText(MIGRATION_TITLE).assertDoesNotExist()
    }
}
