package ai.risicloud.risime.calls.risi

import ai.risicloud.risime.calls.FakeGroupPort
import ai.risicloud.risime.calls.FakeSfu
import ai.risicloud.risime.calls.GroupCallMachine
import ai.risicloud.risime.calls.GroupCallPort
import ai.risicloud.risime.calls.MemMarks
import ai.risicloud.risime.calls.RoomOutcome
import ai.risicloud.risime.calls.SfuParticipant
import ai.risicloud.risime.calls.CryptorState
import ai.risicloud.risime.net.CallRisiStt153
import ai.risicloud.risime.net.CallsRoomReply
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.RisiCallReply
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract v1.53 §41.10 on the phone: nothing is sent on `risi.stt` before Risi's `mode: "phone"`, after the call ends,
 * or to anyone but `risi_identity`; partials ≤ 5/s; finals carry `seq`, `conf`, `lang` and `during_risi` while Risi
 * speaks; `off` stops it for the call; the capability only with an on-device recogniser; never a networked recogniser.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallRisiSttTest {
    private val risi = "u-risi/risi-dev"
    private val mode = """{"t": "mode", "mode": "phone"}""".encodeToByteArray()
    private fun speaking(on: Boolean) = """{"t": "speaking", "on": $on}""".encodeToByteArray()

    private class FakeEngine : CallSttEngine {
        var events: CallSttEvents? = null
        var released = false
        var starts = 0
        override fun start(events: CallSttEvents) { starts++; this.events = events }
        override fun release() { released = true }
    }

    private class Rig(val stt: CallRisiStt, val sent: MutableList<String>, val engines: MutableList<FakeEngine>, var clock: Long) {
        val engine get() = engines.last()
        val json get() = sent.map { ProtocolJson.parseToJsonElement(it) as JsonObject }
    }

    private fun rig(available: Boolean = true): Rig {
        val sent = mutableListOf<String>()
        val engines = mutableListOf<FakeEngine>()
        lateinit var r: Rig
        val stt = CallRisiStt(risi, engine = { if (available) FakeEngine().also { engines += it } else null }, send = { sent += it.decodeToString() }, now = { r.clock })
        r = Rig(stt, sent, engines, 1_000_000L)
        return r
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun nothingIsSentOrRecognisedBeforeModePhone() {
        val r = rig()
        assertTrue(r.engines.isEmpty())
        r.stt.onData(CallRisiStt153.TOPIC, risi, speaking(true))
        assertTrue(r.engines.isEmpty())
        assertTrue(r.sent.isEmpty())
        r.stt.onData(CallRisiStt153.TOPIC, risi, """{"t":"mode","mode":"server"}""".encodeToByteArray())
        assertTrue(r.engines.isEmpty())
        r.stt.onData(CallRisiStt153.TOPIC, risi, mode)
        assertEquals(1, r.engine.starts)
        assertTrue(r.stt.listening)
        assertTrue(r.sent.isEmpty())
    }

    @Test fun otherTopicsSendersAndMalformedPacketsAreIgnored() {
        val r = rig()
        r.stt.onData("risi.cal", risi, mode)
        r.stt.onData(null, risi, mode)
        r.stt.onData(CallRisiStt153.TOPIC, "u-b/b1", mode)
        r.stt.onData(CallRisiStt153.TOPIC, null, mode)
        r.stt.onData(CallRisiStt153.TOPIC, risi, "not json".encodeToByteArray())
        r.stt.onData(CallRisiStt153.TOPIC, risi, """{"t":"mode","mode":"other"}""".encodeToByteArray())
        r.stt.onData(CallRisiStt153.TOPIC, risi, """{"t":"mode"}""".encodeToByteArray())
        r.stt.onData(CallRisiStt153.TOPIC, risi, """["mode"]""".encodeToByteArray())
        assertTrue(r.engines.isEmpty())
        r.stt.onData(CallRisiStt153.TOPIC, risi.uppercase(), mode)
        assertEquals(1, r.engines.size)
    }

    @Test fun partialsAndFinalsWithSeqConfLangAndThrottle() {
        val r = rig()
        r.stt.onData(CallRisiStt153.TOPIC, risi, mode)
        val e = r.engine.events!!
        e.partial("do I")
        r.clock += 100
        e.partial("do I have") // < 200 ms after the last one: dropped (≤ 5/s)
        r.clock += 150
        e.partial("do I have anything")
        e.final("Do I have anything to do today?", 0.91f)
        val j = r.json
        assertEquals(listOf("partial", "partial", "final"), j.map { it.s("t") })
        assertEquals("do I have anything", j[1].s("text"))
        assertEquals(listOf("1", "1", "1"), j.map { it.s("seq") })
        assertEquals("0.91", j[2].s("conf"))
        assertEquals("en", j[2].s("lang"))
        assertFalse("during_risi" in j[2])
        // The next utterance: seq 2; no partial before; conf null when none.
        e.final("And tomorrow?", null)
        val f2 = r.json.last()
        assertEquals("2", f2.s("seq"))
        assertTrue(f2["conf"] is kotlinx.serialization.json.JsonNull)
        // Nothing recognised: nothing sent (seq still grows by one per utterance that sends).
        e.final("   ", 0.5f)
        assertEquals(4, r.sent.size)
        e.final("x".repeat(1_500), 0.7f)
        assertEquals(CallRisiStt153.MAX_TEXT, r.json.last().s("text").length)
        assertEquals("3", r.json.last().s("seq"))
    }

    @Test fun finalsWhileRisiSpeaksAreMarked() {
        val r = rig()
        r.stt.onData(CallRisiStt153.TOPIC, risi, mode)
        r.stt.onData(CallRisiStt153.TOPIC, risi, speaking(true))
        r.engine.events!!.final("Risi stop", 0.8f)
        assertEquals("true", r.json.last().s("during_risi"))
        r.stt.onData(CallRisiStt153.TOPIC, risi, speaking(false))
        r.engine.events!!.final("thanks", 0.8f)
        assertFalse("during_risi" in r.json.last())
    }

    @Test fun offIsSentOnceAndStopsEverything() {
        val r = rig()
        r.stt.onData(CallRisiStt153.TOPIC, risi, mode)
        val e = r.engine.events!!
        e.off(CallRisiStt153.OFF_LANGUAGE)
        assertTrue(r.engine.released)
        assertEquals("""{"t":"off","reason":"language"}""", r.sent.single())
        e.off(CallRisiStt153.OFF_ERROR)
        e.final("late", 0.9f)
        e.partial("late")
        r.stt.onData(CallRisiStt153.TOPIC, risi, mode) // no restart after off in this call
        assertEquals(1, r.sent.size)
        assertEquals(1, r.engines.size)
    }

    @Test fun noOnDeviceRecogniserSendsOffUnavailableAfterMode() {
        val r = rig(available = false)
        r.stt.onData(CallRisiStt153.TOPIC, risi, mode)
        assertEquals("""{"t":"off","reason":"unavailable"}""", r.sent.single())
    }

    @Test fun modeServerStopsTheRecogniserAndItsText() {
        val r = rig()
        r.stt.onData(CallRisiStt153.TOPIC, risi, mode)
        val e = r.engine.events!!
        r.stt.onData(CallRisiStt153.TOPIC, risi, """{"t":"mode","mode":"server"}""".encodeToByteArray())
        assertTrue(r.engine.released)
        e.final("late", 0.9f)
        assertTrue(r.sent.isEmpty())
    }

    @Test fun nothingAfterTheCallEnds() {
        val r = rig()
        r.stt.onData(CallRisiStt153.TOPIC, risi, mode)
        val e = r.engine.events!!
        r.stt.end()
        assertTrue(r.engine.released)
        assertFalse(r.stt.listening)
        e.partial("hello")
        e.final("hello", 0.9f)
        e.off(CallRisiStt153.OFF_ERROR)
        r.stt.onData(CallRisiStt153.TOPIC, risi, mode)
        assertTrue(r.sent.isEmpty())
        assertEquals(1, r.engines.size)
    }

    // ---- the capability ----

    @Test fun capabilityOnlyWithCallRisiApi33AndAnOnDeviceRecogniser() {
        assertTrue(CallRisiSttRules.advertise(callRisi = true, sdk = 33) { true })
        assertFalse(CallRisiSttRules.advertise(callRisi = true, sdk = 33) { false })
        assertFalse(CallRisiSttRules.advertise(callRisi = true, sdk = 33) { throw SecurityException() })
        assertFalse(CallRisiSttRules.advertise(callRisi = true, sdk = 32) { true }) // no EXTRA_AUDIO_SOURCE
        var asked = false
        assertFalse(CallRisiSttRules.advertise(callRisi = false, sdk = 36) { asked = true; true })
        assertFalse(asked)
    }

    @Test fun errorsMapToOffReasonsOrTheNextUtterance() {
        assertEquals(CallRisiStt153.OFF_LANGUAGE, CallRisiSttErrors.offReason(12, 0))
        assertEquals(CallRisiStt153.OFF_LANGUAGE, CallRisiSttErrors.offReason(13, 0))
        assertNull(CallRisiSttErrors.offReason(7, 3)) // silence keeps listening
        assertNull(CallRisiSttErrors.offReason(6, 0))
        assertNull(CallRisiSttErrors.offReason(8, 1))
        assertEquals(CallRisiStt153.OFF_BUSY, CallRisiSttErrors.offReason(8, CallRisiSttErrors.MAX_QUICK_FAILS))
        assertEquals(CallRisiStt153.OFF_ERROR, CallRisiSttErrors.offReason(5, CallRisiSttErrors.MAX_QUICK_FAILS))
        assertEquals(CallRisiStt153.OFF_ERROR, CallRisiSttErrors.offReason(9, 0))
    }

    /** No networked fallback: the Call Risi path uses only the on-device construction point, never createSpeechRecognizer. */
    @Test fun noNetworkedRecogniserInTheCallRisiPath() {
        val main = listOf(File("src/main"), File("app/src/main"), File("android/app/src/main")).first { it.isDirectory }
        val dir = File(main, "java/ai/risicloud/risime/calls/risi")
        val files = dir.listFiles()!!.filter { it.extension == "kt" }
        assertTrue(files.any { it.name == "OnDeviceCallStt.kt" })
        for (f in files) {
            val t = f.readText()
            assertFalse(f.name, Regex("""\bcreateSpeechRecognizer\s*\(""").containsMatchIn(t))
            assertFalse(f.name, t.contains("OnlineRecogniser"))
            assertFalse(f.name, t.contains("isRecognitionAvailable("))
            assertFalse(f.name, t.contains("AudioRecord(")) // never a second microphone capture
        }
        val engine = File(dir, "OnDeviceCallStt.kt").readText()
        assertTrue(engine.contains("phone.newOnDevice()"))
        assertTrue(engine.contains("EXTRA_AUDIO_SOURCE"))
        assertTrue(engine.contains("CallMicTap.sttSink = sink"))
        assertTrue(engine.contains("EXTRA_PREFER_OFFLINE, true"))
    }

    // ---- the audio ----

    @Test fun pcmIsDownmixedAndResampledTo16k() {
        fun pcm(vararg s: Int) = ByteArray(s.size * 2).also { b -> s.forEachIndexed { i, v -> b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = (v shr 8).toByte() } }
        fun shorts(b: ByteArray) = (0 until b.size / 2).map { ((b[2 * it].toInt() and 0xFF) or (b[2 * it + 1].toInt() shl 8)).toShort().toInt() }
        // 48 kHz mono: one output per 3 inputs (their average).
        assertEquals(listOf(200, -200), shorts(PcmTo16k().feed(pcm(100, 200, 300, -100, -200, -300), 12, 48_000, 1)))
        // 48 kHz stereo: channels averaged first.
        assertEquals(listOf(150), shorts(PcmTo16k().feed(pcm(100, 200, 100, 200, 100, 200), 12, 48_000, 2)))
        // 16 kHz: unchanged; 8 kHz: each sample twice.
        assertEquals(listOf(1, 2, 3), shorts(PcmTo16k().feed(pcm(1, 2, 3), 6, 16_000, 1)))
        assertEquals(listOf(5, 5, 7, 7), shorts(PcmTo16k().feed(pcm(5, 7), 4, 8_000, 1)))
        // 44.1 kHz: a second of input gives a second of output.
        val one = PcmTo16k().feed(ByteArray(44_100 * 2), 44_100 * 2, 44_100, 1)
        assertEquals(16_000 * 2, one.size)
        // Remainders carry over between frames.
        val c = PcmTo16k()
        assertEquals(0, c.feed(pcm(3, 3), 4, 48_000, 1).size)
        assertEquals(listOf(3), shorts(c.feed(pcm(3), 2, 48_000, 1)))
        assertEquals(0, PcmTo16k().feed(pcm(1), 2, 48_000, 3).size)
    }

    // ---- in the group call machine ----

    private class RisiPort(val base: FakeGroupPort) : GroupCallPort by base {
        override suspend fun risiRoom(conv: String, callId: String): RoomOutcome = RoomOutcome.Ok(
            CallsRoomReply("wss://x/livekit", "room", "u-a/a1", "token-for-u-a/a1", "2026-10-08T10:10:00.000Z", 2,
                risiCall = RisiCallReply("joining", false, risi, 1800, "en")),
        )
        private val risi = "u-risi/risi-dev"
    }

    private fun TestScope.machine(engines: MutableList<FakeEngine>, sttOn: Boolean = true): Pair<GroupCallMachine, FakeSfu> {
        val sfu = FakeSfu()
        val clock = { testScheduler.currentTime + 1_791_451_200_000L }
        val m = GroupCallMachine(
            "u-a", "a1", backgroundScope, sfu, RisiPort(FakeGroupPort("u-a/a1")), MemMarks(), now = clock,
            newCallId = { "3c8e1f4a-9b2d-4e7f-8a61-5d0c2b9e7f13" },
            risiSttEngine = if (sttOn) ({ FakeEngine().also { engines += it } }) else null,
        )
        return m to sfu
    }

    @Test fun machineSendsOnlyToRisiOnlyAfterModeAndNothingAfterHangUp() = runTest {
        val engines = mutableListOf<FakeEngine>()
        val (m, sfu) = machine(engines)
        assertTrue(m.startRisi("grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"))
        runCurrent()
        val s = sfu.sessions.last()
        s.room(SfuParticipant(risi, local = false, hasAudio = true, encrypted = false, cryptor = CryptorState.NONE))
        runCurrent()
        // A packet from someone else, or before mode: nothing starts, nothing is sent.
        s.listener.onData(CallRisiStt153.TOPIC, "u-b/b1", mode)
        runCurrent()
        assertTrue(engines.isEmpty())
        s.listener.onData(CallRisiStt153.TOPIC, risi, mode)
        runCurrent()
        engines.single().events!!.final("Do I have anything to do today?", 0.91f)
        runCurrent()
        val (payload, topic, to) = s.data.single()
        assertEquals(CallRisiStt153.TOPIC, topic)
        assertEquals(listOf(risi), to)
        assertTrue(payload.contains("\"t\":\"final\""))
        m.hangUp()
        runCurrent()
        assertTrue(engines.single().released)
        engines.single().events!!.final("after the end", 0.9f)
        runCurrent()
        assertEquals(1, s.data.size)
    }

    @Test fun withoutAnEngineTheMachineNeverRecognises() = runTest {
        val engines = mutableListOf<FakeEngine>()
        val (m, sfu) = machine(engines, sttOn = false)
        m.startRisi("grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d")
        runCurrent()
        val s = sfu.sessions.last()
        s.listener.onData(CallRisiStt153.TOPIC, risi, mode)
        runCurrent()
        assertTrue(engines.isEmpty())
        assertTrue(s.data.isEmpty())
    }

    /**
     * Harsha's call 2026-10-16 03:55Z: Risi's worker sends `mode: "phone"` when it hears the phone's microphone track,
     * which can be while publishMic() is still running. The session must exist by then (nightly.11 created it after).
     */
    @Test fun modeThatArrivesWhileTheMicIsBeingPublishedIsNotLost() = runTest {
        val engines = mutableListOf<FakeEngine>()
        val (m, sfu) = machine(engines)
        sfu.duringPublishMic = { s -> s.listener.onData(CallRisiStt153.TOPIC, risi, mode) }
        assertTrue(m.startRisi("grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"))
        runCurrent()
        val s = sfu.sessions.last()
        assertTrue(s.micPublished)
        assertEquals(1, engines.single().starts)
        s.room(SfuParticipant(risi, local = false, hasAudio = true, encrypted = false, cryptor = CryptorState.NONE))
        runCurrent()
        engines.single().events!!.final("Do I have anything to do today?", null)
        runCurrent()
        val (payload, topic, to) = s.data.single()
        assertEquals(CallRisiStt153.TOPIC, topic)
        assertEquals(listOf(risi), to)
        assertTrue(payload.contains("\"t\":\"final\""))
    }

    // ---- confidence, endpointing, health (found on Harsha's first call) ----

    /** Android gives -1 or 0.0 when the recogniser has no confidence; the worker drops conf outside 0..1. */
    @Test fun missingConfidenceIsNullNeverMinusOneOrZero() {
        assertNull(CallRisiSttRules.conf(null))
        assertNull(CallRisiSttRules.conf(floatArrayOf()))
        assertNull(CallRisiSttRules.conf(floatArrayOf(-1f)))
        assertNull(CallRisiSttRules.conf(floatArrayOf(0f, 0.5f)))
        assertNull(CallRisiSttRules.conf(floatArrayOf(Float.NaN)))
        assertEquals(0.91f, CallRisiSttRules.conf(floatArrayOf(0.91f, 0.2f)))
        assertEquals(1f, CallRisiSttRules.conf(floatArrayOf(1.5f)))
        for (raw in listOf(-1f, 0f, Float.NaN)) {
            val j = ProtocolJson.parseToJsonElement(CallRisiStt153.final(1, "hello", raw, false).decodeToString()) as JsonObject
            assertTrue("$raw", j["conf"] is kotlinx.serialization.json.JsonNull)
        }
        val ok = ProtocolJson.parseToJsonElement(CallRisiStt153.final(1, "hello", 0.42f, false).decodeToString()) as JsonObject
        assertEquals("0.42", ok.s("conf"))
        // The seq is a JSON integer (the worker keeps only ints).
        assertEquals("1", ok["seq"]!!.jsonPrimitive.content)
        assertFalse(ok["seq"]!!.jsonPrimitive.isString)
    }

    /** 16 kHz mono PCM16 LE: [ms] of a tone at [amp], or silence. */
    private fun tone(ms: Int, amp: Int): ByteArray {
        val n = 16 * ms
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = if (amp == 0) 0 else (amp * kotlin.math.sin(i * 2 * Math.PI * 220 / 16_000)).toInt()
            b[2 * i] = (v and 0xFF).toByte()
            b[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }

    /** Feeds [b] in 10-ms frames (as the call's capture comes); true if the endpointer fired on any. */
    private fun CallSttEndpointer.frames(b: ByteArray): Int {
        var fired = 0
        for (off in b.indices step 320) if (feed(b.copyOfRange(off, minOf(off + 320, b.size)))) fired++
        return fired
    }

    @Test fun thePhoneEndsAnUtteranceAfterSpeechAndQuiet() {
        val e = CallSttEndpointer()
        assertEquals(0, e.frames(tone(2_000, 0))) // quiet before speech: never ends
        assertEquals(0, e.frames(tone(800, 6_000)))
        assertTrue(e.heardSpeech)
        assertEquals(0, e.frames(tone(500, 0))) // a pause inside the sentence
        assertEquals(0, e.frames(tone(300, 6_000)))
        assertEquals(1, e.frames(tone(1_000, 0))) // ≥ 0.9 s quiet after speech: the end
        assertEquals(0, e.frames(tone(3_000, 0))) // only once
        assertEquals(0, e.frames(tone(500, 6_000)) + e.frames(tone(1_000, 0)))
        // A click (< 240 ms voiced) and quiet hum under the threshold never end an utterance nor count as speech.
        val c = CallSttEndpointer()
        assertEquals(0, c.frames(tone(100, 6_000)) + c.frames(tone(2_000, 0)) + c.frames(tone(2_000, 150)))
        assertFalse(c.heardSpeech)
    }

    @Test fun aRecogniserThatHearsNothingOverClearSpeechStopsWithOff() {
        val h = CallSttHealth()
        assertNull(h.ended(words = false, heardSpeech = true, error = 7))
        assertEquals(CallRisiStt153.OFF_ERROR, h.ended(words = false, heardSpeech = true, error = 6))
        // Words reset it; quiet "no speech" / "no match" (the caller is silent) never count.
        val q = CallSttHealth()
        assertNull(q.ended(words = false, heardSpeech = true, error = 7))
        assertNull(q.ended(words = true, heardSpeech = true))
        assertNull(q.ended(words = false, heardSpeech = true, error = null))
        assertNull(q.ended(words = false, heardSpeech = false, error = 6))
        assertNull(q.ended(words = false, heardSpeech = true, error = 7))
        repeat(50) { assertNull(CallSttHealth().ended(words = false, heardSpeech = false, error = 6)) }
        val quiet = CallSttHealth()
        repeat(50) { assertNull(quiet.ended(words = false, heardSpeech = false, error = 7)) }
        // Slow errors in a row (not only quick ones) end it too.
        val e = CallSttHealth()
        repeat(CallSttHealth.MAX_ERRORS - 1) { assertNull(e.ended(words = false, heardSpeech = false, error = 3)) }
        assertEquals(CallRisiStt153.OFF_ERROR, e.ended(words = false, heardSpeech = false, error = 11))
    }
}
