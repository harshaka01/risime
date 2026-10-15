package ai.risicloud.risime.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.resume

/** Platform-free pre-checks (unit-tested): API level and whether the phone has an on-device recogniser at all. */
object PhoneSttGate {
    const val MIN_SDK = 33 // EXTRA_AUDIO_SOURCE, checkRecognitionSupport, triggerModelDownload

    /** Null = go on; otherwise the honest "not available" reason. [onDeviceAvailable] is consulted only on API 33+. */
    fun precheck(sdk: Int, onDeviceAvailable: () -> Boolean): String? = when {
        sdk < MIN_SDK -> PhoneSttSupport.NEEDS_ANDROID_13.label
        !runCatching(onDeviceAvailable).getOrDefault(false) -> PhoneSttSupport.NO_RECOGNISER.label
        else -> null
    }
}

/**
 * The on-device recogniser. **The only construction point** is [newOnDevice] →
 * `SpeechRecognizer.createOnDeviceSpeechRecognizer`. The online recogniser is never built here: its one guarded
 * construction site is [OnlineRecogniser] (decision 092), which reuses this class's [Session] with its own factory
 * (a unit test scans the sources for both). Audio comes from the app (`EXTRA_AUDIO_SOURCE`: 16 kHz mono PCM16 through a pipe), never the
 * microphone and never the network. Every platform call runs on the main thread, as SpeechRecognizer requires.
 */
class AndroidPhoneRecogniser(private val context: Context, private val log: (String) -> Unit = {}) {
    private val main = Handler(Looper.getMainLooper())

    fun precheck(): String? = PhoneSttGate.precheck(Build.VERSION.SDK_INT) { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }

    /** The one construction point of the on-device recogniser (also used by the v1.53 §41.10 Call Risi recogniser). */
    @RequiresApi(PhoneSttGate.MIN_SDK)
    internal fun newOnDevice(): SpeechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

    private fun intent(tag: String, online: Boolean = false) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !online)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }

    /** RecognitionSupport's on-device lists (the online list is never read), or the reason it couldn't be checked. */
    data class SupportReport(
        val installed: List<String> = emptyList(),
        val pending: List<String> = emptyList(),
        val supported: List<String> = emptyList(),
        val error: String? = null,
    ) {
        fun of(tag: String): Pair<PhoneSttSupport, String?> =
            if (error != null) PhoneSttSupport.CHECK_FAILED to error else PhoneSttSupport.of(tag, installed, pending, supported)
    }

    suspend fun supportReport(): SupportReport = withContext(Dispatchers.Main) {
        precheck()?.let { return@withContext SupportReport(error = it) }
        if (Build.VERSION.SDK_INT < PhoneSttGate.MIN_SDK) return@withContext SupportReport(error = PhoneSttSupport.NEEDS_ANDROID_13.label)
        val r = runCatching { newOnDevice() }.getOrElse { return@withContext SupportReport(error = PhoneSttSupport.NO_RECOGNISER.label) }
        try {
            withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { cont ->
                    r.checkRecognitionSupport(
                        intent(PhoneSttLocales.DEFAULT), context.mainExecutor,
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(s: RecognitionSupport) {
                                if (cont.isActive) cont.resume(SupportReport(s.installedOnDeviceLanguages, s.pendingOnDeviceLanguages, s.supportedOnDeviceLanguages))
                            }

                            override fun onError(error: Int) {
                                if (cont.isActive) cont.resume(SupportReport(error = PhoneSttErrors.describe(error)))
                            }
                        },
                    )
                }
            } ?: SupportReport(error = "no answer from the on-device recogniser")
        } catch (e: Exception) {
            SupportReport(error = "couldn't check (${e.javaClass.simpleName})")
        } finally {
            r.destroy()
        }
    }

    /**
     * Asks the on-device service to download [tag]'s pack. The caller offers this only for [PhoneSttSupport.DOWNLOADABLE]
     * (an on-device pack); it never enables anything online.
     */
    suspend fun download(tag: String): String? = withContext(Dispatchers.Main) {
        precheck()?.let { return@withContext it }
        if (Build.VERSION.SDK_INT < PhoneSttGate.MIN_SDK) return@withContext PhoneSttSupport.NEEDS_ANDROID_13.label
        val r = runCatching { newOnDevice() }.getOrElse { return@withContext PhoneSttSupport.NO_RECOGNISER.label }
        runCatching { r.triggerModelDownload(intent(tag)) }.onFailure { r.destroy(); return@withContext "couldn't start the download" }
        main.postDelayed({ r.destroy() }, 10_000) // the request is async: keep the binding a little
        null
    }

    /** Starts a streaming session for [tag]; feed 48 kHz frames as a [PcmTap], then [Session.await]. */
    fun session(tag: String, pacedRealTime: Boolean = false): Session = Session(tag, pacedRealTime)

    /**
     * Decision 092: a session on a recogniser [create]d by the caller. Only [OnlineRecogniser] calls this, after its
     * guard; the on-device precheck is skipped (the online service isn't on-device).
     */
    internal fun sessionWith(tag: String, create: () -> SpeechRecognizer): Session = Session(tag, false, online = true, create = create)

    /**
     * One recognition over app-supplied audio. [feed] takes 48 kHz mono PCM16 (the recorder's frames or a decoded note),
     * converts to 16 kHz and queues it; a writer thread pushes it into the pipe the recogniser reads, so [feed] never
     * blocks the recorder. [end] closes the stream; the segmented session then ends with the final text.
     */
    inner class Session internal constructor(
        private val tag: String,
        private val paced: Boolean,
        private val online: Boolean = false,
        private val create: (() -> SpeechRecognizer)? = null,
    ) : PhoneSttSessionHandle {
        private val result = CompletableDeferred<PhoneSttResult>()
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val conv = Pcm48To16()
        private val segments = StringBuilder()
        private var confidence: Float? = null
        private var recogniser: SpeechRecognizer? = null
        private var read: ParcelFileDescriptor? = null
        private var write: ParcelFileDescriptor? = null
        @Volatile private var startedAt = 0L
        @Volatile private var closed = false

        /** Starts listening; false (and [await] answers why) when on-device recognition can't run. */
        override fun start(): Boolean {
            if (!online) precheck()?.let { result.complete(PhoneSttResult.NotAvailable(it)); return false }
            if (Build.VERSION.SDK_INT < PhoneSttGate.MIN_SDK) {
                result.complete(PhoneSttResult.NotAvailable(PhoneSttSupport.NEEDS_ANDROID_13.label))
                return false
            }
            val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse { result.complete(PhoneSttResult.Failed("no pipe", 0)); return false }
            read = pipe[0]
            write = pipe[1]
            Thread(::writerLoop, "risime-phone-stt").apply { isDaemon = true }.start()
            main.post { startOnMain() }
            return true
        }

        @RequiresApi(PhoneSttGate.MIN_SDK)
        private fun startOnMain() {
            if (closed) return
            val r = runCatching { create?.invoke() ?: newOnDevice() }.getOrElse {
                finish(PhoneSttResult.NotAvailable(PhoneSttSupport.NO_RECOGNISER.label))
                return
            }
            recogniser = r
            r.setRecognitionListener(listener)
            val i = intent(tag, online).apply {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Pcm48To16.OUT_RATE)
                // The session lasts until the audio source ends (pauses in the note don't end it).
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
            startedAt = System.currentTimeMillis()
            runCatching { r.startListening(i) }.onFailure { finish(PhoneSttResult.Failed("couldn't start (${it.javaClass.simpleName})", 0)) }
        }

        override fun feed(frame: ShortArray, count: Int) {
            if (closed) return
            val b = conv.feed(frame, count)
            if (b.isNotEmpty()) queue.offer(b)
        }

        override fun end() {
            queue.offer(END)
        }

        private fun writerLoop() {
            val w = write ?: return
            val out = ParcelFileDescriptor.AutoCloseOutputStream(w)
            val t0 = System.nanoTime()
            var bytes = 0L
            try {
                while (!closed) {
                    val b = queue.take()
                    if (b === END) break
                    out.write(b)
                    bytes += b.size
                    if (paced) {
                        val dueNs = t0 + bytes * 1_000_000_000L / (Pcm48To16.OUT_RATE * 2)
                        val waitMs = (dueNs - System.nanoTime()) / 1_000_000
                        if (waitMs > 0) Thread.sleep(waitMs)
                    }
                }
            } catch (_: Exception) {
                // The recogniser stopped reading (error or cancel): the result path reports it.
            } finally {
                runCatching { out.close() }
                queue.clear()
            }
        }

        private val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onSegmentResults(segmentResults: Bundle) = append(segmentResults)

            override fun onEndOfSegmentedSession() = finishWithText()

            override fun onResults(results: Bundle?) {
                results?.let(::append)
                finishWithText()
            }

            override fun onError(error: Int) {
                val ms = elapsed()
                when {
                    segments.isNotEmpty() -> finishWithText()
                    PhoneSttErrors.isUnavailable(error) -> finish(PhoneSttResult.NotAvailable("$tag: ${PhoneSttErrors.describe(error)}"))
                    PhoneSttErrors.isNoSpeech(error) -> finish(PhoneSttResult.NoSpeech(tag, ms))
                    else -> finish(PhoneSttResult.Failed(PhoneSttErrors.describe(error), ms))
                }
            }
        }

        private fun append(b: Bundle) {
            val text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                if (segments.isNotEmpty()) segments.append(' ')
                segments.append(text)
            }
            b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()?.takeIf { it >= 0 }?.let { confidence = it }
        }

        private fun elapsed() = if (startedAt == 0L) 0 else System.currentTimeMillis() - startedAt

        private fun finishWithText() {
            val t = segments.toString().trim()
            finish(if (t.isEmpty()) PhoneSttResult.NoSpeech(tag, elapsed()) else PhoneSttResult.Text(t, tag, elapsed(), confidence))
        }

        private fun finish(r: PhoneSttResult) {
            if (!result.complete(r)) return
            log("phone stt: ${r.javaClass.simpleName} ($tag)") // never the text
            close()
        }

        /** Waits up to [timeoutMs] for the final result; null on timeout (the session is then cancelled). */
        override suspend fun await(timeoutMs: Long): PhoneSttResult? {
            val r = withTimeoutOrNull(timeoutMs) { result.await() }
            if (r == null) cancel()
            return r
        }

        /** Stops everything (cancel, the screen went away, a timeout). Idempotent. */
        override fun cancel() {
            result.complete(PhoneSttResult.Failed("cancelled", elapsed()))
            close()
        }

        private fun close() {
            if (closed) return
            closed = true
            queue.clear()
            queue.offer(END)
            main.post {
                recogniser?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
                recogniser = null
                runCatching { read?.close() }
                read = null
            }
        }
    }

    private companion object {
        val END = ByteArray(0)
    }
}
