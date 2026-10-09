package lk.codegen.risime.data.tabs

import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiProgress
import lk.codegen.risime.net.RisiStep
import lk.codegen.risime.net.RisiTextEnvelope
import lk.codegen.risime.net.RisiToolCall
import lk.codegen.risime.net.RisiToolResult
import lk.codegen.risime.net.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** §25 A7: the switch, the stub tool-call handler, progress, step labels and the v1.25 card rules. */
class RisiToolsTest {
    private fun read(name: String): String =
        javaClass.classLoader!!.getResource("contract/v1/examples/$name")?.readText() ?: error("missing $name")

    private val dev = "c0a80101-0000-4000-8000-000000000001"
    private val asker = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"

    private fun call(name: String) = ProtocolJson.decodeFromString<Event>(read(name)).risiToolCall()!!
    private fun ms(ts: String) = Instant.parse(ts).toEpochMilli()

    @Test fun switchIsOnOnlyWithServerAndAdvertisement() {
        val s = RisiToolsSwitch()
        assertFalse(s.on.value)
        s.setServerOn(true)
        assertFalse(s.on.value)
        s.setAdvertised(true)
        assertTrue(s.on.value)
        s.restoreAdvertised(false) // a registration in this process wins
        assertTrue(s.on.value)
        s.setServerOn(false)
        assertFalse(s.on.value)
    }

    private class Posted { val list = mutableListOf<Triple<String, RisiToolResult, String>>() }

    private fun handler(posted: Posted, now: Long, enabled: Boolean = true, device: String? = dev) = RisiToolCallHandler(
        deviceId = { device },
        post = { id, r, d -> posted.list += Triple(id, r, d); ApiResult.Ok(Unit) },
        enabled = { enabled },
        now = { now },
    )

    @Test fun stubDeclinesKnownToolsOnceAndOnlyForThisDevice() = runBlocking {
        val check = call("event_risi_tool_call_calendar_check.json")
        val add = call("event_risi_tool_call_calendar_add.json")
        val p = Posted()
        val h = handler(p, ms(check.serverTs!!))
        h.handle(check)
        h.handle(check) // replayed: answered once
        h.handle(add.copy(expiresAt = check.expiresAt))
        assertEquals(listOf(check.toolCallId, add.toolCallId), p.list.map { it.first })
        assertTrue(p.list.all { it.second == RisiToolResult.declined() && it.third == dev })
    }

    @Test fun unknownToolIsAnError() = runBlocking {
        val c = call("event_risi_tool_call_calendar_check.json").copy(tool = "contacts_read")
        val p = Posted()
        handler(p, ms(c.serverTs!!)).handle(c)
        assertEquals("""{"status":"error","result":{"code":"unknown_tool"}}""", ProtocolJson.encodeToString(RisiToolResult.serializer(), p.list.single().second))
    }

    @Test fun expiredOtherDeviceOrSwitchedOffIsIgnored() = runBlocking {
        val c = call("event_risi_tool_call_calendar_check.json")
        val p = Posted()
        handler(p, ms(c.expiresAt)).handle(c) // at the deadline
        handler(p, ms(c.serverTs!!), device = "c0a80101-0000-4000-8000-000000000002").handle(c)
        handler(p, ms(c.serverTs!!), enabled = false).handle(c)
        handler(p, ms(c.serverTs!!), device = null).handle(c)
        assertTrue(p.list.isEmpty())
    }

    @Test fun networkFailureMayBeRetried() = runBlocking {
        val c = call("event_risi_tool_call_calendar_check.json")
        var n = 0
        val h = RisiToolCallHandler({ dev }, { _, _, _ -> n++; if (n == 1) ApiResult.NetworkError(java.io.IOException()) else ApiResult.Ok(Unit) }, { true }, { ms(c.serverTs!!) })
        h.handle(c)
        h.handle(c)
        h.handle(c)
        assertEquals(2, n)
    }

    @Test fun anOlderServerRefusingTheCalendarFieldGetsTheV125Result() = runBlocking {
        val c = call("event_risi_tool_call_calendar_add.json")
        val sent = mutableListOf<String>()
        val full = RisiToolResult(RisiToolResult.OK, ProtocolJson.parseToJsonElement("""{"event_id":"7","calendar":{"name":"Google Calendar","account":"a@b.c"}}""") as kotlinx.serialization.json.JsonObject)
        val h = RisiToolCallHandler(
            { dev },
            { _, r, _ -> sent += r.result.toString(); if ("calendar" in r.result!!) ApiResult.Error(422, "bad_request", "") else ApiResult.Ok(Unit) },
            { true }, { ms(c.serverTs!!) }, execute = { full },
        )
        h.handle(c)
        assertEquals(listOf(full.result.toString(), """{"event_id":"7"}"""), sent)
    }

    @Test fun progressDropsLowerSeqAndEndsOnDone() {
        val base = ProtocolJson.decodeFromString<Signal>(read("signal_risi_progress.json")).risiProgress()!!
        var now = 1_000L
        val s = RisiProgressStore { now }
        s.apply(base)
        s.apply(base.copy(seq = 2, state = RisiProgress.WORKING, step = null))
        val shown = s.byRequest.value[base.requestId]!!
        assertEquals(3, shown.progress.seq)
        assertEquals("Checking your calendar…", RisiStepLabels.of(shown, now))
        assertEquals("Still working…", RisiStepLabels.of(shown, now + RisiProgressStore.STILL_WORKING_MS))
        s.apply(base.copy(seq = 4, state = RisiProgress.QUEUED, position = 2, step = null))
        assertEquals("Queued (2)…", RisiStepLabels.of(s.byRequest.value[base.requestId]!!, now))
        s.apply(base.copy(seq = 5, state = RisiProgress.DONE, step = null))
        assertTrue(s.byRequest.value.isEmpty())
    }

    @Test fun stepLabels() {
        assertEquals("Checking your calendar…", RisiStepLabels.tool("calendar_check"))
        assertEquals("Setting a reminder…", RisiStepLabels.tool("set_reminder"))
        assertEquals("Working…", RisiStepLabels.tool("teleport"))
        assertEquals("Working…", RisiStepLabels.tool(null))
    }

    private fun risi(name: String) = ProtocolJson.decodeFromString<RisiTextEnvelope>(read(name)).risi!!

    private fun ctl(from: String, target: String, action: String, ts: Long = 0) = MessageEntity(
        clientMsgId = "c-$from-$action-$ts", messageId = null, conversationId = "grp:x", from = from, to = "grp:x", body = "",
        serverTs = null, localTs = ts, status = "READ", outgoing = false, kind = MessageEntity.KIND_RISI_CTL,
        systemJson = ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), RisiControl.action(target, action)),
    )

    @Test fun confirmCardOnlyForItsAskerAndUntilExpiry() {
        val r = risi("envelope_risi_confirm.json")
        val before = ms(r.expiresAt!!) - 1
        val open = RisiToolCards.confirmState(r, emptyList(), before)
        assertEquals(RisiToolCards.ConfirmState.OPEN, open)
        assertEquals(listOf("add", "cancel"), RisiToolCards.confirmButtons(asker, r, open, emptySet()))
        assertTrue(RisiToolCards.confirmButtons("someone-else", r, open, emptySet()).isEmpty())
        assertTrue(RisiToolCards.confirmButtons(asker, r, open, setOf(r.writeId!!)).isEmpty())
        assertEquals(RisiToolCards.ConfirmState.EXPIRED, RisiToolCards.confirmState(r, emptyList(), ms(r.expiresAt!!)))
        // A forged confirm from someone not in `for` is ignored; the asker's decides.
        assertEquals(RisiToolCards.ConfirmState.OPEN, RisiToolCards.confirmState(r, listOf(ctl("intruder", r.writeId!!, "confirm_write")), before))
        assertEquals(RisiToolCards.ConfirmState.CONFIRMED, RisiToolCards.confirmState(r, listOf(ctl(asker, r.writeId!!, "confirm_write")), before))
        assertEquals(RisiToolCards.ConfirmState.CANCELLED, RisiToolCards.confirmState(r, listOf(ctl(asker, r.writeId!!, "cancel_write")), before))
    }

    @Test fun reminderParticipantsFollowHumanMeTooAndNotMe() {
        val r = risi("envelope_risi_reminder_set.json")
        val rid = r.reminderId!!
        val agent = "9e1f0000-0000-4000-8000-000000000001"
        val ps = RisiToolCards.reminderParticipants(
            r,
            listOf(ctl("kamal", rid, "me_too"), ctl("nimal", rid, "me_too"), ctl("nimal", rid, "not_me"), ctl(agent, rid, "me_too"), ctl("x", "other", "me_too")),
            setOf(agent),
        )
        assertEquals(listOf(asker, "kamal"), ps)
        val before = ms(r.reminderWhen()!!) - 1
        assertEquals(listOf("not_me"), RisiToolCards.reminderButtons("kamal", r, ps, before))
        assertEquals(listOf("me_too"), RisiToolCards.reminderButtons("nimal", r, ps, before))
        assertTrue(RisiToolCards.reminderButtons("nimal", r, ps, ms(r.reminderWhen()!!)).isEmpty())
        assertTrue(RisiToolCards.reminderButtons("nimal", r.copy(meToo = false), ps, before).isEmpty())
    }

    @Test fun draftUseOnlyWhenTheTargetIsOnThePhone() {
        val r = risi("envelope_risi_draft.json")
        assertTrue(RisiToolCards.draftUsable(r) { it == r.targetConversationId })
        assertFalse(RisiToolCards.draftUsable(r) { false })
        assertFalse(RisiToolCards.draftUsable(r.copy(targetConversationId = null)) { true })
    }

    @Test fun v125ActionsAreValidControlsWithLines() {
        for (a in listOf("confirm_write", "cancel_write", "me_too", "not_me")) {
            val o = RisiControl.action("t", a)
            assertTrue(a, RisiControl.valid(o))
            val json = ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), o)
            assertTrue(a, RisiControl.line(json, "Kamal")!!.startsWith("Kamal"))
            assertEquals(a, RisiControl.actionOf(json))
        }
        // The example re-encodes exactly.
        assertEquals(ProtocolJson.parseToJsonElement(read("envelope_risi_action_me_too.json")), RisiControl.action("6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9b", "me_too"))
    }

    @Test fun answerV2StepsAndErrors() {
        val r = risi("envelope_risi_answer_v2.json")
        assertEquals(RisiStep("calendar_check", "ok"), r.steps.first())
        assertNull(r.confirmWhen())
        assertEquals("I couldn't reach your phone to add it.", RisiCards.errorText("tool_timeout"))
        assertTrue(RisiCards.errorText("queue_overflow").contains("waiting"))
    }

    @Test fun toolCallEventIsNeverAMessage() {
        val e = ProtocolJson.decodeFromString<Event>(read("event_risi_tool_call_calendar_add.json"))
        assertEquals(Event.KIND_RISI_TOOL_CALL, e.kind)
        assertNull(e.messageData())
        assertEquals(RisiToolCall.TOOL_CALENDAR_ADD, e.risiToolCall()!!.tool)
    }
}
