package ai.risicloud.risime.ui.chat

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import ai.risicloud.risime.ui.chats.NewSheet
import ai.risicloud.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bottom sheets on a small phone (360x640dp) at font scale 1.3: the last rows scroll into view and can be clicked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", application = Application::class)
class SmallScreenSheetsTest {
    @get:Rule val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) { RisiMeTheme { content() } }
    }

    private val labels = listOf("Reply", "Copy", "Forward", "Star", "Info", "Share", "Save as PDF", "Select", "Delete")

    private fun pick(label: String) {
        val hit = mutableListOf<String>()
        show { MessageActionsSheet(true, emptySet(), { _, _ -> }, labels.map { l -> l to { hit += l } }, {}) }
        rule.onNodeWithText(label).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf(label), hit)
    }

    @Test fun selectIsReachable() = pick("Select")

    @Test fun deleteIsReachable() = pick("Delete")

    @Test fun newSheetLastRowIsReachable() {
        val ev = mutableListOf<String>()
        show { NewSheet({ ev += "chat" }, { ev += "group" }, { ev += "friend" }, {}) }
        rule.onNodeWithText("Add friend", substring = true, ignoreCase = true).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("friend"), ev)
    }
}
