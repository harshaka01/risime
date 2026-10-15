package ai.risicloud.risime.calls.transcribe

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** What the §36 recorder needs from a chunker (the 10–15 s [TranscribeChunker] or the fast [VadChunker]). */
interface MicChunker {
    /** Fed duration since start (ms): the silence filler's clock. */
    val fedMs: Long

    fun start(baseOffsetMs: Long)
    fun feed(pcm: ShortArray, n: Int = pcm.size, muted: Boolean = false)
    fun padSilence(ms: Long)
    fun dropPartial()
    fun resumeAt(offsetMs: Long, fedMs: Long)
    fun clear()
}

/**
 * Proposal 2026-10-15-live-call-latency (P0-1b): the fast session's chunker. It cuts this phone's own microphone
 * at pauses, so each line reaches the other phones about 2 s after it was said:
 * - a chunk closes at a pause of [PAUSE_MS] once it is [MIN_MS] long, at the end of an utterance ([END_MS] of
 *   silence after at least [MIN_SPEECH_MS] of speech) even if shorter, and at [MAX_MS] at the latest;
 * - the next chunk starts up to [OVERLAP_MS] before the cut (the whole overlap after a forced cut; only the
 *   trailing silence after a pause), so a word cut at the edge is never lost; the server drops the repeated words;
 * - silence before speech is not sent (only [PREROLL_MS] of it leads a chunk); a short keep-alive chunk goes out
 *   after [KEEPALIVE_MS] of silence (§36.2 `left` at 90 s).
 * The speech decision is a 20-ms energy gate over an adaptive noise floor (speech = [MARGIN_DB] above the floor
 * and above [ABS_MIN_DB]); the server's speech model has its own VAD, so a false "speech" only costs a chunk.
 * `scripts/call-latency-bench` runs the same rules (keep the constants in step). Pure; memory only; never touches disk.
 */
class VadChunker(
    private val rate: Int = Pcm.RATE,
    private val onChunk: (PcmChunk) -> Unit,
) : MicChunker {
    companion object {
        const val FRAME_MS = 20
        const val MIN_MS = 2_000
        const val MAX_MS = 4_000
        const val PAUSE_MS = 300
        const val END_MS = 600
        const val MIN_SPEECH_MS = 200
        const val OVERLAP_MS = 500
        const val PREROLL_MS = 500
        const val KEEPALIVE_MS = 15_000
        const val MARGIN_DB = 10.0
        const val ABS_MIN_DB = -55.0
        const val FLOOR_START_DB = -60.0

        /** A 20-ms frame's level in dBFS (-120 for digital silence). */
        fun db(frame: ShortArray, n: Int = frame.size): Double {
            var s = 0.0
            for (i in 0 until n) s += frame[i].toDouble() * frame[i]
            val mean = s / max(1, n)
            return if (mean <= 0.0) -120.0 else 10 * log10(mean / (32768.0 * 32768.0))
        }
    }

    private val frameLen = rate * FRAME_MS / 1000
    private val maxFrames = MAX_MS / FRAME_MS
    private val frame = ShortArray(frameLen)
    private var frameFill = 0
    private val buf = ShortArray(maxFrames * frameLen)
    private val flags = BooleanArray(maxFrames)
    private var frames = 0
    private var floor = FLOOR_START_DB
    private var idleFrames = 0
    private var seq = 0

    /** Samples that entered the timeline since [start] (whole frames): chunk offsets. */
    private var timeline = 0L
    private var baseOffsetMs = 0L
    private var started = false

    /** Samples fed since [start] (real audio plus silence), for the silence filler. */
    private var fedSamples = 0L

    override val fedMs: Long get() = fedSamples * 1000 / rate

    override fun start(baseOffsetMs: Long) {
        this.baseOffsetMs = baseOffsetMs.coerceAtLeast(0)
        started = true
    }

    override fun feed(pcm: ShortArray, n: Int, muted: Boolean) {
        if (!started) return
        var i = 0
        while (i < n) {
            val take = min(n - i, frameLen - frameFill)
            if (muted) frame.fill(0, frameFill, frameFill + take) else pcm.copyInto(frame, frameFill, i, i + take)
            frameFill += take
            i += take
            fedSamples += take
            if (frameFill == frameLen) onFrame()
        }
    }

    override fun padSilence(ms: Long) {
        if (!started || ms <= 0) return
        var left = rate * ms / 1000
        while (left > 0) {
            val take = min(left, (frameLen - frameFill).toLong()).toInt()
            frame.fill(0, frameFill, frameFill + take)
            frameFill += take
            left -= take
            fedSamples += take
            if (frameFill == frameLen) onFrame()
        }
    }

    private fun isSpeech(): Boolean {
        val d = db(frame, frameLen)
        floor = if (d < floor) d else floor + (d - floor) * 0.002
        floor = floor.coerceIn(-90.0, -30.0)
        return d > floor + MARGIN_DB && d > ABS_MIN_DB
    }

    private fun onFrame() {
        val sp = isSpeech()
        frame.copyInto(buf, frames * frameLen, 0, frameLen)
        flags[frames] = sp
        frames++
        frameFill = 0
        timeline += frameLen
        var speechFrames = 0
        for (k in 0 until frames) if (flags[k]) speechFrames++
        if (speechFrames == 0) {
            idleFrames++
            val keep = PREROLL_MS / FRAME_MS
            if (idleFrames * FRAME_MS >= KEEPALIVE_MS) {
                emit(0)
                idleFrames = 0
            } else if (frames > keep) {
                shift(frames - keep)
            }
            return
        }
        var sil = 0
        for (k in frames - 1 downTo 0) {
            if (flags[k]) break
            sil++
        }
        val ms = frames * FRAME_MS
        val silMs = sil * FRAME_MS
        when {
            ms >= MAX_MS -> emit(OVERLAP_MS / FRAME_MS)
            ms >= MIN_MS && silMs >= PAUSE_MS -> emit(min(OVERLAP_MS / FRAME_MS, sil))
            silMs >= END_MS && speechFrames * FRAME_MS >= MIN_SPEECH_MS -> emit(min(OVERLAP_MS / FRAME_MS, sil))
        }
    }

    /** Sends the buffer as one chunk and keeps its last [keepFrames] as the head of the next one. */
    private fun emit(keepFrames: Int) {
        val samples = frames * frameLen
        val start = timeline - samples
        val pcm = buf.copyOf(samples)
        idleFrames = 0
        shift(frames - keepFrames)
        onChunk(PcmChunk(seq++, baseOffsetMs + start * 1000 / rate, samples * 1000L / rate, pcm))
    }

    /** Drops the first [n] frames (zeroing what is left behind). */
    private fun shift(n: Int) {
        if (n <= 0) return
        val keep = frames - n
        buf.copyInto(buf, 0, n * frameLen, frames * frameLen)
        flags.copyInto(flags, 0, n, frames)
        buf.fill(0, keep * frameLen, frames * frameLen)
        frames = keep
    }

    /** v1.45 §40.9 paused: the partial chunk is dropped and zeroed (it may not be sent). */
    override fun dropPartial() {
        buf.fill(0)
        frame.fill(0)
        frames = 0
        frameFill = 0
        idleFrames = 0
    }

    /** Back `on` after a pause: the next chunk starts at [offsetMs] after `started_at` (the `seq` goes on). */
    override fun resumeAt(offsetMs: Long, fedMs: Long) {
        dropPartial()
        baseOffsetMs = offsetMs - timeline * 1000 / rate
        fedSamples = fedMs.coerceAtLeast(0) * rate / 1000
        started = true
    }

    /** Stop: everything held is dropped and zeroed. */
    override fun clear() {
        dropPartial()
        started = false
    }
}
