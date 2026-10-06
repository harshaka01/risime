package lk.codegen.risime.ui.chat

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import lk.codegen.risime.data.db.LastMessage
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.deletes.DeleteRules
import lk.codegen.risime.ui.chats.dmPreview
import lk.codegen.risime.ui.chats.forPreview
import lk.codegen.risime.ui.group.GroupMessageList
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §15.6/§15.7 UI: tombstone texts, the placed-tombstone attribution rule, the dialog, previews, select-mode copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp-night", application = Application::class)
class DeleteUiTest {
    @get:Rule val rule = createComposeRule()
    private val me = "u-me"
    private val conv = "grp:g"

    private fun tomb(id: String, from: String, by: String, admin: Boolean, placed: Boolean = false, ts: Long = 1) = MessageEntity(
        if (placed) MessageEntity.placeholderId("m-$id") else id, "m-$id", conv, from, conv, "", null, ts, "READ", from == me,
        kind = MessageEntity.KIND_DELETED, deletedBy = by, deletedByAdmin = admin,
    )

    @Test fun groupTombstonesShowTheirTextAndPlacedOnesNoSender() {
        rule.setContent {
            RisiMeTheme(dark = true) {
                GroupMessageList(
                    messages = listOf(
                        tomb("a", "u-bob", "u-bob", false, ts = 1),
                        tomb("b", "u-kamal", "u-admin", true, placed = true, ts = 2),
                        tomb("c", me, me, false, ts = 3),
                    ),
                    meId = me, nameOf = { if (it == "u-bob") "Bob" else "Kamal" }, memberName = { null }, readOnly = false,
                    reactions = emptyMap(), onReact = { _, _, _ -> }, onOpenReactions = {}, onRetry = {}, onDelete = {}, onInfo = {},
                )
            }
        }
        val descs = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("🚫", substring = true)).fetchSemanticsNodes()
            .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString() }
        assertEquals(3, descs.size)
        assertTrue(descs.toString(), descs.any { it.startsWith("Bob: 🚫 This message was deleted,") })
        // The server-placed admin tombstone carries no sender attribution (crypto S2).
        assertTrue(descs.toString(), descs.any { it.startsWith("🚫 This message was deleted by an admin,") })
        assertTrue(descs.toString(), descs.any { it.startsWith("You: 🚫 You deleted this message,") })
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(t: String) =
        onAllNodes(androidx.compose.ui.test.hasText(t)).fetchSemanticsNodes().size

    @Test fun theDialogHidesEveryoneWhenNotEligibleAndShowsTheOldAppsHint() {
        var choice = ""
        rule.setContent {
            RisiMeTheme(dark = true) {
                DeleteMessagesDialog(DeleteDialogState(listOf("a", "b"), canEveryone = true, oldAppsHint = true), { choice = "me" }, { choice = "everyone" }, {})
            }
        }
        rule.onNodeWithText("Delete 2 messages?").assertIsDisplayed()
        rule.onNodeWithText(DeleteRules.OLD_APPS_HINT, substring = true).assertIsDisplayed()
        rule.onNodeWithText("Delete for everyone").performClick()
        assertEquals("everyone", choice)
    }

    @Test fun onlyForMeWhenNotEligible() {
        rule.setContent {
            RisiMeTheme(dark = true) { DeleteMessagesDialog(DeleteDialogState(listOf("a"), canEveryone = false, oldAppsHint = false), {}, {}, {}) }
        }
        rule.onNodeWithText("Delete for me").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("Delete for everyone")).fetchSemanticsNodes().size)
    }

    @Test fun previewsAndCopy() {
        val last = LastMessage("dm:a_b", "", 1, outgoing = false, status = "READ", from = "u-bob", kind = MessageEntity.KIND_DELETED, deletedBy = "u-bob")
        assertEquals(DeleteRules.DELETED, dmPreview(last.forPreview(me)))
        val deleting = LastMessage("dm:a_b", "secret", 1, outgoing = true, status = "SENT", from = me, deleteState = MessageEntity.DELETE_STATE_DELETING)
        assertEquals(DeleteRules.YOU_DELETED, dmPreview(deleting.forPreview(me)))
        val rows = listOf(
            MessageEntity("1", "m1", "dm:a_b", me, "b", "one", null, 1, "SENT", true),
            MessageEntity("2", "m2", "dm:a_b", me, "b", "", null, 2, "SENT", true, kind = MessageEntity.KIND_DELETED),
            MessageEntity("3", "m3", "dm:a_b", me, "b", "three", null, 3, "SENT", true),
        )
        assertEquals("one\nthree", copyText(rows, setOf("1", "2", "3")))
    }
}
