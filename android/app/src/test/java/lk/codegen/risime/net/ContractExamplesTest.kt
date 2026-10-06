package lk.codegen.risime.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses every file in contract/v1/examples (copied into test resources by copyContractExamples). */
class ContractExamplesTest {

    private fun read(name: String): String =
        javaClass.classLoader!!.getResource("contract/v1/examples/$name")?.readText()
            ?: error("missing contract example $name")

    private val all: List<String> by lazy { read("index.txt").lines().filter { it.isNotBlank() } }

    /** Every example file must map to a model; a new file without a decoder fails this test. */
    private val decoders: Map<String, (String) -> Any> = mapOf(
        "auth_verify_reply.json" to { s -> ProtocolJson.decodeFromString<AuthVerifyReply>(s) },
        "contacts_reply.json" to { s -> ProtocolJson.decodeFromString<ContactsReply>(s) },
        "event_message.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.messageData()) } },
        "event_status.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.statusData()) } },
        "join_reply.json" to { s -> ProtocolJson.decodeFromString<EventsPage>(s) },
        "msg_send.json" to { s -> ProtocolJson.decodeFromString<MsgSend>(s) },
        "msg_send_reply.json" to { s -> ProtocolJson.decodeFromString<MsgSendReply>(s) },
        "presence_watch.json" to { s -> ProtocolJson.decodeFromString<PresenceWatch>(s) },
        "presence_watch_reply.json" to { s -> ProtocolJson.decodeFromString<PresenceWatchReply>(s) },
        "signal_presence.json" to { s -> ProtocolJson.decodeFromString<Signal>(s).also { requireNotNull(it.presence()) } },
        "signal_typing.json" to { s -> ProtocolJson.decodeFromString<Signal>(s).also { requireNotNull(it.typing()) } },
        "typing.json" to { s -> ProtocolJson.decodeFromString<TypingPush>(s) },
        // v1.3 (Keycloak auth): parse-only placeholders added by root with the contract merge;
        // the android role replaces them with typed decoders when it implements §6.
        "auth_config.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "error_not_allowlisted.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "error_invalid_token.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "error_identity_conflict.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "auth_refresh.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "auth_refresh_reply.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "auth_refresh_error.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
    )

    @Test
    fun everyExampleParses() {
        assertTrue("no contract examples found", all.isNotEmpty())
        for (name in all) {
            val decode = decoders[name] ?: throw AssertionError("no decoder for contract example $name")
            assertNotNull(name, decode(read(name)))
        }
    }

    /** Re-encoding a model yields the same JSON object as the example (field names match exactly). */
    @Test
    fun clientSentPayloadsRoundTrip() {
        val original = ProtocolJson.parseToJsonElement(read("msg_send.json")) as JsonObject
        val model = ProtocolJson.decodeFromJsonElement<MsgSend>(original)
        assertEquals(original, ProtocolJson.encodeToJsonElement(model))
    }

    @Test
    fun clientSentV12PayloadsRoundTrip() {
        for (name in listOf("presence_watch.json", "typing.json")) {
            val original = ProtocolJson.parseToJsonElement(read(name)) as JsonObject
            val encoded = when (name) {
                "typing.json" -> ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<TypingPush>(original))
                else -> ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<PresenceWatch>(original))
            }
            assertEquals(name, original, encoded)
        }
    }

    @Test
    fun presenceExamples() {
        val reply = ProtocolJson.decodeFromString<PresenceWatchReply>(read("presence_watch_reply.json"))
        val p = reply.presences.single()
        assertFalse(p.online)
        assertNotNull(p.lastSeen)
        val watch = ProtocolJson.decodeFromString<PresenceWatch>(read("presence_watch.json"))
        assertEquals(watch.userIds, listOf(p.userId))

        val sig = ProtocolJson.decodeFromString<Signal>(read("signal_presence.json"))
        assertEquals(Signal.KIND_PRESENCE, sig.kind)
        val live = sig.presence()!!
        assertTrue(live.online)
        assertEquals(null, live.lastSeen)
        assertEquals(null, sig.typing())
    }

    @Test
    fun typingExamples() {
        val t = ProtocolJson.decodeFromString<Signal>(read("signal_typing.json")).typing()!!
        assertTrue(t.typing)
        val push = ProtocolJson.decodeFromString<TypingPush>(read("typing.json"))
        assertEquals(dmConversationId(t.from, push.to), t.conversationId)
        assertTrue(push.typing)
    }

    @Test
    fun unknownSignalKindIsIgnored() {
        val s = ProtocolJson.decodeFromString<Signal>("""{"kind":"mood","data":{"x":1}}""")
        assertEquals(null, s.presence())
        assertEquals(null, s.typing())
    }

    @Test
    fun messageEventFields() {
        val e = ProtocolJson.decodeFromString<Event>(read("event_message.json"))
        val m = e.messageData()!!
        assertEquals(Event.KIND_MESSAGE, e.kind)
        assertEquals(dmConversationId(m.from, m.to), m.conversationId)
        assertEquals(null, e.statusData())
    }

    @Test
    fun statusEventFields() {
        val s = ProtocolJson.decodeFromString<Event>(read("event_status.json")).statusData()!!
        assertEquals("delivered", s.status)
    }

    @Test
    fun contactsUnregisteredHasNullUserId() {
        val c = ProtocolJson.decodeFromString<ContactsReply>(read("contacts_reply.json")).contacts
        assertTrue(c.any { !it.registered && it.userId == null })
        assertFalse(c.isEmpty())
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val s = """{"events":[],"has_more":false,"server_time":"2026-10-06T08:15:29.000Z","extra":1}"""
        assertFalse(ProtocolJson.decodeFromString<EventsPage>(s).hasMore)
    }
}
