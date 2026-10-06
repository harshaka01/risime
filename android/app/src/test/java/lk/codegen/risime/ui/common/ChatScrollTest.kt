package lk.codegen.risime.ui.common

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P0 hotfix: chats open at the newest message, follow new ones at the bottom, else "New messages ↓". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp", application = Application::class)
class ChatScrollTest {
    @get:Rule val rule = createComposeRule()

    private data class Row(val key: String, val outgoing: Boolean = false)

    private val rows = mutableStateListOf<Row>().apply { repeat(40) { add(Row("m$it")) } }

    private fun show() = rule.setContent {
        RisiMeTheme(dark = false) {
            ChatMessageList(
                count = rows.size,
                lastKey = rows.lastOrNull()?.key,
                lastOutgoing = rows.lastOrNull()?.outgoing == true,
                modifier = Modifier.fillMaxWidth().height(400.dp),
                spacing = 0.dp,
            ) {
                items(rows, key = { it.key }) { Text("msg ${it.key}", Modifier.height(80.dp)) }
            }
        }
    }

    private fun add(row: Row) {
        rule.runOnIdle { rows += row }
        rule.waitForIdle()
    }

    @Test fun opensAtTheNewestMessage() {
        show()
        rule.onNodeWithText("msg m39").assertIsDisplayed()
        rule.onNodeWithText("msg m0").assertDoesNotExist()
    }

    @Test fun aNewMessageFollowsWhenAtTheBottom() {
        show()
        add(Row("m40"))
        rule.onNodeWithText("msg m40").assertIsDisplayed()
        rule.onNodeWithText(NEW_MESSAGES_LABEL).assertDoesNotExist()
    }

    @Test fun scrolledUpStaysPutShowsTheButtonAndTheButtonJumpsToTheBottom() {
        show()
        rule.onNodeWithTag(CHAT_LIST_TAG).performScrollToIndex(5)
        rule.waitForIdle()
        add(Row("m40"))
        rule.onNodeWithText("msg m5").assertIsDisplayed()
        rule.onNodeWithText("msg m40").assertDoesNotExist()
        rule.onNodeWithText(NEW_MESSAGES_LABEL).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("msg m40").assertIsDisplayed()
        rule.onNodeWithText(NEW_MESSAGES_LABEL).assertDoesNotExist()
    }

    @Test fun myOwnMessageAlwaysScrollsToTheBottom() {
        show()
        rule.onNodeWithTag(CHAT_LIST_TAG).performScrollToIndex(5)
        rule.waitForIdle()
        add(Row("m40", outgoing = true))
        rule.onNodeWithText("msg m40").assertIsDisplayed()
        rule.onNodeWithText(NEW_MESSAGES_LABEL).assertDoesNotExist()
    }

    @Test fun scrollingBackToTheBottomHidesTheButton() {
        show()
        rule.onNodeWithTag(CHAT_LIST_TAG).performScrollToIndex(5)
        rule.waitForIdle()
        add(Row("m40"))
        rule.onNodeWithText(NEW_MESSAGES_LABEL).assertIsDisplayed()
        rule.onNodeWithTag(CHAT_LIST_TAG).performScrollToIndex(40)
        rule.waitForIdle()
        rule.onNodeWithText(NEW_MESSAGES_LABEL).assertDoesNotExist()
    }
}
