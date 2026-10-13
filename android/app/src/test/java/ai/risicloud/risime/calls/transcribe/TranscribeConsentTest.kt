package ai.risicloud.risime.calls.transcribe

import ai.risicloud.risime.net.CallTranscribeActions
import ai.risicloud.risime.net.CallTranscribeConsent
import ai.risicloud.risime.net.CallTranscribeErrors
import ai.risicloud.risime.net.CallTranscribeSession
import ai.risicloud.risime.net.CallTranscribeStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §36.2/§36.6 the phone's consent machine: the button, the prompt, the bar, recording and the Private rule. */
class TranscribeConsentTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val peer = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val callId = "4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10"
    private val grp = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"

    private fun call(official: Boolean = true, live: Boolean = true, id: String = callId) =
        TranscribeCall(id, peer, official, if (official) grp else grp, live)

    private fun machine(official: Boolean = true, switchOn: Boolean = true, capable: Boolean = true) =
        TranscribeConsentMachine(me).also {
            it.setAvailable(switchOn, capable)
            it.onCall(call(official))
        }

    private fun session(
        state: String,
        requestedBy: String = me,
        mine: String = CallTranscribeConsent.ALLOWED,
        theirs: String = CallTranscribeConsent.PENDING,
        id: String = "s1",
        by: String? = null,
        reason: String? = null,
        compare: Boolean = false,
    ) = CallTranscribeSession(
        sessionId = id, callId = callId, conversationId = grp, state = state, requestedBy = requestedBy,
        expiresAt = "2026-10-12T12:20:31.120Z", startedAt = if (state == CallTranscribeStates.ON) "2026-10-12T12:20:06.480Z" else null,
        participants = listOf(CallTranscribeConsent(me, mine), CallTranscribeConsent(peer, theirs)), by = by, reason = reason, compare = compare,
    )

    @Test
    fun buttonOnlyOnALiveOfficialCallWithTheSwitchAndCapability() {
        assertTrue(machine().view().button)
        assertFalse("Private call: no button", machine(official = false).view().button)
        assertFalse("switch off", machine(switchOn = false).view().button)
        assertFalse("no capability", machine(capable = false).view().button)
        val m = TranscribeConsentMachine(me).also { it.setAvailable(true, true); it.onCall(call(live = false)) }
        assertFalse("ringing / connecting: no button", m.view().button)
        val noOfficial = TranscribeConsentMachine(me).also { it.setAvailable(true, true); it.onCall(TranscribeCall(callId, peer, true, null, true)) }
        assertFalse("no Official conversation on this phone", noOfficial.view().button)
    }

    @Test
    fun aPrivateCallAutoDeclinesWithPrivateCallAndShowsNothing() {
        val m = machine(official = false)
        val out = m.onSession(session(CallTranscribeStates.REQUESTED, requestedBy = peer, mine = CallTranscribeConsent.PENDING, theirs = CallTranscribeConsent.ALLOWED))
        assertEquals(1, out.size)
        assertEquals(CallTranscribeActions.DECLINE, out[0].action)
        assertEquals(CallTranscribeStates.REASON_PRIVATE_CALL, out[0].reason)
        assertEquals(TranscribeView(), m.view())
        assertNull(m.request())
        assertNull(m.recordTarget())
        // Even an `on` for a Private call never records.
        m.onSession(session(CallTranscribeStates.ON))
        assertNull(m.recordTarget())
        assertFalse(m.view().bar)
    }

    @Test
    fun requestShowsAskingThenOnShowsTheBarAndRecords() {
        val m = machine()
        val req = m.request()!!
        assertEquals(CallTranscribeActions.REQUEST, req.action)
        assertEquals(grp, req.conversationId)
        assertTrue(m.view().asking)
        assertFalse(m.view().button)
        m.onSession(session(CallTranscribeStates.REQUESTED))
        assertTrue(m.view().asking)
        assertNull("nothing recorded before everyone allowed", m.recordTarget())
        m.onSession(session(CallTranscribeStates.ON, theirs = CallTranscribeConsent.ALLOWED))
        assertTrue(m.view().bar)
        assertFalse(m.view().asking)
        assertEquals("s1", m.recordTarget()!!.sessionId)
    }

    @Test
    fun thePeerPromptAllowsAndDeclines() {
        val m = machine()
        m.onSession(session(CallTranscribeStates.REQUESTED, requestedBy = peer, mine = CallTranscribeConsent.PENDING, theirs = CallTranscribeConsent.ALLOWED, compare = true))
        val p = m.view().prompt!!
        assertEquals(peer, p.requestedBy)
        assertTrue("the prompt says comparison is on", p.compare)
        assertEquals(CallTranscribeActions.ALLOW, m.allow()!!.action)
        val d = m.decline()!!
        assertEquals(CallTranscribeActions.DECLINE, d.action)
        assertNull(d.reason)
        // Declined: final, no prompt, no bar, nothing recorded.
        m.onSession(session(CallTranscribeStates.DECLINED, requestedBy = peer, mine = CallTranscribeConsent.DECLINED, theirs = CallTranscribeConsent.ALLOWED))
        assertNull(m.view().prompt)
        assertNull(m.recordTarget())
        assertTrue("a new request is possible", m.view().button)
    }

    @Test
    fun declineByThePeerShowsANotice() {
        val m = machine()
        m.request()
        m.onSession(session(CallTranscribeStates.DECLINED, theirs = CallTranscribeConsent.DECLINED))
        assertEquals(TranscribeNotice(TranscribeConsentMachine.NOTICE_DECLINED, peer), m.view().notice)
    }

    @Test
    fun theBarFailsTowardShowingAndOnlyAFinalStateRemovesIt() {
        val m = machine()
        m.onSession(session(CallTranscribeStates.ON, theirs = CallTranscribeConsent.ALLOWED))
        // A request-state echo (or anything non-final) does not hide it.
        m.onSession(session(CallTranscribeStates.REQUESTED))
        assertTrue(m.view().bar)
        // A final state of another session doesn't either.
        m.onSession(session(CallTranscribeStates.EXPIRED, id = "other"))
        assertTrue(m.view().bar)
        m.onSession(session(CallTranscribeStates.STOPPED, by = peer, reason = "stopped"))
        assertFalse(m.view().bar)
        assertNull(m.recordTarget())
        assertEquals(TranscribeNotice(TranscribeConsentMachine.NOTICE_STOPPED, peer), m.view().notice)
        // A late `on` of the stopped session never brings it back.
        m.onSession(session(CallTranscribeStates.ON, theirs = CallTranscribeConsent.ALLOWED))
        assertFalse(m.view().bar)
        assertNull(m.recordTarget())
    }

    @Test
    fun recordingStopsOn409Stopped() {
        val m = machine()
        m.onSession(session(CallTranscribeStates.ON, theirs = CallTranscribeConsent.ALLOWED))
        assertNotNull(m.recordTarget())
        m.onUploadStopped(CallTranscribeErrors.STOPPED)
        assertNull(m.recordTarget())
        assertFalse(m.view().bar)
    }

    @Test
    fun stopIsForEveryoneWithOrWithoutDiscard() {
        val m = machine()
        m.onSession(session(CallTranscribeStates.ON, theirs = CallTranscribeConsent.ALLOWED))
        val s = m.stop(discard = true)!!
        assertEquals(CallTranscribeActions.STOP, s.action)
        assertTrue(s.discard)
        assertEquals("stopped", s.reason)
        assertEquals("s1", s.sessionId)
        assertTrue("the bar stays until the server says final", m.view().bar)
    }

    @Test
    fun hangUpSendsStopEndedAndANewCallInheritsNothing() {
        val m = machine()
        m.onSession(session(CallTranscribeStates.ON, theirs = CallTranscribeConsent.ALLOWED))
        val out = m.onHangUp()
        assertEquals(1, out.size)
        assertEquals(CallTranscribeStates.REASON_ENDED, out[0].reason)
        assertFalse(out[0].discard)
        assertNull(m.recordTarget())
        assertTrue("only once", m.onHangUp().isEmpty())
        val m2 = machine()
        m2.onSession(session(CallTranscribeStates.ON, theirs = CallTranscribeConsent.ALLOWED))
        // The call goes away (or a new call id): stop `ended`, then nothing is carried over.
        val gone = m2.onCall(call(id = "5c8f2d2f-4d1f-4c66-8a9f-1c9f9b2f3e21"))
        assertEquals(CallTranscribeStates.REASON_ENDED, gone.single().reason)
        assertFalse(m2.view().bar)
        assertNull(m2.recordTarget())
        // A session of the old call is ignored now.
        m2.onSession(session(CallTranscribeStates.ON, theirs = CallTranscribeConsent.ALLOWED))
        assertNull(m2.recordTarget())
    }

    @Test
    fun aRequestFailureBringsTheButtonBackWithTheReason() {
        val m = machine()
        m.request()
        m.onRequestFailed(CallTranscribeErrors.PEER_NOT_READY)
        assertTrue(m.view().button)
        assertEquals(CallTranscribeErrors.PEER_NOT_READY, m.view().notice!!.code)
    }

    @Test
    fun aSessionOfAnotherCallIsIgnored() {
        val m = machine()
        val out = m.onSession(session(CallTranscribeStates.REQUESTED, requestedBy = peer, mine = CallTranscribeConsent.PENDING).copy(callId = "5c8f2d2f-4d1f-4c66-8a9f-1c9f9b2f3e21"))
        assertTrue(out.isEmpty())
        assertNull(m.view().prompt)
    }

    // ---- v1.45 §40.9 Official group calls ----

    private val joiner = "3c1d2e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f"

    private fun groupMachine(alone: Boolean = false) = TranscribeConsentMachine(me).also {
        it.setAvailable(true, true)
        it.onCall(TranscribeCall(callId, peer, true, grp, true, group = true, alone = alone))
    }

    private fun paused(mineConsent: String = CallTranscribeConsent.ALLOWED) = session(CallTranscribeStates.ON, mine = CallTranscribeConsent.ALLOWED, theirs = CallTranscribeConsent.ALLOWED).copy(
        state = CallTranscribeStates.PAUSED,
        participants = listOf(CallTranscribeConsent(me, mineConsent), CallTranscribeConsent(peer, CallTranscribeConsent.ALLOWED), CallTranscribeConsent(joiner, CallTranscribeConsent.PENDING)),
    )

    @Test
    fun aJoinerPausesTheGroupSessionTheBarStaysAndNothingIsRecorded() {
        val m = groupMachine()
        m.onSession(session(CallTranscribeStates.ON, mine = CallTranscribeConsent.ALLOWED, theirs = CallTranscribeConsent.ALLOWED))
        assertEquals(false, m.recordTarget()!!.paused)
        m.onSession(paused())
        val v = m.view()
        assertTrue("the bar fails toward showing", v.bar)
        assertEquals(listOf(joiner), v.pausedFor)
        assertNull("I allowed already: no prompt for me", v.prompt)
        assertFalse(v.button)
        assertEquals("paused keeps the recorder (seq) but records nothing", true, m.recordTarget()!!.paused)
        // Back on: recording again, the same session.
        m.onSession(session(CallTranscribeStates.ON, mine = CallTranscribeConsent.ALLOWED, theirs = CallTranscribeConsent.ALLOWED))
        assertEquals(false, m.recordTarget()!!.paused)
        assertTrue(m.view().pausedFor.isEmpty())
    }

    @Test
    fun theJoinerIsPromptedWhilePausedAndAllowGoesOut() {
        val m = TranscribeConsentMachine(joiner).also {
            it.setAvailable(true, true)
            it.onCall(TranscribeCall(callId, peer, true, grp, true, group = true, alone = false))
        }
        m.onSession(paused().copy(participants = listOf(CallTranscribeConsent(me, CallTranscribeConsent.ALLOWED), CallTranscribeConsent(peer, CallTranscribeConsent.ALLOWED), CallTranscribeConsent(joiner, CallTranscribeConsent.PENDING))))
        assertNotNull(m.view().prompt)
        assertEquals(CallTranscribeActions.ALLOW, m.allow()!!.action)
        assertNull("not recorded before my allow lands", m.recordTarget()?.takeIf { !it.paused })
    }

    @Test
    fun leavingAGroupCallStopsNothingWhileOthersRemain() {
        val m = groupMachine(alone = false)
        m.onSession(session(CallTranscribeStates.ON, mine = CallTranscribeConsent.ALLOWED, theirs = CallTranscribeConsent.ALLOWED))
        assertTrue(m.onHangUp().isEmpty())
        val last = groupMachine(alone = true)
        last.onSession(session(CallTranscribeStates.ON, mine = CallTranscribeConsent.ALLOWED, theirs = CallTranscribeConsent.ALLOWED))
        assertEquals(CallTranscribeStates.REASON_ENDED, last.onHangUp().single().reason)
    }

    @Test
    fun pausedLineNamesWhoIsPending() {
        assertEquals("Paused until Sunil allows", pausedLine(listOf("Sunil")))
        assertEquals("Paused until Sunil and Ruwan allow", pausedLine(listOf("Sunil", "Ruwan")))
    }
}
