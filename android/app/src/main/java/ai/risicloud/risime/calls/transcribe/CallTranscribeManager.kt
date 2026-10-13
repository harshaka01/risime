package ai.risicloud.risime.calls.transcribe

import ai.risicloud.risime.calls.CallPhase
import ai.risicloud.risime.calls.CallSnapshot
import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.net.CallTranscribeActions
import ai.risicloud.risime.net.CallTranscribePush
import ai.risicloud.risime.net.CallTranscribeReply
import ai.risicloud.risime.net.CallTranscribeSession
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.realtime.ConnectionState
import ai.risicloud.risime.realtime.PushResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** The ports the §36 manager needs (the app container implements them; tests fake them). */
interface TranscribePort {
    suspend fun me(): String?

    /** `/auth/config` `call_transcribe: on` and this device advertises `call_transcribe`. */
    fun available(): Pair<Boolean, Boolean>

    /** The chat's Official `grp:` for a 1:1 call's `dm:` (null: none on this phone). */
    fun officialConversationOf(dm: String): String?

    /** v1.45 §40.9: [conv] is a group chat's Official conversation on this phone (from MLS-backed tab state). */
    fun isOfficialGroup(conv: String): Boolean = false

    suspend fun push(payload: JsonObject): PushResult<JsonObject>
    suspend fun fetch(callId: String): ApiResult<CallTranscribeReply>
    suspend fun upload(callId: String, chunk: EncodedChunk): UploadResult

    /** The server-corrected clock (ms), §16.3. */
    fun serverNow(): Long
}

/**
 * §36 on the phone: follows the 1:1 call, keeps the [TranscribeConsentMachine], records this phone's own
 * microphone ([CallMicTap]) into 10–15 s Ogg/Opus chunks while the session is `on`, and uploads them.
 * Every audio buffer stays in memory and is zeroed after use; nothing about audio is logged.
 */
class CallTranscribeManager(
    private val scope: CoroutineScope,
    private val port: TranscribePort,
    private val calls: StateFlow<CallSnapshot?>,
    private val connection: StateFlow<ConnectionState>,
    private val encoder: ChunkEncoder,
    private val log: (String) -> Unit = {},
    private val encodeDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    /** Device clock (ms) for the silence filler. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var machine: TranscribeConsentMachine? = null
    private val _view = MutableStateFlow(TranscribeView())
    val view: StateFlow<TranscribeView> = _view.asStateFlow()

    /** The session id being recorded now (tests and the debug log). */
    private val _recording = MutableStateFlow<String?>(null)
    val recording: StateFlow<String?> = _recording.asStateFlow()

    private var recorder: Recorder? = null
    private var expiryJob: Job? = null

    fun start() {
        scope.launch {
            calls.map { it?.let(::toCall) }.distinctUntilChanged().collect { c -> mutex.withLock { onCallLocked(c) } }
        }
        scope.launch {
            var wasLive = connection.value == ConnectionState.Live
            connection.collect { st ->
                val live = st == ConnectionState.Live
                if (live && !wasLive) onReconnected()
                wasLive = live
            }
        }
    }

    private fun toCall(s: CallSnapshot): TranscribeCall? {
        if (s.phase == CallPhase.ENDED) return null
        val live = s.phase == CallPhase.ACTIVE || s.phase == CallPhase.RECONNECTING
        if (s.group) {
            // v1.45 §40.9: a §20 call on an Official grp: (a Private group's call never has a code path).
            val official = runCatching { port.isOfficialGroup(s.conversationId) }.getOrDefault(false)
            val alone = s.members.none { !it.local }
            return TranscribeCall(s.callId, s.peerUserId, official, if (official) s.conversationId else null, live, s.muted, group = true, alone = alone)
        }
        return TranscribeCall(s.callId, s.peerUserId, s.official, if (s.official) port.officialConversationOf(s.conversationId) else null, live, s.muted)
    }

    private suspend fun machineLocked(): TranscribeConsentMachine? {
        val me = port.me() ?: return null
        val m = machine ?: TranscribeConsentMachine(me).also { machine = it }
        val (on, capable) = port.available()
        m.setAvailable(on, capable)
        return m
    }

    private suspend fun onCallLocked(c: TranscribeCall?) {
        val m = machineLocked() ?: return
        val pushes = m.onCall(c)
        recorder?.muted = c?.muted == true
        publishLocked()
        pushes.forEach { send(it) }
    }

    /** §36.3 the `call_transcribe` signal (any thread). */
    fun onSignal(s: CallTranscribeSession) {
        scope.launch { apply(s) }
    }

    private suspend fun apply(s: CallTranscribeSession) {
        val pushes = mutex.withLock {
            val m = machineLocked() ?: return
            m.onSession(s).also { publishLocked() }
        }
        pushes.forEach { send(it) }
    }

    /** The switch or the capability changed (the button follows). */
    fun refreshAvailability() {
        scope.launch { mutex.withLock { machineLocked(); publishLocked() } }
    }

    fun request() = act { it.request() }
    fun allow() = act { it.allow() }
    fun decline() = act { it.decline() }
    fun stop(discard: Boolean) = act { it.stop(discard) }

    /** End tapped: the stop `ended` goes out before the call is torn down (§36.2). */
    fun onHangUp() {
        scope.launch {
            val pushes = mutex.withLock { machine?.onHangUp().orEmpty().also { publishLocked() } }
            pushes.forEach { send(it) }
        }
    }

    fun dismissNotice() {
        scope.launch { mutex.withLock { machine?.clearNotice(); publishLocked() } }
    }

    private fun act(make: (TranscribeConsentMachine) -> CallTranscribePush?) {
        scope.launch {
            val p = mutex.withLock { machineLocked()?.let(make).also { publishLocked() } } ?: return@launch
            send(p)
        }
    }

    private suspend fun send(p: CallTranscribePush) {
        log("transcribe: ${p.action} ${p.callId}" + (p.reason?.let { " ($it)" } ?: ""))
        when (val r = runCatching { port.push(p.toJson()) }.getOrElse { PushResult.Unavailable }) {
            is PushResult.Ok -> runCatching { ProtocolJson.decodeFromJsonElement(CallTranscribeReply.serializer(), r.value) }.getOrNull()?.session?.let { apply(it) }
            is PushResult.Rejected -> {
                log("transcribe: ${p.action} refused (${r.reason})")
                mutex.withLock {
                    if (p.action == CallTranscribeActions.REQUEST) machine?.onRequestFailed(r.reason)
                    publishLocked()
                }
                if (p.action != CallTranscribeActions.REQUEST) refresh(p.callId)
            }
            PushResult.Unavailable -> {
                log("transcribe: ${p.action} not sent (offline)")
                mutex.withLock {
                    if (p.action == CallTranscribeActions.REQUEST) machine?.onRequestFailed("offline")
                    publishLocked()
                }
            }
        }
    }

    /** §36.3 `GET` after a reconnect, before trusting any local state. */
    private suspend fun refresh(callId: String) {
        when (val r = runCatching { port.fetch(callId) }.getOrNull()) {
            is ApiResult.Ok -> r.value.session?.let { apply(it) }
            else -> Unit
        }
    }

    private fun onReconnected() {
        scope.launch {
            val callId = mutex.withLock { machine?.call?.takeIf { it.official }?.callId } ?: return@launch
            refresh(callId)
            recorder?.uploader?.kick()
        }
    }

    // ---------------------------------------------------------------- publishing and recording

    private fun publishLocked() {
        val m = machine
        _view.value = m?.view() ?: TranscribeView()
        val target = m?.recordTarget()
        val rec = recorder
        when {
            target == null && rec != null -> stopRecorder("state")
            target != null && rec != null && rec.target.sessionId != target.sessionId -> {
                stopRecorder("new session")
                startRecorder(target)
            }
            target != null && rec == null -> startRecorder(target)
        }
        // v1.45 §40.9: paused records nothing; back `on`, the next chunk starts at the right offset.
        recorder?.setPaused(target?.paused == true)
        recorder?.muted = m?.call?.muted == true
        scheduleExpiry(m)
    }

    /** The prompt closes when `expires_at` passes; ask the server what happened (it sends `expired` too). */
    private fun scheduleExpiry(m: TranscribeConsentMachine?) {
        val p = m?.view()?.prompt
        val callId = m?.call?.callId
        expiryJob?.cancel()
        expiryJob = null
        val at = p?.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
        expiryJob = scope.launch {
            delay((at - port.serverNow()).coerceAtLeast(0) + 1_500)
            if (callId != null) refresh(callId)
        }
    }

    private fun startRecorder(t: RecordTarget) {
        val startedAt = t.startedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: port.serverNow()
        val r = Recorder(t, (port.serverNow() - startedAt).coerceAtLeast(0))
        recorder = r
        _recording.value = t.sessionId
        r.start()
        log("transcribe: recording on (session ${t.sessionId})")
    }

    private fun stopRecorder(why: String) {
        recorder?.stop()
        recorder = null
        _recording.value = null
        log("transcribe: recording off ($why)")
    }

    private fun onUploadStop(a: UploadAction.Stop, sessionId: String, callId: String) {
        scope.launch {
            mutex.withLock {
                if (recorder?.target?.sessionId == sessionId) machine?.onUploadStopped(a.code)
                if (recorder?.target?.sessionId == sessionId) stopRecorder("upload ${a.code}")
                publishLocked()
            }
            if (a.refresh) refresh(callId)
        }
    }

    /** One session's recording: tap → chunker → encoder → uploader. */
    private inner class Recorder(val target: RecordTarget, baseOffsetMs: Long) {
        @Volatile var muted = false
        @Volatile private var paused = false
        private val lock = Any()
        private val startedLocal = nowMs()
        private val baseFed = baseOffsetMs
        val uploader = TranscribeUploader(
            scope,
            send = { c -> port.upload(target.callId, c) },
            onStop = { a -> onUploadStop(a, target.sessionId, target.callId) },
            log = log,
        )
        private val chunker = TranscribeChunker { pcm -> encodeAndQueue(pcm) }
        private var filler: Job? = null

        init {
            chunker.start(baseFed)
        }

        /** v1.45 §40.9: while paused nothing is recorded or sent; on resume the partial chunk is dropped and offsets jump to now. */
        fun setPaused(p: Boolean) {
            synchronized(lock) {
                if (p == paused) return
                paused = p
                if (!p) chunker.resumeAt(baseFed + (nowMs() - startedLocal), nowMs() - startedLocal)
                else chunker.dropPartial()
            }
            log("transcribe: recording ${if (p) "paused" else "resumed"} (session ${target.sessionId})")
        }

        fun start() {
            CallMicTap.sink = CallMicTap.Sink { data, len, rate, ch ->
                val mono = Pcm.toMono16k(data, len, rate, ch)
                synchronized(lock) { if (!paused) chunker.feed(mono, mono.size, muted) }
                mono.fill(0)
            }
            // The keep-alive: when the microphone delivers nothing (or too little), silence fills the gap.
            filler = scope.launch {
                while (isActive) {
                    delay(1_000)
                    synchronized(lock) {
                        if (paused) return@synchronized
                        val due = nowMs() - startedLocal
                        val gap = due - chunker.fedMs
                        if (gap > 1_500) chunker.padSilence(gap)
                    }
                }
            }
        }

        private fun encodeAndQueue(pcm: PcmChunk) {
            scope.launch(encodeDispatcher) {
                val ogg = runCatching { encoder.encode(pcm.pcm, Pcm.RATE) }.getOrNull()
                pcm.wipe()
                if (ogg == null) {
                    log("transcribe: chunk ${pcm.seq} not encoded")
                    return@launch
                }
                uploader.offer(EncodedChunk(target.sessionId, pcm.seq, pcm.offsetMs, pcm.durationMs, ogg))
            }
        }

        fun stop() {
            if (CallMicTap.sink != null) CallMicTap.sink = null
            filler?.cancel()
            synchronized(lock) { chunker.clear() }
            uploader.stop()
        }
    }
}
