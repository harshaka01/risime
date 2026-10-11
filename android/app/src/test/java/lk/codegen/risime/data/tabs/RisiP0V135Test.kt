package lk.codegen.risime.data.tabs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiItem
import lk.codegen.risime.net.RisiItemPatch
import lk.codegen.risime.net.RisiItemReply
import lk.codegen.risime.net.RisiItemsReply
import lk.codegen.risime.net.RisiTextEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** v1.35 §34 Phase 2: superseded cards, error text, the clarify buttons, Risi's items (rules, "Your events" merge, the model). */
class RisiP0V135Test {
    private fun read(name: String): String =
        javaClass.classLoader!!.getResource("contract/v1/examples/$name")?.readText() ?: error("missing $name")

    private val asker = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val phone = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    private val other = "99999999-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    private fun ms(ts: String) = Instant.parse(ts).toEpochMilli()
    private fun risi(name: String) = ProtocolJson.decodeFromString<RisiTextEnvelope>(read(name)).risi!!

    /** A stored, honoured Risi row of an example. */
    private fun row(name: String, id: String = name) = MessageEntity(
        clientMsgId = id, messageId = id, conversationId = "grp:risi", from = "agent", to = "grp:risi",
        body = ProtocolJson.decodeFromString<RisiTextEnvelope>(read(name)).body, serverTs = null, localTs = 0, status = "READ", outgoing = false,
        systemJson = RisiMessages.encode(ProtocolJson.parseToJsonElement(read(name)).jsonObject["risi"]!!.jsonObject),
    )

    private fun ctl(from: String, target: String, action: String) = MessageEntity(
        clientMsgId = "c-$from-$action", messageId = null, conversationId = "grp:risi", from = from, to = "grp:risi", body = "",
        serverTs = null, localTs = 0, status = "READ", outgoing = false, kind = MessageEntity.KIND_RISI_CTL,
        systemJson = ProtocolJson.encodeToString(JsonObject.serializer(), RisiControl.action(target, action)),
    )

    // ---- §34.2 confirm_update ----

    @Test fun confirmUpdateSupersedesTheCardAndTheFirstDecisiveMessageWins() {
        val card = risi("envelope_risi_confirm_schedule_message.json")
        val before = ms(card.expiresAt!!) - 1
        val update = row("envelope_risi_confirm_update_superseded.json")
        val st = RisiToolCards.confirmState(card, listOf(update), before)
        assertEquals(RisiToolCards.ConfirmState.SUPERSEDED, st)
        assertTrue(RisiToolCards.confirmButtons(asker, card, st, emptySet()).isEmpty())
        assertEquals("Replaced by your newer request", RisiToolCards.closedText(st))
        // A confirm_write before it wins; one after it is too late.
        assertEquals(RisiToolCards.ConfirmState.CONFIRMED, RisiToolCards.confirmState(card, listOf(ctl(asker, card.writeId!!, "confirm_write"), update), before))
        assertEquals(RisiToolCards.ConfirmState.SUPERSEDED, RisiToolCards.confirmState(card, listOf(update, ctl(asker, card.writeId!!, "confirm_write")), before))
        // Another card's write_id is untouched.
        assertEquals(RisiToolCards.ConfirmState.OPEN, RisiToolCards.confirmState(risi("envelope_risi_confirm.json").let { it.copy(expiresAt = card.expiresAt) }, listOf(update), before))
    }

    @Test fun anUnknownConfirmUpdateStateReadsClosed() {
        val card = risi("envelope_risi_confirm_schedule_message.json")
        val m = row("envelope_risi_confirm_update_superseded.json").let { it.copy(systemJson = it.systemJson!!.replace("\"superseded\"", "\"withdrawn\"")) }
        val st = RisiToolCards.confirmState(card, listOf(m), ms(card.expiresAt!!) - 1)
        assertEquals(RisiToolCards.ConfirmState.CLOSED, st)
        assertEquals("Closed", RisiToolCards.closedText(st))
    }

    @Test fun aConfirmUpdateFromAnOrdinaryMessageIsIgnored() {
        val card = risi("envelope_risi_confirm_schedule_message.json")
        // Not honoured on arrival: stored without its `risi` object, so nothing to read.
        val plain = row("envelope_risi_confirm_update_superseded.json").copy(systemJson = null)
        assertEquals(RisiToolCards.ConfirmState.OPEN, RisiToolCards.confirmState(card, listOf(plain), ms(card.expiresAt!!) - 1))
    }

    @Test fun confirmUpdateNeedsNoBubbleWhenItsCardIsHere() {
        val u = risi("envelope_risi_confirm_update_superseded.json")
        assertTrue(RisiP0Cards.confirmUpdateHasCard(u, listOf(row("envelope_risi_confirm_schedule_message.json"))))
        assertFalse(RisiP0Cards.confirmUpdateHasCard(u, listOf(row("envelope_risi_confirm.json"))))
    }

    // ---- §34.2 / §34.3 error cards ----

    @Test fun errorTextShowsTheBodyForSupersededAndUnknownCodes() {
        assertEquals("This was replaced by your newer request.", RisiP0Cards.errorText("superseded", "This was replaced by your newer request."))
        assertEquals(RisiP0Cards.SUPERSEDED_ERROR, RisiP0Cards.errorText("superseded", ""))
        assertEquals(RisiCards.errorText("model_unavailable"), RisiP0Cards.errorText("model_unavailable", "Risi couldn't answer right now."))
        assertEquals("Something new", RisiP0Cards.errorText("brand_new_code", "Something new"))
        assertEquals("Risi couldn't do that.", RisiP0Cards.errorText("brand_new_code", null))
    }

    // ---- §34.5 clarify ----

    @Test fun clarifyButtonsSendTheServersAskTexts() {
        val r = risi("envelope_risi_answer_name_clarify.json")
        val b = RisiP0Cards.clarifyButtons(r)
        assertEquals(listOf("Shirazi", "Keep \"Shutazi\""), b.map { it.label })
        assertEquals(listOf("Use Shirazi", "Keep Shutazi"), b.map { it.text })
        assertTrue(b.last().keep)
        // Both chips are shown as the card's buttons, so no chip is left beside it.
        assertEquals(emptyList<Any>(), RisiP0Cards.chipsBesideClarify(r))
        // An option without a server ask is never invented.
        assertTrue(RisiP0Cards.clarifyButtons(r.copy(nextActions = emptyList())).isEmpty())
    }

    @Test fun clarifyClosesOnConfirmUpdateAndIsAnsweredByTheCard() {
        val r = risi("envelope_risi_answer_name_clarify.json")
        assertEquals(RisiP0Cards.ClarifyState.OPEN, RisiP0Cards.clarifyState(r, emptyList()))
        val wid = r.clarify!!.writeId!!
        val update = row("envelope_risi_confirm_update_superseded.json").let { it.copy(systemJson = it.systemJson!!.replace("2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e", wid)) }
        assertEquals(RisiP0Cards.ClarifyState.CLOSED, RisiP0Cards.clarifyState(r, listOf(update)))
        val card = row("envelope_risi_confirm_risi_calendar_add.json").let { m ->
            val old = RisiMessages.meta(m)!!.writeId!!
            m.copy(systemJson = m.systemJson!!.replace(old, wid))
        }
        assertEquals(RisiP0Cards.ClarifyState.ANSWERED, RisiP0Cards.clarifyState(r, listOf(card)))
    }

    // ---- §34.4 Risi's items ----

    private val items: List<RisiItem> by lazy { ProtocolJson.decodeFromString<RisiItemsReply>(read("risi_items_reply.json")).items }
    private fun item(kind: String) = items.single { it.kind == kind }

    @Test fun buttonsFollowActionsAndThePhoneThatOwnsTheItem() {
        assertEquals(listOf("open", "edit", "delete"), RisiItems.buttons(item(RisiItem.PHONE_EVENT_ADDED), phone))
        assertEquals(emptyList<String>(), RisiItems.buttons(item(RisiItem.PHONE_EVENT_ADDED), other))
        assertEquals(emptyList<String>(), RisiItems.buttons(item(RisiItem.SCHEDULED_MESSAGE), other))
        assertEquals(listOf("open", "edit", "delete"), RisiItems.buttons(item(RisiItem.REMINDER), other))
        assertEquals(listOf("open", "edit"), RisiItems.buttons(item(RisiItem.PROMISE), other))
        assertEquals(listOf("open"), RisiItems.buttons(item(RisiItem.FOLLOW_UP), other))
        assertEquals(emptyList<String>(), RisiItems.buttons(item(RisiItem.REMINDER).copy(kind = "mystery"), phone))
    }

    @Test fun deleteIsRoutedByKind() {
        assertEquals(RisiItems.DeleteRoute.PHONE_EVENT, RisiItems.deleteRoute(item(RisiItem.PHONE_EVENT_ADDED), phone))
        assertEquals(RisiItems.DeleteRoute.NONE, RisiItems.deleteRoute(item(RisiItem.PHONE_EVENT_ADDED), other))
        assertEquals(RisiItems.DeleteRoute.SCHEDULE, RisiItems.deleteRoute(item(RisiItem.SCHEDULED_MESSAGE), phone))
        assertEquals(RisiItems.DeleteRoute.SERVER, RisiItems.deleteRoute(item(RisiItem.REMINDER), other))
        assertEquals(RisiItems.DeleteRoute.SERVER, RisiItems.deleteRoute(item(RisiItem.RISI_CALENDAR_EVENT), other))
        assertEquals(RisiItems.DeleteRoute.NONE, RisiItems.deleteRoute(item(RisiItem.PROMISE), phone))
        assertEquals(RisiItems.DeleteRoute.NONE, RisiItems.deleteRoute(item(RisiItem.FOLLOW_UP), phone))
    }

    @Test fun groupedByDayWithNoDateLastAndOneLineTitles() {
        val zone = ZoneId.of("Asia/Colombo")
        val g = RisiItems.groupByDay(items, zone, Locale.ENGLISH)
        assertEquals(listOf("Mon 12 Oct", "Tue 13 Oct", "Wed 14 Oct", RisiItems.NO_DATE), g.map { it.first })
        assertEquals(3, g[0].second.size)
        assertEquals("06:00 (every day)", RisiItems.timeText(item(RisiItem.SCHEDULED_MESSAGE), zone))
        assertEquals("14:00–15:00", RisiItems.timeText(item(RisiItem.PHONE_EVENT_ADDED), zone))
        assertNull(RisiItems.timeText(item(RisiItem.FOLLOW_UP), zone))
        // A scheduled message: its local text on its own phone, "On your other phone" elsewhere (never a server title).
        assertEquals("Good morning", RisiItems.titleText(item(RisiItem.SCHEDULED_MESSAGE), phone, "Good morning"))
        assertEquals(RisiItems.OTHER_PHONE, RisiItems.titleText(item(RisiItem.SCHEDULED_MESSAGE), other, "Good morning"))
        assertEquals("Work (Google)", RisiItems.calendarText(item(RisiItem.PHONE_EVENT_ADDED)))
        assertEquals(2, RisiItems.upcoming(items, ms("2026-10-13T12:00:00Z")).size) // the promise and the undated follow-up
    }

    @Test fun yourEventsMergesItemsWithoutDuplicates() {
        val from = ms("2026-10-11T18:30:00Z")
        val to = ms("2026-10-12T18:30:00Z")
        val rows = RisiItems.toLocalEvents(items, from, to, phone) { if (it == "1d2e3f4a-5b6c-4d7e-8f8a-9b0c1d2e3f4a") "Good morning" else null }
        assertEquals(listOf(RisiItem.SCHEDULED_MESSAGE, RisiItem.RISI_CALENDAR_EVENT, RisiItem.PHONE_EVENT_ADDED), rows.map { it.itemKind })
        assertEquals("Scheduled message: Good morning", rows[0].title)
        // The provider already shows event 4711, the cached Risi Calendar the Shenika interview: both items drop.
        val shown = listOf(
            LocalEvent("Call with Kamal", ms("2026-10-12T08:30:00Z"), ms("2026-10-12T09:30:00Z"), false, eventId = "4711"),
            LocalEvent("Interview with Shenika", ms("2026-10-12T04:30:00Z"), ms("2026-10-12T05:30:00Z"), false, risi = true, eventId = "7f8a9b0c-1d2e-4f3a-8b4c-5d6e7f8a9b0c"),
        )
        val merged = LocalEvents.withItems(shown, rows)
        assertEquals(3, merged.size)
        assertEquals(listOf(RisiItem.SCHEDULED_MESSAGE), merged.mapNotNull { it.itemKind })
        assertEquals(merged.sortedBy { it.begin }, merged)
        // From another phone, a phone event's id is that phone's: never matched here, so the item stays.
        val elsewhere = LocalEvents.withItems(shown, RisiItems.toLocalEvents(items, from, to, other))
        assertEquals(listOf(RisiItem.SCHEDULED_MESSAGE, RisiItem.PHONE_EVENT_ADDED), elsewhere.mapNotNull { it.itemKind })
        assertEquals("Scheduled message: " + RisiItems.OTHER_PHONE, elsewhere.first { it.itemKind == RisiItem.SCHEDULED_MESSAGE }.title)
    }

    @Test fun buildMergesItemsAndAnItemsFailureIsNeverFatal() = runBlocking {
        val from = ms("2026-10-11T18:30:00Z")
        val to = ms("2026-10-12T18:30:00Z")
        val ok = LocalEvents.build(from, to, phone = { emptyList() }, risi = { emptyList() }, items = { RisiItems.toLocalEvents(items, from, to, phone) })
        assertEquals(3, (ok as LocalEventsResult.Events).events.size)
        val failed = LocalEvents.build(from, to, phone = { emptyList() }, risi = { emptyList() }, items = { error("offline") })
        assertEquals(0, (failed as LocalEventsResult.Events).events.size)
    }

    // ---- the model ----

    private class FakeRest(val items: List<RisiItem>) : RisiItemsRest {
        val deleted = mutableListOf<String>()
        val patched = mutableListOf<Pair<String, RisiItemPatch>>()
        var deleteResult: ApiResult<Unit> = ApiResult.Ok(Unit)
        override suspend fun list(from: String?, to: String?, kinds: List<String>?): ApiResult<RisiItemsReply> = ApiResult.Ok(RisiItemsReply(items))
        override suspend fun patch(id: String, body: RisiItemPatch): ApiResult<RisiItemReply> {
            patched += id to body
            val i = items.single { it.id == id }
            return ApiResult.Ok(RisiItemReply(i.copy(title = body.text ?: i.title, start = body.at ?: i.start)))
        }
        override suspend fun delete(id: String): ApiResult<Unit> { deleted += id; return deleteResult }
    }

    private class FakeLocal(val device: String) : RisiItemsLocal {
        /** write_id → provider event id this phone created. */
        val records = mutableMapOf("5d6e7f8a-9b0c-4d1e-8f2a-3b4c5d6e7f8a" to 4711L)
        val provider = mutableSetOf(4711L)
        val schedules = mutableMapOf("1d2e3f4a-5b6c-4d7e-8f8a-9b0c1d2e3f4a" to "Good morning")
        override suspend fun deviceId() = device
        override suspend fun ownsPhoneEvent(writeId: String?, eventId: Long) = writeId != null && records[writeId] == eventId
        override suspend fun deletePhoneEvent(writeId: String, eventId: Long) = provider.remove(eventId)
        override suspend fun scheduledText(scheduleId: String) = schedules[scheduleId]
        override suspend fun cancelSchedule(scheduleId: String) = schedules.remove(scheduleId) != null
        override suspend fun editSchedule(scheduleId: String, text: String): Boolean { schedules[scheduleId] = text.trim(); return true }
    }

    private fun model(rest: FakeRest, local: FakeLocal) = RisiItemsModel(rest, local, CoroutineScope(Dispatchers.Unconfined), now = { ms("2026-10-11T09:00:00Z") }).also { it.load() }

    @Test fun deletingAPhoneEventRemovesTheProviderRowThenTheServerRow() {
        val rest = FakeRest(items)
        val local = FakeLocal(phone)
        val m = model(rest, local)
        assertEquals(6, m.state.value.items.size)
        assertEquals("Good morning", m.state.value.localTexts["1d2e3f4a-5b6c-4d7e-8f8a-9b0c1d2e3f4a"])
        m.delete(item(RisiItem.PHONE_EVENT_ADDED))
        assertTrue(4711L !in local.provider)
        assertEquals(listOf("3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f"), rest.deleted)
        assertTrue(m.state.value.items.none { it.kind == RisiItem.PHONE_EVENT_ADDED })
        assertEquals(RisiItems.DELETED, m.state.value.note)
    }

    @Test fun aPhoneEventNotInThisPhonesRecordIsNeverTouched() {
        val rest = FakeRest(items)
        val local = FakeLocal(phone).also { it.records.clear() }
        val m = model(rest, local)
        m.delete(item(RisiItem.PHONE_EVENT_ADDED))
        assertTrue(4711L in local.provider)
        assertTrue(rest.deleted.isEmpty())
        assertEquals(RisiItems.DELETE_FAILED, m.state.value.note)
        // From another phone nothing happens at all.
        val m2 = model(rest, FakeLocal(other))
        m2.delete(item(RisiItem.PHONE_EVENT_ADDED))
        assertTrue(rest.deleted.isEmpty())
    }

    @Test fun deletingAScheduleCancelsItHereAndAReminderOnTheServer() {
        val rest = FakeRest(items)
        val local = FakeLocal(phone)
        val m = model(rest, local)
        m.delete(item(RisiItem.SCHEDULED_MESSAGE))
        assertTrue(local.schedules.isEmpty())
        m.delete(item(RisiItem.REMINDER))
        assertEquals(listOf("0c1d2e3f-4a5b-4c6d-9e7f-8a9b0c1d2e3f", "8a9b0c1d-2e3f-4a4b-9c5d-6e7f8a9b0c1d"), rest.deleted)
        // A promise is never deleted here (My promises edits it).
        m.delete(item(RisiItem.PROMISE))
        assertEquals(2, rest.deleted.size)
    }

    @Test fun aFailedServerDeleteKeepsTheRow() {
        val rest = FakeRest(items).also { it.deleteResult = ApiResult.Error(503, "agent_unavailable", "x") }
        val m = model(rest, FakeLocal(phone))
        m.delete(item(RisiItem.REMINDER))
        assertTrue(m.state.value.items.any { it.kind == RisiItem.REMINDER })
        assertEquals(RisiItems.DELETE_FAILED, m.state.value.note)
    }

    @Test fun editingAReminderPatchesItAndAScheduleStaysOnThePhone() {
        val rest = FakeRest(items)
        val local = FakeLocal(phone)
        val m = model(rest, local)
        m.editReminder(item(RisiItem.REMINDER), "2026-10-13T04:30:00.000Z", " Pay the electricity bill ")
        assertEquals(RisiItemPatch("2026-10-13T04:30:00.000Z", "Pay the electricity bill"), rest.patched.single().second)
        assertEquals("2026-10-13T04:30:00.000Z", m.state.value.items.single { it.kind == RisiItem.REMINDER }.start)
        m.editScheduled(item(RisiItem.SCHEDULED_MESSAGE), "Good morning!")
        assertEquals("Good morning!", local.schedules.values.single())
        assertEquals(1, rest.patched.size)
        // An empty or over-long text is no PATCH.
        assertNull(RisiItemPatch.of(null, " "))
        assertNull(RisiItemPatch.of(null, "x".repeat(501)))
        assertNull(RisiItemPatch.of(null, null))
    }
}
