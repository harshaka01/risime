package lk.codegen.risime.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.25 wire models stay compatible with v1.24 shapes, and the client-sent bodies re-encode exactly. */
class Protocol125Test {
    private fun read(name: String): String =
        javaClass.classLoader!!.getResource("contract/v1/examples/$name")?.readText() ?: error("missing $name")

    @Test fun v124AnswerDecodesWithEmptyV125Fields() {
        val r = ProtocolJson.decodeFromString<RisiTextEnvelope>(read("envelope_risi_answer.json")).risi!!
        assertTrue(r.steps.isEmpty() && r.sources.isEmpty() && r.nextSteps.isEmpty())
        assertNull(r.localSearch)
        assertNull(r.turnRef)
        assertNull(r.confirmWhen())
    }

    @Test fun authConfigWithoutRisiToolsIsOff() {
        assertFalse(ProtocolJson.decodeFromString<AuthConfig>(read("auth_config_v124.json")).risiToolsOn)
        assertTrue(ProtocolJson.decodeFromString<AuthConfig>(read("auth_config_v125.json")).risiToolsOn)
    }

    @Test fun toolResultsReEncodeExactly() {
        for (f in listOf("risi_tool_result_calendar_add.json", "risi_tool_result_calendar_check.json")) {
            val s = read(f).trim()
            assertEquals(f, ProtocolJson.parseToJsonElement(s), ProtocolJson.encodeToJsonElement(RisiToolResult.serializer(), ProtocolJson.decodeFromString(s)))
        }
        assertEquals("""{"status":"declined","result":null}""", ProtocolJson.encodeToString(RisiToolResult.serializer(), RisiToolResult.declined()))
        assertEquals("""{"status":"error","result":{"code":"unknown_tool"}}""", ProtocolJson.encodeToString(RisiToolResult.serializer(), RisiToolResult.error(RisiToolErrorCodes.UNKNOWN_TOOL)))
    }

    @Test fun confirmWriteActionReEncodesExactly() {
        val s = read("envelope_risi_action_confirm_write.json").trim()
        assertEquals(ProtocolJson.parseToJsonElement(s), ProtocolJson.encodeToJsonElement(RisiActionEnvelope.serializer(), ProtocolJson.decodeFromString(s)))
    }

    @Test fun risiChatGroupMetaFromMlsState() {
        val id = "grp:2f3a4b5c-6d7e-4f8a-9b0c-1d2e3f4a5b6c"
        val json = """{"v":1,"name":null,"icon":null,"admins":["u"],"tab":"official","chat_id":"$id","agents":["r"],"chat_kind":"risi"}"""
        val m = GroupMeta.decode(json.toByteArray())!!
        assertEquals("", m.name)
        assertTrue(m.isRisiChat(id))
        // A core that drops chat_kind: still a Risi chat by shape (Official, chat_id = own id).
        assertTrue(m.copy(chatKind = null).isRisiChat(id))
        // A normal Official (chat_id = its Private tab) and a Private group are not.
        assertFalse(m.copy(chatKind = null, chatId = "grp:other").isRisiChat(id))
        assertFalse(m.copy(tab = null).isRisiChat(id))
        // Old metas encode without the new key.
        assertFalse(String(GroupMeta(name = "x", admins = listOf("u")).encode()).contains("chat_kind"))
    }

    @Test fun unknownSignalAndEventKindsStillIgnored() {
        val sig = ProtocolJson.decodeFromString<Signal>(read("signal_risi_progress.json"))
        assertNull(sig.presence())
        assertNull(sig.copy(kind = "other").risiProgress())
        val ev = ProtocolJson.decodeFromString<Event>(read("event_risi_tool_call_calendar_check.json"))
        assertNull(ev.messageData())
        assertNull(ev.copy(kind = "other").risiToolCall())
    }

    @Test fun sourcesOnlyShowHttpsLinksAndKnownTypes() {
        assertFalse(RisiSource("link", url = "http://x.example").showable)
        assertTrue(RisiSource("link", url = "https://x.example").showable)
        assertFalse(RisiSource("future_type").showable)
    }
}
