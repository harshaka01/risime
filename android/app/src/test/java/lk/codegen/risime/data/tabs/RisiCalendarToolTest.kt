package lk.codegen.risime.data.tabs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiSkillStates
import lk.codegen.risime.net.RisiToolCall
import lk.codegen.risime.net.RisiToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * P0 (Harsha's phone): the real calendar tools. `calendar_add` runs only after the card rule (v1.26
 * `args`, or the v1.25 `text`/`when`) or the Allowed rule, goes into the Google calendar, is read back,
 * answers exactly `{event_id}`; a failure answers `no_permission` / `calendar_unavailable` and releases
 * the `write_id`; `calendar_check` gives merged free/busy only; `calendar_remove` undoes only this phone's add.
 */
class RisiCalendarToolTest {
    private fun read(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val other = "3b4c5d6e-7f8a-4b9c-8d0e-1f2a3b4c5d6e"
    private val risiUser = "9e1f0000-0000-4000-8000-000000000001"

    private val rows = mutableMapOf<String, MutableList<MessageEntity>>()
    private val states = mutableMapOf("calendar" to RisiSkillStates.ASK)
    private val dao = FakeScheduledDao()
    private val be = FakeCalendarBackend().apply {
        cals += PhoneCalendarInfo(3, "Holidays", "harsha@example.com", "com.google", 200, true)
        cals += PhoneCalendarInfo(5, "Phone", "Phone", "LOCAL", 700, true)
        cals += PhoneCalendarInfo(1, "harsha@example.com", "harsha@example.com", "com.google", 700, true, "harsha@example.com", true)
    }
    private val writes = CalendarWriteLog()
    private val cal = PhoneCalendar(be, MemoryCalendarChoice(), writes, zone = { ZoneId.of("Asia/Colombo") })
    private val scheduled = ScheduledMessages(dao, FakeArmer(), send = { _, _, _ -> true }, isMember = { true })

    private val ex = RisiToolExecutor(
        me = { me }, history = { rows[it].orEmpty() }, risiChats = { emptyList() }, skillState = { states[it] ?: RisiSkillStates.OFF },
        dao = dao, scheduled = scheduled, setAlarm = { true }, calendar = cal,
    )

    private fun call(name: String) = ProtocolJson.decodeFromString<Event>(read(name)).risiToolCall()!!

    private val add get() = call("event_risi_tool_call_calendar_add.json")
    private val conv get() = add.conversationId!!

    private var n = 0

    private fun card(name: String, edit: (JsonObject) -> JsonObject = { it }) {
        val risi = edit((ProtocolJson.parseToJsonElement(read(name)) as JsonObject)["risi"] as JsonObject)
        rows.getOrPut(conv) { mutableListOf() } += MessageEntity(
            clientMsgId = "card${n++}", messageId = "m$n", conversationId = conv, from = risiUser, to = conv, body = "", serverTs = "2026-10-09T09:16:00.000Z",
            localTs = n.toLong(), status = "READ", outgoing = false, systemJson = RisiMessages.encode(risi),
        )
    }

    /** The v1.26 card: the v1.25 example plus `skill_id` and the call's exact `args` (minus `write_id`). */
    private fun v126Card() = card("envelope_risi_confirm.json") { JsonObject(it + ("skill_id" to JsonPrimitive("calendar")) + ("args" to RisiToolExecutor.argsWithoutWriteId(add))) }

    private fun ctl(from: String, json: JsonObject, serverTs: String = "2026-10-09T09:17:00.000Z") {
        rows.getOrPut(conv) { mutableListOf() } += MessageEntity(
            clientMsgId = "ctl${n++}", messageId = "m$n", conversationId = conv, from = from, to = conv, body = "", serverTs = serverTs,
            localTs = n.toLong(), status = "READ", outgoing = from == me, kind = MessageEntity.KIND_RISI_CTL, systemJson = String(RisiControl.encode(json)),
        )
    }

    private fun confirm(from: String = me) = ctl(from, RisiControl.action(add.writeId!!, "confirm_write"))

    @Test fun addAfterMyConfirmGoesToGoogleAndIsVerified() = runBlocking {
        v126Card()
        confirm()
        val r = ex.execute(add)
        assertEquals(RisiToolResult.OK, r.status)
        val ev = be.events.values.single()
        // Exactly the contract's schema on the wire.
        assertEquals("""{"event_id":"${ev.id}"}""", r.result.toString())
        assertEquals(1L, ev.calendarId)
        assertEquals("Dentist", ev.title)
        assertEquals(Instant.parse("2026-10-16T04:30:00Z").toEpochMilli(), ev.dtStart)
        assertEquals(ev.id.toString(), dao.writes[add.writeId!!]!!.target)
        assertEquals("com.google", writes.get(add.writeId!!)!!.accountType)
        // A write_id runs once.
        assertEquals(RisiToolResult.DECLINED, ex.execute(add.copy(toolCallId = "again")).status)
        assertEquals(1, be.events.size)
    }

    @Test fun aV125CardWithoutArgsMatchesOnTextAndWhen() = runBlocking {
        card("envelope_risi_confirm.json")
        confirm()
        assertEquals(RisiToolResult.OK, ex.executeV125(add).status)
        assertEquals(1, be.events.size)
    }

    @Test fun noCardOtherPeoplesConfirmOrDifferentArgsAddNothing() = runBlocking {
        assertEquals(RisiToolResult.DECLINED, ex.execute(add).status)
        v126Card()
        assertEquals(RisiToolResult.DECLINED, ex.execute(add).status)
        confirm(other)
        assertEquals(RisiToolResult.DECLINED, ex.execute(add).status)
        confirm()
        val forged = add.copy(args = JsonObject(add.args + ("title" to JsonPrimitive("Something else"))))
        assertEquals(RisiToolResult.DECLINED, ex.execute(forged).status)
        states["calendar"] = RisiSkillStates.OFF
        assertEquals(RisiToolResult.DECLINED, ex.execute(add).status)
        assertTrue(be.events.isEmpty())
    }

    @Test fun allowedRunsWithMyOwnRecentRequest() = runBlocking {
        states["calendar"] = RisiSkillStates.ALLOWED
        ctl(me, RisiControl.request(add.requestId!!, "ask", "add dentist to my calendar", null), "2026-10-09T09:17:50.000Z")
        assertEquals(RisiToolResult.OK, ex.execute(add).status)
        assertEquals(1, be.events.size)
        assertEquals(add.requestId, writes.get(add.writeId!!)!!.requestId)
    }

    @Test fun failuresAnswerTheirStatusKeepTheReasonAndReleaseTheWrite() = runBlocking {
        v126Card()
        confirm()
        be.write = false
        assertEquals(RisiToolResult(RisiToolResult.NO_PERMISSION, null), ex.execute(add))
        assertEquals("NO_PERMISSION", writes.get(add.writeId!!)!!.failure)
        be.write = true
        be.refuseInsert = true
        assertEquals("""{"code":"calendar_unavailable"}""", ex.execute(add).result.toString())
        assertEquals("INSERT_FAILED", writes.get(add.writeId!!)!!.failure)
        assertNull(dao.writes[add.writeId!!])
        // Only a local calendar left: never written without the user's pick.
        be.refuseInsert = false
        be.cals.removeAll { it.accountType == "com.google" }
        assertEquals("""{"code":"calendar_unavailable"}""", ex.execute(add).result.toString())
        assertEquals("NO_GOOGLE_CALENDAR", writes.get(add.writeId!!)!!.failure)
        // [Retry] after the user picked the local calendar: it runs (the write was released).
        cal.choose(5)
        assertEquals(RisiToolResult.OK, ex.execute(add).status)
        assertEquals(5L, be.events.values.single().calendarId)
    }

    @Test fun checkGivesMergedFreeBusyOnly() = runBlocking {
        val c = call("event_risi_tool_call_calendar_check.json")
        be.extra += BusyRow(Instant.parse("2026-10-13T04:00:00Z").toEpochMilli(), Instant.parse("2026-10-13T05:00:00Z").toEpochMilli(), false, true)
        val r = ex.execute(c)
        assertEquals("""{"blocks":[{"start":"2026-10-13T04:00:00Z","end":"2026-10-13T05:00:00Z","busy":true,"all_day":false}]}""", r.result.toString())
        val wide = c.copy(args = JsonObject(mapOf("from" to JsonPrimitive("2026-10-01T00:00:00Z"), "to" to JsonPrimitive("2026-10-16T00:00:01Z"))))
        assertEquals("""{"code":"bad_args"}""", ex.execute(wide).result.toString())
        be.read = false
        assertEquals(RisiToolResult.NO_PERMISSION, ex.execute(c).status)
        states["calendar"] = RisiSkillStates.OFF
        be.read = true
        assertEquals(RisiToolResult.DECLINED, ex.execute(c).status)
    }

    @Test fun undoRemovesOnlyWhatThisPhoneAdded() = runBlocking {
        val undo = call("event_risi_tool_call_calendar_remove.json")
        assertEquals("""{"removed":false,"reason":"not_found"}""", ex.execute(undo).result.toString())
        v126Card()
        confirm()
        ex.execute(add)
        assertEquals("""{"removed":true,"reason":null}""", ex.execute(undo).result.toString())
        assertTrue(be.events.isEmpty())
        assertTrue(writes.get(add.writeId!!)!!.removed)
        // Already gone (the user deleted it): not_found.
        assertEquals("""{"removed":false,"reason":"not_found"}""", ex.execute(undo).result.toString())
        // Without undo_entry_id: never.
        assertEquals(RisiToolResult.DECLINED, ex.execute(undo.copy(undoEntryId = null)).status)
    }

    @Test fun chipsThatAreQuestionsOrConfirmPhrasesAreHidden() {
        for (bad in listOf("Confirm to add the event", "Yes, add it", "What is on Monday?", "Is that OK", "Cancel", "OK", "Add it", "Should I add a reminder", "“Confirm”")) {
            assertTrue(bad, !RisiSkillCards.chipUsable(bad))
        }
        for (good in listOf("Remind me 30 min before", "Show my week", "Ask me to add it to your calendar", "Note it for Shenika", "Issue the invoice")) {
            assertTrue(good, RisiSkillCards.chipUsable(good))
        }
    }

    @Suppress("unused")
    private val tools = RisiToolCall.TOOL_CALENDAR_ADD
}
