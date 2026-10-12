package ai.risicloud.risime.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §37.3 the recorder: frames → encoder + per-frame peaks → a 64-level waveform; < 0.5 s discarded; cancel encodes nothing. */
class VoiceRecorderTest {
    /** [frames] 20 ms frames of a rising tone, as fast as read (no pacing), then the source ends (-1 = stop reading). */
    private class FakeSource(private val frames: Int) : PcmSource {
        override val rate = 48_000
        var started = false
        var stopped = false
        private var n = 0

        override fun start(): Boolean { started = true; return true }

        override fun read(buf: ShortArray): Int {
            if (n >= frames) { Thread.sleep(5); return 0 }
            val amp = (1_000 + n * 100).coerceAtMost(32_000)
            for (i in buf.indices) buf[i] = (if (i % 2 == 0) amp else -amp).toShort()
            n++
            return buf.size
        }

        override fun stop() { stopped = true }
    }

    private class FakeEncoder : VoiceEncoder {
        var fed = 0
        var finished = false
        var released = false

        override fun feed(pcm: ShortArray, n: Int) { fed += n }

        override fun finish(): ByteArray? { finished = true; released = true; return "OggS".toByteArray() + ByteArray(fed / 960) }

        override fun release() { released = true }
    }

    private fun waitFor(cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < until) Thread.sleep(5)
    }

    @Test fun recordsANoteWithItsDurationAndWaveform() = runBlocking {
        val enc = FakeEncoder()
        val src = FakeSource(frames = 150) // 3 s
        val rec = VoiceRecorder(newEncoder = { enc }, newSource = { src })
        assertTrue(rec.start())
        assertTrue(src.started)
        waitFor { enc.fed >= 150 * 960 }
        val note = rec.stop()
        assertNotNull(note)
        assertEquals(3_000L, note!!.durationMs)
        assertEquals(64, note.waveform.size)
        assertEquals(255, note.waveform.last().toInt() and 0xFF) // rising: the loudest at the end
        assertTrue(note.waveform.first().toInt() and 0xFF in 1..254)
        assertTrue(enc.finished && src.stopped)
        assertEquals(VoiceRecorder.State.Idle, rec.state.value)
    }

    @Test fun aNoteUnderHalfASecondIsDiscarded() = runBlocking {
        val enc = FakeEncoder()
        val rec = VoiceRecorder(newEncoder = { enc }, newSource = { FakeSource(frames = 10) }) // 200 ms
        assertTrue(rec.start())
        waitFor { enc.fed >= 10 * 960 }
        assertNull(rec.stop())
        assertFalse(enc.finished)
        assertTrue(enc.released)
    }

    @Test fun cancelEncodesNothing() {
        val enc = FakeEncoder()
        val src = FakeSource(frames = 100)
        val rec = VoiceRecorder(newEncoder = { enc }, newSource = { src })
        assertTrue(rec.start())
        waitFor { enc.fed > 0 }
        rec.cancel()
        assertFalse(enc.finished)
        assertTrue(enc.released && src.stopped)
        assertFalse(rec.recording)
    }

    @Test fun aMicrophoneThatDoesNotStartIsReported() {
        val enc = FakeEncoder()
        val rec = VoiceRecorder(newEncoder = { enc }, newSource = {
            object : PcmSource {
                override val rate = 48_000
                override fun start() = false
                override fun read(buf: ShortArray) = -1
                override fun stop() = Unit
            }
        })
        assertFalse(rec.start())
        assertTrue(enc.released)
    }
}
