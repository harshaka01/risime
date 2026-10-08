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
class CallNotifications(private val context: Context, private val hideContent: () -> Boolean = { false }) {
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

        /** MainActivity extra on the missed-call "Call back" action: "voice" or "video" (with EXTRA_OPEN_CHAT). */
        const val EXTRA_CALL_BACK = "lk.codegen.risime.CALL_BACK"

        /** §23.5 Stop sharing from the ongoing notification. */
        const val ACTION_STOP_SHARE = "lk.codegen.risime.calls.STOP_SHARE"

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
    fun incoming(name: String?, video: Boolean = false, group: Boolean = false): Notification {
        ensureChannels()
        val person = Person.Builder().setName(name ?: "Incoming RisiMe call").setImportant(name != null).build()
        val n = NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_risime)
            .setContentTitle(name ?: "Incoming RisiMe call")
            .setContentText(if (name == null) "Unlock to answer" else incomingText(video, group))
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
    fun ongoing(name: String, status: String, connectedAtMs: Long?, sharing: Boolean = false): Notification {
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
            // §23.5: one notification while sharing, "Sharing your screen" with Stop.
            .apply { if (sharing) addAction(NotificationCompat.Action.Builder(null, "Stop sharing", broadcast(ACTION_STOP_SHARE, 5)).build()) }
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

    /**
     * "Missed voice call" / "Missed video call" with the caller's name and the time, and the actions
     * "Call back" (same type) and "Message". Decision 064: with the app lock on and "Show content in
     * notifications" off, no name (the type and time stay).
     */
    fun missed(conversationId: String, name: String, video: Boolean = false, atMs: Long = System.currentTimeMillis()): Notification {
        ensureChannels()
        val code = MISSED_BASE + (conversationId.hashCode() and 0xffff)
        fun intent(request: Int, callBack: Boolean): PendingIntent {
            val i = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(Notifier.EXTRA_OPEN_CHAT, conversationId)
                .apply { if (callBack) putExtra(EXTRA_CALL_BACK, if (video) "video" else "voice") }
            return PendingIntent.getActivity(context, request, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val time = lk.codegen.risime.ui.common.timeOf(atMs)
        val hidden = hideContent()
        return NotificationCompat.Builder(context, CH_MISSED)
            .setSmallIcon(R.drawable.ic_stat_risime)
            .setContentTitle(if (video) "Missed video call" else "Missed voice call")
            .setContentText(if (hidden) "Open RisiMe · $time" else "$name · $time")
            .setWhen(atMs)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(intent(code, callBack = false))
            .addAction(0, "Call back", intent(code + 0x10000, callBack = true))
            .addAction(0, "Message", intent(code + 0x20000, callBack = false))
            .build()
    }

    @Suppress("MissingPermission")
    fun postMissed(conversationId: String, name: String, video: Boolean = false, atMs: Long = System.currentTimeMillis()) {
        if (!notificationsAllowed()) return
        nm.notify(MISSED_BASE + (conversationId.hashCode() and 0xffff), missed(conversationId, name, video, atMs))
    }

    fun cancelMissed(conversationId: String) = nm.cancel(MISSED_BASE + (conversationId.hashCode() and 0xffff))
}

/** The incoming ring's text (§16.9, §19.6, §20.4): video calls say so, group calls too. */
fun incomingText(video: Boolean, group: Boolean = false): String = when {
    group && video -> "Incoming group video call"
    group -> "Incoming group voice call"
    video -> "Incoming video call"
    else -> "Incoming voice call"
}
