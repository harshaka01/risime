package lk.codegen.risime.calls

/**
 * The media side of a call (§16.9): WebRTC behind an interface, so the state machine and the
 * pipeline run on the JVM with a fake (the native library can't load there).
 */
interface CallMedia {
    /** Is the native library loaded (a 32-bit phone has none: it never advertises `calls`)? */
    val available: Boolean

    /**
     * A new peer connection for one call: a fresh DTLS certificate (§16.10 c), encryption on (b),
     * the given ICE servers (STUN/TURN; empty = host candidates only).
     */
    fun open(callId: String, iceServers: List<IceServer>, listener: Listener): MediaSession

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

    fun close()
}
