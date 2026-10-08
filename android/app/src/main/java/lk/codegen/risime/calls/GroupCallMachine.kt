package lk.codegen.risime.calls

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import lk.codegen.risime.data.mls.CallKeys
import lk.codegen.risime.data.mls.CallKeysException
import lk.codegen.risime.net.CallsRoomReply
import lk.codegen.risime.net.CallsRoomRequest
import java.time.Instant
import java.util.UUID

/** §20.2 what `POST /calls/rooms` `start`/`join` came back with. */
sealed interface RoomOutcome {
    data class Ok(val reply: CallsRoomReply) : RoomOutcome

    /** `404 call_ended`. */
    data object Ended : RoomOutcome

    /** `409 call_full`. */
    data object Full : RoomOutcome

    /** 503 calls_unavailable, 403, 429, offline… (the reason only for the log). */
    data class Failed(val reason: String) : RoomOutcome
}

/** What the group call machine needs from the app (the container implements it; a fake in tests). */
interface GroupCallPort {
    /** §16.7 TURN credentials (≤ 3 s, empty = none), used as LiveKit's ICE servers (android A5). */
    suspend fun iceServers(): List<IceServer>

    /** §20.6 K4: catch up the group's commits (before deriving keys, and on an unknown key index). */
    suspend fun catchUp(conv: String)

    /** The local epoch of the group, null when this device has no group state (removed). */
    suspend fun epoch(conv: String): Long?

    /** §20.6 `call_frame_keys` at the current epoch (throws [CallKeysException]). */
    suspend fun frameKeys(conv: String, callId: String): CallKeys

    suspend fun room(conv: String, callId: String, media: String, action: String): RoomOutcome

    /** §20.3 a group `call:signal` (`conversation_id`, `media`). */
    suspend fun signal(conv: String, env: CallEnvelope.Env, media: String): SignalOutcome

    /** §20.4 the durable silent `group_call` `started` (an outbox row that is also my line). */
    suspend fun started(conv: String, env: GroupCallEnvelope)

    /** §20.4 the durable silent `group_call` `ended` (and my line). */
    suspend fun ended(conv: String, env: GroupCallEnvelope)

    /** §20.4 the call's room is gone: its `started` line turns "… ended". */
    suspend fun over(conv: String, callId: String)

    /** android A4: a 1:1 call (or a cellular call) is going on: a group ring doesn't ring, a group call can't start. */
    fun busy(): Boolean = false
}

/**
 * The group call state machine (§20.3–§20.6, android A2–A9, crypto K3–K10). Pure Kotlin: the SFU,
 * the keys, signalling and clocks come in as ports; timers run on [scope] (virtual time in tests).
 * One call at a time per device; every input is serialised by one mutex, and the long setup steps
 * (room, connect, keys, publish) re-check that their call is still the current one after each step.
 */
class GroupCallMachine(
    private val me: String,
    private val myDevice: String,
    private val scope: CoroutineScope,
    private val sfu: SfuConnector,
    private val port: GroupCallPort,
    private val marks: CallMarks,
    private val now: () -> Long = System::currentTimeMillis,
    private val serverNow: () -> Long = now,
    private val log: (String) -> Unit = {},
    private val newCallId: () -> String = { UUID.randomUUID().toString() },
    private val lingerMs: Long = 2_500,
    private val ringMs: Long = RING_MS,
    private val overlapMs: Long = FrameKeyRing.OVERLAP_MS,
) {
    companion object {
        /** §20.4: the ring validity, and the starter's wait for a first participant. */
        const val RING_MS = 45_000L
        const val FRESH_MS = 45_000L

        /** android A8: a full disconnect longer than this leaves the call ("Lost connection"). */
        const val LOST_MS = 20_000L

        /** Decision 054 for groups: room + connect + keys + publish must finish in this time. */
        const val SETUP_MS = 30_000L

        /** Everyone else left: this device leaves too after this (and sends the `ended`). */
        const val ALONE_MS = 10_000L

        /** §20.6 K3: how often the group's epoch is compared with the installed keys (plus the commit hook). */
        const val EPOCH_POLL_MS = 500L

        /**
         * K6: a track whose cryptor hasn't reported OK this long after it appeared counts as "Can't
         * verify" (a frame that never decrypts may never change the cryptor's state from NEW).
         */
        const val VERIFY_MS = 5_000L

        /** At most one commit catch-up for a missing key index per this interval. */
        const val CATCH_UP_MS = 3_000L
        const val MAX_CALL_MS = 4 * 3_600_000L
        const val SIGNAL_TIMEOUT_MS = 10_000L
    }

    private val lock = Mutex()
    private val _state = MutableStateFlow<CallSnapshot?>(null)

    /** The current group call (or its short "ended" linger), for the UI, Telecom and the service. */
    val state: StateFlow<CallSnapshot?> = _state.asStateFlow()

    private val myIdentity get() = "$me/$myDevice"

    private class GCall(
        val id: String,
        val conv: String,
        /** The starter's user id (me for an outgoing call). */
        val starter: String,
        val outgoing: Boolean,
        val media: String,
        var phase: CallPhase,
    ) {
        var offerDevice: String? = null
        var pendingRing = false
        var session: SfuSession? = null
        var ring: FrameKeyRing? = null
        val keyLock = Mutex()
        var muted = false
        var wantCamera = false
        var cameraOn = false
        var participants: List<SfuParticipant> = emptyList()
        /** Device ms / server ms when this device first saw another participant (the "connected" of §20.4). */
        var connectedAt: Long? = null
        var connectedAtServer: Long? = null
        var everOthers = false
        var reconnecting = false
        var lastCatchUp = 0L
        /** Device ms when each remote identity was first seen with a track (the "never verifies" timer). */
        val trackSince = HashMap<String, Long>()
        val timers = HashMap<String, Job>()
        var ended = false
        var shown = false
        val video get() = media == CallEnvelope.MEDIA_VIDEO
    }

    private var current: GCall? = null

    /** §19.5 the call screen is visible and the phone unlocked (the camera runs only then). */
    @Volatile private var screenVisible = false

    // ---------------------------------------------------------------- queries

    fun currentCallId(): String? = current?.takeIf { !it.ended }?.id

    /** This device is in a group call or ringing for one (busy for 1:1 calls, android A4). */
    fun active(): Boolean = current?.let { !it.ended } == true

    // ---------------------------------------------------------------- user actions

    /**
     * §20.4 step 1, the call or video button (the UI checked `group_calls_ready` and RECORD_AUDIO).
     * [camera] = CAMERA granted for a video call. False = busy.
     */
    suspend fun start(conv: String, video: Boolean = false, camera: Boolean = video): Boolean {
        val c = lock.withLock {
            if (current?.let { !it.ended } == true || port.busy()) {
                publishNotice(conv, CallNotice.IN_ANOTHER_CALL)
                return false
            }
            GCall(newCallId(), conv, me, outgoing = true, media = if (video) CallEnvelope.MEDIA_VIDEO else CallEnvelope.MEDIA_AUDIO, phase = CallPhase.CALLING).also {
                it.wantCamera = video && camera
                current = it
                marks.put(CallMark(it.id, answered = true, at = now()))
                publish(it)
            }
        }
        log("group call ${c.id} start in ${c.conv} (${c.media})")
        scope.launch { setup(c, CallsRoomRequest.START) }
        return true
    }

    /** Answer the ringing group call ([camera]: "Answer" on a video call; false for "Answer without video"). */
    suspend fun answer(callId: String? = null, camera: Boolean = false): Boolean {
        val c = lock.withLock {
            val c = current?.takeIf { !it.ended && it.phase == CallPhase.RINGING_IN && (callId == null || it.id == callId) } ?: return false
            c.pendingRing = false
            c.wantCamera = c.video && camera
            c.phase = CallPhase.ANSWERING
            c.timers.remove("ring")?.cancel()
            marks.put((marks.get(c.id) ?: CallMark(c.id)).copy(answered = true, at = now()))
            publish(c)
            c
        }
        scope.launch { setup(c, CallsRoomRequest.JOIN) }
        return true
    }

    /** §20.4 Join from the chat line (the call rang earlier, or rang while busy). */
    suspend fun join(conv: String, callId: String, media: String, starter: String, camera: Boolean = false): Boolean {
        val c = lock.withLock {
            val cur = current?.takeIf { !it.ended }
            if (cur != null && cur.id == callId && cur.phase == CallPhase.RINGING_IN) return@withLock null
            if (cur != null || port.busy()) {
                publishNotice(conv, CallNotice.IN_ANOTHER_CALL)
                return false
            }
            GCall(callId, conv, starter, outgoing = false, media = media, phase = CallPhase.ANSWERING).also {
                it.wantCamera = it.video && camera
                current = it
                marks.put((marks.get(callId) ?: CallMark(callId)).copy(answered = true, at = now()))
                publish(it)
            }
        } ?: return answer(callId, camera)
        scope.launch { setup(c, CallsRoomRequest.JOIN) }
        return true
    }

    /** Decline while ringing (siblings stop, android A2), or leave the call. Always works (decision 054). */
    suspend fun hangUp(callId: String? = null) {
        val done = withTimeoutOrNull(2_000) {
            lock.withLock {
                val c = current?.takeIf { !it.ended && (callId == null || it.id == callId) } ?: return@withLock
                if (c.phase == CallPhase.RINGING_IN) decline(c) else leave(c, CallNotice.CALL_ENDED)
            }
        }
        if (done == null) {
            // The machine is stuck in a step: end the call anyway (decision 054).
            current?.takeIf { !it.ended && (callId == null || it.id == callId) }?.let { finish(it, CallNotice.CALL_ENDED) }
        }
    }

    suspend fun setMuted(muted: Boolean) = lock.withLock {
        val c = current?.takeIf { !it.ended } ?: return@withLock
        c.muted = muted
        c.session?.setMicMuted(muted)
        publish(c)
    }

    /** §19.5 the camera button of a video call. */
    suspend fun setCameraWanted(on: Boolean) {
        val c = lock.withLock { current?.takeIf { !it.ended && it.video }?.also { it.wantCamera = on; publish(it) } } ?: return
        applyCamera(c)
    }

    fun switchCamera() {
        current?.takeIf { !it.ended && it.cameraOn }?.session?.switchCamera()
    }

    suspend fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        current?.takeIf { !it.ended && it.video }?.let { applyCamera(it) }
    }

    // ---------------------------------------------------------------- inbound (§20.3)

    /** One decrypted, validated and bound group call envelope (in event order). */
    suspend fun onSignal(s: InboundCall) {
        val env = s.env
        val markedEnded = marks.get(env.callId)?.ended == true
        lock.withLock {
            val own = s.fromUser.equals(me, true)
            if (own && s.fromDevice.equals(myDevice, true)) return
            when (env) {
                is CallEnvelope.SfuOffer -> onOffer(s, env, own, markedEnded)
                is CallEnvelope.Member -> onMember(s, env, own)
                is CallEnvelope.Cancel -> onCancel(s)
                else -> log("${env.type} in a group: dropped")
            }
        }
    }

    /** The batch that carried offers is applied: ring for those still incoming (android R3). */
    suspend fun onPageEnd() {
        lock.withLock {
            val c = current?.takeIf { !it.ended && it.pendingRing && it.phase == CallPhase.RINGING_IN } ?: return
            c.pendingRing = false
            publish(c)
        }
    }

    /** §20.4 a durable `group_call` arrived: an `ended` stops a ring for that call. */
    suspend fun onGroupCallLine(conv: String, from: String, env: GroupCallEnvelope) {
        if (env.state != GroupCallEnvelope.ENDED) return
        lock.withLock {
            val c = current?.takeIf { !it.ended && it.id == env.callId && it.conv == conv && it.phase == CallPhase.RINGING_IN } ?: return
            log("group call ${c.id}: ended while ringing")
            finish(c, null)
        }
    }

    /** §20.6 K3: a commit was merged in [conv]: rekey now (the poll is the backstop). */
    fun onGroupChanged(conv: String) {
        val c = current?.takeIf { !it.ended && it.conv == conv && it.ring != null } ?: return
        scope.launch { rekey(c) }
    }

    private suspend fun onOffer(s: InboundCall, env: CallEnvelope.SfuOffer, own: Boolean, markedEnded: Boolean) {
        if (own) {
            log("group offer ${env.callId}: from my other device: not ringing")
            return
        }
        if (marks.get(env.callId) != null || markedEnded) {
            log("group offer ${env.callId}: already seen")
            return
        }
        val sn = serverNow()
        val sentAt = runCatching { Instant.parse(env.sentAt).toEpochMilli() }.getOrNull() ?: return
        if (sn - s.serverTsMs >= FRESH_MS || kotlin.math.abs(sn - sentAt) >= FRESH_MS) {
            log("group offer ${env.callId}: stale")
            return
        }
        // android A4: one call at a time; no call_busy in groups (the chat line keeps Join).
        if (current?.let { !it.ended } == true || port.busy()) {
            log("group offer ${env.callId}: busy, not ringing")
            marks.put(CallMark(env.callId, ended = true, at = now()))
            return
        }
        val c = GCall(env.callId, s.conversationId, s.fromUser.lowercase(), outgoing = false, media = env.media, phase = CallPhase.RINGING_IN)
        c.offerDevice = s.fromDevice.lowercase()
        current = c
        marks.put(CallMark(env.callId, rang = true, at = now()))
        timer(c, "ring", (s.serverTsMs + ringMs - sn).coerceAtLeast(0)) { x ->
            log("group ring over for ${x.id}")
            finish(x, null)
        }
        if (s.inPage) c.pendingRing = true else publish(c)
        log("group offer ${env.callId} from ${s.fromUser}: ringing")
    }

    private fun onMember(s: InboundCall, env: CallEnvelope.Member, own: Boolean) {
        val c = current?.takeIf { !it.ended && it.id == env.callId } ?: return
        // android A2: my other device answered or declined: this one stops ringing.
        if (own && c.phase == CallPhase.RINGING_IN) {
            log("group call ${c.id}: ${env.state} on my other device")
            finish(c, if (env.state == CallEnvelope.MEMBER_JOINED) CallNotice.ANSWERED_ELSEWHERE else null)
        }
    }

    private fun onCancel(s: InboundCall) {
        val c = current?.takeIf { !it.ended && it.id == s.env.callId && it.phase == CallPhase.RINGING_IN } ?: return
        // §20.3 sender pinning: only the offer's device cancels.
        if (!s.fromDevice.equals(c.offerDevice, true)) return log("call_cancel for ${c.id} from another device: dropped")
        finish(c, null)
    }

    // ---------------------------------------------------------------- setup (§20.4 steps 1–2)

    private suspend fun setup(c: GCall, action: String) {
        timer(c, "setup", SETUP_MS) { x ->
            log("group call ${x.id}: setup timed out")
            leave(x, CallNotice.CANT_CONNECT)
        }
        val servers = runCatching { withTimeoutOrNull(3_000) { port.iceServers() } }.getOrNull().orEmpty()
        runCatching { port.catchUp(c.conv) }
        if (c.ended) return
        val reply = when (val r = runCatching { port.room(c.conv, c.id, c.media, action) }.getOrElse { RoomOutcome.Failed(it.message ?: "error") }) {
            is RoomOutcome.Ok -> r.reply
            RoomOutcome.Ended -> {
                runCatching { port.over(c.conv, c.id) }
                return locked(c) { finish(it, CallNotice.ROOM_ENDED) }
            }
            RoomOutcome.Full -> return locked(c) { finish(it, CallNotice.CALL_FULL) }
            is RoomOutcome.Failed -> {
                log("group call ${c.id}: rooms $action failed: ${r.reason}")
                return locked(c) { finish(it, CallNotice.CANT_CONNECT) }
            }
        }
        // K7: the token's identity must be this device's leaf identity (the keys are bound to it).
        if (!reply.identity.equals(myIdentity, true)) {
            log("group call ${c.id}: token identity is not this device")
            return locked(c) { finish(it, CallNotice.CANT_CONNECT) }
        }
        val session = try {
            sfu.connect(SfuConnect(reply.url, reply.token, servers, c.video), listener(c))
        } catch (e: Exception) {
            log("group call ${c.id}: connect failed: ${e.javaClass.simpleName}: ${e.message}")
            return locked(c) { finish(it, CallNotice.CANT_CONNECT) }
        }
        val keep = lock.withLock {
            if (c.ended || current !== c) false else {
                c.session = session
                c.ring = FrameKeyRing(session.keys, scope, overlapMs, log = log)
                true
            }
        }
        if (!keep) return session.disconnect()
        // K4: keys of the newest epoch before anything is published.
        if (!rekey(c)) return
        if (!session.publishMic()) {
            log("group call ${c.id}: microphone not published")
            return locked(c) { leave(it, CallNotice.CANT_CONNECT) }
        }
        session.setMicMuted(c.muted)
        applyCamera(c)
        lock.withLock {
            if (c.ended || current !== c) return
            c.timers.remove("setup")?.cancel()
            c.phase = when {
                c.everOthers -> CallPhase.ACTIVE
                c.outgoing -> CallPhase.RINGING_OUT
                else -> CallPhase.ACTIVE
            }
            publish(c)
            timer(c, "max", MAX_CALL_MS) { x -> leave(x, CallNotice.CALL_ENDED) }
            c.timers["epoch"] = scope.launch {
                while (!c.ended) {
                    delay(EPOCH_POLL_MS)
                    rekey(c)
                }
            }
        }
        if (c.outgoing) {
            runCatching { port.started(c.conv, GroupCallEnvelope.started(c.id, c.media)) }
            when (val r = sig(c.conv, CallEnvelope.SfuOffer(c.id, c.media, CallStateMachine.iso(serverNow())), c.media)) {
                is SignalOutcome.Refused -> {
                    log("group call ${c.id}: ring refused: ${r.reason}")
                    if (r.reason == lk.codegen.risime.net.CallErrors.CALLS_NOT_READY) return locked(c) { leave(it, CallNotice.GROUP_NOT_READY) }
                    if (r.reason == lk.codegen.risime.net.AuthErrors.RATE_LIMITED) return locked(c) { leave(it, CallNotice.RATE_LIMITED) }
                }
                else -> Unit
            }
            timer(c, "noanswer", ringMs) { x ->
                if (!x.everOthers) {
                    log("group call ${x.id}: nobody joined in ${ringMs} ms")
                    leave(x, CallNotice.NO_ANSWER)
                }
            }
        } else {
            sig(c.conv, CallEnvelope.Member(c.id, CallEnvelope.MEMBER_JOINED), c.media)
            // Joined a room everyone else has already left: don't sit there alone.
            timer(c, "empty", 2 * ALONE_MS) { x ->
                if (!x.everOthers) {
                    log("group call ${x.id}: nobody else in the room")
                    leave(x, CallNotice.CALL_ENDED)
                }
            }
        }
    }

    /**
     * §20.6 K3/K4/K5: derive the current epoch's keys and install them (no-op when installed). A
     * device without the group (removed) leaves at once. @return false when the call ended.
     */
    private suspend fun rekey(c: GCall): Boolean = c.keyLock.withLock {
        val ring = c.ring ?: return@withLock !c.ended
        if (c.ended) return@withLock false
        val epoch = runCatching { port.epoch(c.conv) }.getOrNull()
        if (epoch == null) {
            log("group call ${c.id}: no group state (removed)")
            locked(c) { leave(it, CallNotice.REMOVED) }
            return@withLock false
        }
        if (ring.epoch == epoch) return@withLock true
        val keys = try {
            port.frameKeys(c.conv, c.id)
        } catch (e: CallKeysException) {
            log("group call ${c.id}: frame keys: ${e.kind}")
            val removed = e.kind == CallKeysException.Kind.RemovedFromGroup || e.kind == CallKeysException.Kind.UnknownGroup
            locked(c) { leave(it, if (removed) CallNotice.REMOVED else CallNotice.CANT_CONNECT) }
            return@withLock false
        }
        if (c.ended) {
            keys.wipe()
            return@withLock false
        }
        if (ring.install(keys)) lock.withLock { if (!c.ended) refresh(c) }
        true
    }

    private fun listener(c: GCall) = object : SfuListener {
        override fun onParticipants(list: List<SfuParticipant>) {
            scope.launch {
                lock.withLock {
                    if (c.ended || current !== c) return@withLock
                    c.participants = list
                    refresh(c)
                }
            }
        }

        override fun onConnection(state: SfuConnection) {
            scope.launch {
                lock.withLock {
                    if (c.ended || current !== c) return@withLock
                    when (state) {
                        SfuConnection.RECONNECTING -> {
                            c.reconnecting = true
                            timer(c, "lost", LOST_MS) { x -> leave(x, CallNotice.LOST_CONNECTION, announce = false) }
                        }
                        SfuConnection.CONNECTED -> {
                            c.reconnecting = false
                            c.timers.remove("lost")?.cancel()
                        }
                        // The SFU closed the room for us (removed by the server, the room is gone): over.
                        SfuConnection.DISCONNECTED -> {
                            log("group call ${c.id}: disconnected by the SFU")
                            leave(c, CallNotice.CALL_ENDED, announce = false)
                            return@withLock
                        }
                    }
                    refresh(c)
                }
            }
        }
    }

    /** Under the lock: roster rules (K6, K7), "connected", alone, catch-up on a missing key, then publish. */
    private fun refresh(c: GCall) {
        val session = c.session
        val roster = c.ring?.roster.orEmpty()
        val others = c.participants.filter { !it.local }
        // Before the first keys are installed there is no roster yet: nobody is judged (no flapping subscriptions).
        if (c.ring?.epoch != null) for (p in others) {
            val member = p.identity in roster
            // K7 here; K6 (a track that says it isn't encrypted) is enforced per track by the session and the UI.
            session?.setPlayable(p.identity, member)
        }
        if (others.isNotEmpty()) {
            if (!c.everOthers) {
                c.everOthers = true
                c.connectedAt = now()
                c.connectedAtServer = serverNow()
                c.timers.remove("noanswer")?.cancel()
            }
            c.timers.remove("alone")?.cancel()
            if (c.phase == CallPhase.RINGING_OUT) c.phase = CallPhase.ACTIVE
        } else if (c.everOthers && c.phase == CallPhase.ACTIVE && c.timers["alone"] == null) {
            timer(c, "alone", ALONE_MS) { x ->
                if (x.participants.none { !it.local }) {
                    log("group call ${x.id}: everyone else left")
                    leave(x, CallNotice.CALL_ENDED)
                }
            }
        }
        val t = now()
        c.trackSince.keys.retainAll(others.filter { it.hasAudio || it.hasVideo }.map { it.identity }.toSet())
        for (p in others) if ((p.hasAudio || p.hasVideo) && p.identity !in c.trackSince) c.trackSince[p.identity] = t
        // §20.6: a frame whose key index has no key makes the device catch up commits.
        val missing = others.any { it.identity in roster && failing(c, it, t) }
        if (missing && now() - c.lastCatchUp >= CATCH_UP_MS && c.ring != null) {
            c.lastCatchUp = now()
            scope.launch {
                runCatching { port.catchUp(c.conv) }
                rekey(c)
            }
        }
        publish(c)
    }

    /** K6: this participant's frames don't decrypt (an error state, or never OK within [VERIFY_MS]). */
    private fun failing(c: GCall, p: SfuParticipant, t: Long): Boolean = when (p.cryptor) {
        CryptorState.MISSING_KEY, CryptorState.FAILED -> true
        CryptorState.NEW, CryptorState.NONE -> (p.hasAudio || p.hasVideo) && c.trackSince[p.identity]?.let { t - it >= VERIFY_MS } == true
        CryptorState.OK -> false
    }

    /** §19.5 for group video: the camera only while wanted, the screen visible and connected. */
    private suspend fun applyCamera(c: GCall) {
        if (!c.video) return
        val session = c.session ?: return
        val want = c.wantCamera && screenVisible && !c.ended
        if (want == c.cameraOn) return
        val ok = runCatching { session.setCamera(want) }.getOrDefault(false)
        lock.withLock {
            c.cameraOn = want && ok
            publish(c)
        }
    }

    // ---------------------------------------------------------------- ending (§20.4 step 4)

    /** Decline: `call_member` `declined` (my siblings stop); nothing else is sent (android A2). */
    private fun decline(c: GCall) {
        scope.launch { sig(c.conv, CallEnvelope.Member(c.id, CallEnvelope.MEMBER_DECLINED), c.media) }
        finish(c, null)
    }

    /**
     * Leave = disconnect. A device that sees no other participant sends the durable `ended`; the
     * starter whom nobody joined also sends `call_cancel` (`ended`) so phones stop ringing.
     */
    private fun leave(c: GCall, notice: CallNotice?, announce: Boolean = true) {
        if (c.ended) return
        // Only a device that really sees the room may claim the call is over (not after the SFU dropped it).
        val connected = c.session != null && announce
        val alone = c.participants.none { !it.local }
        if (connected && alone) {
            val env = if (c.outgoing && !c.everOthers) {
                scope.launch { sig(c.conv, CallEnvelope.Cancel(c.id, CallEnvelope.CANCEL_ENDED), c.media) }
                GroupCallEnvelope.ended(c.id, c.media, GroupCallEnvelope.R_TIMEOUT, null, null)
            } else {
                GroupCallEnvelope.ended(
                    c.id, c.media, GroupCallEnvelope.R_HANGUP,
                    c.connectedAtServer?.let(CallStateMachine::iso),
                    c.connectedAt?.let { ((now() - it) / 1000).coerceAtLeast(0) },
                )
            }
            scope.launch { runCatching { port.ended(c.conv, env) } }
        }
        finish(c, notice)
    }

    /** Ends [c]: timers, keys (K10), the room; then the short ended state (no notice = disappear at once). */
    private fun finish(c: GCall, notice: CallNotice?) {
        if (c.ended) return
        c.ended = true
        c.timers.values.forEach { it.cancel() }
        c.timers.clear()
        // K10: keys are overwritten before the room (and its key provider) goes.
        runCatching { c.ring?.wipeAll() }
        runCatching { c.session?.disconnect() }
        c.session = null
        c.cameraOn = false
        scope.launch { marks.put((marks.get(c.id) ?: CallMark(c.id)).copy(ended = true, at = now())) }
        if (current === c) current = null
        if (notice == null || !c.shown) {
            if (_state.value?.callId == c.id) _state.value = null
            return
        }
        _state.value = snapshot(c).copy(phase = CallPhase.ENDED, notice = notice, cameraOn = false)
        scope.launch {
            delay(lingerMs)
            if (_state.value?.callId == c.id && _state.value?.phase == CallPhase.ENDED) _state.value = null
        }
    }

    private fun publishNotice(conv: String, notice: CallNotice) {
        val id = "notice-" + newCallId()
        _state.value = CallSnapshot(id, conv, me, true, CallPhase.ENDED, notice = notice, group = true)
        scope.launch {
            delay(lingerMs)
            if (_state.value?.callId == id) _state.value = null
        }
    }

    private fun snapshot(c: GCall): CallSnapshot {
        val roster = c.ring?.roster.orEmpty()
        val members = c.participants.sortedBy { !it.local }.map { p ->
            val member = p.local || c.ring?.epoch == null || p.identity in roster
            val tracks = p.hasAudio || p.hasVideo
            GroupMember(
                identity = p.identity,
                userId = p.identity.substringBefore('/'),
                local = p.local,
                member = member,
                speaking = p.speaking && member,
                muted = p.audioMuted || !p.hasAudio,
                verified = tracks && p.encrypted && p.cryptor == CryptorState.OK,
                cantVerify = !p.local && tracks && (!p.encrypted || failing(c, p, now())),
                hasVideo = p.hasVideo && !p.videoMuted && member && p.encrypted,
            )
        }
        // K9: the badge shows while every rendered participant decrypts with an MLS-derived key.
        val rendered = members.filter { m -> !m.local && m.member && c.participants.any { it.identity == m.identity && (it.hasAudio || it.hasVideo) } }
        val badge = rendered.isNotEmpty() && rendered.all { it.verified }
        return CallSnapshot(
            c.id, c.conv, c.starter, c.outgoing,
            if (c.reconnecting && c.phase == CallPhase.ACTIVE) CallPhase.RECONNECTING else c.phase,
            c.muted, c.connectedAt, badge, null,
            video = c.video, cameraOn = c.cameraOn, wantCamera = c.wantCamera,
            group = true, members = members,
        )
    }

    private fun publish(c: GCall) {
        if (c.ended || c.pendingRing) return
        c.shown = true
        _state.value = snapshot(c)
    }

    private suspend fun sig(conv: String, env: CallEnvelope.Env, media: String): SignalOutcome =
        withTimeoutOrNull(SIGNAL_TIMEOUT_MS) { runCatching { port.signal(conv, env, media) }.getOrElse { SignalOutcome.Unavailable } }
            ?: SignalOutcome.Unavailable.also { log("${env.type} timed out") }

    private suspend fun locked(c: GCall, block: (GCall) -> Unit) = lock.withLock { if (!c.ended && current === c) block(c) }

    private fun timer(c: GCall, name: String, ms: Long, fire: suspend (GCall) -> Unit) {
        c.timers.remove(name)?.cancel()
        c.timers[name] = scope.launch {
            delay(ms)
            lock.withLock {
                if (!c.ended && current === c) {
                    c.timers.remove(name)
                    fire(c)
                }
            }
        }
    }
}
