package ai.risicloud.risime.calls.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proposal 2026-10-15-live-call-latency: the fast session's VAD chunker (cut at pauses, 0.5 s overlap, keep-alive). */
class VadChunkerTest {
    private val chunks = mutableListOf<PcmChunk>()
    private val c = VadChunker { chunks += it }.also { it.start(0) }

    /**
     * [ms] of "speech" (-30 dBFS, with one quiet 20-ms frame every 200 ms as between syllables, which keeps the noise
     * floor down) or silence (zeros), fed in 10-ms pieces like the tap.
     */
    private fun feed(ms: Int, speech: Boolean, muted: Boolean = false) {
        val loud = ShortArray(160) { 1000 }
        val quiet = ShortArray(160) { 10 }
        val zero = ShortArray(160)
        for (k in 0 until ms / 10) {
            val piece = when {
                !speech -> zero
                k % 20 == 10 || k % 20 == 11 -> quiet
                else -> loud
            }
            c.feed(piece, piece.size, muted)
        }
    }

    @Test
    fun continuousSpeechIsCutEvery4sWithHalfASecondOfOverlap() {
        feed(11_000, speech = true)
        assertEquals(listOf(0, 1, 2), chunks.map { it.seq })
        assertEquals(listOf(4_000L, 4_000L, 4_000L), chunks.map { it.durationMs })
        assertEquals(listOf(0L, 3_500L, 7_000L), chunks.map { it.offsetMs })
    }

    @Test
    fun aShortUtteranceGoesOutAtItsEnd() {
        feed(1_000, speech = true)
        assertTrue("not before the pause", chunks.isEmpty())
        feed(600, speech = false)
        assertEquals(1, chunks.size)
        assertEquals(0L, chunks[0].offsetMs)
        assertEquals(1_600L, chunks[0].durationMs)
        // The silence after it is not sent again.
        feed(5_000, speech = false)
        assertEquals(1, chunks.size)
    }

    @Test
    fun silenceBeforeSpeechIsTrimmedToHalfASecond() {
        feed(3_000, speech = false)
        feed(1_000, speech = true)
        feed(1_000, speech = false)
        assertEquals(1, chunks.size)
        // 0.5 s lead-in + 1 s speech + 0.5 s pause: cut at the 2-s minimum once the pause is ≥ 300 ms.
        assertEquals(2_500L, chunks[0].offsetMs)
        assertEquals(2_000L, chunks[0].durationMs)
    }

    @Test
    fun aPauseAfterTwoSecondsCuts() {
        feed(2_200, speech = true)
        feed(300, speech = false)
        assertEquals(1, chunks.size)
        assertEquals(2_500L, chunks[0].durationMs)
        feed(1_500, speech = true)
        feed(700, speech = false)
        assertEquals(2, chunks.size)
        // The next chunk starts inside the pause (its 300 ms), never re-sending speech.
        assertEquals(2_200L, chunks[1].offsetMs)
    }

    @Test
    fun silenceSendsAShortKeepAliveEvery15s() {
        feed(31_000, speech = false)
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.durationMs <= 600 })
        assertTrue(chunks.all { ch -> ch.pcm.all { it.toInt() == 0 } })
    }

    @Test
    fun mutedSpeechIsSilence() {
        feed(16_000, speech = true, muted = true)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].durationMs <= 600)
    }

    @Test
    fun theSessionsChunkModeParses() {
        val fast = ai.risicloud.risime.net.ProtocolJson.decodeFromString(
            ai.risicloud.risime.net.CallTranscribeChunkLimits.serializer(),
            """{"min_ms":1000,"max_ms":4000,"max_bytes":262144,"mode":"vad","overlap_ms":500}""",
        )
        assertTrue(fast.vad)
        assertEquals(500, fast.overlapMs)
        val old = ai.risicloud.risime.net.ProtocolJson.decodeFromString(
            ai.risicloud.risime.net.CallTranscribeChunkLimits.serializer(),
            """{"min_ms":5000,"max_ms":20000,"max_bytes":262144}""",
        )
        assertTrue(!old.vad)
        val future = ai.risicloud.risime.net.ProtocolJson.decodeFromString(
            ai.risicloud.risime.net.CallTranscribeChunkLimits.serializer(),
            """{"min_ms":1000,"max_ms":4000,"max_bytes":262144,"mode":"stream"}""",
        )
        assertTrue("an unknown mode keeps the 10–15 s chunks", !future.vad)
    }

    @Test
    fun padSilenceAndResumeKeepOffsetsOnTheSessionClock() {
        c.padSilence(1_000)
        feed(1_000, speech = true)
        c.padSilence(1_000)
        assertEquals(1, chunks.size)
        assertEquals(500L, chunks[0].offsetMs)
        c.dropPartial()
        c.resumeAt(60_000, 60_000)
        feed(1_000, speech = true)
        feed(600, speech = false)
        assertEquals(2, chunks.size)
        assertEquals(1, chunks[1].seq)
        assertEquals(60_000L, chunks[1].offsetMs)
    }
}
