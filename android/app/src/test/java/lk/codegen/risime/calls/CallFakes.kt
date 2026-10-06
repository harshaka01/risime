package lk.codegen.risime.calls

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import lk.codegen.risime.net.dmConversationId
import java.util.concurrent.CopyOnWriteArrayList

/** A libwebrtc-shaped SDP (one m=audio, Opus, one sha-256 fingerprint). */
fun fakeSdp(offer: Boolean, fp: String, ufrag: String, audioLevel: Boolean = false): String = buildString {
    append("v=0\r\no=- 4611731 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\na=msid-semantic: WMS\r\n")
    append("m=audio 9 UDP/TLS/RTP/SAVPF 111 0\r\nc=IN IP4 0.0.0.0\r\na=rtcp:9 IN IP4 0.0.0.0\r\n")
    append("a=ice-ufrag:$ufrag\r\na=ice-pwd:u2Vt9pLm4KcR8sWnE1yHaZ0d\r\na=ice-options:trickle\r\n")
    append("a=fingerprint:sha-256 $fp\r\n")
    append(if (offer) "a=setup:actpass\r\n" else "a=setup:active\r\n")
    append("a=mid:0\r\n")
    if (audioLevel) append("a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level\r\n")
    append("a=extmap:3 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01\r\n")
    append("a=sendrecv\r\na=rtcp-mux\r\na=rtpmap:111 opus/48000/2\r\na=rtcp-fb:111 transport-cc\r\na=fmtp:111 minptime=10;useinbandfec=1\r\na=rtpmap:0 PCMU/8000\r\n")
}

fun fingerprintOf(n: Int): String = (0 until 32).joinToString(":") { "%02X".format((n * 7 + it) and 0xff) }

/** Fake WebRTC: one session per call attempt, a fresh "certificate" each, DTLS that sees the real peer certificate. */
class FakeCallMedia(override val available: Boolean = true) : CallMedia {
    private var counter = 0
    val sessions = CopyOnWriteArrayList<FakeSession>()

    /** Every session ever created on any FakeCallMedia (the "network" DTLS sees real certificates). */
    companion object {
        val registry = java.util.concurrent.ConcurrentHashMap<String, FakeSession>()
        private var global = 0
        fun nextId() = synchronized(this) { ++global }
    }

    override fun open(callId: String, iceServers: List<IceServer>, listener: CallMedia.Listener): MediaSession {
        val n = nextId()
        counter++
        return FakeSession(callId, n, iceServers, listener).also {
            sessions += it
            registry[it.ufrag] = it
        }
    }

    class FakeSession(val callId: String, n: Int, val iceServers: List<IceServer>, val listener: CallMedia.Listener) : MediaSession {
        val fp = fingerprintOf(n)
        val ufrag = "uf$n"
        var remote: String? = null
        var localOffers = 0
        val remoteCandidates = CopyOnWriteArrayList<CallEnvelope.Candidate>()
        var mutedNow = false
        var closed = false
        var dtlsState = "connected"
        var cipher = "AEAD_AES_128_GCM"

        override suspend fun createOffer(iceRestart: Boolean): String {
            localOffers++
            return SdpRules.prepareLocal(fakeSdp(true, fp, ufrag, audioLevel = true))
        }

        override suspend fun createAnswer(): String = SdpRules.prepareLocal(fakeSdp(false, fp, ufrag, audioLevel = true))

        override suspend fun setRemote(sdp: String, isOffer: Boolean) {
            remote = sdp
        }

        override fun addRemoteCandidate(c: CallEnvelope.Candidate) {
            remoteCandidates += c
        }

        /** DTLS sees the certificate of the session that really produced the remote SDP (by its ufrag). */
        override suspend fun stats(): DtlsStats {
            val ufrag = remote?.let { r -> SdpRules.lines(r).firstOrNull { it.startsWith("a=ice-ufrag:") }?.removePrefix("a=ice-ufrag:") }
            val peer = ufrag?.let { registry[it] }
            // The DTLS handshake checks the peer certificate against the fingerprint in the remote SDP.
            val expected = remote?.let { SdpRules.fingerprint(it) }
            if (peer != null && expected != SdpRules.normalize(peer.fp)) return DtlsStats("failed", null, SdpRules.normalize(peer.fp))
            return DtlsStats(dtlsState, cipher, peer?.fp?.let(SdpRules::normalize))
        }

        override fun setMuted(muted: Boolean) {
            mutedNow = muted
        }

        override fun close() {
            closed = true
        }

        fun candidate(i: Int) = listener.onLocalCandidate(CallEnvelope.Candidate("candidate:$i 1 udp 2122260223 192.0.2.${i % 250} 4920$i typ host", "0", 0))
    }
}

class FakeMarks : CallMarks {
    val rows = java.util.concurrent.ConcurrentHashMap<String, CallMark>()

    override suspend fun get(callId: String): CallMark? = rows[callId]

    override suspend fun put(mark: CallMark) {
        rows[mark.callId] = mark
    }
}

/**
 * A fake server for N devices: `call:signal` goes to every device of the recipient user and the
 * sender's other devices (sender copy), in send order; `call_end` likewise. [flush] delivers.
 */
class CallNet(private val scope: CoroutineScope, private val serverNow: () -> Long) {
    data class Sent(val fromUser: String, val fromDevice: String, val conv: String, val env: CallEnvelope.Env, val durable: Boolean)

    inner class Dev(val user: String, val device: String) {
        lateinit var machine: CallStateMachine
        val media = FakeCallMedia()
        val marks = FakeMarks()
        var busyAudio = false
        val sent = CopyOnWriteArrayList<Sent>()
        val ends = CopyOnWriteArrayList<CallEnvelope.End>()
        var refuse: SignalOutcome? = null

        val signals = object : CallSignals {
            override suspend fun signal(conversationId: String, peer: String, env: CallEnvelope.Env): SignalOutcome {
                refuse?.let { return it }
                val s = Sent(user, device, conversationId, env, durable = false)
                sent += s
                queue += s
                return SignalOutcome.Ok
            }

            override suspend fun end(conversationId: String, peer: String, env: CallEnvelope.End) {
                ends += env
                val s = Sent(user, device, conversationId, env, durable = true)
                sent += s
                queue += s
            }
        }
        val environment = object : CallEnvironment {
            override fun audioBusy() = busyAudio
        }
    }

    val devices = mutableListOf<Dev>()
    val queue = CopyOnWriteArrayList<Sent>()
    val delivered = CopyOnWriteArrayList<Pair<Dev, Sent>>()

    /** Delivers everything queued (including what the deliveries send), in order. */
    suspend fun flush(max: Int = 1000) {
        var n = 0
        while (queue.isNotEmpty() && n++ < max) {
            val s = queue.removeAt(0)
            val users = devices.map { it.user }.toSet()
            val other = users.firstOrNull { !it.equals(s.fromUser, true) && dmConversationId(it, s.fromUser) == s.conv } ?: continue
            for (d in devices) {
                if (d.device == s.fromDevice) continue
                if (!d.user.equals(other, true) && !d.user.equals(s.fromUser, true)) continue
                delivered += d to s
                if (s.durable) {
                    d.machine.onCallEnd(s.conv, s.fromUser, s.fromDevice, s.env as CallEnvelope.End)
                } else {
                    d.machine.onSignal(InboundCall(s.conv, s.fromUser, s.fromDevice, serverNow(), s.env))
                }
            }
        }
    }
}
