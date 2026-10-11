package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.jsonObject
import lk.codegen.risime.data.db.MessageEntity
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

/** v1.35 §34 Phase 2 UI: a greyed superseded card, the error card's "Ask me again", the native clarify card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiP0CardsUiTest {
    @get:Rule val rule = createComposeRule()

    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"

    private class Host(override val me: String) : RisiHost {
        val acts = mutableListOf<Pair<String, String>>()
        override fun ask(text: String) {}
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) { acts += target to action }
        override fun feedback(callRef: String, rating: String, reason: String?) {}
    }

    private fun read(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private fun row(name: String, id: String = name, edit: (String) -> String = { it }): MessageEntity {
        val s = edit(read(name))
        return MessageEntity(
            clientMsgId = id, messageId = id, conversationId = "grp:risi", from = "9e1f0000-0000-4000-8000-000000000001", to = "grp:risi",
            body = ProtocolJson.decodeFromString(RisiTextEnvelope.serializer(), s).body, serverTs = "2026-10-10T09:30:00.000Z", localTs = 1L,
            status = "DELIVERED", outgoing = false, systemJson = RisiMessages.encode(ProtocolJson.parseToJsonElement(s).jsonObject["risi"]!!.jsonObject),
        )
    }

    private val beforeExpiry = Instant.parse("2026-10-10T10:00:00Z").toEpochMilli()

    private fun show(rows: List<MessageEntity>, host: Host = Host(me), sent: MutableList<String> = mutableListOf()) {
        val ctx = risiCardContext(host, rows, { "Kumu" }, beforeExpiry, onRef = {}, sendChip = { sent += it }, risiChat = true)
        rule.setContent { RisiMeTheme { Column { rows.forEach { m -> RisiCardRow(m, RisiMessages.meta(m)!!, ctx) } } } }
    }

    @Test fun anOpenCardHasItsButtons() {
        show(listOf(row("envelope_risi_confirm_schedule_message.json")))
        rule.onNodeWithTag("risi_confirm_add").assertIsDisplayed()
        rule.onNodeWithTag("risi_confirm_cancel").assertIsDisplayed()
    }

    @Test fun aSupersededCardIsGreyedWithItsStateAndNoButtons() {
        show(listOf(row("envelope_risi_confirm_schedule_message.json"), row("envelope_risi_confirm_update_superseded.json")))
        rule.onNodeWithTag("risi_confirm_state").assertIsDisplayed()
        rule.onNodeWithText("Replaced by your newer request").assertIsDisplayed()
        rule.onNodeWithTag("risi_confirm_add").assertDoesNotExist()
        rule.onNodeWithTag("risi_confirm_cancel").assertDoesNotExist()
        // The confirm_update itself is not a bubble when its card is here.
        rule.onNodeWithTag("risi_card_confirm_update").assertDoesNotExist()
        rule.onNodeWithText("Replaced by your newer request.").assertDoesNotExist()
    }

    @Test fun aConfirmUpdateWithoutItsCardIsASmallLine() {
        show(listOf(row("envelope_risi_confirm_update_superseded.json")))
        rule.onNodeWithText("Replaced by your newer request.").assertIsDisplayed()
    }

    @Test fun theErrorCardShowsAskMeAgainAndATapReasksTheOriginalText() {
        val sent = mutableListOf<String>()
        show(listOf(row("envelope_risi_error_ask_again.json")), sent = sent)
        rule.onNodeWithTag("risi_error_text").assertIsDisplayed()
        rule.onNodeWithText("Ask me again").assertIsDisplayed().performClick()
        assertEquals(listOf("Check my calendar for Monday, October 12th"), sent)
    }

    @Test fun theSupersededErrorShowsItsBody() {
        show(listOf(row("envelope_risi_error_superseded.json")))
        rule.onNodeWithText("This was replaced by your newer request.").assertIsDisplayed()
    }

    @Test fun theClarifyCardSendsTheSameChipText() {
        val sent = mutableListOf<String>()
        show(listOf(row("envelope_risi_answer_name_clarify.json")), sent = sent)
        rule.onNodeWithTag("risi_clarify_card").assertIsDisplayed()
        rule.onNodeWithText("Did you mean Shirazi?").assertIsDisplayed()
        rule.onNodeWithTag("risi_clarify_option_0").assertIsDisplayed().performClick()
        rule.onNodeWithTag("risi_clarify_keep").assertIsDisplayed().performClick()
        assertEquals(listOf("Use Shirazi", "Keep Shutazi"), sent)
        // The two chips are the card's buttons: not shown twice.
        rule.onNodeWithTag("risi_next_0").assertDoesNotExist()
    }

    @Test fun aClosedClarifyCardHasNoButtons() {
        val wid = "5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e"
        show(listOf(
            row("envelope_risi_answer_name_clarify.json"),
            row("envelope_risi_confirm_update_superseded.json") { it.replace("2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e", wid) },
        ))
        rule.onNodeWithTag("risi_clarify_option_0").assertDoesNotExist()
        rule.onNodeWithTag("risi_clarify_keep").assertDoesNotExist()
        rule.onNodeWithTag("risi_clarify_state").assertIsDisplayed()
    }
}
