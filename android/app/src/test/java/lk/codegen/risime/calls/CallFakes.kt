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

/**
 * A libwebrtc-shaped §19 video SDP: m=audio then m=video, BUNDLE, one fingerprint repeated per
 * section, VP8 + rtx (and, as libwebrtc offers them, the denied extensions the app must strip).
 */
fun fakeVideoSdp(offer: Boolean, fp: String, ufrag: String, leaky: Boolean = true, rejectVideo: Boolean = false): String = buildString {
    append("v=0\r\no=- 4611731 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0${if (rejectVideo) "" else " 1"}\r\na=msid-semantic: WMS\r\n")
    append("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\nc=IN IP4 0.0.0.0\r\na=rtcp:9 IN IP4 0.0.0.0\r\n")
    append("a=ice-ufrag:$ufrag\r\na=ice-pwd:u2Vt9pLm4KcR8sWnE1yHaZ0d\r\na=ice-options:trickle\r\na=fingerprint:sha-256 $fp\r\n")
    append(if (offer) "a=setup:actpass\r\n" else "a=setup:active\r\n")
    append("a=mid:0\r\n")
    if (leaky) append("a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level\r\n")
    append("a=sendrecv\r\na=rtcp-mux\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;useinbandfec=1\r\n")
    append("m=video ${if (rejectVideo) 0 else 9} UDP/TLS/RTP/SAVPF 96 97\r\nc=IN IP4 0.0.0.0\r\na=rtcp:9 IN IP4 0.0.0.0\r\n")
    append("a=ice-ufrag:$ufrag\r\na=ice-pwd:u2Vt9pLm4KcR8sWnE1yHaZ0d\r\na=fingerprint:sha-256 $fp\r\n")
    append(if (offer) "a=setup:actpass\r\n" else "a=setup:active\r\n")
    append("a=mid:1\r\n")
    if (leaky) append("a=extmap:13 urn:3gpp:video-orientation\r\na=extmap:14 http://www.webrtc.org/experiments/rtp-hdrext/abs-capture-time\r\n")
    append("a=extmap:3 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01\r\n")
    append("a=sendrecv\r\na=rtcp-mux\r\na=rtcp-rsize\r\na=rtpmap:96 VP8/90000\r\na=rtcp-fb:96 nack\r\na=rtpmap:97 rtx/90000\r\na=fmtp:97 apt=96\r\n")
    append("a=ssrc-group:FID 1001 1002\r\na=ssrc:1001 cname:x\r\na=ssrc:1002 cname:x\r\n")
}

fun fingerprintOf(n: Int): String = (0 until 32).joinToString(":") { "%02X".format((n * 7 + it) and 0xff) }

/** Fake WebRTC: one session per call attempt, a fresh "certificate" each, DTLS that sees the real peer certificate. */
class FakeCallMedia(override val available: Boolean = true) : CallMedia {
    private var counter = 0
    /** New sessions answer video with m=video port 0 (a future app that rejects video). */
    var rejectVideo = false
    /** New sessions behave like libwebrtc's JSEP (no answer without an offer out, m-lines never removed): see [FakeSession.strict]. */
    var strict = false
    val sessions = CopyOnWriteArrayList<FakeSession>()

    /** Every session ever created on any FakeCallMedia (the "network" DTLS sees real certificates). */
    companion object {
        val registry = java.util.concurrent.ConcurrentHashMap<String, FakeSession>()
        private var global = 0
        fun nextId() = synchronized(this) { ++global }
    }

    override val videoAvailable: Boolean = true

    override fun open(callId: String, iceServers: List<IceServer>, listener: CallMedia.Listener, video: Boolean): MediaSession {
        val n = nextId()
        counter++
        return FakeSession(callId, n, iceServers, listener, video).also {
            it.rejectVideo = rejectVideo
            it.strict = strict
            sessions += it
            registry[it.ufrag] = it
        }
    }

    class FakeSession(val callId: String, n: Int, val iceServers: List<IceServer>, val listener: CallMedia.Listener, val video: Boolean = false) : MediaSession {
        /** §19.5 every camera change the machine made (true = on). */
        val camera = CopyOnWriteArrayList<Boolean>()
        var switches = 0
        var lastFrame = 0L
        /** The peer answers with m=video port 0 (a future app that rejects video). */
        var rejectVideo = false

        /** Like the real session: on needs a video session; off is always honoured (privacy). */
        override fun setCamera(on: Boolean) {
            if (on) check(videoOn) { "setCamera on a voice session" }
            camera += on
        }

        /** JSEP-like checks: an answer needs a local offer out with the same m-lines; an offer never removes m-lines. */
        var strict = false
        /** The m-lines of the negotiated (stable) session, a local offer out, a remote offer applied. */
        var held = 0
        var pendingLocal: Int? = null
        var pendingRemote: Int? = null
        /** getStats never calls back (a stuck WebRTC thread). */
        var statsHang = false
        private var restarts = 0

        /** §23.3: the session may carry video now (renegotiation); offers and answers get m=video. */
        @Volatile var videoOn = video
        var rollbacks = 0
        /** §23.5 the screen shares started (the grants) and stopped; [screenFails] = the next start fails. */
        val screens = CopyOnWriteArrayList<Any>()
        var screenStops = 0
        var screenFails = false
        var screenOn = false
        var onScreenStopped: (() -> Unit)? = null

        override fun enableVideo() {
            videoOn = true
        }

        override suspend fun rollback() {
            rollbacks++
            pendingLocal = null
            // The video transceiver that never got a mid is stopped: the session is voice again.
            if (held < 2) videoOn = false
        }

        override fun startScreen(grant: Any, onStopped: () -> Unit): Boolean {
            if (screenFails) return false
            check(videoOn) { "screen on a voice session" }
            screens += grant
            screenOn = true
            onScreenStopped = onStopped
            return true
        }

        override fun stopScreen() {
            screenStops++
            screenOn = false
        }

        override fun switchCamera() {
            switches++
        }

        override fun lastRemoteFrameAt() = lastFrame

        val cameraOn: Boolean get() = camera.lastOrNull() == true
        val fp = fingerprintOf(n)
        private val base = "uf$n"
        /** The current ICE ufrag (an ICE restart, offered or answered, makes a new one). */
        var ufrag = base
            private set

        private fun newUfrag() {
            restarts++
            ufrag = "${base}r$restarts"
            registry[ufrag] = this
        }

        private fun remoteUfrag(sdp: String?) = sdp?.let { r -> SdpRules.lines(r).firstOrNull { it.startsWith("a=ice-ufrag:") }?.removePrefix("a=ice-ufrag:") }
        var remote: String? = null
        var localOffers = 0
        val remoteCandidates = CopyOnWriteArrayList<CallEnvelope.Candidate>()
        var mutedNow = false
        var closed = false
        var dtlsState = "connected"
        var cipher = "AEAD_AES_128_GCM"
        /** Audio bytes received (decision 054 stall watchdog); null = the media can't count. */
        var bytes: Long? = null

        override suspend fun createOffer(iceRestart: Boolean): String {
            localOffers++
            if (iceRestart) newUfrag()
            pendingLocal = if (videoOn) 2 else 1
            return SdpRules.prepareLocal(if (videoOn) fakeVideoSdp(true, fp, ufrag) else fakeSdp(true, fp, ufrag, audioLevel = true))
        }

        private var answerRestart = false

        override suspend fun createAnswer(): String {
            if (answerRestart) newUfrag()
            answerRestart = false
            held = pendingRemote ?: held
            pendingRemote = null
            return SdpRules.prepareLocal(if (videoOn) fakeVideoSdp(false, fp, ufrag, rejectVideo = rejectVideo) else fakeSdp(false, fp, ufrag, audioLevel = true))
        }

        override suspend fun setRemote(sdp: String, isOffer: Boolean) {
            val m = SdpRules.mLineCount(sdp)
            if (isOffer) {
                if (strict && m < held) throw IllegalStateException("the offer removes m-lines ($m < $held)")
                answerRestart = remote != null && remoteUfrag(remote) != remoteUfrag(sdp)
                pendingRemote = m
            } else {
                val out = pendingLocal
                if (strict && out == null) throw IllegalStateException("an answer without a local offer")
                if (strict && out != m) throw IllegalStateException("the answer has $m m-lines, the offer $out")
                held = m
                pendingLocal = null
            }
            remote = sdp
        }

        override fun addRemoteCandidate(c: CallEnvelope.Candidate) {
            remoteCandidates += c
        }

        /** DTLS sees the certificate of the session that really produced the remote SDP (by its ufrag). */
        override suspend fun stats(): DtlsStats {
            if (statsHang) kotlinx.coroutines.awaitCancellation()
            val ufrag = remote?.let { r -> SdpRules.lines(r).firstOrNull { it.startsWith("a=ice-ufrag:") }?.removePrefix("a=ice-ufrag:") }
            val peer = ufrag?.let { registry[it] }
            // The DTLS handshake checks the peer certificate against the fingerprint in the remote SDP.
            val expected = remote?.let { SdpRules.fingerprint(it) }
            if (peer != null && expected != SdpRules.normalize(peer.fp)) return DtlsStats("failed", null, SdpRules.normalize(peer.fp))
            return DtlsStats(dtlsState, cipher, peer?.fp?.let(SdpRules::normalize), bytesReceived = bytes)
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
    data class Sent(val fromUser: String, val fromDevice: String, val conv: String, val env: CallEnvelope.Env, val durable: Boolean, val media: String = CallEnvelope.MEDIA_AUDIO, val at: Long = 0)

    inner class Dev(val user: String, val device: String) {
        lateinit var machine: CallStateMachine
        val media = FakeCallMedia()
        val marks = FakeMarks()
        var busyAudio = false
        val sent = CopyOnWriteArrayList<Sent>()
        val ends = CopyOnWriteArrayList<CallEnvelope.End>()
        var refuse: SignalOutcome? = null
        /** Signals never complete (a stuck socket/lane), decision 054. */
        var hang = false
        /** This process is dead: nothing it sends reaches the network. */
        var dead = false
        var servers: List<IceServer> = emptyList()
        val missedCalls = CopyOnWriteArrayList<String>()

        val signals = object : CallSignals {
            override suspend fun signal(conversationId: String, peer: String, env: CallEnvelope.Env, media: String): SignalOutcome {
                if (hang) kotlinx.coroutines.awaitCancellation()
                if (dead) return SignalOutcome.Unavailable
                refuse?.let { return it }
                val s = Sent(user, device, conversationId, env, durable = false, media = media)
                sent += s
                queue += s
                return SignalOutcome.Ok
            }

            override suspend fun end(conversationId: String, peer: String, env: CallEnvelope.End) {
                if (dead) return
                ends += env
                val s = Sent(user, device, conversationId, env, durable = true)
                sent += s
                queue += s
            }

            override suspend fun missed(conversationId: String, peer: String, callId: String, video: Boolean) {
                missedCalls += callId
            }
        }
        val environment = object : CallEnvironment {
            override fun audioBusy() = busyAudio
            override suspend fun iceServers() = servers
        }
    }

    val devices = mutableListOf<Dev>()
    val queue = CopyOnWriteArrayList<Sent>()
    val delivered = CopyOnWriteArrayList<Pair<Dev, Sent>>()
    /** Signals [holdIf] matches wait in [held] (the server took them; the recipient gets them on [release]). */
    var holdIf: ((Sent) -> Boolean)? = null
    val held = CopyOnWriteArrayList<Sent>()

    /** Stops holding and delivers the held signals (with the server time they were taken at), then everything queued. */
    suspend fun release() {
        holdIf = null
        val h = held.toList()
        held.clear()
        queue.addAll(0, h)
        flush()
    }

    /** Delivers everything queued (including what the deliveries send), in order. */
    suspend fun flush(max: Int = 1000) {
        var n = 0
        while (queue.isNotEmpty() && n++ < max) {
            val s = queue.removeAt(0)
            if (holdIf?.invoke(s) == true) {
                held += s.copy(at = serverNow())
                continue
            }
            val users = devices.map { it.user }.toSet()
            val other = users.firstOrNull { !it.equals(s.fromUser, true) && dmConversationId(it, s.fromUser) == s.conv } ?: continue
            for (d in devices) {
                if (d.device == s.fromDevice) continue
                if (!d.user.equals(other, true) && !d.user.equals(s.fromUser, true)) continue
                delivered += d to s
                if (s.durable) {
                    d.machine.onCallEnd(s.conv, s.fromUser, s.fromDevice, s.env as CallEnvelope.End)
                } else {
                    d.machine.onSignal(InboundCall(s.conv, s.fromUser, s.fromDevice, if (s.at > 0) s.at else serverNow(), s.env, media = s.media))
                }
            }
        }
    }
}
