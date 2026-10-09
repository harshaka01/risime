package lk.codegen.risime.data.notes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiControl
import lk.codegen.risime.data.tabs.RisiLedger
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthConfig
import lk.codegen.risime.net.CAPABILITY_RISI_NOTES
import lk.codegen.risime.net.DevicePut
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiActionEnvelope
import lk.codegen.risime.net.RisiActions130
import lk.codegen.risime.net.RisiCommitmentsReply
import lk.codegen.risime.net.RisiItemStates
import lk.codegen.risime.net.RisiKinds130
import lk.codegen.risime.net.RisiNote
import lk.codegen.risime.net.RisiNoteReply
import lk.codegen.risime.net.RisiNotesReply
import lk.codegen.risime.net.RisiNotesRest
import lk.codegen.risime.net.RisiNoteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * v1.29 §30 Risi Notes on the phone: every fixture decodes (unknown fields tolerated), the per-viewer title,
 * ticks ↔ `done`/`item_reopen` and `item_update`s (card copy and REST copy), the list/search model, the
 * note screen model and sharing. Fixtures: test resources `fixtures/risi_notes/` (shapes from the proposal).
 */
class RisiNotesTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val shenika = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val noteId = "a7b8c9d0-e1f2-4a3b-8c4d-5e6f7a8b9c0d"
    private val mine = "c3d4e5f6-a7b8-4c9d-8e0f-2a3b4c5d6e7f"
    private val hers = "d4e5f6a7-b8c9-4d0e-9f1a-3b4c5d6e7f8a"
    private val colombo = ZoneId.of("Asia/Colombo")
    private val names = mapOf(me to "Harsha", shenika to "Shenika", "11111111-2222-4333-8444-555555555555" to "Kamal", "66666666-7777-4888-9999-000000000000" to "Ruwan")
    private val nameOf: (String) -> String = { names[it] ?: "Someone" }
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/risi_notes/$name")!!.readText()

    private var seq = 0

    private fun row(name: String, ts: String = "2026-10-09T09:41:42.000Z"): MessageEntity {
        val env = ProtocolJson.parseToJsonElement(fixture(name)).jsonObject
        seq++
        return MessageEntity(
            clientMsgId = "c$seq-$name", messageId = "m$seq-$name", conversationId = "grp:risi", from = "risi", to = "grp:risi",
            body = (env["body"] as JsonPrimitive).content, serverTs = ts, localTs = seq.toLong(),
            status = "READ", outgoing = false, systemJson = RisiMessages.encode(env["risi"] as JsonObject),
        )
    }

    /** An `item_update` for [item] (the shape of the §27.5 example, with `note_id`). */
    private fun update(item: String, state: String): MessageEntity {
        seq++
        val risi = """{"v":1,"kind":"item_update","summary_id":"$noteId","note_id":"$noteId","item_id":"$item","state":"$state","by":"$me","text":null,"due":null,"notify":[]}"""
        return MessageEntity(
            clientMsgId = "u$seq", messageId = "mu$seq", conversationId = "grp:risi", from = "risi", to = "grp:risi", body = "update",
            serverTs = "2026-10-09T10:00:00.000Z", localTs = seq.toLong(), status = "READ", outgoing = false, systemJson = risi,
        )
    }

    /** My own `risi_action` row (as the outbox stores it). */
    private fun myAction(item: String, action: String, localTs: Long, status: String = MessageStatus.SENT.name): MessageEntity {
        seq++
        return MessageEntity(
            clientMsgId = "a$seq", messageId = null, conversationId = "grp:risi", from = me, to = "grp:risi", body = "",
            serverTs = null, localTs = localTs, status = status, outgoing = true, kind = MessageEntity.KIND_RISI_CTL,
            systemJson = ProtocolJson.encodeToString(JsonObject.serializer(), RisiControl.action(item, action)),
        )
    }

    private val card: RisiNote get() = RisiNote.parse(row("envelope_risi_note_card.json").systemJson)!!

    // ---- parsing ----

    @Test fun switchCapabilityAndExamplesDecode() {
        assertTrue(ProtocolJson.decodeFromString(AuthConfig.serializer(), fixture("auth_config_v129_notes.json")).risiNotesOn)
        assertFalse(ProtocolJson.decodeFromString(AuthConfig.serializer(), """{"modes":["dev"],"risi_events":"on"}""").risiNotesOn)
        assertTrue(CAPABILITY_RISI_NOTES in ProtocolJson.decodeFromString(DevicePut.serializer(), fixture("device_put_risi_notes.json")).mls!!.capabilities!!)
        val list = ProtocolJson.decodeFromString(RisiNotesReply.serializer(), fixture("risi_notes_reply.json"))
        assertEquals(2, list.notes.size)
        assertTrue(list.hasMore)
        assertEquals(1, list.notes[0].openItemsCount)
        val n = ProtocolJson.decodeFromString(RisiNoteReply.serializer(), fixture("risi_note_reply.json")).note
        assertEquals(RisiItemStates.DONE, n.items[1].state)
        assertEquals("accepted", n.events.single().myStatus)
        val c = ProtocolJson.decodeFromString(RisiCommitmentsReply.serializer(), fixture("risi_commitments_reply_v130.json"))
        assertEquals(noteId, c.commitments[0].noteId)
        assertNull(c.commitments[1].noteId)
    }

    @Test fun noteCardDecodesAsRisiMetaAndAsANote() {
        val r = RisiMessages.meta(row("envelope_risi_note_card.json"))!!
        assertEquals(RisiKinds130.NOTE_CARD, r.kind)
        assertEquals(noteId, r.noteId)
        assertEquals(noteId, r.summaryKey) // = the §27 summary_id
        assertEquals(listOf(me), r.forUsers)
        assertEquals(2, r.items.size)
        val n = card
        assertEquals("interview planning", n.topic)
        assertEquals(3, n.keyPoints.size)
        assertEquals("5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b", n.events.single().eventId)
        assertEquals("2026-10-11T09:41:42.000Z", n.expiresAt)
    }

    @Test fun unknownFieldsAndParticipantObjectsAreTolerated() {
        val m = row("envelope_risi_note_card_future.json")
        val r = RisiMessages.meta(m)!!
        assertEquals(4, r.withUsers.size) // {"user_id"} objects read as ids
        val n = RisiNote.parse(m.systemJson)!!
        assertEquals(RisiNote.SOURCE_CALL, n.source)
        assertNull(n.events.single().myStatus)
        assertTrue(n.items.isEmpty())
    }

    @Test fun notesSavedDecodes() {
        val r = RisiMessages.meta(row("envelope_risi_notes_saved.json"))!!
        assertEquals(RisiKinds130.NOTES_SAVED, r.kind)
        assertEquals(noteId, r.noteId)
        assertEquals(2, r.itemsCount)
        assertEquals(1, r.eventsCount)
        assertTrue(r.notify.isEmpty())
        assertFalse(r.summary!!.contains("Send the interview questions")) // no items/owners/dues in Official
    }

    @Test fun itemReopenIsAValidActionKeyForKey() {
        val built = RisiControl.action(mine, RisiActions130.ITEM_REOPEN)
        assertEquals(ProtocolJson.parseToJsonElement(fixture("envelope_risi_action_item_reopen.json")), built)
        assertTrue(RisiControl.valid(built))
        val env = ProtocolJson.decodeFromString(RisiActionEnvelope.serializer(), fixture("envelope_risi_action_item_reopen.json"))
        assertEquals(RisiActions130.ITEM_REOPEN, env.action)
        assertEquals("You reopened an item", RisiControl.line(ProtocolJson.encodeToString(JsonObject.serializer(), built), "You"))
    }

    // ---- the ledger sees note cards ----

    @Test fun itemUpdatesApplyToTheNoteCardLikeASummary() {
        val c = row("envelope_risi_note_card.json")
        val u = row("envelope_risi_item_update_done.json")
        val views = RisiLedger.items(listOf(c, u))
        assertEquals(RisiItemStates.DONE, views[mine]!!.state)
        assertEquals(noteId, views[mine]!!.summaryId)
        assertEquals(RisiItemStates.PROPOSED, views[hers]!!.state)
        // The update shows no bubble of its own: the card changes.
        assertTrue(RisiLedger.updateHasCard(RisiMessages.meta(u)!!, listOf(c, u)))
        assertEquals(c.messageId, RisiLedger.summaries(listOf(c))[noteId]!!.first.messageId)
        assertEquals(c.messageId, RisiLedger.focusTarget(listOf(c, u), lk.codegen.risime.data.tabs.RisiUiBus.Focus(summaryId = noteId)))
    }

    // ---- title ----

    @Test fun titleIsBuiltPerViewer() {
        assertEquals(
            "Harsha × Shenika · interview planning · Fri 9 Oct",
            RisiNotes.title(me, "Harsha", card.withUsers, nameOf, card.topic, card.endedAt, colombo, Locale.ENGLISH),
        )
        // Shenika's phone: her name first.
        assertEquals(
            "Shenika × Harsha · interview planning · Fri 9 Oct",
            RisiNotes.title(shenika, "Shenika", card.withUsers, nameOf, card.topic, card.endedAt, colombo, Locale.ENGLISH),
        )
        val future = RisiNote.parse(row("envelope_risi_note_card_future.json").systemJson)!!
        assertEquals("Harsha × Shenika +2 · budget · Thu 8 Oct", RisiNotes.title(me, "Harsha", future.withUsers, nameOf, future.topic, future.endedAt, colombo, Locale.ENGLISH))
        // Three people are all named; the date is the viewer's zone (late UTC = next day in Colombo).
        assertEquals(
            "Harsha × Shenika × Kamal · Sat 10 Oct",
            RisiNotes.title(me, "Harsha", listOf(me, shenika, "11111111-2222-4333-8444-555555555555"), nameOf, " ", "2026-10-09T20:00:00Z", colombo, Locale.ENGLISH),
        )
    }

    // ---- ticks ----

    @Test fun controlsFollowTheStateAndTheViewer() {
        val items = RisiNotes.liveItems(card.items, noteId, emptyList(), null)
        val (my, her) = items
        assertEquals(NoteItemControl.Decide, RisiNotes.control(me, my, expired = false, pending = null))
        assertEquals(NoteItemControl.Waiting, RisiNotes.control(me, her, expired = false, pending = null))
        assertEquals(NoteItemControl.Closed("Not tracked"), RisiNotes.control(me, my, expired = true, pending = null))
        val confirmed = my.copy(state = RisiItemStates.CONFIRMED)
        assertEquals(NoteItemControl.Tick(checked = false, enabled = true), RisiNotes.control(me, confirmed, false, null))
        // A counterpart may tick too; someone else sees it read-only.
        assertEquals(NoteItemControl.Tick(false, true), RisiNotes.control(shenika, confirmed, false, null))
        assertEquals(NoteItemControl.Tick(false, false), RisiNotes.control("11111111-2222-4333-8444-555555555555", confirmed, false, null))
        assertEquals(NoteItemControl.Tick(true, true), RisiNotes.control(me, my.copy(state = RisiItemStates.DONE), false, null))
        assertEquals(NoteItemControl.Closed("declined"), RisiNotes.control(me, my.copy(state = RisiItemStates.DECLINED), false, null))
        // Official off / read-only: no buttons.
        assertEquals(NoteItemControl.Tick(false, false), RisiNotes.control(me, confirmed, false, null, readOnly = true))
    }

    @Test fun tickingSendsDoneAndUntickingSendsReopenAndShowsAtOnce() {
        assertEquals("done", RisiNotes.tickAction(true))
        assertEquals("item_reopen", RisiNotes.tickAction(false))
        val confirmed = RisiNotes.liveItems(card.items, noteId, emptyList(), null)[0].copy(state = RisiItemStates.CONFIRMED)
        val now = 1_000_000L
        val pending = RisiNotes.pending(listOf(myAction(mine, "done", now - 1_000)), me, now)
        assertEquals("done", pending[mine])
        // Ticked at once, and off until Risi's item_update arrives.
        assertEquals(NoteItemControl.Tick(true, false), RisiNotes.control(me, confirmed, false, pending[mine]))
        val reopen = RisiNotes.pending(listOf(myAction(mine, "done", now - 5_000), myAction(mine, "item_reopen", now - 1_000)), me, now)
        assertEquals(NoteItemControl.Tick(false, false), RisiNotes.control(me, confirmed.copy(state = RisiItemStates.DONE), false, reopen[mine]))
        // Failed or older than 2 minutes: not pending.
        assertTrue(RisiNotes.pending(listOf(myAction(mine, "done", now - 1_000, MessageStatus.FAILED.name)), me, now).isEmpty())
        assertTrue(RisiNotes.pending(listOf(myAction(mine, "done", now - 3 * 60_000)), me, now).isEmpty())
    }

    @Test fun risisItemUpdateEndsThePendingAction() {
        val now = 1_000_000L
        // ✓ sent, then Risi's item_update (received later): the tick-box is usable at once, not after 2 minutes.
        val confirm = myAction(mine, "confirm", now - 10_000)
        val answered = update(mine, "confirmed").copy(localTs = now - 5_000)
        assertTrue(RisiNotes.pending(listOf(confirm, answered), me, now).isEmpty())
        val confirmed = RisiNotes.liveItems(card.items, noteId, listOf(answered), null)[0]
        assertEquals(NoteItemControl.Tick(false, true), RisiNotes.control(me, confirmed, false, RisiNotes.pending(listOf(confirm, answered), me, now)[mine]))
        // An update from before the action doesn't end it; another item's update doesn't either.
        val older = update(mine, "confirmed").copy(localTs = now - 20_000)
        assertEquals("done", RisiNotes.pending(listOf(older, myAction(mine, "done", now - 1_000)), me, now)[mine])
        val other = update(hers, "confirmed").copy(localTs = now - 500)
        assertEquals("done", RisiNotes.pending(listOf(myAction(mine, "done", now - 1_000), other), me, now)[mine])
    }

    @Test fun cardCopyFollowsEveryItemUpdateInOrder() {
        val rows = listOf(update(mine, "confirmed"), update(mine, "done"), update(mine, "confirmed"), update(hers, "declined"), update("other-item", "done"))
        val live = RisiNotes.liveItems(card.items, noteId, rows, null)
        assertEquals(listOf(RisiItemStates.CONFIRMED, RisiItemStates.DECLINED), live.map { it.state })
        // Untouched fields stay (text, due, all_day of the note's copy).
        assertEquals("Send the interview questions", live[0].text)
        assertEquals("2026-10-09T18:29:59.999Z", live[0].due)
        assertTrue(live[0].allDay)
    }

    @Test fun restCopyAppliesOnlyUpdatesThatCameAfterIt() {
        val rest = ProtocolJson.decodeFromString(RisiNoteReply.serializer(), fixture("risi_note_reply.json")).note
        val old = listOf(update(mine, "proposed"), update(hers, "confirmed")) // already reflected by the reply
        val reflected = RisiNotes.reflectedIds(old)
        assertEquals(listOf(RisiItemStates.CONFIRMED, RisiItemStates.DONE), RisiNotes.liveItems(rest.items, noteId, old, reflected).map { it.state })
        // Ticked in My promises on this or another phone → item_update → the note follows.
        val later = old + update(mine, "done") + update(hers, "confirmed")
        assertEquals(listOf(RisiItemStates.DONE, RisiItemStates.CONFIRMED), RisiNotes.liveItems(rest.items, noteId, later, reflected).map { it.state })
    }

    // ---- share ----

    @Test fun shareTextIsAnOrdinaryMessageEndingWithTheFooter() {
        val items = RisiNotes.liveItems(card.items, noteId, listOf(update(mine, "done")), null)
        val title = RisiNotes.title(me, "Harsha", card.withUsers, nameOf, card.topic, card.endedAt, colombo, Locale.ENGLISH)
        val text = RisiNotes.shareText(title, card, items, me, nameOf, colombo)
        val lines = text.lines()
        assertEquals("Notes: Harsha × Shenika · interview planning · Fri 9 Oct", lines[0])
        assertTrue(lines.contains("• The panel is Harsha and Kamal."))
        assertTrue(lines.contains("Agreed:"))
        assertTrue(lines.contains("☑ Send the interview questions (You · by Fri 9 Oct)"))
        assertTrue(lines.contains("• Book the meeting room (Shenika · by Mon 12 Oct)"))
        assertTrue(lines.contains("• Interview · Mon 12 Oct, 14:00"))
        assertEquals(RisiNotes.SHARE_FOOTER, lines.last())
        // Long notes stay within one message and keep the footer.
        val long = card.copy(keyPoints = List(100) { "x".repeat(150) })
        val clipped = RisiNotes.shareText(title, long, items, me, nameOf, colombo)
        assertTrue(clipped.length <= RisiNotes.MAX_SHARE_CHARS)
        assertTrue(clipped.endsWith(RisiNotes.SHARE_FOOTER))
    }

    @Test fun shareModelSendsThePickedChatAnOrdinaryMessage() {
        val sent = mutableListOf<Pair<String, String>>()
        val share = NoteShareModel(scope, render = { if (it == noteId) "Notes: x\n" + RisiNotes.SHARE_FOOTER else null }, send = { c, t -> sent += c to t; true })
        share.start(noteId)
        assertEquals("Notes: x\n" + RisiNotes.SHARE_FOOTER, share.state.value.text)
        share.pick("dm:a_b", "Kumu")
        assertEquals(listOf("dm:a_b" to "Notes: x\n" + RisiNotes.SHARE_FOOTER), sent)
        assertEquals("Shared with Kumu", share.state.value.notice)
        assertNull(share.state.value.text)
        share.start("missing")
        assertEquals(NOTE_GONE, share.state.value.notice)
        share.startWith("t")
        share.cancel()
        assertNull(share.state.value.text)
        assertEquals(1, sent.size)
    }

    // ---- the list ----

    private class FakeRest(var replies: List<RisiNoteSummary>) : RisiNotesRest {
        val calls = mutableListOf<String>()
        var down = false
        var note: RisiNote? = null
        override suspend fun notes(q: String?, before: String?, limit: Int): ApiResult<RisiNotesReply> {
            calls += "list:${q ?: ""}:${before ?: ""}:$limit"
            if (down) return ApiResult.NetworkError(java.io.IOException("x"))
            val all = replies.filter { q == null || it.topic.contains(q, true) }
            val from = before?.let { b -> all.indexOfFirst { it.noteId == b } + 1 } ?: 0
            val page = all.drop(from).take(1)
            return ApiResult.Ok(RisiNotesReply(page, from + 1 < all.size))
        }
        override suspend fun note(id: String): ApiResult<RisiNoteReply> {
            calls += "get:$id"
            if (down) return ApiResult.NetworkError(java.io.IOException("x"))
            return note?.let { ApiResult.Ok(RisiNoteReply(it)) } ?: ApiResult.Error(404, "not_found", "gone")
        }
        override suspend fun deleteNote(id: String): ApiResult<Unit> {
            calls += "delete:$id"
            if (down) return ApiResult.NetworkError(java.io.IOException("x"))
            replies = replies.filterNot { it.noteId == id }
            return ApiResult.Ok(Unit)
        }
        override suspend fun deleteAllNotes(): ApiResult<Unit> {
            calls += "delete_all"
            replies = emptyList()
            return ApiResult.Ok(Unit)
        }
    }

    private val summaries get() = ProtocolJson.decodeFromString(RisiNotesReply.serializer(), fixture("risi_notes_reply.json")).notes

    @Test fun listLoadsPagesSearchesAndDeletes() {
        val rest = FakeRest(summaries)
        val m = NotesListModel(rest, scope)
        m.load()
        assertEquals(listOf(noteId), m.state.value.notes.map { it.noteId })
        assertTrue(m.state.value.hasMore)
        m.loadMore()
        assertEquals(2, m.state.value.notes.size)
        assertFalse(m.state.value.hasMore)
        assertEquals("list:::30", rest.calls[0]) // first page: no q, no before
        assertEquals("list::$noteId:30", rest.calls[1])
        // Search goes to the server (q), trimmed.
        m.search("  site ")
        assertEquals("list:site::30", rest.calls.last())
        assertEquals(listOf("site visit"), m.state.value.notes.map { it.topic })
        // Delete: from my list only.
        m.delete("b8c9d0e1-f2a3-4b4c-9d5e-6f7a8b9c0d1e")
        assertTrue(m.state.value.notes.isEmpty())
        assertEquals("delete:b8c9d0e1-f2a3-4b4c-9d5e-6f7a8b9c0d1e", rest.calls.last())
        // Delete all asks first.
        m.search("")
        m.askDeleteAll()
        assertTrue(m.state.value.confirmAll)
        m.cancelDeleteAll()
        assertFalse(rest.calls.contains("delete_all"))
        m.askDeleteAll()
        m.confirmDeleteAll()
        assertEquals("delete_all", rest.calls.last())
        assertTrue(m.state.value.notes.isEmpty())
    }

    @Test fun offlineTheListShowsAndSearchesThePhonesOwnCards() {
        val rows = listOf(row("envelope_risi_note_card.json"), row("envelope_risi_note_card_future.json"), update(mine, "confirmed"))
        val rest = FakeRest(summaries).apply { down = true }
        val m = NotesListModel(rest, scope, local = { RisiNotes.localNotes(rows).map { it to RisiNotes.summaryOf(it, rows) } }, nameOf = nameOf)
        m.load()
        assertTrue(m.state.value.local)
        assertEquals(listOf("c9d0e1f2-a3b4-4c5d-8e6f-7a8b9c0d1e2f", noteId), m.state.value.notes.map { it.noteId }) // newest card first
        assertEquals(2, m.state.value.notes[1].openItemsCount) // as the server counts: not done
        // Local search: key points, item texts and participants' names.
        m.search("meeting room")
        assertEquals(listOf(noteId), m.state.value.notes.map { it.noteId })
        m.search("kamal")
        assertEquals(2, m.state.value.notes.size) // a key point of one, a participant of the other
        m.search("2 million")
        assertEquals(listOf("c9d0e1f2-a3b4-4c5d-8e6f-7a8b9c0d1e2f"), m.state.value.notes.map { it.noteId })
        // Nothing is deleted while offline.
        m.loadMore()
        assertFalse(rest.calls.any { it.startsWith("delete") })
    }

    // ---- the note screen ----

    @Test fun noteScreenShowsTheCardThenTheLiveNoteAndSendsTicks() {
        val rest = FakeRest(emptyList()).apply { note = ProtocolJson.decodeFromString(RisiNoteReply.serializer(), fixture("risi_note_reply.json")).note }
        val sent = mutableListOf<String>()
        val m = NoteScreenModel(noteId, rest, scope, act = { id, a, _, _, _ -> sent += "$a:$id"; true }, accept = { sent += "accept:$it"; true })
        val rows = listOf(row("envelope_risi_note_card.json"), update(hers, "confirmed"))
        m.load(card, rows)
        assertTrue(m.state.value.fromServer)
        val v = m.view(rows, me, 0)!!
        assertEquals(listOf(RisiItemStates.CONFIRMED, RisiItemStates.DONE), v.items.map { it.state }) // the reply's, not the older update's
        assertEquals("accepted", v.note.events.single().myStatus)
        m.tick(mine, true)
        m.tick(hers, false)
        m.send(mine, RisiLedger.CONFIRM)
        assertEquals(listOf("done:$mine", "item_reopen:$hers", "item_confirm:$mine"), sent)
        // The item_update that follows the tick changes the screen.
        val after = rows + update(mine, "done")
        assertEquals(RisiItemStates.DONE, m.view(after, me, 0)!!.items[0].state)
        m.acceptEvent("5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b", after)
        assertEquals("accept:5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b", sent.last())
        m.delete()
        assertTrue(m.state.value.deleted)
    }

    @Test fun noteScreenWithoutTheServerOrWhenGone() {
        // Notes off (no REST): the local card only.
        val local = NoteScreenModel(noteId, null, scope, act = { _, _, _, _, _ -> true })
        local.load(card, emptyList())
        assertFalse(local.state.value.loading)
        assertEquals(2, local.view(emptyList(), me, 0)!!.items.size)
        val none = NoteScreenModel(noteId, null, scope, act = { _, _, _, _, _ -> true })
        none.load(null, emptyList())
        assertEquals(NOTE_NOT_HERE, none.state.value.error)
        // Deleted on the server, no card here.
        val gone = NoteScreenModel(noteId, FakeRest(emptyList()), scope, act = { _, _, _, _, _ -> true })
        gone.load(null, emptyList())
        assertEquals(NOTE_GONE, gone.state.value.error)
        assertNull(gone.view(emptyList(), me, 0))
        // Offline with the card: the card, no error.
        val offline = NoteScreenModel(noteId, FakeRest(emptyList()).apply { down = true }, scope, act = { _, _, _, _, _ -> false })
        offline.load(card, emptyList())
        assertNull(offline.state.value.error)
        assertNotNull(offline.view(emptyList(), me, 0))
        offline.tick(mine, true)
        assertNotNull(offline.state.value.notice) // couldn't send: said so
        // Past expires_at: proposed items are "Not tracked".
        val exp = java.time.Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()
        assertTrue(offline.view(emptyList(), me, exp)!!.expired)
    }

    @Test fun countsLine() {
        assertEquals("2 agreed · 1 open · 1 meeting", RisiNotes.countsLine(2, 1, 1))
        assertEquals("1 agreed", RisiNotes.countsLine(1, null, 0))
        assertEquals("0 agreed · 3 meetings", RisiNotes.countsLine(0, null, 3))
        assertEquals("Interview · Mon 12 Oct, 14:00", RisiNotes.eventLine(card.events.single(), colombo))
        assertTrue(RisiNotes.canAccept(card.events.single()))
    }
}
