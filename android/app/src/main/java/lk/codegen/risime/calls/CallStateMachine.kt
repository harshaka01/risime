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
    /** An ephemeral envelope with `call:signal` (`ring` = [CallEnvelope.ringFor]). */
    suspend fun signal(conversationId: String, peer: String, env: CallEnvelope.Env): SignalOutcome

    /** The durable `call_end` (§16.2): an outbox row that is also this device's call-history line. */
    suspend fun end(conversationId: String, peer: String, env: CallEnvelope.End)
}

/** Per-device memory of call ids (§16.3 dedupe, 24 h, persisted) and how this device took part. */
data class CallMark(val callId: String, val rang: Boolean = false, val answered: Boolean = false, val ended: Boolean = false, val at: Long = 0)

interface CallMarks {
    suspend fun get(callId: String): CallMark?

    suspend fun put(mark: CallMark)
}

/** Platform facts the machine asks for. */
interface CallEnvironment {
    /** §16.5 (android R9): `AudioManager.mode` is `MODE_IN_CALL` or `MODE_IN_COMMUNICATION` (another app's call). */
    fun audioBusy(): Boolean = false

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
)

enum class CallPhase { CALLING, RINGING_OUT, RINGING_IN, ANSWERING, CONNECTING, ACTIVE, RECONNECTING, ENDED }

/** Why a call ended, for the short line the UI shows (names are filled in by the UI). */
enum class CallNotice {
    CALL_ENDED, DECLINED, BUSY, NO_ANSWER, ANSWERED_ELSEWHERE, CANT_CONNECT, NOT_READY, NOT_E2EE, RATE_LIMITED, NOT_FRIENDS, IN_ANOTHER_CALL,
}

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
)

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
    }

    private var current: Call? = null

    /** §16.5 sibling rule: my user is calling these peers (another device of mine): peer → (call id, until). */
    private val siblingCalling = HashMap<String, Pair<String, Long>>()

    // ---------------------------------------------------------------- public inputs

    /** The call button (the UI checked `calls_ready`, e2ee and RECORD_AUDIO). False = busy already. */
    suspend fun placeCall(conversationId: String): Boolean {
        val peer = dmPeer(conversationId, me) ?: return false
        // Calling someone who is ringing this device right now: both want the call, so answer it (as glare would).
        val ringingFromPeer = lock.withLock { current?.takeIf { !it.ended && !it.outgoing && it.phase == CallPhase.RINGING_IN && it.peer.equals(peer, true) }?.id }
        if (ringingFromPeer != null) return answer(ringingFromPeer)
        val call = lock.withLock {
            if (current?.let { !it.ended } == true || platform.audioBusy()) {
                publishNotice(conversationId, peer, true, CallNotice.IN_ANOTHER_CALL)
                return false
            }
            Call(newCallId(), conversationId, peer, outgoing = true, phase = CallPhase.CALLING).also {
                current = it
                publish(it)
            }
        }
        marks.put(CallMark(call.id, at = now()))
        // §16.4 step 1: TURN (≤ 3 s, else STUN only), a fresh peer connection, the offer at once (S-a).
        val servers = withTimeoutOrNull(3_000) { runCatching { platform.iceServers() }.getOrDefault(emptyList()) } ?: emptyList()
        val offer = lock.withLock {
            if (current !== call || call.ended) return true
            val s = openSession(call, servers)
            val sdp = runCatching { s.createOffer() }.getOrElse {
                log("createOffer failed: ${it.message}")
                finish(call, CallNotice.CANT_CONNECT, sendEnd = null)
                return true
            }
            SdpRules.validate(sdp, SdpRules.Role.OFFER)?.let {
                log("own offer invalid: $it")
                finish(call, CallNotice.CANT_CONNECT, sendEnd = null)
                return true
            }
            CallEnvelope.Offer(call.id, sdp, iso(serverNow()))
        }
        val r = signals.signal(call.conv, call.peer, offer)
        lock.withLock {
            if (current !== call || call.ended) return true
            when (r) {
                SignalOutcome.Ok -> {
                    call.offerSent = true
                    // §16.4: ring timeout 45 s after the offer.
                    timer(call, "ring", RING_MS) { c -> finish(c, CallNotice.NO_ANSWER, sendEnd = CallEnvelope.R_TIMEOUT) }
                    scheduleIceFlush(call)
                }
                is SignalOutcome.Refused -> finish(
                    call,
                    when (r.reason) {
                        lk.codegen.risime.net.CallErrors.CALLS_NOT_READY -> CallNotice.NOT_READY
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

    /** Answer (an activity, after RECORD_AUDIO): only for a call ringing on this device. */
    suspend fun answer(callId: String? = null): Boolean {
        val call = lock.withLock {
            val c = current?.takeIf { !it.ended && it.phase == CallPhase.RINGING_IN && (callId == null || it.id == callId) } ?: return false
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
            val s = openSession(call, servers)
            val sdp = runCatching {
                s.setRemote(call.offerSdp!!, isOffer = true)
                call.remoteSet = true
                call.remoteBuffer.forEach(s::addRemoteCandidate)
                call.remoteBuffer.clear()
                s.createAnswer()
            }.getOrElse {
                log("answer failed: ${it.message}")
                finish(call, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
                return
            }
            SdpRules.validate(sdp, SdpRules.Role.ANSWER)?.let {
                log("own answer invalid: $it")
                finish(call, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
                return
            }
            CallEnvelope.Answer(call.id, call.offerDevice!!, sdp)
        }
        val r = signals.signal(call.conv, call.peer, answer)
        lock.withLock {
            if (current !== call || call.ended) return
            if (r != SignalOutcome.Ok) {
                log("call_answer not sent: $r")
                finish(call, CallNotice.CANT_CONNECT, sendEnd = null)
                return
            }
            call.offerSent = true
            scheduleIceFlush(call)
            // android R4: 10 s for a call_accepted naming this device.
            timer(call, "accept", ACCEPT_WAIT_MS) { c ->
                finish(c, if (c.siblingAnswered) CallNotice.ANSWERED_ELSEWHERE else CallNotice.CALL_ENDED, sendEnd = null)
            }
        }
    }

    /** Decline (a ringing call) or hang up (any other phase). */
    suspend fun hangUp() {
        lock.withLock {
            val c = current?.takeIf { !it.ended } ?: return
            val reason = when {
                c.phase == CallPhase.RINGING_IN -> CallEnvelope.R_DECLINED
                c.connectedAt != null -> CallEnvelope.R_HANGUP
                c.outgoing && !c.offerSent -> null // nothing went out yet
                c.outgoing && c.selected == null -> CallEnvelope.R_CANCELLED
                else -> CallEnvelope.R_HANGUP
            }
            finish(c, CallNotice.CALL_ENDED, sendEnd = reason)
        }
    }

    suspend fun setMuted(muted: Boolean) {
        lock.withLock {
            val c = current?.takeIf { !it.ended } ?: return
            c.muted = muted
            c.session?.setMuted(muted)
            publish(c)
        }
    }

    /** Telecom/the OS ended the call (a cellular call took over, the user ended it from the system UI). */
    suspend fun onSystemDisconnect() = hangUp()

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
            when (env) {
                is CallEnvelope.Offer -> if (env.restart) onRestartOffer(s, env) else onOffer(s, env, own, markedEnded)
                is CallEnvelope.Ringing -> onRinging(s, own)
                is CallEnvelope.Answer -> onAnswer(s, env, own)
                is CallEnvelope.Accepted -> onAccepted(s, env, own)
                is CallEnvelope.Ice -> onIce(s, env, own)
                is CallEnvelope.Busy -> onBusy(s, own)
                is CallEnvelope.Cancel -> onCancel(s, own)
                is CallEnvelope.End -> log("call_end in a call_signal event: dropped")
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
            scope.launch { signals.signal(loser.conv, loser.peer, CallEnvelope.Cancel(loser.id)) }
            val call = incoming(s, env)
            call.phase = CallPhase.ANSWERING
            marks.put(CallMark(env.callId, rang = false, answered = true, at = now()))
            publish(call)
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
        if (cur != null || platform.audioBusy()) {
            log("offer ${env.callId}: busy")
            marks.put(CallMark(env.callId, ended = true, at = now()))
            scope.launch { signals.signal(s.conversationId, peer, CallEnvelope.Busy(env.callId)) }
            return
        }
        val call = incoming(s, env)
        marks.put(CallMark(env.callId, at = now()))
        // §16.4 ring validity: server_ts + 45 s.
        val left = s.serverTsMs + RING_MS - sn
        timer(call, "ring", left.coerceAtLeast(0)) { c ->
            log("ring validity over for ${c.id}")
            finish(c, notice = null, sendEnd = null)
        }
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
        current = call
        return call
    }

    private suspend fun startRinging(call: Call) {
        lock.withLock {
            if (current !== call || call.ended || call.phase != CallPhase.RINGING_IN) return
            publish(call)
        }
        marks.put((marks.get(call.id) ?: CallMark(call.id)).copy(rang = true, at = now()))
        signals.signal(call.conv, call.peer, CallEnvelope.Ringing(call.id))
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
            // The answer to an ICE restart (§16.2): the same fingerprint as the first SDP (§16.10 d).
            if (c.phase != CallPhase.RECONNECTING && c.phase != CallPhase.ACTIVE) return
            if (!SdpRules.sameFingerprint(SdpRules.fingerprint(env.sdp), c.pinned)) {
                log("dtls_fingerprint_mismatch: restart answer with a different fingerprint")
                finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
                return
            }
            runCatching { c.session?.setRemote(env.sdp, isOffer = false) }.onFailure { log("restart answer: ${it.message}") }
            return
        }
        val session = c.session ?: return
        val fp = SdpRules.fingerprint(env.sdp)
        val remote = if (tamperRemoteFingerprint()) SdpRules.tamperFingerprint(env.sdp) else env.sdp
        c.selected = s.fromDevice.lowercase()
        c.pinned = fp
        c.timers.remove("ring")?.cancel()
        c.phase = CallPhase.CONNECTING
        publish(c)
        val ok = runCatching { session.setRemote(remote, isOffer = false) }.onFailure { log("setRemote(answer) failed: ${it.message}") }.isSuccess
        if (!ok) {
            finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
            return
        }
        c.remoteSet = true
        c.remoteBuffer.forEach(session::addRemoteCandidate)
        c.remoteBuffer.clear()
        scope.launch { signals.signal(c.conv, c.peer, CallEnvelope.Accepted(c.id, c.selected!!)) }
        // §16.4: 20 s to connect after call_accepted.
        timer(c, "connect", CONNECT_MS) { x -> finish(x, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED) }
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
        timer(c, "connect", CONNECT_MS) { x -> finish(x, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED) }
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
        if (!SdpRules.sameFingerprint(SdpRules.fingerprint(env.sdp), c.pinned)) {
            log("dtls_fingerprint_mismatch: restart offer with a different fingerprint")
            finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
            return
        }
        val session = c.session ?: return
        val answer = runCatching {
            session.setRemote(env.sdp, isOffer = true)
            session.createAnswer()
        }.getOrElse {
            finish(c, CallNotice.CANT_CONNECT, sendEnd = CallEnvelope.R_FAILED)
            return
        }
        scope.launch { signals.signal(c.conv, c.peer, CallEnvelope.Answer(c.id, s.fromDevice.lowercase(), answer)) }
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
        )
        call.session = s
        s.setMuted(call.muted)
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
            val s = c.session ?: return
            val sdp = runCatching { s.createOffer(iceRestart = true) }.getOrNull() ?: return
            CallEnvelope.Offer(c.id, sdp, iso(serverNow()), restart = true, toDevice = c.selected)
        }
        signals.signal(c.conv, c.peer, offer)
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
                }?.let { runCatching { it.stats() }.getOrNull() } ?: DtlsStats(null, null, null)
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
                            c.phase = CallPhase.ACTIVE
                            c.connectedAt = now()
                            c.connectedAtServer = serverNow()
                            c.verified = true
                            timer(c, "max", MAX_CALL_MS) { x -> finish(x, CallNotice.CALL_ENDED, sendEnd = CallEnvelope.R_HANGUP) }
                            publish(c)
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
                signals.signal(c.conv, c.peer, batch)
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
            )
            scope.launch { signals.end(c.conv, c.peer, end) }
        }
        if (notice == null || !c.shown) {
            if (_state.value?.callId == c.id) _state.value = null
            return
        }
        _state.value = CallSnapshot(c.id, c.conv, c.peer, c.outgoing, CallPhase.ENDED, c.muted, c.connectedAt, c.verified, notice)
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
        _state.value = CallSnapshot(c.id, c.conv, c.peer, c.outgoing, c.phase, c.muted, c.connectedAt, c.verified, null)
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
