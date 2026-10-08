package lk.codegen.risime.calls

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.RisiMeApp

/**
 * §16.9 (android R5): the call's foreground service. Ringing (incoming, outgoing, a push wake-up)
 * runs as `phoneCall` only; once RECORD_AUDIO is granted and the call is ours it is upgraded to
 * `phoneCall|microphone` (requesting the microphone type without the permission throws on 14+).
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val calls = (application as RisiMeApp).container.calls
        // startForeground must happen at once (the FCM window, the 5-s rule): build synchronously.
        val (notification, mic) = runBlocking { calls.serviceNotification() } ?: run {
            // Nothing to show: still satisfy startForeground, then stop.
            val n = calls.notifications.checking()
            startFg(n, false)
            stopSelf()
            return START_NOT_STICKY
        }
        // §23.5 (android A2): `mediaProjection` joins the types BEFORE getMediaProjection (Android 14 rule).
        val projection = calls.projectionWanted() && Build.VERSION.SDK_INT >= 29
        val ok = startFg(notification, mic, projection)
        calls.onServiceForeground(projection && ok)
        return START_NOT_STICKY
    }

    private fun startFg(n: android.app.Notification, mic: Boolean, projection: Boolean = false): Boolean {
        val type = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
                (if (projection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        } else if (projection && Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        return runCatching { ServiceCompat.startForeground(this, CallNotifications.CALL_ID, n, type) }
            .onFailure {
                Log.w("RisiMe", "call service startForeground($type): ${it.message}")
                // Never crash the ring: fall back to phoneCall only.
                if (mic || projection) runCatching { ServiceCompat.startForeground(this, CallNotifications.CALL_ID, n, if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0) }
            }.isSuccess
    }

    /** Decision 054: the app was swiped away during a call: end it and give the audio back. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Only the app itself swiped away ends the call; the call screen's own task closing never does.
        if (rootIntent?.component?.className == CallActivity::class.java.name) return super.onTaskRemoved(rootIntent)
        runCatching { (application as RisiMeApp).container.calls.onTaskRemoved() }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
