package lk.codegen.risime.calls

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import lk.codegen.risime.net.dmConversationId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * §16.9/§16.10 on a real arm64 Android (redroid): LiveKit's libwebrtc loads, our prepared SDP is
 * accepted, ICE connects over host candidates, DTLS-SRTP comes up, and the getStats fingerprint
 * equals the MLS-delivered one. A tampered fingerprint never connects. Then two full call state
 * machines with the real media, over an in-memory relay standing in for the server.
 */
@RunWith(AndroidJUnit4::class)
class WebRtcDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val media = WebRtcCallMedia(ctx, debug = true)
    private val closers = CopyOnWriteArrayList<MediaSession>()

    @After fun tearDown() {
        closers.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    private class Peer : CallMedia.Listener {
        lateinit var other: () -> MediaSession?
        val ice = CopyOnWriteArrayList<IceState>()
        val buffered = CopyOnWriteArrayList<CallEnvelope.Candidate>()
        val local = CopyOnWriteArrayList<CallEnvelope.Candidate>()
        @Volatile var remoteReady = false
        override fun onLocalCandidate(c: CallEnvelope.Candidate) {
            local += c
            val o = other()
            if (o != null && remoteReady) o.addRemoteCandidate(c) else buffered += c
        }
        override fun onGatheringDone() = Unit
        override fun onIceState(state: IceState) { ice += state }
    }

    private suspend fun waitFor(ms: Long, what: String, p: suspend () -> Boolean) {
        if (withTimeoutOrNull(ms) { while (!p()) delay(100); true } == null) throw AssertionError("timed out: $what")
    }

    private suspend fun loopback(tamper: Boolean): Pair<DtlsStats, DtlsStats> {
        val pa = Peer()
        val pb = Peer()
        val a = media.open("call-a", emptyList(), pa).also { closers += it }
        val b = media.open("call-b", emptyList(), pb).also { closers += it }
        pa.other = { b }
        pb.other = { a }
        val offer = a.createOffer()
        assertNull("our offer passes the strict rules", SdpRules.validate(offer, SdpRules.Role.OFFER))
        assertFalse(offer.contains(SdpRules.AUDIO_LEVEL))
        b.setRemote(offer, isOffer = true)
        val answer = b.createAnswer()
        assertNull("our answer passes the strict rules", SdpRules.validate(answer, SdpRules.Role.ANSWER))
        a.setRemote(if (tamper) SdpRules.tamperFingerprint(answer) else answer, isOffer = false)
        pa.remoteReady = true
        pb.remoteReady = true
        pa.buffered.forEach(b::addRemoteCandidate)
        pb.buffered.forEach(a::addRemoteCandidate)
        // §16.10 (c): every session has its own certificate.
        assertNotEquals(SdpRules.fingerprint(offer), SdpRules.fingerprint(answer))
        if (tamper) {
            delay(8_000)
            return a.stats() to b.stats()
        }
        waitFor(20_000, "ICE connected ${pa.ice} ${pb.ice}") { pa.ice.any { it == IceState.CONNECTED || it == IceState.COMPLETED } && pb.ice.any { it == IceState.CONNECTED || it == IceState.COMPLETED } }
        waitFor(10_000, "DTLS connected") { a.stats().dtlsState == "connected" && b.stats().dtlsState == "connected" }
        val sa = a.stats()
        val sb = b.stats()
        assertTrue("A's DTLS saw B's certificate: ${sa.remoteFingerprint} vs ${SdpRules.fingerprint(answer)}", SdpRules.sameFingerprint(sa.remoteFingerprint, SdpRules.fingerprint(answer)))
        assertTrue("B's DTLS saw A's certificate", SdpRules.sameFingerprint(sb.remoteFingerprint, SdpRules.fingerprint(offer)))
        return sa to sb
    }

    @Test fun libraryLoadsAndALoopbackCallConnectsOverHostCandidatesWithDtlsSrtp() = runBlocking {
        assertTrue("liblkjingle_peerconnection_so.so loads on arm64", media.available)
        val (sa, sb) = loopback(tamper = false)
        Log.i("RisiMe", "device loopback: A=$sa B=$sb")
        assertFalse("SRTP cipher", sa.srtpCipher.isNullOrEmpty())
        assertEquals("host", sa.localCandidateType)
        assertTrue("direct path: ${sa.remoteCandidateType}", sa.remoteCandidateType in setOf("host", "prflx")) // prflx = the same direct address, seen by a check before its trickled candidate
        // §16.9: AEAD_AES_128_GCM preferred, AES_CM_128_HMAC_SHA1_80 the fallback. On this build the GCM
        // preference doesn't take yet (status note); either allowed suite passes, nothing weaker.
        assertTrue("SRTP suite ${sa.srtpCipher}", sa.srtpCipher in setOf("AEAD_AES_128_GCM", "AEAD_AES_256_GCM", "AES_CM_128_HMAC_SHA1_80"))
    }

    @Test fun aTamperedFingerprintNeverConnects() = runBlocking {
        assertTrue(media.available)
        val (sa, _) = loopback(tamper = true)
        Log.i("RisiMe", "device tampered: A=$sa")
        assertNotEquals("DTLS must not come up with a tampered fingerprint", "connected", sa.dtlsState)
    }

    /** Two CallStateMachines with the real media; signals go through an in-memory relay (the server's role). */
    @Test fun fullCallStateMachinesWithRealMediaReachActiveAndVerified() = runBlocking {
        assertTrue(media.available)
        val ua = "aaaaaaaa-0000-4000-8000-000000000001"
        val ub = "bbbbbbbb-0000-4000-8000-000000000002"
        val conv = dmConversationId(ua, ub)
        val machines = ConcurrentHashMap<String, CallStateMachine>()
        val devOf = mapOf(ua to "a1a1a1a1-0000-4000-8000-000000000001", ub to "b1b1b1b1-0000-4000-8000-000000000003")
        val ends = CopyOnWriteArrayList<CallEnvelope.End>()
        fun signalsFor(me: String) = object : CallSignals {
            override suspend fun signal(conversationId: String, peer: String, env: CallEnvelope.Env, media: String): SignalOutcome {
                val target = machines[peer]!!
                scope.launch { runCatching { target.onSignal(InboundCall(conversationId, me, devOf[me]!!, System.currentTimeMillis(), env)) } }
                return SignalOutcome.Ok
            }
            override suspend fun end(conversationId: String, peer: String, env: CallEnvelope.End) {
                ends += env
                machines[peer]!!.onCallEnd(conversationId, me, devOf[me], env)
            }
        }
        val marks = { object : CallMarks {
            val rows = ConcurrentHashMap<String, CallMark>()
            override suspend fun get(callId: String) = rows[callId]
            override suspend fun put(mark: CallMark) { rows[mark.callId] = mark }
        } }
        for (u in listOf(ua, ub)) machines[u] = CallStateMachine(u, devOf[u]!!, scope, media, signalsFor(u), marks(), log = { Log.i("RisiMe", "[$u] $it") })
        val a = machines[ua]!!
        val b = machines[ub]!!
        a.placeCall(conv)
        waitFor(10_000, "B rings") { b.state.value?.phase == CallPhase.RINGING_IN }
        b.answer()
        waitFor(30_000, "both active: ${a.state.value} / ${b.state.value}") { a.state.value?.phase == CallPhase.ACTIVE && b.state.value?.phase == CallPhase.ACTIVE }
        assertTrue(a.state.value!!.verified && b.state.value!!.verified)
        delay(1_500)
        a.hangUp()
        waitFor(10_000, "B ended") { b.state.value == null || b.state.value?.phase == CallPhase.ENDED }
        assertEquals(CallEnvelope.R_HANGUP, ends.single().reason)
    }

}
