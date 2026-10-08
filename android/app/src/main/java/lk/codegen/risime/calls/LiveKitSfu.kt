package lk.codegen.risime.calls

import android.content.Context
import android.util.Log
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.E2EEState
import io.livekit.android.e2ee.KeyProvider
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.CustomVideoPreset
import io.livekit.android.room.track.VideoCaptureParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import livekit.LivekitModels
import livekit.org.webrtc.FrameCryptor
import livekit.org.webrtc.PeerConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * §20.5 the real SFU: livekit-android 2.29.0 (decision 057) on the app's libwebrtc build.
 * - Frame E2EE always on: `E2EEOptions` with a per-participant `BaseKeyProvider` (no shared key, no
 *   auto-ratchet, a 16-key ring, frames dropped while a cryptor isn't ready, salt `risime-call-v1`).
 *   Keys come only from MLS ([FrameKeyRing] through [SfuSession.keys]).
 * - Our coturn credentials as the ICE servers (LiveKit's own list only when we have none).
 * - No audio handler: core-telecom owns focus, mode and routing (as for 1:1 calls, decision 054).
 * - No data channel, chat or metadata use (android A7).
 */
class LiveKitSfu(
    private val context: Context,
    /** Debug builds only: TURN relay candidates only (§20.5 "a debug-only relay-only switch must work"). */
    private val relayOnly: () -> Boolean = { false },
    private val debug: Boolean = false,
    /** Debug builds only: a phone without a camera (redroid) sends the v1.18 test pattern instead. */
    private val fakeCamera: () -> Boolean = { false },
) : SfuConnector {
    private val tag = "RisiMe"

    override val available: Boolean by lazy {
        runCatching {
            // The native library and the frame cryptor classes load (32-bit phones have neither).
            LiveKit.init(context.applicationContext)
            Class.forName("livekit.org.webrtc.FrameCryptorFactory")
            Class.forName("io.livekit.android.e2ee.E2EEManager")
            true
        }.onFailure { Log.w(tag, "LiveKit unavailable: ${it.javaClass.simpleName}: ${it.message}") }.getOrDefault(false)
    }

    override suspend fun connect(p: SfuConnect, listener: SfuListener): SfuSession {
        val keyProvider = RisiKeyProvider(
            BaseKeyProvider(
                ratchetSalt = RATCHET_SALT,
                ratchetWindowSize = 0,
                enableSharedKey = false,
                keyRingSize = FrameKeyRing.RING_SIZE,
                discardFrameWhenCryptorNotReady = true,
            ),
        )
        val room = LiveKit.create(
            context.applicationContext,
            RoomOptions(
                adaptiveStream = true,
                dynacast = true,
                e2eeOptions = E2EEOptions(keyProvider = keyProvider, encryptionType = LivekitModels.Encryption.Type.GCM),
                // Opus with DTX and in-band FEC; no RED (redundant audio around encrypted frames).
                audioTrackPublishDefaults = AudioTrackPublishDefaults(audioBitrate = 32_000, dtx = true, red = false),
                // §20.5 (android A6): VP8, two simulcast layers (320×180 at 150 kbit/s, 640×360 at 500 kbit/s).
                videoTrackPublishDefaults = VideoTrackPublishDefaults(
                    videoEncoding = VideoEncoding(500_000, 24),
                    simulcast = true,
                    videoCodec = "vp8",
                    simulcastLayers = listOf(CustomVideoPreset(VideoCaptureParameter(320, 180, 15), VideoEncoding(150_000, 15))),
                ),
            ),
            LiveKitOverrides(audioOptions = AudioOptions(audioHandler = NoAudioHandler())),
        )
        val session = LiveKitSession(room, keyProvider, listener, p.video, debug && fakeCamera())
        try {
            val servers = p.iceServers.map { s ->
                PeerConnection.IceServer.builder(s.urls).apply {
                    s.username?.let { setUsername(it) }
                    s.credential?.let { setPassword(it) }
                }.createIceServer()
            }
            // LiveKit 2.29 uses ConnectOptions.iceServers only together with an rtcConfig.
            val rtc = servers.takeIf { it.isNotEmpty() }?.let { list ->
                PeerConnection.RTCConfiguration(list).apply {
                    if (debug && relayOnly()) iceTransportsType = PeerConnection.IceTransportsType.RELAY
                }
            }
            session.start()
            withTimeout(CONNECT_MS) { room.connect(p.url, p.token, ConnectOptions(autoSubscribe = true, rtcConfig = rtc)) }
            // Fail closed: no frame encryption → no call.
            if (room.e2eeManager == null) throw IllegalStateException("frame encryption is not available")
            session.connected()
            return session
        } catch (e: Throwable) {
            session.disconnect()
            throw if (e is Exception) e else IllegalStateException(e)
        }
    }

    companion object {
        /** §20.5 (crypto K6). */
        const val RATCHET_SALT = "risime-call-v1"
        const val CONNECT_MS = 15_000L
    }
}

/**
 * The key provider the room's frame cryptors use: LiveKit's `BaseKeyProvider` for the keys, and our
 * own sending index (a sender cryptor created later, e.g. the camera after a rekey, starts at it).
 */
private class RisiKeyProvider(private val base: BaseKeyProvider) : KeyProvider by base {
    @Volatile var ownIndex = 0

    override fun getLatestKeyIndex(participantId: String): Int = ownIndex
}

private class LiveKitSession(
    private val room: Room,
    private val keyProvider: RisiKeyProvider,
    private val listener: SfuListener,
    private val video: Boolean,
    private val fakeCamera: Boolean,
) : SfuSession, LiveKitRoomHolder {
    private val tag = "RisiMe"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cryptors = ConcurrentHashMap<String, CryptorState>() // track sid → state
    private val playable = ConcurrentHashMap<String, Boolean>()
    @Volatile private var closed = false
    private var ticker: Job? = null

    override val localIdentity: String get() = room.localParticipant.identity?.value.orEmpty()

    override val keys: FrameKeySink = object : FrameKeySink {
        override fun setKey(identity: String, keyText: String, index: Int) {
            if (closed) return
            // The rtc provider directly: LiveKit's own "latest index" bookkeeping is replaced by RisiKeyProvider.
            keyProvider.rtcKeyProvider.setKey(identity, index, keyText.toByteArray(Charsets.US_ASCII))
        }

        override fun setOwnIndex(index: Int) {
            if (closed) return
            keyProvider.ownIndex = index
            senderCryptors().forEach { runCatching { it.keyIndex = index } }
        }
    }

    /**
     * The sender frame cryptors of this device (E2EEManager keeps them private in 2.29): their key
     * index must follow the epoch at once (§20.6 K3). Release builds keep the class names (no R8).
     */
    @Suppress("UNCHECKED_CAST")
    private fun senderCryptors(): List<FrameCryptor> = runCatching {
        val mgr = room.e2eeManager ?: return emptyList()
        val f = mgr.javaClass.getDeclaredField("frameCryptors").apply { isAccessible = true }
        val map = f.get(mgr) as Map<Pair<String, Participant.Identity>, FrameCryptor>
        val me = room.localParticipant.identity
        map.filterKeys { it.second == me }.values.toList()
    }.onFailure { Log.w(tag, "group call: sender cryptors: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())

    fun start() {
        scope.launch {
            room.events.collect { e ->
                when (e) {
                    is RoomEvent.Reconnecting -> listener.onConnection(SfuConnection.RECONNECTING)
                    is RoomEvent.Reconnected -> listener.onConnection(SfuConnection.CONNECTED)
                    is RoomEvent.Disconnected -> if (!closed) listener.onConnection(SfuConnection.DISCONNECTED)
                    is RoomEvent.TrackE2EEStateEvent -> {
                        cryptors[e.publication.sid] = when (e.state) {
                            E2EEState.NEW -> CryptorState.NEW
                            E2EEState.OK, E2EEState.KEY_RATCHETED -> CryptorState.OK
                            E2EEState.MISSING_KEY -> CryptorState.MISSING_KEY
                            else -> CryptorState.FAILED
                        }
                        if (e.state != E2EEState.OK) Log.i(tag, "group call: ${e.participant.identity?.value} ${e.publication.kind} cryptor ${e.state}")
                    }
                    is RoomEvent.TrackSubscribed -> {
                        // K6/K7: a track of a participant that isn't playable is dropped at once.
                        val id = e.participant.identity?.value
                        if (id != null && playable[id] == false) (e.publication as? RemoteTrackPublication)?.setSubscribed(false)
                        if (e.track is RemoteAudioTrack && (id == null || playable[id] == false || !encrypted(e.publication))) (e.track as RemoteAudioTrack).setVolume(0.0)
                        Log.i(tag, "group call: subscribed ${e.publication.kind} of $id (encrypted=${encrypted(e.publication)})")
                    }
                    else -> Unit
                }
                emit()
            }
        }
    }

    fun connected() {
        listener.onConnection(SfuConnection.CONNECTED)
        emit()
        // Speaking and audio levels change without events: refresh the list every 500 ms.
        ticker = scope.launch {
            while (!closed) {
                delay(500)
                emit()
            }
        }
    }

    private fun state(pubs: Collection<TrackPublication>): CryptorState {
        val states = pubs.mapNotNull { cryptors[it.sid] }
        return when {
            pubs.isEmpty() -> CryptorState.NONE
            states.isEmpty() -> CryptorState.NEW
            states.any { it == CryptorState.FAILED } -> CryptorState.FAILED
            states.any { it == CryptorState.MISSING_KEY } -> CryptorState.MISSING_KEY
            states.any { it == CryptorState.NEW } -> CryptorState.NEW
            else -> CryptorState.OK
        }
    }

    private fun describe(p: Participant, local: Boolean): SfuParticipant? {
        val id = p.identity?.value ?: return null
        val audio = p.audioTrackPublications.map { it.first }
        val vid = p.videoTrackPublications.map { it.first }
        val all = audio + vid
        return SfuParticipant(
            identity = id,
            local = local,
            speaking = p.isSpeaking,
            audioLevel = p.audioLevel,
            hasAudio = audio.isNotEmpty(),
            audioMuted = audio.isNotEmpty() && audio.all { it.muted },
            hasVideo = vid.isNotEmpty(),
            videoMuted = vid.isNotEmpty() && vid.all { it.muted },
            // K6: every published track must say it is end-to-end encrypted.
            encrypted = all.all { encrypted(it) },
            cryptor = state(all),
        )
    }

    private fun emit() {
        if (closed) return
        val list = buildList {
            describe(room.localParticipant, true)?.let(::add)
            room.remoteParticipants.values.forEach { r -> describe(r, false)?.let(::add) }
        }
        listener.onParticipants(list)
    }

    override suspend fun publishMic(): Boolean = runCatching { room.localParticipant.setMicrophoneEnabled(true) }
        .onFailure { Log.w(tag, "group call: microphone: ${it.message}") }.getOrDefault(false)

    override fun setMicMuted(muted: Boolean) {
        scope.launch { runCatching { room.localParticipant.setMicrophoneEnabled(!muted) } }
    }

    private var patternTrack: io.livekit.android.room.track.LocalVideoTrack? = null

    override suspend fun setCamera(on: Boolean): Boolean {
        if (!video) return false
        Log.i(tag, "group call: camera ${if (on) "on" else "off"}${if (fakeCamera) " (debug pattern)" else ""}")
        if (!fakeCamera) return runCatching { room.localParticipant.setCameraEnabled(on) }.getOrDefault(false)
        // Debug test pattern (no camera on redroid): our own capturer as the camera track.
        return runCatching {
            val lp = room.localParticipant
            if (on) {
                if (patternTrack != null) return@runCatching true
                val t = lp.createVideoTrack("camera", DebugPatternCapturer())
                t.startCapture()
                patternTrack = t
                lp.publishVideoTrack(
                    t,
                    io.livekit.android.room.participant.VideoTrackPublishOptions(null, room.videoTrackPublishDefaults, io.livekit.android.room.track.Track.Source.CAMERA),
                )
            } else {
                patternTrack?.let { t ->
                    lp.unpublishTrack(t)
                    runCatching { t.stopCapture() }
                }
                patternTrack = null
                true
            }
        }.onFailure { Log.w(tag, "group call: pattern camera: ${it.message}") }.getOrDefault(false)
    }

    /** K7: a participant that isn't a leaf of the group: every track unsubscribed (and silent). */
    override fun setPlayable(identity: String, playable: Boolean) {
        val before = this.playable.put(identity, playable)
        if (before == playable) return
        if (before != null || !playable) Log.i(tag, "group call: $identity ${if (playable) "playable" else "not played (not a member)"}")
        val p = room.remoteParticipants[Participant.Identity(identity)] ?: return
        p.trackPublications.values.forEach { pub ->
            (pub as? RemoteTrackPublication)?.setSubscribed(playable)
            ((pub.track) as? RemoteAudioTrack)?.setVolume(if (playable && encrypted(pub)) 1.0 else 0.0)
        }
    }

    /** K6: LiveKit's track info says the track is end-to-end encrypted. */
    private fun encrypted(pub: TrackPublication) = pub.encryptionType != LivekitModels.Encryption.Type.NONE

    override suspend fun stats(): List<SfuStats> = room.remoteParticipants.values.mapNotNull { p ->
        val id = p.identity?.value ?: return@mapNotNull null
        val audioPub = p.audioTrackPublications.firstOrNull()
        val audio = audioPub?.second
        val report = runCatching { audio?.getRTCStats() }.getOrNull()
        val audioIn = report?.statsMap?.values?.filter { it.type == "inbound-rtp" && it.members["kind"] == "audio" }.orEmpty()
        // This participant's receiver (the report may hold every inbound stream of the subscriber connection).
        val inbound = (audioIn.firstOrNull { it.members["trackIdentifier"] == audioPub?.first?.sid } ?: audioIn.singleOrNull())?.members
        fun n(k: String) = (inbound?.get(k) as? Number)
        val videoReport = runCatching { p.videoTrackPublications.firstOrNull()?.second?.getRTCStats() }.getOrNull()
        val vin = videoReport?.statsMap?.values?.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" }?.members
        SfuStats(
            identity = id,
            cryptor = state(p.audioTrackPublications.map { it.first } + p.videoTrackPublications.map { it.first }),
            audioPackets = n("packetsReceived")?.toLong() ?: 0,
            audioBytes = n("bytesReceived")?.toLong() ?: 0,
            audioEnergy = n("totalAudioEnergy")?.toDouble() ?: 0.0,
            concealed = n("concealedSamples")?.toLong() ?: 0,
            // Frames that passed the frame cryptor reach the jitter buffer; failed ones never do.
            jitterEmitted = n("jitterBufferEmittedCount")?.toLong() ?: 0,
            videoFrames = (vin?.get("framesDecoded") as? Number)?.toLong() ?: 0,
            videoWidth = (vin?.get("frameWidth") as? Number)?.toInt() ?: 0,
            videoHeight = (vin?.get("frameHeight") as? Number)?.toInt() ?: 0,
        )
    }

    /** The room (for the video renderers of the call screen). */
    override fun room(): Room = room

    override fun disconnect() {
        if (closed) return
        closed = true
        ticker?.cancel()
        runCatching { room.disconnect() }
        runCatching { room.release() }
        scope.cancel()
    }
}
