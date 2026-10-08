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
    /** Audio bytes received so far (inbound-rtp), for the decision-054 media-stall watchdog; null = unknown. */
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
}
