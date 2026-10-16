package ai.risicloud.risime.calls

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import ai.risicloud.risime.MainActivity
import ai.risicloud.risime.R

/**
 * P0 calls (Harsha 2026-10-16): Settings → Calls → "Test incoming call". In [DELAY_MS] the phone rings
 * the way a real incoming call does: the same `calls` channel (its importance, ringtone and vibration,
 * so a channel the user or the phone muted stays silent here too), an insistent CallStyle with the
 * full-screen intent, Answer and Decline. It is local only: nothing goes to the server or a peer.
 * An exact alarm wakes the app (the user may lock the phone during the wait); without the exact-alarm
 * permission an inexact one (a few seconds late at most while the screen is on).
 */
object TestRing {
    const val ACTION_FIRE = "ai.risicloud.risime.calls.TEST_RING"
    const val ACTION_ANSWER = "ai.risicloud.risime.calls.TEST_RING_ANSWER"
    const val ACTION_DECLINE = "ai.risicloud.risime.calls.TEST_RING_DECLINE"
    const val NOTIFICATION_ID = 51
    const val DELAY_MS = 5_000L
    /** The test ring stops by itself after this (a real ring's validity is 45 s). */
    const val RING_MS = 30_000L
    const val TITLE = "RisiMe test call"

    private fun pending(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code, Intent(context, TestRingReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Arms the ring [DELAY_MS] from now. */
    @SuppressLint("MissingPermission")
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + DELAY_MS
        val pi = pending(context, ACTION_FIRE, 7_001)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching { alarms.canScheduleExactAlarms() }.getOrDefault(false)
        val ok = runCatching {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }.isSuccess
        Log.i("RisiMe", "calls: test ring armed in $DELAY_MS ms (exact=$exact ok=$ok)")
    }

    /** The test ring's notification: the real incoming one's channel, style, flags and full-screen intent. */
    fun notification(context: Context): Notification {
        val calls = CallNotifications(context)
        calls.ensureChannels()
        val person = Person.Builder().setName(TITLE).setImportant(true).build()
        val open = PendingIntent.getActivity(
            context, 7_004,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CallNotifications.CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_risime)
            .setContentTitle(TITLE)
            .setContentText("Ringing works on this phone. Tap Answer or Decline.")
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, pending(context, ACTION_DECLINE, 7_002), pending(context, ACTION_ANSWER, 7_003)))
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(RING_MS)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        return n
    }

    @SuppressLint("MissingPermission")
    fun ring(context: Context): Boolean {
        val calls = CallNotifications(context)
        if (!calls.notificationsAllowed()) {
            Log.w("RisiMe", "calls: test ring: notifications are off")
            return false
        }
        if (!calls.canUseFullScreenIntent()) Log.w("RisiMe", "calls: test ring: full-screen intent not allowed (heads-up only)")
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification(context))
        Log.i("RisiMe", "calls: test ring posted")
        return true
    }

    fun stop(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}

/** The test ring's alarm and its Answer / Decline buttons. */
class TestRingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            TestRing.ACTION_FIRE -> TestRing.ring(context)
            TestRing.ACTION_ANSWER, TestRing.ACTION_DECLINE -> {
                TestRing.stop(context)
                Log.i("RisiMe", "calls: test ring ${if (intent.action == TestRing.ACTION_ANSWER) "answered" else "declined"}")
                runCatching { Toast.makeText(context, "Test call: this phone rings for RisiMe calls.", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
