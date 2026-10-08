package lk.codegen.risime.ui.chats

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.ZoneId
import java.time.ZonedDateTime
import lk.codegen.risime.calls.CallOutcome
import lk.codegen.risime.calls.CallRecord
import lk.codegen.risime.calls.CallRecords
import lk.codegen.risime.calls.groupCalls
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp", application = Application::class)
class CallsTabTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val now = ZonedDateTime.of(2026, 10, 8, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val kamal = ContactEntity("+1", "Kamal", "Rise", "bbbb", true)
    private fun rec(id: String, out: Boolean, o: CallOutcome, video: Boolean, atMs: Long) = CallRecord(id, "call-$id", "dm:a:b", video, out, o, null, atMs)

    private fun rows(vararg r: CallRecord) = buildCallRows(r.toList(), mapOf("dm:a:b" to kamal))

    @Test fun emptyStateLikeWhatsApp() {
        rule.setContent { RisiMeTheme { CallsList(emptyList(), now, {}, { _, _ -> }) } }
        rule.onNodeWithText("No calls yet. Calls you make and receive show up here.").assertIsDisplayed()
    }

    @Test fun rowsGroupedWithDirectionDateAndCallBackOfTheSameType() {
        val events = mutableListOf<String>()
        val r = rows(
            rec("1", false, CallOutcome.MISSED, true, at(8, 14, 5)),
            rec("2", false, CallOutcome.MISSED, true, at(8, 13, 0)),
            rec("3", false, CallOutcome.MISSED, true, at(8, 9, 0)),
            rec("4", true, CallOutcome.ANSWERED, false, at(7, 9, 12)),
        )
        assertEquals(listOf("Kamal (3)", "Kamal"), r.map { it.title })
        var info: CallRow? = null
        rule.setContent { RisiMeTheme { CallsList(r, now, { info = it }, { conv, video -> events += "$conv/${if (video) "video" else "voice"}" }) } }
        rule.onNodeWithText("Kamal (3)").assertIsDisplayed()
        rule.onNodeWithText("↙ Today 14:05").assertIsDisplayed()
        rule.onNodeWithText("↗ Yesterday 09:12").assertIsDisplayed()
        // The icon calls back with the row's type: a video row offers video, a voice row voice.
        rule.onNodeWithContentDescription("Video call Kamal").performClick()
        rule.onNodeWithContentDescription("Voice call Kamal").performClick()
        assertEquals(listOf("dm:a:b/video", "dm:a:b/voice"), events)
        // The row itself opens call info.
        rule.onNodeWithText("Kamal (3)").performClick()
        assertEquals(3, info!!.group.count)
    }

    @Test fun callInfoListsEveryCallWithTimeAndDuration() {
        val calls = listOf(
            rec("1", false, CallOutcome.MISSED, false, at(8, 14, 5)),
            rec("2", true, CallOutcome.ANSWERED, true, at(7, 9, 12)).copy(durationS = 192),
        )
        val row = rows(*calls.toTypedArray()).first()
        rule.setContent { RisiMeTheme { CallInfoDialog(row, calls, now, {}, { _, _ -> }, {}) } }
        rule.onNodeWithText("Kamal").assertIsDisplayed()
        rule.onNodeWithText("Incoming voice call").assertIsDisplayed()
        rule.onNodeWithText("Outgoing video call").assertIsDisplayed()
        rule.onNodeWithText("↙ Today 14:05 · Missed").assertIsDisplayed()
        rule.onNodeWithText("↗ Yesterday 09:12 · 3 min").assertIsDisplayed()
    }

    @Test fun groupingHelperKeepsNewestFirstAndStampIsLocalised() {
        val g = groupCalls(listOf(rec("1", true, CallOutcome.ANSWERED, false, at(8, 10, 0)), rec("2", true, CallOutcome.ANSWERED, false, at(8, 9, 0))))
        assertEquals(1, g.size)
        assertEquals("Today 10:00", CallRecords.stamp(g[0].latest.atMs, now))
    }
}
