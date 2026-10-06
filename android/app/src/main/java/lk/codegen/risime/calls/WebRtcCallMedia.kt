package lk.codegen.risime.calls

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.CryptoOptions
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * §16.9/§16.10 the real media: LiveKit's prefixed libwebrtc (`livekit.org.webrtc`), audio only,
 * Opus, DTLS-SRTP (GCM preferred), a fresh peer connection and certificate per call.
 */
object WebRtcConfig {
    const val NATIVE_LIB = "lkjingle_peerconnection_so"

    /**
     * §16.10 (b): the options every factory is built with. Encryption can't be turned off; a unit
     * test reads these.
     */
    fun factoryOptions(): PeerConnectionFactory.Options = PeerConnectionFactory.Options().apply {
        disableEncryption = false
        disableNetworkMonitor = false
    }

    /** SRTP: AEAD_AES_128_GCM preferred, AES_CM_128_HMAC_SHA1_80 as the fallback (§16.9). */
    fun cryptoOptions(): CryptoOptions = CryptoOptions.builder()
        .setEnableGcmCryptoSuites(true)
        .setEnableAes128Sha1_32CryptoCipher(false)
        .setEnableEncryptedRtpHeaderExtensions(false) // the audio-level extension is stripped instead (crypto R5)
        .setRequireFrameEncryption(false)
        .createCryptoOptions()

    fun rtcConfig(servers: List<IceServer>, relayOnly: Boolean): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(
            servers.map { s ->
                PeerConnection.IceServer.builder(s.urls).apply {
                    s.username?.let(::setUsername)
                    s.credential?.let(::setPassword)
                }.createIceServer()
            },
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            // §16.10 (c): no certificate given: libwebrtc generates a fresh ECDSA P-256 one per connection.
            keyType = PeerConnection.KeyType.ECDSA
            certificate = null
            iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            cryptoOptions = cryptoOptions()
        }
}

class WebRtcCallMedia(
    private val context: Context,
    /** Debug "Always relay calls" (§16.13 later setting; the §16.10 f relay-only test). */
    private val relayOnly: () -> Boolean = { false },
    private val debug: Boolean = false,
) : CallMedia {
    override val available: Boolean by lazy {
        runCatching {
            System.loadLibrary(WebRtcConfig.NATIVE_LIB)
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setNativeLibraryName(WebRtcConfig.NATIVE_LIB)
                    .createInitializationOptions(),
            )
            true
        }.getOrElse {
            Log.w("RisiMe", "WebRTC unavailable on this phone: ${it.javaClass.simpleName}")
            false
        }
    }

    /** The last §16.10 stats read (the debug overlay and the device smoke test). */
    @Volatile var lastStats: DtlsStats? = null
        private set

    override fun open(callId: String, iceServers: List<IceServer>, listener: CallMedia.Listener): MediaSession {
        check(available) { "WebRTC not loaded" }
        return Session(context.applicationContext, iceServers, relayOnly(), listener, debug) { lastStats = it }
    }

    private class Session(
        context: Context,
        servers: List<IceServer>,
        relay: Boolean,
        private val listener: CallMedia.Listener,
        private val debug: Boolean,
        private val onStats: (DtlsStats) -> Unit,
    ) : MediaSession {
        private val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .createAudioDeviceModule()
        private val factory: PeerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(WebRtcConfig.factoryOptions())
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        private val source = factory.createAudioSource(
            MediaConstraints().apply {
                mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
                mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
                mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
                mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
            },
        )
        private val track: AudioTrack = factory.createAudioTrack("risime-audio", source)
        private val pc: PeerConnection = factory.createPeerConnection(WebRtcConfig.rtcConfig(servers, relay), observer())
            ?: error("createPeerConnection failed")

        init {
            pc.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("risime")))
        }

        private fun observer() = object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                listener.onIceState(
                    when (s) {
                        PeerConnection.IceConnectionState.CHECKING -> IceState.CHECKING
                        PeerConnection.IceConnectionState.CONNECTED -> IceState.CONNECTED
                        PeerConnection.IceConnectionState.COMPLETED -> IceState.COMPLETED
                        PeerConnection.IceConnectionState.DISCONNECTED -> IceState.DISCONNECTED
                        PeerConnection.IceConnectionState.FAILED -> IceState.FAILED
                        PeerConnection.IceConnectionState.CLOSED -> IceState.CLOSED
                        else -> IceState.NEW
                    },
                )
            }
            override fun onIceConnectionReceivingChange(b: Boolean) = Unit
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) listener.onGatheringDone()
            }
            override fun onIceCandidate(c: IceCandidate) {
                // Release builds never log candidates or IPs (§16.9).
                listener.onLocalCandidate(CallEnvelope.Candidate(c.sdp.removePrefix("a="), c.sdpMid, c.sdpMLineIndex))
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) = Unit
            override fun onAddStream(s: MediaStream?) = Unit
            override fun onRemoveStream(s: MediaStream?) = Unit
            override fun onDataChannel(d: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
        }

        private suspend fun create(offer: Boolean, constraints: MediaConstraints): SessionDescription = suspendCancellableCoroutine { cont ->
            val obs = object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription) = cont.resume(d)
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(e: String?) = cont.resumeWithException(IllegalStateException("create: $e"))
                override fun onSetFailure(e: String?) = Unit
            }
            if (offer) pc.createOffer(obs, constraints) else pc.createAnswer(obs, constraints)
        }

        private suspend fun set(local: Boolean, d: SessionDescription) = suspendCancellableCoroutine { cont ->
            val obs = object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription?) = Unit
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onCreateFailure(e: String?) = Unit
                override fun onSetFailure(e: String?) = cont.resumeWithException(IllegalStateException("set: $e"))
            }
            if (local) pc.setLocalDescription(obs, d) else pc.setRemoteDescription(obs, d)
        }

        private fun audioOnly(restart: Boolean) = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
            if (restart) mandatory += MediaConstraints.KeyValuePair("IceRestart", "true")
        }

        override suspend fun createOffer(iceRestart: Boolean): String {
            if (iceRestart) pc.restartIce()
            val d = create(true, audioOnly(iceRestart))
            // crypto R5 + §16.9: strip the audio level, tune Opus, then set what we send.
            val sdp = SdpRules.prepareLocal(d.description)
            set(true, SessionDescription(SessionDescription.Type.OFFER, sdp))
            return sdp
        }

        override suspend fun createAnswer(): String {
            val d = create(false, audioOnly(false))
            val sdp = SdpRules.prepareLocal(d.description)
            set(true, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            return sdp
        }

        override suspend fun setRemote(sdp: String, isOffer: Boolean) {
            set(false, SessionDescription(if (isOffer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER, sdp))
        }

        override fun addRemoteCandidate(c: CallEnvelope.Candidate) {
            pc.addIceCandidate(IceCandidate(c.sdpMid ?: "0", c.sdpMLineIndex, c.candidate))
        }

        /** §16.10 (e): the transport's DTLS state, SRTP cipher and the remote certificate's fingerprint. */
        override suspend fun stats(): DtlsStats = suspendCancellableCoroutine { cont ->
            pc.getStats { report ->
                val all = report.statsMap.values
                val transport = all.firstOrNull { it.type == "transport" && it.members["dtlsState"] != null }
                val remoteCertId = transport?.members?.get("remoteCertificateId") as? String
                val fp = remoteCertId?.let { id -> all.firstOrNull { it.id == id }?.members?.get("fingerprint") as? String }
                val pairId = transport?.members?.get("selectedCandidatePairId") as? String
                val pair = pairId?.let { id -> all.firstOrNull { it.id == id } }
                    ?: all.firstOrNull { it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true }
                val local = pair?.members?.get("localCandidateId")?.let { id -> all.firstOrNull { it.id == id }?.members?.get("candidateType") } as? String
                val remote = pair?.members?.get("remoteCandidateId")?.let { id -> all.firstOrNull { it.id == id }?.members?.get("candidateType") } as? String
                // Debug overlay data only; release builds never log candidates or IPs (§16.9).
                if (debug) Log.d("RisiMe", "call stats: pair=$local/$remote rtt=${pair?.members?.get("currentRoundTripTime")} dtls=${transport?.members?.get("dtlsState")} srtp=${transport?.members?.get("srtpCipher")}")
                val st = DtlsStats(transport?.members?.get("dtlsState") as? String, transport?.members?.get("srtpCipher") as? String, fp, local, remote)
                onStats(st)
                cont.resume(st)
            }
        }

        override fun setMuted(muted: Boolean) {
            track.setEnabled(!muted)
        }

        override fun close() {
            runCatching { pc.close() }
            runCatching { pc.dispose() }
            runCatching { source.dispose() }
            runCatching { factory.dispose() }
            runCatching { adm.release() }
        }
    }
}
