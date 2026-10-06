package lk.codegen.risime.ui.common

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.ui.chat.ChatItem
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chat scroll rules (DM; the group list shares them, see GroupChatScrollTest). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp", application = Application::class)
class ChatScrollTest {
    @get:Rule val rule = createComposeRule()

    private val now = System.currentTimeMillis()
    private fun msg(key: String, outgoing: Boolean = false) = MessageEntity(
        clientMsgId = key, messageId = "s-$key", conversationId = "c", from = if (outgoing) "me" else "peer",
        to = "x", body = "msg $key", serverTs = null, localTs = now, status = "DELIVERED", outgoing = outgoing,
    )

    private val rows = mutableStateListOf<MessageEntity>().apply { repeat(40) { add(msg("m$it")) } }
    private var viewport by mutableStateOf(400.dp)
    private val tall = mutableStateOf<String?>(null)

    private val list: @androidx.compose.runtime.Composable () -> Unit = {
        RisiMeTheme(dark = false) {
            ChatMessageList(rows.toList(), Modifier.fillMaxWidth().height(viewport), spacing = 0.dp) { items, i ->
                when (val item = items[i]) {
                    is ChatItem.Day -> Text(item.label)
                    is ChatItem.Msg -> Column {
                        Text(item.m.body, Modifier.height(60.dp))
                        Spacer(Modifier.height(if (tall.value == item.m.clientMsgId) 300.dp else 0.dp))
                        Text("end ${item.m.clientMsgId}", Modifier.height(20.dp))
                    }
                }
            }
        }
    }

    private fun show() = rule.setContent(list)

    private fun add(vararg m: MessageEntity) {
        rule.runOnIdle { rows.addAll(m) }
        rule.waitForIdle()
    }

    private fun scrollUp() {
        rule.onNodeWithTag(CHAT_LIST_TAG).performScrollToIndex(30) // reversed: index 30 = m9
        rule.waitForIdle()
        rule.onNodeWithText("msg m9").assertIsDisplayed()
    }

    private fun assertAtBottom(key: String) {
        rule.onNodeWithText("end $key").assertIsDisplayed()
        rule.onNodeWithText(NEW_MESSAGES_LABEL).assertDoesNotExist()
    }

    @Test fun opensAtTheLatestMessageOfALongHistory() {
        show()
        assertAtBottom("m39")
        rule.onNodeWithText("msg m0").assertDoesNotExist()
    }

    @Test fun opensAtTheLatestMessageWhenHistoryLoadsAfterTheFirstFrame() {
        val history = rows.toList()
        rows.clear()
        show()
        add(*history.toTypedArray())
        assertAtBottom("m39")
    }

    @Test fun anIncomingMessageAtTheBottomStaysAtTheBottom() {
        show()
        add(msg("m40"))
        assertAtBottom("m40")
    }

    @Test fun sendingStaysAtTheBottom() {
        show()
        add(msg("m40", outgoing = true))
        assertAtBottom("m40")
    }

    @Test fun sendingWhileScrolledUpJumpsToTheBottom() {
        show()
        scrollUp()
        add(msg("m40", outgoing = true))
        assertAtBottom("m40")
    }

    @Test fun aSyncBatchOf50InSeveralEmissionsStaysAtTheBottom() {
        show()
        // Five emissions in one frame, then five more each with its own frame.
        rule.runOnIdle { repeat(5) { b -> rows.addAll((0 until 5).map { msg("s${b * 5 + it}") }) } }
        repeat(5) { b -> add(*(0 until 5).map { msg("s${25 + b * 5 + it}") }.toTypedArray()) }
        assertAtBottom("s49")
    }

    @Test fun theKeyboardOpeningKeepsTheLastMessageVisible() {
        show()
        viewport = 180.dp // imePadding shrinks the list by the keyboard height
        rule.waitForIdle()
        assertAtBottom("m39")
        viewport = 400.dp
        rule.waitForIdle()
        assertAtBottom("m39")
    }

    @Test fun theNewestMessageGrowingStaysAtTheBottom() {
        show()
        tall.value = "m39" // an image or reactions arriving later
        rule.waitForIdle()
        assertAtBottom("m39")
    }

    @Test fun scrolledUpAnIncomingMessageShowsTheButtonWithItsCountAndDoesNotMove() {
        show()
        scrollUp()
        add(msg("m40"))
        add(msg("m41"), msg("m42", outgoing = false))
        rule.onNodeWithText("msg m9").assertIsDisplayed()
        rule.onNodeWithText("msg m42").assertDoesNotExist()
        rule.onNodeWithText(NEW_MESSAGES_LABEL).assertIsDisplayed()
        rule.onNodeWithTag(NEW_MESSAGES_COUNT_TAG, useUnmergedTree = true).assertTextEquals("3")
    }

    @Test fun tappingTheButtonGoesToTheBottomAndHidesIt() {
        show()
        scrollUp()
        add(msg("m40"))
        rule.onNodeWithText(NEW_MESSAGES_LABEL).performClick()
        rule.waitForIdle()
        assertAtBottom("m40")
        add(msg("m41")) // back at the bottom: follows again
        assertAtBottom("m41")
    }

    @Test fun scrollingBackToTheBottomHidesTheButton() {
        show()
        scrollUp()
        add(msg("m40"))
        rule.onNodeWithText(NEW_MESSAGES_LABEL).assertIsDisplayed()
        rule.onNodeWithTag(CHAT_LIST_TAG).performScrollToIndex(0)
        rule.waitForIdle()
        assertAtBottom("m40")
    }

    @Test fun restoreAfterRecreationStaysAtTheBottom() {
        val restore = StateRestorationTester(rule)
        restore.setContent(list)
        assertAtBottom("m39")
        viewport = 250.dp // e.g. rotation to landscape
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertAtBottom("m39")
        add(msg("m40"))
        assertAtBottom("m40")
    }

    @Test fun restoreAfterRecreationWhileScrolledUpKeepsThePositionAndTheCount() {
        val restore = StateRestorationTester(rule)
        restore.setContent(list)
        scrollUp()
        add(msg("m40"))
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithText("msg m9").assertIsDisplayed()
        rule.onNodeWithTag(NEW_MESSAGES_COUNT_TAG, useUnmergedTree = true).assertTextEquals("1")
    }
}
