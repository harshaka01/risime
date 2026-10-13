package ai.risicloud.risime.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ai.risicloud.risime.net.CallsRoomReply
import ai.risicloud.risime.net.CallsRoomRequest
import ai.risicloud.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** v1.44 §39.3: a connected 1:1 call moves into a group call (both sides), and every way it can give up. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallMoverTest {
    private val userA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val userB = "bbbbbbbb-0000-4000-8000-000000000002"
    private val userC = "cccccccc-0000-4000-8000-000000000003"
    private val devA = "a1a1a1a1-0000-4000-8000-000000000001"
    private val devB = "b1b1b1b1-0000-4000-8000-000000000003"
    private val conv = dmConversationId(userA, userB)
    private val grp = "grp:6d2a9c4e-1b7f-4e3a-9c5d-8f0e2a4b6c71"
    private val room = "8e4b2d71-5c3a-4f9e-b0d2-6a1c7e9f3b58"
    private val t0 = 1_791_000_000_000L

    private class FakeMovePort : MovePort {
        var group: MoveGroup? = null
        var groupHere = true
        val created = CopyOnWriteArrayList<List<String>>()
        val links = CopyOnWriteArrayList<Triple<String, String, Boolean>>()
        override suspend fun createCallGroup(members: List<String>, official: Boolean): MoveGroup? = group.also { created += members }
        override suspend fun awaitGroup(conv: String, identities: List<String>, timeoutMs: Long): Boolean {
            if (!groupHere) kotlinx.coroutines.delay(timeoutMs)
            return groupHere
        }
        override suspend fun members(conv: String) = emptyList<String>()
        override suspend fun displayName(userId: String) = when (userId) { "aaaaaaaa-0000-4000-8000-000000000001" -> "Kamal"; "bbbbbbbb-0000-4000-8000-000000000002" -> "Nimal"; else -> "Sunil" }
        override fun linkPlatformCall(fromCallId: String, toCallId: String, on: Boolean) { links += Triple(fromCallId, toCallId, on) }
    }

    private class Side(val dev: CallNet.Dev, val group: GroupCallMachine, val gport: FakeGroupPort, val sfu: FakeSfu, val movePort: FakeMovePort) {
        lateinit var mover: CallMover
        val session get() = sfu.sessions.last()
    }

    private fun TestScope.world(features: List<String> = listOf("switch", "add"), peerFeatures: List<String> = features): Triple<CallNet, Side, Side> {
        val clock = { testScheduler.currentTime + t0 }
        val net = CallNet(backgroundScope, clock)
        fun side(user: String, device: String, name: String, features: List<String>): Side {
            val d = net.Dev(user, device)
            val gport = FakeGroupPort("$user/$device").also { it.members = listOf("$userA/$devA", "$userB/$devB", "$userC/c1c1c1c1-0000-4000-8000-000000000005") }
            gport.room = { _ -> RoomOutcome.Ok(CallsRoomReply("wss://x/livekit", "room", "$user/$device", "token-for-$user/$device", "2026-10-13T10:10:00.000Z", 32)) }
            val sfu = FakeSfu()
            val group = GroupCallMachine(user, device, backgroundScope, sfu, gport, MemMarks(), now = clock, serverNow = clock, log = { println("  [$name g] $it") }, newCallId = { room })
            val s = Side(d, group, gport, sfu, FakeMovePort().also { it.group = MoveGroup(grp) })
            d.machine = CallStateMachine(
                user, device, backgroundScope, d.media, d.signals, d.marks, d.environment, now = clock, serverNow = clock,
                log = { println("  [$name] $it") }, features = { features },
                onEscalate = { env, from -> s.mover.onEscalate(env, from) },
                onMovedByPeer = { id, _ -> s.mover.onMovedByPeer(id) },
            )
            s.mover = CallMover(backgroundScope, { user }, { device }, { d.machine }, { group }, s.movePort, log = { println("  [$name mv] $it") }, newCallId = { room })
            net.devices += d
            return s
        }
        return Triple(net, side(userA, devA, "A", features), side(userB, devB, "B", peerFeatures))
    }

    private suspend fun TestScope.settle(net: CallNet, rounds: Int = 8) {
        repeat(rounds) {
            runCurrent()
            net.flush()
        }
        runCurrent()
    }

    /** A calls B, B answers, ICE connects: both ACTIVE. */
    private suspend fun TestScope.connected(net: CallNet, a: Side, b: Side) {
        a.dev.machine.placeCall(conv)
        settle(net)
        assertTrue(b.dev.machine.answer())
        settle(net)
        listOf(a, b).forEach { it.dev.media.sessions.last().listener.onIceState(IceState.CONNECTED) }
        settle(net)
        advanceTimeBy(1_000)
        settle(net)
        assertEquals(CallPhase.ACTIVE, a.dev.machine.state.value?.phase)
        assertEquals(CallPhase.ACTIVE, b.dev.machine.state.value?.phase)
    }

    private fun remote(id: String) = SfuParticipant(id, local = false, hasAudio = false, encrypted = true, cryptor = CryptorState.NONE)

    @Test fun addButtonNeedsBothDevicesToListAdd() = runTest {
        val (net, a, b) = world(peerFeatures = listOf("switch"))
        connected(net, a, b)
        val s = a.dev.machine.state.value!!
        assertFalse(s.canAdd)
        assertTrue(s.iCanAdd)
        assertEquals("Nimal needs to update RisiMe to add people", addControl(s, "Nimal", groupAdd = false).hint)
        assertFalse(addControl(s, "Nimal", groupAdd = false).enabled)
        assertFalse(a.mover.add(listOf(userC)))
        assertTrue(a.gport.rooms.isEmpty())
        // The peer without `add` never acts on an escalate either.
        val bs = b.dev.machine.state.value!!
        assertEquals(ADD_UNAVAILABLE, addControl(bs, "Kamal", groupAdd = false).hint)
    }

    @Test fun addControlWhileConnecting() {
        val s = CallSnapshot("4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10", conv, userB, true, CallPhase.CONNECTING, iCanAdd = true)
        assertEquals("Add people once the call has connected", addControl(s, "Nimal", false).hint)
        val g = s.copy(group = true, phase = CallPhase.ACTIVE)
        assertTrue(addControl(g, "Pilot", groupAdd = true).enabled)
        assertFalse(addControl(g, "Pilot", groupAdd = false).enabled)
    }

    @Test fun movesTheCallIntoTheGroupWithoutDroppingAnyone() = runTest {
        val (net, a, b) = world()
        connected(net, a, b)
        assertTrue(a.dev.machine.state.value!!.canAdd)
        val p2pId = a.dev.machine.currentCallId()!!

        assertTrue(a.mover.add(listOf(userC)))
        settle(net)
        // Step 1: the group of the peer and the invitee; step 2: the room in hold (nothing published).
        assertEquals(listOf(listOf(userB, userC)), a.movePort.created)
        assertEquals(listOf(CallsRoomRequest.START), a.gport.rooms)
        assertFalse("nothing published while the 1:1 call carries the audio", a.session.micPublished)
        // Step 3: the escalate went to B's device over the 1:1 path, before the ring.
        val esc = a.dev.sent.map { it.env }.filterIsInstance<CallEnvelope.Escalate>().single()
        assertEquals(CallEnvelope.ESC_MOVE, esc.state)
        assertEquals(grp, esc.conversationId)
        assertEquals(room, esc.roomCallId)
        assertEquals(devB, esc.toDevice)
        // Step 4: started, then the ring naming only the invitee.
        assertEquals(GroupCallEnvelope.started(room, "audio"), a.gport.started.single())
        val ring = a.gport.signals.filterIsInstance<CallEnvelope.SfuOffer>().single()
        assertEquals(listOf(userC), ring.ringUsers)
        // B followed: joined in hold, call_member joined, the 1:1 still up on both sides (moving).
        assertEquals(listOf(CallsRoomRequest.JOIN), b.gport.rooms)
        assertFalse(b.session.micPublished)
        assertEquals(CallEnvelope.Member(room, CallEnvelope.MEMBER_JOINED), b.gport.signals.filterIsInstance<CallEnvelope.Member>().single())
        assertTrue(a.dev.machine.state.value!!.moving && b.dev.machine.state.value!!.moving)
        assertEquals(CallPhase.ACTIVE, a.dev.machine.state.value!!.phase)
        // The platform call is linked on both sides (one Telecom call through the move).
        assertEquals(Triple(p2pId, room, true), a.movePort.links.single())
        assertEquals(Triple(p2pId, room, true), b.movePort.links.single())

        // P2P loss while moving is expected, never "failed".
        b.dev.media.sessions.last().listener.onIceState(IceState.DISCONNECTED)
        settle(net)
        assertEquals(CallPhase.ACTIVE, b.dev.machine.state.value?.phase)

        // Both see each other in the room as members: each switches.
        a.session.room(remote("$userB/$devB"))
        b.session.room(remote("$userA/$devA"))
        settle(net)
        assertTrue(a.session.micPublished && b.session.micPublished)
        assertNull(a.dev.machine.state.value)
        assertNull(b.dev.machine.state.value)
        assertTrue(a.dev.media.sessions.last().closed && b.dev.media.sessions.last().closed)
        // The adder's durable call_end `moved` with moved_to; the peer sends none.
        val end = a.dev.ends.single()
        assertEquals(CallEnvelope.R_MOVED, end.reason)
        assertEquals(CallEnvelope.MovedTo(grp, room), end.movedTo)
        assertNotNull(end.connectedAt)
        assertTrue(b.dev.ends.isEmpty())
        // The group calls carry on (the screens show them).
        assertEquals(room, a.group.state.value!!.callId)
        assertEquals(room, b.group.state.value!!.callId)
        assertNull(a.mover.ui.value)
    }

    @Test fun thePeerFollowsAtOnceWhenTheAdderSwitchedFirst() = runTest {
        val (net, a, b) = world()
        connected(net, a, b)
        a.mover.add(listOf(userC))
        settle(net)
        // A sees B (B doesn't see A yet): A switches and sends `moved`; B follows on that line.
        a.session.room(remote("$userB/$devB"))
        settle(net)
        assertEquals(CallEnvelope.R_MOVED, a.dev.ends.single().reason)
        assertTrue(b.session.micPublished)
        assertNull(b.dev.machine.state.value)
        assertTrue(b.dev.ends.isEmpty())
    }

    @Test fun givesUpWhenThePeerNeverArrivesAndStaysPeerToPeer() = runTest {
        val (net, a, b) = world()
        connected(net, a, b)
        b.movePort.groupHere = false // B never gets the group's Welcome
        a.mover.add(listOf(userC))
        settle(net)
        advanceTimeBy(CallMover.GROUP_WAIT_MS + 1)
        settle(net)
        // B said `failed` (no_group): A gives up at once, cancels the invitees' ring, keeps the 1:1 call.
        val failed = b.dev.sent.map { it.env }.filterIsInstance<CallEnvelope.Escalate>().single()
        assertEquals(CallEnvelope.ESC_FAILED, failed.state)
        assertEquals(CallEnvelope.ESC_NO_GROUP, failed.reason)
        assertEquals(CallEnvelope.Cancel(room, CallEnvelope.CANCEL_ENDED), a.gport.signals.last())
        assertEquals(GroupCallEnvelope.R_TIMEOUT, a.gport.ended.single().reason)
        assertTrue(a.session.disconnected)
        assertEquals(CallPhase.ACTIVE, a.dev.machine.state.value!!.phase)
        assertFalse(a.dev.machine.state.value!!.moving)
        assertEquals(CallPhase.ACTIVE, b.dev.machine.state.value!!.phase)
        assertTrue(a.mover.ui.value!!.failed)
        assertEquals(CallMover.couldntAdd("Nimal"), a.mover.ui.value!!.line)
        assertTrue(a.dev.ends.isEmpty())
        // At most one move per call: the button is gone.
        assertFalse(a.dev.machine.state.value!!.canAdd)
    }

    @Test fun givesUpAfterThirtySecondsWithoutThePeerInTheRoom() = runTest {
        val (net, a, b) = world()
        connected(net, a, b)
        a.mover.add(listOf(userC))
        settle(net)
        advanceTimeBy(CallStateMachine.MOVE_MS + 1)
        settle(net)
        assertEquals(CallPhase.ACTIVE, a.dev.machine.state.value!!.phase)
        assertTrue(a.session.disconnected)
        assertEquals(Triple(a.dev.machine.currentCallId()!!, room, false), a.movePort.links.last())
        // B gave up too (its own 30 s) and stays on the 1:1 call.
        assertEquals(CallPhase.ACTIVE, b.dev.machine.state.value!!.phase)
        assertTrue(b.session.disconnected)
    }

    @Test fun hangingUpDuringTheMoveEndsTheRoomToo() = runTest {
        val (net, a, b) = world()
        connected(net, a, b)
        a.mover.add(listOf(userC))
        settle(net)
        a.dev.machine.hangUp()
        settle(net)
        assertEquals(CallEnvelope.R_HANGUP, a.dev.ends.single().reason)
        assertTrue(a.session.disconnected)
        assertTrue(b.session.disconnected)
        assertNull(a.group.currentCallId())
        assertNull(b.group.currentCallId())
    }

    @Test fun theGroupChatNotCreatedChangesNothing() = runTest {
        val (net, a, b) = world()
        connected(net, a, b)
        a.movePort.group = null
        a.mover.add(listOf(userC))
        settle(net)
        assertTrue(a.gport.rooms.isEmpty())
        assertTrue(a.dev.sent.none { it.env is CallEnvelope.Escalate })
        assertEquals(CallPhase.ACTIVE, a.dev.machine.state.value!!.phase)
        assertTrue(a.mover.ui.value!!.failed)
    }

    @Test fun anEscalateFromAnotherDeviceIsDropped() = runTest {
        val (net, a, b) = world()
        connected(net, a, b)
        val id = b.dev.machine.currentCallId()!!
        b.dev.machine.onSignal(InboundCall(conv, userA, "a9a9a9a9-0000-4000-8000-000000000009", testScheduler.currentTime + t0, CallEnvelope.Escalate(id, devB, CallEnvelope.ESC_MOVE, grp, room, "audio")))
        settle(net)
        assertTrue(b.gport.rooms.isEmpty())
        assertFalse(b.dev.machine.state.value!!.moving)
    }

    @Test fun texts() {
        assertEquals("Adding Sunil…", CallMover.adding(listOf("Sunil")))
        assertEquals("Kamal is adding Sunil and Ruwan…", CallMover.beingAdded("Kamal", listOf("Sunil", "Ruwan")))
        assertEquals("A, B and C", CallMover.joinNames(listOf("A", "B", "C")))
        assertEquals("Couldn't add people. You're still in the call with Nimal.", CallMover.couldntAdd("Nimal"))
    }
}
