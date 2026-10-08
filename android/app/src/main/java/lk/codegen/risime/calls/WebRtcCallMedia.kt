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
import livekit.org.webrtc.Camera2Enumerator
import livekit.org.webrtc.CameraVideoCapturer
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.SoftwareVideoDecoderFactory
import livekit.org.webrtc.SoftwareVideoEncoderFactory
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoSource
import livekit.org.webrtc.VideoTrack
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

    /** §19.7 sender caps: 1.5 Mbit/s on Wi-Fi, 800 kbit/s on mobile data. */
    const val WIFI_BPS = 1_500_000
    const val MOBILE_BPS = 800_000

    /** §19.7 capture: ≤ 1280×720 at 30 fps; mobile data or a relayed path 640×480 at 24; thermal ≥ SEVERE 640×480 at 15. */
    fun captureFormat(constrained: Boolean, hot: Boolean): Triple<Int, Int, Int> = when {
        hot -> Triple(640, 480, 15)
        constrained -> Triple(640, 480, 24)
        else -> Triple(1280, 720, 30)
    }

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
    /** Debug builds only: a generated test pattern instead of the camera (redroid has no camera sensor). Never in release. */
    private val fakeCamera: () -> Boolean = { false },
    /** §19.7: on mobile data the sender starts lower (640×480 at 24 fps, 800 kbit/s). */
    private val metered: () -> Boolean = { false },
) : CallMedia {
    /** §19.1 (android A9): VP8 in the software encoder and decoder factories (no new native library). */
    override val videoAvailable: Boolean by lazy {
        available && runCatching {
            SoftwareVideoEncoderFactory().supportedCodecs.any { it.name.equals("VP8", true) } &&
                SoftwareVideoDecoderFactory().supportedCodecs.any { it.name.equals("VP8", true) }
        }.getOrElse {
            Log.w("RisiMe", "VP8 unavailable: ${it.javaClass.simpleName}")
            false
        }
    }

    /** One EGL context for the capturer's texture helper and the call screen's renderers. */
    val egl: EglBase by lazy { EglBase.create() }

    @Volatile private var current: Session? = null

    /** The call screen's renderers (null detaches); frames go to whatever is attached now. */
    fun attachRemote(sink: VideoSink?) {
        remoteTarget = sink
    }

    fun attachLocal(sink: VideoSink?) {
        localTarget = sink
    }

    @Volatile private var remoteTarget: VideoSink? = null
    @Volatile private var localTarget: VideoSink? = null

    /** §19.5 the frozen-frame rule's input: when the current call's last remote frame was decoded. */
    fun lastRemoteFrameAt(): Long = current?.lastRemoteFrameAt() ?: 0

    /** Is the current local camera the front one (the preview is mirrored)? */
    val frontCamera: Boolean get() = current?.front ?: true

    /** Debug overlay data (§19.9): the last video stats line. */
    @Volatile var lastVideoStats: String? = null
        private set
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

    override fun open(callId: String, iceServers: List<IceServer>, listener: CallMedia.Listener, video: Boolean): MediaSession {
        check(available) { "WebRTC not loaded" }
        check(!video || videoAvailable) { "VP8 not available" }
        val relay = relayOnly()
        return Session(context.applicationContext, iceServers, relay, listener, debug, video) { lastStats = it }.also { current = it }
    }

    /** Forwards frames to the renderer attached now; the remote one also notes when the last frame arrived. */
    private inner class Proxy(private val remote: Boolean) : VideoSink {
        @Volatile var lastFrameAt = 0L

        override fun onFrame(frame: VideoFrame) {
            if (remote) lastFrameAt = System.currentTimeMillis()
            (if (remote) remoteTarget else localTarget)?.onFrame(frame)
        }
    }

    private inner class Session(
        private val context: Context,
        servers: List<IceServer>,
        private val relay: Boolean,
        private val listener: CallMedia.Listener,
        private val debug: Boolean,
        private val video: Boolean,
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
            .apply {
                // §19.0: VP8 in software on every phone (no hardware H264 surprises); codec preferences narrow it to VP8 + rtx.
                if (video) {
                    setVideoEncoderFactory(SoftwareVideoEncoderFactory())
                    setVideoDecoderFactory(SoftwareVideoDecoderFactory())
                }
            }
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
            // addTrack, not addTransceiver: only an addTrack transceiver is reused for the remote
            // offer's audio m-line (JSEP). nightly.16–21 used addTransceiver, so the callee's
            // setRemoteDescription(offer) created a second, track-less transceiver and the answer
            // said a=recvonly: only the caller was heard.
            pc.addTrack(track, listOf("risime"))
        }

        // ---- §19 video: one sendrecv video transceiver for the whole call, the camera on its sender ----

        private val remoteProxy = Proxy(remote = true)
        private val localProxy = Proxy(remote = false)
        private var videoTransceiver: RtpTransceiver? = null
        private var capturer: VideoCapturer? = null
        private var helper: SurfaceTextureHelper? = null
        private var videoSource: VideoSource? = null
        private var videoTrack: VideoTrack? = null
        private var cameraOn = false
        private var capturing = false
        @Volatile var front = true
        private var remoteAttached = false

        /** §19.4 (android A8): codec preferences VP8 then its rtx, nothing else, so offers and answers carry only those. */
        private fun preferVp8(t: RtpTransceiver) {
            val caps = factory.getRtpSenderCapabilities(livekit.org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val vp8 = caps.filter { it.name.equals("VP8", true) }
            val rtx = caps.filter { it.name.equals("rtx", true) }
            if (vp8.isEmpty()) error("no VP8 sender capability")
            t.setCodecPreferences(vp8 + rtx)
        }

        /**
         * The caller adds its video transceiver right before its first offer (after the audio
         * track, so m=audio comes first); the callee takes the one the remote offer created (an
         * added transceiver would never be matched to the offer's m-line, the nightly.16 audio bug).
         */
        private fun ensureVideo(offerer: Boolean) {
            if (!video) return
            val t = videoTransceiver ?: if (offerer) {
                pc.addTransceiver(
                    livekit.org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("risime")),
                )
            } else {
                pc.transceivers.firstOrNull { it.mediaType == livekit.org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && it.mid != null && !it.isStopped }
                    ?: return
            }
            videoTransceiver = t
            if (t.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            preferVp8(t)
            attachRemote(t)
            applySender()
        }

        private fun attachRemote(t: RtpTransceiver) {
            if (remoteAttached) return
            (t.receiver.track() as? VideoTrack)?.let {
                it.addSink(remoteProxy)
                remoteAttached = true
            }
        }

        /** setTrack(video) when the camera is on, setTrack(null) when off (§19.5: no renegotiation). */
        private fun applySender() {
            val sender = videoTransceiver?.sender ?: return
            if (cameraOn) {
                val track = videoTrack ?: return
                sender.setTrack(track, false)
                runCatching {
                    val p = sender.parameters
                    p.degradationPreference = livekit.org.webrtc.RtpParameters.DegradationPreference.BALANCED
                    p.encodings.firstOrNull()?.let { e ->
                        e.maxBitrateBps = if (metered()) WebRtcConfig.MOBILE_BPS else WebRtcConfig.WIFI_BPS
                        e.maxFramerate = 30
                    }
                    sender.parameters = p
                }.onFailure { Log.w("RisiMe", "video sender parameters: ${it.message}") }
            } else {
                sender.setTrack(null, false)
            }
        }

        private fun ensureCapturer(): Boolean {
            if (capturer != null) return true
            val fake = fakeCamera()
            val cap: VideoCapturer = if (fake) {
                DebugPatternCapturer()
            } else {
                val en = Camera2Enumerator(context)
                val names = en.deviceNames.toList()
                val name = names.firstOrNull { en.isFrontFacing(it) }?.also { front = true } ?: names.firstOrNull()?.also { front = false } ?: return false
                en.createCapturer(name, null) ?: return false
            }
            val src = factory.createVideoSource(false)
            val h = SurfaceTextureHelper.create("risime-camera", egl.eglBaseContext)
            cap.initialize(h, context, src.capturerObserver)
            val t = factory.createVideoTrack("risime-video", src)
            t.addSink(localProxy)
            capturer = cap
            helper = h
            videoSource = src
            videoTrack = t
            return true
        }

        override fun setCamera(on: Boolean) {
            if (!video) return
            if (on) {
                if (!ensureCapturer()) {
                    Log.w("RisiMe", "no camera on this phone: camera stays off")
                    return
                }
                if (!capturing) {
                    val (w, h, fps) = WebRtcConfig.captureFormat(metered() || relay, thermalSevere(context))
                    capturer?.startCapture(w, h, fps)
                    capturing = true
                }
            } else if (capturing) {
                runCatching { capturer?.stopCapture() }
                capturing = false
            }
            cameraOn = on
            applySender()
        }

        override fun switchCamera() {
            (capturer as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    front = isFrontCamera
                }

                override fun onCameraSwitchError(error: String?) {
                    Log.w("RisiMe", "switch camera: $error")
                }
            })
        }

        override fun lastRemoteFrameAt(): Long = remoteProxy.lastFrameAt

        private fun closeVideo() {
            runCatching { if (capturing) capturer?.stopCapture() }
            capturing = false
            runCatching { capturer?.dispose() }
            runCatching { videoTrack?.removeSink(localProxy) }
            runCatching { videoSource?.dispose() }
            runCatching { helper?.dispose() }
            capturer = null
            helper = null
            videoSource = null
            videoTrack = null
        }

        /**
         * The callee's answer always sends: the transceiver the remote offer created or matched
         * carries our microphone track and is sendrecv (defence in depth for the addTrack reuse).
         */
        private fun sendOnRemoteAudio() {
            for (t in pc.transceivers) {
                if (t.mediaType != livekit.org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO || t.mid == null || t.isStopped) continue
                if (t.sender.track() == null) t.sender.setTrack(track, false)
                if (t.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            }
            track.setEnabled(!mutedNow)
        }

        @Volatile private var mutedNow = false

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
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (video) "true" else "false")
            if (restart) mandatory += MediaConstraints.KeyValuePair("IceRestart", "true")
        }

        override suspend fun createOffer(iceRestart: Boolean): String {
            if (iceRestart) pc.restartIce()
            ensureVideo(offerer = true)
            val d = create(true, audioOnly(iceRestart))
            // crypto R5 + §16.9: strip the audio level, tune Opus, then set what we send.
            val sdp = SdpRules.prepareLocal(d.description)
            set(true, SessionDescription(SessionDescription.Type.OFFER, sdp))
            return sdp
        }

        override suspend fun createAnswer(): String {
            sendOnRemoteAudio()
            ensureVideo(offerer = false)
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
                val received = all.filter { it.type == "inbound-rtp" }.mapNotNull { (it.members["bytesReceived"] as? Number)?.toLong() }.takeIf { it.isNotEmpty() }?.sum()
                val sent = all.filter { it.type == "outbound-rtp" }.mapNotNull { (it.members["bytesSent"] as? Number)?.toLong() }.takeIf { it.isNotEmpty() }?.sum()
                if (debug) Log.d("RisiMe", "call stats: pair=$local/$remote rtt=${pair?.members?.get("currentRoundTripTime")} dtls=${transport?.members?.get("dtlsState")} srtp=${transport?.members?.get("srtpCipher")} sent=${sent ?: 0} recv=${received ?: 0}")
                // §19.9 the debug overlay: video codec, resolution, frame rate and bitrate (no addresses).
                if (video) {
                    val inV = all.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" }
                    val outV = all.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" }
                    val codec = (inV ?: outV)?.members?.get("codecId")?.let { id -> all.firstOrNull { it.id == id }?.members?.get("mimeType") }
                    lastVideoStats = "video $codec in ${inV?.members?.get("frameWidth")}x${inV?.members?.get("frameHeight")}@${inV?.members?.get("framesPerSecond")} " +
                        "decoded=${inV?.members?.get("framesDecoded")} out ${outV?.members?.get("frameWidth")}x${outV?.members?.get("frameHeight")}@${outV?.members?.get("framesPerSecond")} sent=${outV?.members?.get("bytesSent")}"
                    if (debug) Log.d("RisiMe", "call $lastVideoStats")
                }
                val st = DtlsStats(transport?.members?.get("dtlsState") as? String, transport?.members?.get("srtpCipher") as? String, fp, local, remote, received)
                onStats(st)
                cont.resume(st)
            }
        }

        override fun setMuted(muted: Boolean) {
            mutedNow = muted
            track.setEnabled(!muted)
        }

        override fun close() {
            if (current === this) current = null
            closeVideo()
            runCatching { pc.close() }
            runCatching { pc.dispose() }
            runCatching { source.dispose() }
            runCatching { factory.dispose() }
            runCatching { adm.release() }
        }
    }
}

/** §19.7: PowerManager thermal status ≥ SEVERE (API 29+). */
private fun thermalSevere(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT >= 29 &&
        (context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0) >= android.os.PowerManager.THERMAL_STATUS_SEVERE

/**
 * Debug builds only (`run-as <pkg> touch files/debug_fake_camera`, or a phone without a camera such
 * as redroid): a moving test pattern at the requested size and rate, so frames flow end to end
 * through VP8, SRTP and the peer's decoder. Never used in release builds.
 */
class DebugPatternCapturer : VideoCapturer {
    private var observer: livekit.org.webrtc.CapturerObserver? = null
    private var thread: android.os.HandlerThread? = null
    private var handler: android.os.Handler? = null
    @Volatile private var running = false
    private var n = 0

    override fun initialize(helper: SurfaceTextureHelper?, context: Context?, observer: livekit.org.webrtc.CapturerObserver?) {
        this.observer = observer
    }

    override fun startCapture(width: Int, height: Int, fps: Int) {
        if (running) return
        running = true
        val t = android.os.HandlerThread("risime-pattern").also { it.start() }
        thread = t
        val h = android.os.Handler(t.looper)
        handler = h
        val w = minOf(width, 640)
        val ht = minOf(height, 480)
        val period = 1000L / fps.coerceIn(1, 15)
        observer?.onCapturerStarted(true)
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val buf = livekit.org.webrtc.JavaI420Buffer.allocate(w, ht)
                val y = buf.dataY
                val shift = (n++ * 4) % w
                for (row in 0 until ht) for (col in 0 until w) y.put(row * buf.strideY + col, (((col + shift) * 255 / w) xor (row and 0x20)).toByte())
                val u = buf.dataU
                val v = buf.dataV
                for (i in 0 until u.capacity()) u.put(i, 90.toByte())
                for (i in 0 until v.capacity()) v.put(i, 200.toByte())
                val frame = VideoFrame(buf, 0, System.nanoTime())
                observer?.onFrameCaptured(frame)
                frame.release()
                h.postDelayed(this, period)
            }
        }
        h.post(tick)
    }

    override fun stopCapture() {
        running = false
        thread?.quitSafely()
        thread = null
        handler = null
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) = Unit

    override fun dispose() {
        running = false
        thread?.quitSafely()
    }

    override fun isScreencast(): Boolean = false
}
