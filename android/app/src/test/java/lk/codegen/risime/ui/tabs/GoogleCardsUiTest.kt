package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v1.31 §31.5/§31.8 the Risi chat's Google pieces: the "Checked:" names (Google phone only) and the google_reconnect card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GoogleCardsUiTest {
    @get:Rule val rule = createComposeRule()

    private val me = "ff03ab6f-6457-46b1-a53b-9efd937920df"
    private val risiUser = "9e1f0000-0000-4000-8000-000000000001"
    private val conv = "grp:9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"

    private fun example(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private class Host(override val me: String, override val gcalNames: Map<String, String> = emptyMap()) : RisiHost {
        val calls = mutableListOf<String>()
        override fun ask(text: String) {}
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) {}
        override fun feedback(callRef: String, rating: String, reason: String?) {}
        override fun openSkills(skillId: String?) { calls += "skills:$skillId" }
    }

    private fun row(file: String): Pair<MessageEntity, RisiMeta> {
        val env = ProtocolJson.parseToJsonElement(example(file)) as JsonObject
        val m = MessageEntity(
            clientMsgId = file, messageId = "c1a2b3f1-a4f0-11f1-8000-0242ac120002", conversationId = conv, from = risiUser, to = conv,
            body = (env["body"] as JsonPrimitive).content, serverTs = "2026-10-10T09:15:33.000Z", localTs = 1L,
            status = MessageStatus.DELIVERED.name, outgoing = false, systemJson = RisiMessages.encode(env["risi"] as JsonObject),
        )
        return m to RisiMessages.meta(m)!!
    }

    private fun show(host: Host, file: String) {
        val (m, r) = row(file)
        val ctx = risiCardContext(host, listOf(m), { "You" }, java.time.Instant.parse("2026-10-10T09:16:00Z").toEpochMilli(), onRef = {})
        rule.setContent { RisiMeTheme { Column { RisiCardRow(m, r, ctx) } } }
    }

    @Test fun theGooglePhoneShowsTheLocalNamesAndEveryoneElseTheCounts() {
        show(Host(me, mapOf("g4k2m7qa" to "Work", "g9t3b8rc" to "Personal")), "envelope_risi_answer_calendar_sources_google.json")
        rule.onNodeWithText("Checked: Risi Calendar · Google Calendar (Work, Personal). Not checked: Phone calendar (not connected).", substring = true).assertIsDisplayed()
    }

    @Test fun anotherDeviceShowsTheServersCount() {
        show(Host(me), "envelope_risi_answer_calendar_sources_google.json")
        rule.onNodeWithText("Google Calendar (2 calendars)", substring = true).assertIsDisplayed()
    }

    @Test fun theReconnectCardHasAReconnectButtonThatOpensTheCalendarSkill() {
        val h = Host(me)
        show(h, "envelope_risi_google_reconnect.json")
        rule.onNodeWithText("Google Calendar needs reconnecting.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("risi_google_reconnect_button").performClick()
        assertEquals(listOf("skills:calendar"), h.calls)
        assertTrue(rule.onAllNodes(hasTestTag("risi_google_reconnect")).fetchSemanticsNodes().isNotEmpty())
    }
}
