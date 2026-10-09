package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.calendar.CalendarResult
import lk.codegen.risime.data.calendar.RisiCalendarCardsPort
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiCalendarEventsReply
import lk.codegen.risime.net.RisiCalendarSettings
import lk.codegen.risime.net.RisiEvent
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.ui.calendar.RISI_CALENDAR_INFO
import lk.codegen.risime.ui.calendar.RisiCalendarActions
import lk.codegen.risime.ui.calendar.RisiCalendarScreen
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
import java.time.ZoneId
import java.util.TimeZone

/**
 * v1.29 §29.8–§29.10 the Risi Calendar cards as native cards (never chips) and their actions, and §29 the
 * Calendar tab's views. Fixtures under test resources `fixtures/risi_calendar/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiCalendarCardsUiTest {
    @get:Rule val rule = createComposeRule()

    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val shenika = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val ev = "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b"
    private val risiUser = "9e1f0000-0000-4000-8000-000000000001"
    private val conv = "grp:3d4e5f6a-7b8c-4d9e-8f0a-1b2c3d4e5f6a"
    private val now = java.time.Instant.parse("2026-10-09T15:11:00Z").toEpochMilli()
    private var tz: TimeZone? = null

    @Before fun zone() {
        tz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))
    }

    @After fun restore() = TimeZone.setDefault(tz)

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/risi_calendar/$name")!!.readText()
    private val cache: List<RisiEvent> by lazy { ProtocolJson.decodeFromString(RisiCalendarEventsReply.serializer(), fixture("risi_calendar_events_reply.json")).events }

    private class Port(list: List<RisiEvent>) : RisiCalendarCardsPort {
        val calls = mutableListOf<String>()
        override val events = MutableStateFlow(list)
        override suspend fun respond(eventId: String, response: String, version: Int?, expectStart: String?, expectEnd: String?, suggest: Triple<String, String, Boolean>?): CalendarResult {
            calls += "respond:$eventId:$response:$version" + (suggest?.let { ":${it.first}" } ?: "")
            return CalendarResult.Ok(null)
        }
        override suspend fun resolve(suggestionId: String, action: String): CalendarResult { calls += "resolve:$suggestionId:$action"; return CalendarResult.Ok(null) }
        override suspend fun delete(eventId: String): CalendarResult { calls += "delete:$eventId"; return CalendarResult.Ok(null) }
        override fun open(eventId: String) { calls += "open:$eventId" }
    }

    private class Host(override val me: String, override val risiCalendar: RisiCalendarCardsPort?) : RisiHost {
        val calls = mutableListOf<String>()
        override fun ask(text: String) { calls += "ask:$text" }
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) { calls += "act:$target:$action" }
        override fun feedback(callRef: String, rating: String, reason: String?) {}
        override fun confirmEdited(writeId: String, edit: JsonObject) { calls += "edit:$writeId:${edit["start"]}" }
        override fun conversationName(conversationId: String): String? = if (conversationId.startsWith("grp:9a8b")) "Shenika · Official" else null
        override fun openChat(conversationId: String, atIso: String?) { calls += "chat:$conversationId" }
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

    private fun show(host: Host, rows: List<Pair<MessageEntity, RisiMeta>>) {
        val ctx = risiCardContext(host, rows.map { it.first }, { if (it == shenika) "Shenika" else "Kamal" }, now, onRef = {})
        rule.setContent { RisiMeTheme { Column { rows.forEach { (m, r) -> RisiCardRow(m, r, ctx) } } } }
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theActionCardAddsWithoutAnyDevicePermission() {
        val h = Host(me, Port(cache))
        show(h, listOf(row("envelope_risi_confirm_risi_calendar_add.json")))
        rule.onNodeWithText("Risi · Add to Risi Calendar").assertExists()
        rule.onNodeWithTag("risi_calendar_add_card").assertExists()
        rule.onNodeWithText("Mon 12 Oct · 14:00–15:00").assertExists()
        rule.onNodeWithText("With: Shenika").assertExists()
        rule.onNodeWithTag("risi_calendar_edit").assertExists()
        rule.onNodeWithTag("risi_confirm_add").performClick()
        // [Add] is confirm_write: the server creates the event (no client tool, no picker, no permission).
        assertEquals(listOf("act:2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e:confirm_write"), h.calls)
    }

    @Test fun cancelIsCancelWrite() {
        val h = Host(me, null)
        show(h, listOf(row("envelope_risi_confirm_risi_calendar_add.json")))
        rule.onNodeWithTag("risi_confirm_cancel").performClick()
        assertEquals(listOf("act:2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e:cancel_write"), h.calls)
    }

    @Test fun anInviteShowsTheTimelineWithTheClashAndAccepts() {
        val port = Port(cache)
        val h = Host(me, port)
        show(h, listOf(row("envelope_risi_calendar_invite.json")))
        rule.onNodeWithText("Risi · Invitation").assertExists()
        rule.onNodeWithTag("risi_calendar_invite").assertExists()
        rule.onNodeWithText("With: Shenika ?").assertExists()
        rule.onNodeWithText("From: ").assertDoesNotExist()
        rule.onNodeWithTag("risi_event_from").assertExists()
        rule.onNodeWithTag("risi_event_timeline").assertExists()
        rule.onNodeWithText("Clashes with 1 event").assertExists()
        rule.onNodeWithTag("risi_event_suggest").assertExists()
        rule.onNodeWithTag("risi_event_accept").performClick()
        rule.waitUntil(3_000) { port.calls.isNotEmpty() }
        assertEquals(listOf("respond:$ev:accept:1"), port.calls)
    }

    @Test fun suggestAnotherTimeOpensThePickerPrefilledPlusOneHour() {
        val port = Port(cache)
        show(Host(me, port), listOf(row("envelope_risi_calendar_invite.json")))
        rule.onNodeWithTag("risi_event_suggest").performClick()
        rule.onNodeWithTag("risi_suggest_form").assertExists()
        rule.onNodeWithText("15:00").assertExists()
        rule.onNodeWithTag("risi_suggest_send").performClick()
        rule.waitUntil(3_000) { port.calls.isNotEmpty() }
        assertEquals(listOf("respond:$ev:suggest:1:2026-10-12T09:30:00.000Z"), port.calls)
    }

    @Test fun anAnsweredInviteFollowsTheUpdateAndTheUpdateHasNoBubble() {
        val port = Port(listOf(cache[0].copy(version = 2, myStatus = "accepted", participants = cache[0].participants.map { it.copy(status = "accepted") })))
        show(Host(me, port), listOf(row("envelope_risi_calendar_invite.json"), row("envelope_risi_event_update.json")))
        rule.onNodeWithText("With: Shenika ✓").assertExists()
        rule.onNodeWithTag("risi_event_accept").assertDoesNotExist()
        rule.onNodeWithText("Accepted").assertExists()
        // event_update applied to the invite: no line of its own.
        rule.onNodeWithText("Shenika accepted 'Interview'").assertDoesNotExist()
    }

    @Test fun anUpdateWithoutItsCardIsASmallLine() {
        show(Host(me, Port(cache)), listOf(row("envelope_risi_event_update.json")))
        rule.onNodeWithText("Shenika accepted 'Interview'").assertExists()
    }

    @Test fun theOfficialCardOffersAnswersOnlyToProposedParticipants() {
        val port = Port(cache)
        show(Host("3f3f0000-0000-4000-8000-000000000003", port), listOf(row("envelope_risi_event_card_official.json")))
        rule.onNodeWithText("Risi · Meeting").assertExists()
        rule.onNodeWithTag("risi_event_accept").assertDoesNotExist()
        rule.onNodeWithText("Open in Calendar").performClick()
        assertEquals(listOf("open:$ev"), port.calls)
    }

    @Test fun theAddedCardHasOpenEditDeleteForTheOwner() {
        // The cache doesn't have it yet (the card's own data; owner = me).
        val port = Port(emptyList())
        show(Host(me, port), listOf(row("envelope_risi_event_card_added.json")))
        rule.onNodeWithText("Risi · Added to your Risi Calendar").assertExists()
        rule.onNodeWithTag("risi_event_edit").assertExists()
        rule.onNodeWithTag("risi_event_delete").performClick()
        rule.onNodeWithTag("risi_event_delete_confirm").performClick()
        rule.waitUntil(3_000) { port.calls.isNotEmpty() }
        assertEquals(listOf("delete:$ev"), port.calls)
    }

    @Test fun theOwnerUsesASuggestion() {
        val port = Port(cache)
        show(Host(me, port), listOf(row("envelope_risi_calendar_suggestion.json")))
        rule.onNodeWithText("Risi · New time suggested").assertExists()
        rule.onNodeWithTag("risi_suggestion_use").performClick()
        rule.waitUntil(3_000) { exists("risi_suggestion_state") }
        assertEquals(listOf("resolve:9c0d1e2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f:use"), port.calls)
        rule.onNodeWithTag("risi_suggestion_keep").assertDoesNotExist()
    }

    @Test fun aReminderCardOpensTheEvent() {
        val port = Port(cache)
        show(Host(me, port), listOf(row("envelope_risi_calendar_reminder.json")))
        rule.onNodeWithText("In 30 min: Interview (14:00)").assertExists()
        rule.onNodeWithTag("risi_event_open").performClick()
        assertEquals(listOf("open:$ev"), port.calls)
    }

    @Test fun anUnknownKindIsItsBody() {
        show(Host(me, Port(cache)), listOf(row("envelope_risi_future_kind.json")))
        rule.onNodeWithText("Something new. Update RisiMe to answer.").assertExists()
    }

    // ---- the Calendar tab ----

    private class Actions : RisiCalendarActions {
        val calls = mutableListOf<String>()
        override fun opened() { calls += "opened" }
        override fun loadRange(fromMs: Long, toMs: Long) { calls += "range" }
        override fun respond(event: RisiEvent, response: String) { calls += "respond:${event.title}:$response" }
        override fun delete(event: RisiEvent) { calls += "delete:${event.title}" }
        override fun setReminder(event: RisiEvent, minutes: Int?) { calls += "reminder:${event.title}:$minutes" }
        override fun openChat(conversationId: String) { calls += "chat:$conversationId" }
    }

    private fun screen(a: Actions, showDeclined: Boolean = false) = rule.setContent {
        RisiMeTheme {
            RisiCalendarScreen(
                cache, me, { if (it == shenika) "Shenika" else "Kamal" }, { if (it.startsWith("grp:9a8b")) "Shenika · Official" else null },
                RisiCalendarSettings(), showDeclined, a, zone = ZoneId.of("Asia/Colombo"), today = LocalDate.of(2026, 10, 9),
            )
        }
    }

    @Test fun agendaIsTheDefaultAndShowsTitleTimeParticipantsSourceAndStatus() {
        val a = Actions()
        screen(a)
        rule.onNodeWithTag("calendar_agenda").assertExists()
        rule.onNodeWithText("Interview").assertExists()
        rule.onNodeWithText("Standup").assertExists()
        rule.onNodeWithText("Lunch").assertDoesNotExist() // declined: hidden
        rule.onNodeWithText("With: Shenika ?").assertExists()
        rule.onNodeWithText("Proposed").assertExists()
        rule.onNodeWithText("From: Shenika · Official").performClick()
        assertTrue("opened" in a.calls)
        assertTrue("chat:grp:9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d" in a.calls)
    }

    @Test fun showDeclinedShowsThem() {
        screen(Actions(), showDeclined = true)
        rule.onNodeWithTag("calendar_mode_month").performClick()
        rule.onNodeWithTag("calendar_cell_2026-10-13").performClick()
        rule.onNodeWithText("Lunch").assertExists()
        rule.onNodeWithText("Declined").assertExists()
    }

    @Test fun dayWeekAndMonthViews() {
        val a = Actions()
        screen(a)
        rule.onNodeWithTag("calendar_mode_month").performClick()
        rule.onNodeWithText("October 2026").assertExists()
        rule.onNodeWithTag("calendar_month").assertExists()
        rule.onNodeWithTag("calendar_cell_2026-10-12").performClick()
        rule.onNodeWithText("Monday 12 October").assertExists()
        rule.onNodeWithTag("calendar_day").assertExists()
        rule.onNodeWithText("Interview").assertExists()
        rule.onNodeWithTag("calendar_mode_week").performClick()
        rule.onNodeWithText("12 Oct – 18 Oct").assertExists()
        rule.onNodeWithTag("calendar_week").assertExists()
        assertTrue("range" in a.calls)
    }

    @Test fun theDetailAnswersSetsMyReminderAndDeletes() {
        val a = Actions()
        screen(a)
        rule.onNodeWithTag("calendar_event_$ev").performClick()
        rule.onNodeWithTag("calendar_detail").assertExists()
        // Not the owner: no Edit.
        rule.onNodeWithTag("calendar_detail_edit").assertDoesNotExist()
        rule.onNodeWithTag("calendar_reminder_10").performScrollTo().performClick()
        rule.onNodeWithTag("calendar_detail_accept").performScrollTo().performClick()
        rule.onNodeWithTag("calendar_detail_delete").performClick()
        rule.onNodeWithTag("calendar_delete_confirm").performClick()
        assertEquals(listOf("reminder:Interview:10", "respond:Interview:accept", "delete:Interview"), a.calls.filter { !it.startsWith("opened") && it != "range" })
    }

    @Test fun theInfoSheetSaysItIsNotEndToEndEncrypted() {
        screen(Actions())
        rule.onNodeWithTag("calendar_info").performClick()
        rule.onNodeWithText(RISI_CALENDAR_INFO).assertExists()
    }
}
