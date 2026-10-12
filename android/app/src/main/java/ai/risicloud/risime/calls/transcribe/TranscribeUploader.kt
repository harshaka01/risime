package ai.risicloud.risime.calls.transcribe

import ai.risicloud.risime.net.CallTranscribeErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** §36.4 one encoded chunk ready to upload (Ogg/Opus bytes in memory only). */
class EncodedChunk(
    val sessionId: String,
    val seq: Int,
    val offsetMs: Long,
    val durationMs: Long,
    val audio: ByteArray,
) {
    /** Retries already spent on `503 transcribe_unavailable` / a 5xx (one is allowed). */
    var retries = 0

    fun wipe() = audio.fill(0)
}

/** What an upload attempt came back with (the HTTP status and §0 error code, or no answer at all). */
sealed interface UploadResult {
    data class Accepted(val duplicate: Boolean) : UploadResult
    data class Failed(val status: Int, val code: String, val retryAfterSec: Long? = null) : UploadResult
    /** No answer (socket, DNS, timeout): keep the chunk (at most 2) and try again. */
    data object Offline : UploadResult
}

/** The §36.4 reaction to one upload result. */
sealed interface UploadAction {
    /** Sent (or a duplicate): drop the chunk, send the next. */
    data object Done : UploadAction
    /** The session is over for this phone (or never was): stop recording at once, zero everything. [refresh]: ask `GET` for the state. */
    data class Stop(val code: String, val refresh: Boolean) : UploadAction
    /** Keep the chunk and try it again after [afterMs]. */
    data class Backoff(val afterMs: Long) : UploadAction
    /** Retry the same `seq` once after [afterMs], then drop it. */
    data class RetryOnce(val afterMs: Long) : UploadAction
    /** This chunk can't be sent (too large, bad request): drop it, go on with the next. */
    data class Drop(val code: String) : UploadAction
}

object UploadPolicy {
    const val UNAVAILABLE_RETRY_MS = 5_000L
    const val RATE_LIMIT_DEFAULT_MS = 10_000L
    const val OFFLINE_RETRY_MS = 3_000L

    /** §36.4 error table → what the phone does. */
    fun classify(r: UploadResult): UploadAction = when (r) {
        is UploadResult.Accepted -> UploadAction.Done
        UploadResult.Offline -> UploadAction.Backoff(OFFLINE_RETRY_MS)
        is UploadResult.Failed -> when {
            r.status == 409 && r.code == CallTranscribeErrors.STOPPED -> UploadAction.Stop(r.code, refresh = false)
            // `requested` (not everyone allowed yet): never send ahead of consent; the signal/GET decide.
            r.status == 409 -> UploadAction.Stop(r.code, refresh = true)
            r.status == 403 -> UploadAction.Stop(r.code, refresh = true)
            r.status == 404 -> UploadAction.Stop(r.code, refresh = true)
            r.status == 429 -> UploadAction.Backoff(r.retryAfterSec?.let { it * 1000 }?.coerceIn(1_000, 60_000) ?: RATE_LIMIT_DEFAULT_MS)
            r.status == 503 && r.code == CallTranscribeErrors.TRANSCRIBE_OFF -> UploadAction.Stop(r.code, refresh = false)
            r.status == 503 -> UploadAction.RetryOnce(UNAVAILABLE_RETRY_MS)
            r.status == 413 || r.status == 422 -> UploadAction.Drop(r.code)
            r.status >= 500 -> UploadAction.RetryOnce(UNAVAILABLE_RETRY_MS)
            else -> UploadAction.Drop(r.code)
        }
    }
}

/**
 * §36.4 the upload queue: at most [MAX_HELD] chunks in memory (the oldest is dropped and zeroed), sent in
 * `seq` order by one worker. A socket drop keeps the chunks and resends them with their original `seq`.
 * [onStop] fires once when the server says the session is over (409 stopped, 503 off, …).
 */
class TranscribeUploader(
    private val scope: CoroutineScope,
    private val send: suspend (EncodedChunk) -> UploadResult,
    private val onStop: (UploadAction.Stop) -> Unit,
    private val log: (String) -> Unit = {},
) {
    companion object {
        /** §36.2: hold at most 2 chunks, dropping the oldest. */
        const val MAX_HELD = 2
    }

    private val lock = Any()
    private val queue = ArrayDeque<EncodedChunk>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null

    @Volatile var stopped = false
        private set

    /** Chunks sent and accepted (tests and the debug log). */
    @Volatile var accepted = 0
        private set

    /** Held now (never more than [MAX_HELD]). */
    val held: Int get() = synchronized(lock) { queue.size }

    fun offer(c: EncodedChunk) {
        if (stopped) {
            c.wipe()
            return
        }
        synchronized(lock) {
            queue.addLast(c)
            while (queue.size > MAX_HELD) queue.removeFirst().also { log("transcribe: dropped chunk ${it.seq} (held ${queue.size + 1})") }.wipe()
        }
        ensureWorker()
        wake.trySend(Unit)
    }

    /** The socket is back: try the held chunks now. */
    fun kick() {
        wake.trySend(Unit)
    }

    /** Stop sending at once and zero every held chunk. */
    fun stop() {
        stopped = true
        synchronized(lock) {
            queue.forEach { it.wipe() }
            queue.clear()
        }
        worker?.cancel()
        worker = null
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (!stopped) {
                val c = synchronized(lock) { queue.firstOrNull() }
                if (c == null) {
                    wake.receive()
                    continue
                }
                wake.tryReceive() // this attempt answers any earlier wake-up
                val r = runCatching { send(c) }.getOrElse { UploadResult.Offline }
                if (stopped) break
                when (val a = UploadPolicy.classify(r)) {
                    UploadAction.Done -> {
                        accepted++
                        remove(c)
                    }
                    is UploadAction.Drop -> {
                        log("transcribe: chunk ${c.seq} dropped (${a.code})")
                        remove(c)
                    }
                    is UploadAction.RetryOnce -> {
                        if (c.retries >= 1) {
                            log("transcribe: chunk ${c.seq} dropped after one retry")
                            remove(c)
                        } else {
                            c.retries++
                            delay(a.afterMs)
                        }
                    }
                    is UploadAction.Backoff -> {
                        // Wait for the delay or a kick (reconnect), whichever comes first.
                        kotlinx.coroutines.withTimeoutOrNull(a.afterMs) { wake.receive() }
                    }
                    is UploadAction.Stop -> {
                        log("transcribe: server stopped the upload (${a.code})")
                        stop()
                        onStop(a)
                        break
                    }
                }
            }
        }
    }

    private fun remove(c: EncodedChunk) {
        synchronized(lock) { queue.remove(c) }
        c.wipe()
    }
}
