package lk.codegen.risime.update

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import lk.codegen.risime.RisiMeApp

/**
 * P0-1: the update's download → verify → install, in an expedited WorkManager job that runs in the
 * foreground (dataSync, a progress notification with Cancel). It outlives the screen: Back, Home,
 * rotation or a killed process don't lose it (WorkManager runs it again and the download resumes
 * from the partial file). The release comes from [UpdateStore.pending], so a re-run after process
 * death needs no input.
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val updater get() = (applicationContext as RisiMeApp).container.updater

    override suspend fun doWork(): Result {
        val u = updater
        if (!u.enabled) return Result.success()
        runCatching { setForeground(getForegroundInfo()) }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        try {
            u.runUpdate { info, step, percent -> u.notifications.showProgress(u.notifications.progress(info, step, percent, cancel)) }
        } finally {
            u.notifications.cancelProgress()
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val u = updater
        val n = u.notifications.progress(u.state.value.infoOrNull(), "Starting the download…", null, null)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(UpdateNotifications.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(UpdateNotifications.PROGRESS_ID, n)
        }
    }

    companion object {
        const val NAME = "risime-update"

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
        }
    }
}
