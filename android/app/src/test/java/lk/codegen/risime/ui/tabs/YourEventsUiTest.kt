package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.LocalEvent
import lk.codegen.risime.data.tabs.LocalEvents
import lk.codegen.risime.data.tabs.LocalEventsResult
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiTextEnvelope
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** v1.32 §29.7 "Your events" under the contract's `local_events` answer: rows, none, no permission, never outside the Risi chat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class YourEventsUiTest {
    @get:Rule val rule = createComposeRule()

    private val me = "ff03ab6f-6457-46b1-a53b-9efd937920df"
    private val conv = "grp:9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"

    private class Host(override val me: String, val result: LocalEventsResult) : RisiHost {
        val asked = mutableListOf<Pair<Long, Long>>()
        override fun ask(text: String) {}
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) {}
        override fun feedback(callRef: String, rating: String, reason: String?) {}
        override suspend fun localEvents(fromMs: Long, toMs: Long): LocalEventsResult { asked += fromMs to toMs; return result }
    }

    private fun row(): MessageEntity {
        val s = javaClass.classLoader!!.getResource("contract/v1/examples/envelope_risi_answer_local_events.json")!!.readText()
        val env = ProtocolJson.decodeFromString(RisiTextEnvelope.serializer(), s)
        val risi = (ProtocolJson.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject)["risi"] as kotlinx.serialization.json.JsonObject
        return MessageEntity(
            clientMsgId = "le", messageId = "c1a2b3f1-a4f0-11f1-8000-0242ac120009", conversationId = conv, from = "9e1f0000-0000-4000-8000-000000000001", to = conv,
            body = env.body, serverTs = "2026-10-10T09:30:00.000Z", localTs = 1L, status = MessageStatus.DELIVERED.name, outgoing = false,
            systemJson = RisiMessages.encode(risi),
        )
    }

    private fun show(h: Host, risiChat: Boolean = true) {
        val m = row()
        val ctx = risiCardContext(h, listOf(m), { "You" }, 0L, onRef = {}, risiChat = risiChat)
        rule.setContent { RisiMeTheme { Column { RisiCardRow(m, RisiMessages.meta(m)!!, ctx) } } }
    }

    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun listsTheEventsSortedOneLineEach() {
        val ev = listOf(
            LocalEvent("Standup", ms("2026-10-12T04:30:00Z"), ms("2026-10-12T05:30:00Z"), false),
            LocalEvent("Poya", ms("2026-10-14T18:30:00Z"), ms("2026-10-15T18:30:00Z"), true),
        )
        val h = Host(me, LocalEventsResult.Events(ev, ms("2026-10-11T18:30:00Z"), ms("2026-10-18T18:30:00Z")))
        show(h)
        rule.onNodeWithText(LocalEvents.HEADER).assertIsDisplayed()
        rule.onNodeWithTag("risi_your_event_0").assertIsDisplayed()
        rule.onNodeWithTag("risi_your_event_1").assertIsDisplayed()
        rule.onNodeWithText("All day", substring = true).assertIsDisplayed()
        assertEquals(listOf(ms("2026-10-11T18:30:00Z") to ms("2026-10-18T18:30:00Z")), h.asked)
    }

    @Test fun noneSaysSo() {
        show(Host(me, LocalEventsResult.Events(emptyList(), ms("2026-10-11T18:30:00Z"), ms("2026-10-18T18:30:00Z"))))
        rule.onNodeWithTag("risi_your_events_none").assertIsDisplayed()
        rule.onNodeWithText("No events in your phone calendars from", substring = true).assertIsDisplayed()
    }

    @Test fun withoutPermissionAsksForIt() {
        show(Host(me, LocalEventsResult.NoPermission))
        rule.onNodeWithText(LocalEvents.NO_PERMISSION).assertIsDisplayed()
        rule.onNodeWithTag("risi_your_events_allow").assertIsDisplayed()
    }

    @Test fun neverInOfficialOrForSomeoneElse() {
        val h = Host(me, LocalEventsResult.NoPermission)
        show(h, risiChat = false)
        rule.onNodeWithTag("risi_your_events").assertDoesNotExist()
        assertEquals(0, h.asked.size)
    }

    @Test fun notTheAskersDevice() {
        val h = Host("3b4c5d6e-7f8a-4b9c-8d0e-1f2a3b4c5d6e", LocalEventsResult.NoPermission)
        show(h)
        rule.onNodeWithTag("risi_your_events").assertDoesNotExist()
    }
}
