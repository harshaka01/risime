package lk.codegen.risime.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import lk.codegen.risime.RisiMeApp

/** Decline and Hang up from the notification (Answer is an activity, android R5). */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = (context.applicationContext as RisiMeApp).container.calls
        when (intent.action) {
            CallNotifications.ACTION_DECLINE, CallNotifications.ACTION_HANGUP -> calls.hangUp()
        }
    }
}
