package ai.risicloud.risime.calls.risi

import ai.risicloud.risime.calls.transcribe.CallMicTap
import ai.risicloud.risime.net.CallRisiStt153
import ai.risicloud.risime.voice.AndroidPhoneRecogniser
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Contract v1.53 §41.10 on Android: a continuous **on-device** recogniser (en-US) over the Call Risi's own captured
 * microphone audio. The audio is the WebRTC capture's samples (`CallMicTap`, the `JavaAudioDeviceModule`
 * samples-ready callback of the call that holds the microphone): never a second capture, never a networked recogniser
 * (the recogniser comes only from [AndroidPhoneRecogniser.newOnDevice], i.e. `createOnDeviceSpeechRecognizer`).
 *
 * Each utterance is one `startListening` over a fresh pipe (`EXTRA_AUDIO_SOURCE`, 16 kHz mono PCM16); after its final
 * (or "no speech" / "no match") the next one starts at once. Every recogniser call runs on the main thread. Text is never
 * logged.
 *
 * Fixes after Harsha's first call (2026-10-16 03:55Z: `mode phone` acked, then 25 s with no `final` and no `off`):
 * - The pipe's write end is **non-blocking**, written and closed only by the writer thread. Before, a recogniser that
 *   stopped reading (after its result) left the writer blocked forever on a full pipe (our own read end kept it open),
 *   so every later utterance got no audio and ended in "no speech", which never sends `off`.
 * - The phone **ends each utterance itself** ([CallSttEndpointer]: speech, then 0.9 s of quiet → `stopListening` and
 *   the stream closes), in case the recogniser doesn't endpoint an app-supplied stream.
 * - **Health** ([CallSttHealth]): no audio from the call's capture at all, a recogniser that returns no words over
 *   clear speech twice in a row, repeated errors, or a recogniser that never answers → `off`, so Risi's server takes
 *   over at once instead of after its own 3 misses.
 * - A logcat line per utterance (tag RisiMe, never the text): how much audio was fed, voiced, dropped, and how it ended.
 */
class OnDeviceCallStt(private val phone: AndroidPhoneRecogniser, private val log: (String) -> Unit = {}) : CallSttEngine {
    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUED)
    private val conv = PcmTo16k()

    @Volatile private var closed = false
    @Volatile private var feed: Feed? = null
    @Volatile private var frames = 0L

    // Main thread only.
    private var events: CallSttEvents? = null
    private var recogniser: SpeechRecognizer? = null
    private var read: ParcelFileDescriptor? = null
    private var generation = 0
    private var doneGen = 0
    private var startedAt = 0L
    private var quickFails = 0
    private val health = CallSttHealth()

    /**
     * One utterance's stream: the pipe's write end (non-blocking; written and closed only by the writer thread) and its
     * endpointer. [ended] (set by the main thread) tells the writer to close it: the recogniser then reads end-of-stream.
     */
    private class Feed(val gen: Int, val pfd: ParcelFileDescriptor) {
        val vad = CallSttEndpointer()
        @Volatile var ended = false
        @Volatile var fedMs = 0
        @Volatile var droppedMs = 0
        private var shut = false

        /** Writer thread only. False when the bytes were dropped (the recogniser isn't reading) or the stream is gone. */
        fun write(b: ByteArray): Boolean {
            if (shut) return false
            return try {
                var off = 0
                while (off < b.size) off += Os.write(pfd.fileDescriptor, b, off, b.size - off)
                fedMs += b.size / BYTES_PER_MS
                true
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN) droppedMs += b.size / BYTES_PER_MS else close()
                false
            } catch (_: Exception) {
                close()
                false
            }
        }

        /** Writer thread only (or after it stopped). Idempotent. */
        fun close() {
            if (shut) return
            shut = true
            runCatching { pfd.close() }
        }
    }

    private val sink = CallMicTap.Sink { data, length, rate, channels ->
        if (closed) return@Sink
        frames++
        val b = conv.feed(data, length, rate, channels)
        if (b.isEmpty()) return@Sink
        // Never block the audio thread: drop the oldest audio when the writer falls behind.
        if (!queue.offer(b)) {
            queue.poll()
            queue.offer(b)
        }
    }

    override fun start(events: CallSttEvents) {
        main.post { startOnMain(events) }
    }

    private fun startOnMain(ev: CallSttEvents) {
        if (closed) return
        events = ev
        if (Build.VERSION.SDK_INT < CallRisiSttRules.MIN_SDK || phone.precheck() != null) return off(CallRisiStt153.OFF_UNAVAILABLE)
        val r = runCatching { phone.newOnDevice() }.getOrElse { return off(CallRisiStt153.OFF_UNAVAILABLE) }
        recogniser = r
        r.setRecognitionListener(listener)
        CallMicTap.sttSink = sink
        Thread(::writerLoop, "risime-call-stt").apply { isDaemon = true }.start()
        // The call's capture must deliver audio; without any, recognition can't work (never a second microphone).
        main.postDelayed({
            if (!closed && frames == 0L) {
                log("risi stt: no audio from the call's microphone capture")
                off(CallRisiStt153.OFF_UNAVAILABLE)
            }
        }, NO_AUDIO_MS)
        listen()
    }

    private fun intent(src: ParcelFileDescriptor) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, src)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PcmTo16k.OUT_RATE)
    }

    /** One utterance: the previous stream ends, a fresh non-blocking pipe, then listen. */
    private fun listen() {
        if (closed) return
        val r = recogniser ?: return
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse { return off(CallRisiStt153.OFF_ERROR) }
        runCatching { Os.fcntlInt(pipe[1].fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK) }.onFailure {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            return off(CallRisiStt153.OFF_ERROR)
        }
        feed?.ended = true
        runCatching { read?.close() }
        read = pipe[0]
        val gen = ++generation
        feed = Feed(gen, pipe[1])
        startedAt = android.os.SystemClock.elapsedRealtime()
        if (gen <= 3 || gen % 20 == 0) log("risi stt: listening (utterance $gen)")
        runCatching { r.startListening(intent(pipe[0])) }.onFailure {
            log("risi stt: couldn't start (${it.javaClass.simpleName})")
            return off(CallRisiStt153.OFF_ERROR)
        }
        // A recogniser that never ends an utterance (constant noise) is asked for its result.
        main.postDelayed({ endUtterance(gen, "max length") }, UTTERANCE_MAX_MS)
    }

    /**
     * The phone ends utterance [gen] (its own end of speech, or the length cap): the stream closes and the recogniser is
     * asked for its result. A recogniser that then never answers is cancelled ([STUCK_MS]) and counts as an error.
     */
    private fun endUtterance(gen: Int, why: String) {
        if (closed || gen != generation || doneGen == gen) return
        val f = feed?.takeIf { it.gen == gen } ?: return
        if (f.ended) return
        f.ended = true
        runCatching { recogniser?.stopListening() }
        main.postDelayed({
            if (closed || gen != generation || doneGen == gen) return@postDelayed
            doneGen = gen
            runCatching { recogniser?.cancel() }
            events?.final("", null)
            over(gen, "no answer after $why", words = false, error = STUCK)
        }, STUCK_MS)
    }

    private fun restart(delayMs: Long = 0) {
        if (closed) return
        if (delayMs <= 0) main.post { listen() } else main.postDelayed({ listen() }, delayMs)
    }

    /** Main thread: utterance [gen] is over. Logs its numbers (never text), checks the recogniser's health, goes on. */
    private fun over(gen: Int, how: String, words: Boolean, error: Int?, quickReason: String? = null, retryMs: Long = 0) {
        val f = feed?.takeIf { it.gen == gen }
        f?.ended = true
        val heard = f?.vad?.heardSpeech ?: false
        log(
            "risi stt: utterance $gen $how; fed ${f?.fedMs ?: 0} ms, voiced ${f?.vad?.voicedMs ?: 0} ms, " +
                "dropped ${f?.droppedMs ?: 0} ms" + (if (words) ", words" else ""),
        )
        val reason = quickReason ?: health.ended(words, heard, error)
        if (reason != null) off(reason) else restart(retryMs)
    }

    /** Writer thread: moves the call's 16 kHz audio into the current utterance's pipe, never blocking. */
    private fun writerLoop() {
        var cur: Feed? = null
        try {
            while (!closed) {
                val b = queue.poll(100, TimeUnit.MILLISECONDS)
                val f = feed
                cur?.let { c -> if (c !== f || c.ended) c.close() }
                cur = f
                if (b == null || f == null || f.ended) continue
                if (!f.write(b)) continue
                if (f.vad.feed(b)) main.post { endUtterance(f.gen, "end of speech") }
            }
        } catch (_: InterruptedException) {
        } finally {
            cur?.close()
            feed?.close()
            queue.clear()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (generation <= 2) log("risi stt: ready (utterance $generation)")
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (closed) return
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (t.isNotEmpty()) events?.partial(t)
        }

        override fun onResults(results: Bundle?) {
            if (closed || doneGen == generation) return
            val gen = generation
            doneGen = gen
            quickFails = 0
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            val conf = CallRisiSttRules.conf(results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES))
            events?.final(t, conf)
            over(gen, "result" + (if (conf == null) " (no confidence)" else ""), words = t.isNotEmpty(), error = null)
        }

        override fun onError(error: Int) {
            if (closed || doneGen == generation) return
            val gen = generation
            doneGen = gen
            val quick = android.os.SystemClock.elapsedRealtime() - startedAt < QUICK_FAIL_MS
            quickFails = if (quick) quickFails + 1 else 0
            // The utterance (if any partial went out) is over without words.
            events?.final("", null)
            over(
                gen, CallRisiSttErrors.describe(error) + (if (quick) " (quick $quickFails)" else ""),
                words = false, error = error,
                quickReason = CallRisiSttErrors.offReason(error, quickFails),
                retryMs = if (quick) RETRY_MS else 0,
            )
        }
    }

    private fun off(reason: String) {
        if (closed) return
        val ev = events
        log("risi stt: stopping ($reason)")
        release()
        ev?.off(reason)
    }

    override fun release() {
        if (closed) return
        closed = true
        if (CallMicTap.sttSink === sink) CallMicTap.sttSink = null
        queue.clear()
        feed?.ended = true
        main.post {
            recogniser?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
            recogniser = null
            runCatching { read?.close() }
            read = null
            events = null
        }
    }

    private companion object {
        const val LANGUAGE = "en-US"
        const val BYTES_PER_MS = 32 // 16 kHz mono PCM16

        /** About 6 s of 10-ms frames at 16 kHz. */
        const val MAX_QUEUED = 600
        const val UTTERANCE_MAX_MS = 30_000L
        const val QUICK_FAIL_MS = 400L
        const val RETRY_MS = 300L
        const val NO_AUDIO_MS = 6_000L

        /** After the phone ended an utterance, the recogniser's result must come within this. */
        const val STUCK_MS = 4_000L

        /** "The recogniser never answered": counts as an error in [CallSttHealth]. */
        const val STUCK = -1
    }
}

/** SpeechRecognizer error codes → whether §41.10 recognition stops (`off` reason) or the next utterance starts. */
object CallRisiSttErrors {
    /** Consecutive failures within a moment of starting before the phone gives up (`error` or `busy`). */
    const val MAX_QUICK_FAILS = 5

    fun describe(code: Int): String = ai.risicloud.risime.voice.PhoneSttErrors.describe(code)

    /** Null = start the next utterance; otherwise the `off` reason. */
    fun offReason(code: Int, quickFails: Int): String? = when (code) {
        12, 13 -> CallRisiStt153.OFF_LANGUAGE // language not supported / pack not installed
        9 -> CallRisiStt153.OFF_ERROR // insufficient permissions
        8 -> if (quickFails >= MAX_QUICK_FAILS) CallRisiStt153.OFF_BUSY else null
        6, 7 -> if (quickFails >= MAX_QUICK_FAILS * 4) CallRisiStt153.OFF_ERROR else null // silence: keep listening
        else -> if (quickFails >= MAX_QUICK_FAILS) CallRisiStt153.OFF_ERROR else null
    }
}
