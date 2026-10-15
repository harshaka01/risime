package ai.risicloud.risime.calls.risi

import ai.risicloud.risime.calls.CryptorState
import ai.risicloud.risime.calls.FakeGroupPort
import ai.risicloud.risime.calls.FakeSfu
import ai.risicloud.risime.calls.GroupCallMachine
import ai.risicloud.risime.calls.GroupCallPort
import ai.risicloud.risime.calls.MemMarks
import ai.risicloud.risime.calls.RoomOutcome
import ai.risicloud.risime.calls.SfuParticipant
import ai.risicloud.risime.data.tabs.TitledEvent
import ai.risicloud.risime.net.CallRisiCal152
import ai.risicloud.risime.net.CallRisiCalReply
import ai.risicloud.risime.net.CallsRoomReply
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.RisiCallReply
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * Contract v1.52 §41.8 on the phone: Risi's `risi.cal` request is answered only from `risi_identity`, only on request,
 * only while the Risi call is up; the setting off gives `reason: "off"`; no permission, no calendars and a failed read
 * have their reasons; at most 100 events by start; titles ≤ 200 characters or null; times in the request's offset.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallRisiCalTest {
    private val risi = "u-risi/risi-dev"
    private val id = "8f3a6c21-4d7e-4b59-a1c8-3e9f0d2b7a64"
    private val req = """{"t":"cal_req","id":"$id","from":"2026-10-15T00:00:00+05:30","to":"2026-10-17T00:00:00+05:30"}"""

    private fun ms(s: String) = OffsetDateTime.parse(s).toInstant().toEpochMilli()

    private class Source(
        var read: Boolean = true,
        var calendars: Boolean = true,
        var fail: Boolean = false,
        var events: List<TitledEvent> = emptyList(),
    ) : CallCalSource {
        var reads = 0
        override fun canRead() = read
        override fun hasCalendars() = calendars
        override fun events(fromMs: Long, toMs: Long): List<TitledEvent> {
            reads++
            if (fail) throw IllegalStateException("provider")
            return events
        }
    }

    private fun reply(b: ByteArray?): CallRisiCalReply = ProtocolJson.decodeFromString(CallRisiCalReply.serializer(), b!!.decodeToString())

    @Test fun parsesOnlyAWellFormedRequest() {
        val r = CallRisiCal.parseRequest(req.toByteArray())!!
        assertEquals(id, r.id)
        assertEquals(ms("2026-10-15T00:00:00+05:30"), r.fromMs)
        assertEquals(2L * 86_400_000, r.toMs - r.fromMs)
        assertEquals("+05:30", r.offset.id)
        for (bad in listOf(
            req.replace("cal_req", "cal"),
            req.replace("\"$id\"", "\"\""),
            req.replace("2026-10-17T00", "2026-10-14T00"), // to before from
            req.replace("2026-10-17T00", "2026-10-24T00"), // > 8 days
            req.replace("2026-10-17T00:00:00+05:30", "tomorrow"),
            "not json", "[]", "",
        )) assertNull(bad, CallRisiCal.parseRequest(bad.toByteArray()))
        assertNotNull(CallRisiCal.parseRequest(req.replace("2026-10-17T00", "2026-10-23T00").toByteArray())) // exactly 8 days
    }

    @Test fun acceptsOnlyRisisTopicAndIdentityWhileTheCallIsUp() {
        assertTrue(CallRisiCal.accepts("risi.cal", risi, risi, callUp = true))
        assertTrue(CallRisiCal.accepts("risi.cal", risi.uppercase(), risi, callUp = true))
        assertFalse(CallRisiCal.accepts("risi.cal", risi, risi, callUp = false))
        assertFalse(CallRisiCal.accepts("other", risi, risi, callUp = true))
        assertFalse(CallRisiCal.accepts(null, risi, risi, callUp = true))
        assertFalse(CallRisiCal.accepts("risi.cal", "u-x/x1", risi, callUp = true))
        assertFalse(CallRisiCal.accepts("risi.cal", null, risi, callUp = true))
        assertFalse(CallRisiCal.accepts("risi.cal", risi, null, callUp = true))
    }

    @Test fun ignoresOtherSendersTopicsAndJunkWithoutReading() {
        val src = Source()
        val cal = CallRisiCal(src, titlesOn = { true })
        assertNull(cal.respond("risi.cal", "u-x/x1", req.toByteArray(), risi, true))
        assertNull(cal.respond("chat", risi, req.toByteArray(), risi, true))
        assertNull(cal.respond("risi.cal", risi, req.toByteArray(), risi, false))
        assertNull(cal.respond("risi.cal", risi, "junk".toByteArray(), risi, true))
        assertEquals(0, src.reads)
    }

    @Test fun theReasons() {
        val src = Source()
        var on = false
        val cal = CallRisiCal(src, titlesOn = { on })
        assertEquals(CallRisiCal152.OFF, reply(cal.respond("risi.cal", risi, req.toByteArray(), risi, true)).reason)
        assertEquals(0, src.reads)
        on = true
        src.read = false
        reply(cal.respond("risi.cal", risi, req.toByteArray(), risi, true)).also {
            assertFalse(it.ok)
            assertEquals(id, it.id)
            assertEquals(CallRisiCal152.NO_PERMISSION, it.reason)
        }
        src.read = true
        src.calendars = false
        assertEquals(CallRisiCal152.NO_CALENDARS, reply(cal.respond("risi.cal", risi, req.toByteArray(), risi, true)).reason)
        src.calendars = true
        src.fail = true
        assertEquals(CallRisiCal152.ERROR, reply(cal.respond("risi.cal", risi, req.toByteArray(), risi, true)).reason)
    }

    @Test fun eventsSortedLimitedTitledAndInTheRequestsOffset() {
        val base = ms("2026-10-15T00:00:00+05:30")
        val many = (0 until 150).map { i -> TitledEvent(base + (149 - i) * 600_000L, base + (149 - i) * 600_000L + 300_000, false, "T$i", true) }
        val special = listOf(
            TitledEvent(ms("2026-10-15T10:00:00+05:30"), ms("2026-10-15T10:30:00+05:30"), false, "Call with Shakya", true),
            TitledEvent(base - 3_600_000, base - 1, false, "before the range", true),
        )
        val src = Source(events = many + special)
        val cal = CallRisiCal(src, titlesOn = { true })
        val out = cal.respond("risi.cal", risi, req.toByteArray(), risi, true)!!
        val r = reply(out)
        assertTrue(r.ok)
        assertEquals(id, r.id)
        assertEquals(CallRisiCal152.MAX_EVENTS, r.events!!.size)
        val starts = r.events!!.map { OffsetDateTime.parse(it.start).toInstant() }
        assertEquals(starts.sorted(), starts)
        assertTrue(r.events!!.none { it.title == "before the range" })
        assertTrue(r.events!!.all { it.start.endsWith("+05:30") && it.end.endsWith("+05:30") })
        assertEquals("2026-10-15T00:00:00+05:30", r.events!!.first().start)
        // An ok reply carries no reason; an empty title is an explicit null.
        val o = ProtocolJson.parseToJsonElement(out.decodeToString()).jsonObject
        assertFalse("reason" in o)

        src.events = listOf(
            TitledEvent(base, base + 86_400_000, true, "  ", false),
            TitledEvent(base + 1000, base + 2000, false, "x".repeat(300), true),
            TitledEvent(base + 3000, base + 4000, false, "Line\u0000one\ntwo", true),
        )
        val r2 = cal.respond("risi.cal", risi, req.toByteArray(), risi, true)!!
        val ev = ProtocolJson.parseToJsonElement(r2.decodeToString()).jsonObject["events"]!!.jsonArray
        assertEquals(JsonNull, ev[0].jsonObject["title"])
        val d = reply(r2).events!!
        assertTrue(d[0].allDay && !d[0].busy && d[0].title == null)
        assertEquals("2026-10-16T00:00:00+05:30", d[0].end)
        assertEquals(200, d[1].title!!.length)
        assertEquals("Line one two", d[2].title)
    }

    // ---- in the machine: only a live Call Risi answers, only to Risi's identity ----

    private class RisiPort(val base: FakeGroupPort) : GroupCallPort by base {
        override suspend fun risiRoom(conv: String, callId: String): RoomOutcome = RoomOutcome.Ok(
            CallsRoomReply("wss://x/livekit", "room", "u-a/a1", "token-for-u-a/a1", "2026-10-08T10:10:00.000Z", 2, risiCall = RisiCallReply("joining", false, "u-risi/risi-dev", 1800, "en")),
        )
    }

    private fun TestScope.machine(src: Source, calls: MutableList<String>): Pair<GroupCallMachine, FakeSfu> {
        val sfu = FakeSfu()
        val cal = CallRisiCal(src, titlesOn = { true })
        val m = GroupCallMachine(
            "u-a", "a1", backgroundScope, sfu, RisiPort(FakeGroupPort("u-a/a1")), MemMarks(),
            now = { testScheduler.currentTime + 1_791_451_200_000L }, newCallId = { "3c8e1f4a-9b2d-4e7f-8a61-5d0c2b9e7f13" },
            risiData = { topic, from, payload, risiId -> calls += "$topic $from"; cal.respond(topic, from, payload, risiId, callUp = true) },
        )
        return m to sfu
    }

    @Test fun aLiveRisiCallRepliesToRisiOnly() = runTest {
        val calls = mutableListOf<String>()
        val src = Source(events = listOf(TitledEvent(ms("2026-10-15T10:00:00+05:30"), ms("2026-10-15T10:30:00+05:30"), false, "Call with Shakya", true)))
        val (m, sfu) = machine(src, calls)
        assertTrue(m.startRisi("grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"))
        runCurrent()
        val s = sfu.sessions.single()
        s.room(SfuParticipant(risi, local = false, hasAudio = true, encrypted = false, cryptor = CryptorState.NONE))
        runCurrent()

        s.listener.onData("risi.cal", "u-x/x1", req.toByteArray())
        s.listener.onData("other", risi, req.toByteArray())
        runCurrent()
        assertTrue(s.data.isEmpty())

        s.listener.onData("risi.cal", risi, req.toByteArray())
        runCurrent()
        val (payload, topic, to) = s.data.single()
        assertEquals("risi.cal", topic)
        assertEquals(listOf(risi), to)
        val r = ProtocolJson.decodeFromString(CallRisiCalReply.serializer(), payload)
        assertTrue(r.ok && r.events!!.single().title == "Call with Shakya")

        // After hang-up nothing is answered (and nothing is ever sent unasked).
        m.hangUp()
        runCurrent()
        s.listener.onData("risi.cal", risi, req.toByteArray())
        runCurrent()
        assertEquals(1, s.data.size)
    }

    @Test fun aNormalGroupCallNeverAnswers() = runTest {
        val calls = mutableListOf<String>()
        val (m, sfu) = machine(Source(), calls)
        m.start("grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d")
        runCurrent()
        val s = sfu.sessions.single()
        s.listener.onData("risi.cal", risi, req.toByteArray())
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(s.data.isEmpty())
    }

    @Test fun theSettingDefaultsOnAndTheTranscriptNote() {
        val flag = CallRisiFlag(null)
        assertTrue(flag.calTitles.value)
        flag.setCalTitles(false)
        assertFalse(flag.calTitles.value)
        assertTrue(CallRisiRules.advertiseCal(true) && !CallRisiRules.advertiseCal(false))

        val t = CallTranscript.parseJson(
            """{"risi":{"kind":"risi_call_transcript","call_id":"c1","lines":[{"who":"risi","text":"10:00–10:30 busy.","titles_spoken":true},{"who":"user","text":"thanks"}]}}""",
        )!!
        assertTrue(t.lines[0].titlesSpoken)
        assertFalse(t.lines[1].titlesSpoken)
        assertEquals("Meeting titles were said aloud, not saved.", CallRisiTexts.TITLES_SPOKEN)
    }
}
