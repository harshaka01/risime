package lk.codegen.risime.ui.group

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.unit.dp
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.ui.common.CHAT_LIST_TAG
import lk.codegen.risime.ui.common.NEW_MESSAGES_COUNT_TAG
import lk.codegen.risime.ui.common.NEW_MESSAGES_LABEL
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The group chat list follows the same scroll rules as a DM (shared ChatMessageList). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp", application = Application::class)
class GroupChatScrollTest {
    @get:Rule val rule = createComposeRule()

    private val now = System.currentTimeMillis()
    private fun msg(i: Int, outgoing: Boolean = false) = MessageEntity(
        clientMsgId = "g$i", messageId = "s$i", conversationId = "grp", from = if (outgoing) "me" else "u-kamal",
        to = "grp", body = "group message $i", serverTs = null, localTs = now, status = "DELIVERED", outgoing = outgoing,
    )

    private val rows = mutableStateListOf<MessageEntity>().apply { repeat(40) { add(msg(it)) } }

    private fun show() = rule.setContent {
        RisiMeTheme(dark = false) {
            GroupMessageList(
                messages = rows.toList(), meId = "me", nameOf = { "Kamal" }, memberName = { "Kamal" }, readOnly = false,
                reactions = emptyMap(), onReact = { _, _, _ -> }, onOpenReactions = {}, onRetry = {}, onDelete = {}, onInfo = {},
                modifier = Modifier.fillMaxWidth().height(400.dp),
            )
        }
    }

    /** A group bubble's text is its merged content description. */
    private fun bubble(i: Int) = rule.onNode(hasContentDescription("group message $i", substring = true))

    private fun add(vararg m: MessageEntity) {
        rule.runOnIdle { rows.addAll(m) }
        rule.waitForIdle()
    }

    @Test fun groupOpensAtTheLatestFollowsAtTheBottomAndShowsTheButtonWhenScrolledUp() {
        show()
        bubble(39).assertIsDisplayed()
        add(msg(40))
        bubble(40).assertIsDisplayed()
        add(*(41 until 91).map { msg(it) }.toTypedArray()) // sync batch of 50
        bubble(90).assertIsDisplayed()
        add(msg(91, outgoing = true))
        bubble(91).assertIsDisplayed()
        rule.onNodeWithText(NEW_MESSAGES_LABEL).assertDoesNotExist()

        rule.onNodeWithTag(CHAT_LIST_TAG).performScrollToIndex(60)
        rule.waitForIdle()
        add(msg(92), msg(93))
        bubble(93).assertDoesNotExist()
        rule.onNodeWithTag(NEW_MESSAGES_COUNT_TAG, useUnmergedTree = true).assertTextEquals("2")
        rule.onNodeWithText(NEW_MESSAGES_LABEL).performClick()
        rule.waitForIdle()
        bubble(93).assertIsDisplayed()
        rule.onNodeWithText(NEW_MESSAGES_LABEL).assertDoesNotExist()
    }
}
