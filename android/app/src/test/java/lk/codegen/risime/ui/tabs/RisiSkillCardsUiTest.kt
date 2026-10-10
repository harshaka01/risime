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
import lk.codegen.risime.data.tabs.RisiControl
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.data.tabs.RisiProgressStore
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.RisiProgress
import lk.codegen.risime.net.RisiStep
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §25.4/§26.5 A10: the confirm, reminder_set, draft, skill_done and skill_needed cards, answer v2 and the progress bubble. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiSkillCardsUiTest {
    @get:Rule val rule = createComposeRule()

    private val asker = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val other = "3b4c5d6e-7f8a-4b9c-8d0e-1f2a3b4c5d6e"
    private val risiUser = "9e1f0000-0000-4000-8000-000000000001"
    private val conv = "grp:9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"
    private val kumuDm = "dm:3b4c5d6e-7f8a-4b9c-8d0e-1f2a3b4c5d6e_7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"

    /** Before every example's `expires_at`. */
    private val now = java.time.Instant.parse("2026-10-09T15:11:00Z").toEpochMilli()

    private fun example(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private class Host(override val me: String, private val convs: Map<String, String> = emptyMap()) : RisiHost {
        val calls = mutableListOf<String>()
        override fun ask(text: String) { calls += "ask:$text" }
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) { calls += "act:$target:$action" }
        override fun feedback(callRef: String, rating: String, reason: String?) {}
        override fun undo(skillId: String, entryId: String, token: String) { calls += "undo:$skillId:$entryId:$token" }
        override fun openSkills(skillId: String?) { calls += "skills:$skillId" }
        override fun useDraft(conversationId: String, text: String) { calls += "use:$conversationId:$text" }
        override fun hasConversation(conversationId: String) = convs.containsKey(conversationId.lowercase())
        override fun conversationName(conversationId: String) = convs[conversationId.lowercase()]
    }

    private fun row(file: String, edit: (JsonObject) -> JsonObject = { it }): Pair<MessageEntity, RisiMeta> {
        val env = ProtocolJson.parseToJsonElement(example(file)) as JsonObject
        val risi = edit(env["risi"] as JsonObject)
        val m = MessageEntity(
            clientMsgId = file, messageId = "c1a2b3f1-a4f0-11f1-8000-0242ac120002", conversationId = conv, from = risiUser, to = conv,
            body = (env["body"] as JsonPrimitive).content, serverTs = "2026-10-09T15:10:00.000Z", localTs = 1L,
            status = MessageStatus.DELIVERED.name, outgoing = false, systemJson = RisiMessages.encode(risi),
        )
        return m to RisiMessages.meta(m)!!
    }

    private var prefilled: String? = null

    private fun show(host: Host, rows: List<Pair<MessageEntity, RisiMeta>>, extra: List<MessageEntity> = emptyList(), nowMs: Long = now) {
        val messages = extra + rows.map { it.first }
        val ctx = risiCardContext(host, messages, { if (it.equals(asker, true)) "You" else "Kumu" }, nowMs, onRef = {}, prefill = { prefilled = it })
        rule.setContent { RisiMeTheme { Column { rows.forEach { (m, r) -> RisiCardRow(m, r, ctx) } } } }
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun alarmConfirmShowsTheExactArgsAndAddCancelOnlyToTheAsker() {
        val h = Host(asker)
        show(h, listOf(row("envelope_risi_confirm_set_alarm.json")))
        rule.onNodeWithTag("risi_confirm_args").assertIsDisplayed()
        rule.onNodeWithText("05:30").assertIsDisplayed()
        rule.onNodeWithText("“Wake up”").assertIsDisplayed()
        rule.onNodeWithTag("risi_confirm_add").performClick()
        rule.onNodeWithTag("risi_confirm_cancel").performClick()
        assertEquals(listOf("act:1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d:confirm_write", "act:1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d:cancel_write"), h.calls)
    }

    @Test fun someoneNotInForSeesNoButtons() {
        show(Host(other), listOf(row("envelope_risi_confirm_set_alarm.json")))
        rule.onNodeWithTag("risi_confirm_summary").assertIsDisplayed()
        assertTrue(!exists("risi_confirm_add") && !exists("risi_confirm_cancel"))
    }

    @Test fun confirmExpiresAfterItsExpiry() {
        show(Host(asker), listOf(row("envelope_risi_confirm_set_alarm.json")), nowMs = java.time.Instant.parse("2026-10-10T15:10:00Z").toEpochMilli())
        rule.onNodeWithText("Expired").assertIsDisplayed()
        assertTrue(!exists("risi_confirm_add"))
    }

    @Test fun scheduleConfirmShowsRecipientTextTimeAndRepeat() {
        show(Host(asker, mapOf(kumuDm to "Kumu")), listOf(row("envelope_risi_confirm_schedule_message.json")))
        rule.onNodeWithText("Kumu").assertIsDisplayed()
        rule.onNodeWithText("“Good morning”").assertIsDisplayed()
        rule.onNodeWithText("Every day").assertIsDisplayed()
        rule.onNodeWithTag("risi_confirm_add").assertIsDisplayed()
    }

    @Test fun aConfirmedCardSaysSoAndHasNoButtons() {
        val (m, r) = row("envelope_risi_confirm_set_alarm.json")
        val ctl = MessageEntity(
            clientMsgId = "ctl", messageId = "x", conversationId = conv, from = asker, to = conv, body = "", serverTs = null, localTs = 2L,
            status = "READ", outgoing = true, kind = MessageEntity.KIND_RISI_CTL,
            systemJson = String(RisiControl.encode(RisiControl.action(r.writeId!!, "confirm_write"))),
        )
        show(Host(asker), listOf(m to r), extra = listOf(ctl))
        rule.onNodeWithText("Confirmed").assertIsDisplayed()
        assertTrue(!exists("risi_confirm_add"))
    }

    @Test fun anUnknownConfirmToolShowsOnlyItsSummary() {
        show(Host(asker), listOf(row("envelope_risi_confirm_set_alarm.json") { JsonObject(it + ("tool" to JsonPrimitive("teleport"))) }))
        rule.onNodeWithTag("risi_confirm_summary").assertIsDisplayed()
        assertTrue(!exists("risi_confirm_add"))
    }

    @Test fun manualSkillDoneShowsTheHintAndOpenClockButNoUndo() {
        show(Host(asker), listOf(row("envelope_risi_skill_done.json")))
        rule.onNodeWithText("Open Clock to remove it").assertIsDisplayed()
        rule.onNodeWithTag("risi_open_clock").assertIsDisplayed()
        assertTrue(!exists("risi_undo"))
    }

    @Test fun clientSkillDoneOffersUndoWithItsToken() {
        val h = Host(asker)
        show(h, listOf(row("envelope_risi_skill_done.json") {
            JsonObject(
                it + mapOf(
                    "skill_id" to JsonPrimitive("scheduled_messages"),
                    "undo" to ProtocolJson.parseToJsonElement("""{"kind":"client","state":"available","until":null,"hint":null}"""),
                    "undo_token" to JsonPrimitive("u1.tok"),
                ),
            )
        }))
        rule.onNodeWithTag("risi_undo").performClick()
        assertEquals(listOf("undo:scheduled_messages:7c6b5a49-3827-4615-9403-f2a1b0c9d8e7:u1.tok"), h.calls)
    }

    @Test fun skillNeededOpensRisiSkillsAtThatSkill() {
        val h = Host(asker)
        show(h, listOf(row("envelope_risi_skill_needed.json")))
        rule.onNodeWithText("I no longer have access to your alarms. Turn it on in Settings → Risi skills.").assertIsDisplayed()
        rule.onNodeWithTag("risi_open_skills").performClick()
        assertEquals(listOf("skills:alarm"), h.calls)
    }

    @Test fun draftUseOnlyWhenTheTargetIsOnThePhoneAndNeverSends() {
        val (m, r) = row("envelope_risi_draft.json")
        show(Host(asker), listOf(m to r))
        assertTrue(!exists("risi_draft_use"))
    }

    @Test fun draftUseFillsTheTarget() {
        val (m, r) = row("envelope_risi_draft.json")
        val h = Host(asker, mapOf(r.targetConversationId!!.lowercase() to "Team"))
        show(h, listOf(m to r))
        rule.onNodeWithTag("risi_draft_use").performClick()
        assertEquals(listOf("use:${r.targetConversationId}:${r.text}"), h.calls)
    }

    @Test fun reminderSetShowsMeTooToOthers() {
        val h = Host(other)
        val (m, r) = row("envelope_risi_reminder_set.json")
        show(h, listOf(m to r), nowMs = 0L)
        rule.onNodeWithTag("risi_me_too").performClick()
        assertEquals(listOf("act:${r.reminderId}:me_too"), h.calls)
    }

    @Test fun answerV2WithoutASendPathShowsNoChips() {
        show(Host(asker), listOf(row("envelope_risi_answer_v2.json")))
        rule.onNodeWithTag("risi_steps").assertIsDisplayed()
        rule.onNodeWithText("✓ Checked your calendar").assertIsDisplayed()
        rule.onNodeWithTag("risi_next_0").assertDoesNotExist()
        assertEquals(null, prefilled)
    }

    // P0 2026-10-10: a chip runs its request (a risi_request ask), never fills the composer, never a plain message.
    @Test fun aChipTapSendsItsTextToRisiAtOnce() {
        val h = Host(asker)
        val (m, r) = row("envelope_risi_answer_v2.json") { o -> JsonObject(o + ("next_steps" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("Check if you are free on Monday"), JsonPrimitive("What is next?"))))) }
        val ctx = risiCardContext(h, listOf(m), { "You" }, now, onRef = {}, prefill = { prefilled = it }, sendChip = { RisiChips.send(h, it) })
        rule.setContent { RisiMeTheme { Column { RisiCardRow(m, r, ctx) } } }
        rule.onNodeWithText("Check if you are free on Monday").assertIsDisplayed()
        rule.onNodeWithTag("risi_next_1").assertDoesNotExist() // a question is never a chip
        rule.onNodeWithTag("risi_next_0").performClick()
        assertEquals(listOf("ask:Check if you are free on Monday"), h.calls)
        assertEquals(null, prefilled)
    }

    @Test fun chipsOnlyWhereTheyCanBeSentAndOnlyOnMyAnswer() {
        val (_, r) = row("envelope_risi_answer_v2.json") { o -> JsonObject(o + ("next_steps" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("Check if you are free on Monday"))))) }
        assertEquals(listOf("Check if you are free on Monday"), RisiChips.visible(r.copy(forUsers = emptyList()), asker, readOnly = false, canSend = true))
        assertEquals(emptyList<String>(), RisiChips.visible(r, asker, readOnly = true, canSend = true))
        assertEquals(emptyList<String>(), RisiChips.visible(r, asker, readOnly = false, canSend = false))
        assertEquals(emptyList<String>(), RisiChips.visible(r.copy(forUsers = listOf(other)), asker, readOnly = false, canSend = true))
        val h = Host(asker)
        RisiChips.send(h, "  ")
        assertEquals(emptyList<String>(), h.calls)
    }

    @Test fun progressBubbleShowsTheStepAndStillWorking() {
        val p = RisiProgress("r1", conv, RisiProgress.STEP, seq = 1, step = RisiStep("set_alarm", "running"))
        rule.setContent { RisiMeTheme { RisiProgressBubble(RisiProgressStore.Shown(p, 0L), RisiProgressStore.STILL_WORKING_MS) } }
        rule.onNodeWithText("Still working…").assertIsDisplayed()
    }

    @Test fun progressBubbleText() {
        val p = RisiProgress("r1", conv, RisiProgress.STEP, seq = 1, step = RisiStep("set_alarm", "running"))
        rule.setContent { RisiMeTheme { RisiProgressBubble(RisiProgressStore.Shown(p, 0L), 1_000L) } }
        rule.onNodeWithText("Setting an alarm…").assertIsDisplayed()
    }
}
