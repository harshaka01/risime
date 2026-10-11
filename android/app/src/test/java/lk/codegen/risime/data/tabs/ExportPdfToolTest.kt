package lk.codegen.risime.data.tabs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.ExportPdfArgs
import lk.codegen.risime.net.ExportPdfResult
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * §33.15 / §33.20 gate 10: the phone runs `export_pdf` only for the agent's confirm card with this
 * `write_id` and `tool: "export_pdf"`, the user's own `confirm_write`, args equal to the card's `export`,
 * an unused `write_id`; a forged `write_id` is `declined`.
 */
class ExportPdfToolTest {
    private fun read(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val risiUser = "9e1f0000-0000-4000-8000-000000000001"
    private val conv = "grp:2f3a4b5c-6d7e-4f8a-9b0c-1d2e3f4a5b6c"
    private val rows = mutableListOf<MessageEntity>()
    private val ran = mutableListOf<ExportPdfArgs>()
    private val dao = FakeScheduledDao()
    private val now = Instant.parse("2026-10-11T09:18:12Z").toEpochMilli()
    private val scheduled = ScheduledMessages(dao, FakeArmer(), send = { _, _, _ -> true }, isMember = { true }, now = { now }, zone = { ZoneId.of("Asia/Colombo") })
    private val ex = RisiToolExecutor(
        me = { me }, history = { if (it.equals(conv, true)) rows else emptyList() }, risiChats = { listOf(conv) }, skillState = { "off" },
        dao = dao, scheduled = scheduled, setAlarm = { true }, now = { now },
        exportPdf = { a -> ran += a; RisiToolResult(RisiToolResult.OK, ProtocolJson.encodeToJsonElement(ExportPdfResult.serializer(), ExportPdfResult("sent", 3)) as JsonObject) },
    )
    private var n = 0

    private fun card(edit: (JsonObject) -> JsonObject = { it }) {
        val risi = edit((ProtocolJson.parseToJsonElement(read("envelope_risi_confirm_export_pdf.json")) as JsonObject)["risi"] as JsonObject)
        rows += MessageEntity("card${n++}", "m$n", conv, risiUser, conv, "", "2026-10-11T09:15:30.456Z", n.toLong(), "READ", false, systemJson = RisiMessages.encode(risi))
    }

    private fun confirm(writeId: String, action: String = "confirm_write") {
        rows += MessageEntity(
            "ctl${n++}", "m$n", conv, me, conv, "", "2026-10-11T09:17:00.000Z", n.toLong(), "READ", true,
            kind = MessageEntity.KIND_RISI_CTL, systemJson = String(RisiControl.encode(RisiControl.action(writeId, action))),
        )
    }

    private val call get() = ProtocolJson.decodeFromString<Event>(read("event_risi_tool_call_export_pdf.json")).risiToolCall()!!

    @Test fun confirmedCardRunsOnceAndReportsSent() = runBlocking {
        card()
        confirm(call.writeId!!)
        val r = ex.execute(call)
        assertEquals(RisiToolResult.OK, r.status)
        assertEquals("""{"state":"sent","pages":3}""", r.result.toString())
        assertEquals(1, ran.size)
        // The write_id is used: never twice.
        assertEquals(RisiToolResult.DECLINED, ex.execute(call.copy(toolCallId = "again")).status)
        // Also on a v1.25-style device path (pdf_export is gated by the capability, not a skill).
        assertEquals(RisiToolResult.DECLINED, ex.executeV125(call.copy(toolCallId = "again2")).status)
        assertEquals(1, ran.size)
    }

    @Test fun aForgedWriteIdIsDeclined() = runBlocking {
        card()
        confirm(call.writeId!!)
        val forged = "aaaaaaaa-6f70-4812-93a4-b5c6d7e8f901"
        val c = call.copy(args = JsonObject(call.args + ("write_id" to JsonPrimitive(forged))))
        assertEquals(RisiToolResult.DECLINED, ex.execute(c).status)
        assertTrue(ran.isEmpty())
    }

    @Test fun argsThatDifferFromTheCardsExportAreDeclined() = runBlocking {
        card()
        confirm(call.writeId!!)
        val other = call.copy(args = JsonObject(call.args + ("conversation_id" to JsonPrimitive("grp:00000000-0000-4000-8000-000000000000"))))
        assertEquals(RisiToolResult.DECLINED, ex.execute(other).status)
        assertTrue(ran.isEmpty())
    }

    @Test fun noConfirmOrACancelIsDeclined() = runBlocking {
        card()
        assertEquals(RisiToolResult.DECLINED, ex.execute(call).status)
        confirm(call.writeId!!, "cancel_write")
        confirm(call.writeId!!)
        assertEquals(RisiToolResult.DECLINED, ex.execute(call).status)
        assertTrue(ran.isEmpty())
    }

    @Test fun aCardForSomeoneElseIsDeclined() = runBlocking {
        card { JsonObject(it + ("for" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"))))) }
        confirm(call.writeId!!)
        assertEquals(RisiToolResult.DECLINED, ex.execute(call).status)
    }
}
