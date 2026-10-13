package ai.risicloud.risime.calls

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** v1.44 §39.3 the call conversation the move goes into. */
data class MoveGroup(
    val conversationId: String,
    /** The call was Official but its Official conversation wasn't active in time: "Continuing as a Private call". */
    val privateFallback: Boolean = false,
)

/** What moving a call needs from the app (the container implements it; a fake in tests). */
interface MovePort {
    /**
     * §39.3 step 1: a new group chat of me and [members] (the peer first), named from first names;
     * for an [official] call also its Official conversation (≤ 15 s). Null = it couldn't be created.
     */
    suspend fun createCallGroup(members: List<String>, official: Boolean): MoveGroup?

    /** §39.3 peer step 2: [conv]'s MLS state is on this device with [identities] among its leaves (catching up, ≤ [timeoutMs]). */
    suspend fun awaitGroup(conv: String, identities: List<String>, timeoutMs: Long): Boolean

    /** The users of [conv] (from its MLS-backed member list), for "Kamal is adding Sunil…". */
    suspend fun members(conv: String): List<String> = emptyList()

    suspend fun displayName(userId: String): String

    /** The platform call carries on: the 1:1 call [fromCallId]'s Telecom call becomes [toCallId]'s (or, [on] false, that link is dropped). */
    fun linkPlatformCall(fromCallId: String, toCallId: String, on: Boolean) = Unit
}

/** v1.44 §39.3 the move as the call screen shows it ("Adding Sunil…", "Couldn't add people…"). */
data class MoveUi(val callId: String, val line: String, val failed: Boolean = false)

/**
 * v1.44 §39.3 moves a connected 1:1 call into a group call without dropping the two people already
 * talking: the room comes up in hold on both phones while the peer-to-peer call carries the audio;
 * each side switches when it sees the other in the room (an MLS member), then the P2P media closes.
 * Pure Kotlin over the two machines and [MovePort]; timers on [scope] (virtual time in tests).
 */
class CallMover(
    private val scope: CoroutineScope,
    private val me: () -> String?,
    private val myDevice: () -> String,
    private val oneToOne: () -> CallStateMachine?,
    private val group: () -> GroupCallMachine?,
    private val port: MovePort,
    private val log: (String) -> Unit = {},
    private val newCallId: () -> String = { UUID.randomUUID().toString() },
    private val moveMs: Long = CallStateMachine.MOVE_MS,
    private val groupWaitMs: Long = GROUP_WAIT_MS,
    private val createMs: Long = CREATE_MS,
    private val noticeMs: Long = NOTICE_MS,
) {
    companion object {
        /** §39.3 peer step 2: the group's Welcome must be here within this. */
        const val GROUP_WAIT_MS = 20_000L

        /** §39.3 step 1: creating the group chat (and its Official) may take this long. */
        const val CREATE_MS = 30_000L
        const val NOTICE_MS = 4_000L

        fun couldntAdd(peerName: String) = "Couldn't add people. You're still in the call with $peerName."
        fun adding(names: List<String>) = "Adding ${joinNames(names)}…"
        fun beingAdded(adder: String, names: List<String>) = if (names.isEmpty()) "$adder is adding people…" else "$adder is adding ${joinNames(names)}…"
        const val PRIVATE_FALLBACK = "Continuing as a Private call"

        fun joinNames(names: List<String>): String = when (names.size) {
            0 -> "people"
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }

    private val lock = Mutex()
    private val _ui = MutableStateFlow<MoveUi?>(null)
    val ui: StateFlow<MoveUi?> = _ui.asStateFlow()

    private class Move(val callId: String, val adder: Boolean) {
        val ready = CompletableDeferred<Boolean>()
        var roomCallId: String? = null
        var failReason: String? = null
    }

    @Volatile private var active: Move? = null

    /** A move is under way for [callId] (or any, when null). */
    fun moving(callId: String? = null): Boolean = active?.let { callId == null || it.callId == callId } == true

    // ---------------------------------------------------------------- the adder (§39.3 steps 1–6)

    /** "Add" in the picker: move the current 1:1 call into a group call with [users]. False = it can't start. */
    suspend fun add(users: List<String>): Boolean {
        val m1 = oneToOne() ?: return false
        val g = group() ?: return false
        val myId = me() ?: return false
        val invitees = users.map(String::lowercase).filter { !it.equals(myId, true) }.distinct()
        if (invitees.isEmpty()) return false
        val move = lock.withLock {
            if (active != null) return false
            val info = m1.beginMove(adder = true) ?: return false
            Move(info.callId, adder = true).also { active = it } to info
        }
        val (mv, info) = move
        scope.launch { runAdder(mv, info, invitees, m1, g) }
        return true
    }

    private suspend fun runAdder(mv: Move, info: MoveInfo, invitees: List<String>, m1: CallStateMachine, g: GroupCallMachine) {
        val peerName = runCatching { port.displayName(info.peer) }.getOrDefault("them")
        val names = invitees.map { runCatching { port.displayName(it) }.getOrDefault("…") }
        _ui.value = MoveUi(info.callId, adding(names))
        val grp = withTimeoutOrNull(createMs) { runCatching { port.createCallGroup(listOf(info.peer.lowercase()) + invitees, info.official) }.getOrNull() }
        if (grp == null) return giveUp(mv, m1, g, peerName, "the group chat wasn't created")
        if (grp.privateFallback) _ui.value = MoveUi(info.callId, PRIVATE_FALLBACK)
        val room = newCallId()
        mv.roomCallId = room
        val media = if (info.video) CallEnvelope.MEDIA_VIDEO else CallEnvelope.MEDIA_AUDIO
        port.linkPlatformCall(info.callId, room, true)
        val hold = HoldSpec(
            peerIdentity = "${info.peer.lowercase()}/${info.peerDevice.lowercase()}",
            muted = info.muted,
            beforeRing = {
                val r = m1.sendEscalate(CallEnvelope.Escalate(info.callId, info.peerDevice.lowercase(), CallEnvelope.ESC_MOVE, grp.conversationId, room, media))
                log("move ${info.callId}: call_escalate move sent: $r")
                if (r !is SignalOutcome.Ok) mv.ready.complete(false)
            },
            onReady = { mv.ready.complete(true) },
            onFailed = { mv.ready.complete(false) },
        )
        if (!g.startHeld(grp.conversationId, room, info.video, info.wantCamera, invitees, hold)) return giveUp(mv, m1, g, peerName, "another group call exists")
        when (awaitSwitch(mv, m1, info.callId)) {
            Outcome.READY -> {
                // Step 5: the P2P media closes and the durable `call_end` `moved` goes out; then the room carries the audio.
                m1.movedOut(info.callId, sendEnd = true, movedTo = CallEnvelope.MovedTo(grp.conversationId, room))
                g.release(room)
                log("move ${info.callId}: switched to ${grp.conversationId} call $room")
                done(mv, null)
            }
            Outcome.ENDED -> {
                log("move ${info.callId}: the 1:1 call ended during the move")
                g.abortHeld(room)
                port.linkPlatformCall(info.callId, room, false)
                done(mv, null)
            }
            Outcome.FAILED -> giveUp(mv, m1, g, peerName, mv.failReason ?: "the peer didn't arrive in the room")
        }
    }

    // ---------------------------------------------------------------- the peer (§39.3 peer steps 1–4)

    /** A valid `call_escalate` from the selected peer device (the 1:1 machine checked who and where). */
    suspend fun onEscalate(env: CallEnvelope.Escalate, fromDevice: String) {
        if (env.state == CallEnvelope.ESC_FAILED) {
            val mv = active?.takeIf { it.adder && it.callId == env.callId } ?: return
            log("move ${env.callId}: the peer couldn't follow (${env.reason})")
            mv.failReason = "the peer couldn't follow (${env.reason})"
            mv.ready.complete(false)
            return
        }
        val m1 = oneToOne() ?: return
        val g = group() ?: return
        val conv = env.conversationId ?: return
        val room = env.roomCallId ?: return
        val media = env.media ?: return
        val claimed = lock.withLock {
            if (active != null) null else m1.beginMove(env.callId, adder = false)?.let { info -> Move(info.callId, adder = false).also { it.roomCallId = room; active = it } to info }
        }
        if (claimed == null) {
            log("move ${env.callId}: can't follow (busy)")
            m1.sendEscalate(CallEnvelope.Escalate(env.callId, fromDevice.lowercase(), CallEnvelope.ESC_FAILED, reason = CallEnvelope.ESC_BUSY))
            return
        }
        val (mv, info) = claimed
        scope.launch { runPeer(mv, info, conv, room, media, m1, g) }
    }

    private suspend fun runPeer(mv: Move, info: MoveInfo, conv: String, room: String, media: String, m1: CallStateMachine, g: GroupCallMachine) {
        val adderName = runCatching { port.displayName(info.peer) }.getOrDefault("Someone")
        _ui.value = MoveUi(info.callId, beingAdded(adderName, emptyList()))
        val myId = me() ?: return
        val peerIdentity = "${info.peer.lowercase()}/${info.peerDevice.lowercase()}"
        val mine = "${myId.lowercase()}/${myDevice().lowercase()}"
        if (!port.awaitGroup(conv, listOf(peerIdentity, mine), groupWaitMs)) return peerFail(mv, info, m1, g, CallEnvelope.ESC_NO_GROUP, adderName)
        val others = runCatching { port.members(conv) }.getOrDefault(emptyList()).map(String::lowercase).filter { it != myId.lowercase() && it != info.peer.lowercase() }
        if (others.isNotEmpty()) _ui.value = MoveUi(info.callId, beingAdded(adderName, others.map { runCatching { port.displayName(it) }.getOrDefault("…") }))
        port.linkPlatformCall(info.callId, room, true)
        val hold = HoldSpec(
            peerIdentity = peerIdentity,
            muted = info.muted,
            onReady = { mv.ready.complete(true) },
            onFailed = { if (!mv.ready.isCompleted) { mv.failReason = CallEnvelope.ESC_ROOM; mv.ready.complete(false) } },
        )
        if (!g.joinHeld(conv, room, media, info.peer, info.wantCamera, hold)) return peerFail(mv, info, m1, g, CallEnvelope.ESC_BUSY, adderName)
        when (awaitSwitch(mv, m1, info.callId)) {
            Outcome.READY -> {
                // Peer step 3: no call_end from this side (the adder's `moved` is the line).
                m1.movedOut(info.callId, sendEnd = false, movedTo = null)
                g.release(room)
                log("move ${info.callId}: followed into $conv call $room")
                done(mv, null)
            }
            Outcome.ENDED -> {
                g.abortHeld(room)
                port.linkPlatformCall(info.callId, room, false)
                done(mv, null)
            }
            Outcome.FAILED -> peerFail(mv, info, m1, g, mv.failReason ?: CallEnvelope.ESC_ROOM, adderName)
        }
    }

    /**
     * §39.3: the adder's `call_end` `moved` came while this side still waits (the adder has switched):
     * switch now (called under the 1:1 machine's lock, before that call ends).
     */
    fun onMovedByPeer(callId: String) {
        val mv = active?.takeIf { !it.adder && it.callId == callId } ?: return
        log("move $callId: the adder switched: following")
        mv.ready.complete(true)
    }

    private suspend fun peerFail(mv: Move, info: MoveInfo, m1: CallStateMachine, g: GroupCallMachine, reason: String, adderName: String) {
        log("move ${info.callId}: can't follow ($reason)")
        m1.sendEscalate(CallEnvelope.Escalate(info.callId, info.peerDevice.lowercase(), CallEnvelope.ESC_FAILED, reason = reason))
        mv.roomCallId?.let {
            g.abortHeld(it)
            port.linkPlatformCall(info.callId, it, false)
        }
        m1.endMove(info.callId)
        done(mv, MoveUi(info.callId, couldntAdd(adderName).replace("Couldn't add people", "Couldn't join the group call"), failed = true))
    }

    // ---------------------------------------------------------------- shared

    private enum class Outcome { READY, FAILED, ENDED }

    /** Ready (the other side is in the room, or the adder switched), failed, or the 1:1 call ended first; at most [moveMs]. */
    private suspend fun awaitSwitch(mv: Move, m1: CallStateMachine, callId: String): Outcome = withTimeoutOrNull(moveMs) {
        coroutineScope {
            val ended = async { m1.state.first { it == null || it.callId != callId || it.phase == CallPhase.ENDED } }
            // `ready` first: a peer whose 1:1 call ends because the adder switched still switches (biased select).
            val r = select<Outcome> {
                mv.ready.onAwait { if (it) Outcome.READY else Outcome.FAILED }
                ended.onAwait { Outcome.ENDED }
            }
            ended.cancel()
            r
        }
    } ?: Outcome.FAILED.also { log("move $callId: no switch within $moveMs ms") }

    private suspend fun giveUp(mv: Move, m1: CallStateMachine, g: GroupCallMachine, peerName: String, why: String) {
        log("move ${mv.callId}: giving up: $why")
        mv.roomCallId?.let {
            g.abortHeld(it)
            port.linkPlatformCall(mv.callId, it, false)
        }
        m1.endMove(mv.callId)
        done(mv, MoveUi(mv.callId, couldntAdd(peerName), failed = true))
    }

    private suspend fun done(mv: Move, notice: MoveUi?) {
        lock.withLock { if (active === mv) active = null }
        _ui.value = notice
        if (notice != null) scope.launch {
            delay(noticeMs)
            if (_ui.value === notice) _ui.value = null
        }
    }
}
