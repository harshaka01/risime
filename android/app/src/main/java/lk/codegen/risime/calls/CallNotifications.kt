package lk.codegen.risime.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import lk.codegen.risime.MainActivity
import lk.codegen.risime.R
import lk.codegen.risime.push.Notifier

/**
 * §16.9 call notifications: the `calls` channel (IMPORTANCE_HIGH, the default ringtone with
 * USAGE_NOTIFICATION_RINGTONE, vibration; ringer mode, DND and per-channel settings apply), an
 * insistent CallStyle for incoming calls (always with a full-screen intent), CallStyle ongoing as
 * the foreground-service notification, and "Missed call from <name>".
 */
class CallNotifications(private val context: Context) {
    companion object {
        const val CH_CALLS = "calls"
        /**
         * The ongoing-call notification (the foreground service's). Was "call_status" at
         * IMPORTANCE_LOW: collapsed at the bottom of the shade, so after Home the call looked gone.
         * Channel importance can't be raised after creation, hence a new id.
         */
        const val CH_CALL_STATUS = "call_ongoing"
        private const val CH_CALL_STATUS_OLD = "call_status"
        const val CH_MISSED = "missed_calls"
        const val CALL_ID = 50
        const val MISSED_BASE = 60_000

        const val ACTION_ANSWER = "lk.codegen.risime.calls.ANSWER"
        const val ACTION_DECLINE = "lk.codegen.risime.calls.DECLINE"
        const val ACTION_HANGUP = "lk.codegen.risime.calls.HANGUP"
        const val ACTION_SHOW = "lk.codegen.risime.calls.SHOW"

        val VIBRATION = longArrayOf(0, 800, 600, 800, 600)
    }

    private val nm = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val m = context.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(
            NotificationChannel(CH_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming RisiMe calls"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
                vibrationPattern = VIBRATION
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        runCatching { m.deleteNotificationChannel(CH_CALL_STATUS_OLD) }
        m.createNotificationChannel(
            NotificationChannel(CH_CALL_STATUS, "Call in progress", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        m.createNotificationChannel(NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Android 14+: may this app show full-screen intents (the user can revoke it)? Checked before every ring. */
    fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun notificationsAllowed(): Boolean =
        nm.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun activityIntent(action: String, code: Int): PendingIntent {
        val i = Intent(context, CallActivity::class.java).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun broadcast(action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code, Intent(context, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Incoming: CallStyle.forIncomingCall always with the full-screen intent (API 31+ throws
     * without one or a foreground service; the system makes it a heads-up when FSI isn't allowed).
     * Answer is an *activity* PendingIntent (android R5). [name] null = the locked ring (§16.8).
     */
    fun incoming(name: String?): Notification {
        ensureChannels()
        val person = Person.Builder().setName(name ?: "Incoming RisiMe call").setImportant(name != null).build()
        val n = NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_risime)
            .setContentTitle(name ?: "Incoming RisiMe call")
            .setContentText(if (name == null) "Unlock to answer" else "Incoming voice call")
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, broadcast(ACTION_DECLINE, 2), activityIntent(ACTION_ANSWER, 1)))
            .setFullScreenIntent(activityIntent(ACTION_SHOW, 3), true)
            .setContentIntent(activityIntent(ACTION_SHOW, 3))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        return n
    }

    /** Outgoing / connecting / active: CallStyle.forOngoingCall, the foreground-service notification. */
    fun ongoing(name: String, status: String, connectedAtMs: Long?): Notification {
        ensureChannels()
        val person = Person.Builder().setName(name).build()
        return NotificationCompat.Builder(context, CH_CALL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_risime)
            .setContentTitle(name)
            .setContentText(status)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, broadcast(ACTION_HANGUP, 4)))
            .setContentIntent(activityIntent(ACTION_SHOW, 3))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
            .apply { if (connectedAtMs != null) setWhen(connectedAtMs).setUsesChronometer(true) }
            .build()
    }

    /** A push wake-up while the app syncs (unlocked session): nothing rings yet. */
    fun checking(): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CH_CALL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_risime)
            .setContentTitle("RisiMe")
            .setContentText("Connecting a call…")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setSilent(true)
            .build()
    }

    @Suppress("MissingPermission")
    fun postMissed(conversationId: String, name: String) {
        if (!notificationsAllowed()) return
        ensureChannels()
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(Notifier.EXTRA_OPEN_CHAT, conversationId)
        val code = MISSED_BASE + (conversationId.hashCode() and 0xffff)
        nm.notify(
            code,
            NotificationCompat.Builder(context, CH_MISSED)
                .setSmallIcon(R.drawable.ic_stat_risime)
                .setContentTitle("Missed call from $name")
                .setContentText("Voice call")
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, code, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build(),
        )
    }

    fun cancelMissed(conversationId: String) = nm.cancel(MISSED_BASE + (conversationId.hashCode() and 0xffff))
}
