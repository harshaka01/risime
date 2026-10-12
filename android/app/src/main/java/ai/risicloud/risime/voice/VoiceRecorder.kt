package ai.risicloud.risime.voice

import ai.risicloud.risime.data.media.VoiceEnvelope
import ai.risicloud.risime.data.media.Waveform
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** 16-bit mono PCM at [rate], read in blocking 20 ms frames. */
interface PcmSource {
    val rate: Int

    fun start(): Boolean

    /** Fills [buf] (one frame); returns the samples read, or -1 when the source failed. */
    fun read(buf: ShortArray): Int

    fun stop()
}

/** The phone's microphone (the caller holds RECORD_AUDIO). */
class MicSource(override val rate: Int = StreamingOpusEncoder.RATE) : PcmSource {
    private var rec: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
        }.getOrNull() ?: return false
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        runCatching { r.startRecording() }.onFailure { r.release(); return false }
        rec = r
        return true
    }

    override fun read(buf: ShortArray): Int {
        val r = rec ?: return -1
        var got = 0
        while (got < buf.size) {
            val n = r.read(buf, got, buf.size - got)
            if (n < 0) return -1
            got += n
        }
        return got
    }

    override fun stop() {
        rec?.let { r -> runCatching { r.stop() }; r.release() }
        rec = null
    }
}

/**
 * Debug builds only (redroid has no microphone): a clip armed by the debug `VOICE_INJECT` receiver replaces the
 * microphone for the next recordings, paced in real time, then silence. Release builds never arm one: [arm] is a
 * no-op there and the receiver class is not in the release APK.
 */
object VoiceMicInject {
    @Volatile private var clip: ShortArray? = null

    fun arm(samples48kMono: ShortArray) {
        if (!ai.risicloud.risime.BuildConfig.DEBUG) return
        clip = samples48kMono
    }

    fun clear() {
        clip?.fill(0)
        clip = null
    }

    /** The armed clip as a source (null: use the microphone). */
    fun source(): PcmSource? = if (ai.risicloud.risime.BuildConfig.DEBUG) clip?.let { InjectedSource(it) } else null
}

private class InjectedSource(private val clip: ShortArray, override val rate: Int = StreamingOpusEncoder.RATE) : PcmSource {
    private var pos = 0
    private var startNs = 0L
    private var frames = 0L

    override fun start(): Boolean {
        startNs = System.nanoTime()
        return true
    }

    override fun read(buf: ShortArray): Int {
        frames++
        val due = startNs + frames * 20_000_000L
        val wait = (due - System.nanoTime()) / 1_000_000
        if (wait > 0) Thread.sleep(wait)
        for (i in buf.indices) buf[i] = if (pos < clip.size) clip[pos++] else 0
        return buf.size
    }

    override fun stop() = Unit
}

/** A finished recording, in memory. */
class RecordedNote(val audio: ByteArray, val durationMs: Long, val waveform: ByteArray) {
    fun wipe() = audio.fill(0)
}

/**
 * §37.3 the recorder: 20 ms frames from a [PcmSource] → [VoiceEncoder] (Ogg/Opus in memory) plus one peak per frame for
 * the 64-level waveform and the live meter. Stops itself at 30 min ([onLimit]); notes under 0.5 s are discarded.
 */
class VoiceRecorder(
    private val newEncoder: () -> VoiceEncoder = { StreamingOpusEncoder() },
    private val newSource: () -> PcmSource = { VoiceMicInject.source() ?: MicSource() },
    private val log: (String) -> Unit = {},
) {
    sealed interface State {
        data object Idle : State

        /** [levels]: the last few frames (0..1) for the live waveform. */
        data class Recording(val elapsedMs: Long, val levels: List<Float>) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile private var running = false
    @Volatile private var failed = false
    private var thread: Thread? = null
    private var encoder: VoiceEncoder? = null
    private var source: PcmSource? = null
    private var peaks = IntArray(1024)
    private var frames = 0
    private var samples = 0L

    /** Called (on the recorder thread) when the 30-minute limit stops the recording. */
    @Volatile var onLimit: (() -> Unit)? = null

    val recording: Boolean get() = running

    /** Starts recording; false when this phone can't (no encoder, the microphone is busy or refused). */
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val enc = runCatching { newEncoder() }.getOrElse { log("voice: no encoder (${it.javaClass.simpleName})"); return false }
        val src = newSource()
        if (!src.start()) {
            enc.release()
            log("voice: the microphone did not start")
            return false
        }
        encoder = enc
        source = src
        peaks = IntArray(1024)
        frames = 0
        samples = 0
        failed = false
        running = true
        _state.value = State.Recording(0, emptyList())
        val frame = ShortArray(src.rate / 50)
        val recent = ArrayDeque<Float>()
        thread = Thread({
            try {
                while (running) {
                    val n = src.read(frame)
                    if (n < 0) { failed = true; break }
                    if (n == 0) continue
                    enc.feed(frame, n)
                    val p = Waveform.peak(frame, n)
                    if (frames == peaks.size) peaks = peaks.copyOf(peaks.size * 2)
                    peaks[frames++] = p
                    samples += n
                    recent.addLast(kotlin.math.sqrt(p / 32_768f).coerceIn(0f, 1f))
                    while (recent.size > LIVE_BARS) recent.removeFirst()
                    val ms = samples * 1000 / src.rate
                    if (frames % 2 == 0) _state.value = State.Recording(ms, recent.toList())
                    if (ms >= VoiceEnvelope.MAX_DURATION_MS) {
                        running = false
                        onLimit?.invoke()
                    }
                }
            } catch (e: Exception) {
                failed = true
                log("voice: recording failed (${e.javaClass.simpleName})")
            } finally {
                frame.fill(0)
            }
        }, "risime-voice-rec").also { it.start() }
        return true
    }

    /** Stops and returns the note (null: shorter than 0.5 s, or it failed). */
    suspend fun stop(): RecordedNote? = withContext(Dispatchers.IO) {
        val enc = halt() ?: return@withContext null
        val ms = samples * 1000 / (source?.rate ?: StreamingOpusEncoder.RATE)
        source = null
        if (failed || ms < VoiceEnvelope.MIN_DURATION_MS) {
            enc.release()
            return@withContext null
        }
        val audio = enc.finish() ?: return@withContext null
        RecordedNote(audio, ms.coerceAtMost(VoiceEnvelope.MAX_DURATION_MS), Waveform.of(peaks, frames))
    }

    /** Discards the recording (slide to cancel, Delete). */
    fun cancel() {
        halt()?.release()
        source = null
    }

    @Synchronized
    private fun halt(): VoiceEncoder? {
        val enc = encoder ?: return null
        running = false
        thread?.join(2_000)
        thread = null
        source?.stop()
        encoder = null
        _state.value = State.Idle
        return enc
    }

    companion object {
        const val LIVE_BARS = 40
    }
}
