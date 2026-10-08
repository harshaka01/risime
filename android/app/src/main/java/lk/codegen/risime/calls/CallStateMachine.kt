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
import lk.codegen.risime.net.dmPeer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** What a `call:signal` push came back with. */
sealed interface SignalOutcome {
    data object Ok : SignalOutcome

    data class Refused(val reason: String) : SignalOutcome

    data object Unavailable : SignalOutcome
}

/** Outgoing call traffic (the app sends it through the conversation's encrypt-and-push lane). */
interface CallSignals {
    /** An ephemeral envelope with `call:signal` (`ring` = [CallEnvelope.ringFor]); [media] = the call's (§19.2). */
    suspend fun signal(conversationId: String, peer: String, env: CallEnvelope.Env, media: String = CallEnvelope.MEDIA_AUDIO): SignalOutcome

    /** The durable `call_end` (§16.2): an outbox row that is also this device's call-history line. */
    suspend fun end(conversationId: String, peer: String, env: CallEnvelope.End)

    /**
     * This device rang for [callId] and the ring ran out without an answer, and no durable
     * `call_end` arrived within [CallStateMachine.MISSED_GRACE_MS] (the caller crashed or was
     * killed mid-ring): record "Missed voice call" locally (one line per call id; a later durable
     * `call_end` is deduplicated against it) and notify.
     */
    suspend fun missed(conversationId: String, peer: String, callId: String, video: Boolean = false) = Unit
}

/** Per-device memory of call ids (§16.3 dedupe, 24 h, persisted) and how this device took part. */
data class CallMark(val callId: String, val rang: Boolean = false, val answered: Boolean = false, val ended: Boolean = false, val at: Long = 0)

interface CallMarks {
    suspend fun get(callId: String): CallMark?

    suspend fun put(mark: CallMark)
}

/** Platform facts the machine asks for. */
interface CallEnvironment {
    /**
     * §16.5 (android R9, decision 054): a real cellular call is active (`AudioManager.mode` is
     * `MODE_IN_CALL`). `MODE_IN_COMMUNICATION` alone is NOT busy: a stale mode or a ghost Telecom
     * call left by an earlier attempt kept every later call "busy" (the nightly.16 P0).
     */
    fun audioBusy(): Boolean = false

    /** §20.4 (android A4): this device is in (or ringing for) a group call: a 1:1 offer is busy, a 1:1 call can't start. */
    fun otherCallActive(): Boolean = false

    /** §16.7: TURN credentials, at most 3 s (then STUN only: an empty list or STUN-only servers). */
    suspend fun iceServers(): List<IceServer> = emptyList()
}

/** One decrypted, bound (§16.3) call envelope with its event's routing facts. */
data class InboundCall(
    val conversationId: String,
    val fromUser: String,
    val fromDevice: String,
    /** The event's `server_ts` (ms). */
    val serverTsMs: Long,
    val env: CallEnvelope.Env,
    /** Part of a join/sync page: an offer rings only after the whole page is applied (§16.3, android R3). */
    val inPage: Boolean = false,
    /** §19.2 the event's cleartext media (absent = audio), bound to the call's (crypto K6). */
    val media: String = CallEnvelope.MEDIA_AUDIO,
)

enum class CallPhase { CALLING, RINGING_OUT, RINGING_IN, ANSWERING, CONNECTING, ACTIVE, RECONNECTING, ENDED }

/** Why a call ended, for the short line the UI shows (names are filled in by the UI). */
enum class CallNotice {
    CALL_ENDED, DECLINED, BUSY, NO_ANSWER, ANSWERED_ELSEWHERE, CANT_CONNECT, NOT_READY, NOT_E2EE, RATE_LIMITED, NOT_FRIENDS, IN_ANOTHER_CALL,
    /** §19.2 `video_not_ready`: the peer has no device that takes video calls. */
    VIDEO_NOT_READY,

    /** §20.2 `404 call_ended`: the group call's room is gone. */
    ROOM_ENDED,

    /** §20.2 `409 call_full`. */
    CALL_FULL,

    /** §20.4 (android A8): disconnected from the SFU for more than 20 s. */
    LOST_CONNECTION,

    /** §20.6 K5: this device was removed from the group (no keys for the new epoch). */
    REMOVED,

    /** §20.3 `calls_not_ready` in a group: nobody else can join group calls yet. */
    GROUP_NOT_READY,
}

/** §20.5 one participant of a group call as the call screen shows it (named from the app's database, android A7). */
data class GroupMember(
    val identity: String,
    val userId: String,
    val local: Boolean,
    /** K7: a leaf of the group at the current epoch (otherwise "Not a member", never played). */
    val member: Boolean,
    val speaking: Boolean = false,
    val muted: Boolean = false,
    /** K6/K9: every track of this participant decrypts with an MLS-derived key (frame cryptor OK). */
    val verified: Boolean = false,
    /** K6: a track that doesn't decrypt (or says it isn't encrypted): "Can't verify", never played. */
    val cantVerify: Boolean = false,
    val hasVideo: Boolean = false,
)

data class CallSnapshot(
    val callId: String,
    val conversationId: String,
    val peerUserId: String,
    val outgoing: Boolean,
    val phase: CallPhase,
    val muted: Boolean = false,
    /** Device ms when the media connected and passed the §16.10 check. */
    val connectedAtMs: Long? = null,
    /** §16.10 (e) passed: the UI may say "End-to-end encrypted". */
    val verified: Boolean = false,
    val notice: CallNotice? = null,
    /** §19: a video call (fixed when it starts; false again if the peer answered audio-only). */
    val video: Boolean = false,
    /** §19.5 the local camera is running now (wanted, the call screen visible, a session). */
    val cameraOn: Boolean = false,
    /** The user's camera choice (Answer / Answer without video / the camera button). */
    val wantCamera: Boolean = false,
    /** The peer's last `call_media` (true until one says otherwise), and when it changed (device ms). */
    val peerCamera: Boolean = true,
    val peerCameraAtMs: Long = 0,
    /** §20: a group call (conversationId is the `grp:`; [peerUserId] is the starter). */
    val group: Boolean = false,
    /** §20.5 the room's participants (this device first), named by the UI. */
    val members: List<GroupMember> = emptyList(),
    /** §23.1: both selected devices listed `switch`: the Video (voice mode) and Voice (video mode) buttons work. */
    val canSwitch: Boolean = false,
    /** §23.1: both listed `screen`: Share screen works. */
    val canShare: Boolean = false,
    /** §23.2 my pending request's source ("Asking … to switch to video…" with Cancel), null = none. */
    val asking: String? = null,
    /** §23.2 the peer's pending request's source (the prompt), null = none. */
    val prompt: String? = null,
    /** §23.2 the short line after a switch attempt ("No answer", "… declined video", "Couldn't switch to video"). */
    val switchNotice: SwitchNotice? = null,
    /** §23.2 step 3: after a decline the Video button waits until this device time (ms). */
    val videoBlockedUntilMs: Long = 0,
    /** §23.5 this device shares its screen now. */
    val sharing: Boolean = false,
    /** §23.4 the peer's last `call_media` says `screen` ("<name> is sharing their screen"). */
    val peerSharing: Boolean = false,
    /** §23.8 the call was in video mode at some point (its `call_end.media` is "video"). */
    val everVideo: Boolean = false,
)

/** §23.2 what a switch attempt ended with (the UI names the peer). */
enum class SwitchNotice { NO_ANSWER, DECLINED, FAILED, SHARE_FAILED }

/**
 * The 1:1 call state machine (§16.3–§16.5, android R2–R4, R9; crypto R3–R6). Pure Kotlin: media,
 * signalling, clocks and the dedupe memory come in as ports; timers run on [scope] (virtual time in
 * tests). One call at a time per device; every input is serialised by one mutex.
 */
class CallStateMachine(
    private val me: String,
    private val myDevice: String,
    private val scope: CoroutineScope,
    private val media: CallMedia,
    private val signals: CallSignals,
    private val marks: CallMarks,
    private val platform: CallEnvironment = object : CallEnvironment {},
    /** Device clock (ms). */
    private val now: () -> Long = System::currentTimeMillis,
    /** §16.3: device_now + offset (the server-corrected clock, §15.7). */
    private val serverNow: () -> Long = now,
    private val log: (String) -> Unit = {},
    private val newCallId: () -> String = { UUID.randomUUID().toString() },
    /** Debug-only negative test (§16.10 f): flip a byte of the remote answer's fingerprint before applying it. */
    private val tamperRemoteFingerprint: () -> Boolean = { false },
    private val lingerMs: Long = 2_500,
    /** The ring timeout (caller) and ring validity (callee), §16.4; shorter only in live tests. */
    private val ringMs: Long = RING_MS,
    /** §23.1 what this device can do in a call (`switch`, `screen`), sent in `features`. */
    private val features: () -> List<String> = { emptyList() },
) {
    companion object {
        const val RING_MS = 45_000L
        const val FRESH_MS = 45_000L
        const val ACCEPT_WAIT_MS = 10_000L
        const val CONNECT_MS = 20_000L
        const val RECONNECT_MS = 15_000L
        const val MAX_CALL_MS = 4 * 3_600_000L
        const val ICE_BATCH_MS = 250L
        const val DEDUPE_MS = 24 * 3_600_000L
        const val SIBLING_MS = 45_000L
        const val STATS_POLL_MS = 250L

        /** §19.3: at most one `call_media` per second per call. */
        const val CAMERA_SIGNAL_MS = 1_000L

        /** Decision 054: a call that has no verified media this long after it started is torn down (never stuck). */
        const val MEDIA_WATCHDOG_MS = 80_000L
        /** Decision 054: a `call:signal` that gets no result in this time counts as Unavailable. */
        const val SIGNAL_TIMEOUT_MS = 10_000L
        /** Decision 054: one WebRTC operation (create/set description) may hold the machine this long at most. */
        const val MEDIA_OP_MS = 10_000L
        /** Decision 054: without a relay (TURN 503) a direct path connects within seconds or never. */
        const val DIRECT_CONNECT_MS = 10_000L
        /** Decision 054: after the ring ran out, how long the callee waits for the caller's `call_end`. */
        const val MISSED_GRACE_MS = 10_000L
        /** Decision 054: Hang up / Cancel waits this long for the machine, then ends the call anyway. */
        const val HANGUP_LOCK_MS = 2_000L
        /** Decision 054: audio that flowed and then stopped this long ends an active call ("Can't connect the call"). */
        const val MEDIA_STALL_MS = 20_000L
        const val MEDIA_STALL_POLL_MS = 2_000L

        /** §23.2 step 3: a request without an answer is cancelled after this. */
        const val SWITCH_ASK_MS = 20_000L
        /** §23.2 step 3: after a decline the Video button waits this long. */
        const val SWITCH_COOLDOWN_MS = 10_000L
        /** §23.3: the re-offer gets its answer within this, or the caller rolls back. */
        const val RENEGOTIATE_MS = 10_000L
        /** §23.3 a re-offer the server took: the caller waits this much longer (the reconnect bound) before rolling back. */
        const val RENEGOTIATE_DELIVERED_MS = RECONNECT_MS
        /** A `getStats` gets this long (decision 054's operation bound). */
        const val STATS_TIMEOUT_MS = MEDIA_OP_MS
        /** The media watch logs (once) when received audio stopped advancing this long (debug aid, before the 20-s end). */
        const val STALL_LOG_MS = 6_000L
        /** The callee's wait for the caller's re-offer after an accept (the caller's 10 s plus delivery). */
        const val RENEGOTIATE_WAIT_MS = 15_000L
        /** How long a switch notice line stays. */
        const val SWITCH_NOTICE_MS = 4_000L

        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun iso(ms: Long): String = ISO.format(Instant.ofEpochMilli(ms))
    }

    private val lock = Mutex()
    private val _state = MutableStateFlow<CallSnapshot?>(null)

    /** The current call (or its short "ended" linger), for the UI, Telecom and the service. */
    val state: StateFlow<CallSnapshot?> = _state.asStateFlow()

    private class Call(
        val id: String,
        val conv: String,
        val peer: String,
        val outgoing: Boolean,
        var phase: CallPhase,
    ) {
        /** Incoming: the offer's device C (crypto R3 pinning). */
        var offerDevice: String? = null
        var offerSdp: String? = null
        var offerServerTsMs: Long = 0
        /** Outgoing: the callee device named in `call_accepted`. */
        var selected: String? = null
        /** §16.10 (d): the peer device's fingerprint, pinned for the whole call. */
        var pinned: String? = null
        var session: MediaSession? = null
        val remoteBuffer = mutableListOf<CallEnvelope.Candidate>()
        var remoteSet = false
        var siblingAnswered = false
        var pendingRing = false
        var offerSent = false
        /** A TURN relay was available for this call (otherwise only direct paths: shorter connect timeout). */
        var relay = false
        var connectedAt: Long? = null
        var connectedAtServer: Long? = null
        var verified = false
        var muted = false
        val timers = HashMap<String, Job>()
        val localBuffer = mutableListOf<CallEnvelope.Candidate>()
        var gatheringDone = false
        var doneSent = false
        var iceJob: Job? = null
        var checking: Job? = null
        var ended = false
        /** Published to the UI at least once (a call that never showed ends without a notice). */
        var shown = false
        /** §19: the call's media (fixed by the offer); [video] goes false if the answer rejected video. */
        var media: String = CallEnvelope.MEDIA_AUDIO
        var video = false
        var wantCamera = false
        var cameraOn = false
        var peerCamera = true
        var peerCameraAt = 0L
        /** §19.3/§23.4 `call_media`: the last video state sent and when (at most one per second, the last state wins). */
        var cameraSent: String? = null
        var cameraSentAt = 0L
        var cameraJob: Job? = null

        // ---- §23 switching and screen sharing ----
        /** §23.1 the selected peer device's `features` (from its offer or answer), and mine (fixed per call). */
        var peerFeatures: Set<String> = emptySet()
        var myFeatures: Set<String> = emptySet()
        /** The session carries an `m=video` (started as video and accepted, or renegotiated). */
        var videoSection = false
        var renegotiating = false
        var renegotiated = false
        /** Crypto C2: this device's user accepted (or itself asked for) a switch in this call. */
        var consented = false
        var everVideo = false
        /** §23.2 my last `seq` (request/voice) and the last one seen from the peer. */
        var mySeq = 0
        var peerSeq = 0
        /** My pending request (seq, source) and the peer's (the prompt). */
        var asking: Pair<Int, String>? = null
        var prompt: Pair<Int, String>? = null
        var blockedUntil = 0L
        var switchNotice: SwitchNotice? = null
        /** §23.5 the one-use consent held until the share starts (never kept for a later share). */
        var screenGrant: Any? = null
        var screenOn = false
        var cameraBeforeShare = false
        var peerScreen = false
        /** §23.3 each side's first SDP of the call (the renegotiation's fingerprint and `a=setup` are checked against it). */
        var myFirstSdp: String? = null
        var peerFirstSdp: String? = null
        /** §23.3 each side's last applied SDP (an ICE restart updates it): the re-offer keeps the current ICE credentials. */
        var myCurrentSdp: String? = null
        var peerCurrentSdp: String? = null
        /** §23.3 the re-offer reached the server (`call:signal` ok): from then on the callee may apply it, so the caller doesn't roll back at 10 s. */
        var reofferDelivered = false
        /** An ICE restart was due while the re-offer was out (one offer at a time): it runs as soon as the re-offer resolves. */
        var restartPending = false
        /** §23.3 at most one renegotiation per call: after a failed one the call stays voice. */
        var renegotiationFailed = false
        /** The switch, `call_media` and re-offer envelopes go out in the order they were decided. */
        var ordered: kotlinx.coroutines.channels.Channel<Pair<CallEnvelope.Env, (suspend (SignalOutcome) -> Unit)?>>? = null
    }

    /** §19.5 (android A1): the in-call activity is visible and unlocked (the camera runs only then). */
    @Volatile private var screenVisible = false

    private var current: Call? = null

    /** §16.5 sibling rule: my user is calling these peers (another device of mine): peer → (call id, until). */
    private val siblingCalling = HashMap<String, Pair<String, Long>>()

    // ---------------------------------------------------------------- public inputs

    /**
     * The call button (the UI checked `calls_ready`, e2ee and RECORD_AUDIO). False = busy already.
     * [video] (§19.6): a video call; [camera] = CAMERA granted (denied: the call goes on with the camera off).
     */
    suspend fun placeCall(conversationId: String, video: Boolean = false, camera: Boolean = video): Boolean {
        val peer = dmPeer(conversationId, me) ?: return false
        // Calling someone who is ringing this device right now: both want the call, so answer it (as glare would).
        val ringingFromPeer = lock.withLock { current?.takeIf { !it.ended && !it.outgoing && it.phase == CallPhase.RINGING_IN && it.peer.equals(peer, true) }?.id }
        if (ringingFromPeer != null) return answer(ringingFromPeer)
        val call = lock.withLock {
            if (current?.let { !it.ended } == true || platform.audioBusy() || platform.otherCallActive()) {
                publishNotice(conversationId, peer, true, CallNotice.IN_ANOTHER_CALL)
                return false
            }
            Call(newCallId(), conversationId, peer, outgoing = true, phase = CallPhase.CALLING).also {
                if (video) {
                    it.media = CallEnvelope.MEDIA_VIDEO
                    it.video = true
                    it.videoSection = true
                    it.everVideo = true
                    it.wantCamera = camera
                }
                it.myFeatures = runCatching { features().toSet() }.getOrDefault(emptySet())
                current = it
                publish(it)
                // §16.4 ring timeout, armed at once (decision 054): a stuck TURN fetch or signal can't
                // keep "Calling…" forever.
                timer(it, "ring", ringMs) { c ->
                    if (c.offerSent) finish(c, CallNotice.NO_ANSWER, sendEnd = CallEnvelope.R_TIMEOUT) else finish(c, CallNotice.CANT_CONNECT, sendEnd = null)
                }
                watchdog(it)
            }
        }
        marks.put(CallMark(call.id, at = now()))
        // §16.4 step 1: TURN (≤ 3 s, else STUN only), a fresh peer connection, the offer at once (S-a).
        val servers = withTimeoutOrNull(3_000) { runCatching { platform.iceServers() }.getOrDefault(emptyList()) } ?: emptyList()
        val offer = lock.withLock {
            if (current !== call || call.ended) return true
            val s = runCatching { openSession(call, servers) }.getOrElse {
                log("open media failed: ${it.message}")
                finish(call, CallNotice.CANT_CONNECT, sendEnd = null)
                return true
            }
            val sdp = runCatching { mediaOp { s.createOffer() } }.getOrElse {
                log("createOffer failed: ${it.message}")
                finish(call, CallNotice.CANT_CONNECT, sendEnd = null)
                return true
            }
            SdpRules.validate(sdp, SdpRules.Role.OFFER, call.video)?.let {
                log("own offer invalid: $it")
                finish(call, CallNotice.CANT_CONNECT, sendEnd = null)
                return true
            }
            call.myFirstSdp = sdp
            call.myCurrentSdp = sdp
            CallEnvelope.Offer(call.id, sdp, iso(serverNow()), media = call.media, features = call.myFeatures.toList())
        }
        val r = sig(call.conv, call.peer, offer, call.media)
        lock.withLock {
            if (current !== call || call.ended) return true
            when (r) {
                SignalOutcome.Ok -> {
                    call.offerSent = true
                    // §16.4: the ring timeout was armed when the call started (at most 3 s before the offer).
                    scheduleIceFlush(call)
                }
                is SignalOutcome.Refused -> finish(
                    call,
                    when (r.reason) {
                        lk.codegen.risime.net.CallErrors.CALLS_NOT_READY -> CallNotice.NOT_READY
                        lk.codegen.risime.net.CallErrors.VIDEO_NOT_READY -> CallNotice.VIDEO_NOT_READY
                        lk.codegen.risime.net.AuthErrors.NOT_E2EE -> CallNotice.NOT_E2EE
                        lk.codegen.risime.net.AuthErrors.RATE_LIMITED -> CallNotice.RATE_LIMITED
                        lk.codegen.risime.net.AuthErrors.NOT_FRIENDS -> CallNotice.NOT_FRIENDS
                        else -> CallNotice.CANT_CONNECT
                    },
                    sendEnd = null, // never rang anywhere
                )
                SignalOutcome.Unavailable -> finish(call, CallNotice.CANT_CONNECT, sendEnd = null)
            }
        }
        return true
    }

    /**
     * Answer (an activity, after RECORD_AUDIO): only for a call ringing on this device. [camera]
     * (§19.6, android A3): a human tapped Answer (camera on) or Answer without video; the camera
     * never starts while ringing or by itself. Telecom's answer callback passes false.
     */
    suspend fun answer(callId: String? = null, camera: Boolean = false): Boolean {
        val call = lock.withLock {
            val c = current?.takeIf { !it.ended && it.phase == CallPhase.RINGING_IN && (callId == null || it.id == callId) } ?: return false
            c.wantCamera = c.video && camera
            c.phase = CallPhase.ANSWERING
            c.timers.remove("ring")?.cancel()
            publish(c)
            c
        }
        marks.put((marks.get(call.id) ?: CallMark(call.id)).copy(answered = true, at = now()))
        val servers = withTimeoutOrNull(3_000) { runCatching { platform.iceServers() }.getOrDefault(emptyList()) } ?: emptyList()
        doAnswer(call, servers)
        return true
    }

    private suspend fun doAnswer(call: Call, servers: List<IceServer>) {
        val answer = lock.withLock {
            if (current !== call || call.ended) return
            val sdp = runCatching {
                val s = openSession(call, servers)
                mediaOp { s.setRemote(call.offerSdp!!, isOffer = true) }
                call.remoteSet = true
                call.remoteBuffer.forEach(s::addRemoteCandidate)
                call.remoteBuffer.clear()
                mediaOp { s.createAnswer() }
            }.getOrElse {
                log("answer failed: ${it.message}")
                finish(call, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
                return
            }
            SdpRules.validate(sdp, SdpRules.Role.ANSWER, call.video)?.let {
                log("own answer invalid: $it")
                finish(call, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
                return
            }
            call.myFirstSdp = sdp
            call.myCurrentSdp = sdp
            CallEnvelope.Answer(call.id, call.offerDevice!!, sdp, call.myFeatures.toList())
        }
        val r = sig(call.conv, call.peer, answer, call.media)
        lock.withLock {
            if (current !== call || call.ended) return
            if (r != SignalOutcome.Ok) {
                log("call_answer not sent: $r")
                finish(call, CallNotice.CANT_CONNECT, sendEnd = null)
                return
            }
            call.offerSent = true
            scheduleIceFlush(call)
            // android R4: 10 s for a call_accepted naming this device. Without a sibling's answer the
            // caller never confirmed: a failure the user must see (decision 054).
            timer(call, "accept", ACCEPT_WAIT_MS) { c ->
                finish(c, if (c.siblingAnswered) CallNotice.ANSWERED_ELSEWHERE else CallNotice.CANT_CONNECT, sendEnd = null)
            }
        }
    }

    /**
     * Decline (a ringing call) or hang up (any other phase). Always works (decision 054): if the
     * machine is held up (a stuck media operation), the call is ended anyway after
     * [HANGUP_LOCK_MS]. [callId] = only that call (a Telecom callback of an older call is ignored).
     */
    suspend fun hangUp(callId: String? = null) {
        val done = withTimeoutOrNull(HANGUP_LOCK_MS) {
            lock.withLock { current?.takeIf { !it.ended && (callId == null || it.id == callId) }?.let(::hangUpLocked) }
            true
        }
        if (done == null) {
            log("hang up: machine busy for $HANGUP_LOCK_MS ms, ending the call anyway")
            current?.takeIf { !it.ended && (callId == null || it.id == callId) }?.let(::hangUpLocked)
        }
    }

    private fun hangUpLocked(c: Call) {
        val reason = when {
            c.phase == CallPhase.RINGING_IN -> CallEnvelope.R_DECLINED
            c.connectedAt != null -> CallEnvelope.R_HANGUP
            c.outgoing && !c.offerSent -> null // nothing went out yet
            c.outgoing && c.selected == null -> CallEnvelope.R_CANCELLED
            else -> CallEnvelope.R_HANGUP
        }
        finish(c, CallNotice.CALL_ENDED, sendEnd = reason)
    }

    /**
     * The platform says this call can't go on (decision 054: the socket stayed down mid-call setup):
     * end it visibly ("Can't connect the call"), telling the peer when anything went out.
     */
    suspend fun fail(callId: String? = null) {
        val done = withTimeoutOrNull(HANGUP_LOCK_MS) {
            lock.withLock { current?.takeIf { !it.ended && (callId == null || it.id == callId) }?.let(::failLocked) }
            true
        }
        if (done == null) current?.takeIf { !it.ended && (callId == null || it.id == callId) }?.let(::failLocked)
    }

    private fun failLocked(c: Call) {
        when {
            !c.outgoing && c.phase == CallPhase.RINGING_IN -> finish(c, notice = null, sendEnd = null)
            c.outgoing && !c.offerSent -> finish(c, CallNotice.CANT_CONNECT, sendEnd = null)
            // The callee rang: "Missed voice call" there, "No answer" here.
            c.outgoing && c.selected == null -> finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_CANCELLED)
            else -> finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
        }
    }

    /** The id of the call that exists now (not ended), if any. */
    fun currentCallId(): String? = current?.takeIf { !it.ended }?.id

    suspend fun setMuted(muted: Boolean) {
        lock.withLock {
            val c = current?.takeIf { !it.ended } ?: return
            c.muted = muted
            c.session?.setMuted(muted)
            publish(c)
        }
    }

    /** §19.5 the camera button: the user's choice (it runs only while the call screen is visible). */
    suspend fun setCameraWanted(on: Boolean) {
        lock.withLock {
            val c = current?.takeIf { !it.ended && it.video } ?: return
            c.wantCamera = on
            applyCamera(c)
        }
    }

    /** §19.7 front/back: local only. */
    suspend fun switchCamera() {
        lock.withLock { current?.takeIf { !it.ended && it.cameraOn }?.session?.switchCamera() }
    }

    /**
     * §19.5 (android A1): the in-call activity became visible (resumed, unlocked) or hidden (home,
     * screen off, another app, Back to chats). Hidden stops the camera and sends `camera: false`;
     * visible restarts it if the user had it on. Audio goes on either way.
     */
    suspend fun setScreenVisible(visible: Boolean) {
        lock.withLock {
            screenVisible = visible
            current?.takeIf { !it.ended && it.video }?.let(::applyCamera)
        }
    }

    /** Under the lock: the camera follows (wanted ∧ visible ∧ a session); a connected call tells the peer. */
    private fun applyCamera(c: Call) {
        val session = c.session
        val on = c.video && c.wantCamera && screenVisible && session != null
        if (on != c.cameraOn) {
            c.cameraOn = on
            log("camera ${if (on) "on" else "off"}")
            runCatching { session?.setCamera(on) }.onFailure { log("camera: ${it.message}") }
            log("camera ${if (on) "on" else "off"}: done")
            publish(c)
        }
        if (c.phase == CallPhase.ACTIVE || c.phase == CallPhase.RECONNECTING) scheduleCameraSignal(c)
    }

    /** §23.4 what this device sends now: the screen, the camera or nothing. */
    private fun videoState(c: Call): String = when {
        c.screenOn -> CallEnvelope.VIDEO_SCREEN
        c.cameraOn -> CallEnvelope.VIDEO_CAMERA
        else -> CallEnvelope.VIDEO_OFF
    }

    /** §23.1 the peer listed `switch` (and so do I): `video` goes into `call_media`, the switch buttons work. */
    private fun peerSwitches(c: Call) = CallEnvelope.FEATURE_SWITCH in c.peerFeatures && CallEnvelope.FEATURE_SWITCH in c.myFeatures

    /**
     * §19.3/§23.4 `call_media` to the selected peer device, in video mode only: at most one per
     * second per call, the last state wins; `video` only to a peer that listed `switch`.
     */
    private fun scheduleCameraSignal(c: Call) {
        if (!c.video || c.ended) return
        if (c.cameraJob?.isActive == true) return
        if (c.cameraSent == videoState(c)) return
        c.cameraJob = scope.launch {
            val wait = c.cameraSentAt + CAMERA_SIGNAL_MS - now()
            if (c.cameraSentAt > 0 && wait > 0) delay(wait)
            val env = lock.withLock {
                val st = videoState(c)
                if (c.ended || current !== c || !c.video || c.cameraSent == st) return@launch
                val to = (if (c.outgoing) c.selected else c.offerDevice) ?: return@launch
                c.cameraSent = st
                c.cameraSentAt = now()
                CallEnvelope.Media(c.id, to, st == CallEnvelope.VIDEO_CAMERA, st.takeIf { peerSwitches(c) })
            }
            sig(c.conv, c.peer, env, c.media)
            // A change while this one was going out: send the latest (still at most one per second).
            lock.withLock { if (!c.ended && current === c && c.video && c.cameraSent != videoState(c)) scope.launch { lock.withLock { scheduleCameraSignal(c) } } }
        }
    }

    /** Telecom/the OS ended the call (a cellular call took over, the user ended it from the system UI). */
    suspend fun onSystemDisconnect(callId: String? = null) = hangUp(callId)

    /** Has this device rung for [callId] without answering it (the "Missed" vs "Couldn't connect" line)? */
    suspend fun rangUnanswered(callId: String): Boolean = marks.get(callId)?.let { it.rang && !it.answered } == true

    /** The incoming call this device is ringing for, if any (the locked-answer path, §16.8). */
    fun ringingCallId(): String? = current?.takeIf { !it.ended && it.phase == CallPhase.RINGING_IN && !it.pendingRing }?.id

    // ---------------------------------------------------------------- inbound

    /** One decrypted, validated and bound call envelope (in event order). */
    suspend fun onSignal(s: InboundCall) {
        val env = s.env
        val markedEnded = marks.get(env.callId)?.ended == true
        lock.withLock {
            pruneSiblings()
            val own = s.fromUser.equals(me, true)
            if (own && s.fromDevice.equals(myDevice, true)) return // my own copy
            // §19.2 (crypto K6): every later signal of a call carries the offer's media.
            val known = current?.takeIf { it.id == env.callId }
            if (known != null && env !is CallEnvelope.Offer && s.media != known.media) {
                log("${env.type} for ${env.callId}: media ${s.media} != ${known.media}: dropped")
                return
            }
            when (env) {
                is CallEnvelope.Offer -> when {
                    env.renegotiate -> if (!own) onRenegotiateOffer(s, env)
                    env.restart -> onRestartOffer(s, env)
                    else -> onOffer(s, env, own, markedEnded)
                }
                is CallEnvelope.Switch -> onSwitch(s, env, own)
                is CallEnvelope.Ringing -> onRinging(s, own)
                is CallEnvelope.Answer -> onAnswer(s, env, own)
                is CallEnvelope.Accepted -> onAccepted(s, env, own)
                is CallEnvelope.Ice -> onIce(s, env, own)
                is CallEnvelope.Busy -> onBusy(s, own)
                is CallEnvelope.Cancel -> onCancel(s, own)
                is CallEnvelope.Media -> onMedia(s, env, own)
                is CallEnvelope.End -> log("call_end in a call_signal event: dropped")
                // §20.3: group call envelopes go to the group call machine (the pipeline never routes them here).
                is CallEnvelope.SfuOffer, is CallEnvelope.Member -> log("${env.type} in a DM: dropped")
            }
        }
    }

    /** A durable `call_end` (from a message event, either user, any device; display only). */
    suspend fun onCallEnd(conversationId: String, fromUser: String, fromDevice: String?, end: CallEnvelope.End) {
        if (fromUser.equals(me, true) && fromDevice.equals(myDevice, true)) return
        lock.withLock { onEndSignal(end.callId, end.reason) }
        marks.put((marks.get(end.callId) ?: CallMark(end.callId)).copy(ended = true, at = now()))
    }

    /** §16.3 (android R3): the page that carried offers is applied; ring for those still incoming. */
    suspend fun onPageEnd() {
        val ring = lock.withLock {
            val c = current?.takeIf { !it.ended && it.pendingRing && it.phase == CallPhase.RINGING_IN } ?: return
            c.pendingRing = false
            c
        }
        startRinging(ring)
    }

    // ---------------------------------------------------------------- handlers (under the lock)

    private fun pruneSiblings() {
        val t = now()
        siblingCalling.entries.removeAll { it.value.second <= t }
    }

    private suspend fun onOffer(s: InboundCall, env: CallEnvelope.Offer, own: Boolean, markedEnded: Boolean) {
        if (own) {
            // §16.5 sibling rule: my user is calling the DM peer from another device.
            dmPeer(s.conversationId, me)?.let { siblingCalling[it.lowercase()] = env.callId to now() + SIBLING_MS }
            return
        }
        val prior = marks.get(env.callId)
        if (prior != null || markedEnded) {
            log("offer ${env.callId}: already seen")
            return
        }
        // §16.3 freshness: both the server's and the sender's clock.
        val sn = serverNow()
        val sentAt = runCatching { Instant.parse(env.sentAt).toEpochMilli() }.getOrNull() ?: return
        if (sn - s.serverTsMs >= FRESH_MS || kotlin.math.abs(sn - sentAt) >= FRESH_MS) {
            log("offer ${env.callId}: stale (server ${sn - s.serverTsMs} ms, sent_at ${sn - sentAt} ms)")
            return
        }
        val peer = s.fromUser.lowercase()
        val cur = current?.takeIf { !it.ended }
        // §16.5 glare: my own outgoing ringing call to the same peer.
        if (cur != null && cur.outgoing && cur.peer.equals(peer, true) && (cur.phase == CallPhase.CALLING || cur.phase == CallPhase.RINGING_OUT)) {
            if (cur.id < env.callId) {
                log("glare: my ${cur.id} wins over ${env.callId}")
                return // the other side cancels and answers mine
            }
            log("glare: ${env.callId} wins over my ${cur.id}: cancel mine, answer theirs")
            val loser = cur
            dropQuietly(loser)
            marks.put(CallMark(loser.id, ended = true, at = now()))
            scope.launch { sig(loser.conv, loser.peer, CallEnvelope.Cancel(loser.id), loser.media) }
            val call = incoming(s, env)
            // §19.6 (android A4): the automatic answer uses the camera only if my losing call was a video call.
            call.wantCamera = call.video && loser.media == CallEnvelope.MEDIA_VIDEO && loser.wantCamera
            call.phase = CallPhase.ANSWERING
            marks.put(CallMark(env.callId, rang = true, answered = true, at = now())) // rang: this device is the callee (its call_end line says "incoming")
            publish(call)
            watchdog(call)
            scope.launch {
                val servers = withTimeoutOrNull(3_000) { runCatching { platform.iceServers() }.getOrDefault(emptyList()) } ?: emptyList()
                doAnswer(call, servers)
            }
            return
        }
        // §16.5 sibling rule: another device of my user is calling this peer: don't ring.
        siblingCalling[peer]?.let {
            log("offer ${env.callId}: my user is calling ${s.fromUser} (${it.first}): not ringing")
            return
        }
        // §16.5 busy (android R9): own call, or another app's call by the audio mode.
        if (cur != null || platform.audioBusy() || platform.otherCallActive()) {
            log("offer ${env.callId}: busy")
            marks.put(CallMark(env.callId, ended = true, at = now()))
            scope.launch { sig(s.conversationId, peer, CallEnvelope.Busy(env.callId), env.media) }
            return
        }
        val call = incoming(s, env)
        marks.put(CallMark(env.callId, at = now()))
        // §16.4 ring validity: server_ts + 45 s.
        val left = s.serverTsMs + ringMs - sn
        timer(call, "ring", left.coerceAtLeast(0)) { c ->
            log("ring validity over for ${c.id}")
            finish(c, notice = null, sendEnd = null)
            // Decision 054: a caller that crashed mid-ring sends no call_end; the missed call shows anyway.
            scope.launch {
                delay(MISSED_GRACE_MS)
                if (marks.get(c.id)?.let { it.rang && !it.answered } == true) runCatching { signals.missed(c.conv, c.peer, c.id, c.video) }
            }
        }
        watchdog(call)
        if (s.inPage) {
            call.pendingRing = true
        } else {
            scope.launch { startRinging(call) }
        }
    }

    private fun incoming(s: InboundCall, env: CallEnvelope.Offer): Call {
        val call = Call(env.callId, s.conversationId, s.fromUser.lowercase(), outgoing = false, phase = CallPhase.RINGING_IN)
        call.offerDevice = s.fromDevice.lowercase()
        call.offerSdp = env.sdp
        call.offerServerTsMs = s.serverTsMs
        call.pinned = SdpRules.fingerprint(env.sdp)
        call.media = env.media
        call.video = env.media == CallEnvelope.MEDIA_VIDEO
        call.videoSection = call.video
        call.everVideo = call.video
        call.peerFeatures = env.features.toSet()
        call.myFeatures = runCatching { features().toSet() }.getOrDefault(emptySet())
        call.peerFirstSdp = env.sdp
        call.peerCurrentSdp = env.sdp
        current = call
        return call
    }

    private suspend fun startRinging(call: Call) {
        lock.withLock {
            if (current !== call || call.ended || call.phase != CallPhase.RINGING_IN) return
            publish(call)
        }
        marks.put((marks.get(call.id) ?: CallMark(call.id)).copy(rang = true, at = now()))
        sig(call.conv, call.peer, CallEnvelope.Ringing(call.id), call.media)
    }

    private fun onRinging(s: InboundCall, own: Boolean) {
        if (own) return
        val c = current?.takeIf { !it.ended && it.outgoing && it.id == s.env.callId && it.peer.equals(s.fromUser, true) } ?: return
        if (c.phase == CallPhase.CALLING) {
            c.phase = CallPhase.RINGING_OUT
            publish(c)
        }
    }

    private suspend fun onAnswer(s: InboundCall, env: CallEnvelope.Answer, own: Boolean) {
        val c = current?.takeIf { !it.ended && it.id == env.callId } ?: return
        if (own) {
            // A sibling of mine answered this call (sender copy): stop ringing at once.
            if (c.outgoing) return
            c.siblingAnswered = true
            if (c.phase == CallPhase.RINGING_IN) finish(c, CallNotice.ANSWERED_ELSEWHERE, sendEnd = null)
            return
        }
        // Caller: only from the peer user, addressed to this device; the first answer wins.
        if (!c.outgoing || !c.peer.equals(s.fromUser, true) || !env.toDevice.equals(myDevice, true)) return
        if (c.selected != null) {
            if (!c.selected.equals(s.fromDevice, true)) return // a later answer: the first one won
            // §23.3 the answer to my re-offer (one at a time: a restart never runs while it is out).
            if (c.renegotiating) return onRenegotiateAnswer(c, env)
            // The answer to an ICE restart (§16.2): the same fingerprint as the first SDP (§16.10 d).
            if (c.phase != CallPhase.RECONNECTING && c.phase != CallPhase.ACTIVE) return
            if (!SdpRules.sameFingerprint(SdpRules.fingerprint(env.sdp), c.pinned)) {
                log("dtls_fingerprint_mismatch: restart answer with a different fingerprint")
                finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
                return
            }
            if (SdpRules.mLineCount(env.sdp) == 2 && !c.videoSection) {
                // A re-answer after the rollback (it took longer than the whole bound): the peer holds m=video, this
                // session doesn't, and libwebrtc can't take an answer without an offer out. Logged for the gate.
                log("call_answer with m=video after the re-offer was rolled back: the sessions are out of step")
                return
            }
            val ok = runCatching { mediaOp { c.session?.setRemote(env.sdp, isOffer = false) } }.onFailure { log("restart answer: ${it.message}") }.isSuccess
            if (ok) c.peerCurrentSdp = env.sdp
            return
        }
        val session = c.session ?: return
        // §19.4: the answer's shape must match the offer's media; a rejected m=video makes it an audio call.
        if ((SdpRules.mLineCount(env.sdp) == 2) != (c.media == CallEnvelope.MEDIA_VIDEO)) {
            log("call_answer for ${c.id}: m-lines don't match the ${c.media} offer: dropped")
            return
        }
        if (c.video && SdpRules.videoRejected(env.sdp)) {
            log("call_answer for ${c.id}: video rejected, an audio call")
            c.video = false
            c.videoSection = false
            applyCamera(c)
        }
        c.peerFeatures = env.features.toSet()
        c.peerFirstSdp = env.sdp
        c.peerCurrentSdp = env.sdp
        val fp = SdpRules.fingerprint(env.sdp)
        val remote = if (tamperRemoteFingerprint()) SdpRules.tamperFingerprint(env.sdp) else env.sdp
        c.selected = s.fromDevice.lowercase()
        c.pinned = fp
        c.timers.remove("ring")?.cancel()
        c.phase = CallPhase.CONNECTING
        publish(c)
        val ok = runCatching { mediaOp { session.setRemote(remote, isOffer = false) } }.onFailure { log("setRemote(answer) failed: ${it.message}") }.isSuccess
        if (!ok) {
            finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
            return
        }
        c.remoteSet = true
        c.remoteBuffer.forEach(session::addRemoteCandidate)
        c.remoteBuffer.clear()
        scope.launch { sig(c.conv, c.peer, CallEnvelope.Accepted(c.id, c.selected!!), c.media) }
        // §16.4: 20 s to connect after call_accepted (10 s without a relay, decision 054).
        timer(c, "connect", connectMs(c)) { x -> finish(x, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED) }
        // From now on my candidates go to the selected device.
        scheduleIceFlush(c)
    }

    private fun onAccepted(s: InboundCall, env: CallEnvelope.Accepted, own: Boolean) {
        if (own) return // my own user's caller device: nothing to do on a sibling
        val c = current?.takeIf { !it.ended && !it.outgoing && it.id == env.callId } ?: return
        if (!s.fromDevice.equals(c.offerDevice, true)) {
            log("call_accepted from ${s.fromDevice}, not the offer's device: ignored")
            return
        }
        if (!env.deviceId.equals(myDevice, true)) {
            // Another device of mine won (android R4): tear down quietly, no call_end.
            finish(c, CallNotice.ANSWERED_ELSEWHERE, sendEnd = null)
            return
        }
        if (c.phase != CallPhase.ANSWERING) return
        c.timers.remove("accept")?.cancel()
        c.selected = c.offerDevice
        c.phase = CallPhase.CONNECTING
        publish(c)
        timer(c, "connect", connectMs(c)) { x -> finish(x, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED) }
        maybeVerify(c)
    }

    private fun onIce(s: InboundCall, env: CallEnvelope.Ice, own: Boolean) {
        if (own) return
        val c = current?.takeIf { !it.ended && it.id == env.callId && it.peer.equals(s.fromUser, true) } ?: return
        if (c.outgoing) {
            // Caller: only from the selected device, addressed to me.
            if (c.selected == null || !c.selected.equals(s.fromDevice, true)) return
            if (!env.toDevice.equals(myDevice, true)) return
        } else {
            // Callee: only from the offer's device; null (before accepted) or addressed to me.
            if (!s.fromDevice.equals(c.offerDevice, true)) return
            if (env.toDevice != null && !env.toDevice.equals(myDevice, true)) return
        }
        val session = c.session
        if (session == null || !c.remoteSet) {
            c.remoteBuffer += env.candidates
        } else {
            env.candidates.forEach(session::addRemoteCandidate)
        }
    }

    private fun onBusy(s: InboundCall, own: Boolean) {
        val c = current?.takeIf { !it.ended && it.id == s.env.callId } ?: return
        if (own) {
            // My user is busy on another device (sender copy): don't ring here either.
            if (!c.outgoing) finish(c, notice = null, sendEnd = null)
            return
        }
        if (c.outgoing && c.peer.equals(s.fromUser, true) && c.selected == null) {
            finish(c, CallNotice.BUSY, sendEnd = CallEnvelope.R_BUSY)
        }
    }

    /** §19.3 `call_media` (android A5): only from the selected peer device (sender pinning), to this device, in a video call. */
    private fun onMedia(s: InboundCall, env: CallEnvelope.Media, own: Boolean) {
        if (own) return
        val c = current?.takeIf { !it.ended && it.id == env.callId && it.video && it.peer.equals(s.fromUser, true) } ?: return
        val peerDevice = if (c.outgoing) c.selected else c.offerDevice
        if (peerDevice == null || !s.fromDevice.equals(peerDevice, true) || !env.toDevice.equals(myDevice, true)) {
            log("call_media for ${c.id} from ${s.fromDevice}: not the selected device: dropped")
            return
        }
        // §23.4: `video` (absent = derived from `camera`); the screen label comes only from here.
        val camera = env.state != CallEnvelope.VIDEO_OFF
        val screen = env.state == CallEnvelope.VIDEO_SCREEN
        if (c.peerCamera != camera || c.peerScreen != screen) {
            c.peerCamera = camera
            c.peerScreen = screen
            c.peerCameraAt = now()
            publish(c)
        }
    }

    private fun onCancel(s: InboundCall, own: Boolean) {
        val id = s.env.callId
        if (own) {
            siblingCalling.entries.removeAll { it.value.first == id }
            return
        }
        val c = current?.takeIf { !it.ended && !it.outgoing && it.id == id } ?: return
        if (!s.fromDevice.equals(c.offerDevice, true)) return
        // Glare (android R2): stop ringing, render nothing.
        finish(c, notice = null, sendEnd = null)
    }

    private fun onEndSignal(callId: String, reason: String) {
        siblingCalling.entries.removeAll { it.value.first == callId }
        val c = current?.takeIf { !it.ended && it.id == callId } ?: return
        val notice = when {
            c.outgoing && reason == CallEnvelope.R_DECLINED -> CallNotice.DECLINED
            reason == CallEnvelope.R_FAILED -> CallNotice.CANT_CONNECT
            else -> CallNotice.CALL_ENDED
        }
        finish(c, if (c.phase == CallPhase.RINGING_IN) null else notice, sendEnd = null)
    }

    private suspend fun onRestartOffer(s: InboundCall, env: CallEnvelope.Offer) {
        val c = current?.takeIf { !it.ended && it.id == env.callId } ?: return
        val peerDevice = if (c.outgoing) c.selected else c.offerDevice
        if (!s.fromUser.equals(c.peer, true) || !s.fromDevice.equals(peerDevice, true) || !env.toDevice.equals(myDevice, true)) return
        // §19.3: restarts keep the media and the same m-lines.
        if (env.media != c.media || s.media != c.media) {
            log("restart offer for ${c.id} with media ${env.media}: dropped")
            return
        }
        if (!SdpRules.sameFingerprint(SdpRules.fingerprint(env.sdp), c.pinned)) {
            log("dtls_fingerprint_mismatch: restart offer with a different fingerprint")
            finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
            return
        }
        val session = c.session ?: return
        val answer = runCatching {
            mediaOp { session.setRemote(env.sdp, isOffer = true) }
            mediaOp { session.createAnswer() }
        }.getOrElse {
            log("restart offer not applied (${SdpRules.mLineCount(env.sdp)} m-lines): ${it.message}")
            finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
            return
        }
        // §23.3: the current session's ICE credentials (a later re-offer keeps these).
        c.peerCurrentSdp = env.sdp
        c.myCurrentSdp = answer
        scope.launch { sig(c.conv, c.peer, CallEnvelope.Answer(c.id, s.fromDevice.lowercase(), answer), c.media) }
    }

    // ---------------------------------------------------------------- §23 switching and screen sharing

    private fun peerDevice(c: Call): String? = if (c.outgoing) c.selected else c.offerDevice

    private fun live(c: Call) = c.phase == CallPhase.ACTIVE || c.phase == CallPhase.RECONNECTING

    /** §23.1/§23.3: both listed `switch`, and the session has `m=video` or may get it (a voice call, never renegotiated yet). */
    private fun canSwitch(c: Call): Boolean = peerSwitches(c) &&
        (c.videoSection || (c.media == CallEnvelope.MEDIA_AUDIO && !c.renegotiationFailed))

    private fun canShare(c: Call): Boolean = canSwitch(c) && CallEnvelope.FEATURE_SCREEN in c.peerFeatures && CallEnvelope.FEATURE_SCREEN in c.myFeatures

    /** Under the lock: queues [env] behind every earlier switch/media/re-offer envelope of [c]. */
    private fun send(c: Call, env: CallEnvelope.Env, after: (suspend (SignalOutcome) -> Unit)? = null) {
        val q = c.ordered ?: kotlinx.coroutines.channels.Channel<Pair<CallEnvelope.Env, (suspend (SignalOutcome) -> Unit)?>>(kotlinx.coroutines.channels.Channel.UNLIMITED).also { ch ->
            c.ordered = ch
            scope.launch {
                for ((e, cb) in ch) {
                    val r = sig(c.conv, c.peer, e, c.media)
                    if (e is CallEnvelope.Switch) log("call_switch ${e.action} ${e.seq} sent: $r")
                    cb?.invoke(r)
                }
            }
        }
        q.trySend(env to after)
    }

    /**
     * §23.2 step 1: the Video ([CallEnvelope.SOURCE_CAMERA]; CAMERA already asked) or Share screen
     * ([CallEnvelope.SOURCE_SCREEN] with [grant], the consent of this tap) button in voice mode.
     * When the peer is asking already, the tap is the answer (both asked). False = not now.
     */
    suspend fun requestVideo(source: String, grant: Any? = null): Boolean {
        lock.withLock {
            val c = current?.takeIf { !it.ended && live(it) && !it.video } ?: return false
            if (!canSwitch(c) || source !in CallEnvelope.SOURCES) return false
            if (source == CallEnvelope.SOURCE_SCREEN && (!canShare(c) || grant == null)) return false
            if (c.asking != null || now() < c.blockedUntil) return false
            val to = peerDevice(c) ?: return false
            c.prompt?.let { (seq, _) ->
                answerPromptLocked(c, seq, to, mine = source, grant = grant)
                return true
            }
            c.mySeq++
            val seq = c.mySeq
            c.asking = seq to source
            c.screenGrant = grant
            c.consented = true // crypto C2: this user asked
            c.switchNotice = null
            publish(c)
            timer(c, "switch_ask", SWITCH_ASK_MS) { x ->
                if (x.asking?.first == seq) {
                    log("switch request $seq: no answer in $SWITCH_ASK_MS ms: cancel")
                    x.asking = null
                    x.screenGrant = null
                    send(x, CallEnvelope.Switch(x.id, to, seq, CallEnvelope.SW_CANCEL))
                    notice(x, SwitchNotice.NO_ANSWER)
                }
            }
            send(c, CallEnvelope.Switch(c.id, to, seq, CallEnvelope.SW_REQUEST, source))
            log("switch request $seq ($source)")
        }
        return true
    }

    /** §23.2 Cancel on "Asking … to switch to video…". */
    suspend fun cancelVideoRequest() {
        lock.withLock {
            val c = current?.takeIf { !it.ended } ?: return
            val (seq, _) = c.asking ?: return
            val to = peerDevice(c) ?: return
            c.asking = null
            c.screenGrant = null
            c.timers.remove("switch_ask")?.cancel()
            publish(c)
            send(c, CallEnvelope.Switch(c.id, to, seq, CallEnvelope.SW_CANCEL))
        }
    }

    /**
     * §23.2 steps 2–3, the prompt: [accept] with [camera] (Accept: my camera on, CAMERA already
     * asked; Without my camera / Watch: off) or Decline ("Not now").
     */
    suspend fun answerVideoRequest(accept: Boolean, camera: Boolean = false) {
        lock.withLock {
            val c = current?.takeIf { !it.ended && live(it) } ?: return
            val (seq, _) = c.prompt ?: return
            val to = peerDevice(c) ?: return
            if (!accept) {
                c.prompt = null
                c.timers.remove("prompt")?.cancel()
                publish(c)
                send(c, CallEnvelope.Switch(c.id, to, seq, CallEnvelope.SW_DECLINE))
                log("switch request $seq declined")
                return
            }
            answerPromptLocked(c, seq, to, mine = if (camera) CallEnvelope.SOURCE_CAMERA else null, grant = null)
        }
    }

    private fun answerPromptLocked(c: Call, seq: Int, to: String, mine: String?, grant: Any?) {
        c.prompt = null
        c.timers.remove("prompt")?.cancel()
        if (grant != null) c.screenGrant = grant
        send(c, CallEnvelope.Switch(c.id, to, seq, CallEnvelope.SW_ACCEPT))
        log("switch request $seq accepted")
        enterVideo(c, mine)
    }

    /** §23.2 Voice in video mode: both stop sending video; a later switch needs a new request (no new renegotiation). */
    suspend fun backToVoice() {
        lock.withLock {
            val c = current?.takeIf { !it.ended && it.video && live(it) } ?: return
            if (!peerSwitches(c)) return
            val to = peerDevice(c) ?: return
            c.mySeq++
            send(c, CallEnvelope.Switch(c.id, to, c.mySeq, CallEnvelope.SW_VOICE))
            enterVoice(c)
        }
    }

    /** §23.5 Share screen in video mode ([grant]: the consent of this tap, used once). */
    suspend fun startShare(grant: Any): Boolean {
        lock.withLock {
            val c = current?.takeIf { !it.ended && it.video && live(it) && !it.screenOn } ?: return false
            if (!canShare(c)) return false
            c.screenGrant = grant
            startScreenLocked(c)
            return c.screenOn
        }
    }

    /** §23.5 Stop: the banner, the notification's Stop, SCREEN_OFF, the platform's onStop. */
    suspend fun stopShare(why: String = "stop") {
        lock.withLock { current?.takeIf { !it.ended }?.let { stopScreenLocked(it, why) } }
    }

    private fun startScreenLocked(c: Call) {
        val grant = c.screenGrant ?: return
        c.screenGrant = null // one-use: never kept for a later share (§23.5)
        val s = c.session ?: return
        c.cameraBeforeShare = c.wantCamera
        c.wantCamera = false
        applyCamera(c)
        val ok = runCatching {
            s.startScreen(grant) { scope.launch { lock.withLock { if (current === c) stopScreenLocked(c, "the platform stopped the projection") } } }
        }.onFailure { log("screen share: ${it.message}") }.getOrDefault(false)
        if (ok) {
            c.screenOn = true
            log("screen share on")
        } else {
            c.wantCamera = c.cameraBeforeShare
            applyCamera(c)
            notice(c, SwitchNotice.SHARE_FAILED)
        }
        publish(c)
        scheduleCameraSignal(c)
    }

    private fun stopScreenLocked(c: Call, why: String) {
        if (!c.screenOn) return
        log("screen share off ($why)")
        runCatching { c.session?.stopScreen() }
        c.screenOn = false
        // §23.5: back to the camera only if it was on before and the call screen is visible.
        if (c.video && c.cameraBeforeShare && screenVisible) c.wantCamera = true
        c.cameraBeforeShare = false
        applyCamera(c)
        publish(c)
        scheduleCameraSignal(c)
    }

    /** §23.2 step 5: video mode; [mine] = what this device starts (camera, screen, or nothing). */
    private fun enterVideo(c: Call, mine: String?) {
        c.timers.remove("switch_ask")?.cancel()
        c.asking = null
        c.prompt = null
        c.switchNotice = null
        c.video = true
        c.everVideo = true
        c.consented = true
        c.peerCamera = false
        c.peerScreen = false
        c.peerCameraAt = now()
        c.cameraSent = null // one `call_media` after entering video mode
        log("video mode (mine=${mine ?: "off"}, m=video ${if (c.videoSection) "negotiated" else "to add"})")
        if (!c.videoSection) {
            c.session?.enableVideo()
            if (c.outgoing) {
                scope.launch { renegotiate(c) }
            } else {
                timer(c, "reneg_wait", RENEGOTIATE_WAIT_MS) { x -> if (!x.videoSection && x.video) failSwitch(x, "no re-offer from the caller") }
            }
        }
        when (mine) {
            CallEnvelope.SOURCE_CAMERA -> c.wantCamera = true
            CallEnvelope.SOURCE_SCREEN -> startScreenLocked(c)
        }
        applyCamera(c)
        publish(c)
        scheduleCameraSignal(c)
    }

    /** §23.2 back to voice mode: no video sent (setTrack(null)), no share, nothing rendered. */
    private fun enterVoice(c: Call) {
        log("voice mode")
        c.timers.remove("switch_ask")?.cancel()
        c.timers.remove("reneg_wait")?.cancel()
        // A re-offer still out (the caller) stays out: the callee may apply it, so dropping it here would leave
        // the sessions out of step. Its answer adds m=video (unused in voice mode) or its bound rolls it back;
        // either way an ICE restart that came due meanwhile runs right after (restartPending), never lost.
        c.asking = null
        c.prompt = null
        c.screenGrant = null
        c.video = false
        stopScreenLocked(c, "voice")
        c.wantCamera = false
        c.peerScreen = false
        applyCamera(c)
        publish(c)
    }

    private fun notice(c: Call, n: SwitchNotice) {
        c.switchNotice = n
        publish(c)
        timer(c, "switch_notice", SWITCH_NOTICE_MS) { x ->
            if (x.switchNotice == n) {
                x.switchNotice = null
                publish(x)
            }
        }
    }

    /** §23.2 step 6: a renegotiation that failed rolls back, keeps audio, and puts both sides back in voice mode. */
    private suspend fun failSwitch(c: Call, why: String) {
        log("couldn't switch to video: $why")
        c.timers.remove("reneg")?.cancel()
        c.timers.remove("reneg_wait")?.cancel()
        if (c.renegotiating) {
            c.renegotiating = false
            runCatching { mediaOp { c.session?.rollback() } }.onFailure { log("rollback: ${it.message}") }
        }
        if (!c.videoSection) c.renegotiationFailed = true
        val to = peerDevice(c)
        if (c.video && to != null) {
            c.mySeq++
            send(c, CallEnvelope.Switch(c.id, to, c.mySeq, CallEnvelope.SW_VOICE))
        }
        enterVoice(c)
        notice(c, SwitchNotice.FAILED)
        runPendingRestart(c)
    }

    /** The ICE restart that waited for the re-offer (one offer at a time) runs now, if the call still needs it. */
    private fun runPendingRestart(c: Call) {
        if (!c.restartPending) return
        c.restartPending = false
        if (c.outgoing && c.phase == CallPhase.RECONNECTING && !c.ended) {
            log("the restart that waited for the re-offer runs now")
            scope.launch { restart(c) }
        }
    }

    /** §23.3 the original caller's one re-offer that adds `m=video` (after an accept). */
    private suspend fun renegotiate(c: Call) {
        lock.withLock {
            if (c.ended || current !== c || c.videoSection || c.renegotiating || c.renegotiationFailed || !c.outgoing) return
            val s = c.session ?: return
            val first = c.myFirstSdp ?: return failSwitch(c, "no first offer")
            c.renegotiating = true
            val sdp = runCatching {
                s.enableVideo()
                mediaOp { s.createOffer() }
            }.getOrElse { return failSwitch(c, "re-offer: ${it.message}") }
            (SdpRules.validate(sdp, SdpRules.Role.OFFER, video = true) ?: SdpRules.renegotiationProblem(first, c.myCurrentSdp ?: first, sdp, SdpRules.Role.OFFER))?.let {
                return failSwitch(c, "own re-offer: $it")
            }
            c.reofferDelivered = false
            // §23.3 the 10-s bound. A re-offer the server took may still be applied by the callee (who then holds
            // m=video): rolling back then would leave the sessions out of step and the next ICE restart would end the
            // call. So after a delivery the caller waits [RENEGOTIATE_DELIVERED_MS] more for the answer (and the callee
            // never applies a re-offer older than [RENEGOTIATE_MS]); only an undelivered re-offer rolls back at 10 s.
            timer(c, "reneg", RENEGOTIATE_MS) { x ->
                if (!x.renegotiating) return@timer
                if (!x.reofferDelivered) return@timer failSwitch(x, "no answer to the re-offer in $RENEGOTIATE_MS ms")
                log("renegotiate: the re-offer was delivered, no answer yet: waiting up to $RENEGOTIATE_DELIVERED_MS ms more")
                x.timers.remove("reneg") // this job: replaced below, not cancelled under itself
                timer(x, "reneg", RENEGOTIATE_DELIVERED_MS) { y ->
                    if (y.renegotiating) failSwitch(y, "no answer to the delivered re-offer in ${RENEGOTIATE_MS + RENEGOTIATE_DELIVERED_MS} ms")
                }
            }
            log("renegotiate: re-offer out")
            send(c, CallEnvelope.Offer(c.id, sdp, iso(serverNow()), toDevice = c.selected, media = c.media, renegotiate = true)) { r ->
                lock.withLock {
                    if (r == SignalOutcome.Ok) {
                        c.reofferDelivered = true
                    } else if (c.renegotiating && current === c && !c.ended) {
                        failSwitch(c, "re-offer not sent: $r")
                    }
                }
            }
        }
    }

    /** §23.3 the caller: the answer to the re-offer (fingerprint, ICE credentials, setup, mids), then (e) again. */
    private suspend fun onRenegotiateAnswer(c: Call, env: CallEnvelope.Answer) {
        val session = c.session ?: return
        val first = c.peerFirstSdp ?: return failSwitch(c, "no first answer")
        if (SdpRules.mLineCount(env.sdp) != 2) return failSwitch(c, "answer without m=video")
        if (!SdpRules.sameFingerprint(SdpRules.fingerprint(env.sdp), c.pinned)) {
            log("dtls_fingerprint_mismatch: the re-offer's answer has a different fingerprint")
            finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
            return
        }
        SdpRules.renegotiationProblem(first, c.peerCurrentSdp ?: first, env.sdp, SdpRules.Role.ANSWER)?.let { return failSwitch(c, "answer: $it") }
        val ok = runCatching { mediaOp { session.setRemote(env.sdp, isOffer = false) } }.onFailure { log("setRemote(re-answer): ${it.message}") }.isSuccess
        if (!ok) return failSwitch(c, "answer not applied")
        c.timers.remove("reneg")?.cancel()
        c.renegotiating = false
        c.renegotiated = true
        c.videoSection = true
        c.peerCurrentSdp = env.sdp
        log("renegotiated: m=video added")
        recheck(c)
        applyCamera(c)
        publish(c)
        runPendingRestart(c)
    }

    /** §23.3 the callee: the caller's re-offer, applied only with this user's consent (crypto C2) and only once. */
    private suspend fun onRenegotiateOffer(s: InboundCall, env: CallEnvelope.Offer) {
        val c = current?.takeIf { !it.ended && it.id == env.callId } ?: return
        if (c.outgoing) return log("re-offer for ${c.id} to the caller: dropped (only the original caller re-offers)")
        if (!s.fromUser.equals(c.peer, true) || !s.fromDevice.equals(c.offerDevice, true) || !env.toDevice.equals(myDevice, true)) return
        if (env.media != c.media || s.media != c.media) return log("re-offer for ${c.id} with media ${env.media}: dropped")
        if (!c.consented || c.renegotiated || c.videoSection) return log("re-offer for ${c.id} without consent (or a second one): dropped")
        // §23.3: the caller gives up on an undelivered re-offer at 10 s and on a delivered one later; a re-offer older
        // than the 10-s bound is never applied, so the callee can't take m=video after the caller rolled back.
        val age = serverNow() - s.serverTsMs
        if (s.serverTsMs > 0 && age > RENEGOTIATE_MS) {
            return failSwitch(c, "re-offer $age ms old (the caller's bound is $RENEGOTIATE_MS ms)")
        }
        if (!SdpRules.sameFingerprint(SdpRules.fingerprint(env.sdp), c.pinned)) {
            log("dtls_fingerprint_mismatch: re-offer with a different fingerprint")
            finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
            return
        }
        val first = c.peerFirstSdp ?: return
        SdpRules.renegotiationProblem(first, c.peerCurrentSdp ?: first, env.sdp, SdpRules.Role.OFFER)?.let { return failSwitch(c, "re-offer: $it") }
        val session = c.session ?: return
        c.renegotiating = true
        val answer = runCatching {
            session.enableVideo()
            mediaOp { session.setRemote(env.sdp, isOffer = true) }
            mediaOp { session.createAnswer() }
        }.getOrElse { return failSwitch(c, "answer to the re-offer: ${it.message}") }
        (SdpRules.validate(answer, SdpRules.Role.ANSWER, video = true) ?: c.myFirstSdp?.let { SdpRules.renegotiationProblem(it, c.myCurrentSdp ?: it, answer, SdpRules.Role.ANSWER) })?.let {
            return failSwitch(c, "own re-answer: $it")
        }
        c.renegotiating = false
        c.renegotiated = true
        c.videoSection = true
        c.peerCurrentSdp = env.sdp
        c.myCurrentSdp = answer
        c.timers.remove("reneg_wait")?.cancel()
        log("renegotiated: m=video added (callee)")
        send(c, CallEnvelope.Answer(c.id, c.offerDevice!!, answer))
        recheck(c)
        applyCamera(c)
        publish(c)
    }

    /** §23.3: after the renegotiation the §16.10 (e) check runs again (same transport, same fingerprint). */
    private fun recheck(c: Call) {
        scope.launch {
            delay(STATS_POLL_MS)
            // Bounded like every WebRTC operation (decision 054): a getStats that never calls back can't hang the check.
            val st = lock.withLock { if (c.ended || current !== c) null else c.session }?.let { boundedStats(it) } ?: return@launch
            lock.withLock {
                if (c.ended || current !== c) return@launch
                if ((st.remoteFingerprint != null && !SdpRules.sameFingerprint(st.remoteFingerprint, c.pinned)) || st.dtlsState == "failed") {
                    log("dtls_fingerprint_mismatch after the renegotiation")
                    finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
                }
            }
        }
    }

    /** §23.2 an inbound `call_switch` from the selected peer device to this one (sender pinning, `seq` rules). */
    private suspend fun onSwitch(s: InboundCall, env: CallEnvelope.Switch, own: Boolean) {
        if (own) return
        val c = current?.takeIf { !it.ended && it.id == env.callId && it.peer.equals(s.fromUser, true) } ?: return log("call_switch ${env.action} ${env.seq}: no such call")
        log("call_switch ${env.action} ${env.seq} received")
        val peerDev = peerDevice(c)
        if (peerDev == null || !s.fromDevice.equals(peerDev, true) || !env.toDevice.equals(myDevice, true)) {
            return log("call_switch for ${c.id} from ${s.fromDevice}: not the selected device: dropped")
        }
        if (CallEnvelope.FEATURE_SWITCH !in c.myFeatures) return log("call_switch: this device doesn't switch: dropped")
        if (!live(c)) return log("call_switch ${env.action} before the call connected: dropped")
        when (env.action) {
            CallEnvelope.SW_REQUEST -> {
                if (env.seq <= c.peerSeq) return log("call_switch request ${env.seq}: replayed (last ${c.peerSeq}): dropped")
                c.peerSeq = env.seq
                if (c.video) return log("call_switch request in video mode: dropped")
                if (!canSwitch(c)) {
                    send(c, CallEnvelope.Switch(c.id, peerDev, env.seq, CallEnvelope.SW_DECLINE))
                    return
                }
                c.asking?.let { (_, mine) ->
                    // §23.2 step 4: crossing requests: both asked, no prompt; both send accept.
                    log("crossing switch requests: accepted without a prompt")
                    answerPromptLocked(c, env.seq, peerDev, mine, grant = null)
                    return
                }
                c.prompt = env.seq to env.source!!
                // The requester cancels after 20 s; a lost cancel still removes the prompt.
                timer(c, "prompt", SWITCH_ASK_MS + 5_000) { x ->
                    if (x.prompt?.first == env.seq) {
                        x.prompt = null
                        publish(x)
                    }
                }
                publish(c)
            }
            CallEnvelope.SW_ACCEPT -> {
                val a = c.asking
                if (a == null || a.first != env.seq) return log("call_switch accept ${env.seq}: not my pending request: dropped")
                enterVideo(c, a.second)
            }
            CallEnvelope.SW_DECLINE -> {
                if (c.asking?.first != env.seq) return log("call_switch decline ${env.seq}: not my pending request: dropped")
                c.asking = null
                c.screenGrant = null
                c.timers.remove("switch_ask")?.cancel()
                c.blockedUntil = now() + SWITCH_COOLDOWN_MS
                notice(c, SwitchNotice.DECLINED)
            }
            CallEnvelope.SW_CANCEL -> {
                if (c.prompt?.first != env.seq) return log("call_switch cancel ${env.seq}: no such prompt: dropped")
                c.prompt = null
                c.timers.remove("prompt")?.cancel()
                publish(c)
            }
            CallEnvelope.SW_VOICE -> {
                if (env.seq <= c.peerSeq) return log("call_switch voice ${env.seq}: replayed (last ${c.peerSeq}): dropped")
                c.peerSeq = env.seq
                if (c.video) enterVoice(c)
            }
        }
    }

    // ---------------------------------------------------------------- media callbacks

    private fun openSession(call: Call, servers: List<IceServer>): MediaSession {
        val s = media.open(
            call.id, servers,
            object : CallMedia.Listener {
                override fun onLocalCandidate(c: CallEnvelope.Candidate) {
                    scope.launch {
                        lock.withLock {
                            if (call.ended) return@withLock
                            call.localBuffer += c
                            scheduleIceFlush(call)
                        }
                    }
                }

                override fun onGatheringDone() {
                    scope.launch {
                        lock.withLock {
                            if (call.ended) return@withLock
                            call.gatheringDone = true
                            scheduleIceFlush(call)
                        }
                    }
                }

                override fun onIceState(state: IceState) {
                    scope.launch { lock.withLock { onIce(call, state) } }
                }
            },
            video = call.media == CallEnvelope.MEDIA_VIDEO,
        )
        call.session = s
        call.relay = servers.any { srv -> srv.urls.any { it.startsWith("turn:") || it.startsWith("turns:") } }
        s.setMuted(call.muted)
        // §19.6: the caller's local preview from the start; the callee's only after a human Answer.
        if (call.video) applyCamera(call)
        return s
    }

    private fun onIce(c: Call, state: IceState) {
        if (c.ended || current !== c) return
        lastIce[c.id] = state
        when (state) {
            IceState.CONNECTED, IceState.COMPLETED -> {
                c.timers.remove("reconnect")?.cancel()
                if (c.phase == CallPhase.RECONNECTING) {
                    c.phase = CallPhase.ACTIVE
                    publish(c)
                } else {
                    maybeVerify(c)
                }
            }
            // §16.11: ICE failed → "Can't connect the call" at once.
            IceState.FAILED -> finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
            IceState.DISCONNECTED -> if (c.phase == CallPhase.ACTIVE) {
                c.phase = CallPhase.RECONNECTING
                publish(c)
                timer(c, "reconnect", RECONNECT_MS) { x -> finish(x, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED) }
                // The caller restarts ICE on the same call_id (§16.2 restart).
                if (c.outgoing) scope.launch { restart(c) }
            }
            else -> Unit
        }
    }

    private val lastIce = HashMap<String, IceState>()

    private suspend fun restart(c: Call) {
        val offer = lock.withLock {
            if (c.ended || current !== c) return
            // §23.3 (android A1): one offer at a time; the restart runs as soon as the re-offer resolves (answered or
            // rolled back), so a drop during a switch (also after the call went back to voice) is still repaired.
            if (c.renegotiating) {
                c.restartPending = true
                log("restart waits for the outstanding re-offer")
                return
            }
            val s = c.session ?: return
            val sdp = runCatching { mediaOp { s.createOffer(iceRestart = true) } }.getOrNull() ?: return
            c.myCurrentSdp = sdp
            CallEnvelope.Offer(c.id, sdp, iso(serverNow()), restart = true, toDevice = c.selected, media = c.media)
        }
        sig(c.conv, c.peer, offer, c.media)
    }

    /** §16.10 (e) after ICE connected (and, for the callee, after call_accepted named it). */
    private fun maybeVerify(c: Call) {
        if (c.phase != CallPhase.CONNECTING) return
        // ICE not connected yet: onIce calls back here when it is.
        val ice = lastIce[c.id]
        if (ice != IceState.CONNECTED && ice != IceState.COMPLETED) return
        if (c.checking?.isActive == true) return
        c.checking = scope.launch {
            while (true) {
                val stats = lock.withLock {
                    if (c.ended || current !== c || c.phase != CallPhase.CONNECTING) return@launch
                    c.session
                }?.let { boundedStats(it) } ?: DtlsStats(null, null, null)
                val done = lock.withLock {
                    if (c.ended || current !== c || c.phase != CallPhase.CONNECTING) return@launch
                    when {
                        stats.remoteFingerprint != null && !SdpRules.sameFingerprint(stats.remoteFingerprint, c.pinned) -> {
                            log("dtls_fingerprint_mismatch")
                            finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
                            true
                        }
                        stats.dtlsState == "failed" -> {
                            finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
                            true
                        }
                        stats.dtlsState == "connected" && !stats.srtpCipher.isNullOrEmpty() && stats.remoteFingerprint != null -> {
                            c.timers.remove("connect")?.cancel()
                            c.timers.remove("watchdog")?.cancel()
                            c.phase = CallPhase.ACTIVE
                            c.connectedAt = now()
                            c.connectedAtServer = serverNow()
                            c.verified = true
                            timer(c, "max", MAX_CALL_MS) { x -> finish(x, CallNotice.CALL_ENDED, sendEnd = CallEnvelope.R_HANGUP) }
                            publish(c)
                            stallWatch(c)
                            // §19.3: a call that connects with my camera off tells the peer (their avatar at once).
                            if (c.video && !c.cameraOn) scheduleCameraSignal(c)
                            true
                        }
                        else -> false
                    }
                }
                if (done) return@launch
                delay(STATS_POLL_MS)
            }
        }
    }

    // ---------------------------------------------------------------- ICE batching (§16.2)

    private fun scheduleIceFlush(c: Call) {
        if (!c.offerSent || c.ended) return
        if (c.iceJob?.isActive == true) return
        if (c.localBuffer.isEmpty() && !(c.gatheringDone && !c.doneSent)) return
        // The caller sends `to_device: null` until call_accepted (every ringing device may use them).
        c.iceJob = scope.launch {
            delay(ICE_BATCH_MS)
            while (true) {
                val batch = lock.withLock {
                    if (c.ended || current !== c) return@launch
                    val take = c.localBuffer.take(CallEnvelope.MAX_CANDIDATES)
                    c.localBuffer.subList(0, take.size).clear()
                    val done = c.gatheringDone && c.localBuffer.isEmpty() && !c.doneSent
                    if (take.isEmpty() && !done) return@launch
                    if (done) c.doneSent = true
                    val to = if (c.outgoing) c.selected else c.offerDevice
                    CallEnvelope.Ice(c.id, to, take, done)
                }
                sig(c.conv, c.peer, batch, c.media)
                delay(ICE_BATCH_MS)
            }
        }
    }

    // ---------------------------------------------------------------- endings

    /** Glare loser: no call_end, no notice (it never connected). */
    private fun dropQuietly(c: Call) {
        c.ended = true
        c.timers.values.forEach { it.cancel() }
        c.timers.clear()
        c.iceJob?.cancel()
        c.checking?.cancel()
        c.cameraJob?.cancel()
        c.cameraOn = false
        // §23.5: the share stops on call end (before the session goes).
        if (c.screenOn) runCatching { c.session?.stopScreen() }
        c.screenOn = false
        c.screenGrant = null
        c.ordered?.close()
        runCatching { c.session?.close() }
        c.session = null
        if (current === c) current = null
    }

    /**
     * Ends [c]: timers, media, a durable `call_end` with [sendEnd] (null = send nothing), and the
     * short ended state (no notice = disappear at once).
     */
    private fun finish(c: Call, notice: CallNotice?, sendEnd: String?) {
        if (c.ended) return
        val connectedAtServer = c.connectedAtServer
        val duration = c.connectedAt?.let { ((now() - it) / 1000).coerceAtLeast(0) }
        dropQuietly(c)
        scope.launch { marks.put((marks.get(c.id) ?: CallMark(c.id)).copy(ended = true, at = now())) }
        if (sendEnd != null) {
            val end = CallEnvelope.End(
                c.id, sendEnd,
                connectedAt = connectedAtServer?.takeIf { sendEnd == CallEnvelope.R_HANGUP || sendEnd == CallEnvelope.R_FAILED }?.let(::iso),
                durationS = duration?.takeIf { connectedAtServer != null },
                // §19.3/§23.8: "video" if the call was ever in video mode (even if both cameras were off).
                media = if (c.everVideo || c.media == CallEnvelope.MEDIA_VIDEO) CallEnvelope.MEDIA_VIDEO else CallEnvelope.MEDIA_AUDIO,
                outgoing = c.outgoing,
            )
            scope.launch { signals.end(c.conv, c.peer, end) }
        }
        if (notice == null || !c.shown) {
            if (_state.value?.callId == c.id) _state.value = null
            return
        }
        _state.value = CallSnapshot(c.id, c.conv, c.peer, c.outgoing, CallPhase.ENDED, c.muted, c.connectedAt, c.verified, notice, video = c.video, everVideo = c.everVideo)
        scope.launch {
            delay(lingerMs)
            if (_state.value?.callId == c.id && _state.value?.phase == CallPhase.ENDED) _state.value = null
        }
    }

    private fun publishNotice(conv: String, peer: String, outgoing: Boolean, notice: CallNotice) {
        val id = "notice-" + newCallId()
        _state.value = CallSnapshot(id, conv, peer, outgoing, CallPhase.ENDED, notice = notice)
        scope.launch {
            delay(lingerMs)
            if (_state.value?.callId == id) _state.value = null
        }
    }

    private fun publish(c: Call) {
        if (c.ended) return
        c.shown = true
        _state.value = CallSnapshot(
            c.id, c.conv, c.peer, c.outgoing, c.phase, c.muted, c.connectedAt, c.verified, null,
            video = c.video, cameraOn = c.cameraOn, wantCamera = c.wantCamera, peerCamera = c.peerCamera, peerCameraAtMs = c.peerCameraAt,
            canSwitch = canSwitch(c), canShare = canShare(c), asking = c.asking?.second, prompt = c.prompt?.second,
            switchNotice = c.switchNotice, videoBlockedUntilMs = c.blockedUntil, sharing = c.screenOn, peerSharing = c.video && c.peerScreen,
            everVideo = c.everVideo,
        )
    }

    /** A `call:signal` that never hangs the call (decision 054); [media] = the call's (§19.2). */
    private suspend fun sig(conv: String, peer: String, env: CallEnvelope.Env, media: String): SignalOutcome =
        withTimeoutOrNull(SIGNAL_TIMEOUT_MS) { signals.signal(conv, peer, env, media) } ?: SignalOutcome.Unavailable.also { log("${env.type} timed out") }

    /** `getStats` bounded (decision 054); null = no result in time (or it failed). */
    private suspend fun boundedStats(s: MediaSession): DtlsStats? =
        withTimeoutOrNull(STATS_TIMEOUT_MS) { runCatching { s.stats() }.getOrNull() }

    /** One WebRTC operation under the machine lock, bounded (decision 054): a stuck callback can't hold every input. */
    private suspend fun <T> mediaOp(block: suspend () -> T): T =
        withTimeoutOrNull(MEDIA_OP_MS) { block() } ?: throw IllegalStateException("media operation timed out")

    private fun connectMs(c: Call) = if (c.relay) CONNECT_MS else DIRECT_CONNECT_MS

    /**
     * Decision 054: while the call is up, received audio must keep growing (Opus DTX still sends
     * every 400 ms in silence). Once audio has flowed, [MEDIA_STALL_MS] without a new byte ends the
     * call visibly with the full cleanup. A media layer that can't count bytes (null) is never judged.
     */
    private fun stallWatch(c: Call) {
        c.timers.remove("stall")?.cancel()
        c.timers["stall"] = scope.launch {
            var last = -1L
            var lastChange = now()
            var told = false
            var statsAt = now()
            while (true) {
                delay(MEDIA_STALL_POLL_MS)
                val session = lock.withLock { if (c.ended || current !== c) return@launch else c.session } ?: return@launch
                // The "WebRTC stall" watch item: a getStats that never called back parked this loop for good (no stall
                // could be judged after it). Bounded now, and the watch logs when the stats or the audio stop advancing.
                val st = boundedStats(session)
                if (st == null) {
                    log("media watch: getStats gave no result (stats silent for ${now() - statsAt} ms)")
                    continue
                }
                statsAt = now()
                val bytes = st.bytesReceived ?: continue
                if (bytes != last) {
                    if (told) log("media watch: audio_recv advancing again ($last→$bytes)")
                    told = false
                    last = bytes
                    lastChange = now()
                } else if (bytes > 0 && !told && now() - lastChange >= STALL_LOG_MS) {
                    told = true
                    log("media watch: audio_recv stopped advancing at $bytes for ${now() - lastChange} ms (dtls=${st.dtlsState} pair=${st.localCandidateType}/${st.remoteCandidateType})")
                }
                if (bytes > 0 && bytes == last && now() - lastChange >= MEDIA_STALL_MS) {
                    lock.withLock {
                        if (!c.ended && current === c) {
                            log("no audio received for ${now() - lastChange} ms: ending ${c.id}")
                            finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
                        }
                    }
                    return@launch
                }
            }
        }
    }

    /** Decision 054: no verified media [MEDIA_WATCHDOG_MS] after the call started → torn down, visibly. */
    private fun watchdog(c: Call) = timer(c, "watchdog", MEDIA_WATCHDOG_MS) { x ->
        if (x.phase != CallPhase.ACTIVE && x.phase != CallPhase.RECONNECTING) {
            log("watchdog: no media for ${x.id} after $MEDIA_WATCHDOG_MS ms")
            failLocked(x)
        }
    }

    private fun timer(c: Call, name: String, ms: Long, fire: suspend (Call) -> Unit) {
        c.timers.remove(name)?.cancel()
        c.timers[name] = scope.launch {
            delay(ms)
            lock.withLock {
                if (!c.ended && current === c) fire(c)
            }
        }
    }
}
