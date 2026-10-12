package ai.risicloud.risime.calls.transcribe

import ai.risicloud.risime.net.CallTranscribeErrors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §36.4 the upload errors and the 2-chunk memory limit. */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscribeUploaderTest {

    private fun chunk(seq: Int) = EncodedChunk("s1", seq, seq * 12_000L, 12_000L, ByteArray(100) { 1 })

    @Test
    fun theErrorTable() {
        fun f(status: Int, code: String, ra: Long? = null) = UploadPolicy.classify(UploadResult.Failed(status, code, ra))
        assertEquals(UploadAction.Done, UploadPolicy.classify(UploadResult.Accepted(false)))
        assertEquals(UploadAction.Done, UploadPolicy.classify(UploadResult.Accepted(true)))
        assertEquals(UploadAction.Stop(CallTranscribeErrors.STOPPED, false), f(409, CallTranscribeErrors.STOPPED))
        assertEquals(UploadAction.Stop(CallTranscribeErrors.NOT_CONSENTED, true), f(409, CallTranscribeErrors.NOT_CONSENTED))
        assertEquals(UploadAction.Stop(CallTranscribeErrors.NOT_OFFICIAL, true), f(403, CallTranscribeErrors.NOT_OFFICIAL))
        assertEquals(UploadAction.Stop(CallTranscribeErrors.INVALID_DEVICE, true), f(403, CallTranscribeErrors.INVALID_DEVICE))
        assertEquals(UploadAction.Stop(CallTranscribeErrors.NOT_FOUND, true), f(404, CallTranscribeErrors.NOT_FOUND))
        assertEquals(UploadAction.Backoff(7_000), f(429, CallTranscribeErrors.RATE_LIMITED, 7))
        assertEquals(UploadAction.Backoff(UploadPolicy.RATE_LIMIT_DEFAULT_MS), f(429, CallTranscribeErrors.RATE_LIMITED))
        assertEquals(UploadAction.Stop(CallTranscribeErrors.TRANSCRIBE_OFF, false), f(503, CallTranscribeErrors.TRANSCRIBE_OFF))
        assertEquals(UploadAction.RetryOnce(5_000), f(503, CallTranscribeErrors.TRANSCRIBE_UNAVAILABLE))
        assertEquals(UploadAction.Drop(CallTranscribeErrors.TOO_LARGE), f(413, CallTranscribeErrors.TOO_LARGE))
        assertEquals(UploadAction.Drop(CallTranscribeErrors.BAD_REQUEST), f(422, CallTranscribeErrors.BAD_REQUEST))
        assertTrue(UploadPolicy.classify(UploadResult.Offline) is UploadAction.Backoff)
    }

    @Test
    fun offlineHoldsAtMostTwoChunksAndResendsThemWithTheirSeq() = runTest {
        var online = false
        val sent = mutableListOf<Int>()
        val u = TranscribeUploader(backgroundScope, send = { c -> if (online) UploadResult.Accepted(false).also { sent += c.seq } else UploadResult.Offline }, onStop = {})
        val chunks = (0 until 4).map(::chunk)
        chunks.forEach { u.offer(it); runCurrent() }
        assertEquals(2, u.held)
        assertTrue("dropped chunks are zeroed", chunks[0].audio.all { it.toInt() == 0 } && chunks[1].audio.all { it.toInt() == 0 })
        online = true
        u.kick()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(2, 3), sent)
        assertEquals(0, u.held)
        assertTrue("sent chunks are zeroed", chunks[2].audio.all { it.toInt() == 0 })
    }

    @Test
    fun stopped409StopsAtOnceAndNothingMoreIsSent() = runTest {
        var calls = 0
        var stopped: UploadAction.Stop? = null
        val u = TranscribeUploader(backgroundScope, send = { calls++; UploadResult.Failed(409, CallTranscribeErrors.STOPPED) }, onStop = { stopped = it })
        u.offer(chunk(0))
        u.offer(chunk(1))
        runCurrent()
        assertEquals(CallTranscribeErrors.STOPPED, stopped!!.code)
        assertTrue(u.stopped)
        assertEquals(0, u.held)
        u.offer(chunk(2))
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun unavailableRetriesTheSameSeqOnceAfterFiveSecondsThenDrops() = runTest {
        val seqs = mutableListOf<Int>()
        val u = TranscribeUploader(backgroundScope, send = { c -> seqs += c.seq; if (c.seq == 0) UploadResult.Failed(503, CallTranscribeErrors.TRANSCRIBE_UNAVAILABLE) else UploadResult.Accepted(false) }, onStop = {})
        u.offer(chunk(0))
        runCurrent()
        assertEquals(listOf(0), seqs)
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(listOf(0), seqs)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(0, 0), seqs)
        u.offer(chunk(1))
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(listOf(0, 0, 1), seqs)
        assertEquals(1, u.accepted)
    }

    @Test
    fun rateLimitedBacksOffAndKeepsTheChunk() = runTest {
        var n = 0
        val u = TranscribeUploader(backgroundScope, send = { n++; if (n == 1) UploadResult.Failed(429, CallTranscribeErrors.RATE_LIMITED, 3) else UploadResult.Accepted(false) }, onStop = {})
        u.offer(chunk(0))
        runCurrent()
        assertEquals(1, u.held)
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(0, u.held)
        assertEquals(1, u.accepted)
    }
}
