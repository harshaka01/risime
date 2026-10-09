package lk.codegen.risime.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.launch
import lk.codegen.risime.RisiMeApp
import lk.codegen.risime.data.tabs.ScheduleArmer
import lk.codegen.risime.data.tabs.isoToCalendarDay
import lk.codegen.risime.net.SetAlarmArgs
import java.util.concurrent.TimeUnit

/*
 * Contract v1.26 §26.6 on Android: an exact alarm (`setExactAndAllowWhileIdle`) when
 * SCHEDULE_EXACT_ALARM is granted, else WorkManager ("may be a few minutes late"); the send itself
 * runs in [ScheduledSendWorker] (normal send path, encrypted then). Re-armed after a reboot and an
 * update ([ScheduledRearmReceiver]) and at every process start.
 */
class AndroidScheduleArmer(private val context: Context) : ScheduleArmer {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    override fun exact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching { alarms.canScheduleExactAlarms() }.getOrDefault(false)

    private fun intent(scheduleId: String): PendingIntent = PendingIntent.getBroadcast(
        context, scheduleId.hashCode(),
        Intent(context, ScheduledSendReceiver::class.java).setAction(ACTION_SEND).putExtra(EXTRA_ID, scheduleId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun arm(scheduleId: String, atMs: Long) {
        val delay = (atMs - System.currentTimeMillis()).coerceAtLeast(0)
        if (exact()) {
            runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, intent(scheduleId)) }
                .onFailure { Log.w("RisiMe", "scheduled message: exact alarm refused (${it.javaClass.simpleName}); WorkManager instead") }
                .onSuccess { return }
        }
        // WorkManager fallback (inexact): one unique job per schedule.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "scheduled:$scheduleId", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScheduledSendWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
        )
    }

    override fun disarm(scheduleId: String) {
        runCatching { alarms.cancel(intent(scheduleId)) }
        runCatching { WorkManager.getInstance(context).cancelUniqueWork("scheduled:$scheduleId") }
    }

    companion object {
        const val ACTION_SEND = "lk.codegen.risime.SCHEDULED_SEND"
        const val EXTRA_ID = "schedule_id"
    }
}

/** The exact alarm fired: send what is due now (expedited work; the app may not be running). */
class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidScheduleArmer.ACTION_SEND) return
        ScheduledSendWorker.enqueueNow(context)
    }
}

/** §26.6 after a reboot or an app update: re-arm every pending schedule and send what is overdue. */
class ScheduledRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? RisiMeApp ?: return
        val pending = goAsync()
        app.container.scope.launch {
            try {
                runCatching { app.container.scheduled.rearmAll() }
                runCatching { app.container.flushOutboxAfterUpload() }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Sends the due scheduled messages through the normal send path, then gives the outbox a connection. */
class ScheduledSendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RisiMeApp).container
        val n = runCatching { c.scheduled.runDue() }.getOrDefault(0)
        if (n > 0) runCatching { c.flushOutboxAfterUpload() }
        Log.i("RisiMe", "scheduled message: worker queued $n")
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifier.SYNC_ID, (applicationContext as RisiMeApp).container.notifier.syncNotification())

    companion object {
        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "scheduled_send_now", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ScheduledSendWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build(),
            )
        }
    }
}

/** §26.6 `set_alarm`: `AlarmClock.ACTION_SET_ALARM` with skip UI, on this phone only; false: no Clock app. */
fun fireSetAlarm(context: Context, a: SetAlarmArgs): Boolean {
    val (h, m) = a.time.split(':').map { it.toInt() }
    val i = Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, h)
        .putExtra(AlarmClock.EXTRA_MINUTES, m)
        .putExtra(AlarmClock.EXTRA_MESSAGE, a.label)
        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    a.days?.takeIf { it.isNotEmpty() }?.let { d -> i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(d.distinct().map(::isoToCalendarDay))) }
    if (i.resolveActivity(context.packageManager) == null) return false
    return runCatching { context.startActivity(i) }.onFailure { Log.w("RisiMe", "set_alarm: ${it.javaClass.simpleName}") }.isSuccess
}
