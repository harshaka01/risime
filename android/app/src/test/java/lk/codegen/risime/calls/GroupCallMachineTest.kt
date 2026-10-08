package lk.codegen.risime.calls

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.mls.CallKey
import lk.codegen.risime.data.mls.CallKeys
import lk.codegen.risime.data.mls.CallKeysException
import lk.codegen.risime.net.CallsRoomReply
import lk.codegen.risime.net.CallsRoomRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** A key sink that records what the machine installed (index → identity → key text) and the own index. */
class FakeKeySink : FrameKeySink {
    val keys = HashMap<Int, HashMap<String, String>>()
    val ownIndexes = CopyOnWriteArrayList<Int>()

    @Synchronized override fun setKey(identity: String, keyText: String, index: Int) {
        keys.getOrPut(index) { HashMap() }[identity] = keyText
    }

    override fun setOwnIndex(index: Int) {
        ownIndexes += index
    }
}

class FakeSfuSession(override val localIdentity: String, val listener: SfuListener) : SfuSession {
    override val keys = FakeKeySink()
    var micPublished = false
    var muted = false
    var disconnected = false
    val playable = HashMap<String, Boolean>()
    val camera = CopyOnWriteArrayList<Boolean>()

    override suspend fun publishMic(): Boolean = true.also { micPublished = true }
    override fun setMicMuted(muted: Boolean) { this.muted = muted }
    override suspend fun setCamera(on: Boolean): Boolean = true.also { camera += on }
    override fun setPlayable(identity: String, playable: Boolean) { this.playable[identity] = playable }
    override fun disconnect() { disconnected = true }

    /** The room now has [remote] besides this device. */
    fun room(vararg remote: SfuParticipant) = listener.onParticipants(listOf(SfuParticipant(localIdentity, local = true, hasAudio = true, cryptor = CryptorState.OK)) + remote)
}

class FakeSfu : SfuConnector {
    override val available = true
    var fail = false
    val sessions = CopyOnWriteArrayList<FakeSfuSession>()
    val connects = CopyOnWriteArrayList<SfuConnect>()

    override suspend fun connect(p: SfuConnect, listener: SfuListener): SfuSession {
        connects += p
        if (fail) throw IllegalStateException("unreachable")
        return FakeSfuSession(identityOf(p.token), listener).also { sessions += it }
    }

    companion object {
        fun identityOf(token: String) = token.removePrefix("token-for-")
    }
}

class FakeGroupPort(private val identity: String) : GroupCallPort {
    var epoch: Long? = 7
    var members = listOf(identity, "u-b/b1", "u-c/c1")
    var room: (String) -> RoomOutcome = { _ -> RoomOutcome.Ok(CallsRoomReply("wss://x/livekit", "room", identity, "token-for-$identity", "2026-10-08T10:10:00.000Z", 32)) }
    val rooms = CopyOnWriteArrayList<String>()
    val signals = CopyOnWriteArrayList<CallEnvelope.Env>()
    val started = CopyOnWriteArrayList<GroupCallEnvelope>()
    val ended = CopyOnWriteArrayList<GroupCallEnvelope>()
    val over = CopyOnWriteArrayList<String>()
    var catchUps = 0
    var busy = false
    var signalResult: SignalOutcome = SignalOutcome.Ok

    override suspend fun iceServers() = listOf(IceServer(listOf("turn:203.0.113.1:3478"), "u", "p"))
    override suspend fun catchUp(conv: String) { catchUps++ }
    override suspend fun epoch(conv: String) = epoch
    override suspend fun frameKeys(conv: String, callId: String): CallKeys {
        val e = epoch ?: throw CallKeysException(CallKeysException.Kind.UnknownGroup, "gone")
        return CallKeys(e, FrameKeyRing.keyIndex(e), members.map { CallKey(it, "$it@$e".toByteArray().copyOf(32)) })
    }
    override suspend fun room(conv: String, callId: String, media: String, action: String): RoomOutcome = room(action).also { rooms += action }
    override suspend fun signal(conv: String, env: CallEnvelope.Env, media: String): SignalOutcome = signalResult.also { signals += env }
    override suspend fun started(conv: String, env: GroupCallEnvelope) { started += env }
    override suspend fun ended(conv: String, env: GroupCallEnvelope) { ended += env }
    override suspend fun over(conv: String, callId: String) { over += callId }
    override fun busy() = busy
}

class MemMarks : CallMarks {
    val m = HashMap<String, CallMark>()
    override suspend fun get(callId: String) = m[callId]
    override suspend fun put(mark: CallMark) { m[mark.callId] = mark }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GroupCallMachineTest {
    private val conv = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val callId = "3c8e1f4a-9b2d-4e7f-8a61-5d0c2b9e7f13"

    private class Rig(val m: GroupCallMachine, val port: FakeGroupPort, val sfu: FakeSfu, val marks: MemMarks) {
        val session get() = sfu.sessions.last()
    }

    private fun TestScope.rig(user: String = "u-a", device: String = "a1", scope: CoroutineScope = backgroundScope): Rig {
        val port = FakeGroupPort("$user/$device")
        val sfu = FakeSfu()
        val marks = MemMarks()
        val clock = { testScheduler.currentTime + 1_791_451_200_000L }
        val m = GroupCallMachine(user, device, scope, sfu, port, marks, now = clock, newCallId = { callId })
        return Rig(m, port, sfu, marks)
    }

    private fun TestScope.offer(r: Rig, from: String = "u-b", fromDevice: String = "b1", inPage: Boolean = false, media: String = "audio", ageMs: Long = 0) {
        val ts = testScheduler.currentTime + 1_791_451_200_000L - ageMs
        backgroundScope.launchNow { r.m.onSignal(InboundCall(conv, from, fromDevice, ts, CallEnvelope.SfuOffer(callId, media, CallStateMachine.iso(ts)), inPage, media)) }
    }

    private fun CoroutineScope.launchNow(block: suspend () -> Unit) = this.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { block() }

    private fun remote(id: String, cryptor: CryptorState = CryptorState.OK, encrypted: Boolean = true, speaking: Boolean = false) =
        SfuParticipant(id, local = false, hasAudio = true, encrypted = encrypted, cryptor = cryptor, speaking = speaking)

    @Test fun startConnectsInstallsKeysPublishesThenStartedAndRing() = runTest {
        val r = rig()
        assertTrue(r.m.start(conv))
        runCurrent()
        assertEquals(listOf(CallsRoomRequest.START), r.port.rooms)
        assertEquals(1, r.port.catchUps) // K4 before deriving
        val s = r.session
        assertTrue(s.micPublished)
        // epoch 7 → index 7, every member's key there, own sending switched.
        assertEquals(setOf("u-a/a1", "u-b/b1", "u-c/c1"), s.keys.keys[7]!!.keys)
        assertEquals(FrameKeyRing.keyText("u-b/b1@7".toByteArray().copyOf(32)), s.keys.keys[7]!!["u-b/b1"])
        assertEquals(listOf(7), s.keys.ownIndexes)
        // TURN credentials are LiveKit's ICE servers (android A5).
        assertEquals("turn:203.0.113.1:3478", r.sfu.connects.single().iceServers.single().urls.single())
        // Order: started (durable) then the ring.
        assertEquals(GroupCallEnvelope.started(callId, "audio"), r.port.started.single())
        val ring = r.port.signals.single() as CallEnvelope.SfuOffer
        assertEquals(callId, ring.callId)
        assertEquals(CallPhase.RINGING_OUT, r.m.state.value!!.phase)
        assertTrue(r.m.state.value!!.group)

        // B joins: active; the badge only once B's track decrypts.
        s.room(remote("u-b/b1", CryptorState.NEW))
        runCurrent()
        assertEquals(CallPhase.ACTIVE, r.m.state.value!!.phase)
        assertFalse(r.m.state.value!!.verified)
        s.room(remote("u-b/b1", CryptorState.OK, speaking = true))
        runCurrent()
        val st = r.m.state.value!!
        assertTrue(st.verified)
        assertTrue(st.members.single { it.userId == "u-b" }.speaking)
        assertEquals(true, s.playable["u-b/b1"])

        // Leaving while B is still there sends no `ended`.
        advanceTimeBy(60_000)
        r.m.hangUp()
        runCurrent()
        assertTrue(r.port.ended.isEmpty())
        assertTrue(s.disconnected)
        // K10: every installed key overwritten.
        assertFalse(s.keys.keys[7]!!["u-b/b1"] == FrameKeyRing.keyText("u-b/b1@7".toByteArray().copyOf(32)))
    }

    @Test fun lastOneOutSendsEndedWithDuration() = runTest {
        val r = rig()
        r.m.start(conv)
        runCurrent()
        r.session.room(remote("u-b/b1"))
        runCurrent()
        advanceTimeBy(723_000)
        r.session.room() // B left
        runCurrent()
        r.m.hangUp()
        runCurrent()
        val e = r.port.ended.single()
        assertEquals(GroupCallEnvelope.R_HANGUP, e.reason)
        assertEquals(723L, e.durationS)
        assertTrue(e.connectedAt != null)
    }

    @Test fun everyoneElseLeftEndsTheCallAfterTenSeconds() = runTest {
        val r = rig()
        r.m.start(conv)
        runCurrent()
        r.session.room(remote("u-b/b1"))
        runCurrent()
        r.session.room()
        runCurrent()
        advanceTimeBy(GroupCallMachine.ALONE_MS - 1)
        assertEquals(CallPhase.ACTIVE, r.m.state.value!!.phase)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(CallPhase.ENDED, r.m.state.value!!.phase)
        assertEquals(GroupCallEnvelope.R_HANGUP, r.port.ended.single().reason)
    }

    @Test fun starterTimeoutCancelsAndSendsTimeout() = runTest {
        val r = rig()
        r.m.start(conv)
        runCurrent()
        advanceTimeBy(GroupCallMachine.RING_MS + 1)
        runCurrent()
        assertEquals(CallEnvelope.Cancel(callId, CallEnvelope.CANCEL_ENDED), r.port.signals.last())
        val e = r.port.ended.single()
        assertEquals(GroupCallEnvelope.R_TIMEOUT, e.reason)
        assertNull(e.durationS)
        assertEquals(CallNotice.NO_ANSWER, r.m.state.value!!.notice)
        assertTrue(r.session.disconnected)
    }

    @Test fun incomingRingAnswerJoinsAndSendsCallMember() = runTest {
        val r = rig()
        offer(r)
        runCurrent()
        assertEquals(CallPhase.RINGING_IN, r.m.state.value!!.phase)
        assertEquals("u-b", r.m.state.value!!.peerUserId)
        assertTrue(r.m.answer(camera = false))
        runCurrent()
        assertEquals(listOf(CallsRoomRequest.JOIN), r.port.rooms)
        assertEquals(CallEnvelope.Member(callId, CallEnvelope.MEMBER_JOINED), r.port.signals.single())
        assertTrue(r.port.started.isEmpty())
        r.session.room(remote("u-b/b1"))
        runCurrent()
        assertEquals(CallPhase.ACTIVE, r.m.state.value!!.phase)
    }

    @Test fun siblingsStopDeclineCancelPinningStaleAndBusy() = runTest {
        // My other device joined: this one stops ringing.
        val a = rig()
        offer(a)
        runCurrent()
        backgroundScope.launchNow { a.m.onSignal(InboundCall(conv, "u-a", "a2", 0, CallEnvelope.Member(callId, CallEnvelope.MEMBER_JOINED))) }
        runCurrent()
        assertEquals(CallNotice.ANSWERED_ELSEWHERE, a.m.state.value?.notice)

        // Another member's call_member doesn't stop my ring; a cancel from another device is ignored; the offer's device cancels.
        val b = rig()
        offer(b)
        runCurrent()
        backgroundScope.launchNow { b.m.onSignal(InboundCall(conv, "u-c", "c1", 0, CallEnvelope.Member(callId, CallEnvelope.MEMBER_JOINED))) }
        backgroundScope.launchNow { b.m.onSignal(InboundCall(conv, "u-c", "c1", 0, CallEnvelope.Cancel(callId, "ended"))) }
        runCurrent()
        assertEquals(CallPhase.RINGING_IN, b.m.state.value!!.phase)
        backgroundScope.launchNow { b.m.onSignal(InboundCall(conv, "u-b", "b1", 0, CallEnvelope.Cancel(callId, "ended"))) }
        runCurrent()
        assertNull(b.m.state.value)

        // Decline sends call_member declined, nothing else.
        val c = rig()
        offer(c)
        runCurrent()
        c.m.hangUp()
        runCurrent()
        assertEquals(listOf<CallEnvelope.Env>(CallEnvelope.Member(callId, CallEnvelope.MEMBER_DECLINED)), c.port.signals.toList())
        assertTrue(c.port.rooms.isEmpty())

        // Stale (over 45 s) and busy offers never ring; my own user's offer never rings here.
        val d = rig()
        offer(d, ageMs = 46_000)
        runCurrent()
        assertNull(d.m.state.value)
        val e = rig()
        e.port.busy = true
        offer(e)
        runCurrent()
        assertNull(e.m.state.value)
        val f = rig()
        offer(f, from = "u-a", fromDevice = "a2")
        runCurrent()
        assertNull(f.m.state.value)
    }

    @Test fun pageOffersRingAfterThePageAndTheRingRunsOut() = runTest {
        val r = rig()
        offer(r, inPage = true)
        runCurrent()
        assertNull(r.m.state.value)
        r.m.onPageEnd()
        assertEquals(CallPhase.RINGING_IN, r.m.state.value!!.phase)
        advanceTimeBy(GroupCallMachine.RING_MS + 1)
        runCurrent()
        assertNull(r.m.state.value)
    }

    @Test fun joinOfAnEndedOrFullRoom() = runTest {
        val r = rig()
        r.port.room = { RoomOutcome.Ended }
        r.m.join(conv, callId, "audio", "u-b")
        runCurrent()
        assertEquals(CallNotice.ROOM_ENDED, r.m.state.value!!.notice)
        assertEquals(listOf(callId), r.port.over.toList())
        val f = rig()
        f.port.room = { RoomOutcome.Full }
        f.m.join(conv, callId, "audio", "u-b")
        runCurrent()
        assertEquals(CallNotice.CALL_FULL, f.m.state.value!!.notice)
        val u = rig()
        u.sfu.fail = true
        u.m.start(conv)
        runCurrent()
        assertEquals(CallNotice.CANT_CONNECT, u.m.state.value!!.notice)
    }

    @Test fun rekeyOnEveryEpochWithTheTenSecondOverlapThenRemoval() = runTest {
        val r = rig()
        r.m.start(conv)
        runCurrent()
        r.session.room(remote("u-b/b1"))
        runCurrent()
        val keys = r.session.keys
        val oldB = keys.keys[7]!!["u-b/b1"]
        // A commit adds u-d: epoch 8.
        r.port.epoch = 8
        r.port.members = r.port.members + "u-d/d1"
        r.m.onGroupChanged(conv)
        runCurrent()
        assertEquals(listOf(7, 8), keys.ownIndexes.toList())
        assertEquals(4, keys.keys[8]!!.size)
        advanceTimeBy(FrameKeyRing.OVERLAP_MS - 100)
        assertEquals(oldB, keys.keys[7]!!["u-b/b1"]) // still valid for late senders
        advanceTimeBy(200)
        runCurrent()
        assertFalse(oldB == keys.keys[7]!!["u-b/b1"]) // wiped
        // The poll also notices an epoch change without the hook.
        r.port.epoch = 9
        advanceTimeBy(GroupCallMachine.EPOCH_POLL_MS + 1)
        runCurrent()
        assertEquals(9, keys.ownIndexes.last())
        // Removed (no group state): the call ends at once.
        r.port.epoch = null
        advanceTimeBy(GroupCallMachine.EPOCH_POLL_MS + 1)
        runCurrent()
        assertEquals(CallNotice.REMOVED, r.m.state.value!!.notice)
        assertTrue(r.session.disconnected)
    }

    @Test fun ghostsAndUnencryptedTracksAreNeverPlayedAndMissingKeysCatchUp() = runTest {
        val r = rig()
        r.m.start(conv)
        runCurrent()
        val before = r.port.catchUps
        r.session.room(remote("u-b/b1"), remote("u-x/ghost"), remote("u-c/c1", encrypted = false))
        runCurrent()
        assertEquals(true, r.session.playable["u-b/b1"])
        assertEquals(false, r.session.playable["u-x/ghost"])
        assertEquals(true, r.session.playable["u-c/c1"]) // a member: K6 is per track (silent, not rendered), K7 unsubscribes ghosts
        val st = r.m.state.value!!
        assertFalse(st.members.single { it.identity == "u-x/ghost" }.member)
        assertTrue(st.members.single { it.identity == "u-c/c1" }.cantVerify)
        assertFalse(st.verified) // C's track can't verify: the badge is withdrawn (K9)
        // A member whose frames have no key: catch up (at most every 3 s).
        r.session.room(remote("u-b/b1", CryptorState.MISSING_KEY))
        runCurrent()
        r.session.room(remote("u-b/b1", CryptorState.MISSING_KEY))
        runCurrent()
        assertEquals(before + 1, r.port.catchUps)
        assertTrue(r.m.state.value!!.members.single { it.identity == "u-b/b1" }.cantVerify)
    }

    @Test fun aTrackThatNeverDecryptsIsCantVerifyAfterFiveSecondsAndWithdrawsTheBadge() = runTest {
        val r = rig()
        r.m.start(conv)
        runCurrent()
        r.session.room(remote("u-b/b1"), remote("u-c/c1", CryptorState.NEW))
        runCurrent()
        assertFalse(r.m.state.value!!.verified) // C hasn't verified yet: no badge
        assertFalse(r.m.state.value!!.members.single { it.identity == "u-c/c1" }.cantVerify)
        val before = r.port.catchUps
        advanceTimeBy(GroupCallMachine.VERIFY_MS + 1)
        r.session.room(remote("u-b/b1"), remote("u-c/c1", CryptorState.NEW))
        runCurrent()
        assertTrue(r.m.state.value!!.members.single { it.identity == "u-c/c1" }.cantVerify)
        assertFalse(r.m.state.value!!.verified)
        assertEquals(before + 1, r.port.catchUps) // and catches up commits
        // C's frames start decrypting: badge back.
        r.session.room(remote("u-b/b1"), remote("u-c/c1"))
        runCurrent()
        assertTrue(r.m.state.value!!.verified)
    }

    @Test fun lostConnectionAfterTwentySecondsAndOneCallAtATime() = runTest {
        val r = rig()
        r.m.start(conv)
        runCurrent()
        r.session.room(remote("u-b/b1"))
        runCurrent()
        assertTrue(r.m.active())
        assertFalse(r.m.start(conv)) // already in a call
        r.session.listener.onConnection(SfuConnection.RECONNECTING)
        runCurrent()
        assertEquals(CallPhase.RECONNECTING, r.m.state.value!!.phase)
        advanceTimeBy(GroupCallMachine.LOST_MS + 1)
        runCurrent()
        assertEquals(CallNotice.LOST_CONNECTION, r.m.state.value!!.notice)
    }

    @Test fun ringRefusedWhenNobodyCanJoin() = runTest {
        val r = rig()
        r.port.signalResult = SignalOutcome.Refused(lk.codegen.risime.net.CallErrors.CALLS_NOT_READY)
        r.m.start(conv)
        runCurrent()
        assertEquals(CallNotice.GROUP_NOT_READY, r.m.state.value!!.notice)
        assertTrue(r.session.disconnected)
    }
}
