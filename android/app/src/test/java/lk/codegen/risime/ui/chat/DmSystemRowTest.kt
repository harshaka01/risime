package lk.codegen.risime.ui.chat

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.material3.Text
import lk.codegen.risime.data.HistoryMarkers
import lk.codegen.risime.data.db.LastMessage
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.ui.chats.dmPreview
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §13.3: a DM marker renders as a system line (no bubble, no ticks); the chat list shows it without "You:". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp", application = Application::class)
class DmSystemRowTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun markerRendersWithoutBubbleOrTicks() {
        var bubbleShown = false
        val m = HistoryMarkers.row("dm:a_b", SystemLine.HISTORY_GAP, "2026-10-01T10:00:00.000Z", 0)
        rule.setContent {
            RisiMeTheme(dark = false) {
                DmMessageRow(m) {
                    bubbleShown = true
                    Text("bubble")
                }
            }
        }
        rule.onNodeWithText(SystemLine.HISTORY_GAP_TEXT).assertIsDisplayed()
        assertFalse(bubbleShown)
        listOf("Read", "Sent", "Delivered", "Pending").forEach { rule.onAllNodesWithContentDescription(it).assertCountEquals(0) }
    }

    @Test
    fun chatListPreviewHasNoYouPrefixForAMarker() {
        val marker = LastMessage("dm:a_b", SystemLine.HISTORY_GAP_TEXT, 1, outgoing = false, status = "READ", kind = MessageEntity.KIND_SYSTEM)
        assertEquals(SystemLine.HISTORY_GAP_TEXT, dmPreview(marker))
        assertEquals("You: hi", dmPreview(LastMessage("dm:a_b", "hi", 1, outgoing = true, status = "SENT")))
    }
}
