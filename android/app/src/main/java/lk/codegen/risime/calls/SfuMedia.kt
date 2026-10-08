package lk.codegen.risime.calls

/**
 * §20.5 the SFU side of a group call (LiveKit) behind an interface, so the group call machine runs
 * on the JVM with a fake (the native library can't load there). [LiveKitSfu] is the real one.
 */
interface SfuConnector {
    /** The LiveKit SDK and its frame encryption load on this phone (only then is `group_calls` advertised). */
    val available: Boolean

    /**
     * Connects to the room with frame encryption on (`E2EEOptions`, an empty per-participant key
     * provider: keys come from [SfuSession.keys]) and [SfuConnect.iceServers] as ICE servers. Throws
     * when the room can't be reached or the frame encryption isn't there (fail closed).
     */
    suspend fun connect(p: SfuConnect, listener: SfuListener): SfuSession
}

data class SfuConnect(
    val url: String,
    val token: String,
    val iceServers: List<IceServer>,
    val video: Boolean,
) {
    override fun toString() = "SfuConnect($url, ${iceServers.size} ICE servers, video=$video)" // never the token
}

enum class SfuConnection { CONNECTED, RECONNECTING, DISCONNECTED }

/** A track's frame cryptor (LiveKit's `E2EEState`), reduced to what the UI rules need (crypto K6, K9). */
enum class CryptorState { NONE, NEW, OK, MISSING_KEY, FAILED }

/** One participant as LiveKit sees it (the app names it from its own database, android A7). */
data class SfuParticipant(
    val identity: String,
    val local: Boolean,
    val speaking: Boolean = false,
    val audioLevel: Float = 0f,
    /** A microphone track is published (and, for remotes, subscribed). */
    val hasAudio: Boolean = false,
    val audioMuted: Boolean = false,
    val hasVideo: Boolean = false,
    val videoMuted: Boolean = false,
    /** Every published track says it is end-to-end encrypted (LiveKit track info, K6). */
    val encrypted: Boolean = true,
    /** The worst state of this participant's frame cryptors (NONE: no track yet). */
    val cryptor: CryptorState = CryptorState.NONE,
)

interface SfuListener {
    fun onParticipants(list: List<SfuParticipant>)

    fun onConnection(state: SfuConnection)
}

interface SfuSession {
    /** The participant identity the room knows this device by (`<user_id>/<device_id>`). */
    val localIdentity: String

    /** The frame key provider of this room (and the sender cryptors' key index). */
    val keys: FrameKeySink

    /** Publishes the microphone (frame-encrypted). False = it couldn't. */
    suspend fun publishMic(): Boolean

    fun setMicMuted(muted: Boolean)

    /** §19.5 rules: on only while wanted and the call screen is visible. False = it couldn't. */
    suspend fun setCamera(on: Boolean): Boolean = false

    fun switchCamera() = Unit

    /**
     * K6/K7: a participant that isn't a leaf of the group, or whose tracks say they aren't
     * encrypted, is never played or rendered ([playable] false unsubscribes its tracks).
     */
    fun setPlayable(identity: String, playable: Boolean)

    /** Debug and device-test evidence: per remote identity, what the receivers decoded so far. */
    suspend fun stats(): List<SfuStats> = emptyList()

    fun disconnect()
}

/** Inbound audio (and video) counters of one remote participant (from WebRTC stats). */
data class SfuStats(
    val identity: String,
    val cryptor: CryptorState,
    val audioPackets: Long,
    val audioBytes: Long,
    val audioEnergy: Double,
    val concealed: Long,
    val videoFrames: Long = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)
