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
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Contract v1.53 §41.10 on Android: a continuous **on-device** recogniser (en-US) over the Call Risi's own captured
 * microphone audio. The audio is the WebRTC capture's samples (`CallMicTap`, the `JavaAudioDeviceModule`
 * samples-ready callback of the call that holds the microphone): never a second capture, never a networked recogniser
 * (the recogniser comes only from [AndroidPhoneRecogniser.newOnDevice], i.e. `createOnDeviceSpeechRecognizer`).
 * Each utterance is one `startListening` over a fresh pipe (`EXTRA_AUDIO_SOURCE`, 16 kHz mono PCM16); after its
 * final the next one starts at once. Every recogniser call runs on the main thread. Text is never logged.
 */
class OnDeviceCallStt(private val phone: AndroidPhoneRecogniser, private val log: (String) -> Unit = {}) : CallSttEngine {
    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUED)
    private val conv = PcmTo16k()

    @Volatile private var closed = false
    @Volatile private var out: OutputStream? = null

    // Main thread only.
    private var events: CallSttEvents? = null
    private var recogniser: SpeechRecognizer? = null
    private var read: ParcelFileDescriptor? = null
    private var generation = 0
    private var startedAt = 0L
    private var quickFails = 0

    private val sink = CallMicTap.Sink { data, length, rate, channels ->
        if (closed) return@Sink
        val b = conv.feed(data, length, rate, channels)
        if (b.isEmpty()) return@Sink
        // Never block the audio thread: drop the oldest audio when the recogniser falls behind.
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

    /** One utterance: a fresh pipe (the previous read end closes, so a writer blocked on it moves on), then listen. */
    private fun listen() {
        if (closed) return
        val r = recogniser ?: return
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse { return off(CallRisiStt153.OFF_ERROR) }
        runCatching { read?.close() }
        read = pipe[0]
        out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        val gen = ++generation
        startedAt = System.currentTimeMillis()
        runCatching { r.startListening(intent(pipe[0])) }.onFailure {
            log("risi stt: couldn't start (${it.javaClass.simpleName})")
            return off(CallRisiStt153.OFF_ERROR)
        }
        // A recogniser that never ends an utterance (constant noise) is asked for its result.
        main.postDelayed({ if (!closed && generation == gen) runCatching { recogniser?.stopListening() } }, UTTERANCE_MAX_MS)
    }

    private fun restart(delayMs: Long = 0) {
        if (closed) return
        if (delayMs <= 0) main.post { listen() } else main.postDelayed({ listen() }, delayMs)
    }

    private fun writerLoop() {
        var cur: OutputStream? = null
        try {
            while (!closed) {
                val b = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val o = out ?: continue
                if (o !== cur) {
                    runCatching { cur?.close() }
                    cur = o
                }
                try {
                    o.write(b)
                } catch (_: IOException) {
                    // That utterance's reader is gone; the next listen() brings a new pipe.
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { cur?.close() }
            runCatching { out?.close() }
            queue.clear()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
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
            if (closed) return
            quickFails = 0
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()?.takeIf { it in 0f..1f }
            events?.final(t, conf)
            restart()
        }

        override fun onError(error: Int) {
            if (closed) return
            val quick = System.currentTimeMillis() - startedAt < QUICK_FAIL_MS
            quickFails = if (quick) quickFails + 1 else 0
            log("risi stt: ${CallRisiSttErrors.describe(error)}${if (quick) " (quick $quickFails)" else ""}")
            // The utterance (if any partial went out) is over without words.
            events?.final("", null)
            when (val reason = CallRisiSttErrors.offReason(error, quickFails)) {
                null -> restart(if (quick) RETRY_MS else 0)
                else -> off(reason)
            }
        }
    }

    private fun off(reason: String) {
        if (closed) return
        val ev = events
        release()
        ev?.off(reason)
    }

    override fun release() {
        if (closed) return
        closed = true
        if (CallMicTap.sttSink === sink) CallMicTap.sttSink = null
        queue.clear()
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

        /** About 6 s of 10-ms frames at 16 kHz. */
        const val MAX_QUEUED = 600
        const val UTTERANCE_MAX_MS = 30_000L
        const val QUICK_FAIL_MS = 400L
        const val RETRY_MS = 300L
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
