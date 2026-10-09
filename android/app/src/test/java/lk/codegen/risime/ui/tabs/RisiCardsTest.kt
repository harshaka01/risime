package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiCards
import lk.codegen.risime.data.tabs.RisiControl
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

/**
 * §24 A6: Risi's messages as Material 3 cards in the Official tab, rendered from the contract examples.
 * Action buttons only for the owner or a counterpart; a `risi` object that was not honoured is plain text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiCardsTest {
    @get:Rule val rule = createComposeRule()

    private val kamal = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e" // owner
    private val harsha = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3" // counterpart
    private val shenika = "11111111-2222-4333-8444-555555555555" // neither
    private val official = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val risiUser = "9e1f0000-0000-4000-8000-000000000001"

    private fun example(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private val names = mapOf(kamal to "Kamal", harsha to "Harsha", shenika to "Shenika", risiUser to "Risi")

    private class Host(override val me: String) : RisiHost {
        val calls = mutableListOf<String>()
        override fun ask(text: String) { calls += "ask:$text" }
        override fun summarise() { calls += "summarise" }
        override fun report() { calls += "report" }
        override fun act(target: String, action: String, editText: String?, editDue: String?) { calls += "act:$target:$action:$editText:$editDue" }
        override fun feedback(callRef: String, rating: String, reason: String?) { calls += "feedback:$callRef:$rating:$reason" }
    }

    /** A stored Risi row exactly as the pipeline keeps it (honoured `risi` object in `system_json`). */
    private fun row(file: String, id: String = file, serverTs: String = "2026-10-08T09:15:30.456Z", localTs: Long = 1L): Pair<MessageEntity, RisiMeta> {
        val env = ProtocolJson.parseToJsonElement(example(file)) as JsonObject
        val risi = env["risi"] as JsonObject
        val m = MessageEntity(
            clientMsgId = id, messageId = "c1a2b3f1-a4f0-11f1-8000-0242ac120002", conversationId = official, from = risiUser, to = official,
            body = (env["body"] as kotlinx.serialization.json.JsonPrimitive).content, serverTs = serverTs, localTs = localTs,
            status = MessageStatus.DELIVERED.name, outgoing = false, systemJson = RisiMessages.encode(risi),
        )
        return m to RisiMessages.meta(m)!!
    }

    private fun show(host: Host, rows: List<Pair<MessageEntity, RisiMeta>>, nowMs: Long = 1_760_000_000_000L, readOnly: Boolean = false, extra: List<MessageEntity> = emptyList()) {
        val messages = extra + rows.map { it.first }
        val ctx = risiCardContext(host, messages, { names[it.lowercase()] ?: "Someone" }, nowMs, onRef = { host.calls += "ref:$it" }, knownNames = names.values.toList(), readOnly = readOnly)
        rule.setContent { RisiMeTheme { Column { rows.forEach { (m, r) -> RisiCardRow(m, r, ctx) } } } }
    }

    // ---- every kind renders from its contract example ----

    @Test fun commitmentCardShowsTextOwnerDueAndTheThreeButtonsToTheOwner() {
        val h = Host(kamal)
        show(h, listOf(row("envelope_risi_commitment.json")))
        rule.onNodeWithTag("risi_card_commitment").assertIsDisplayed()
        rule.onNodeWithText("Send the revised quote").assertIsDisplayed()
        rule.onNodeWithTag("risi_owner").assertIsDisplayed()
        rule.onNodeWithText("Kamal").assertIsDisplayed()
        rule.onNodeWithTag("risi_due").assertIsDisplayed()
        rule.onNodeWithTag("risi_state").assertIsDisplayed()
        rule.onNodeWithTag("risi_confirm").performClick()
        rule.onNodeWithTag("risi_decline").performClick()
        assertEquals(listOf("act:5c0e1d2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f:confirm:null:null", "act:5c0e1d2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f:decline:null:null"), h.calls)
    }

    private fun assertButtons(user: String, expected: Boolean) {
        show(Host(user), listOf(row("envelope_risi_commitment.json")))
        for (tag in listOf("risi_confirm", "risi_decline", "risi_edit")) {
            assertEquals("$tag for $user", expected, rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty())
        }
        rule.onNodeWithTag("risi_state").assertIsDisplayed() // everyone sees the state
    }

    @Test fun theOwnerGetsTheButtons() = assertButtons(kamal, true)

    @Test fun aCounterpartGetsTheButtons() = assertButtons(harsha, true)

    @Test fun someoneElseSeesOnlyTheState() = assertButtons(shenika, false)

    @Test fun aStateUpdateChangesTheOriginalCardInPlaceAndShowsASmallLine() {
        val h = Host(kamal)
        val card = row("envelope_risi_commitment.json", id = "c1")
        val upd = row("envelope_risi_commitment_update.json", id = "c2")
        show(h, listOf(card, upd))
        rule.onNodeWithTag("risi_state").assertIsDisplayed()
        rule.onNodeWithText("Confirmed by Kamal").assertIsDisplayed()
        rule.onNodeWithText("Kamal confirmed: send the revised quote by Friday 5 pm.").assertIsDisplayed() // the small line
        // Confirmed: Confirm/Decline/Edit are gone; the owner can mark it done.
        assertTrue(rule.onAllNodes(hasTestTag("risi_confirm")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("risi_done").performClick()
        assertEquals(listOf("act:5c0e1d2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f:done:null:null"), h.calls)
    }

    @Test fun aProposalGreysOutAfter48HoursAndOffersNoButtons() {
        val h = Host(kamal)
        val serverTs = "2026-10-08T09:15:30.456Z"
        val ts = java.time.Instant.parse(serverTs).toEpochMilli()
        show(h, listOf(row("envelope_risi_commitment.json", serverTs = serverTs)), nowMs = ts + RisiCards.EXPIRY_MS + 1)
        rule.onNodeWithText("Expired").assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasTestTag("risi_confirm")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun editOpensADialogWithTextAndDueAndSendsAnEditAction() {
        val h = Host(kamal)
        show(h, listOf(row("envelope_risi_commitment.json")))
        rule.onNodeWithTag("risi_edit").performClick()
        rule.onNodeWithTag("risi_edit_date").assertIsDisplayed()
        rule.onNodeWithTag("risi_edit_time").assertIsDisplayed()
        rule.onNodeWithTag("risi_edit_text").performTextClearance()
        rule.onNodeWithTag("risi_edit_text").performTextInput("Send the revised quote today")
        rule.onNodeWithTag("risi_edit_save").performClick()
        assertEquals(1, h.calls.size)
        assertTrue(h.calls[0], h.calls[0].startsWith("act:5c0e1d2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f:edit:Send the revised quote today:2026-10-09T"))
    }

    @Test fun reminderEscalationDigestAnswerSummaryReportOfferAndErrorAllRender() {
        val h = Host(harsha)
        show(
            h,
            listOf(
                row("envelope_risi_commitment.json", id = "a"),
                row("envelope_risi_reminder.json", id = "b"),
                row("envelope_risi_escalation.json", id = "c"),
                row("envelope_risi_digest.json", id = "d"),
                row("envelope_risi_answer.json", id = "e"),
                row("envelope_risi_summary.json", id = "f"),
                row("envelope_risi_report.json", id = "g"),
                row("envelope_risi_offer.json", id = "h"),
                row("envelope_risi_error.json", id = "i"),
            ),
        )
        for (k in listOf("commitment", "reminder", "escalation", "digest", "answer", "summary", "report", "offer", "error")) {
            rule.onNodeWithTag("risi_card_$k").assertExists()
        }
        // digest: the open item with its owner
        rule.onNodeWithTag("risi_digest_item").assertExists()
        // escalation: "1 day overdue"
        rule.onNodeWithText("Overdue by 1 day").assertExists()
        // summary: the summary, decisions, action items with the owner as a chip, open questions
        rule.onNodeWithText("The quote is being revised; the site visit is Thursday 10 am.").assertExists()
        rule.onNodeWithTag("risi_decisions").assertExists()
        rule.onNodeWithTag("risi_action_owner").assertExists()
        rule.onNodeWithTag("risi_questions").assertExists()
        // report: title and sections
        rule.onNodeWithText("Today in Site team").assertExists()
        rule.onNodeWithText("Drawings shared; permit filed.").assertExists()
        // offer: Yes / Not now for its addressee (Harsha)
        rule.onNodeWithTag("risi_not_now").assertExists()
        rule.onNodeWithTag("risi_yes").assertExists()
        // error: the out_of_window text
        rule.onNodeWithTag("risi_error_text").assertExists()
        rule.onNodeWithText("I can only summarise the last 24 hours.").assertExists()
    }

    @Test fun anOfferAnswersYesOrNotNowForItsAddressee() {
        val h = Host(harsha)
        show(h, listOf(row("envelope_risi_offer.json")))
        rule.onNodeWithTag("risi_not_now").performClick()
        rule.onNodeWithTag("risi_yes").performClick()
        assertEquals(listOf("act:8f3b4a5c-6d7e-4f8a-9b0c-1d2e3f4a5b6c:offer_not_now:null:null", "act:8f3b4a5c-6d7e-4f8a-9b0c-1d2e3f4a5b6c:offer_yes:null:null"), h.calls)
    }

    @Test fun anOfferNeverShowsButtonsToSomeoneItDidNotAddress() {
        show(Host(shenika), listOf(row("envelope_risi_offer.json")))
        rule.onNodeWithTag("risi_card_offer").assertExists()
        assertTrue(rule.onAllNodes(hasTestTag("risi_yes")).fetchSemanticsNodes().isEmpty())
    }

    private val refId = "c1a2b3f2-a4f0-11f1-8000-0242ac120002"

    private fun said(from: String, body: String, id: String = refId) = MessageEntity(
        clientMsgId = "m-$id", messageId = id, conversationId = official, from = from, to = official, body = body,
        serverTs = "2026-10-08T09:10:00.000Z", localTs = 0L, status = MessageStatus.DELIVERED.name, outgoing = false,
    )

    @Test fun answerSourcesAreQuotesOfTheMessageThatScrollToIt() {
        val h = Host(harsha)
        show(h, listOf(row("envelope_risi_answer.json")), extra = listOf(said(kamal, "The site visit moved to Thursday 10 am because the crane is late")))
        rule.onNodeWithText("Kamal: “The site visit moved to Thursday 10 am…”").assertIsDisplayed()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("Message 1")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("risi_ref_0").performClick()
        assertEquals(listOf("ref:$refId"), h.calls)
    }

    @Test fun aSourceNotOnThisPhoneShowsNothing() {
        show(Host(harsha), listOf(row("envelope_risi_answer.json")))
        rule.onNodeWithTag("risi_card_answer").assertExists()
        assertTrue(rule.onAllNodes(hasTestTag("risi_ref_0")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("Message 1")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun sourceQuotesSkipDeletedAndOtherRowsAndShortTextIsWhole() {
        val (_, r) = row("envelope_risi_answer.json")
        val nameOf = { id: String -> names[id.lowercase()] ?: "Someone" }
        assertEquals(listOf(lk.codegen.risime.data.tabs.SourceQuote(refId, "Kamal", "Thursday works")), RisiCards.sourceQuotes(r, listOf(said(kamal, "Thursday works")), nameOf))
        assertEquals("You", RisiCards.sourceQuotes(r, listOf(said(harsha, "ok").copy(outgoing = true)), nameOf).single().sender)
        assertTrue(RisiCards.sourceQuotes(r, listOf(said(kamal, "x").copy(kind = MessageEntity.KIND_DELETED)), nameOf).isEmpty())
        assertTrue(RisiCards.sourceQuotes(r, listOf(said(kamal, "x", id = "other")), nameOf).isEmpty())
    }

    @Test fun followUpChipDismissesWithTheCross() {
        var dismissed = 0
        rule.setContent { RisiMeTheme { RisiFollowUpChip { dismissed++ } } }
        rule.onNodeWithText(lk.codegen.risime.data.tabs.RisiFollowUp.CHIP_LABEL).assertIsDisplayed()
        rule.onNodeWithTag("risi_follow_up").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun officialDmHeaderAndComposerTexts() {
        assertEquals("Kumu · Risi", officialDmTitle("Kumu"))
        assertEquals("Message", OFFICIAL_COMPOSER_HINT)
        assertTrue(!OFFICIAL_DM_SUBTITLE.contains("member"))
    }

    @Test fun readOnlyOfficialShowsNoButtons() {
        show(Host(kamal), listOf(row("envelope_risi_commitment.json")), readOnly = true)
        rule.onNodeWithTag("risi_state").assertExists()
        assertTrue(rule.onAllNodes(hasTestTag("risi_confirm")).fetchSemanticsNodes().isEmpty())
    }

    // ---- feedback ----

    @Test fun thumbsUpSendsFeedbackAtOnceAndThumbsDownAsksForAnOptionalReason() {
        val h = Host(harsha)
        show(h, listOf(row("envelope_risi_answer.json")))
        rule.onNodeWithTag("risi_up").performClick()
        rule.onNodeWithTag("risi_down").performClick()
        rule.onNodeWithTag("risi_reason").performTextInput("Wrong due date")
        rule.onNodeWithTag("risi_reason_send").performClick()
        assertEquals(
            listOf("feedback:7e2a3f4b-5c6d-4e7f-8a9b-0c1d2e3f4a5b:up:null", "feedback:7e2a3f4b-5c6d-4e7f-8a9b-0c1d2e3f4a5b:down:Wrong due date"),
            h.calls,
        )
    }

    @Test fun noFeedbackRowWithoutACallRef() {
        show(Host(harsha), listOf(row("envelope_risi_digest.json"))) // call_ref null
        assertTrue(rule.onAllNodes(hasTestTag("risi_up")).fetchSemanticsNodes().isEmpty())
    }

    // ---- pure rules ----

    @Test fun canActIsOwnerOrCounterpartOnly() {
        val (_, r) = row("envelope_risi_commitment.json")
        assertTrue(RisiCards.canAct(kamal, r))
        assertTrue(RisiCards.canAct(harsha.uppercase(), r))
        assertEquals(false, RisiCards.canAct(shenika, r))
        assertEquals(false, RisiCards.canAct(null, r))
    }

    @Test fun myPendingActionHidesTheButtonsForTheTargetMeanwhile() {
        val (m, _) = row("envelope_risi_commitment.json")
        val ctl = MessageEntity(
            clientMsgId = "x", messageId = null, conversationId = official, from = kamal, to = official, body = "Confirmed", serverTs = null, localTs = 1_000L,
            status = MessageStatus.PENDING.name, outgoing = true, kind = MessageEntity.KIND_RISI_CTL,
            systemJson = ProtocolJson.encodeToString(JsonObject.serializer(), RisiControl.action("5c0e1d2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f", "confirm")),
        )
        assertEquals(setOf("5c0e1d2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f"), RisiCards.awaiting(listOf(m, ctl), kamal, 2_000L))
        assertEquals(emptySet<String>(), RisiCards.awaiting(listOf(m, ctl), kamal, 1_000L + RisiCards.ACTION_PENDING_MS + 1))
        assertEquals(emptySet<String>(), RisiCards.awaiting(listOf(m, ctl), harsha, 2_000L))
    }

    @Test fun splitOwnerPicksTheMemberNamePrefix() {
        assertEquals("Kamal" to "send the quote", splitOwner("Kamal: send the quote", listOf("Kamal", "Harsha")))
        assertEquals(null to "Who: knows", splitOwner("Who: knows", listOf("Kamal")))
        assertEquals(null to "plain", splitOwner("plain", listOf("Kamal")))
    }
}
