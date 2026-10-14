package ai.risicloud.risime.data.tabs

import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.RisiMeta
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hotfix 2026-10-14 (P1, the morning brief missing in the Risi chat): every Risi message kind the server
 * sends renders with visible text, the brief shows its body (= the chat-list preview), and an unknown kind
 * falls back to a readable line instead of disappearing.
 */
class RisiRenderTest {
    private val examples: File =
        File(System.getProperty("risime.contract") ?: "../../contract/v1", "examples")

    /** Every `envelope_risi_*.json` that is a Risi message (has a `risi` object; client actions/requests don't). */
    private fun risiExamples(): List<Pair<String, JsonObject>> =
        (examples.listFiles { f -> f.name.startsWith("envelope_risi_") && f.name.endsWith(".json") } ?: emptyArray())
            .sortedBy { it.name }
            .map { it.name to (ProtocolJson.parseToJsonElement(it.readText()) as JsonObject) }
            .filter { (_, env) -> env["risi"] is JsonObject }

    @Test fun everyRisiExampleParsesAndShowsText() {
        val all = risiExamples()
        assertTrue("found the contract examples in $examples", all.size >= 40)
        val failures = mutableListOf<String>()
        for ((name, env) in all) {
            val body = (env["body"] as? JsonPrimitive)?.content
            val risi = env["risi"] as JsonObject
            val meta = runCatching { ProtocolJson.decodeFromJsonElement(RisiMeta.serializer(), risi) }.getOrNull()
            if (meta == null) { failures += "$name: risi object does not parse"; continue }
            val text = RisiRender.visibleText(body, meta)
            if (text.isBlank()) failures += "$name: blank"
            if (text == RisiRender.FALLBACK) failures += "$name: shows the update fallback (kind ${meta.kind})"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** The §35.4 brief exactly as `RisiMe.Agent.Brief` posts it (there is no contract example of scope "brief" yet). */
    private val brief = """
        {"v":1,"type":"text","body":"Good morning. Your day, Wed 14 Oct\n\nOverdue:\n• Send the quote\n\nYesterday's decisions:\n• Go with vendor B\n\nChecked: Risi Calendar, your promises, your Official chats. Not your phone calendar.",
         "risi":{"kind":"digest","scope":"brief","date":"2026-10-14","language":"en",
           "events":[{"event_id":"e1","title":"Standup"}],"risi_items":[{"id":"i1","kind":"reminder","start":"2026-10-14T04:30:00Z","end":null,"all_day":false}],
           "items":[{"commitment_id":"c1","text":"Send the quote","owner":"u1","due":"2026-10-13T11:30:00.000Z","state":"confirmed","direction":"i_promised"}],
           "decisions":[{"text":"Go with vendor B","conversation_id":"grp:x"}],"notify":["u1"]}}
    """.trimIndent()

    @Test fun briefShowsItsBodyLikeTheListPreview() {
        val env = ProtocolJson.parseToJsonElement(brief) as JsonObject
        val body = (env["body"] as JsonPrimitive).content
        // Object-shaped `decisions` must not make the whole risi object unreadable.
        val r = ProtocolJson.decodeFromJsonElement(RisiMeta.serializer(), env["risi"] as JsonObject)
        assertEquals(listOf("Go with vendor B"), r.decisions)
        assertTrue(RisiRender.digestShowsBody(r))
        assertEquals(body, RisiRender.visibleText(body, r))
        assertTrue(RisiRender.visibleText(body, r).startsWith("Good morning"))
        assertEquals("Risi · Your day · 2026-10-14", RisiRender.digestHeader(r))
    }

    @Test fun groupAndPersonalDigestsKeepTheirCards() {
        val item = """{"commitment_id":"c1","text":"Send the quote","owner":"u1"}"""
        val group = ProtocolJson.decodeFromString(RisiMeta.serializer(), """{"kind":"digest","date":"2026-10-14","items":[$item]}""")
        val personal = ProtocolJson.decodeFromString(RisiMeta.serializer(), """{"kind":"digest","scope":"personal","items":[$item]}""")
        assertFalse(RisiRender.digestShowsBody(group))
        assertFalse(RisiRender.digestShowsBody(personal))
        assertEquals("Risi · Open items · 2026-10-14", RisiRender.digestHeader(group))
        // A digest with no items, or a scope this version doesn't know, shows its body.
        assertTrue(RisiRender.digestShowsBody(group.copy(items = emptyList())))
        assertTrue(RisiRender.digestShowsBody(group.copy(scope = "weekly")))
    }

    @Test fun unknownKindShowsBodyOrTheUpdateLine() {
        val r = RisiMeta(kind = "something_new")
        assertEquals("Here is a new thing", RisiRender.visibleText("Here is a new thing", r))
        assertEquals(RisiRender.FALLBACK, RisiRender.visibleText("", r))
        assertEquals(RisiRender.FALLBACK, RisiRender.visibleText(null, r))
        assertEquals(RisiRender.FALLBACK, RisiRender.visibleText("  ", null))
        assertEquals("Risi sent something this version can't show yet. Update RisiMe to see it.", RisiRender.FALLBACK)
    }

    @Test fun unreadableRisiObjectStillDraws() {
        val row = MessageEntity("c", "m", "grp:x", "risi", "me", "Good morning.", null, 1, "READ", outgoing = false)
            .copy(systemJson = """{"kind": 5, "items": "not a list"}""")
        val r = RisiMessages.metaForDisplay(row)
        assertNotNull(r)
        assertEquals(RisiMessages.UNKNOWN_KIND, r!!.kind)
        assertEquals("Good morning.", RisiRender.visibleText(row.body, r))
        // An ordinary text row stays an ordinary bubble.
        assertEquals(null, RisiMessages.metaForDisplay(row.copy(systemJson = null)))
    }
}
