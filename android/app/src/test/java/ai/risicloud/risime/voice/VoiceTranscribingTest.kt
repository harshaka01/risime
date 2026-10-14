package ai.risicloud.risime.voice

import ai.risicloud.risime.data.PresenceTracker
import ai.risicloud.risime.net.ApiClient
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.RisiVoiceTranscript
import ai.risicloud.risime.net.Signal
import ai.risicloud.risime.net.VoiceTranscribing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proposal 2026-10-14-transcribe-progress (v1.48 §37.11) and the phone-transcript fallback's `source: "phone"`. */
class VoiceTranscribingTest {
    private val example = javaClass.classLoader!!.getResource("fixtures/voice_transcribing/signal_voice_transcribing.json")!!.readText()
    private val conv = "grp:6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f"
    private val id = "0e4f3a20-a95b-11f1-8000-0242ac120002"

    private fun sig(state: String, seq: Int, canCancel: Boolean = false, canRetry: Boolean = false, language: String? = "si") =
        VoiceTranscribing(conv, id, state, language, seq, canCancel, canRetry)

    @Test fun theExampleParses() {
        val s = ProtocolJson.decodeFromString(Signal.serializer(), example)
        val v = s.voiceTranscribing()!!
        assertEquals(id, v.messageId)
        assertEquals(VoiceTranscribing.WORKING, v.state)
        assertEquals("si", v.language)
        assertEquals(1, v.seq)
        assertTrue(v.canCancel)
        assertFalse(v.canRetry)
        // Other kinds don't parse as it, and it doesn't parse as them.
        assertNull(s.risiProgress())
        assertNull(Signal("risi_progress", s.data).voiceTranscribing())
    }

    @Test fun thePresenceTrackerHandsItOver() {
        var got: VoiceTranscribing? = null
        val p = PresenceTracker(CoroutineScope(Dispatchers.Unconfined), onVoiceTranscribing = { got = it })
        p.onSignal(ProtocolJson.decodeFromString(Signal.serializer(), example))
        assertEquals(id, got?.messageId)
    }

    @Test fun aLowerOrEqualSeqIsDroppedAndDoneRemoves() {
        val st = VoiceTranscribingStore(now = { 0 })
        st.apply(sig("failed", 3, canRetry = true))
        st.apply(sig("working", 2, canCancel = true))
        assertEquals("failed", st.lineFor(id, 6_000, 0)!!.state)
        st.apply(sig("failed", 3))
        assertTrue(st.lineFor(id, 6_000, 0)!!.signal.canRetry)
        st.apply(sig("done", 4))
        assertNull(st.lineFor(id, 6_000, 0))
    }

    @Test fun anUnknownStateIsIgnored() {
        val st = VoiceTranscribingStore(now = { 0 })
        st.apply(sig("working", 1, canCancel = true))
        st.apply(sig("slow", 2))
        assertEquals("working", st.lineFor(id, 6_000, 0)!!.state)
        st.apply(sig("weird", 1))
        assertEquals(1, st.lines.value.size)
    }

    @Test fun theLinePerState() {
        fun view(s: VoiceTranscribing) = VoiceTranscribingStore.view(VoiceTranscribingStore.Line(s, 0))
        assertEquals(VoiceTranscribingStore.View("Transcribing (Sinhala)…", "Stop"), view(sig("working", 1, canCancel = true)))
        assertEquals(VoiceTranscribingStore.View("Transcribing (Tamil)…", null), view(sig("working", 1, language = "ta")))
        assertEquals("Transcribing…", view(sig("working", 1, language = "en"))!!.text)
        assertEquals("Transcribing…", view(sig("working", 1, language = null))!!.text)
        assertEquals(VoiceTranscribingStore.View("Transcription stopped", "Try again"), view(sig("cancelled", 2, canRetry = true)))
        assertEquals(VoiceTranscribingStore.View("Transcription stopped", null), view(sig("cancelled", 2)))
        assertEquals(VoiceTranscribingStore.View("Couldn't transcribe", "Try again"), view(sig("failed", 2, canRetry = true)))
        assertEquals(
            VoiceTranscribingStore.View("Can't try again. Send the note again.", null),
            VoiceTranscribingStore.view(VoiceTranscribingStore.Line(sig("failed", 2, canRetry = true), 0, expired = true)),
        )
        assertNull(view(sig("done", 3)))
    }

    @Test fun aQuietWorkingLineDisappears() {
        assertEquals(60_000, VoiceTranscribingStore.quietMs(6_000))
        assertEquals(400_000, VoiceTranscribingStore.quietMs(100_000))
        val st = VoiceTranscribingStore(now = { 1_000 })
        st.apply(sig("working", 1, canCancel = true))
        assertNotNull(st.lineFor(id, 6_000, 60_999))
        assertNull(st.lineFor(id, 6_000, 61_000))
        // A stopped or failed line stays (it has a button).
        st.apply(sig("failed", 2, canRetry = true))
        assertNotNull(st.lineFor(id, 6_000, 10_000_000))
    }

    @Test fun neverPrivateAndOnlyOwnNotes() {
        assertFalse(VoiceTranscribingStore.shows(VoicePlace.PRIVATE, outgoing = true))
        assertFalse(VoiceTranscribingStore.shows(VoicePlace.OFFICIAL, outgoing = false))
        assertTrue(VoiceTranscribingStore.shows(VoicePlace.OFFICIAL, outgoing = true))
        assertTrue(VoiceTranscribingStore.shows(VoicePlace.RISI_CHAT, outgoing = true))
    }

    @Test fun stopAndTryAgainCallTheEndpoints() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"state":"cancelled","can_retry":true}"""))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"state":"working"}"""))
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"expired"}"""))
        server.start()
        try {
            val api = ApiClient(OkHttpClient(), { server.url("/").toString() }, { "t" })
            val st = VoiceTranscribingStore(now = { 0 })
            st.apply(sig("working", 1, canCancel = true))

            st.stop(api, conv, id)
            val r1 = server.takeRequest()
            assertEquals("POST", r1.method)
            assertTrue(r1.path!!.endsWith("me/risi/voice/cancel"))
            val body = r1.body.readUtf8()
            assertTrue(body.contains("\"conversation_id\":\"$conv\"") && body.contains("\"message_id\":\"$id\""))
            var l = st.lineFor(id, 6_000, 0)!!
            assertEquals(VoiceTranscribingStore.View("Transcription stopped", "Try again"), VoiceTranscribingStore.view(l))

            st.retry(api, conv, id)
            assertTrue(server.takeRequest().path!!.endsWith("me/risi/voice/retry"))
            l = st.lineFor(id, 6_000, 0)!!
            assertEquals(VoiceTranscribingStore.View("Transcribing (Sinhala)…", "Stop"), VoiceTranscribingStore.view(l))

            st.cancelled(id, canRetry = true)
            st.retry(api, conv, id)
            l = st.lineFor(id, 6_000, 0)!!
            assertEquals(VoiceTranscribingStore.View("Can't try again. Send the note again.", null), VoiceTranscribingStore.view(l))
        } finally {
            server.shutdown()
        }
    }

    @Test fun stopOnANoteNoLongerRunningRemovesTheLine() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_running"}"""))
        server.start()
        try {
            val st = VoiceTranscribingStore(now = { 0 })
            st.apply(sig("working", 1, canCancel = true))
            st.stop(ApiClient(OkHttpClient(), { server.url("/").toString() }, { "t" }), conv, id)
            assertNull(st.lineFor(id, 6_000, 0))
        } finally {
            server.shutdown()
        }
    }

    @Test fun aPhoneSourcedTranscriptIsMarked() {
        val phone = RisiVoiceTranscript.parse(
            """{"v":1,"kind":"voice_transcript","conversation_id":"$conv","message_id":"$id","status":"ok","text":"hello","language":"si","model":"phone-on-device","source":"phone"}""",
        )!!
        assertTrue(phone.fromPhone)
        val own = RisiVoiceTranscript.parse(
            """{"v":1,"kind":"voice_transcript","conversation_id":"$conv","message_id":"$id","status":"ok","text":"hello","language":"si","model":"qwen3-asr-0.6b-sinhala"}""",
        )!!
        assertFalse(own.fromPhone)
    }
}
