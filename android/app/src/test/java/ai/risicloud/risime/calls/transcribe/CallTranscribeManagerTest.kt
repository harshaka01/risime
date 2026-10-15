package ai.risicloud.risime.calls.transcribe

import ai.risicloud.risime.calls.CallPhase
import ai.risicloud.risime.calls.CallSnapshot
import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.net.CallTranscribeChunkLimits
import ai.risicloud.risime.net.CallTranscribeActions
import ai.risicloud.risime.net.CallTranscribeConsent
import ai.risicloud.risime.net.CallTranscribeErrors
import ai.risicloud.risime.net.CallTranscribePush
import ai.risicloud.risime.net.CallTranscribeReply
import ai.risicloud.risime.net.CallTranscribeSession
import ai.risicloud.risime.net.CallTranscribeStates
import ai.risicloud.risime.realtime.ConnectionState
import ai.risicloud.risime.realtime.PushResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §36 end to end on one phone with fakes: the Private call sends nothing but the automatic decline; an
 * Official `on` records only the tap (this phone's own microphone) into seq-ordered chunks; `409 stopped`
 * stops recording at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallTranscribeManagerTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val peer = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val callId = "4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10"
    private val dm = "dm:${peer}_$me"
    private val grp = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"

    @After
    fun tearDown() {
        CallMicTap.sink = null
        CallMicTap.setInjecting(false)
    }

    private class Fake(val nowMs: () -> Long) : TranscribePort {
        val pushes = mutableListOf<CallTranscribePush>()
        val uploads = mutableListOf<EncodedChunk>()
        var uploadResult: UploadResult = UploadResult.Accepted(false)
        var fetched = 0
        override suspend fun me() = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
        override fun available() = true to true
        override fun officialConversationOf(dm: String) = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
        override suspend fun push(payload: JsonObject): PushResult<JsonObject> {
            pushes += CallTranscribePush.parse(payload)!!
            return PushResult.Rejected("bad_state")
        }
        override suspend fun fetch(callId: String): ApiResult<CallTranscribeReply> {
            fetched++
            return ApiResult.Ok(CallTranscribeReply(null))
        }
        override suspend fun upload(callId: String, chunk: EncodedChunk): UploadResult {
            uploads += EncodedChunk(chunk.sessionId, chunk.seq, chunk.offsetMs, chunk.durationMs, chunk.audio.copyOf())
            return uploadResult
        }
        override fun serverNow() = nowMs()
    }

    private fun snap(official: Boolean, phase: CallPhase = CallPhase.ACTIVE) =
        CallSnapshot(callId, dm, peer, outgoing = true, phase = phase, official = official)

    private fun session(
        state: String,
        mine: String = CallTranscribeConsent.ALLOWED,
        requestedBy: String = me,
        chunk: CallTranscribeChunkLimits = CallTranscribeChunkLimits(),
    ) = CallTranscribeSession(
        sessionId = "s1", callId = callId, conversationId = grp, state = state, requestedBy = requestedBy,
        startedAt = if (state == CallTranscribeStates.ON) "1970-01-01T00:00:00Z" else null,
        participants = listOf(CallTranscribeConsent(me, mine), CallTranscribeConsent(peer, CallTranscribeConsent.ALLOWED)),
        chunk = chunk,
    )

    private val vadChunk = CallTranscribeChunkLimits(minMs = 1000, maxMs = 4000, mode = "vad", overlapMs = 500)

    private fun TestScope.setup(official: Boolean, liveFast: Boolean = true): Triple<CallTranscribeManager, Fake, MutableStateFlow<CallSnapshot?>> {
        val fake = Fake { testScheduler.currentTime }
        val calls = MutableStateFlow<CallSnapshot?>(snap(official))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val m = CallTranscribeManager(
            backgroundScope, fake, calls, MutableStateFlow(ConnectionState.Live),
            encoder = { pcm, _ -> ByteArray(64) { (pcm.firstOrNull() ?: 0).toByte() } },
            encodeDispatcher = dispatcher,
            nowMs = { testScheduler.currentTime },
            liveFast = { liveFast },
        )
        m.start()
        runCurrent()
        return Triple(m, fake, calls)
    }

    /** 1 s of 48 kHz mono PCM16 from the "microphone" (the ADM's record samples). */
    private fun mic(value: Short = 1000): ByteArray {
        val b = ByteArray(48_000 * 2)
        for (i in 0 until 48_000) {
            b[i * 2] = value.toByte()
            b[i * 2 + 1] = (value.toInt() shr 8).toByte()
        }
        return b
    }

    @Test
    fun aPrivateCallDeclinesAutomaticallyAndUploadsNothing() = runTest {
        val (m, fake, _) = setup(official = false)
        assertFalse(m.view.value.button)
        m.onSignal(session(CallTranscribeStates.REQUESTED, mine = CallTranscribeConsent.PENDING, requestedBy = peer))
        runCurrent()
        assertEquals(CallTranscribeActions.DECLINE, fake.pushes.single().action)
        assertEquals(CallTranscribeStates.REASON_PRIVATE_CALL, fake.pushes.single().reason)
        m.onSignal(session(CallTranscribeStates.ON))
        runCurrent()
        repeat(30) { CallMicTap.onMicrophone(mic(), 48_000, 1) }
        advanceTimeBy(30_000)
        runCurrent()
        assertNull(m.recording.value)
        assertTrue("a Private call never uploads", fake.uploads.isEmpty())
        assertFalse(m.view.value.bar)
    }

    @Test
    fun anOfficialSessionRecordsOnlyTheOwnMicrophoneInChunksAndStopsOn409() = runTest {
        val (m, fake, _) = setup(official = true)
        assertTrue(m.view.value.button)
        m.request()
        runCurrent()
        assertEquals(CallTranscribeActions.REQUEST, fake.pushes.single().action)
        assertTrue("nothing before consent", fake.uploads.isEmpty())
        m.onSignal(session(CallTranscribeStates.ON))
        runCurrent()
        assertTrue(m.view.value.bar)
        assertEquals("s1", m.recording.value)
        // 25 s of microphone audio → two 12-s chunks, seq 0 and 1.
        repeat(25) { CallMicTap.onMicrophone(mic(), 48_000, 1) }
        runCurrent()
        assertEquals(listOf(0, 1), fake.uploads.map { it.seq })
        assertEquals(listOf(12_000L, 12_000L), fake.uploads.map { it.durationMs })
        assertEquals(12_000L, fake.uploads[1].offsetMs - fake.uploads[0].offsetMs)
        // The server stops it: 409 stopped → recording off at once, the bar goes, nothing more is sent.
        fake.uploadResult = UploadResult.Failed(409, CallTranscribeErrors.STOPPED)
        repeat(12) { CallMicTap.onMicrophone(mic(), 48_000, 1) }
        runCurrent()
        assertNull(m.recording.value)
        assertFalse(m.view.value.bar)
        assertNull("the tap is released", CallMicTap.sink)
        val n = fake.uploads.size
        repeat(30) { CallMicTap.onMicrophone(mic(), 48_000, 1) }
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(n, fake.uploads.size)
    }

    @Test
    fun silenceKeepsTheSessionAliveWhenTheMicrophoneIsQuiet() = runTest {
        val (m, fake, _) = setup(official = true)
        m.onSignal(session(CallTranscribeStates.ON))
        runCurrent()
        advanceTimeBy(26_000)
        runCurrent()
        assertTrue("silent keep-alive chunks", fake.uploads.size >= 2)
        assertTrue(fake.uploads.all { c -> c.audio.all { it.toInt() == 0 } })
        m.stop(discard = false)
        runCurrent()
        assertEquals(CallTranscribeActions.STOP, fake.pushes.last().action)
    }

    @Test
    fun hangUpSendsStopEndedAndRecordingStops() = runTest {
        val (m, fake, calls) = setup(official = true)
        m.onSignal(session(CallTranscribeStates.ON))
        runCurrent()
        assertEquals("s1", m.recording.value)
        calls.value = snap(official = true, phase = CallPhase.ENDED)
        runCurrent()
        val stop = fake.pushes.last()
        assertEquals(CallTranscribeActions.STOP, stop.action)
        assertEquals(CallTranscribeStates.REASON_ENDED, stop.reason)
        assertNull(m.recording.value)
    }

    @Test
    fun injectedAudioReplacesTheMicrophoneOnlyWhileInjecting() = runTest {
        val (m, fake, _) = setup(official = true)
        m.onSignal(session(CallTranscribeStates.ON))
        runCurrent()
        CallMicTap.setInjecting(true)
        repeat(13) {
            CallMicTap.onMicrophone(mic(1000), 48_000, 1) // ignored while injecting
            val inj = mic(2000)
            CallMicTap.onInjected(inj, inj.size, 48_000, 1)
        }
        runCurrent()
        assertEquals(1, fake.uploads.size)
        // The fake encoder writes the first sample's low byte: 2000 → 0xD0 (the injected audio, not the mic's 1000 → 0xE8).
        assertEquals(2000.toShort().toByte(), fake.uploads[0].audio[0])
    }

    @Test
    fun aFastSessionCutsAtFourSecondsWithOverlap() = runTest {
        val (m, fake, _) = setup(official = true)
        m.onSignal(session(CallTranscribeStates.ON, chunk = vadChunk))
        runCurrent()
        assertEquals("s1", m.recording.value)
        // 8 s of steady speech: cut at 4 s, the next one starts 0.5 s before the cut.
        repeat(8) { CallMicTap.onMicrophone(mic(), 48_000, 1) }
        runCurrent()
        assertEquals(listOf(0, 1), fake.uploads.map { it.seq })
        assertEquals(listOf(4_000L, 4_000L), fake.uploads.map { it.durationMs })
        assertEquals(listOf(0L, 3_500L), fake.uploads.map { it.offsetMs })
    }

    @Test
    fun theDeveloperFlagOffKeepsTwelveSecondChunksInAFastSession() = runTest {
        val (m, fake, _) = setup(official = true, liveFast = false)
        m.onSignal(session(CallTranscribeStates.ON, chunk = vadChunk))
        runCurrent()
        repeat(13) { CallMicTap.onMicrophone(mic(), 48_000, 1) }
        runCurrent()
        assertEquals(listOf(12_000L), fake.uploads.map { it.durationMs })
    }

    @Test
    fun anOldServersSessionWithoutModeKeepsTwelveSecondChunks() = runTest {
        val (m, fake, _) = setup(official = true)
        m.onSignal(session(CallTranscribeStates.ON))
        runCurrent()
        repeat(13) { CallMicTap.onMicrophone(mic(), 48_000, 1) }
        runCurrent()
        assertEquals(listOf(12_000L), fake.uploads.map { it.durationMs })
    }
}
