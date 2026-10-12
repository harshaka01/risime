package ai.risicloud.risime.ui.tabs

import ai.risicloud.risime.data.MessageStatus
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.tabs.LedgerItemView
import ai.risicloud.risime.data.tabs.RisiActionItems
import ai.risicloud.risime.data.tabs.RisiControl
import ai.risicloud.risime.data.tabs.RisiLedger
import ai.risicloud.risime.data.tabs.RisiMessages
import ai.risicloud.risime.data.tabs.RisiUiBus
import ai.risicloud.risime.net.ActionItem
import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.RisiActionCard
import ai.risicloud.risime.net.RisiActions142
import ai.risicloud.risime.net.RisiCommitmentsReply
import ai.risicloud.risime.net.RisiFactsReply
import ai.risicloud.risime.net.RisiItemStates
import ai.risicloud.risime.net.RisiKinds142
import ai.risicloud.risime.net.RisiRest
import ai.risicloud.risime.net.SourceRef
import ai.risicloud.risime.ui.settings.RisiPromiseOpener
import ai.risicloud.risime.ui.settings.RisiPromisesModel
import ai.risicloud.risime.ui.theme.RisiMeTheme
import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
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

/**
 * v1.42 §38 Risi voice → action items on the phone: the Official card (grouped by kind, owner / "Unassigned", due only
 * when said, live state from `item_update`, the jump to the line, the unclear card), the owner's confirm card
 * (confirm-before-write for the calendar and the reminder, Not mine hides it), and My promises' source chip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiActionItemsTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private val harsha = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val kamal = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val official = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val risiChat = "grp:99999999-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val risiUser = "9e1f0000-0000-4000-8000-000000000001"
    private val names = mapOf(harsha to "Harsha", kamal to "Kamal")
    private val now = Instant.parse("2026-10-12T12:40:00Z").toEpochMilli()

    private fun example(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private class Host(override val me: String) : RisiHost {
        val calls = mutableListOf<String>()
        override fun ask(text: String) { calls += "ask:$text" }
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) { calls += "act:$target:$action" }
        override fun feedback(callRef: String, rating: String, reason: String?) {}
        override fun actItem142(itemId: String, action: String, edit: JsonObject?) { calls += "$action:$itemId:$edit" }
        override fun openLine(conversationId: String, ref: SourceRef) { calls += "openLine:$conversationId:${ref.turn}" }
        override fun risiChatAvailable(): Boolean = true
    }

    private fun row(file: String, conv: String = official, id: String = file): MessageEntity {
        val env = ProtocolJson.parseToJsonElement(example(file)) as JsonObject
        return MessageEntity(
            clientMsgId = id, messageId = "c1a2b3f1-a4f0-11f1-8000-0242ac12000" + (id.length % 10), conversationId = conv, from = risiUser, to = conv,
            body = (env["body"] as JsonPrimitive).content, serverTs = "2026-10-12T12:31:12.004Z", localTs = 1L,
            status = MessageStatus.DELIVERED.name, outgoing = false, systemJson = RisiMessages.encode(env["risi"] as JsonObject),
        )
    }

    private fun show(host: Host, rows: List<MessageEntity>, itemViews: Map<String, LedgerItemView> = emptyMap(), jumps: MutableList<String>? = null) {
        val ctx = risiCardContext(
            host, rows, { names[it.lowercase()] ?: "Someone" }, now, onRef = {}, itemViews = itemViews,
            jumpLine = { conv, ref -> jumps?.add("$conv:${ref.type}:${ref.turn ?: ref.messageId}") },
        )
        rule.setContent { RisiMeTheme { Column { rows.forEach { m -> RisiCardRow(m, RisiMessages.meta(m)!!, ctx) } } } }
    }

    private fun card(file: String) = RisiActionCard.parse((ProtocolJson.parseToJsonElement(example(file)) as JsonObject)["risi"].toString())!!

    // ---- pure rules ----

    @Test fun parseIsStrictAndNeverGuesses() {
        val ok = card("envelope_risi_action_items.json")
        assertEquals(4, ok.items.size)
        // An unknown kind or an empty text is left out (never shown as a guess); a bad source drops the card.
        val bad = ok.copy(items = ok.items + ok.items[0].copy(itemId = "x", kind = "rumour") + ok.items[0].copy(itemId = "y", text = " "))
        assertEquals(4, RisiActionCard.validated(bad)!!.items.size)
        assertNull(RisiActionCard.validated(ok.copy(source = "chat")))
        assertNull(RisiActionCard.validated(ok.copy(status = "maybe")))
        // "Couldn't hear clearly": never any items, whatever came with it.
        assertTrue(RisiActionCard.validated(ok.copy(status = RisiKinds142.STATUS_UNCLEAR))!!.items.isEmpty())
        assertNull(RisiActionCard.parse("""{"kind":"answer"}"""))
    }

    @Test fun ownersDueAndVisibility() {
        val items = card("envelope_risi_action_items.json").items
        val promise = items[0]
        val decision = items[2]
        assertEquals("You", RisiActionItems.ownerLabel(promise, harsha) { names[it]!! })
        assertEquals("Harsha", RisiActionItems.ownerLabel(promise, kamal) { names[it]!! })
        assertEquals(RisiActionItems.UNASSIGNED, RisiActionItems.ownerLabel(promise.copy(owner = null), kamal) { "?" })
        assertNull(RisiActionItems.ownerLabel(decision, kamal) { "?" })
        assertNull("no due said: nothing shown", RisiActionItems.dueLabel(decision))
        // Not mine hides it for that owner only; cancelled hides it for everyone.
        assertFalse(RisiActionItems.visible(promise, RisiItemStates.DECLINED, harsha))
        assertTrue(RisiActionItems.visible(promise, RisiItemStates.DECLINED, kamal))
        assertFalse(RisiActionItems.visible(promise, RisiItemStates.CANCELLED, kamal))
    }

    @Test fun buttonsOnlyForTheOwnersOwnProposedItemBeforeExpiry() {
        val i = card("envelope_risi_action_confirm.json").items.single()
        assertEquals(RisiKinds142.BUTTONS, RisiActionItems.buttons(i, RisiItemStates.PROPOSED, harsha, false, emptySet(), false))
        assertTrue(RisiActionItems.buttons(i, RisiItemStates.PROPOSED, kamal, false, emptySet(), false).isEmpty())
        assertTrue(RisiActionItems.buttons(i, RisiItemStates.CONFIRMED, harsha, false, emptySet(), false).isEmpty())
        assertTrue(RisiActionItems.buttons(i, RisiItemStates.PROPOSED, harsha, true, emptySet(), false).isEmpty())
        assertTrue(RisiActionItems.buttons(i, RisiItemStates.PROPOSED, harsha, false, setOf(i.itemId), false).isEmpty())
        assertTrue(RisiActionItems.buttons(i, RisiItemStates.PROPOSED, harsha, false, emptySet(), true).isEmpty())
    }

    @Test fun noUsableTimeMeansThePickerNeverAGuess() {
        val i = card("envelope_risi_action_confirm.json").items.single()
        assertNull(RisiActionItems.calendarSlot(i.copy(due = null), now))
        // v1.43 §38.9: an all-day due is an all-day slot on that date (never a guessed hour).
        val zone = java.time.ZoneId.of("Asia/Colombo")
        val day = RisiActionItems.calendarSlot(i.copy(allDay = true, due = "2026-10-16T18:29:59.999Z"), now, zone)!!
        assertTrue(day.allDay && day.endMs - day.startMs == 86_400_000L)
        assertEquals("Fri 16 Oct (all day)", RisiActionItems.slotLabel(day, zone))
        assertNull(RisiActionItems.calendarSlot(i, Instant.parse("2026-10-14T00:00:00Z").toEpochMilli()))
        assertNull(RisiActionItems.remindAt(i.copy(due = null), now))
        assertNull(RisiActionItems.remindAt(i, Instant.parse("2026-10-13T11:00:00Z").toEpochMilli()))
    }

    @Test fun lineRowFindsTheTranscriptTurnAndTheVoiceNote() {
        val turn = MessageEntity(
            clientMsgId = "t3", messageId = "m-t3", conversationId = official, from = risiUser, to = official, body = "line", serverTs = null, localTs = 2L,
            status = MessageStatus.DELIVERED.name, outgoing = false,
            systemJson = """{"v":1,"kind":"call_transcript","session_id":"9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d","turn":3,"speaker":"$harsha","text":"x","lang":"en"}""",
        )
        val note = MessageEntity(
            clientMsgId = "n1", messageId = "d1e2f3a4-a6f2-11f1-8000-0242ac120002", conversationId = official, from = harsha, to = official, body = "", serverTs = null, localTs = 3L,
            status = MessageStatus.DELIVERED.name, outgoing = false, kind = MessageEntity.KIND_VOICE,
        )
        val call = card("envelope_risi_action_items.json").items[0].sourceRef!!
        val voice = card("envelope_risi_action_items_voice_note.json").items[0].sourceRef!!
        assertEquals("t3", RisiActionItems.lineRow(listOf(turn, note), call)?.clientMsgId)
        assertEquals("n1", RisiActionItems.lineRow(listOf(turn, note), voice)?.clientMsgId)
        assertNull(RisiActionItems.lineRow(listOf(note), call))
        // Opened from the Risi chat / My promises: the focus scrolls to the same row.
        assertEquals("m-t3", RisiLedger.focusTarget(listOf(turn, note), RisiUiBus.Focus(line = call)))
        assertEquals(0.5f, RisiActionItems.seekFraction(6_100, 12_200), 0.001f)
    }

    // ---- the Official card ----

    @Test fun officialCardGroupsByKindWithOwnerDueAndJump() {
        val jumps = mutableListOf<String>()
        show(Host(kamal), listOf(row("envelope_risi_action_items.json")), jumps = jumps)
        rule.onNodeWithTag("risi_card_action_items").assertIsDisplayed()
        rule.onNodeWithText(RisiActionItems.HEADER_ITEMS).assertIsDisplayed()
        for (k in listOf("promise", "meeting", "decision", "number")) rule.onNodeWithTag("risi_action_kind_$k").assertExists()
        rule.onNodeWithText("Decisions").assertExists()
        rule.onNodeWithText("From the call").assertIsDisplayed()
        rule.onNodeWithText("• Send the revised quotation", substring = true).performClick()
        assertEquals(listOf("null:call:3"), jumps)
        // A participant who owns an open item may open their Risi chat; nothing is confirmed from Official.
        rule.onNodeWithTag("risi_action_open_risi_chat").assertExists()
        assertTrue(rule.onAllNodes(hasTestTag("risi_action_add_calendar")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun officialCardFollowsItemUpdatesAndHidesNotMineForThatOwner() {
        val c = card("envelope_risi_action_items.json")
        val declined = mapOf(c.items[0].itemId.lowercase() to LedgerItemView(c.items[0].itemId, c.summaryId, harsha, emptyList(), c.items[0].text, null, false, null, RisiItemStates.DECLINED, harsha))
        show(Host(harsha), listOf(row("envelope_risi_action_items.json")), itemViews = declined)
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("• Send the revised quotation", substring = true)).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("• Site visit at level 3", substring = true).assertIsDisplayed()
    }

    @Test fun officialCardShowsOthersTheLiveState() {
        val c = card("envelope_risi_action_items.json")
        val confirmed = mapOf(c.items[0].itemId.lowercase() to LedgerItemView(c.items[0].itemId, c.summaryId, harsha, emptyList(), c.items[0].text, null, false, null, RisiItemStates.CONFIRMED, harsha))
        show(Host(kamal), listOf(row("envelope_risi_action_items.json")), itemViews = confirmed)
        rule.onNodeWithText("Harsha · due", substring = true).assertIsDisplayed()
        rule.onNodeWithText("confirmed ✓", substring = true).assertIsDisplayed()
    }

    @Test fun unclearCardSaysSoAndListsNothing() {
        show(Host(kamal), listOf(row("envelope_risi_action_items_unclear.json")))
        rule.onNodeWithTag("risi_action_items_unclear").assertIsDisplayed()
        // v1.43 §38.9: "I couldn't hear this clearly" in the card's language (this one is Sinhala).
        rule.onNodeWithText(RisiActionItems.UNCLEAR_SI).assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasTestTag("risi_action_item")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun unclearTextByLanguage() {
        val c = card("envelope_risi_action_items_unclear.json")
        assertEquals("I couldn't hear this clearly.", RisiActionItems.unclearText(c.copy(lang = "en")))
        assertEquals(RisiActionItems.UNCLEAR_TA, RisiActionItems.unclearText(c.copy(lang = "ta")))
        assertEquals(RisiActionItems.UNCLEAR_EN, RisiActionItems.unclearText(c.copy(lang = null, reason = RisiKinds142.REASON_NO_SPEECH)))
    }

    // ---- v1.43 §38.9 ask, never guess ----

    private class ClaimHost(override val me: String) : RisiHost {
        val claims = mutableListOf<String>()
        override fun ask(text: String) {}
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) {}
        override fun feedback(callRef: String, rating: String, reason: String?) {}
        override fun claimItem(itemId: String) { claims += itemId }
        override fun risiChatAvailable(): Boolean = true
    }

    @Test fun unownedItemAsksAndAnyMemberClaimsIt() {
        val r = row("envelope_risi_action_items_v143.json")
        val c = card("envelope_risi_action_items_v143.json")
        val task = c.items.single { it.claim }
        val host = ClaimHost(kamal)
        // The card's own time is 2026-10-12T12:31Z; now is 12:40Z: within 48 h.
        val ctx = risiCardContext(host, listOf(r), { names[it.lowercase()] ?: "Someone" }, now, onRef = {})
        rule.setContent { RisiMeTheme { Column { RisiCardRow(r, RisiMessages.meta(r)!!, ctx) } } }
        rule.onNodeWithText("Unassigned", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("risi_action_claim").performClick()
        assertEquals(listOf(task.itemId), host.claims)
        // Once tapped the button goes (the item_update with the owner follows).
        assertTrue(rule.onAllNodes(hasTestTag("risi_action_claim")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun claimedItemShowsItsOwnerAndNoButton() {
        val c = card("envelope_risi_action_items_v143.json")
        val task = c.items.single { it.claim }
        val upd = ProtocolJson.decodeFromString<ai.risicloud.risime.net.RisiTextEnvelope>(example("envelope_risi_item_update_v143.json")).risi!!
        val views = mapOf(task.itemId.lowercase() to LedgerItemView(task.itemId, c.summaryId, upd.owner, emptyList(), task.text, null, false, null, RisiItemStates.PROPOSED, upd.by))
        assertEquals(kamal, RisiActionItems.live(task, views).owner)
        assertFalse(RisiActionItems.canClaim(task, views, null, now, emptySet(), false))
        assertFalse("not after 48 h", RisiActionItems.canClaim(task, emptyMap(), now - RisiActionItems.CLAIM_WINDOW_MS, now, emptySet(), false))
        assertFalse("read-only", RisiActionItems.canClaim(task, emptyMap(), null, now, emptySet(), true))
        assertFalse("an owned item is never claimable", RisiActionItems.canClaim(c.items.single { !it.claim }, emptyMap(), null, now, emptySet(), false))
        // RisiLedger takes the owner from a state-only item_update (no text, no due: the card keeps its own).
        val m = row("envelope_risi_item_update_v143.json", conv = risiChat)
        val v = RisiLedger.items(listOf(m))[task.itemId.lowercase()]!!
        assertEquals(kamal, v.owner)
        assertEquals(RisiItemStates.PROPOSED, v.state)
    }

    @Test fun remindersOffIsExplainedNotTurnedOn() {
        val c = card("envelope_risi_action_confirm_v143.json")
        val i = c.items.single()
        assertEquals(RisiActionItems.REMINDERS_NOTE, RisiActionItems.remindersNote(c, i.buttons))
        assertNull(RisiActionItems.remindersNote(c.copy(remindersSkill = "ask"), i.buttons))
        assertNull(RisiActionItems.remindersNote(c, i.buttons - RisiKinds142.BUTTON_REMIND))
        show(Host(harsha), listOf(row("envelope_risi_action_confirm_v143.json", conv = risiChat)))
        rule.onNodeWithTag("risi_action_reminders_note").assertIsDisplayed()
        rule.onNodeWithTag("risi_action_remind").assertIsDisplayed()
    }

    @Test fun voiceNoteCardNamesTheNoteAndJumpsToIt() {
        val jumps = mutableListOf<String>()
        show(Host(kamal), listOf(row("envelope_risi_action_items_voice_note.json")), jumps = jumps)
        rule.onNodeWithText("From a voice note").performClick()
        rule.onNodeWithText("• Send the quotation", substring = true).performClick()
        assertEquals(listOf("null:voice_note:d1e2f3a4-a6f2-11f1-8000-0242ac120002", "null:voice_note:d1e2f3a4-a6f2-11f1-8000-0242ac120002"), jumps)
        // No due was said: the row shows only the owner and the state.
        rule.onNodeWithText("Harsha · waiting").assertIsDisplayed()
    }

    // ---- the owner's confirm card ----

    @Test fun addToCalendarAsksWithTheExactTimeBeforeWriting() {
        val h = Host(harsha)
        show(h, listOf(row("envelope_risi_action_confirm.json", conv = risiChat)))
        rule.onNodeWithText(RisiActionItems.HEADER_CONFIRM).assertIsDisplayed()
        rule.onNodeWithTag("risi_action_add_calendar").performClick()
        rule.onNodeWithTag("risi_action_dialog_text").assertIsDisplayed()
        rule.onNodeWithText("Add \"Send the revised quotation\" to your Risi Calendar on", substring = true).assertIsDisplayed()
        assertTrue("nothing is written before the tap", h.calls.isEmpty())
        rule.onNodeWithTag("risi_action_dialog_cancel").performClick()
        assertTrue(h.calls.isEmpty())
        rule.onNodeWithTag("risi_action_add_calendar").performClick()
        rule.onNodeWithTag("risi_action_dialog_ok").performClick()
        val expected = RisiActionItems.calendarEdit(RisiActionItems.Slot(Instant.parse("2026-10-13T11:30:00Z").toEpochMilli(), Instant.parse("2026-10-13T12:30:00Z").toEpochMilli(), false))
        assertEquals(listOf("${RisiActions142.ITEM_CALENDAR}:f1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c:$expected"), h.calls)
    }

    @Test fun remindMeAsksWithDueMinusOneHour() {
        val h = Host(harsha)
        show(h, listOf(row("envelope_risi_action_confirm.json", conv = risiChat)))
        rule.onNodeWithTag("risi_action_remind").performClick()
        rule.onNodeWithText("Remind you about \"Send the revised quotation\"", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("risi_action_dialog_ok").performClick()
        assertEquals(listOf("${RisiActions142.ITEM_REMIND}:f1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c:${buildJsonObject { put("remind_at", "2026-10-13T10:30:00.000Z") }}"), h.calls)
    }

    @Test fun notMineAndAddToPromisesSendTheirActions() {
        val h = Host(harsha)
        show(h, listOf(row("envelope_risi_action_confirm.json", conv = risiChat)))
        rule.onNodeWithTag("risi_action_add_promise").performClick()
        rule.onNodeWithTag("risi_action_not_mine").performClick()
        assertEquals(listOf("act:f1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c:item_confirm", "${RisiActions142.ITEM_NOT_MINE}:f1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c:null"), h.calls)
    }

    @Test fun afterNotMineTheItemIsGoneForThatOwner() {
        val ctl = MessageEntity(
            clientMsgId = "ctl", messageId = null, conversationId = risiChat, from = harsha, to = risiChat, body = "", serverTs = null, localTs = 5L,
            status = MessageStatus.SENT.name, outgoing = true, kind = MessageEntity.KIND_RISI_CTL,
            systemJson = RisiMessages.encode(RisiControl.action("f1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c", RisiActions142.ITEM_NOT_MINE)),
        )
        val h = Host(harsha)
        val card = row("envelope_risi_action_confirm.json", conv = risiChat)
        val ctx = risiCardContext(h, listOf(card, ctl), { names[it.lowercase()] ?: "Someone" }, now, onRef = {})
        rule.setContent { RisiMeTheme { RisiCardRow(card, RisiMessages.meta(card)!!, ctx) } }
        rule.onNodeWithTag("risi_action_not_mine_done").assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasTestTag("risi_action_add_calendar")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun someoneElseSeesNoButtonsOnAConfirmCard() {
        show(Host(kamal), listOf(row("envelope_risi_action_confirm.json", conv = risiChat)))
        assertTrue(rule.onAllNodes(hasTestTag("risi_action_add_promise")).fetchSemanticsNodes().isEmpty())
    }

    // ---- My promises ----

    private class Rest(val reply: RisiCommitmentsReply) : RisiRest {
        val calls = mutableListOf<String>()
        override suspend fun feedback(callRef: String, rating: String, reason: String?): ApiResult<Unit> = ApiResult.Ok(Unit)
        override suspend fun facts(): ApiResult<RisiFactsReply> = ApiResult.Ok(RisiFactsReply(emptyList()))
        override suspend fun deleteFact(factId: String): ApiResult<Unit> = ApiResult.Ok(Unit)
        override suspend fun deleteAllFacts(): ApiResult<Unit> = ApiResult.Ok(Unit)
        override suspend fun commitments(state: String): ApiResult<RisiCommitmentsReply> { calls += "commitments:$state"; return ApiResult.Ok(reply) }
        override suspend fun commitments(state: String, source: String?): ApiResult<RisiCommitmentsReply> { calls += "commitments:$state:$source"; return ApiResult.Ok(reply) }
    }

    private fun await(p: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!p()) kotlinx.coroutines.delay(10) } }

    @Test fun promisesSourceChipUsesTheQueryAndATapJumpsToTheLine() {
        val v142 = ProtocolJson.decodeFromString(RisiCommitmentsReply.serializer(), example("risi_commitments_reply_v142.json"))
        val chat = v142.commitments.single().copy(commitmentId = "chat-1", source = "chat", sourceRef = null, kind = null)
        val rest = Rest(v142.copy(commitments = v142.commitments + chat))
        val opened = mutableListOf<String>()
        val opener = object : RisiPromiseOpener {
            override suspend fun has(conversationId: String) = true
            override fun open(conversationId: String, messageId: String?) { opened += "open:$conversationId" }
            override fun openLine(conversationId: String, ref: SourceRef) { opened += "line:$conversationId:${ref.turn}" }
        }
        val model = RisiPromisesModel(rest, scope, opener, sourceChip = true)
        model.load()
        await { !model.state.value.loading }
        assertEquals(2, model.state.value.items.size)
        model.setFromCalls(true)
        await { !model.state.value.loading && model.state.value.fromCalls }
        assertEquals(listOf("commitments:open", "commitments:open:call,voice_note"), rest.calls)
        // An older server ignores `source`: the phone keeps only call/voice items.
        assertEquals(listOf("f1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c"), model.state.value.items.map { it.commitmentId })
        model.open(model.state.value.items.single())
        await { opened.isNotEmpty() }
        assertEquals(listOf("line:grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b:3"), opened)
        assertEquals("From a call", RisiActionItems.sourceLabel(v142.commitments.single().source))
    }

    @Test fun promisesScreenShowsTheChipOnlyWhenTheServerSaysSo() {
        val v142 = ProtocolJson.decodeFromString(RisiCommitmentsReply.serializer(), example("risi_commitments_reply_v142.json"))
        val model = RisiPromisesModel(Rest(v142), scope, sourceChip = true)
        model.load()
        await { !model.state.value.loading }
        rule.setContent { RisiMeTheme { ai.risicloud.risime.ui.settings.RisiPromisesScreen(model, harsha, { names[it] ?: "?" }, onBack = {}) } }
        rule.onNodeWithTag("risi_promises_source_chip").assertIsDisplayed()
        rule.onNodeWithText("From a call", substring = true).assertIsDisplayed()
        val off = RisiPromisesModel(Rest(v142), scope)
        assertFalse(off.state.value.sourceChip)
    }

}
