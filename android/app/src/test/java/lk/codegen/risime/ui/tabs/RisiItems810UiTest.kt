package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.CalendarAddRecord
import lk.codegen.risime.data.tabs.PhoneCalendarInfo
import lk.codegen.risime.data.tabs.RisiCalendarPort
import lk.codegen.risime.data.tabs.RisiControl
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.util.TimeZone

/**
 * Server items 8 and 10 (2026-10-09): a proactive offer is the P0 action card with "Add to calendar?" /
 * "Remind me?" ([Add] sends only `confirm_write`; no progress bubble); the `item_clarify` card shows the
 * question and, for the owner, [New date] → the picker → §27.5 `item_edit` with the new `due`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiItems810UiTest {
    @get:Rule val rule = createComposeRule()

    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val risiUser = "9e1f0000-0000-4000-8000-000000000001"
    private val conv = "grp:9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"
    private val now = java.time.Instant.parse("2026-10-09T15:11:00Z").toEpochMilli()
    private var tz: TimeZone? = null

    @Before fun zone() {
        tz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))
    }

    @After fun restore() = TimeZone.setDefault(tz)

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/risi_items_8_10/$name")!!.readText()

    private val google = PhoneCalendarInfo(1, "harsha@example.com", "harsha@example.com", "com.google", 700, true, "harsha@example.com", true)

    private class Port(chosen: PhoneCalendarInfo) : RisiCalendarPort {
        private val opts = listOf(chosen)
        override val records = MutableStateFlow<Map<String, CalendarAddRecord>>(emptyMap())
        override val chosenId = MutableStateFlow<Long?>(chosen.id)
        override fun hasPermission() = true
        override suspend fun options() = opts
        override suspend fun chosen() = opts.firstOrNull { it.id == chosenId.value }
        override suspend fun choose(id: Long) { chosenId.value = id }
        override fun open(eventId: Long) {}
    }

    private class Host(override val me: String, override val calendar: RisiCalendarPort?) : RisiHost {
        val calls = mutableListOf<String>()
        override fun ask(text: String) { calls += "ask:$text" }
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) { calls += "act:$target:$action" }
        override fun actItem(itemId: String, action: String, text: String?, due: String?, allDay: Boolean) { calls += "item:$itemId:$action:$text:$due:$allDay" }
        override fun feedback(callRef: String, rating: String, reason: String?) {}
        override fun confirmEdited(writeId: String, edit: JsonObject) { calls += "edit:$writeId" }
    }

    private var n = 0

    private fun row(name: String): Pair<MessageEntity, RisiMeta> {
        val env = ProtocolJson.parseToJsonElement(fixture(name)) as JsonObject
        val m = MessageEntity(
            clientMsgId = "$name${n++}", messageId = "c1a2b3f1-a4f0-11f1-8000-0242ac12010$n", conversationId = conv, from = risiUser, to = conv,
            body = (env["body"] as JsonPrimitive).content, serverTs = "2026-10-09T15:10:00.000Z", localTs = n.toLong(),
            status = MessageStatus.DELIVERED.name, outgoing = false, systemJson = RisiMessages.encode(env["risi"] as JsonObject),
        )
        return m to RisiMessages.meta(m)!!
    }

    private fun show(host: Host, rows: List<Pair<MessageEntity, RisiMeta>>, extra: List<MessageEntity> = emptyList()) {
        val ctx = risiCardContext(host, rows.map { it.first } + extra, { "You" }, now, onRef = {}, prefill = {})
        rule.setContent { RisiMeTheme { Column { rows.forEach { (m, r) -> RisiCardRow(m, r, ctx) } } } }
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun aCalendarOfferIsTheActionCardAskingAddToCalendar() {
        val h = Host(me, Port(google))
        show(h, listOf(row("envelope_risi_confirm_offer_calendar.json")))
        rule.onNodeWithText("Risi · Add to calendar?").assertExists()
        rule.onNodeWithTag("risi_calendar_card").assertExists()
        rule.onNodeWithText("Interview with Shenika").assertExists()
        rule.onNodeWithTag("risi_calendar_edit").assertExists()
        rule.onNodeWithTag("risi_confirm_cancel").assertExists()
        rule.waitUntil(3_000) { exists("risi_calendar_chosen") }
        rule.onNodeWithTag("risi_confirm_add").performClick()
        rule.waitUntil(3_000) { h.calls.isNotEmpty() }
        // [Add] is the confirm (no request, no model turn).
        assertEquals(listOf("act:5d6e7f8a-9b0c-4d1e-8f2a-3b4c5d6e7f8a:confirm_write"), h.calls)
    }

    @Test fun aReminderOfferAsksRemindMeWithAddAndCancel() {
        val h = Host(me, null)
        show(h, listOf(row("envelope_risi_confirm_offer_reminder.json")))
        rule.onNodeWithText("Risi · Remind me?").assertExists()
        rule.onNodeWithText("Remind you on Mon 12 Oct at 13:45: Interview with Shenika").assertExists()
        rule.onNodeWithTag("risi_confirm_cancel").performClick()
        assertEquals(listOf("act:6e7f8a9b-0c1d-4e2f-8a3b-4c5d6e7f8a9b:cancel_write"), h.calls)
    }

    @Test fun theClarifyCardShowsTheQuestionAndNewDateOpensThePicker() {
        val h = Host(me, null)
        show(h, listOf(row("envelope_risi_item_clarify.json")))
        rule.onNodeWithText("Risi · When is it due?").assertExists()
        rule.onNodeWithTag("risi_clarify_question").assertExists()
        rule.onNodeWithTag("risi_clarify_new_date").performClick()
        rule.onNodeWithTag("risi_new_date_form").assertExists()
        rule.onNodeWithTag("risi_new_date_day").assertExists()
        assertTrue(h.calls.isEmpty())
    }

    @Test fun aV124ClarifyShowsTheQuestionOnly() {
        show(Host(me, null), listOf(row("envelope_risi_item_clarify_no_buttons.json")))
        rule.onNodeWithTag("risi_clarify_question").assertExists()
        assertTrue(!exists("risi_clarify_new_date"))
    }

    @Test fun afterMyItemEditTheCardSaysTheDateWasSent() {
        val (m, r) = row("envelope_risi_item_clarify.json")
        val edit = MessageEntity(
            clientMsgId = "ctl", messageId = "ctl1", conversationId = conv, from = me, to = conv, body = "", serverTs = "2026-10-09T15:10:30.000Z",
            localTs = 99, status = MessageStatus.DELIVERED.name, outgoing = true, kind = MessageEntity.KIND_RISI_CTL,
            systemJson = String(RisiControl.encode(RisiControl.action(r.itemId!!, "item_edit", r.text, "2026-10-14T04:30:00.000Z", editAllDay = false))),
        )
        show(Host(me, null), listOf(m to r), extra = listOf(edit))
        rule.onNodeWithTag("risi_clarify_sent").assertExists()
        assertTrue(!exists("risi_clarify_new_date"))
    }

    @Test fun theDigestUsesTheSameDirectionsAndTotals() {
        show(Host(me, null), listOf(row("envelope_risi_digest_personal_item9.json")))
        rule.onNodeWithText("I promised (2)").assertExists()
        rule.onNodeWithText("Promised to me (1)").assertExists()
        rule.onNodeWithText("Send the revised quote").assertExists()
        assertTrue(!exists("risi_digest_section_others"))
    }

    @Test fun savingTheNewDateGivesTheItemEditDue() {
        var picked: Pair<String, Boolean>? = null
        rule.setContent { RisiMeTheme { NewDateDialog(onPick = { d, a -> picked = d to a }, onDismiss = {}, initialDate = LocalDate.of(2026, 10, 14), initialTime = LocalTime.of(10, 0)) } }
        rule.onNodeWithText("Wed 14 Oct").assertExists()
        rule.onNodeWithTag("risi_new_date_save").performClick()
        assertEquals("2026-10-14T04:30:00.000Z" to false, picked)
    }

    @Test fun allDaySavesTheDaysEnd() {
        var picked: Pair<String, Boolean>? = null
        rule.setContent { RisiMeTheme { NewDateDialog(onPick = { d, a -> picked = d to a }, onDismiss = {}, initialDate = LocalDate.of(2026, 10, 14)) } }
        rule.onNodeWithTag("risi_new_date_all_day").performClick()
        rule.onNodeWithTag("risi_new_date_save").performClick()
        assertEquals("2026-10-14T18:29:59.999Z" to true, picked)
    }
}
