package lk.codegen.risime.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import lk.codegen.risime.data.tabs.RisiItems
import lk.codegen.risime.data.tabs.RisiItemsLocal
import lk.codegen.risime.data.tabs.RisiItemsModel
import lk.codegen.risime.data.tabs.RisiItemsRest
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiItem
import lk.codegen.risime.net.RisiItemPatch
import lk.codegen.risime.net.RisiItemReply
import lk.codegen.risime.net.RisiItemsReply
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** v1.35 §34.4 Settings → Risi skills → Calendar → "Risi's items": the list, Open / Edit / Delete, and Delete of a phone event. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiItemsUiTest {
    @get:Rule val rule = createComposeRule()

    private val phone = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    private val items: List<RisiItem> by lazy {
        ProtocolJson.decodeFromString<RisiItemsReply>(javaClass.classLoader!!.getResource("contract/v1/examples/risi_items_reply.json")!!.readText()).items
    }

    private inner class Rest : RisiItemsRest {
        val deleted = mutableListOf<String>()
        override suspend fun list(from: String?, to: String?, kinds: List<String>?): ApiResult<RisiItemsReply> = ApiResult.Ok(RisiItemsReply(items))
        override suspend fun patch(id: String, body: RisiItemPatch): ApiResult<RisiItemReply> = ApiResult.Ok(RisiItemReply(items.single { it.id == id }))
        override suspend fun delete(id: String): ApiResult<Unit> { deleted += id; return ApiResult.Ok(Unit) }
    }

    private class Local(val device: String) : RisiItemsLocal {
        val provider = mutableSetOf(4711L)
        override suspend fun deviceId() = device
        override suspend fun ownsPhoneEvent(writeId: String?, eventId: Long) = writeId == "5d6e7f8a-9b0c-4d1e-8f2a-3b4c5d6e7f8a" && eventId == 4711L
        override suspend fun deletePhoneEvent(writeId: String, eventId: Long) = provider.remove(eventId)
        override suspend fun scheduledText(scheduleId: String) = "Good morning"
        override suspend fun cancelSchedule(scheduleId: String) = true
        override suspend fun editSchedule(scheduleId: String, text: String) = true
    }

    private class Opener : RisiItemOpener {
        val opened = mutableListOf<String>()
        val edited = mutableListOf<String>()
        override fun open(item: RisiItem) { opened += item.kind }
        override fun editElsewhere(item: RisiItem) { edited += item.kind }
    }

    private fun show(device: String = phone): Triple<Rest, Local, Opener> {
        val rest = Rest()
        val local = Local(device)
        val opener = Opener()
        val model = RisiItemsModel(rest, local, CoroutineScope(Dispatchers.Unconfined), now = { Instant.parse("2026-10-11T09:00:00Z").toEpochMilli() })
        model.load()
        rule.setContent { RisiMeTheme { RisiItemsScreen(model, opener, onBack = {}) } }
        return Triple(rest, local, opener)
    }

    /** The [tag] button of the row of [kind] (scrolled into view first). */
    private fun buttonOf(kind: String, tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        rule.onNodeWithTag("risi_items_list").performScrollToNode(androidx.compose.ui.test.hasTestTag("risi_item_$kind"))
        return rule.onNode(androidx.compose.ui.test.hasTestTag(tag) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("risi_item_$kind")))
    }

    @Test fun listsTheItemsByDayWithTheirButtons() {
        show()
        rule.onNodeWithText(RisiItems.TITLE).assertIsDisplayed()
        rule.onAllNodesWithTag("risi_items_day")[0].assertIsDisplayed()
        // The scheduled message shows this phone's own text; the phone event its calendar.
        rule.onNodeWithText("Good morning").assertIsDisplayed()
        rule.onNodeWithTag("risi_items_list").performScrollToNode(hasText("Call with Kamal"))
        rule.onNodeWithText("Call with Kamal").assertIsDisplayed()
        rule.onNodeWithText("Work (Google)").assertIsDisplayed()
        rule.onNodeWithTag("risi_items_list").performScrollToNode(hasText(RisiItems.NO_DATE))
        rule.onNodeWithText(RisiItems.NO_DATE).assertIsDisplayed()
    }

    @Test fun onAnotherPhoneItsPhoneItemsHaveNoButtons() {
        val (_, _, opener) = show(device = "99999999-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
        rule.onNodeWithText(RisiItems.OTHER_PHONE).assertIsDisplayed()
        // The scheduled message (first row) has no buttons here: the first Open is the Risi Calendar event's.
        rule.onAllNodesWithTag("risi_item_open")[0].performClick()
        assertEquals(listOf(RisiItem.RISI_CALENDAR_EVENT), opener.opened)
        assertTrue(rule.onAllNodesWithText("Call with Kamal").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun openAndEditAreDeepLinks() {
        val (_, _, opener) = show()
        rule.onAllNodesWithTag("risi_item_open")[0].performClick()
        assertEquals(listOf(RisiItem.SCHEDULED_MESSAGE), opener.opened)
        // The Risi Calendar event (second row) edits on its event screen.
        rule.onAllNodesWithTag("risi_item_edit")[1].performClick()
        assertEquals(listOf(RisiItem.RISI_CALENDAR_EVENT), opener.edited)
    }

    @Test fun deletingAPhoneEventAsksFirstThenRemovesTheProviderRow() {
        val (rest, local, _) = show()
        // Rows: scheduled message, Risi Calendar event, phone event (each with Delete).
        buttonOf(RisiItem.PHONE_EVENT_ADDED, "risi_item_delete").performClick()
        rule.onNodeWithText("Delete this event from your phone's calendar?").assertIsDisplayed()
        assertTrue(4711L in local.provider)
        rule.onNodeWithTag("risi_item_delete_confirm").performClick()
        rule.waitForIdle()
        assertFalse(4711L in local.provider)
        assertEquals(listOf("3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f"), rest.deleted)
        rule.onNodeWithText("Call with Kamal").assertDoesNotExist()
    }

    @Test fun cancellingTheDialogDeletesNothing() {
        val (rest, local, _) = show()
        buttonOf(RisiItem.PHONE_EVENT_ADDED, "risi_item_delete").performClick()
        rule.onNodeWithText(RisiItems.CANCEL).performClick()
        assertTrue(4711L in local.provider)
        assertTrue(rest.deleted.isEmpty())
    }
}
