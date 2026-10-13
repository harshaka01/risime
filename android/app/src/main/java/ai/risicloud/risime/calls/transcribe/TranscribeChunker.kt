package ai.risicloud.risime.calls.transcribe

/**
 * §36.4 one cut piece of this phone's own microphone: 16 kHz mono PCM, in memory only.
 * [offsetMs] is the chunk's start in ms since the session's `started_at` (server-corrected clock).
 */
class PcmChunk(val seq: Int, val offsetMs: Long, val durationMs: Long, val pcm: ShortArray) {
    /** §36.6: zero the buffer once it is encoded or dropped. */
    fun wipe() = pcm.fill(0)
}

/**
 * §36.4 the chunker: cuts this phone's microphone into chunks of [targetMs] (10–15 s; 12 s by default),
 * `seq` 0, 1, 2… strictly increasing per session. Muted audio is fed as silence, so silent chunks still
 * go out (the keep-alive). Pure; one instance per session. Never touches disk.
 */
class TranscribeChunker(
    private val targetMs: Long = TARGET_MS,
    private val rate: Int = Pcm.RATE,
    private val onChunk: (PcmChunk) -> Unit,
) {
    companion object {
        const val MIN_MS = 10_000L
        const val MAX_MS = 15_000L
        const val TARGET_MS = 12_000L
    }

    init {
        require(targetMs in MIN_MS..MAX_MS) { "chunks are 10–15 s (§36.4)" }
    }

    private val capacity = (rate * targetMs / 1000).toInt()
    private var buf = ShortArray(capacity)
    private var fill = 0
    private var seq = 0

    /** Total samples cut since [start] (the next chunk's offset). */
    private var cutSamples = 0L
    private var baseOffsetMs = 0L
    private var started = false

    /** Samples fed since [start] (real audio plus silence), for the silence filler. */
    var fedSamples = 0L
        private set

    /** Starts (or restarts) at [baseOffsetMs] after `started_at`, e.g. when this phone joins the recording late. */
    fun start(baseOffsetMs: Long) {
        this.baseOffsetMs = baseOffsetMs.coerceAtLeast(0)
        started = true
    }

    /** Feeds [n] samples of 16 kHz mono; [muted] feeds silence of the same length (§36.4). */
    fun feed(pcm: ShortArray, n: Int = pcm.size, muted: Boolean = false) {
        if (!started) return
        var i = 0
        while (i < n) {
            val take = minOf(n - i, capacity - fill)
            if (muted) buf.fill(0, fill, fill + take) else pcm.copyInto(buf, fill, i, i + take)
            fill += take
            i += take
            fedSamples += take
            if (fill == capacity) cut()
        }
    }

    /** Feeds [ms] of silence (the microphone delivered nothing: the keep-alive still goes out). */
    fun padSilence(ms: Long) {
        if (!started || ms <= 0) return
        var left = rate * ms / 1000
        while (left > 0) {
            val take = minOf(left, (capacity - fill).toLong()).toInt()
            buf.fill(0, fill, fill + take)
            fill += take
            left -= take
            fedSamples += take
            if (fill == capacity) cut()
        }
    }

    /** Fed duration since start (ms). */
    val fedMs: Long get() = fedSamples * 1000 / rate

    private fun cut() {
        val out = buf
        buf = ShortArray(capacity)
        val chunk = PcmChunk(seq++, baseOffsetMs + cutSamples * 1000 / rate, fill * 1000L / rate, out)
        cutSamples += fill
        fill = 0
        onChunk(chunk)
    }

    /** v1.45 §40.9 paused: the partial chunk is dropped and zeroed (it may not be sent). */
    fun dropPartial() {
        buf.fill(0)
        fill = 0
    }

    /**
     * v1.45 §40.9 back `on` after a pause: the next chunk starts at [offsetMs] after `started_at` (the
     * `seq` goes on, so the server's "strictly increasing" holds); [fedMs] is the silence filler's clock.
     */
    fun resumeAt(offsetMs: Long, fedMs: Long) {
        dropPartial()
        baseOffsetMs = (offsetMs - cutSamples * 1000 / rate).coerceAtLeast(0)
        fedSamples = fedMs.coerceAtLeast(0) * rate / 1000
        started = true
    }

    /** Stop: the partial chunk is dropped and zeroed (after a final state there is nothing to send). */
    fun clear() {
        buf.fill(0)
        fill = 0
        started = false
    }
}
