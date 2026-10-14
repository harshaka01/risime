package ai.risicloud.risime.data.tabs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.net.Event
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.RisiSend149
import ai.risicloud.risime.net.RisiTextEnvelope
import ai.risicloud.risime.net.RisiToolResult
import ai.risicloud.risime.net.SendMessageArgs
import ai.risicloud.risime.net.SendMessageResult
import ai.risicloud.risime.net.sendMessageArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Proposal 2026-10-14-send-for-me §42.2/§42.3/§42.9: decoding the proposal's examples, the phone's rule for
 * `send_message` (the agent's card, MY first answer `confirm_write`, args equal to the card's or merged with MY OWN
 * `edit.text`, an unused `write_id`), and the per-contact auto-send only on the phone that asked.
 */
class SendForMeTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val other = "3b4c5d6e-7f8a-4b9c-8d0e-1f2a3b4c5d6e"
    private val upali = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val risiUser = "9e1f0000-0000-4000-8000-000000000001"
    private val conv = "grp:2f3a4b5c-6d7e-4f8a-9b0c-1d2e3f4a5b6c"
    private val dm = "dm:0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e_7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val text = "Hi Upali, please update RisiMe to the latest version."
    private val wid = "2b3c4d5e-6f70-4812-93a4-b5c6d7e8f901"
    private val rid = "6d1f2e3a-4b5c-4d6e-9f7a-8b9c0d1e2f3a"

    // §42.9 examples (copies; root adds the files to contract/v1/examples when the proposal is applied).
    private val confirmJson = """{"v":1,"type":"text","body":"Send to Upali Perera (Private): $text? Update RisiMe to answer.","risi":{"v":1,"kind":"confirm","request_id":"$rid","write_id":"$wid","tool":"send_message","summary":"Send to Upali Perera (Private): $text","when":null,"text":"$text","args":{"conversation_id":"$dm","text":"$text"},"send":{"to":{"name":"Upali Perera","user_id":"$upali"},"tab":"private","language":"en"},"for":["$me"],"buttons":["send","edit","cancel"],"expires_at":"2026-10-15T02:23:30.456Z","turn_ref":"8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","call_ref":"7e2a3f4b-5c6d-4e7f-8a9b-0c1d2e3f4a5b","notify":["$me"]}}"""
    private val editJson = """{"v":1,"type":"risi_action","action":"confirm_write","target":"$wid","edit":{"text":"Hi Upali, please update RisiMe today."}}"""
    private val callJson = """{"event_id":"e5b2c3d4-a5b0-11f1-8000-0242ac120002","kind":"risi_tool_call","data":{"tool_call_id":"4c5d6e7f-8a9b-4c0d-9e1f-2a3b4c5d6e7f","turn_id":"8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","request_id":"$rid","conversation_id":"$conv","device_id":"c0a80101-0000-4000-8000-000000000001","tool":"send_message","args":{"write_id":"$wid","conversation_id":"$dm","text":"$text"},"undo_entry_id":null,"expires_at":"2026-10-14T02:25:30.000Z","to_devices":["c0a80101-0000-4000-8000-000000000001"],"server_ts":"2026-10-14T02:23:30.000Z"}}"""
    private val resultJson = """{"status":"ok","result":{"state":"sent"}}"""
    private val sentJson = """{"v":1,"type":"text","body":"✓ Sent to Upali Perera","risi":{"v":1,"kind":"message_sent","request_id":"$rid","write_id":"$wid","conversation_id":"$dm","to":{"name":"Upali Perera","user_id":"$upali"},"tab":"private","state":"sent","turn_ref":"8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","call_ref":null,"made_by":{"model":null,"provider":"rule","at":"2026-10-14T02:23:33.000Z","also":[]},"notify":["$me"]}}"""
    private val devicePutJson = """{"mls":{"signature_key":"AAAA","capabilities":["groups","tabs","risi_tools","risi_skills","risi_send"]}}"""

    private val rows = mutableListOf<MessageEntity>()
    private val sent = mutableListOf<SendMessageArgs>()
    private val dao = FakeScheduledDao()
    private val now = Instant.parse("2026-10-14T02:23:31Z").toEpochMilli()
    private val scheduled = ScheduledMessages(dao, FakeArmer(), send = { _, _, _ -> true }, isMember = { true }, now = { now }, zone = { ZoneId.of("Asia/Colombo") })
    private var sendResult: (SendMessageArgs) -> RisiToolResult = {
        RisiToolResult(RisiToolResult.OK, ProtocolJson.encodeToJsonElement(SendMessageResult.serializer(), SendMessageResult(SendMessageResult.SENT)) as JsonObject)
    }
    private val ex = RisiToolExecutor(
        me = { me }, history = { if (it.equals(conv, true)) rows else emptyList() }, risiChats = { listOf(conv) }, skillState = { "off" },
        dao = dao, scheduled = scheduled, setAlarm = { true }, now = { now },
        sendMessage = { a -> sent += a; sendResult(a) },
    )
    private var n = 0

    private fun card(edit: (JsonObject) -> JsonObject = { it }, serverTs: String = "2026-10-14T02:23:30.456Z"): MessageEntity {
        val risi = edit((ProtocolJson.parseToJsonElement(confirmJson) as JsonObject)["risi"] as JsonObject)
        val row = MessageEntity("card${n++}", "m$n", conv, risiUser, conv, "", serverTs, n.toLong(), "READ", false, systemJson = RisiMessages.encode(risi))
        rows += row
        return row
    }

    private fun ctl(from: String, json: JsonObject, serverTs: String = "2026-10-14T02:23:31.000Z", fromDevice: String? = null) {
        rows += MessageEntity(
            "ctl${n++}", "m$n", conv, from, conv, "", serverTs, n.toLong(), "READ", from == me,
            kind = MessageEntity.KIND_RISI_CTL, systemJson = String(RisiControl.encode(json)), fromDevice = fromDevice,
        )
    }

    private fun confirm(from: String = me, edit: JsonObject? = null) = ctl(from, RisiControl.action(wid, "confirm_write", writeEdit = edit))

    private val call get() = ProtocolJson.decodeFromString<Event>(callJson).risiToolCall()!!

    private fun withText(t: String) = call.copy(args = JsonObject(call.args + ("text" to JsonPrimitive(t))))

    @Test fun theExamplesDecode() {
        val c = ProtocolJson.decodeFromString(RisiTextEnvelope.serializer(), confirmJson).risi!!
        assertEquals(RisiSend149.TOOL_SEND_MESSAGE, c.tool)
        assertEquals("Upali Perera", c.send!!.to.name)
        assertEquals(upali, c.send!!.to.userId)
        assertEquals("private", c.send!!.tab)
        assertEquals(listOf("send", "edit", "cancel"), c.buttons)
        assertTrue(SendForMeRules.isSendCard(c) && SendForMeRules.isOneTapCard(c))
        assertEquals("To: Upali Perera · 🔒 Private", SendForMeRules.toLine(c))
        assertEquals(text, SendForMeRules.text(c))
        assertEquals("Send to Upali without asking next time", SendForMeRules.tickLabel(c))
        val a = call.sendMessageArgs()!!
        assertEquals(SendMessageArgs(wid, dm, text), a)
        assertEquals("sent", ProtocolJson.decodeFromString(RisiToolResult.serializer(), resultJson).result!!["state"].let { (it as JsonPrimitive).content })
        val s = ProtocolJson.decodeFromString(RisiTextEnvelope.serializer(), sentJson)
        assertEquals(RisiSend149.KIND_MESSAGE_SENT, s.risi!!.kind)
        assertEquals("Upali Perera", s.risi!!.to!!.name)
        assertEquals("private", s.risi!!.tab)
        assertEquals("sent", s.risi!!.state)
        assertEquals(dm, s.risi!!.conversationId)
        assertEquals("✓ Sent to Upali Perera", SendForMeRules.sentLine(s.risi!!, s.body))
        assertEquals("✓ Sent to Upali Perera", SendForMeRules.sentLine(s.risi!!, ""))
        assertEquals("Hi Upali, please update RisiMe today.", (RisiControl.editOf(editJson.let { String(RisiControl.encode(ProtocolJson.parseToJsonElement(it) as JsonObject)) })!!["text"] as JsonPrimitive).content)
        val caps = ((ProtocolJson.parseToJsonElement(devicePutJson) as JsonObject)["mls"] as JsonObject)["capabilities"] as kotlinx.serialization.json.JsonArray
        assertTrue(JsonPrimitive(ai.risicloud.risime.net.DeviceMls.CAP_RISI_SEND) in caps)
    }

    @Test fun aConfirmedCardSendsOnceAndAnswersSent() = runBlocking {
        card()
        confirm()
        val r = ex.execute(call)
        assertEquals(RisiToolResult.OK, r.status)
        assertEquals("""{"state":"sent"}""", r.result.toString())
        assertEquals(listOf(SendMessageArgs(wid, dm, text)), sent)
        // Never twice for the same write_id.
        assertEquals(RisiToolResult.DECLINED, ex.execute(call.copy(toolCallId = "again")).status)
        assertEquals(1, sent.size)
    }

    @Test fun myOwnEditIsAcceptedButOnlyExactly() = runBlocking {
        card()
        val edited = "Hi Upali, please update RisiMe today."
        confirm(edit = JsonObject(mapOf("text" to JsonPrimitive(edited))))
        // The unedited text is not what I confirmed.
        assertEquals(RisiToolResult.DECLINED, ex.execute(call).status)
        assertEquals(RisiToolResult.DECLINED, ex.execute(withText("Something else")).status)
        assertEquals(RisiToolResult.OK, ex.execute(withText(edited)).status)
        assertEquals(edited, sent.single().text)
    }

    @Test fun anEditWithOtherKeysIsNotAccepted() = runBlocking {
        card()
        confirm(edit = JsonObject(mapOf("text" to JsonPrimitive("x"), "conversation_id" to JsonPrimitive("dm:other"))))
        val c = call.copy(args = JsonObject(call.args + ("text" to JsonPrimitive("x")) + ("conversation_id" to JsonPrimitive("dm:other"))))
        assertEquals(RisiToolResult.DECLINED, ex.execute(c).status)
        assertTrue(sent.isEmpty())
    }

    @Test fun someoneElsesConfirmNoConfirmOrACancelFirstIsDeclined() = runBlocking {
        card()
        assertEquals(RisiToolResult.DECLINED, ex.execute(call).status)
        confirm(from = other)
        assertEquals(RisiToolResult.DECLINED, ex.execute(call).status)
        ctl(me, RisiControl.action(wid, "cancel_write"))
        confirm()
        assertEquals(RisiToolResult.DECLINED, ex.execute(call).status)
        assertTrue(sent.isEmpty())
    }

    @Test fun argsThatDifferFromTheCardOrAForgedWriteIdAreDeclined() = runBlocking {
        card()
        confirm()
        assertEquals(RisiToolResult.DECLINED, ex.execute(call.copy(args = JsonObject(call.args + ("conversation_id" to JsonPrimitive("dm:someone_else"))))).status)
        assertEquals(RisiToolResult.DECLINED, ex.execute(call.copy(args = JsonObject(call.args + ("write_id" to JsonPrimitive("aaaaaaaa-6f70-4812-93a4-b5c6d7e8f901"))))).status)
        assertTrue(sent.isEmpty())
    }

    @Test fun aRefusedTargetIsNotMemberAndReleasesTheWriteId() = runBlocking {
        card()
        confirm()
        sendResult = { RisiToolResult.error("not_member") }
        assertEquals("""{"code":"not_member"}""", ex.execute(call).result.toString())
        // Retry may run again (a Risi chat or a chat this phone doesn't hold is refused by the send itself).
        sendResult = { RisiToolResult(RisiToolResult.OK, ProtocolJson.encodeToJsonElement(SendMessageResult.serializer(), SendMessageResult("queued")) as JsonObject) }
        assertEquals("""{"state":"queued"}""", ex.execute(call.copy(toolCallId = "retry")).result.toString())
    }

    @Test fun badArgsAndADeviceWithoutRisiSend() = runBlocking {
        card()
        confirm()
        assertEquals("""{"code":"bad_args"}""", ex.execute(withText("   ")).result.toString())
        val bare = RisiToolExecutor(me = { me }, history = { rows }, risiChats = { listOf(conv) }, skillState = { "off" }, dao = dao, scheduled = scheduled, setAlarm = { true })
        assertEquals("""{"code":"unknown_tool"}""", bare.execute(call).result.toString())
        assertTrue(sent.isEmpty())
    }

    @Test fun scheduleMessageAcceptsMyOwnTextEdit() = runBlocking {
        val sched = ProtocolJson.decodeFromString<Event>(javaClass.classLoader!!.getResource("contract/v1/examples/event_risi_tool_call_schedule_message.json")!!.readText()).risiToolCall()!!
        val cardRisi = (ProtocolJson.parseToJsonElement(javaClass.classLoader!!.getResource("contract/v1/examples/envelope_risi_confirm_schedule_message.json")!!.readText()) as JsonObject)["risi"] as JsonObject
        val schedRows = mutableListOf(MessageEntity("c", "m", sched.conversationId!!, risiUser, sched.conversationId!!, "", "2026-10-09T15:09:00.000Z", 1, "READ", false, systemJson = RisiMessages.encode(cardRisi)))
        val myId = (cardRisi["for"] as kotlinx.serialization.json.JsonArray).first().let { (it as JsonPrimitive).content }
        schedRows += MessageEntity(
            "e", "m2", sched.conversationId!!, myId, sched.conversationId!!, "", "2026-10-09T15:09:30.000Z", 2, "READ", true, kind = MessageEntity.KIND_RISI_CTL,
            systemJson = String(RisiControl.encode(RisiControl.action(sched.writeId!!, "confirm_write", writeEdit = JsonObject(mapOf("text" to JsonPrimitive("Good morning!")))))),
        )
        val t = Instant.parse("2026-10-09T15:10:00Z").toEpochMilli()
        val s2 = ScheduledMessages(FakeScheduledDao(), FakeArmer(), send = { _, _, _ -> true }, isMember = { true }, now = { t }, zone = { ZoneId.of("Asia/Colombo") })
        val d2 = FakeScheduledDao()
        val e2 = RisiToolExecutor(me = { myId }, history = { schedRows }, risiChats = { listOf(sched.conversationId!!) }, skillState = { "ask" }, dao = d2, scheduled = s2, setAlarm = { true }, now = { t })
        assertEquals(RisiToolResult.DECLINED, e2.execute(sched).status) // the unedited text isn't what I confirmed
        val edited = sched.copy(args = JsonObject(sched.args + ("text" to JsonPrimitive("Good morning!"))))
        assertEquals(RisiToolResult.OK, e2.execute(edited).status)
    }

    // ---- the per-contact auto-send ----

    private class Store(on: Set<String> = emptySet()) : AutoSendStore {
        val on = on.toMutableSet()
        override fun isOn(userId: String, tab: String) = "$userId/$tab" in on
        override fun set(userId: String, tab: String, name: String, on: Boolean) { if (on) this.on += "$userId/$tab" else this.on -= "$userId/$tab" }
        override fun list() = on.map { AutoSendContact(it.substringBefore('/'), it.substringAfter('/'), "x") }
        override val version: StateFlow<Int> = MutableStateFlow(0)
    }

    private val myDevice = "c0a80101-0000-4000-8000-000000000001"

    private fun meta(row: MessageEntity) = RisiMessages.meta(row)!!

    @Test fun autoSendOnlyOnThePhoneThatAskedAndOnlyWhenChosen() {
        val c = card()
        val store = Store(setOf("$upali/private"))
        // No request from me on this phone: never.
        assertFalse(SendForMeRules.autoConfirm(c, meta(c), rows, me, myDevice, now, store))
        // My request, but sent from my OTHER phone: never (that phone decides).
        ctl(me, RisiControl.request(rid, "ask", "send Upali a message", null), "2026-10-14T02:23:10.000Z", fromDevice = "other-device")
        assertFalse(SendForMeRules.autoConfirm(c, meta(c), rows, me, myDevice, now, store))
        // Someone else's request with that id: never.
        ctl(other, RisiControl.request(rid, "ask", "x", null), "2026-10-14T02:23:10.000Z")
        assertFalse(SendForMeRules.autoConfirm(c, meta(c), rows, me, myDevice, now, store))
        // My request from this phone: yes.
        ctl(me, RisiControl.request(rid, "ask", "send Upali a message", null), "2026-10-14T02:23:10.000Z", fromDevice = myDevice)
        assertTrue(SendForMeRules.autoConfirm(c, meta(c), rows, me, myDevice, now, store))
        // Not chosen for that contact (or only for Official): never.
        assertFalse(SendForMeRules.autoConfirm(c, meta(c), rows, me, myDevice, now, Store()))
        assertFalse(SendForMeRules.autoConfirm(c, meta(c), rows, me, myDevice, now, Store(setOf("$upali/official"))))
        // Already answered: never a second confirm.
        confirm()
        assertFalse(SendForMeRules.autoConfirm(c, meta(c), rows, me, myDevice, now, store))
    }

    @Test fun autoSendNeedsARecentRequestAndAnOpenCard() {
        val c = card()
        val store = Store(setOf("$upali/private"))
        ctl(me, RisiControl.request(rid, "ask", "x", null), "2026-10-14T02:10:00.000Z") // over 10 min before the card
        assertFalse(SendForMeRules.autoConfirm(c, meta(c), rows, me, myDevice, now, store))
        rows.clear()
        val c2 = card()
        ctl(me, RisiControl.request(rid, "ask", "x", null), "2026-10-14T02:23:10.000Z")
        assertTrue(SendForMeRules.autoConfirm(c2, meta(c2), rows, me, myDevice, now, store))
        // Expired card.
        assertFalse(SendForMeRules.autoConfirm(c2, meta(c2), rows, me, myDevice, Instant.parse("2026-10-16T00:00:00Z").toEpochMilli(), store))
        // Not for me.
        assertFalse(SendForMeRules.autoConfirm(c2, meta(c2), rows, other, myDevice, now, store))
    }

    @Test fun buttonsEditAndFlag() {
        val c = meta(card())
        assertEquals(listOf("send", "edit", "cancel"), SendForMeRules.buttons(me, c, RisiToolCards.ConfirmState.OPEN, emptySet()))
        assertTrue(SendForMeRules.buttons(other, c, RisiToolCards.ConfirmState.OPEN, emptySet()).isEmpty())
        assertTrue(SendForMeRules.buttons(me, c, RisiToolCards.ConfirmState.CONFIRMED, emptySet()).isEmpty())
        assertTrue(SendForMeRules.buttons(me, c, RisiToolCards.ConfirmState.OPEN, setOf(wid)).isEmpty())
        assertNull(SendForMeRules.edit(text, "  $text "))
        assertNull(SendForMeRules.edit(text, "   "))
        assertEquals("""{"text":"Hi"}""", SendForMeRules.edit(text, " Hi ").toString())
        assertTrue(SendForMeRules.flagOn(dev = true, choice = null))
        assertFalse(SendForMeRules.flagOn(dev = true, choice = "off"))
        assertFalse(SendForMeRules.flagOn(dev = false, choice = "on"))
    }
}
