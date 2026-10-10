package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
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
import java.util.TimeZone

/**
 * P0 (Harsha's phone): the calendar action card. [Add] sends `confirm_write` and nothing else (no extra
 * model turn); the first use opens the picker (Google calendars, read-only ones never); [Edit] sends the
 * exact event; after the add the card reads "Added to your Google Calendar: … [Open] [Undo]"; next-step
 * chips only fill the composer and never offer a question or a confirm phrase.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiCalendarCardUiTest {
    @get:Rule val rule = createComposeRule()

    private val asker = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val risiUser = "9e1f0000-0000-4000-8000-000000000001"
    private val conv = "grp:9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"
    private val writeId = "5d6e7f8a-9b0c-4d1e-8f2a-3b4c5d6e7f8a"
    private val now = java.time.Instant.parse("2026-10-09T15:11:00Z").toEpochMilli()
    private var tz: TimeZone? = null

    @Before fun zone() {
        tz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))
    }

    @After fun restore() = TimeZone.setDefault(tz)

    private fun example(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private val google = PhoneCalendarInfo(1, "harsha@example.com", "harsha@example.com", "com.google", 700, true, "harsha@example.com", true)

    private class Port(var opts: List<PhoneCalendarInfo>, chosen: PhoneCalendarInfo? = null) : RisiCalendarPort {
        override val records = MutableStateFlow<Map<String, CalendarAddRecord>>(emptyMap())
        override val chosenId = MutableStateFlow(chosen?.id)
        val opened = mutableListOf<Long>()
        override fun hasPermission() = true
        override suspend fun options() = opts
        override suspend fun chosen() = opts.firstOrNull { it.id == chosenId.value }
        override suspend fun choose(id: Long) { chosenId.value = id }
        override suspend fun matchHint(hint: lk.codegen.risime.net.RisiCalendarRef?) = opts.firstOrNull { hint != null && it.accountName == hint.account }
        override fun open(eventId: Long) { opened += eventId }
    }

    private class Host(override val me: String, override val calendar: RisiCalendarPort?) : RisiHost {
        val calls = mutableListOf<String>()
        override fun ask(text: String) { calls += "ask:$text" }
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) { calls += "act:$target:$action" }
        override fun feedback(callRef: String, rating: String, reason: String?) {}
        override fun undo(skillId: String, entryId: String, token: String) { calls += "undo:$skillId:$entryId:$token" }
        override fun confirmEdited(writeId: String, edit: JsonObject) { calls += "edit:$writeId:$edit" }
    }

    private var n = 0

    private fun row(file: String, edit: (JsonObject) -> JsonObject = { it }): Pair<MessageEntity, RisiMeta> {
        val env = ProtocolJson.parseToJsonElement(example(file)) as JsonObject
        val risi = edit(env["risi"] as JsonObject)
        val m = MessageEntity(
            clientMsgId = "$file${n++}", messageId = "c1a2b3f1-a4f0-11f1-8000-0242ac12000$n", conversationId = conv, from = risiUser, to = conv,
            body = (env["body"] as JsonPrimitive).content, serverTs = "2026-10-09T15:10:00.000Z", localTs = n.toLong(),
            status = MessageStatus.DELIVERED.name, outgoing = false, systemJson = RisiMessages.encode(risi),
        )
        return m to RisiMessages.meta(m)!!
    }

    private fun calendarCard() = row("envelope_risi_confirm.json") {
        JsonObject(it + ("skill_id" to JsonPrimitive("calendar")) + ("args" to ProtocolJson.parseToJsonElement("""{"title":"Dentist","start":"2026-10-16T04:30:00.000Z","end":"2026-10-16T05:30:00.000Z","all_day":false}""")))
    }

    private fun confirmed(): MessageEntity = MessageEntity(
        clientMsgId = "ctl${n++}", messageId = "ctl$n", conversationId = conv, from = asker, to = conv, body = "", serverTs = "2026-10-09T15:10:30.000Z",
        localTs = n.toLong(), status = MessageStatus.DELIVERED.name, outgoing = true, kind = MessageEntity.KIND_RISI_CTL,
        systemJson = String(RisiControl.encode(RisiControl.action(writeId, "confirm_write"))),
    )

    private var prefilled: String? = null

    private fun show(host: Host, rows: List<Pair<MessageEntity, RisiMeta>>, extra: List<MessageEntity> = emptyList()) {
        val messages = rows.map { it.first } + extra
        val ctx = risiCardContext(host, messages, { "You" }, now, onRef = {}, prefill = { prefilled = it })
        rule.setContent { RisiMeTheme { Column { rows.forEach { (m, r) -> RisiCardRow(m, r, ctx) } } } }
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theCardShowsTheEventAndAddSendsOnlyConfirmWrite() {
        val h = Host(asker, Port(listOf(google), chosen = google))
        show(h, listOf(calendarCard()))
        rule.onNodeWithText("Dentist").assertIsDisplayed()
        rule.onNodeWithText("Fri 16 Oct").assertIsDisplayed()
        rule.onNodeWithText("10–11 AM").assertIsDisplayed()
        rule.waitUntil(3_000) { exists("risi_calendar_chosen") }
        rule.onNodeWithText("harsha@example.com · Google").assertIsDisplayed()
        rule.onNodeWithTag("risi_calendar_edit").assertIsDisplayed()
        rule.onNodeWithTag("risi_confirm_add").performClick()
        rule.waitUntil(3_000) { h.calls.isNotEmpty() }
        // [Add] is the confirm: the phone runs the tool; no new request (no model turn).
        assertEquals(listOf("act:$writeId:confirm_write"), h.calls)
    }

    @Test fun firstUseOpensThePickerThenAdds() {
        val port = Port(listOf(google, google.copy(id = 2, displayName = "Work")))
        val h = Host(asker, port)
        show(h, listOf(calendarCard()))
        rule.onNodeWithText("Choose calendar").assertIsDisplayed()
        rule.onNodeWithTag("risi_confirm_add").performClick()
        rule.waitUntil(3_000) { exists("risi_calendar_picker") }
        rule.onNodeWithText("Work").assertIsDisplayed()
        assertTrue(h.calls.isEmpty())
        rule.onNodeWithTag("risi_calendar_option_2").performClick()
        rule.onNodeWithTag("risi_calendar_pick").performClick()
        rule.waitUntil(3_000) { h.calls.isNotEmpty() }
        assertEquals(2L, port.chosenId.value)
        assertEquals(listOf("act:$writeId:confirm_write"), h.calls)
    }

    @Test fun editConfirmsWithTheCorrectedEvent() {
        val h = Host(asker, Port(listOf(google), chosen = google))
        show(h, listOf(calendarCard()))
        rule.onNodeWithTag("risi_calendar_edit").performClick()
        rule.onNodeWithTag("risi_calendar_edit_title").performTextReplacement("Dentist (check-up)")
        rule.onNodeWithTag("risi_calendar_edit_time").performTextReplacement("10:30")
        rule.onNodeWithTag("risi_calendar_edit_send").performClick()
        rule.waitUntil(3_000) { h.calls.isNotEmpty() }
        // One confirm_write with the edit (no cancel, no new request: no model turn).
        assertEquals(
            listOf("edit:$writeId:{\"title\":\"Dentist (check-up)\",\"start\":\"2026-10-16T05:00:00.000Z\",\"end\":\"2026-10-16T06:00:00.000Z\",\"all_day\":false}"),
            h.calls,
        )
    }

    @Test fun afterTheAddTheCardIsTheSuccessCardWithOpenAndUndo() {
        val port = Port(listOf(google), chosen = google)
        port.records.value = mapOf(
            writeId to CalendarAddRecord(writeId, "6d1f2e3a-4b5c-4d6e-9f7a-8b9c0d1e2f3a", "Dentist", "2026-10-16T04:30:00Z", "2026-10-16T05:30:00Z", false,
                eventId = 4711, calendarId = 1, calendarName = "harsha@example.com · Google", accountType = "com.google"),
        )
        val h = Host(asker, port)
        val done = row("envelope_risi_skill_done.json") {
            JsonObject(it + ("skill_id" to JsonPrimitive("calendar")) + ("action" to JsonPrimitive("calendar_added")) + ("undo_token" to JsonPrimitive("tok")) +
                ("undo" to ProtocolJson.parseToJsonElement("""{"kind":"client","state":"available","until":null,"hint":null}""")))
        }
        show(h, listOf(calendarCard(), done), extra = listOf(confirmed()))
        // The card says it is done; the success line below (skill_done) carries [Open] and [Undo], once each.
        rule.onNodeWithText("✓ Added · Fri 16 Oct, 10–11 AM").assertIsDisplayed()
        assertEquals(1, rule.onAllNodes(hasTestTag("risi_calendar_open")).fetchSemanticsNodes().size)
        rule.onNodeWithTag("risi_calendar_open").performClick()
        assertEquals(listOf(4711L), port.opened)
        assertEquals(1, rule.onAllNodes(hasTestTag("risi_undo")).fetchSemanticsNodes().size)
        rule.onNodeWithTag("risi_undo").performClick()
        assertEquals(listOf("undo:calendar:7c6b5a49-3827-4615-9403-f2a1b0c9d8e7:tok"), h.calls)
    }

    @Test fun theServersHintPicksTheCalendarWithoutThePicker() {
        val port = Port(listOf(google))
        val h = Host(asker, port)
        show(h, listOf(row("envelope_risi_confirm.json") {
            JsonObject(it + ("skill_id" to JsonPrimitive("calendar")) + ("calendar" to ProtocolJson.parseToJsonElement("""{"name":"Google Calendar","account":"harsha@example.com"}""")) +
                ("args" to ProtocolJson.parseToJsonElement("""{"title":"Dentist","start":"2026-10-16T04:30:00.000Z","end":"2026-10-16T05:30:00.000Z","all_day":false}""")))
        }))
        rule.waitUntil(3_000) { exists("risi_calendar_chosen") }
        rule.onNodeWithTag("risi_confirm_add").performClick()
        rule.waitUntil(3_000) { h.calls.isNotEmpty() }
        assertTrue(!exists("risi_calendar_picker"))
        assertEquals(1L, port.chosenId.value)
        assertEquals(listOf("act:$writeId:confirm_write"), h.calls)
    }

    @Test fun withoutASkillDoneTheCardIsTheSuccessCard() {
        val port = Port(listOf(google), chosen = google)
        port.records.value = mapOf(writeId to CalendarAddRecord(writeId, null, "Dentist", "2026-10-16T04:30:00Z", "2026-10-16T05:30:00Z", false,
            eventId = 4711, calendarId = 1, calendarName = "harsha@example.com · Google", accountType = "com.google"))
        show(Host(asker, port), listOf(calendarCard()), extra = listOf(confirmed()))
        rule.onNodeWithText("Added to your Google Calendar: Dentist · Fri 16 Oct, 10–11 AM").assertIsDisplayed()
        rule.onNodeWithTag("risi_calendar_open").performClick()
        assertEquals(listOf(4711L), port.opened)
    }

    @Test fun aFailedAddSaysWhy() {
        val port = Port(listOf(google), chosen = google)
        port.records.value = mapOf(writeId to CalendarAddRecord(writeId, null, "Dentist", "2026-10-16T04:30:00Z", "2026-10-16T05:30:00Z", failure = "NO_GOOGLE_CALENDAR"))
        show(Host(asker, port), listOf(calendarCard()), extra = listOf(confirmed()))
        rule.onNodeWithTag("risi_calendar_failed").assertIsDisplayed()
        assertTrue(!exists("risi_confirm_add"))
    }

    @Test fun noNextStepChipIsDrawnAndNothingIsSentOrPrefilled() {
        val h = Host(asker, null)
        val answer = row("envelope_risi_answer_v2.json") {
            JsonObject(it + ("next_steps" to JsonArray(listOf("Confirm to add the event", "What is on Monday?", "Remind me 30 min before").map(::JsonPrimitive))))
        }
        show(h, listOf(answer))
        // v1.32 item 7: a chip that only pre-fills its label as chat text is a fake chip: none is drawn.
        assertTrue(!exists("risi_next_0") && !exists("risi_next_1"))
        rule.onNodeWithText("Remind me 30 min before").assertDoesNotExist()
        assertEquals(null, prefilled)
        assertTrue(h.calls.isEmpty())
    }

    @Test fun skillNeededForACalendarNeverOnSaysTurnOnCalendar() {
        val h = Host(asker, null)
        show(h, listOf(row("envelope_risi_skill_needed.json") { JsonObject(it + ("skill_id" to JsonPrimitive("calendar")) + ("was_on" to JsonPrimitive(false))) }))
        rule.onNodeWithText("Turn on Calendar").assertIsDisplayed()
    }
}
