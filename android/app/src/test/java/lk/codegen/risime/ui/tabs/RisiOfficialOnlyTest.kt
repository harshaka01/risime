package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.ui.group.GroupMessageList
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §24.9/§24.11: a `risi` object is honoured only from an agent leaf in an Official conversation; a
 * spoofed one (a human sender, or Private) is plain text. Private has no Risi affordance at all, and the
 * @Risi chip sends a structured request, never inferred from free text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiOfficialOnlyTest {
    @get:Rule val rule = createComposeRule()

    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val kamal = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val risiUser = "9e1f0000-0000-4000-8000-000000000001"
    private val official = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val priv = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"

    private fun example(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private val envelope = ProtocolJson.parseToJsonElement(example("envelope_risi_commitment.json")) as JsonObject
    private val risiObj = envelope["risi"] as JsonObject
    private val bodyText = (envelope["body"] as JsonPrimitive).content

    private class Host(override val me: String) : RisiHost {
        val calls = mutableListOf<String>()
        override fun ask(text: String) { calls += "ask:$text" }
        override fun summarise() { calls += "summarise" }
        override fun report() { calls += "report" }
        override fun act(target: String, action: String, editText: String?, editDue: String?) { calls += "act:$action" }
        override fun feedback(callRef: String, rating: String, reason: String?) { calls += "feedback" }
    }

    // ---- honoured only from an agent leaf in Official ----

    @Test fun aRisiObjectIsHonouredOnlyFromAnAgentLeafInOfficial() {
        val officialMeta = GroupMeta(name = "", tab = "official", chatId = priv, agents = listOf(risiUser))
        val privateMeta = GroupMeta(name = "Team", tab = "private", chatId = priv)
        assertEquals(risiObj, RisiMessages.honoured(risiObj, officialMeta, risiUser, setOf(risiUser)))
        // spoofed by a human member of Official
        assertNull(RisiMessages.honoured(risiObj, officialMeta, kamal, setOf(risiUser)))
        // the agent's id in a Private group (or a DM): plain text
        assertNull(RisiMessages.honoured(risiObj, privateMeta, risiUser, setOf(risiUser)))
        assertNull(RisiMessages.honoured(risiObj, null, risiUser, setOf(risiUser)))
        // an agent that isn't attested as one
        assertNull(RisiMessages.honoured(risiObj, officialMeta, risiUser, emptySet()))
    }

    private fun row(from: String, risi: Boolean) = MessageEntity(
        clientMsgId = "m-$from-$risi", messageId = "c1a2b3f1-a4f0-11f1-8000-0242ac120002", conversationId = official, from = from, to = official,
        body = bodyText, serverTs = "2026-10-08T09:15:30.456Z", localTs = 1L, status = MessageStatus.DELIVERED.name, outgoing = false,
        systemJson = if (risi) RisiMessages.encode(risiObj) else null,
    )

    private fun list(messages: List<MessageEntity>, ctx: RisiCardContext?) {
        rule.setContent {
            RisiMeTheme {
                GroupMessageList(
                    messages = messages, meId = me, nameOf = { if (it == me) "You" else "Someone" }, memberName = { "Someone" }, readOnly = false,
                    reactions = emptyMap(), onReact = { _, _, _ -> }, onOpenReactions = {}, onRetry = {}, onDelete = {}, onInfo = {}, risi = ctx,
                )
            }
        }
    }

    private fun ctx(host: Host) = risiCardContext(host, emptyList(), { "Someone" }, 1_760_000_000_000L, onRef = {})

    @Test fun anHonouredRowIsACardInOfficial() {
        list(listOf(row(risiUser, risi = true)), ctx(Host(me)))
        rule.onNodeWithTag("risi_card_commitment").assertExists()
    }

    @Test fun aSpoofedRowWithoutAnHonouredObjectIsAPlainBubble() {
        // The pipeline stored no `risi` for it (a human sender): the text shows as a bubble, never a card.
        list(listOf(row(kamal, risi = false)), ctx(Host(me)))
        assertTrue(rule.onAllNodes(hasTestTag("risi_card_commitment")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("chat_list").assertExists()
        rule.onNodeWithText(bodyText, useUnmergedTree = true).assertExists()
    }

    @Test fun privateDrawsNoCardEvenForARowThatCarriesTheObject() {
        // Private passes no Risi context: a row can't turn into a card there.
        list(listOf(row(risiUser, risi = true)), null)
        assertTrue(rule.onAllNodes(hasTestTag("risi_card_commitment")).fetchSemanticsNodes().isEmpty())
    }

    // ---- the chip ----

    @Test fun theChipSendsAStructuredAskAndNothingElseDoes() {
        val h = Host(me)
        val plain = mutableListOf<String>()
        assertTrue(sendFromComposer(h, true, "what is due Friday?", plain::add))
        assertEquals(listOf("ask:what is due Friday?"), h.calls)
        assertTrue(plain.isEmpty())
        // Free text is never parsed for intent: "@Risi ..." typed by hand is an ordinary message.
        assertEquals(false, sendFromComposer(h, false, "@Risi summarise please", plain::add))
        assertEquals(listOf("@Risi summarise please"), plain)
        assertEquals(1, h.calls.size)
    }

    @Test fun privateHasNoHostSoEvenAnActiveChipSendsAnOrdinaryMessage() {
        val plain = mutableListOf<String>()
        assertEquals(false, sendFromComposer(null, true, "ask", plain::add))
        assertEquals(listOf("ask"), plain)
    }

    @Test fun theChipRowTogglesAndTheMenuHasSummariseAndReport() {
        var active by mutableStateOf(false)
        val h = Host(me)
        rule.setContent {
            RisiMeTheme {
                RisiChipRow(active) { active = it }
                DropdownMenu(expanded = true, onDismissRequest = {}) { RisiMenuItems(h, enabled = true, close = {}) }
            }
        }
        rule.onNodeWithTag("risi_chip").performClick()
        assertTrue(active)
        rule.onNodeWithTag("risi_chip").performClick()
        assertEquals(false, active)
        rule.onNodeWithTag("risi_menu_summarise").performClick()
        rule.onNodeWithTag("risi_menu_report").performClick()
        assertEquals(listOf("summarise", "report"), h.calls)
    }
}
