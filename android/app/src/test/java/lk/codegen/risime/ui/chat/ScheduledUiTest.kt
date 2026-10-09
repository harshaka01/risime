package lk.codegen.risime.ui.chat

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.ScheduledMessageEntity
import lk.codegen.risime.data.db.ScheduledSendEntity
import lk.codegen.risime.data.tabs.FakeArmer
import lk.codegen.risime.data.tabs.FakeScheduledDao
import lk.codegen.risime.data.tabs.ScheduledMessages
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §26.6 A14 on screen: the clock-icon bubble with Edit / Send now / Cancel, the ⋮ list, "Sent late". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScheduledUiTest {
    @get:Rule val rule = createComposeRule()

    private val conv = "dm:a_b"
    private val dao = FakeScheduledDao()
    private val sent = mutableListOf<String>()
    private val scheduled = ScheduledMessages(dao, FakeArmer(), send = { _, t, _ -> sent += t; true }, isMember = { true }, now = { 1_000L })
    private val ctl = ScheduledControls(scheduled, dao.open(), dao.sends(), CoroutineScope(Dispatchers.Unconfined), conv)

    private fun row(id: String, state: String = ScheduledMessageEntity.STATE_PENDING, repeat: String? = "daily") =
        ScheduledMessageEntity(id, "w", conv, "Good morning", repeat, 6, 0, 2_000_000L, state, 0)

    @Test fun theBubbleHasAClockAndItsActions() {
        runBlocking { dao.upsert(row("s1")) }
        rule.setContent { RisiMeTheme { ScheduledBubbles(ctl.here.value, ctl) } }
        rule.onNodeWithText("Good morning").assertIsDisplayed()
        rule.onNodeWithContentDescription("Scheduled", useUnmergedTree = true).assertExists()
        assertTrue(ScheduledMessages.label(row("s1")).endsWith("(every day)"))
        rule.onNodeWithTag("scheduled_bubble").performClick()
        rule.onNodeWithText("Edit").assertIsDisplayed()
        rule.onNodeWithText("Send now").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Good morning"), sent)
    }

    @Test fun cancelFromTheBubble() {
        runBlocking { dao.upsert(row("s1", repeat = null)) }
        rule.setContent { RisiMeTheme { ScheduledBubbles(ctl.here.value, ctl) } }
        rule.onNodeWithTag("scheduled_bubble").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(ScheduledMessageEntity.STATE_CANCELLED, runBlocking { dao.get("s1")!!.state })
    }

    @Test fun aMissedOneOffOffersSendNowAndDiscard() {
        runBlocking { dao.upsert(row("s1", ScheduledMessageEntity.STATE_MISSED, null)) }
        rule.setContent { RisiMeTheme { ScheduledBubbles(ctl.here.value, ctl) } }
        rule.onNodeWithText("Not sent: your phone was off").assertIsDisplayed()
        rule.onNodeWithTag("scheduled_bubble").performClick()
        rule.onNodeWithText("Discard").assertIsDisplayed()
        rule.onNodeWithText("Send now").assertIsDisplayed()
    }

    @Test fun theMenuListShowsThisChatsSchedules() {
        runBlocking { dao.upsert(row("s1")); dao.upsert(row("s2").copy(conversationId = "dm:other")) }
        assertEquals(listOf("s1"), ctl.here.value.map { it.scheduleId })
        rule.setContent { RisiMeTheme { ScheduledMessagesSheet(ctl.here.value, ctl) {} } }
        rule.onNodeWithText(SCHEDULED_MESSAGES_TITLE).assertIsDisplayed()
    }

    @Test fun sentLateOnlyOnTheSendersBubble() {
        val m = MessageEntity(
            clientMsgId = "c1", messageId = "m1", conversationId = conv, from = "a", to = "b", body = "Good morning",
            serverTs = "1970-01-01T00:10:00.000Z", localTs = 0, status = "SENT", outgoing = true,
        )
        val sends = mapOf("c1" to ScheduledSendEntity("c1", "s1", 0L, 0L))
        assertTrue(isSentLate(m, sends))
        assertFalse(isSentLate(m.copy(serverTs = "1970-01-01T00:01:00.000Z"), sends))
        assertFalse(isSentLate(m.copy(outgoing = false), sends))
        assertFalse(isSentLate(m, emptyMap()))
        rule.setContent { RisiMeTheme { WithSentLate(true) { androidx.compose.material3.Text("bubble") } } }
        rule.onNodeWithText(SENT_LATE).assertIsDisplayed()
    }
}
