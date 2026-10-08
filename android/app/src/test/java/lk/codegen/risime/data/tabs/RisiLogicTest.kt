package lk.codegen.risime.data.tabs

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §24 A6 pure rules: the `risi_request` / `risi_action` envelopes, Private sends nothing, the tz PATCH. */
class RisiLogicTest {
    private fun example(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private fun obj(s: String) = ProtocolJson.parseToJsonElement(s) as JsonObject

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    // ---- envelopes ----

    @Test fun theContractExamplesAreValidControlsAndRenderLines() {
        val req = example("envelope_risi_request.json")
        val act = example("envelope_risi_action.json")
        assertTrue(RisiControl.valid(obj(req)))
        assertTrue(RisiControl.valid(obj(act)))
        assertTrue(MlsPayload.decode(req.toByteArray()) is MlsPayload.Decoded.RisiControl)
        assertTrue(MlsPayload.decode(act.toByteArray()) is MlsPayload.Decoded.RisiControl)
        assertEquals("Kamal asked Risi to summarise", RisiControl.line(req, "Kamal"))
        assertEquals("Kamal edited a commitment", RisiControl.line(act, "Kamal"))
        assertEquals("5c0e1d2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f", RisiControl.targetOf(act))
        assertEquals("Kamal confirmed", RisiControl.line(ProtocolJson.encodeToString(JsonObject.serializer(), RisiControl.action("t", "confirm")), "Kamal"))
        assertNull(RisiControl.line("{\"v\":1,\"type\":\"risi_action\",\"target\":\"t\",\"action\":\"nuke\"}", "Kamal"))
        assertNull(RisiControl.line("not json", "Kamal"))
    }

    @Test fun anActionEnvelopeMatchesTheContractShape() {
        val e = RisiControl.action("5c0e1d2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f", "edit", "Send the revised quote", "2026-10-10T11:30:00.000Z")
        assertEquals(obj(example("envelope_risi_action.json")), e)
        // Non-edit actions carry `edit: null`.
        assertEquals(JsonObject.serializer().let { ProtocolJson.encodeToString(it, RisiControl.action("t", "confirm")) }, """{"v":1,"type":"risi_action","target":"t","action":"confirm","edit":null}""")
    }

    @Test fun aRequestEnvelopeMatchesTheContractShape() {
        val e = RisiControl.request("6d1f2e3a-4b5c-4d6e-9f7a-8b9c0d1e2f3a", "summarise", null, java.time.Instant.parse("2026-10-07T09:15:30.456Z").toEpochMilli())
        assertEquals(obj(example("envelope_risi_request.json")), e)
        val ask = RisiControl.request("id", "ask", "what is due?", null)
        assertEquals("what is due?", str(ask, "text"))
        assertFalse(ask.containsKey("scope"))
    }

    // ---- RisiRequests: structured, Official only ----

    private class Sink {
        val sent = mutableListOf<JsonObject>()
        suspend fun send(o: JsonObject) { sent += o }
    }

    private fun requests(official: Boolean, sink: Sink, now: Long = 1_760_000_000_000L) =
        RisiRequests({ official }, sink::send, newId = { "req-1" }, now = { now })

    @Test fun askSendsAStructuredRequestWithTheText() = runTest {
        val s = Sink()
        assertTrue(requests(true, s).ask("  what did Kamal promise?  "))
        val o = s.sent.single()
        assertEquals("risi_request", str(o, "type"))
        assertEquals("ask", str(o, "action"))
        assertEquals("what did Kamal promise?", str(o, "text"))
        assertEquals("req-1", str(o, "request_id"))
    }

    @Test fun askRejectsEmptyAndTooLong() = runTest {
        val s = Sink()
        assertFalse(requests(true, s).ask("   "))
        assertFalse(requests(true, s).ask("x".repeat(1001)))
        assertTrue(requests(true, s).ask("x".repeat(1000)))
        assertEquals(1, s.sent.size)
    }

    @Test fun summariseAndReportAskForTheLast24HoursAtMost() = runTest {
        val now = 1_760_000_000_000L
        val s = Sink()
        val r = requests(true, s, now)
        assertTrue(r.summarise())
        assertTrue(r.report())
        assertEquals(listOf("summarise", "report"), s.sent.map { str(it, "action") })
        for (o in s.sent) {
            val since = java.time.Instant.parse(str(o["scope"] as JsonObject, "since")!!).toEpochMilli()
            assertTrue("within 24 h", now - since <= 24L * 3600_000)
            assertTrue(now - since > 23L * 3600_000)
        }
    }

    @Test fun nothingRisiGoesOutFromPrivate() = runTest {
        val s = Sink()
        val r = requests(false, s)
        assertFalse(r.ask("hello"))
        assertFalse(r.summarise())
        assertFalse(r.report())
        assertFalse(r.act("t", "confirm"))
        assertTrue(s.sent.isEmpty())
    }

    @Test fun actionsAreOnlyTheContractOnes() = runTest {
        val s = Sink()
        val r = requests(true, s)
        assertFalse(r.act("t", "explode"))
        assertTrue(r.act("t", "decline"))
        assertTrue(r.act("o", "offer_not_now"))
        assertEquals(listOf("decline", "offer_not_now"), s.sent.map { str(it, "action") })
    }

    // ---- timezone ----

    @Test fun theTimezoneIsPatchedOnceThenOnlyWhenItChanges() = runTest {
        var zone = "Asia/Colombo"
        val remembered = mutableMapOf<String, String>()
        val patched = mutableListOf<String>()
        val sync = TimezoneSync({ zone }, { remembered[it] }, { u, tz -> remembered[u] = tz }, { tz -> patched += tz; ApiResult.Ok(Unit) })
        assertTrue(sync.sync("u1"))
        assertFalse(sync.sync("u1"))
        zone = "Europe/London"
        assertTrue(sync.sync("u1"))
        assertEquals(listOf("Asia/Colombo", "Europe/London"), patched)
        // Another account on the same phone sends its own.
        zone = "Europe/London"
        assertTrue(sync.sync("u2"))
        assertFalse(sync.sync(null))
    }

    @Test fun aFailedTimezonePatchIsRetriedAnUnknownZoneIsNot() = runTest {
        val remembered = mutableMapOf<String, String>()
        var reply: ApiResult<Unit> = ApiResult.NetworkError(java.io.IOException("down"))
        var n = 0
        val sync = TimezoneSync({ "Asia/Colombo" }, { remembered[it] }, { u, tz -> remembered[u] = tz }, { n++; reply })
        assertFalse(sync.sync("u1"))
        assertFalse(sync.sync("u1"))
        assertEquals(2, n)
        reply = ApiResult.Ok(Unit)
        assertTrue(sync.sync("u1"))
        assertFalse(sync.sync("u1"))
        assertEquals(3, n)
        remembered.clear()
        reply = ApiResult.Error(422, "bad_request", "unknown zone")
        n = 0
        assertFalse(sync.sync("u1"))
        assertFalse(sync.sync("u1"))
        assertEquals("a refused zone isn't hammered", 1, n)
    }
}
