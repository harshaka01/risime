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
    suspend fun sendSignal(conv: String, peer: String, env: CallEnvelope.Env): PushResult<*>
    suspend fun queueCallEnd(conv: String, peer: String, env: CallEnvelope.End, rangUnanswered: Boolean)
    fun foreground(): Boolean
}

/**
 * The Android side of §16: owns the state machine, keeps the socket and a wake lock up while a call
 * exists (android R6), runs the `phoneCall` foreground service and core-telecom, posts the call
 * notifications, handles the `call` push (an unlocked sync, or the nameless locked ring of decision
 * 051) and routes audio through Telecom's endpoints.
 */
class CallManager(private val context: Context, private val port: CallAppPort, private val mediaFactory: () -> CallMedia = { WebRtcCallMedia(context, debug = BuildConfig.DEBUG) }) {
    private val log: (String) -> Unit = { Log.i("RisiMe", "calls: $it") }
    val notifications = CallNotifications(context)
    val media: CallMedia by lazy { mediaFactory() }
    private val scope get() = port.scope

    private val _machine = MutableStateFlow<CallStateMachine?>(null)
    val machineFlow: StateFlow<CallStateMachine?> = _machine.asStateFlow()
    val machine: CallStateMachine? get() = _machine.value

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
    @Volatile private var telecomScope: CallControlScope? = null
    private var telecomJob: Job? = null
    private var telecomCallId: String? = null

    private val callsManager: CallsManager? by lazy {
        runCatching { CallsManager(context).also { it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) } }
            .onFailure { Log.w("RisiMe", "core-telecom registration failed: ${it.message}") }.getOrNull()
    }

    /** §16.1 (S-f): advertise `calls` only when this phone can ring, answer and play a call. */
    fun canAdvertise(): Boolean = BuildConfig.CALLS_ENABLED && media.available && callsManager != null && notifications.notificationsAllowed()

    /** Why this phone can't make or take calls (the Settings "Calls" row), null when it can. */
    fun unsupportedReason(): String? = when {
        !BuildConfig.CALLS_ENABLED -> "calls are off in this build"
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
    val hooks: CallHooks = MachineCallHooks({ _machine.value }, marks) { conv, from ->
        scope.launch {
            if (port.foreground() && openConversation?.invoke() == conv) return@launch
            notifications.postMissed(conv, port.displayName(from))
        }
    }

    /** The conversation on screen (no missed-call notification for it). */
    var openConversation: (() -> String?)? = null

    private var turnCache: Pair<List<IceServer>, Long>? = null

    private val signals = object : CallSignals {
        override suspend fun signal(conversationId: String, peer: String, env: CallEnvelope.Env): SignalOutcome {
            repeat(3) { attempt ->
                when (val r = port.sendSignal(conversationId, peer, env)) {
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
    }

    private val environment = object : CallEnvironment {
        override fun audioBusy(): Boolean {
            val am = context.getSystemService(AudioManager::class.java) ?: return false
            // android R9: no READ_PHONE_STATE; another app's call shows in the audio mode.
            return state.value == null && (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION)
        }

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
        // One machine per signed-in user and device.
        scope.launch {
            var lastUser: String? = null
            while (true) {
                val me = runCatching { port.me() }.getOrNull()
                if (me != lastUser) {
                    lastUser = me
                    _machine.value = me?.let { newMachine(it, port.deviceId()) }
                }
                delay(2_000)
            }
        }
        scope.launch {
            _machine.collectLatest { m -> m?.state?.collect { s -> onState(s) } ?: onState(null) }
        }
        // Prune 24-h marks.
        scope.launch { runCatching { port.callMarkDao.prune(System.currentTimeMillis() - CallStateMachine.DEDUPE_MS) } }
    }

    private fun newMachine(me: String, device: String) = CallStateMachine(
        me, device, scope, media, signals, marks, environment,
        serverNow = { port.serverNow() }, log = log,
        tamperRemoteFingerprint = { BuildConfig.DEBUG && debugTamperFingerprint },
    )

    /** §16.10 (f) debug-only negative test switch. */
    @Volatile var debugTamperFingerprint = false

    // ---------------------------------------------------------------- user actions

    fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The call button (RECORD_AUDIO already granted by the UI). */
    fun placeCall(conversationId: String) {
        scope.launch { _machine.value?.placeCall(conversationId) }
    }

    fun answer() {
        scope.launch { _machine.value?.answer() }
    }

    fun hangUp() {
        if (blindRing.value != null) return stopBlindRing()
        scope.launch { _machine.value?.hangUp() }
    }

    fun setMuted(muted: Boolean) {
        scope.launch { _machine.value?.setMuted(muted) }
    }

    fun selectEndpoint(e: CallEndpointCompat) {
        scope.launch { telecomScope?.requestEndpointChange(e) }
    }

    // ---------------------------------------------------------------- push (§16.8)

    /** `{"type":"call"}`: start the phoneCall service at once, then sync (unlocked) or ring blind (locked). */
    fun onCallPush() {
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
            endTelecom(s)
            releaseWake()
        }
        updateProximity()
        refreshService()
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

    /** §16.9 (S-d): the proximity wake lock only while the endpoint is the earpiece and the call is active. */
    private fun updateProximity() {
        val s = state.value
        val want = s?.phase == CallPhase.ACTIVE && currentEndpoint.value?.type.let { it == null || it == CallEndpointCompat.TYPE_EARPIECE }
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
        if (telecomCallId == s.callId) return
        telecomCallId = s.callId
        val name = port.displayName(s.peerUserId)
        val attrs = CallAttributesCompat(
            name, Uri.fromParts("risime", s.peerUserId, null),
            if (s.outgoing) CallAttributesCompat.DIRECTION_OUTGOING else CallAttributesCompat.DIRECTION_INCOMING,
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL, 0,
        )
        telecomJob = scope.launch {
            runCatching {
                cm.addCall(
                    attrs,
                    onAnswer = { _ -> _machine.value?.answer() },
                    onDisconnect = { _ -> _machine.value?.onSystemDisconnect() },
                    onSetActive = {},
                    // A cellular call took over (android R9): no hold in v1.13, so the call ends.
                    onSetInactive = { _machine.value?.onSystemDisconnect() },
                ) {
                    telecomScope = this
                    launch { availableEndpoints.collect { endpoints.value = it } }
                    launch {
                        currentCallEndpoint.collect {
                            currentEndpoint.value = it
                            updateProximity()
                        }
                    }
                }
            }.onFailure { Log.w("RisiMe", "telecom addCall: ${it.message}") }
        }
    }

    private var answeredOn: String? = null
    private var activeOn: String? = null

    private fun telecomAnswer() {
        val id = state.value?.callId ?: return
        if (answeredOn == id) return
        answeredOn = id
        scope.launch { waitScope()?.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) }
    }

    private fun telecomActive(s: CallSnapshot) {
        if (activeOn == s.callId) return
        activeOn = s.callId
        if (s.outgoing) scope.launch { waitScope()?.setActive() }
    }

    private suspend fun waitScope(): CallControlScope? = withTimeoutOrNull(3_000) {
        while (telecomScope == null) delay(50)
        telecomScope
    }

    private fun endTelecom(s: CallSnapshot?) {
        if (telecomCallId == null) return
        val sc = telecomScope
        telecomScope = null
        telecomCallId = null
        endpoints.value = emptyList()
        currentEndpoint.value = null
        val cause = when (s?.notice) {
            CallNotice.DECLINED -> DisconnectCause.REJECTED
            CallNotice.BUSY -> DisconnectCause.BUSY
            CallNotice.ANSWERED_ELSEWHERE -> DisconnectCause.ANSWERED_ELSEWHERE
            CallNotice.CANT_CONNECT -> DisconnectCause.ERROR
            null -> DisconnectCause.MISSED
            else -> DisconnectCause.LOCAL
        }
        val job = telecomJob
        scope.launch {
            runCatching { sc?.disconnect(DisconnectCause(cause)) }
            delay(1_000)
            job?.cancel()
        }
    }

    // ---- the foreground service (android R5) ----

    /** What the service shows now, and whether it may add the microphone type. Null = stop. */
    suspend fun serviceNotification(): Pair<Notification, Boolean>? {
        val s = state.value
        if (blindRing.value != null && (s == null || s.phase == CallPhase.ENDED)) return notifications.incoming(null) to false
        if (s == null || s.phase == CallPhase.ENDED) return if (waking.value) notifications.checking() to false else null
        val name = port.displayName(s.peerUserId)
        return when (s.phase) {
            CallPhase.RINGING_IN -> notifications.incoming(name) to false
            else -> notifications.ongoing(name, CallTexts.status(s.phase, s.connectedAtMs) ?: "Voice call", s.connectedAtMs) to hasMicPermission()
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

    fun openCallScreen(action: String = CallNotifications.ACTION_SHOW) {
        runCatching {
            context.startActivity(Intent(context, CallActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /** True while a call (or its blind ring) exists. */
    fun inCall(): Boolean = state.value?.let { it.phase != CallPhase.ENDED } == true || blindRing.value != null

    @Suppress("unused")
    private fun sdk() = Build.VERSION.SDK_INT

    /** Name lookups for the UI. */
    suspend fun nameOf(userId: String): String = port.displayName(userId)

    /** First non-null machine (tests and the debug screen). */
    suspend fun awaitMachine(): CallStateMachine? = withTimeoutOrNull(5_000) { machineFlow.map { it }.firstOrNull { it != null } }
}

/** UI texts (§16.4–§16.6, §16.11). */
object CallTexts {
    fun status(phase: CallPhase, connectedAtMs: Long?, now: Long = System.currentTimeMillis()): String? = when (phase) {
        CallPhase.CALLING -> "Calling…"
        CallPhase.RINGING_OUT -> "Ringing…"
        CallPhase.RINGING_IN -> "Incoming voice call"
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
        CallNotice.NOT_READY -> "$name needs to update the app to receive calls"
        CallNotice.NOT_E2EE -> "Calls need an end-to-end encrypted chat."
        CallNotice.RATE_LIMITED -> "Too many calls. Try again in a minute."
        CallNotice.NOT_FRIENDS -> "You can only call friends"
        CallNotice.IN_ANOTHER_CALL -> "You're already in a call"
    }
}
