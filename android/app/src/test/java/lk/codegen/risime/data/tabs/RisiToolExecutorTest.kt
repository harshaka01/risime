package lk.codegen.risime.data.tabs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiSkillStates
import lk.codegen.risime.net.RisiToolCall
import lk.codegen.risime.net.RisiToolResult
import lk.codegen.risime.net.SetAlarmArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * §25.3/§26.3/§26.4 A14: the phone's acceptance rules for Risi's client tools: (a) the card's `args`
 * equal the call's exactly and the user's own `confirm_write`; (b) Allowed = the phone's own record
 * plus the user's own `risi_request` ≤ 10 min before; a `write_id` once; undo only against the local record.
 */
class RisiToolExecutorTest {
    private fun read(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val other = "3b4c5d6e-7f8a-4b9c-8d0e-1f2a3b4c5d6e"
    private val risiUser = "9e1f0000-0000-4000-8000-000000000001"
    private val conv = "grp:9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d" // the turn's (and here the Risi chat's) conversation
    private fun ms(ts: String) = Instant.parse(ts).toEpochMilli()

    private val rows = mutableMapOf<String, MutableList<MessageEntity>>()
    private val states = mutableMapOf("alarm" to RisiSkillStates.ASK, "scheduled_messages" to RisiSkillStates.ASK)
    private val dao = FakeScheduledDao()
    private val armer = FakeArmer()
    private val alarms = mutableListOf<SetAlarmArgs>()
    private var now = ms("2026-10-09T15:10:00Z")
    private val scheduled = ScheduledMessages(dao, armer, send = { _, _, _ -> true }, isMember = { true }, now = { now }, zone = { ZoneId.of("Asia/Colombo") })

    private val ex = RisiToolExecutor(
        me = { me }, history = { rows[it].orEmpty() }, risiChats = { listOf(conv) }, skillState = { states[it] ?: RisiSkillStates.OFF },
        dao = dao, scheduled = scheduled, setAlarm = { alarms += it; true }, now = { now },
    )

    private fun call(name: String) = ProtocolJson.decodeFromString<Event>(read(name)).risiToolCall()!!

    private var n = 0

    /** The agent's card as the pipeline stores it (honoured `risi`, in system_json). */
    private fun card(name: String, edit: (JsonObject) -> JsonObject = { it }) {
        val risi = edit((ProtocolJson.parseToJsonElement(read(name)) as JsonObject)["risi"] as JsonObject)
        rows.getOrPut(conv) { mutableListOf() } += MessageEntity(
            clientMsgId = "card${n++}", messageId = "m$n", conversationId = conv, from = risiUser, to = conv, body = "", serverTs = "2026-10-09T15:09:00.000Z",
            localTs = n.toLong(), status = "READ", outgoing = false, systemJson = RisiMessages.encode(risi),
        )
    }

    private fun ctl(from: String, json: JsonObject, serverTs: String = "2026-10-09T15:09:30.000Z") {
        rows.getOrPut(conv) { mutableListOf() } += MessageEntity(
            clientMsgId = "ctl${n++}", messageId = "m$n", conversationId = conv, from = from, to = conv, body = "", serverTs = serverTs,
            localTs = n.toLong(), status = "READ", outgoing = from == me, kind = MessageEntity.KIND_RISI_CTL, systemJson = String(RisiControl.encode(json)),
        )
    }

    private fun confirm(from: String, writeId: String, action: String = "confirm_write") = ctl(from, RisiControl.action(writeId, action))

    private fun request(from: String, requestId: String, serverTs: String) = ctl(from, RisiControl.request(requestId, "ask", "wake me up at 5:30", null), serverTs)

    private val alarmCall get() = call("event_risi_tool_call_set_alarm.json")
    private val scheduleCall get() = call("event_risi_tool_call_schedule_message.json")

    private fun status(r: RisiToolResult) = r.status

    @Test fun alarmWithACardWhoseArgsMatchAndMyConfirm() = runBlocking {
        card("envelope_risi_confirm_set_alarm.json")
        confirm(me, alarmCall.writeId!!)
        val r = ex.execute(alarmCall)
        assertEquals(RisiToolResult.OK, status(r))
        assertEquals("""{"alarm_set":true}""", r.result.toString())
        assertEquals(listOf(SetAlarmArgs("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", "05:30", "Wake up", null)), alarms)
        // The same write_id never runs twice.
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(alarmCall.copy(toolCallId = "again"))))
        assertEquals(1, alarms.size)
    }

    @Test fun argsThatDifferFromTheCardAreDeclined() = runBlocking {
        card("envelope_risi_confirm_set_alarm.json")
        confirm(me, alarmCall.writeId!!)
        val forged = alarmCall.copy(args = JsonObject(alarmCall.args + ("time" to JsonPrimitive("04:30"))))
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(forged)))
        val extra = alarmCall.copy(args = JsonObject(alarmCall.args + ("label" to JsonPrimitive("Wake up!"))))
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(extra)))
        assertTrue(alarms.isEmpty())
    }

    @Test fun noConfirmSomeoneElsesConfirmOrACancelFirstIsDeclined() = runBlocking {
        card("envelope_risi_confirm_set_alarm.json")
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(alarmCall)))
        confirm(other, alarmCall.writeId!!) // not my leaf
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(alarmCall)))
        confirm(me, alarmCall.writeId!!, "cancel_write")
        confirm(me, alarmCall.writeId!!) // the first answer decides
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(alarmCall)))
        assertTrue(alarms.isEmpty())
    }

    @Test fun aRevokedSkillVoidsTheCard() = runBlocking {
        card("envelope_risi_confirm_set_alarm.json")
        confirm(me, alarmCall.writeId!!)
        states["alarm"] = RisiSkillStates.OFF
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(alarmCall)))
    }

    @Test fun allowedRunsWithoutACardOnlyForMyOwnRecentRequest() = runBlocking {
        states["alarm"] = RisiSkillStates.ALLOWED
        // No request at all: declined.
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(alarmCall)))
        // Someone else's request with that id: declined.
        request(other, alarmCall.requestId!!, "2026-10-09T15:09:50.000Z")
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(alarmCall)))
        rows.clear()
        // My own request, more than 10 minutes before server_ts: declined.
        request(me, alarmCall.requestId!!, "2026-10-09T14:59:00.000Z")
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(alarmCall)))
        rows.clear()
        request(me, alarmCall.requestId!!, "2026-10-09T15:09:50.000Z")
        assertEquals(RisiToolResult.OK, status(ex.execute(alarmCall)))
        assertEquals(1, alarms.size)
    }

    @Test fun askNeedsTheCardEvenWithMyRequest() = runBlocking {
        request(me, alarmCall.requestId!!, "2026-10-09T15:09:50.000Z")
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(alarmCall)))
    }

    @Test fun scheduleIsNeverAllowedWithoutItsCard() = runBlocking {
        states["scheduled_messages"] = RisiSkillStates.ALLOWED // can't be set on the server; the phone refuses it anyway
        request(me, scheduleCall.requestId!!, "2026-10-09T15:09:50.000Z")
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(scheduleCall)))
        card("envelope_risi_confirm_schedule_message.json")
        confirm(me, scheduleCall.writeId!!)
        val r = ex.execute(scheduleCall)
        assertEquals(RisiToolResult.OK, status(r))
        val id = r.result!!["schedule_id"]!!.let { (it as JsonPrimitive).content }
        assertEquals("Good morning", dao.get(id)!!.text)
        assertEquals("schedule_message", dao.writes[scheduleCall.writeId!!]!!.tool)
    }

    @Test fun undoCancelsOnlyAScheduleThisPhoneHolds() = runBlocking {
        card("envelope_risi_confirm_schedule_message.json")
        confirm(me, scheduleCall.writeId!!)
        val id = (ex.execute(scheduleCall).result!!["schedule_id"] as JsonPrimitive).content
        val undo = call("event_risi_tool_call_cancel_scheduled.json")
        // Unknown to this phone.
        assertEquals("""{"cancelled":false,"reason":"unknown"}""", ex.execute(undo).result.toString())
        val mine = undo.copy(args = JsonObject(undo.args + ("schedule_id" to JsonPrimitive(id))))
        assertEquals("""{"cancelled":true,"reason":null}""", ex.execute(mine).result.toString())
        assertEquals("cancelled", dao.get(id)!!.state)
    }

    @Test fun aCancelByRequestNeedsItsCard() = runBlocking {
        val c = call("event_risi_tool_call_cancel_scheduled.json").let { it.copy(undoEntryId = null, args = JsonObject(it.args + ("write_id" to JsonPrimitive("w-cancel")))) }
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(c.copy(conversationId = conv))))
    }

    @Test fun calendarRemoveFindsNothingItDidNotAdd() = runBlocking {
        assertEquals("""{"removed":false,"reason":"not_found"}""", ex.execute(call("event_risi_tool_call_calendar_remove.json")).result.toString())
    }

    @Test fun badArgsAndUnknownTools() = runBlocking {
        val bad = alarmCall.copy(args = JsonObject(alarmCall.args + ("time" to JsonPrimitive("25:00"))))
        assertEquals("""{"code":"bad_args"}""", ex.execute(bad).result.toString())
        val days = alarmCall.copy(args = JsonObject(alarmCall.args + ("days" to ProtocolJson.parseToJsonElement("[0]"))))
        assertEquals("""{"code":"bad_args"}""", ex.execute(days).result.toString())
        assertEquals("""{"code":"unknown_tool"}""", ex.execute(alarmCall.copy(tool = "contacts_read")).result.toString())
        assertEquals(RisiToolResult.DECLINED, status(ex.execute(call("event_risi_tool_call_calendar_add.json"))))
    }

    @Test fun weekdaysMapToCalendarConstants() {
        assertEquals(java.util.Calendar.MONDAY, isoToCalendarDay(1))
        assertEquals(java.util.Calendar.SATURDAY, isoToCalendarDay(6))
        assertEquals(java.util.Calendar.SUNDAY, isoToCalendarDay(7))
    }

    @Suppress("unused")
    private fun JsonObject.obj(k: String) = this[k]!!.jsonObject
}
