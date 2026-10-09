package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiControl
import lk.codegen.risime.data.tabs.RisiLedger
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.data.tabs.RisiUiBus
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiCommitmentsReply
import lk.codegen.risime.net.RisiItemStates
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** §27.3–§27.6 (A14–A16): the per-person summary, item actions, item_update, reminders, the short card, My promises. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiLedgerTest {
    @get:Rule val rule = createComposeRule()

    private val harsha = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3" // R: owns "Send the revised quote"
    private val shenika = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e" // owns "Book the site visit transport"
    private val myItem = "c3d4e5f6-a7b8-4c9d-8e0f-2a3b4c5d6e7f"
    private val herItem = "d4e5f6a7-b8c9-4d0e-9f1a-3b4c5d6e7f8a"
    private val risiChat = "grp:aaaa0000-0000-4000-8000-000000000001"
    private val official = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val colombo = ZoneId.of("Asia/Colombo")
    private val names = mapOf(harsha to "Harsha", shenika to "Shenika")
    private val beforeExpiry = Instant.parse("2026-10-09T05:00:00Z").toEpochMilli()

    private fun example(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private fun row(file: String, id: String = file, conv: String = risiChat, ts: String = "2026-10-09T04:14:40.000Z"): MessageEntity {
        val env = ProtocolJson.parseToJsonElement(example(file)) as JsonObject
        return MessageEntity(
            clientMsgId = id, messageId = "m-$id", conversationId = conv, from = "9e1f0000-0000-4000-8000-000000000001", to = conv,
            body = (env["body"] as JsonPrimitive).content, serverTs = ts, localTs = 1L,
            status = MessageStatus.DELIVERED.name, outgoing = false, systemJson = RisiMessages.encode(env["risi"] as JsonObject),
        )
    }

    private fun meta(m: MessageEntity): RisiMeta = RisiMessages.meta(m)!!

    private class Host(override val me: String, private val risiChat: Boolean = true) : RisiHost {
        val calls = mutableListOf<String>()
        override fun ask(text: String) {}
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) { calls += "act:$target:$action" }
        override fun feedback(callRef: String, rating: String, reason: String?) {}
        override fun actItem(itemId: String, action: String, text: String?, due: String?, allDay: Boolean) { calls += "item:$itemId:$action" }
        override fun openChat(conversationId: String, atIso: String?) { calls += "open:$conversationId:$atIso" }
        override fun openRisiChat(summaryId: String) { calls += "risi:$summaryId" }
        override fun risiChatAvailable(): Boolean = risiChat
    }

    private fun show(host: Host, rows: List<MessageEntity>, nowMs: Long = beforeExpiry) {
        val ctx = risiCardContext(host, rows, { names[it.lowercase()] ?: "Someone" }, nowMs, onRef = {})
        rule.setContent { RisiMeTheme { Column { rows.forEach { m -> RisiCardRow(m, meta(m), ctx) } } } }
    }

    // ---- pure logic ----

    @Test fun headers() {
        val chat = meta(row("envelope_risi_discussion_summary_chat.json"))
        assertEquals("Summary of your discussion with Shenika (09:12–09:31, chat)", RisiLedger.summaryHeader(chat, harsha, { names[it] ?: "?" }, colombo))
        val call = meta(row("envelope_risi_discussion_summary_call.json"))
        assertEquals("Summary of your discussion with Shenika (09:12, video call 32 min)", RisiLedger.summaryHeader(call, harsha, { names[it] ?: "?" }, colombo))
        assertEquals("Shenika, Kamal and Ruwan", RisiLedger.joinNames(listOf("Shenika", "Kamal", "Ruwan")))
        assertEquals("3 items · Details in your Risi chat", RisiLedger.cardCountLine(3))
        assertEquals("by Mon 12 Oct", RisiLedger.dueLabel("2026-10-12T18:29:59.999Z", true, "by Monday", colombo))
        assertEquals("due Fri 9 Oct, 17:00", RisiLedger.dueLabel("2026-10-09T11:30:00.000Z", false, null, colombo))
    }

    @Test fun itemUpdatesApplyToTheCardsItems() {
        val rows = listOf(row("envelope_risi_discussion_summary_call.json", "s"), row("envelope_risi_item_update.json", "u"))
        val items = RisiLedger.items(rows)
        assertEquals(RisiItemStates.PROPOSED, items[myItem]!!.state)
        assertEquals(RisiItemStates.CONFIRMED, items[herItem]!!.state)
        assertEquals(shenika, items[herItem]!!.by)
        assertEquals(shenika, items[herItem]!!.owner) // kept from the summary
        assertTrue(RisiLedger.updateHasCard(meta(rows[1]), rows))
        assertFalse(RisiLedger.updateHasCard(meta(rows[1]), rows.drop(1)))
    }

    @Test fun buttonsOnlyOnMyOwnProposedItemsUntilExpiry() {
        val items = RisiLedger.items(listOf(row("envelope_risi_discussion_summary_call.json")))
        assertEquals(listOf("item_confirm", "item_decline", "item_edit"), RisiLedger.itemButtons(harsha, items[myItem]!!, false, emptySet()))
        assertEquals(emptyList<String>(), RisiLedger.itemButtons(harsha, items[herItem]!!, false, emptySet()))
        assertEquals(emptyList<String>(), RisiLedger.itemButtons(harsha, items[myItem]!!, true, emptySet())) // expired: Not tracked
        assertEquals(emptyList<String>(), RisiLedger.itemButtons(harsha, items[myItem]!!, false, setOf(myItem))) // on its way
        // A confirmed item: done for its counterpart, done/edit for its owner.
        val confirmed = items[herItem]!!.copy(state = RisiItemStates.CONFIRMED)
        assertEquals(listOf("done"), RisiLedger.itemButtons(harsha, confirmed, false, emptySet()))
        assertEquals(listOf("done", "item_edit"), RisiLedger.itemButtons(shenika, confirmed, false, emptySet()))
        assertEquals("Not tracked", RisiLedger.stateLabel(RisiItemStates.PROPOSED, true))
        assertEquals("waiting", RisiLedger.stateLabel(RisiItemStates.PROPOSED, false))
    }

    @Test fun itemActionsEncodeLikeTheExamples() {
        fun same(o: JsonObject, file: String) = assertEquals(ProtocolJson.parseToJsonElement(example(file)), o)
        same(RisiControl.action(myItem, "item_confirm"), "envelope_risi_action_item_confirm.json")
        same(RisiControl.action(myItem, "item_decline"), "envelope_risi_action_item_decline.json")
        same(RisiControl.action(myItem, "item_edit", "Send the revised quote with transport", "2026-10-10T06:30:00.000Z", editAllDay = false), "envelope_risi_action_item_edit.json")
        assertTrue(RisiControl.valid(RisiControl.action(myItem, "item_confirm")))
    }

    @Test fun focusTargets() {
        val rows = listOf(row("envelope_risi_discussion_summary_call.json", "s"))
        assertEquals("m-s", RisiLedger.focusTarget(rows, RisiUiBus.Focus(summaryId = "B2C3D4E5-F6A7-4B8C-9D0E-1F2A3B4C5D6E")))
        assertNull(RisiLedger.focusTarget(rows, RisiUiBus.Focus(summaryId = "other")))
        assertEquals("m-s", RisiLedger.focusTarget(rows, RisiUiBus.Focus(at = "2026-10-09T03:42:00.000Z")))
    }

    @Test fun promisesSplitByRole() {
        val c = ProtocolJson.decodeFromString(RisiCommitmentsReply.serializer(), example("risi_commitments_reply_v127.json")).commitments
        val (mine, owed) = RisiLedger.promisesSplit(c, harsha)
        assertEquals(listOf(myItem), mine.map { it.commitmentId })
        assertEquals(listOf(herItem), owed.map { it.commitmentId })
    }

    // ---- cards ----

    @Test fun summaryCardConfirmsOnlyMyItem() {
        val host = Host(harsha)
        show(host, listOf(row("envelope_risi_discussion_summary_chat.json")))
        rule.onNodeWithText("Summary of your discussion with Shenika", substring = true).assertExists()
        rule.onNodeWithText("You agreed:").assertExists()
        rule.onNodeWithText("Shenika agreed:").assertExists()
        assertEquals(2, rule.onAllNodesWithTag("risi_key_point").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithTag("risi_item_confirm").fetchSemanticsNodes().size)
        rule.onNodeWithTag("risi_item_confirm").performClick()
        rule.onNodeWithTag("risi_open_chat").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(listOf("item:$myItem:item_confirm", "open:$official:2026-10-09T03:42:10.000Z"), host.calls)
    }

    @Test fun theOtherOwnerSeesNoButtonsOnMyItemAndUpdatesShowNoBubble() {
        val host = Host(shenika)
        show(host, listOf(row("envelope_risi_discussion_summary_call.json", "s"), row("envelope_risi_item_update.json", "u")))
        // Shenika's own item is confirmed now: Done (and ✎), no ✓; Harsha's item is read-only "waiting".
        assertEquals(0, rule.onAllNodesWithTag("risi_item_confirm").fetchSemanticsNodes().size)
        rule.onNodeWithTag("risi_item_done").assertExists()
        rule.onNodeWithText("waiting", substring = true).assertExists()
        // The update itself is not a bubble.
        rule.onAllNodes(hasTestTag("risi_card_item_update")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        assertTrue(rule.onAllNodesWithText("Shenika confirmed 'Book the site visit transport' (by Monday).").fetchSemanticsNodes().isEmpty())
    }

    @Test fun anUpdateWithoutItsCardIsASmallLine() {
        show(Host(harsha), listOf(row("envelope_risi_item_update.json")))
        rule.onNodeWithText("Shenika confirmed 'Book the site visit transport' (by Monday).").assertExists()
    }

    @Test fun expiredSummaryGreysOutAsNotTracked() {
        show(Host(harsha), listOf(row("envelope_risi_discussion_summary_chat.json")), nowMs = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli())
        assertEquals(0, rule.onAllNodesWithTag("risi_item_confirm").fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithText("Not tracked", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun ownerReminderHasDoneAndNewDate() {
        val host = Host(harsha)
        show(host, listOf(row("envelope_risi_discussion_summary_call.json", "s"), row("envelope_risi_item_due.json", "d")))
        rule.onNodeWithText("Risi · Reminder").assertExists()
        rule.onNodeWithTag("risi_reminder_new_date").assertExists()
        rule.onNodeWithTag("risi_reminder_done").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.onNodeWithTag("risi_reminder_open_chat").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals("item:$myItem:done", host.calls[0])
        assertTrue(host.calls[1].startsWith("open:$official"))
    }

    @Test fun counterpartReminderOffersOnlyMarkDone() {
        show(Host(harsha), listOf(row("envelope_risi_item_due_counterpart.json")))
        rule.onNodeWithText("Risi · Due today").assertExists()
        rule.onNodeWithTag("risi_reminder_mark_done").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("risi_reminder_new_date").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("risi_reminder_open_chat").fetchSemanticsNodes().size) // its summary isn't on this phone
    }

    @Test fun overdueAndNudge() {
        show(Host(harsha), listOf(row("envelope_risi_item_overdue.json", "o"), row("envelope_risi_item_nudge.json", "n")))
        rule.onNodeWithText("Risi · Overdue").assertExists()
        rule.onNodeWithText("Risi · Not done yet").assertExists()
        rule.onNodeWithTag("risi_reminder_new_date").assertExists()
    }

    @Test fun personalDigestSplitsMineAndOwed() {
        show(Host(harsha), listOf(row("envelope_risi_digest_personal.json")))
        rule.onNodeWithText("Your open items").assertExists()
        rule.onNodeWithText("Owed to you").assertExists()
        rule.onNodeWithText("Shenika: Book the site visit transport").assertExists()
    }

    @Test fun shortCardInOfficialOpensTheRisiChat() {
        val host = Host(harsha)
        show(host, listOf(row("envelope_risi_discussion_card.json", conv = official)))
        rule.onNodeWithText("Video call about the revised quote and Thursday's site visit.").assertExists()
        rule.onNodeWithText("2 items · Details in your Risi chat").assertExists()
        rule.onNodeWithTag("risi_open_risi_chat").performClick()
        assertEquals(listOf("risi:b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e"), host.calls)
    }

    @Test fun shortCardForANonParticipantIsOnlyTheLine() {
        show(Host("11111111-2222-4333-8444-555555555555"), listOf(row("envelope_risi_discussion_card.json", conv = official)))
        assertEquals(0, rule.onAllNodesWithTag("risi_card_count").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("risi_open_risi_chat").fetchSemanticsNodes().size)
    }
}
