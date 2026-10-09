package lk.codegen.risime.calls

/**
 * The media side of a call (§16.9): WebRTC behind an interface, so the state machine and the
 * pipeline run on the JVM with a fake (the native library can't load there).
 */
interface CallMedia {
    /** Is the native library loaded (a 32-bit phone has none: it never advertises `calls`)? */
    val available: Boolean

    /** §19.1 (android A9): the VP8 encoder and decoder load (only then is `video` advertised). */
    val videoAvailable: Boolean get() = false

    /**
     * A new peer connection for one call: a fresh DTLS certificate (§16.10 c), encryption on (b),
     * the given ICE servers (STUN/TURN; empty = host candidates only). [video] (§19): one `m=audio`
     * and one `m=video` (VP8 and its rtx only, sendrecv for the whole call); the camera starts off.
     */
    fun open(callId: String, iceServers: List<IceServer>, listener: Listener, video: Boolean = false): MediaSession

    interface Listener {
        fun onLocalCandidate(c: CallEnvelope.Candidate)

        /** Gathering finished (end-of-candidates). */
        fun onGatheringDone()

        fun onIceState(state: IceState)
    }
}

data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

enum class IceState { NEW, CHECKING, CONNECTED, COMPLETED, DISCONNECTED, FAILED, CLOSED }

/** §16.10 (e): what `getStats()` says about the transport after ICE connected. */
data class DtlsStats(
    val dtlsState: String?,
    val srtpCipher: String?,
    val remoteFingerprint: String?,
    /** The selected candidate pair's types ("host", "srflx", "relay"), for the debug overlay and the device smoke test. */
    val localCandidateType: String? = null,
    val remoteCandidateType: String? = null,
    /** Audio bytes received so far (inbound-rtp of kind audio only: video bytes must not hide an audio stall, P0-3), for the decision-054 media-stall watchdog; null = unknown. */
    val bytesReceived: Long? = null,
)

interface MediaSession {
    /** A local offer, already prepared ([SdpRules.prepareLocal]) and set as the local description. */
    suspend fun createOffer(iceRestart: Boolean = false): String

    /** A local answer to the remote offer, prepared and set. */
    suspend fun createAnswer(): String

    /** The only remote-description source (§16.10 a): SDP from a decrypted, bound, pinned MLS envelope. */
    suspend fun setRemote(sdp: String, isOffer: Boolean)

    fun addRemoteCandidate(c: CallEnvelope.Candidate)

    suspend fun stats(): DtlsStats

    fun setMuted(muted: Boolean)

    /**
     * §19.5 camera on/off without renegotiation: on = start capture and `RtpSender.setTrack(video)`,
     * off = `setTrack(null)` and stop capture. Never called for a voice call.
     */
    fun setCamera(on: Boolean) = Unit

    /** §19.7 front/back (local only, no signal). */
    fun switchCamera() = Unit

    /** Device ms of the last decoded remote video frame (0 = none yet): the 3-s frozen-frame rule (§19.5). */
    fun lastRemoteFrameAt(): Long = 0

    /**
     * §23.3: this voice session may now carry video (the switch was accepted): the next local offer
     * (the caller's re-offer) adds one sendrecv VP8 transceiver after the audio one; a callee's
     * answer to the re-offer takes the transceiver the remote `m=video` created. Audio is untouched.
     */
    fun enableVideo() = Unit

    /** §23.3 rollback (decision 054 bound): drop an outstanding local offer; audio goes on as before. */
    suspend fun rollback() = Unit

    /**
     * §23.5 the screen instead of the camera on the one video sender (`setTrack`, screencast source,
     * MAINTAIN_RESOLUTION). [grant] is the platform's one-use consent (the MediaProjection result
     * Intent); [onStopped] runs when the platform stops the projection. False = it couldn't start.
     */
    fun startScreen(grant: Any, onStopped: () -> Unit): Boolean = false

    /** §23.5 stop the screen share (within 1 s); the sender goes back to whatever the camera says. */
    fun stopScreen() = Unit

    fun close()
}

/**
 * §19.5 (android A5): the peer's video shows while frames keep coming; its avatar shows on
 * `camera: false` and after [FROZEN_MS] without a decoded frame, and the video comes back on the
 * next frame (also when a lost `call_media` left [peerCamera] false: frames newer than the change win).
 */
object VideoRules {
    const val FROZEN_MS = 3_000L

    fun showPeerVideo(peerCamera: Boolean, cameraChangedAtMs: Long, lastFrameAtMs: Long, nowMs: Long): Boolean {
        if (lastFrameAtMs <= 0 || nowMs - lastFrameAtMs >= FROZEN_MS) return false
        return peerCamera || lastFrameAtMs > cameraChangedAtMs + 1_000
    }

    /**
     * §23.2 rendering rule (crypto C3): nothing is rendered in voice mode, whatever arrives; in video
     * mode [showPeerVideo] decides. A shared screen ([peerScreen], the peer's `call_media` says `screen`)
     * has no frozen rule: a screencast sends frames only when the screen changes, so a still screen
     * (a chat list, a document) sends none for seconds and must stay on, not turn into the avatar.
     */
    fun render(videoMode: Boolean, peerCamera: Boolean, cameraChangedAtMs: Long, lastFrameAtMs: Long, nowMs: Long, peerScreen: Boolean = false): Boolean =
        videoMode && (if (peerScreen) lastFrameAtMs > 0 else showPeerVideo(peerCamera, cameraChangedAtMs, lastFrameAtMs, nowMs))
}
