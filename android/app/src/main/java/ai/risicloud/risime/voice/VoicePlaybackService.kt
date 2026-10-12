package ai.risicloud.risime.voice

import ai.risicloud.risime.R
import ai.risicloud.risime.RisiMeApp
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * §37.3 "keeps playing with the screen off": while a voice note plays, a `mediaPlayback` foreground service holds a
 * media session (headset/lock-screen play-pause) and a quiet notification with Pause/Play and Stop. No audio and no
 * message text in the notification ("Voice note").
 */
class VoicePlaybackService : Service() {
    private var session: MediaSession? = null

    private val player get() = (application as RisiMeApp).container.voicePlayer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> player.pause()
            ACTION_PLAY -> player.resume()
            ACTION_STOP -> {
                player.stop()
                return START_NOT_STICKY
            }
        }
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
        val s = session ?: MediaSession(this, "risime-voice").also { ms ->
            ms.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = player.resume()

                override fun onPause() = player.pause()

                override fun onStop() = player.stop()
            })
            ms.isActive = true
            session = ms
        }
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        val n = notification(playing)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type) }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    private fun notification(playing: Boolean): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Voice notes", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        fun action(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, VoicePlaybackService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_risime)
            .setContentTitle(if (playing) "Playing voice note" else "Voice note paused")
            .setOngoing(playing)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, if (playing) "Pause" else "Play", action(if (playing) ACTION_PAUSE else ACTION_PLAY, 1))
            .addAction(0, "Stop", action(ACTION_STOP, 2))
            .build()
    }

    override fun onDestroy() {
        session?.let { runCatching { it.isActive = false; it.release() } }
        session = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "voice_playback"
        private const val NOTIFICATION_ID = 0x7631
        private const val ACTION_PAUSE = "ai.risicloud.risime.voice.PAUSE"
        private const val ACTION_PLAY = "ai.risicloud.risime.voice.PLAY"
        private const val ACTION_STOP = "ai.risicloud.risime.voice.STOP"
        private const val EXTRA_PLAYING = "playing"

        /** Start or refresh (playing / paused). A refused start (background limits) leaves playback as it is. */
        fun update(context: Context, playing: Boolean) {
            runCatching {
                context.startForegroundService(Intent(context, VoicePlaybackService::class.java).putExtra(EXTRA_PLAYING, playing))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VoicePlaybackService::class.java)) }
        }
    }
}
