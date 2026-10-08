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

    /**
     * Orders every service start/stop decision: a push wake-up's start, the start-up cleanup's stop
     * and [refreshService]. The decision (reading [waking], the call, the blind ring) and the
     * stop happen under it, so no stop can land between a push's start and its startForeground.
     */
    private val serviceLock = Any()

    /** Push wake-ups in progress (under [serviceLock]); [waking] = wakers > 0. */
    private var wakers = 0

    private fun endWake() = synchronized(serviceLock) {
        wakers = (wakers - 1).coerceAtLeast(0)
        waking.value = wakers > 0
    }

    /** Answer as soon as the unlocked sync finds the call (the user tapped Answer on the blind ring). */
    @Volatile var pendingBlindAnswer = false

    /** android R6: keep the realtime connection up regardless of foreground. */
    val keepConnected: StateFlow<Boolean> = MutableStateFlow(false).also { out ->
        scope.launch { combine(state, blindRing, waking) { s, b, w -> s != null || b != null || w }.distinctUntilChanged().collect { out.value = it } }
    }

    // ---- audio routes for the UI (P0 audio routing: Telecom merged with AudioManager) ----
    val routes = MutableStateFlow(CallRoutes())
    /**
     * Decision 054: one core-telecom call per RisiMe call id. The nightly.16 code kept a single
     * slot that a second call id overwrote (glare, a quick re-call) without disconnecting the first:
     * a ghost self-managed call that held MODE_IN_COMMUNICATION (every later call "busy" / "You're
     * already in a call") and whose Telecom callbacks hung up whatever call was current.
     */
    /**
     * One call's routes and its platform call. [telecom] false: core-telecom is missing on this phone,
     * [job] does nothing and the routes go through AudioManager only (the label, the picker and the
     * video-speaker default still work).
     */
    private class TelecomCall(val callId: String, val job: Job, val telecom: Boolean = true) {
        @Volatile var scope: CallControlScope? = null

        /** P0-3: this call's routes, copied by its [EndpointTracker] (the only reader of Telecom's flows). */
        val endpoints = MutableStateFlow<List<CallEndpointCompat>>(emptyList())
        val current = MutableStateFlow<CallEndpointCompat?>(null)

        /** P0 audio routing: the user's pick and the route events of this call ([routeTarget]). */
        val policy = RoutePolicyState()

        /** The merged routes ([mergeRoutes]), recomputed on every Telecom or AudioManager change. */
        @Volatile var routes = CallRoutes()

        /** The last route change went through AudioManager (Telecom had no endpoint for it, or refused). */
        @Volatile var viaAudio = false
        @Volatile var lastTelecomCurrent: String? = null
        @Volatile var lastVideo: Boolean? = null
        val routeBusy = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var routeDirty = false
        @Volatile var lastRouteLine: String? = null
        @Volatile var lastDecision: String? = null
    }
    private val telecomCalls = java.util.concurrent.ConcurrentHashMap<String, TelecomCall>()

    /**
     * Review fix (threading): every route recompute and decision runs on this one-at-a-time lane
     * (Telecom's endpoint changes, AudioManager's device callbacks, phase changes, the user's picks),
     * never on the main thread next to a pass on another thread.
     */
    private val routeLane: kotlinx.coroutines.CoroutineDispatcher by lazy {
        val d = scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as? kotlinx.coroutines.CoroutineDispatcher
        // Dispatchers.Unconfined (tests) has no limited view: the default dispatcher's then.
        runCatching { d?.limitedParallelism(1) }.getOrNull() ?: kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)
    }

    private fun onRouteLane(block: suspend () -> Unit) {
        scope.launch(routeLane) { block() }
    }

    private val callsManager: CallsManager? by lazy {
        runCatching { CallsManager(context).also { it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING) } }
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

    companion object {
        /** Decision 054: how long the realtime socket may stay down while a call is being set up. */
        const val SOCKET_LOSS_MS = 15_000L
        private val SETUP_PHASES = setOf(CallPhase.CALLING, CallPhase.RINGING_OUT, CallPhase.ANSWERING, CallPhase.CONNECTING)
        private const val PREFS = "risime_calls"
        private const val KEY_ACTIVE = "active_call"

        /** How long an AudioManager route may take to show up in the read-back. */
        private const val AUDIO_VERIFY_MS = 600L
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
        // Never stop a service a push wake-up (or a blind ring) has just started.
        val stopped = synchronized(serviceLock) {
            if (waking.value || blindRing.value != null) false
            else { runCatching { context.stopService(Intent(context, CallService::class.java)) }; true }
        }
        if (stopped) runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel(CallNotifications.CALL_ID) }
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

    /**
     * P0 audio routing: the user picked [e] (the button's switch or the picker). The pick is kept by
     * the call's [RoutePolicyState] (never overridden, except by a Bluetooth device connecting after
     * it or a voice→video switch). Telecom's endpoint when it lists one, else AudioManager.
     */
    fun selectRoute(e: EndpointUi) {
        val id = state.value?.callId ?: return
        val h = telecomCalls[id]
        h?.policy?.userPicked(e.kind)
        log("route decision (user): → ${routeKindName(e.kind)}: the user's pick")
        onRouteLane {
            if (h != null) {
                applyRoute(h, e.kind, "user pick", e.id)
            } else {
                val r = runCatching { audioSetRoute(e.kind) }
                log("route set ${routeKindName(e.kind)} (user pick) via audio: ${r.getOrNull()?.let { audioRouteResult(e.kind, it) } ?: r.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * P0 audio routing, the v1.23 voice→video hook: the call's video flag changed. Turning it on moves
     * the route to the speaker (unless Bluetooth or a headset is there) and clears an earlier pick;
     * the user's picks after it are never overridden. [onState] calls it when a snapshot's video flag
     * changes; the mid-call switch (decision 062) may call it directly.
     */
    fun onCallVideoChanged(callId: String, video: Boolean) {
        val h = telecomCalls[callId] ?: return
        h.policy.videoChanged(video)
        log("route: the call turned ${if (video) "to video" else "to voice"}")
        checkRoute(callId, if (video) "video on" else "video off")
    }

    // ---------------------------------------------------------------- push (§16.8)

    /** `{"type":"call"}`: start the phoneCall service at once, then sync (unlocked) or ring blind (locked). */
    fun onCallPush() {
        // Decision 054: a wake-up with no call here first clears anything a crash left behind.
        val cleanup = state.value == null
        if (port.connection.value == ConnectionState.Live) { // the live socket already brings it
            if (cleanup) scope.launch { runCatching { cleanupAfterProcessStart() } }
            return
        }
        // Start the phoneCall service inside the high-priority FCM window, before anything else.
        // P0 (push-device-test): under the lock, so the stale-call cleanup above can't stop the
        // service between startForegroundService and its startForeground (that crashed the app with
        // ForegroundServiceDidNotStartInTimeException on every call push: no ring).
        synchronized(serviceLock) {
            wakers++
            waking.value = true
            startService()
        }
        // Then the stale-call cleanup (it leaves the service alone while waking).
        if (cleanup) scope.launch { runCatching { cleanupAfterProcessStart() } }
        scope.launch {
            if (port.sessionLocked()) {
                startBlindRing()
                endWake()
                return@launch
            }
            // The sync runs on the kept-up socket; the machine rings after the page (§16.3).
            withTimeoutOrNull(20_000) { state.first { it != null } }
            endWake()
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
            // P0-3: the call on screen shows its own routes (copied from its handle).
            telecomCalls[s.callId]?.let { h ->
                onRouteLane { publishRoutes(h) }
                val was = h.lastVideo
                h.lastVideo = s.video
                if (was != null && was != s.video) onCallVideoChanged(s.callId, s.video)
            }
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
            // P0 audio routing: the policy runs in every in-call phase (decisions are logged once each).
            if (routePhase(s.phase)) checkRoute(s.callId, s.phase.name)
        } else {
            releaseWake()
        }
        updateProximity()
        updateRingback(s)
        refreshService()
    }

    // ---- ringback (the caller hears it while the callee's phone rings) ----

    // Thread-safe owner: onState can run on several threads (hang-up, state collector); see RingbackTone.
    private val ringback = RingbackTone {
        val g = android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
        g.startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE)
        object : RingTone {
            override fun stop() = g.stopTone()
            override fun release() = g.release()
        }
    }

    /** The ringback tone on the voice-call stream while calling/ringing out; stopped on any other phase. */
    private fun updateRingback(s: CallSnapshot?) {
        ringback.update(s != null && s.outgoing && (s.phase == CallPhase.CALLING || s.phase == CallPhase.RINGING_OUT))
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
        val want = s?.video != true && wantsProximity(s?.phase, telecomType(routes.value.current?.kind), callScreenVisible.value)
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        if (want && proximity?.isHeld != true && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            // A platform without a usable proximity sensor may still refuse the lock: no lock, no crash.
            proximity = runCatching {
                pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "RisiMe:proximity").apply {
                    setReferenceCounted(false)
                    acquire(CallStateMachine.MAX_CALL_MS)
                }
            }.onFailure { Log.w("RisiMe", "proximity lock: ${it.message}") }.getOrNull()
        } else if (!want) {
            runCatching { proximity?.takeIf { it.isHeld }?.release() }
            proximity = null
        }
    }

    // ---- core-telecom (self-managed; audio focus, mode and routing belong to Telecom) ----

    private suspend fun ensureTelecom(s: CallSnapshot) {
        // Re-registered on every call after releaseAudio unregistered it (no-op while registered).
        watchAudioDevices()
        if (telecomCalls.containsKey(s.callId)) return
        val callId = s.callId
        val cm = callsManager
        if (cm == null) {
            // Review fix (no Telecom): a handle with a no-op job, so the AudioManager routes, the
            // button's label and the video-speaker default work without core-telecom.
            val h = TelecomCall(callId, Job().also { it.complete() }, telecom = false)
            h.lastVideo = s.video
            telecomCalls[callId] = h
            log("route: no Telecom, AudioManager routes only")
            onRouteLane { publishRoutes(h) }
            return
        }
        val name = callTitle(s)
        val attrs = CallAttributesCompat(
            name, Uri.fromParts("risime", if (s.group) s.conversationId else s.peerUserId, null),
            if (s.outgoing) CallAttributesCompat.DIRECTION_OUTGOING else CallAttributesCompat.DIRECTION_INCOMING,
            // §19.6 (android A6): a video call is a Telecom video call.
            if (s.video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL, 0,
        )
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
                    // P0-3: availableEndpoints and currentCallEndpoint are Channel.receiveAsFlow() in
                    // core-telecom 1.0.1 (each value reaches ONE collector). The tracker is their only
                    // reader, started for every call; the UI and the route policy read its copies.
                    EndpointTracker(availableEndpoints, currentCallEndpoint, handle.endpoints, handle.current).start(this) {
                        onRouteLane {
                            publishRoutes(handle)
                            checkRoute(callId, "telecom route change")
                        }
                    }
                }
            }.onFailure { Log.w("RisiMe", "telecom addCall: ${it.message}") }
        }
        handle = TelecomCall(callId, job)
        handle.lastVideo = s.video
        telecomCalls[callId] = handle
        job.start()
        // AudioManager's routes count from the start (Telecom's lists can be empty or late).
        onRouteLane { publishRoutes(handle) }
    }

    private var answeredOn: String? = null
    private var activeOn: String? = null

    private fun telecomAnswer() {
        val id = state.value?.callId ?: return
        if (answeredOn == id) return
        answeredOn = id
        val type = if (state.value?.video == true) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL
        scope.launch {
            val r = runCatching { waitScope(id)?.answer(type) }
            log("telecom answer: ${r.getOrNull() ?: r.exceptionOrNull()?.message}")
            checkRoute(id, "answer")
        }
    }

    private fun telecomActive(s: CallSnapshot) {
        if (activeOn == s.callId) return
        activeOn = s.callId
        if (s.outgoing) scope.launch {
            val r = runCatching { waitScope(s.callId)?.setActive() }
            log("telecom setActive: ${r.getOrNull() ?: r.exceptionOrNull()?.message}")
            checkRoute(s.callId, "setActive")
        }
    }

    /**
     * P0 audio routing: recomputes [h]'s routes (Telecom merged with AudioManager, [mergeRoutes]) and
     * copies them into the UI's flow while its call is the current one; logs every change as
     * `calls: route current=<KIND> available=<KIND,…> via=telecom|audio`.
     */
    private fun publishRoutes(h: TelecomCall) {
        val tcur = h.current.value
        val tid = tcur?.identifier?.toString()
        if (tid != h.lastTelecomCurrent) {
            h.lastTelecomCurrent = tid
            // Telecom moved the route itself (or confirmed ours): its current is the real one again.
            if (tid != null) h.viaAudio = false
        }
        val audio = runCatching { audioRoutes() }.getOrDefault(emptyList<EndpointUi.Kind>() to null)
        val r = mergeRoutes(h.endpoints.value.map(::endpointUi), tcur?.let(::endpointUi), audio.first, audio.second, h.viaAudio)
        h.routes = r
        if (state.value?.callId != h.callId || telecomCalls[h.callId] !== h) return
        routes.value = r
        val line = r.line()
        if (line != h.lastRouteLine) {
            h.lastRouteLine = line
            log("route $line")
        }
        updateProximity()
    }

    /**
     * P0 audio routing: runs [callId]'s [RoutePolicyState] on the current routes and applies its
     * decision. Called on every Telecom route change, AudioManager device change, phase change,
     * answer/setActive and video change; one pass at a time per call (a change during a pass runs
     * another). Every decision is logged once with its reason (`calls: route decision (<why>): …`).
     */
    private fun checkRoute(callId: String, why: String) {
        val h = telecomCalls[callId] ?: return
        if (!h.routeBusy.compareAndSet(false, true)) {
            h.routeDirty = true
            return
        }
        scope.launch(routeLane) {
            try {
                do {
                    h.routeDirty = false
                    val s = state.value?.takeIf { it.callId == callId } ?: break
                    if (telecomCalls[callId] !== h) break
                    publishRoutes(h)
                    val r = h.routes
                    val d = h.policy.decide(s.video, s.phase, r.available.map { it.kind }.toSet(), r.current?.kind)
                    val text = "${d.target?.let { "→ ${routeKindName(it)}" } ?: "keep"}: ${d.reason} [${r.line()}]"
                    if (d.target != null || text != h.lastDecision) {
                        h.lastDecision = text
                        log("route decision ($why): $text")
                    }
                    // A failed attempt (refused, or the route didn't take) is asked again: the policy
                    // counts the same ask and gives up after MAX_TRIES.
                    if (d.target != null && !applyRoute(h, d.target, d.reason, null)) h.routeDirty = true
                } while (h.routeDirty)
            } finally {
                h.routeBusy.set(false)
            }
            if (h.routeDirty) checkRoute(callId, why)
        }
    }

    /**
     * Moves [h]'s route to [kind]: Telecom's endpoint ([id] first, else the first of that kind) when
     * Telecom lists one and accepts the change; else AudioManager (setCommunicationDevice on API 31+,
     * the speakerphone/SCO switches below). Logged as `calls: route set <KIND> (<reason>) via …`.
     *
     * Review fix: the AudioManager route counts only when it is read back ([audioSetRoute] returns
     * the actual route; a mismatch is a failed attempt). When it didn't take and the call has a
     * Telecom endpoint of that kind that wasn't tried yet, Telecom is asked after all. Returns
     * whether the route moved.
     */
    private suspend fun applyRoute(h: TelecomCall, kind: EndpointUi.Kind, reason: String, id: String?): Boolean {
        h.policy.requested(kind)
        fun endpoint() = h.endpoints.value.let { l -> l.firstOrNull { it.identifier.toString() == id } ?: l.firstOrNull { telecomKind(it.type) == kind } }
        var telecomTried = false
        suspend fun viaTelecom(ep: CallEndpointCompat): Boolean {
            val sc = h.scope ?: if (h.telecom && h.job.isActive) waitScope(h.callId) else null
            sc ?: return false
            telecomTried = true
            // An earlier AudioManager route would outlive Telecom's change: give it back first.
            if (h.viaAudio) runCatching { audioClearRoute() }
            val r = runCatching { sc.requestEndpointChange(ep) }
            val ok = r.getOrNull() is androidx.core.telecom.CallControlResult.Success
            log("route set ${routeKindName(kind)} ($reason) via telecom: ${r.getOrNull() ?: r.exceptionOrNull()?.message}")
            if (ok) {
                h.viaAudio = false
                publishRoutes(h)
            }
            return ok
        }
        val ep = endpoint()
        if (ep != null && viaTelecom(ep)) return true
        val r = runCatching { audioSetRoute(kind) }
        val actual = r.getOrNull()
        val ok = actual == kind
        if (ok) h.viaAudio = true
        if (ok && telecomCalls[h.callId] !== h) {
            // The call ended while its route was being set: give the route back at once.
            h.viaAudio = false
            audioClearRoute()
        }
        log("route set ${routeKindName(kind)} ($reason) via audio${if (ep == null) " (Telecom lists no ${routeKindName(kind)})" else ""}: ${if (r.isSuccess) audioRouteResult(kind, actual) else r.exceptionOrNull()?.message}")
        if (!ok && !telecomTried && h.telecom) {
            // The AudioManager route didn't take: Telecom's endpoint, when the call has one now.
            endpoint()?.let { e -> if (viaTelecom(e)) return true }
        }
        if (!ok) h.policy.requestFailed()
        publishRoutes(h)
        return ok
    }

    // ---- AudioManager (P0 audio routing: the fallback when Telecom's routes are missing) ----

    /** The call routes AudioManager offers, and its current one. */
    @Suppress("DEPRECATION")
    private fun audioRoutes(): Pair<List<EndpointUi.Kind>, EndpointUi.Kind?> {
        val am = context.getSystemService(AudioManager::class.java) ?: return emptyList<EndpointUi.Kind>() to null
        return if (Build.VERSION.SDK_INT >= 31) {
            val kinds = am.availableCommunicationDevices.mapNotNull { audioDeviceKind(it.type) }.distinct()
            kinds to am.communicationDevice?.let { audioDeviceKind(it.type) }
        } else {
            val kinds = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { audioDeviceKind(it.type) }.distinct()
            val cur = when {
                am.isBluetoothScoOn && EndpointUi.Kind.BLUETOOTH in kinds -> EndpointUi.Kind.BLUETOOTH
                am.isSpeakerphoneOn -> EndpointUi.Kind.SPEAKER
                EndpointUi.Kind.WIRED in kinds -> EndpointUi.Kind.WIRED
                EndpointUi.Kind.EARPIECE in kinds -> EndpointUi.Kind.EARPIECE
                else -> null
            }
            kinds to cur
        }
    }

    /**
     * Sets the AudioManager route to [kind] and reads it back (waiting ≤ [AUDIO_VERIFY_MS] for the
     * communication-device change to land): returns the route actually in place afterwards (null:
     * none), so the caller can tell a route that didn't take from one that did.
     */
    private suspend fun audioSetRoute(kind: EndpointUi.Kind): EndpointUi.Kind? {
        if (!audioSetRouteNow(kind)) return audioRoutes().second
        return withTimeoutOrNull(AUDIO_VERIFY_MS) {
            var actual = audioRoutes().second
            while (actual != kind) {
                delay(50)
                actual = audioRoutes().second
            }
            actual
        } ?: audioRoutes().second
    }

    @Suppress("DEPRECATION")
    private fun audioSetRouteNow(kind: EndpointUi.Kind): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 31) {
            val dev = am.availableCommunicationDevices.firstOrNull { audioDeviceKind(it.type) == kind } ?: return false
            return am.setCommunicationDevice(dev)
        }
        when (kind) {
            EndpointUi.Kind.SPEAKER -> {
                if (am.isBluetoothScoOn) { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
                am.isSpeakerphoneOn = true
            }
            EndpointUi.Kind.EARPIECE, EndpointUi.Kind.WIRED -> {
                if (am.isBluetoothScoOn) { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
                am.isSpeakerphoneOn = false
            }
            EndpointUi.Kind.BLUETOOTH -> {
                am.isSpeakerphoneOn = false
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            }
            EndpointUi.Kind.OTHER -> return false
        }
        return true
    }

    /** Gives back the AudioManager route (the communication device; below API 31 the speakerphone and SCO). */
    private fun audioClearRoute() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        clearAudioManagerRoute(am)
    }

    private val watchingAudio = java.util.concurrent.atomic.AtomicBoolean(false)
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null

    /** The API 31+ communication-device listener (typed Any: the interface is missing below API 31). */
    private var commDeviceListener: Any? = null

    /**
     * AudioManager device and communication-device changes re-run the routes of the current call, on
     * the route lane (the callbacks come on the main thread). Registered for a call, unregistered by
     * [releaseAudio] once no call is left.
     */
    private fun watchAudioDevices() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        if (!watchingAudio.compareAndSet(false, true)) return
        val onChange = {
            onRouteLane { state.value?.callId?.let { id -> telecomCalls[id]?.let { h -> publishRoutes(h); checkRoute(id, "audio devices") } } }
        }
        runCatching {
            val cb = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) = onChange()
                override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) = onChange()
            }
            am.registerAudioDeviceCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
            audioDeviceCallback = cb
            if (Build.VERSION.SDK_INT >= 31) {
                val l = AudioManager.OnCommunicationDeviceChangedListener { onChange() }
                am.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(context), l)
                commDeviceListener = l
            }
        }.onFailure { Log.w("RisiMe", "audio device callback: ${it.message}") }
    }

    private fun unwatchAudioDevices() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        if (!watchingAudio.compareAndSet(true, false)) return
        audioDeviceCallback?.let { cb -> runCatching { am.unregisterAudioDeviceCallback(cb) } }
        audioDeviceCallback = null
        if (Build.VERSION.SDK_INT >= 31) {
            (commDeviceListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let { l -> runCatching { am.removeOnCommunicationDeviceChangedListener(l) } }
        }
        commDeviceListener = null
    }

    private suspend fun waitScope(callId: String): CallControlScope? = withTimeoutOrNull(3_000) {
        var sc = telecomCalls[callId]?.scope
        // A call without Telecom never gets a scope: don't wait for one.
        while (sc == null && telecomCalls[callId]?.telecom == true) {
            delay(50)
            sc = telecomCalls[callId]?.scope
        }
        sc
    }

    /** Disconnects the Telecom call of [callId], waiting (≤ 3 s) for Telecom to have added it first, then drops it. */
    private fun endTelecom(callId: String, notice: CallNotice?) {
        val h = telecomCalls.remove(callId) ?: return
        if (state.value?.callId == callId || state.value == null) routes.value = CallRoutes()
        // Review fix (per-call route leak): the AudioManager route this call set (the communication
        // device, SCO) is given back now, whether or not another call is still there; a call that
        // is still there re-reads its route and asks again.
        if (h.viaAudio) onRouteLane {
            h.viaAudio = false
            audioClearRoute()
            log("route: cleared the AudioManager route of the ended call")
            state.value?.callId?.let { id ->
                telecomCalls[id]?.let { other ->
                    other.viaAudio = false
                    other.policy.routeReset()
                    publishRoutes(other)
                    checkRoute(id, "the previous call's route cleared")
                }
            }
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
        // No device callbacks without a call (re-registered by the next call's ensureTelecom).
        if (telecomCalls.isEmpty()) unwatchAudioDevices()
        // The communication device; below API 31 the speakerphone and a started Bluetooth SCO link.
        clearAudioManagerRoute(am)
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
            else -> notifications.ongoing(name, CallTexts.status(s.phase, s.connectedAtMs, video = s.video) ?: if (s.video) "Video call" else "Voice call", s.connectedAtMs) to hasMicPermission()
        }
    }

    private fun startService() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)) }
            .onFailure { Log.w("RisiMe", "call service: ${it.message}") }
    }

    /** Start or stop the service for what is going on now; decided and done under [serviceLock] (no suspension). */
    private fun refreshService() = synchronized(serviceLock) {
        val active = state.value?.let { it.phase != CallPhase.ENDED } == true || blindRing.value != null || waking.value
        if (active) startService() else runCatching { context.stopService(Intent(context, CallService::class.java)) }
        Unit
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

    // Last in the class body: the init block launches collectors on the app scope (Dispatchers.Default)
    // that may run before the constructor reaches a property declared below the block. At app start
    // onState(null) read `callScreenVisible` while it was still null (NPE in updateProximity). Every
    // property is declared above this block; keep new ones above it too.
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
