package ai.risicloud.risime.voice

import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.media.Decrypted
import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** §37.3 playback speeds (remembered per app). */
object VoiceSpeeds {
    val ALL = listOf(1f, 1.5f, 2f)

    fun next(s: Float): Float = ALL[(ALL.indexOfFirst { it == s }.coerceAtLeast(0) + 1) % ALL.size]

    fun label(s: Float): String = when (s) {
        1.5f -> "1.5×"
        2f -> "2×"
        else -> "1×"
    }
}

/**
 * §37.3 auto-play: after [currentId] ends, the next received voice note of the chat that hasn't been played on this
 * phone. System lines, Risi's transcripts and voice notes already played (or mine) are passed over; any other message
 * ends the run (the notes must follow each other). [rows] is the chat in order.
 */
object VoiceQueue {
    fun next(rows: List<MessageEntity>, currentId: String, isTranscript: (MessageEntity) -> Boolean = { false }): MessageEntity? {
        val i = rows.indexOfFirst { it.clientMsgId == currentId }
        if (i < 0) return null
        for (m in rows.drop(i + 1)) {
            if (m.system || m.risiCtl || isTranscript(m)) continue
            if (!m.voice || m.showsAsDeleted) return null
            if (!m.outgoing && m.playedAt == null) return m
        }
        return null
    }
}

/** What the bubbles draw: the note being played (or paused), its position and the speed. */
data class VoicePlayState(
    val clientMsgId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val loading: Boolean = false,
    /** The proximity sensor is near: the earpiece is on. */
    val earpiece: Boolean = false,
    val error: String? = null,
)

/**
 * §37.3 the one voice-note player of the app. The note is decrypted **in memory** ([decrypt], after every §14.7
 * check) and handed to [MediaPlayer] through a [MediaDataSource]: the plaintext audio is never written to disk.
 * Starting a received note for the first time sends the `played` receipt ([markPlayed]); the next unplayed note of
 * the chat follows ([nextOf]); the screen may go off ([VoicePlaybackService] holds a media session); the proximity
 * sensor switches to the earpiece and back.
 */
class VoicePlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val decrypt: suspend (String) -> Decrypted,
    private val download: suspend (String) -> Boolean,
    private val markPlayed: suspend (String) -> Unit,
    private val nextOf: suspend (String) -> MessageEntity?,
    private val prefs: SharedPreferences?,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(VoicePlayState(speed = prefs?.getFloat(KEY_SPEED, 1f)?.takeIf { it in VoiceSpeeds.ALL } ?: 1f))
    val state: StateFlow<VoicePlayState> = _state

    private var player: MediaPlayer? = null
    private var bytes: ByteArray? = null
    private var ticker: Job? = null
    private var focus: AudioFocusRequest? = null
    private var proximityOn = false
    private var proximityLock: PowerManager.WakeLock? = null
    private val audio get() = context.getSystemService(AudioManager::class.java)

    /** Play/pause on a bubble: the same note toggles; another note stops this one and starts that one. */
    fun toggle(m: MessageEntity, durationMs: Long) {
        val s = _state.value
        if (s.clientMsgId == m.clientMsgId && player != null) {
            if (s.playing) pause() else resume()
            return
        }
        scope.launch(Dispatchers.Main) { start(m, durationMs, fromMs = if (s.clientMsgId == m.clientMsgId) s.positionMs else 0) }
    }

    /** The scrubber: [fraction] of [m]; starts it (paused at that point) when it isn't the current note. */
    fun seek(m: MessageEntity, durationMs: Long, fraction: Float) {
        val to = (durationMs * fraction.coerceIn(0f, 1f)).toLong()
        val s = _state.value
        val p = player
        if (s.clientMsgId == m.clientMsgId && p != null) {
            runCatching { p.seekTo(to.toInt()) }
            _state.value = s.copy(positionMs = to)
        } else {
            scope.launch(Dispatchers.Main) { start(m, durationMs, fromMs = to) }
        }
    }

    fun cycleSpeed() {
        val sp = VoiceSpeeds.next(_state.value.speed)
        runCatching { prefs?.edit()?.putFloat(KEY_SPEED, sp)?.apply() }
        _state.value = _state.value.copy(speed = sp)
        val p = player ?: return
        if (_state.value.playing) applySpeed(p, sp)
        log("voice: speed ${VoiceSpeeds.label(sp)}")
    }

    fun pause() {
        val p = player ?: return
        runCatching { p.pause() }
        ticker?.cancel()
        _state.value = _state.value.copy(playing = false, positionMs = runCatching { p.currentPosition.toLong() }.getOrDefault(_state.value.positionMs))
        proximity(false)
        VoicePlaybackService.update(context, playing = false)
    }

    fun resume() {
        val p = player ?: return
        if (!requestFocus()) return
        applySpeed(p, _state.value.speed)
        runCatching { p.start() }
        _state.value = _state.value.copy(playing = true)
        tick()
        proximity(true)
        VoicePlaybackService.update(context, playing = true)
    }

    /** Stops and forgets the current note (a deleted or purged note, leaving a chat, the notification's Stop). */
    fun stop() {
        release()
        _state.value = VoicePlayState(speed = _state.value.speed)
        VoicePlaybackService.stop(context)
    }

    /** §15.6: a purged note stops at once. */
    fun purged(clientMsgId: String) {
        if (_state.value.clientMsgId == clientMsgId) stop()
    }

    private suspend fun start(m: MessageEntity, durationMs: Long, fromMs: Long) {
        release()
        _state.value = VoicePlayState(clientMsgId = m.clientMsgId, durationMs = durationMs, positionMs = fromMs, speed = _state.value.speed, loading = true)
        var d = withContext(Dispatchers.IO) { decrypt(m.clientMsgId) }
        if (d is Decrypted.Missing && withContext(Dispatchers.IO) { download(m.clientMsgId) }) d = withContext(Dispatchers.IO) { decrypt(m.clientMsgId) }
        if (_state.value.clientMsgId != m.clientMsgId) return // another note was tapped meanwhile
        val ok = d as? Decrypted.Ok ?: run {
            _state.value = _state.value.copy(loading = false, error = if (d is Decrypted.Missing) NOT_ON_PHONE else CANT_PLAY)
            return
        }
        bytes = ok.bytes
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(attributes(earpiece = false))
            p.setDataSource(BytesSource(ok.bytes))
            p.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            p.prepare()
        } catch (e: Exception) {
            log("voice: cannot play ${m.clientMsgId.take(8)} (${e.javaClass.simpleName})")
            runCatching { p.release() }
            wipe()
            _state.value = _state.value.copy(loading = false, error = CANT_PLAY)
            return
        }
        p.setOnCompletionListener { onComplete(m) }
        player = p
        current = m
        val dur = runCatching { p.duration.toLong() }.getOrDefault(durationMs).takeIf { it > 0 } ?: durationMs
        if (fromMs in 1 until dur) runCatching { p.seekTo(fromMs.toInt()) }
        _state.value = _state.value.copy(loading = false, durationMs = dur)
        resume()
        log("voice: play ${m.clientMsgId.take(8)} speed=${_state.value.speed}")
        // §37.4: the first start of a received note on this phone sends `played` (never for my own notes).
        if (!m.outgoing && m.playedAt == null) scope.launch(Dispatchers.IO) { runCatching { markPlayed(m.clientMsgId) } }
    }

    private fun onComplete(m: MessageEntity) {
        ticker?.cancel()
        release()
        _state.value = VoicePlayState(speed = _state.value.speed)
        scope.launch(Dispatchers.Main) {
            val next = runCatching { withContext(Dispatchers.IO) { nextOf(m.clientMsgId) } }.getOrNull()
            if (next != null) {
                val dur = ai.risicloud.risime.data.media.VoiceMeta.decode(next.systemJson)?.durationMs ?: 0
                log("voice: auto-play next ${next.clientMsgId.take(8)}")
                start(next, dur, 0)
            } else {
                VoicePlaybackService.stop(context)
            }
        }
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val p = player ?: break
                val pos = runCatching { p.currentPosition.toLong() }.getOrNull() ?: break
                _state.value = _state.value.copy(positionMs = pos)
                delay(100)
            }
        }
    }

    private fun applySpeed(p: MediaPlayer, speed: Float) {
        runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
    }

    private fun attributes(earpiece: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (earpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus(): Boolean {
        val am = audio ?: return true
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes(false))
            .setOnAudioFocusChangeListener { change -> if (change < 0) scope.launch(Dispatchers.Main) { pause() } }
            .build().also { focus = it }
        return runCatching { am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_FAILED }.getOrDefault(true)
    }

    // ---- the proximity sensor: the earpiece at the ear, the speaker away from it ----

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val max = e.sensor.maximumRange
            val near = e.values.firstOrNull()?.let { it < max && it < 5f } ?: false
            if (near != _state.value.earpiece) scope.launch(Dispatchers.Main) { route(near) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun proximity(on: Boolean) {
        if (on == proximityOn) return
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
        proximityOn = on
        if (on) {
            sm.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            sm.unregisterListener(sensorListener)
            if (_state.value.earpiece) route(false)
        }
    }

    /** Re-routes the playing note: the earpiece (screen off at the ear) or the speaker; it resumes where it was. */
    private fun route(earpiece: Boolean) {
        val p = player ?: return
        val data = bytes ?: return
        val pos = runCatching { p.currentPosition }.getOrDefault(0)
        val wasPlaying = _state.value.playing
        val am = audio
        runCatching { am?.mode = if (earpiece) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL }
        @Suppress("DEPRECATION")
        runCatching { am?.isSpeakerphoneOn = !earpiece }
        screenOffLock(earpiece)
        val q = MediaPlayer()
        try {
            q.setAudioAttributes(attributes(earpiece))
            q.setDataSource(BytesSource(data))
            q.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            q.prepare()
            q.seekTo(pos)
        } catch (e: Exception) {
            runCatching { q.release() }
            return
        }
        runCatching { p.setOnCompletionListener(null); p.stop(); p.release() }
        player = q
        current?.let { m -> q.setOnCompletionListener { onComplete(m) } }
        _state.value = _state.value.copy(earpiece = earpiece)
        if (wasPlaying) {
            applySpeed(q, _state.value.speed)
            runCatching { q.start() }
        }
        log("voice: route ${if (earpiece) "earpiece" else "speaker"}")
    }

    /** The note in [player]. */
    private var current: MessageEntity? = null

    private fun screenOffLock(on: Boolean) {
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        if (on) {
            if (proximityLock == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "risime:voice-ear").also { runCatching { it.acquire(30 * 60_000L) } }
            }
        } else {
            proximityLock?.let { l -> runCatching { if (l.isHeld) l.release() } }
            proximityLock = null
        }
    }

    private fun release() {
        ticker?.cancel()
        proximity(false)
        screenOffLock(false)
        player?.let { p -> runCatching { p.setOnCompletionListener(null); p.stop() }; runCatching { p.release() } }
        player = null
        current = null
        if (_state.value.earpiece) {
            runCatching { audio?.mode = AudioManager.MODE_NORMAL }
        }
        focus?.let { f -> runCatching { audio?.abandonAudioFocusRequest(f) } }
        wipe()
    }

    private fun wipe() {
        bytes?.fill(0)
        bytes = null
    }

    /** The decrypted note, served from memory (never a file). */
    private class BytesSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() = Unit
    }

    companion object {
        const val KEY_SPEED = "voice_speed"
        const val NOT_ON_PHONE = "Downloading…"
        const val CANT_PLAY = "Can't play this voice note"
    }
}
