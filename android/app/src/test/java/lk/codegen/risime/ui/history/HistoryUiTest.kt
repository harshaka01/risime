package lk.codegen.risime.ui.history

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.HistoryProvideEntity
import lk.codegen.risime.data.db.HistoryRequestEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.data.history.HistoryApproval
import lk.codegen.risime.data.history.HistoryMarkerState
import lk.codegen.risime.net.HistoryState
import lk.codegen.risime.ui.chat.MessageActionsSheet
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §17.8/§17.12 on the JVM: Request history and its sheet, progress, both approval prompts, the "Shared by" label, Settings → Privacy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h720dp", application = Application::class)
class HistoryUiTest {
    @get:Rule val rule = createComposeRule()

    private val me = "u-me"
    private val dm = "dm:u-me_u-peer"
    private val grp = "grp:g1"

    private fun req(state: String, sources: String = "own", parts: Int = 0, done: Int = 0, closed: Boolean = false, own: String? = null) = HistoryRequestEntity(
        "r1", dm, sources, state, parts = parts, partsDone = done, createdAt = 0, expiresAt = 1, rangeFrom = "a", rangeTo = "b", gapCount = 3,
        ownDevicesJson = own, closed = closed,
    )

    private fun provide(own: Boolean, conv: String = grp) = HistoryProvideEntity(
        "r1", conv, "u-b", "d-b", ByteArray(32), own, ByteArray(32), "a", "b", "[]", 412, null, HistoryProvideEntity.ASK, createdAt = 0, updatedAt = 0,
    )

    @Test fun requestHistoryOpensTheSourceSheet() {
        val asked = mutableListOf<String>()
        rule.setContent {
            RisiMeTheme {
                HistoryMarkerRow(SystemLine.HISTORY_GAP_TEXT, HistoryMarkerState(3, null, emptySet()), grp, me, null, { asked += it }, {})
            }
        }
        rule.onNodeWithText(SystemLine.HISTORY_GAP_TEXT).assertIsDisplayed()
        rule.onNodeWithText(REQUEST_HISTORY).performClick()
        rule.onNodeWithText("Get earlier messages from:").assertIsDisplayed()
        rule.onNodeWithText("Your other phone or group members").assertIsDisplayed()
        rule.onNodeWithText("Your other phone").performClick()
        rule.waitForIdle()
        assertEquals(listOf("own"), asked)
    }

    @Test fun noActionWithoutGapRowsOrWithAnOpenRequest() {
        assertEquals(false, HistoryMarkerState(0, null, emptySet()).canRequest)
        assertEquals(false, HistoryMarkerState(3, req(HistoryState.SEARCHING), emptySet()).canRequest)
        assertEquals(true, HistoryMarkerState(3, req(HistoryState.UNAVAILABLE, closed = true), emptySet()).canRequest)
        // android R6: after an own-device share, only the members option is left (and vice versa).
        assertEquals(listOf("any"), historySourceOptions(HistoryMarkerState(1, null, setOf(me)), grp, me, null).map { it.second })
        assertEquals(listOf("own"), historySourceOptions(HistoryMarkerState(1, null, setOf("u-c")), grp, me, null).map { it.second })
        assertEquals("Your other phone or Kamal", historySourceOptions(HistoryMarkerState(1, null, emptySet()), dm, me, "Kamal")[1].first)
    }

    @Test fun progressLines() {
        val pixel = """[{"device_id":"d1","device_name":"Pixel 8","last_seen_at":null,"online":false}]"""
        assertEquals("Open RisiMe on Pixel 8 to share", historyProgressText(HistoryMarkerState(3, req(HistoryState.SEARCHING, own = pixel), emptySet())))
        assertEquals("Looking for your other phone…", historyProgressText(HistoryMarkerState(3, req(HistoryState.SEARCHING), emptySet())))
        assertEquals("Asking group members…", historyProgressText(HistoryMarkerState(3, req(HistoryState.WAITING_FOR_MEMBER, "any"), emptySet())))
        assertEquals("Receiving history… 2 of 3", historyProgressText(HistoryMarkerState(3, req(HistoryState.RECEIVING, parts = 3, done = 1), emptySet())))
        assertEquals("No device could share this history right now", historyProgressText(HistoryMarkerState(3, req(HistoryState.UNAVAILABLE, closed = true), emptySet())))
        assertNull(historyProgressText(HistoryMarkerState(0, req(HistoryState.DONE, closed = true), emptySet())))
    }

    @Test fun progressAndAskMembersNowOnTheMarker() {
        var escalated = 0
        rule.setContent {
            RisiMeTheme {
                HistoryMarkerRow(SystemLine.HISTORY_GAP_TEXT, HistoryMarkerState(3, req(HistoryState.SEARCHING, "any", own = """[{"device_id":"d1","device_name":"Pixel 8"}]"""), emptySet()), grp, me, null, {}, { escalated++ })
            }
        }
        rule.onNodeWithText("Open RisiMe on Pixel 8 to share").assertIsDisplayed()
        rule.onNodeWithText(ASK_MEMBERS_NOW).performClick()
        rule.waitForIdle()
        assertEquals(1, escalated)
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText(REQUEST_HISTORY)).fetchSemanticsNodes().size)
    }

    @Test fun ownApprovalPrompt() {
        var answer: Boolean? = null
        rule.setContent { RisiMeTheme { HistoryPromptDialog(provide(own = true, conv = dm), "Me", { answer = it }) } }
        rule.onNodeWithText("Your new phone wants your chat history. Allow?").assertIsDisplayed()
        rule.onNodeWithText(HISTORY_OWN_SMALL_PRINT).assertIsDisplayed()
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertEquals(true, answer)
    }

    @Test fun memberPromptAlwaysAsks() {
        var answer: Boolean? = null
        rule.setContent { RisiMeTheme { HistoryPromptDialog(provide(own = false), "Kamal", { answer = it }) } }
        rule.onNodeWithText("Kamal wants the group history from when they were in the group. Share?").assertIsDisplayed()
        rule.onNodeWithText("About 412 messages").assertIsDisplayed()
        rule.onNodeWithText("Not now").performClick()
        rule.waitForIdle()
        assertEquals(false, answer)
        assertEquals("Kamal wants your chat history. Share?", historyPromptText(provide(own = false, conv = dm), "Kamal"))
    }

    @Test fun sharedByLabelInTheInfoSheet() {
        val row = MessageEntity("c1", "m1", grp, "u-a", grp, "hi", null, 1, MessageStatus.READ.name, false, origin = MessageEntity.ORIGIN_SHARED, sharedBy = "u-c")
        val label = sharedByLabel(row) { if (it == "u-c") "Chamari" else "?" }
        assertEquals("Shared by Chamari", label)
        assertNull(sharedByLabel(row.copy(from = "u-c")) { "Chamari" }) // the provider's own messages
        assertNull(sharedByLabel(row.copy(origin = MessageEntity.ORIGIN_OWN_DEVICE)) { "x" }) // own-device restores: no label
        rule.setContent { RisiMeTheme { MessageActionsSheet(canReact = false, myReactions = emptySet(), onReact = { _, _ -> }, actions = listOf("Copy" to {}), onDismiss = {}, info = label) } }
        rule.onNodeWithText("Shared by Chamari").assertIsDisplayed()
    }

    @Test fun privacySettings() {
        val removed = mutableListOf<String>()
        var members = true
        rule.setContent {
            RisiMeTheme {
                androidx.compose.foundation.layout.Column {
                    HistoryPrivacyContent(true, true, listOf(HistoryApproval("d-new-phone", me, "k", 0)), { members = it }, {}, { removed += it })
                }
            }
        }
        rule.onNodeWithText("Phones allowed to get your history").assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()
        rule.waitForIdle()
        assertEquals(listOf("d-new-phone"), removed)
        assertEquals(true, members)
    }
}
