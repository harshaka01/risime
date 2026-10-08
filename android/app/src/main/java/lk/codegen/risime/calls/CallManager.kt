package lk.codegen.risime.calls

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import lk.codegen.risime.BuildConfig
import lk.codegen.risime.data.db.CallMarkDao
import lk.codegen.risime.data.db.CallMarkEntity
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.realtime.ConnectionState
import lk.codegen.risime.realtime.PushResult

/** What the call layer needs from the rest of the app (the container implements it). */
interface CallAppPort {
    val scope: CoroutineScope
    val api: ApiClient
    val connection: StateFlow<ConnectionState>
    val callMarkDao: CallMarkDao

    /** My user id, or null when signed out. */
    suspend fun me(): String?
    suspend fun deviceId(): String

    /** The OIDC session is locked (no token, no socket, no MLS, §16.8). */
    suspend fun sessionLocked(): Boolean
    fun serverNow(): Long
    suspend fun displayName(userId: String): String
    suspend fun sendSignal(conv: String, peer: String, env: CallEnvelope.Env, media: String = CallEnvelope.MEDIA_AUDIO): PushResult<*>
    suspend fun queueCallEnd(conv: String, peer: String, env: CallEnvelope.End, rangUnanswered: Boolean)

    /** Decision 054: a local "Missed voice call" line (no durable `call_end` came); false if the call already has a line. */
    suspend fun localMissedCall(conv: String, peer: String, callId: String, video: Boolean = false): Boolean = false
    fun foreground(): Boolean

    // ---- §20 group calls (the container implements these; defaults = an app without group calls) ----

    /** The MLS core exports call frame keys (§20.6). */
    val callKeysSupported: Boolean get() = false

    suspend fun groupCatchUp(conv: String) = Unit

    suspend fun groupEpoch(conv: String): Long? = null

    suspend fun groupFrameKeys(conv: String, callId: String): lk.codegen.risime.data.mls.CallKeys =
        throw lk.codegen.risime.data.mls.CallKeysException(lk.codegen.risime.data.mls.CallKeysException.Kind.Unsupported, "no group calls")

    suspend fun sendGroupSignal(conv: String, env: CallEnvelope.Env, media: String): PushResult<*> = PushResult.Unavailable

    suspend fun groupCallStarted(conv: String, env: GroupCallEnvelope) = Unit

    suspend fun groupCallEnded(conv: String, env: GroupCallEnvelope) = Unit

    suspend fun groupCallOver(conv: String, callId: String) = Unit

    suspend fun groupName(conv: String): String = "Group"
}

/**
 * The Android side of §16: owns the state machine, keeps the socket and a wake lock up while a call
 * exists (android R6), runs the `phoneCall` foreground service and core-telecom, posts the call
 * notifications, handles the `call` push (an unlocked sync, or the nameless locked ring of decision
 * 051) and routes audio through Telecom's endpoints.
 */
class CallManager(private val context: Context, private val port: CallAppPort, private val sfuFactory: () -> SfuConnector = {
    LiveKitSfu(
        context,
        relayOnly = { BuildConfig.DEBUG && java.io.File(context.filesDir, "debug_relay_only").exists() },
        debug = BuildConfig.DEBUG,
        fakeCamera = {
            BuildConfig.DEBUG && (java.io.File(context.filesDir, "debug_fake_camera").exists() ||
                runCatching { livekit.org.webrtc.Camera2Enumerator(context).deviceNames.isEmpty() }.getOrDefault(true))
        },
    )
}, private val mediaFactory: () -> CallMedia = {
    WebRtcCallMedia(
        context,
        // Debug builds only: `run-as <pkg> touch files/debug_relay_only` forces TURN relay candidates
        // (the redroid relay test, §16.10 f); release builds never read it.
        relayOnly = { BuildConfig.DEBUG && java.io.File(context.filesDir, "debug_relay_only").exists() },
        debug = BuildConfig.DEBUG,
        // Debug builds only: `touch files/debug_fake_camera` (or no camera at all, as on redroid) sends a test pattern.
        fakeCamera = {
            BuildConfig.DEBUG && (java.io.File(context.filesDir, "debug_fake_camera").exists() ||
                runCatching { livekit.org.webrtc.Camera2Enumerator(context).deviceNames.isEmpty() }.getOrDefault(true))
        },
        metered = {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        },
    )
}) {
    private val log: (String) -> Unit = { Log.i("RisiMe", "calls: $it") }
    val notifications = CallNotifications(context)
    val media: CallMedia by lazy { mediaFactory() }
    private val scope get() = port.scope

    private val _machine = MutableStateFlow<CallStateMachine?>(null)
    val machineFlow: StateFlow<CallStateMachine?> = _machine.asStateFlow()
    val machine: CallStateMachine? get() = _machine.value

    /** §20 the group call machine (one per signed-in user and device, like [machine]). */
    private val _group = MutableStateFlow<GroupCallMachine?>(null)
    val groupMachine: GroupCallMachine? get() = _group.value

    /** §20.5 LiveKit (loaded lazily: JVM tests and 32-bit phones never touch it). */
    val sfu: SfuConnector by lazy { sfuFactory() }

    /** The current call snapshot (null = no call). */
    val state = MutableStateFlow<CallSnapshot?>(null)

    /** §16.8 the nameless ring of a locked app: non-null while ringing blind (device ms when it started). */
    val blindRing = MutableStateFlow<Long?>(null)

    /** A push woke the app: the socket stays up while it syncs (unlocked). */
    private val waking = MutableStateFlow(false)

    /** Answer as soon as the unlocked sync finds the call (the user tapped Answer on the blind ring). */
    @Volatile var pendingBlindAnswer = false

    /** android R6: keep the realtime connection up regardless of foreground. */
    val keepConnected: StateFlow<Boolean> = MutableStateFlow(false).also { out ->
        scope.launch { combine(state, blindRing, waking) { s, b, w -> s != null || b != null || w }.distinctUntilChanged().collect { out.value = it } }
    }

    // ---- Telecom endpoints for the UI ----
    val endpoints = MutableStateFlow<List<CallEndpointCompat>>(emptyList())
    val currentEndpoint = MutableStateFlow<CallEndpointCompat?>(null)
    /**
     * Decision 054: one core-telecom call per RisiMe call id. The nightly.16 code kept a single
     * slot that a second call id overwrote (glare, a quick re-call) without disconnecting the first:
     * a ghost self-managed call that held MODE_IN_COMMUNICATION (every later call "busy" / "You're
     * already in a call") and whose Telecom callbacks hung up whatever call was current.
     */
    private class TelecomCall(val callId: String, val job: Job) {
        @Volatile var scope: CallControlScope? = null
    }
    private val telecomCalls = java.util.concurrent.ConcurrentHashMap<String, TelecomCall>()
    private val telecomScope: CallControlScope? get() = state.value?.callId?.let { telecomCalls[it]?.scope }

    private val callsManager: CallsManager? by lazy {
        runCatching { CallsManager(context).also { it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) } }
            .onFailure { Log.w("RisiMe", "core-telecom registration failed: ${it.message}") }.getOrNull()
    }

    /** §16.1 (S-f): advertise `calls` only when this phone can ring, answer and play a call. */
    fun canAdvertise(): Boolean = BuildConfig.CALLS_ENABLED && media.available && callsManager != null && notifications.notificationsAllowed()

    /** §19.1 (android A9): `video` only together with `calls`, and only when the VP8 encoder and decoder load. */
    fun canAdvertiseVideo(): Boolean = canAdvertise() && media.videoAvailable

    /**
     * §20.1: `group_calls` only together with `calls` and `video` (the caller adds `groups`), and only
     * when LiveKit and its frame encryption load and the core exports call keys.
     */
    fun canAdvertiseGroupCalls(): Boolean = BuildConfig.GROUP_CALLS_ENABLED && canAdvertiseVideo() && port.callKeysSupported && runCatching { sfu.available }.getOrDefault(false)

    /** Why this phone can't make or take calls (the Settings "Calls" row), null when it can. */
    fun unsupportedReason(): String? = when {
        !BuildConfig.CALLS_ENABLED -> lk.codegen.risime.ui.chat.CALLS_OFF_IN_BUILD
        !media.available -> "Calls aren't supported on this phone"
        callsManager == null -> "This phone's calling service isn't available to RisiMe"
        !notifications.notificationsAllowed() -> "Turn on notifications so RisiMe calls can ring"
        else -> null
    }

    /** §16.9 (A3): full-screen intents were refused at the last ring: the in-app card offers the setting. */
    val fullScreenDenied = MutableStateFlow(false)

    private val marks = object : CallMarks {
        override suspend fun get(callId: String): CallMark? = port.callMarkDao.get(callId)?.let { CallMark(it.callId, it.rang, it.answered, it.ended, it.at) }
        override suspend fun put(mark: CallMark) = port.callMarkDao.put(CallMarkEntity(mark.callId, mark.rang, mark.answered, mark.ended, if (mark.at == 0L) System.currentTimeMillis() else mark.at))
    }

    /** The hooks the chat pipeline calls (after each commit). */
    val hooks: CallHooks = MachineCallHooks({ _machine.value }, marks, { _group.value }) { conv, from, video ->
        scope.launch {
            if (port.foreground() && openConversation?.invoke() == conv) return@launch
            notifications.postMissed(conv, port.displayName(from), video)
        }
    }

    /** The conversation on screen (no missed-call notification for it). */
    var openConversation: (() -> String?)? = null

    private var turnCache: Pair<List<IceServer>, Long>? = null

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val signals = object : CallSignals {
        override suspend fun signal(conversationId: String, peer: String, env: CallEnvelope.Env, media: String): SignalOutcome {
            repeat(3) { attempt ->
                when (val r = port.sendSignal(conversationId, peer, env, media)) {
                    is PushResult.Ok -> return SignalOutcome.Ok
                    is PushResult.Rejected -> if (r.reason == "rate_limited" && env !is CallEnvelope.Offer && attempt < 2) delay(1_000) else return SignalOutcome.Refused(r.reason)
                    PushResult.Unavailable -> withTimeoutOrNull(5_000) { port.connection.first { it == ConnectionState.Live } }
                }
            }
            return SignalOutcome.Unavailable
        }

        override suspend fun end(conversationId: String, peer: String, env: CallEnvelope.End) {
            port.queueCallEnd(conversationId, peer, env, marks.get(env.callId)?.let { it.rang && !it.answered } == true)
        }

        override suspend fun missed(conversationId: String, peer: String, callId: String, video: Boolean) {
            if (!port.localMissedCall(conversationId, peer, callId, video)) return
            if (port.foreground() && openConversation?.invoke() == conversationId) return
            notifications.postMissed(conversationId, port.displayName(peer), video)
        }
    }

    private val environment = object : CallEnvironment {
        override fun audioBusy(): Boolean {
            val am = context.getSystemService(AudioManager::class.java) ?: return false
            // android R9: no READ_PHONE_STATE. Decision 054: only a cellular call (MODE_IN_CALL) is
            // busy; MODE_IN_COMMUNICATION may be stale (a ghost Telecom call, another app's leftover).
            return state.value == null && am.mode == AudioManager.MODE_IN_CALL
        }

        // android A4: a group call (or its ring) makes this device busy for 1:1 calls.
        override fun otherCallActive(): Boolean = _group.value?.active() == true

        override suspend fun iceServers(): List<IceServer> {
            // §16.7: reuse only while expires_at − now ≥ 4 h 5 min; never persisted; 503 → STUN only.
            turnCache?.let { (servers, exp) -> if (exp - port.serverNow() >= (4 * 3600 + 300) * 1000L) return servers }
            return when (val r = withTimeoutOrNull(3_000) { port.api.callsTurn() }) {
                is ApiResult.Ok -> r.value.iceServers.map { IceServer(it.urls, it.username, it.credential) }.also { servers ->
                    runCatching { java.time.Instant.parse(r.value.expiresAt).toEpochMilli() }.getOrNull()?.let { turnCache = servers to it }
                }
                else -> emptyList() // 503 calls_unavailable, 429, offline: direct ICE only (host candidates)
            }
        }
    }

    init {
        // Decision 054: whatever a dead process left behind is cleaned up before anything rings.
        scope.launch { runCatching { cleanupAfterProcessStart() }.onFailure { Log.w("RisiMe", "call cleanup: ${it.message}") } }
        // One machine per signed-in user and device.
        scope.launch {
            var lastUser: String? = null
            while (true) {
                val me = runCatching { port.me() }.getOrNull()
                if (me != lastUser) {
                    lastUser = me
                    _machine.value = me?.let { newMachine(it, port.deviceId()) }
                    _group.value = me?.let { newGroupMachine(it, port.deviceId()) }
                }
                delay(2_000)
            }
        }
        scope.launch {
            // One device, one call: the 1:1 machine's call, else the group machine's (§20.4, android A4).
            val oneToOne = _machine.flatMapLatest { it?.state ?: kotlinx.coroutines.flow.flowOf(null) }
            val group = _group.flatMapLatest { it?.state ?: kotlinx.coroutines.flow.flowOf(null) }
            combine(oneToOne, group) { a, g -> pickCall(a, g) }.collectLatest { s -> onState(s) }
        }
        // Debug builds: the group call's receive counters every 5 s (the device test's evidence that
        // frames decrypt: cryptor OK, packets and audio energy growing; a wrong key shows FAILED / no energy).
        if (BuildConfig.DEBUG) scope.launch {
            state.map { it?.takeIf { s -> s.group && s.phase != CallPhase.ENDED }?.callId }.distinctUntilChanged().collectLatest { id ->
                if (id == null) return@collectLatest
                while (true) {
                    delay(5_000)
                    val sess = groupSession() ?: continue
                    runCatching { sess.stats() }.getOrNull()?.forEach { st ->
                        Log.i("RisiMe", "calls: group stats ${st.identity} cryptor=${st.cryptor} audio_packets=${st.audioPackets} audio_bytes=${st.audioBytes} audio_energy=${"%.4f".format(st.audioEnergy)} concealed=${st.concealed} jb_emitted=${st.jitterEmitted} video_frames=${st.videoFrames} ${st.videoWidth}x${st.videoHeight}")
                    }
                }
            }
        }
        // Prune 24-h marks.
        scope.launch { runCatching { port.callMarkDao.prune(System.currentTimeMillis() - CallStateMachine.DEDUPE_MS) } }
        // Decision 054: the socket down for SOCKET_LOSS_MS while a call is being set up → "Can't connect the call".
        scope.launch {
            combine(state, port.connection) { s, c -> s?.takeIf { it.phase in SETUP_PHASES }?.callId to (c == ConnectionState.Live) }
                .distinctUntilChanged()
                .collectLatest { (callId, live) ->
                    if (callId == null || live) return@collectLatest
                    delay(SOCKET_LOSS_MS)
                    log("socket down ${SOCKET_LOSS_MS} ms during call setup: ending $callId")
                    _machine.value?.fail(callId)
                }
        }
    }

    companion object {
        /** Decision 054: how long the realtime socket may stay down while a call is being set up. */
        const val SOCKET_LOSS_MS = 15_000L
        private val SETUP_PHASES = setOf(CallPhase.CALLING, CallPhase.RINGING_OUT, CallPhase.ANSWERING, CallPhase.CONNECTING)
        private const val PREFS = "risime_calls"
        private const val KEY_ACTIVE = "active_call"
    }

    // ---------------------------------------------------------------- process death (decision 054)

    /** The call this process has now, persisted so the next process can end it properly after a kill. */
    private fun persistActive(s: CallSnapshot?) {
        runCatching {
            val e = prefs.edit()
            if (s == null || s.phase == CallPhase.ENDED) e.remove(KEY_ACTIVE) else e.putString(KEY_ACTIVE, ActiveCallRecord(s.callId, s.conversationId, s.peerUserId, s.outgoing, s.phase.name, s.connectedAtMs != null).encode())
            e.apply()
        }
    }

    /**
     * A new process: no call exists yet, so anything left from the last one is stale. Stop the
     * foreground service and the ringing notification, give the audio mode back, and end the call
     * the dead process had: its `call_end` goes out (the peer stops waiting and gets its line).
     */
    internal suspend fun cleanupAfterProcessStart() {
        if (state.value != null) return
        runCatching { context.stopService(Intent(context, CallService::class.java)) }
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel(CallNotifications.CALL_ID) }
        // This process's own audio requests (Android 12+ keeps one per process); a cellular call is untouched.
        releaseAudio()
        val rec = runCatching { prefs.getString(KEY_ACTIVE, null)?.let(ActiveCallRecord::decode) }.getOrNull()
        runCatching { prefs.edit().remove(KEY_ACTIVE).apply() }
        rec ?: return
        log("process start: ending ${rec.callId} left by the previous process (${rec.phase})")
        val mark = marks.get(rec.callId)
        marks.put((mark ?: CallMark(rec.callId)).copy(ended = true, at = System.currentTimeMillis()))
        if (mark?.ended == true) return
        ActiveCallRecord.endReason(rec)?.let { reason -> port.queueCallEnd(rec.conversationId, rec.peerUserId, CallEnvelope.End(rec.callId, reason), false) }
    }

    /** The group call's SFU session (debug stats, the video renderers). */
    @Volatile private var currentGroupSession: SfuSession? = null

    fun groupSession(): SfuSession? = currentGroupSession

    private val groupPort = object : GroupCallPort {
        override suspend fun iceServers(): List<IceServer> = environment.iceServers()
        override suspend fun catchUp(conv: String) = port.groupCatchUp(conv)
        override suspend fun epoch(conv: String): Long? = port.groupEpoch(conv)
        override suspend fun frameKeys(conv: String, callId: String): lk.codegen.risime.data.mls.CallKeys {
            val k = port.groupFrameKeys(conv, callId)
            // Debug builds only (the fail-closed device test): `run-as <pkg> touch files/debug_wrong_call_keys`
            // installs keys that are NOT the MLS-derived ones, so this phone must hear nobody and nobody it.
            if (BuildConfig.DEBUG && java.io.File(context.filesDir, "debug_wrong_call_keys").exists()) {
                log("DEBUG: wrong call keys installed (fail-closed test)")
                k.keys.forEach { key -> for (i in key.key.indices) key.key[i] = (key.key[i].toInt() xor 0x5a).toByte() }
            }
            return k
        }
        override suspend fun room(conv: String, callId: String, media: String, action: String): RoomOutcome =
            when (val r = withTimeoutOrNull(10_000) { port.api.callsRoom(lk.codegen.risime.net.CallsRoomRequest(conv, callId, media, action), port.deviceId()) }) {
                is ApiResult.Ok -> RoomOutcome.Ok(r.value)
                is ApiResult.Error -> when (r.code) {
                    lk.codegen.risime.net.CallErrors.CALL_ENDED -> RoomOutcome.Ended
                    lk.codegen.risime.net.CallErrors.CALL_FULL -> RoomOutcome.Full
                    else -> RoomOutcome.Failed("${r.httpStatus} ${r.code}")
                }
                else -> RoomOutcome.Failed("network")
            }
        override suspend fun signal(conv: String, env: CallEnvelope.Env, media: String): SignalOutcome {
            repeat(3) { attempt ->
                when (val r = port.sendGroupSignal(conv, env, media)) {
                    is PushResult.Ok -> return SignalOutcome.Ok
                    is PushResult.Rejected -> if (r.reason == "rate_limited" && env !is CallEnvelope.SfuOffer && attempt < 2) delay(1_000) else return SignalOutcome.Refused(r.reason)
                    PushResult.Unavailable -> withTimeoutOrNull(5_000) { port.connection.first { it == ConnectionState.Live } }
                }
            }
            return SignalOutcome.Unavailable
        }
        override suspend fun started(conv: String, env: GroupCallEnvelope) = port.groupCallStarted(conv, env)
        override suspend fun ended(conv: String, env: GroupCallEnvelope) = port.groupCallEnded(conv, env)
        override suspend fun over(conv: String, callId: String) = port.groupCallOver(conv, callId)
        override fun busy(): Boolean {
            if (_machine.value?.currentCallId() != null) return true
            val am = context.getSystemService(AudioManager::class.java) ?: return false
            return am.mode == AudioManager.MODE_IN_CALL
        }
    }

    /** The SFU as the machine sees it: remembers the live session for stats and video. */
    private val trackedSfu = object : SfuConnector {
        override val available: Boolean get() = sfu.available
        override suspend fun connect(p: SfuConnect, listener: SfuListener): SfuSession {
            val s = sfu.connect(p, listener)
            currentGroupSession = s
            return object : SfuSession by s {
                override fun disconnect() {
                    if (currentGroupSession === s) currentGroupSession = null
                    s.disconnect()
                }
            }
        }
    }

    private fun newGroupMachine(me: String, device: String) = GroupCallMachine(
        me, device, scope, trackedSfu, groupPort, marks,
        serverNow = { port.serverNow() }, log = log,
    )

    private fun newMachine(me: String, device: String) = CallStateMachine(
        me, device, scope, media, signals, marks, environment,
        serverNow = { port.serverNow() }, log = log,
        tamperRemoteFingerprint = { BuildConfig.DEBUG && debugTamperFingerprint },
    )

    /** §16.10 (f) debug-only negative test switch. */
    @Volatile var debugTamperFingerprint = false

    // ---------------------------------------------------------------- user actions

    fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The call button (RECORD_AUDIO already granted by the UI); [video] with [camera] = CAMERA granted (§19.6). */
    fun placeCall(conversationId: String, video: Boolean = false, camera: Boolean = video) {
        if (lk.codegen.risime.net.isGroupConversation(conversationId)) {
            scope.launch { _group.value?.start(conversationId, video, camera) }
            return
        }
        scope.launch { _machine.value?.placeCall(conversationId, video, camera) }
    }

    /** §20.4 Join from a group call's line (the room is checked by `join`). */
    fun joinGroupCall(conversationId: String, callId: String, media: String, starter: String, camera: Boolean = false) {
        // Join on the line of the call this phone is already in: just back to the call screen.
        if (state.value?.callId == callId && state.value?.phase != CallPhase.ENDED && state.value?.phase != CallPhase.RINGING_IN) return
        scope.launch { _group.value?.join(conversationId, callId, media, starter, camera) }
    }

    /**
     * §20.4 "running" for a `started` line without `ended` (under 4 h old), on chat open and on Join:
     * a missing room turns the line "… ended". Returns whether the call runs (null: unknown).
     */
    suspend fun groupCallRunning(conversationId: String, callId: String, media: String): Boolean? {
        val r = withTimeoutOrNull(5_000) { port.api.callsRoomStatus(conversationId, callId, media, port.deviceId()) }
        val running = when (r) {
            // An empty room (everyone left, LiveKit closes it 20 s later) is not a call to join.
            is ApiResult.Ok -> r.value.active && r.value.participants > 0
            is ApiResult.Error -> if (r.code == lk.codegen.risime.net.CallErrors.CALL_ENDED || r.httpStatus == 404) false else null
            else -> null
        }
        // My own call that is still going is running whatever the room list says right now.
        if (running == false && state.value?.callId == callId && state.value?.phase != CallPhase.ENDED) return true
        if (running == false) port.groupCallOver(conversationId, callId)
        return running
    }

    /** A human tapped Answer in the call screen: [camera] for "Answer" on a video call, false for "Answer without video" (§19.6). */
    fun answer(camera: Boolean = false) {
        scope.launch {
            if (state.value?.group == true) _group.value?.answer(camera = camera) else _machine.value?.answer(camera = camera)
        }
    }

    fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** §19.5 the camera button. */
    fun setCameraWanted(on: Boolean) {
        scope.launch { if (state.value?.group == true) _group.value?.setCameraWanted(on) else _machine.value?.setCameraWanted(on) }
    }

    /** §19.7 front/back. */
    fun switchCamera() {
        scope.launch { if (state.value?.group == true) _group.value?.switchCamera() else _machine.value?.switchCamera() }
    }

    fun hangUp() {
        if (blindRing.value != null) return stopBlindRing()
        scope.launch {
            _group.value?.takeIf { state.value?.group == true }?.hangUp()
            val m = _machine.value
            m?.hangUp()
            // Decision 054: the button always works. Whatever is still shown without a call behind it goes.
            val s = state.value
            if (s != null && s.phase != CallPhase.ENDED && (m == null || m.currentCallId() != s.callId) && _group.value?.currentCallId() != s.callId) {
                log("hang up: no call behind ${s.callId}: clearing")
                onState(null)
            }
        }
    }

    fun setMuted(muted: Boolean) {
        scope.launch { if (state.value?.group == true) _group.value?.setMuted(muted) else _machine.value?.setMuted(muted) }
    }

    /** §20.6 K3: a commit was merged in [conversationId] (the group machine rekeys at once). */
    fun onGroupChanged(conversationId: String) {
        if (lk.codegen.risime.net.isGroupConversation(conversationId)) _group.value?.onGroupChanged(conversationId)
    }

    fun selectEndpoint(e: CallEndpointCompat) {
        scope.launch { telecomScope?.requestEndpointChange(e) }
    }

    // ---------------------------------------------------------------- push (§16.8)

    /** `{"type":"call"}`: start the phoneCall service at once, then sync (unlocked) or ring blind (locked). */
    fun onCallPush() {
        // Decision 054: a wake-up with no call here first clears anything a crash left behind.
        if (state.value == null) scope.launch { runCatching { cleanupAfterProcessStart() } }
        if (port.connection.value == ConnectionState.Live) return // the live socket already brings it
        // Start the phoneCall service inside the high-priority FCM window, before anything else.
        waking.value = true
        startService()
        scope.launch {
            if (port.sessionLocked()) {
                startBlindRing()
                waking.value = false
                return@launch
            }
            // The sync runs on the kept-up socket; the machine rings after the page (§16.3).
            withTimeoutOrNull(20_000) { state.first { it != null } }
            waking.value = false
            refreshService()
        }
    }

    private var blindJob: Job? = null

    private fun startBlindRing() {
        if (blindRing.value != null) return
        if (!notifications.canUseFullScreenIntent()) fullScreenDenied.value = true
        blindRing.value = System.currentTimeMillis()
        startService()
        blindJob = scope.launch {
            delay(CallStateMachine.RING_MS) // the blind ring stops after 45 s
            stopBlindRing()
        }
    }

    fun stopBlindRing() {
        blindJob?.cancel()
        blindRing.value = null
        pendingBlindAnswer = false
        refreshService()
    }

    /**
     * After the fingerprint unlock (the user tapped Answer on the blind ring): the unlocked sync
     * decides. A call still ringing is answered; otherwise "Call ended" and nothing is sent.
     */
    fun onUnlockedAfterBlindAnswer(onNothing: (String) -> Unit) {
        pendingBlindAnswer = true
        scope.launch {
            val s = withTimeoutOrNull(15_000) { state.first { it != null && it.phase != CallPhase.ENDED } }
            blindJob?.cancel()
            blindRing.value = null
            if (s == null) {
                pendingBlindAnswer = false
                onNothing("Call ended")
            }
            refreshService()
        }
    }

    // ---------------------------------------------------------------- state → platform

    private var wakeLock: PowerManager.WakeLock? = null
    private var proximity: PowerManager.WakeLock? = null
    private var lastRingingId: String? = null

    private suspend fun onState(s: CallSnapshot?) {
        state.value = s
        persistActive(s)
        // Decision 054: a Telecom call of any other call id is over (StateFlow conflation, glare).
        telecomCalls.keys.filter { s == null || it != s.callId || s.phase == CallPhase.ENDED }.forEach { endTelecom(it, s?.takeIf { x -> x.callId == it }?.notice) }
        if ((s == null || s.phase == CallPhase.ENDED) && telecomCalls.isEmpty()) releaseAudio()
        if (s != null && s.phase != CallPhase.ENDED) {
            acquireWake()
            if (blindRing.value != null) {
                blindJob?.cancel()
                blindRing.value = null
            }
            ensureTelecom(s)
            if (s.phase == CallPhase.RINGING_IN && lastRingingId != s.callId) {
                lastRingingId = s.callId
                if (!notifications.canUseFullScreenIntent()) fullScreenDenied.value = true
                if (pendingBlindAnswer) {
                    pendingBlindAnswer = false
                    openCallScreen(CallNotifications.ACTION_ANSWER)
                }
            }
            if (s.phase == CallPhase.ANSWERING) telecomAnswer()
            if (s.phase == CallPhase.CONNECTING || s.phase == CallPhase.ACTIVE) telecomActive(s)
        } else {
            releaseWake()
        }
        updateProximity()
        updateRingback(s)
        refreshService()
    }

    // ---- ringback (the caller hears it while the callee's phone rings) ----

    private var ringback: android.media.ToneGenerator? = null

    /** The ringback tone on the voice-call stream while calling/ringing out; stopped on any other phase. */
    private fun updateRingback(s: CallSnapshot?) {
        val want = s != null && s.outgoing && (s.phase == CallPhase.CALLING || s.phase == CallPhase.RINGING_OUT)
        if (want && ringback == null) {
            ringback = runCatching {
                android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also { it.startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE) }
            }.onFailure { Log.w("RisiMe", "ringback: ${it.message}") }.getOrNull()
        } else if (!want) {
            ringback?.let { t -> runCatching { t.stopTone() }; runCatching { t.release() } }
            ringback = null
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RisiMe:call").apply {
            setReferenceCounted(false)
            acquire(CallStateMachine.MAX_CALL_MS) // capped at the 4-h maximum
        }
    }

    private fun releaseWake() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /**
     * The call screen is in front (CallActivity resumed). The proximity sensor may blank the screen
     * only then: after Back the call goes on behind the chats, and a held proximity lock turned the
     * chat screen black whenever the sensor read "near" (a hand, a case, a flaky sensor) — the
     * "dark screen after Back from a call" (GO UX).
     */
    val callScreenVisible = MutableStateFlow(false)

    fun onCallScreenVisible(visible: Boolean) {
        if (callScreenVisible.value == visible) return
        callScreenVisible.value = visible
        updateProximity()
        // §19.5 (android A1): the camera runs only while the call screen is visible and the phone unlocked.
        scope.launch {
            _machine.value?.setScreenVisible(visible)
            _group.value?.setScreenVisible(visible)
        }
    }

    /** §16.9 (S-d): the proximity wake lock only while the endpoint is the earpiece, the call is active and its screen is in front. */
    @Synchronized
    private fun updateProximity() {
        val s = state.value
        // §19.6 (android A6): no proximity wake lock in a video call.
        val want = s?.video != true && wantsProximity(s?.phase, currentEndpoint.value?.type, callScreenVisible.value)
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        if (want && proximity?.isHeld != true && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "RisiMe:proximity").apply {
                setReferenceCounted(false)
                acquire(CallStateMachine.MAX_CALL_MS)
            }
        } else if (!want) {
            runCatching { proximity?.takeIf { it.isHeld }?.release() }
            proximity = null
        }
    }

    // ---- core-telecom (self-managed; audio focus, mode and routing belong to Telecom) ----

    private suspend fun ensureTelecom(s: CallSnapshot) {
        val cm = callsManager ?: return
        if (telecomCalls.containsKey(s.callId)) return
        val callId = s.callId
        val name = callTitle(s)
        val attrs = CallAttributesCompat(
            name, Uri.fromParts("risime", if (s.group) s.conversationId else s.peerUserId, null),
            if (s.outgoing) CallAttributesCompat.DIRECTION_OUTGOING else CallAttributesCompat.DIRECTION_INCOMING,
            // §19.6 (android A6): a video call is a Telecom video call.
            if (s.video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL, 0,
        )
        val video = s.video
        lateinit var handle: TelecomCall
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runCatching {
                // Every callback is bound to THIS call id (decision 054): an older call's callback
                // never answers, declines or hangs up a newer one.
                cm.addCall(
                    attrs,
                    // A Telecom answer (a watch, the car) never turns the camera on (android A3).
                    onAnswer = { _ -> if (_group.value?.currentCallId() == callId) _group.value?.answer(callId, camera = false) else _machine.value?.answer(callId, camera = false) },
                    onDisconnect = { _ -> _machine.value?.onSystemDisconnect(callId); _group.value?.hangUp(callId) },
                    onSetActive = {},
                    // A cellular call took over (android R9): no hold in v1.13, so the call ends.
                    onSetInactive = { _machine.value?.onSystemDisconnect(callId); _group.value?.hangUp(callId) },
                ) {
                    handle.scope = this
                    if (state.value?.callId == callId) {
                        launch { availableEndpoints.collect { if (state.value?.callId == callId) endpoints.value = it } }
                        launch {
                            currentCallEndpoint.collect {
                                if (state.value?.callId == callId) currentEndpoint.value = it
                                updateProximity()
                            }
                        }
                        // §19.6 (android A6): a video call starts on the speaker unless a headset is connected.
                        if (video) launch {
                            val list = availableEndpoints.first { it.isNotEmpty() }
                            val headset = list.any { it.type == CallEndpointCompat.TYPE_BLUETOOTH || it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }
                            val speaker = list.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
                            if (!headset && speaker != null && currentCallEndpoint.first().type == CallEndpointCompat.TYPE_EARPIECE) requestEndpointChange(speaker)
                        }
                    }
                }
            }.onFailure { Log.w("RisiMe", "telecom addCall: ${it.message}") }
        }
        handle = TelecomCall(callId, job)
        telecomCalls[callId] = handle
        job.start()
    }

    private var answeredOn: String? = null
    private var activeOn: String? = null

    private fun telecomAnswer() {
        val id = state.value?.callId ?: return
        if (answeredOn == id) return
        answeredOn = id
        val type = if (state.value?.video == true) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL
        scope.launch { waitScope(id)?.answer(type) }
    }

    private fun telecomActive(s: CallSnapshot) {
        if (activeOn == s.callId) return
        activeOn = s.callId
        if (s.outgoing) scope.launch { waitScope(s.callId)?.setActive() }
    }

    private suspend fun waitScope(callId: String): CallControlScope? = withTimeoutOrNull(3_000) {
        var sc = telecomCalls[callId]?.scope
        while (sc == null && telecomCalls.containsKey(callId)) {
            delay(50)
            sc = telecomCalls[callId]?.scope
        }
        sc
    }

    /** Disconnects the Telecom call of [callId], waiting (≤ 3 s) for Telecom to have added it first, then drops it. */
    private fun endTelecom(callId: String, notice: CallNotice?) {
        val h = telecomCalls.remove(callId) ?: return
        if (state.value?.callId == callId || state.value == null) {
            endpoints.value = emptyList()
            currentEndpoint.value = null
        }
        val cause = telecomDisconnectCause(notice)
        scope.launch {
            val sc = h.scope ?: withTimeoutOrNull(3_000) {
                while (h.scope == null && h.job.isActive) delay(50)
                h.scope
            }
            // Decision 054 (the P0 safety bug): core-telecom accepts only LOCAL, REMOTE, MISSED and
            // REJECTED; nightly.16/17 passed ERROR/BUSY/ANSWERED_ELSEWHERE, the disconnect threw, and
            // the self-managed call stayed ACTIVE in Telecom, holding the mic, the speaker and
            // MODE_IN_COMMUNICATION (normal phone calls broke until a reboot). Fall back to LOCAL.
            val first = runCatching { sc?.disconnect(DisconnectCause(cause)) }
            val ok = first.getOrNull()?.let { it is androidx.core.telecom.CallControlResult.Success } ?: (sc == null)
            if (!ok) {
                Log.w("RisiMe", "telecom disconnect($cause): ${first.exceptionOrNull()?.message ?: first.getOrNull()}; retrying LOCAL")
                runCatching { sc?.disconnect(DisconnectCause(DisconnectCause.LOCAL)) }.onFailure { Log.w("RisiMe", "telecom disconnect(LOCAL): ${it.message}") }
            }
            delay(1_000)
            h.job.cancel()
            if (state.value.let { it == null || it.phase == CallPhase.ENDED } && telecomCalls.isEmpty()) releaseAudio()
        }
    }

    /**
     * Decision 054: give every audio resource back once no call is left: this process's audio mode
     * (MODE_NORMAL, unless a cellular call holds MODE_IN_CALL), the communication device, the
     * speakerphone. The WebRTC audio device module (and its AudioRecord) is released by
     * MediaSession.close on every end path; RisiMe never requests audio focus (Telecom owns it).
     */
    fun releaseAudio() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching { if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() }
        @Suppress("DEPRECATION")
        runCatching { if (am.isSpeakerphoneOn) am.isSpeakerphoneOn = false }
        runCatching { if (am.mode != AudioManager.MODE_NORMAL && am.mode != AudioManager.MODE_IN_CALL) am.mode = AudioManager.MODE_NORMAL }
    }

    /** The service's task was swiped away (onTaskRemoved) or the app is going away: end everything (decision 054). */
    fun onTaskRemoved() {
        if (blindRing.value != null) stopBlindRing()
        scope.launch {
            _machine.value?.hangUp()
            _group.value?.hangUp()
            onState(null)
            releaseAudio()
        }
    }

    // ---- the foreground service (android R5) ----

    /** What the service shows now, and whether it may add the microphone type. Null = stop. */
    suspend fun serviceNotification(): Pair<Notification, Boolean>? {
        val s = state.value
        if (blindRing.value != null && (s == null || s.phase == CallPhase.ENDED)) return notifications.incoming(null) to false
        if (s == null || s.phase == CallPhase.ENDED) return if (waking.value) notifications.checking() to false else null
        val name = callTitle(s)
        return when (s.phase) {
            CallPhase.RINGING_IN -> notifications.incoming(name, video = s.video, group = s.group) to false
            else -> notifications.ongoing(name, CallTexts.status(s.phase, s.connectedAtMs) ?: if (s.video) "Video call" else "Voice call", s.connectedAtMs) to hasMicPermission()
        }
    }

    private fun startService() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)) }
            .onFailure { Log.w("RisiMe", "call service: ${it.message}") }
    }

    private fun refreshService() {
        val active = state.value?.let { it.phase != CallPhase.ENDED } == true || blindRing.value != null || waking.value
        if (active) startService() else context.stopService(Intent(context, CallService::class.java))
    }

    /** Test hook: whether the proximity wake lock is held now. */
    internal fun proximityHeld(): Boolean = proximity?.isHeld == true

    fun openCallScreen(action: String = CallNotifications.ACTION_SHOW) {
        runCatching {
            context.startActivity(Intent(context, CallActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /** True while a call (or its blind ring) exists. */
    fun inCall(): Boolean = state.value?.let { it.phase != CallPhase.ENDED } == true || blindRing.value != null

    @Suppress("unused")
    private fun sdk() = Build.VERSION.SDK_INT

    /** Test hook: the Telecom calls this process holds (decision 054: never more than the current one). */
    internal fun telecomCallIds(): Set<String> = telecomCalls.keys.toSet()

    /** Name lookups for the UI. */
    suspend fun nameOf(userId: String): String = port.displayName(userId)

    /** §20.4 the group's name (the call screen's title). */
    suspend fun groupNameOf(conversationId: String): String = port.groupName(conversationId)

    /**
     * What Telecom, the notification and the ongoing bar call this call: the peer for a 1:1 call;
     * for a group "Kamal · Pilot team" while it rings in (§20.4 step 2), else the group's name.
     */
    suspend fun callTitle(s: CallSnapshot): String = when {
        !s.group -> port.displayName(s.peerUserId)
        s.phase == CallPhase.RINGING_IN -> "${port.displayName(s.peerUserId)} · ${port.groupName(s.conversationId)}"
        else -> port.groupName(s.conversationId)
    }

    /** First non-null machine (tests and the debug screen). */
    suspend fun awaitMachine(): CallStateMachine? = withTimeoutOrNull(5_000) { machineFlow.map { it }.firstOrNull { it != null } }
}

/** One device, one call: the live one wins (the 1:1 call first); otherwise whichever is lingering "ended". */
fun pickCall(oneToOne: CallSnapshot?, group: CallSnapshot?): CallSnapshot? = when {
    oneToOne != null && oneToOne.phase != CallPhase.ENDED -> oneToOne
    group != null && group.phase != CallPhase.ENDED -> group
    else -> oneToOne ?: group
}

/**
 * Decision 054: the only DisconnectCause codes core-telecom's `disconnect` accepts are LOCAL,
 * REMOTE, MISSED and REJECTED (anything else throws, and the call stays in Telecom).
 */
fun telecomDisconnectCause(notice: CallNotice?): Int = when (notice) {
    null, CallNotice.ANSWERED_ELSEWHERE -> DisconnectCause.MISSED
    CallNotice.DECLINED -> DisconnectCause.REJECTED
    CallNotice.CALL_ENDED, CallNotice.IN_ANOTHER_CALL -> DisconnectCause.LOCAL
    else -> DisconnectCause.REMOTE
}

/** Decision 054: the call this process had, persisted (SharedPreferences) so the next process can end it after a kill. */
data class ActiveCallRecord(val callId: String, val conversationId: String, val peerUserId: String, val outgoing: Boolean, val phase: String, val connected: Boolean) {
    fun encode(): String = listOf(callId, conversationId, peerUserId, outgoing.toString(), phase, connected.toString()).joinToString("|")

    companion object {
        fun decode(s: String): ActiveCallRecord? = s.split("|").takeIf { it.size == 6 }?.let { ActiveCallRecord(it[0], it[1], it[2], it[3].toBoolean(), it[4], it[5].toBoolean()) }

        /**
         * The `call_end` a killed process owes: a ringing callee owes nothing (the caller's ring
         * timeout ends it); a caller still ringing cancels (the callee gets "Missed voice call");
         * anything that was answered or connecting failed.
         */
        fun endReason(r: ActiveCallRecord): String? = when {
            // §20: a group call owes no `call_end`; its line reads "… ended" once the room is gone.
            lk.codegen.risime.net.isGroupConversation(r.conversationId) -> null
            !r.outgoing && r.phase == CallPhase.RINGING_IN.name -> null
            r.outgoing && (r.phase == CallPhase.CALLING.name || r.phase == CallPhase.RINGING_OUT.name) -> CallEnvelope.R_CANCELLED
            else -> CallEnvelope.R_FAILED
        }
    }
}

/** UI texts (§16.4–§16.6, §16.11). */
object CallTexts {
    fun status(phase: CallPhase, connectedAtMs: Long?, now: Long = System.currentTimeMillis(), video: Boolean = false): String? = when (phase) {
        CallPhase.CALLING -> "Calling…"
        CallPhase.RINGING_OUT -> "Ringing…"
        CallPhase.RINGING_IN -> if (video) "Incoming video call" else "Incoming voice call"
        CallPhase.ANSWERING, CallPhase.CONNECTING -> "Connecting…"
        CallPhase.ACTIVE -> connectedAtMs?.let { CallLines.duration(((now - it) / 1000).coerceAtLeast(0)) }
        CallPhase.RECONNECTING -> "Reconnecting…"
        CallPhase.ENDED -> "Call ended"
    }

    fun notice(n: CallNotice, name: String): String = when (n) {
        CallNotice.CALL_ENDED -> "Call ended"
        CallNotice.DECLINED -> "Call declined"
        CallNotice.BUSY -> "$name is on another call"
        CallNotice.NO_ANSWER -> "No answer"
        CallNotice.ANSWERED_ELSEWHERE -> "Answered on another device"
        CallNotice.CANT_CONNECT -> "Can't connect the call"
        CallNotice.NOT_READY -> lk.codegen.risime.ui.chat.peerCantTakeCallsText(name)
        CallNotice.NOT_E2EE -> "Calls need an end-to-end encrypted chat."
        CallNotice.RATE_LIMITED -> "Too many calls. Try again in a minute."
        CallNotice.NOT_FRIENDS -> "You can only call friends"
        CallNotice.IN_ANOTHER_CALL -> "You're already in a call"
        CallNotice.VIDEO_NOT_READY -> videoNotReadyText(name)
        CallNotice.ROOM_ENDED -> "This call has ended"
        CallNotice.CALL_FULL -> "This call is full"
        CallNotice.LOST_CONNECTION -> "Lost connection"
        CallNotice.REMOVED -> "You're no longer in this group"
        CallNotice.GROUP_NOT_READY -> GROUP_NOT_READY_TEXT
    }

    /** §20.1 the disabled group call buttons. */
    const val GROUP_NOT_READY_TEXT = "Nobody else in this group can join calls yet"
    const val GROUP_UPDATE_TEXT = "Update RisiMe on this phone to make calls"

    /** §19.1: the disabled video button and the `video_not_ready` refusal. */
    fun videoNotReadyText(name: String) = "$name needs to update the app for video calls"
}

/**
 * The proximity screen-off lock is wanted only for an active call on the earpiece (or an unknown
 * route) whose call screen is in front; never behind the chats or on speaker/Bluetooth/headset.
 */
fun wantsProximity(phase: CallPhase?, endpointType: Int?, screenVisible: Boolean): Boolean =
    screenVisible && phase == CallPhase.ACTIVE && (endpointType == null || endpointType == CallEndpointCompat.TYPE_EARPIECE)
