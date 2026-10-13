package ai.risicloud.risime.calls.risi

import ai.risicloud.risime.calls.CallEnvelope
import ai.risicloud.risime.calls.CallNotice
import ai.risicloud.risime.calls.CallPhase
import ai.risicloud.risime.calls.CryptorState
import ai.risicloud.risime.calls.FakeGroupPort
import ai.risicloud.risime.calls.FakeSfu
import ai.risicloud.risime.calls.GroupCallEnvelope
import ai.risicloud.risime.calls.GroupCallMachine
import ai.risicloud.risime.calls.GroupCallPort
import ai.risicloud.risime.calls.MemMarks
import ai.risicloud.risime.calls.RoomOutcome
import ai.risicloud.risime.calls.SfuParticipant
import ai.risicloud.risime.net.CallErrors
import ai.risicloud.risime.net.CallsRoomReply
import ai.risicloud.risime.net.RisiCallReply
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Proposal 2026-10-14-call-risi §41.2–§41.3 in the group call machine (decision 090): a Call Risi asks
 * `risi_call`, connects without frame encryption, sends no ring and no `call_member`, writes `started`/
 * `ended` with `"with": "risi"`, plays only Risi's identity (anyone else ends the call), gives up when Risi
 * doesn't join in 12 s, and maps the four refusals to their notices. Parity: a normal group call is unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallRisiMachineTest {
    private val conv = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val callId = "3c8e1f4a-9b2d-4e7f-8a61-5d0c2b9e7f13"
    private val risi = "u-risi/risi-dev"

    private class RisiPort(val base: FakeGroupPort, var outcome: () -> RoomOutcome) : GroupCallPort by base {
        val risiRooms = CopyOnWriteArrayList<String>()
        override suspend fun risiRoom(conv: String, callId: String): RoomOutcome = outcome().also { risiRooms += callId }
    }

    private class Rig(val m: GroupCallMachine, val port: RisiPort, val sfu: FakeSfu) {
        val base get() = port.base
        val session get() = sfu.sessions.last()
    }

    private fun reply(risiCall: RisiCallReply? = RisiCallReply("joining", false, "u-risi/risi-dev", 1800, "en")) =
        CallsRoomReply("wss://x/livekit", "room", "u-a/a1", "token-for-u-a/a1", "2026-10-08T10:10:00.000Z", 2, risiCall = risiCall)

    private fun TestScope.rig(outcome: () -> RoomOutcome = { RoomOutcome.Ok(reply()) }): Rig {
        val port = RisiPort(FakeGroupPort("u-a/a1"), outcome)
        val sfu = FakeSfu()
        val clock = { testScheduler.currentTime + 1_791_451_200_000L }
        val m = GroupCallMachine("u-a", "a1", backgroundScope, sfu, port, MemMarks(), now = clock, newCallId = { callId })
        return Rig(m, port, sfu)
    }

    private fun remote(id: String, speaking: Boolean = false) =
        SfuParticipant(id, local = false, hasAudio = true, encrypted = false, cryptor = CryptorState.NONE, speaking = speaking)

    @Test fun risiCallConnectsWithoutFrameKeysNoRingStartedWithRisi() = runTest {
        val r = rig()
        assertTrue(r.m.startRisi(conv))
        runCurrent()
        assertEquals(listOf(callId), r.port.risiRooms)
        assertTrue(r.base.rooms.isEmpty()) // never the normal `start`
        val connect = r.sfu.connects.single()
        assertFalse(connect.e2ee)
        assertFalse(connect.video)
        assertTrue(r.session.micPublished)
        assertTrue(r.session.keys.keys.isEmpty()) // no frame keys at all
        assertTrue(r.base.signals.isEmpty()) // no call_offer, no call_member
        assertEquals(GroupCallEnvelope.started(callId, "audio").copy(with = "risi"), r.base.started.single())
        val st = r.m.state.value!!
        assertTrue(st.risiCall)
        assertFalse(st.risiPresent)
        assertFalse(st.verified)
        assertEquals(CallPhase.RINGING_OUT, st.phase)

        r.session.room(remote(risi, speaking = true))
        runCurrent()
        val on = r.m.state.value!!
        assertEquals(CallPhase.ACTIVE, on.phase)
        assertTrue(on.risiPresent)
        assertTrue(on.risiSpeaking)
        assertFalse(on.verified) // never claimed: not frame-encrypted
        assertEquals(true, r.session.playable[risi])

        advanceTimeBy(192_000)
        r.m.hangUp()
        runCurrent()
        val e = r.base.ended.single()
        assertEquals(GroupCallEnvelope.R_HANGUP, e.reason)
        assertEquals("risi", e.with)
        assertEquals(192L, e.durationS)
        assertTrue(r.session.disconnected)
        assertTrue(r.base.signals.isEmpty())
    }

    @Test fun anotherIdentityInTheRoomEndsTheCall() = runTest {
        val r = rig()
        r.m.startRisi(conv)
        runCurrent()
        r.session.room(remote(risi), remote("u-b/b1"))
        runCurrent()
        assertEquals(false, r.session.playable["u-b/b1"])
        assertTrue(r.session.disconnected)
        assertEquals(CallNotice.RISI_UNSAFE, r.m.state.value?.notice)
    }

    @Test fun risiNotJoiningIn12sLeavesWithTimeout() = runTest {
        val r = rig()
        r.m.startRisi(conv)
        runCurrent()
        advanceTimeBy(CallRisiRules.JOIN_TIMEOUT_MS - 100)
        runCurrent()
        assertFalse(r.session.disconnected)
        advanceTimeBy(200)
        runCurrent()
        assertTrue(r.session.disconnected)
        assertEquals(CallNotice.RISI_NO_ANSWER, r.m.state.value?.notice)
        val e = r.base.ended.single()
        assertEquals(GroupCallEnvelope.R_TIMEOUT, e.reason)
        assertEquals("risi", e.with)
    }

    @Test fun risiLeavingEndsTheCall() = runTest {
        val r = rig()
        r.m.startRisi(conv)
        runCurrent()
        r.session.room(remote(risi))
        runCurrent()
        r.session.room()
        runCurrent()
        assertTrue(r.session.disconnected)
        assertEquals(GroupCallEnvelope.R_HANGUP, r.base.ended.single().reason)
    }

    @Test fun refusalsMapToTheirNotices() = runTest {
        val cases = mapOf(
            CallErrors.CALL_RISI_OFF to CallNotice.RISI_OFF,
            CallErrors.NOT_RISI_CHAT to CallNotice.RISI_NOT_CHAT,
            CallErrors.RISI_BUSY to CallNotice.RISI_BUSY,
            CallErrors.RISI_UNAVAILABLE to CallNotice.RISI_UNAVAILABLE,
            "calls_unavailable" to CallNotice.CANT_CONNECT,
        )
        for ((code, notice) in cases) {
            val r = rig { RoomOutcome.Failed("403 $code", code) }
            r.m.startRisi(conv)
            runCurrent()
            assertEquals(code, notice, r.m.state.value?.notice)
            assertTrue(r.sfu.connects.isEmpty())
            assertTrue(r.base.started.isEmpty())
            assertTrue(r.base.ended.isEmpty()) // no `started` went out, so no `ended` either
            advanceTimeBy(10_000)
        }
    }

    @Test fun aReplyWithoutRisiCallIsNotARisiCall() = runTest {
        val r = rig { RoomOutcome.Ok(reply(risiCall = null)) }
        r.m.startRisi(conv)
        runCurrent()
        assertTrue(r.sfu.connects.isEmpty())
        assertEquals(CallNotice.CANT_CONNECT, r.m.state.value?.notice)
    }

    @Test fun parityANormalGroupCallIsUnchanged() = runTest {
        val r = rig()
        r.m.start(conv)
        runCurrent()
        assertTrue(r.port.risiRooms.isEmpty())
        assertTrue(r.sfu.connects.single().e2ee)
        val started = r.base.started.single()
        assertNull(started.with)
        assertFalse(started.toJson().containsKey("with"))
        assertTrue(r.base.signals.single() is CallEnvelope.SfuOffer)
        assertFalse(r.m.state.value!!.risiCall)
    }
}
