package lk.codegen.risime.push

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import lk.codegen.risime.RisiMeApp
import lk.codegen.risime.data.backup.BackupReason
import lk.codegen.risime.data.media.NetKind
import java.util.concurrent.TimeUnit

/**
 * §22.7 the daily backup (WorkManager, while charging and the battery isn't low): a local backup,
 * then the upload when server backup is on and the network allows it (unmetered, or mobile data if
 * the user allowed it); otherwise the upload waits in [BackupUploadWorker] for an unmetered network.
 * It needs the encryption core: since decision 064 a WorkManager-started process restores the session
 * and opens it without UI (waited for up to 15 s); otherwise the catch-up on the next app open makes
 * it ([lk.codegen.risime.AppContainer]).
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RisiMeApp).container
        if (c.sessionStore.current() == null || c.awaitMlsCore()?.backupKeys == null) return Result.success()
        runCatching { setForeground(getForegroundInfo()) }
        val b = c.backups
        val net = currentNetKind(applicationContext)
        val now = b.serverOn && (net == NetKind.UNMETERED || (b.mobileData && net == NetKind.METERED))
        b.backupNow(BackupReason.DAILY, upload = now)
        if (b.serverOn && !now) BackupUploadWorker.enqueue(applicationContext, b.mobileData)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = (applicationContext as RisiMeApp).container.notifier.backupNotification()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifier.BACKUP_WORK_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.BACKUP_WORK_ID, n)
        }
    }

    companion object {
        const val NAME = "backup-daily"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** §22.7 the server upload of the newest local backup, on an unmetered network (or any, with "Use mobile data"). */
class BackupUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RisiMeApp).container
        if (c.sessionStore.current() == null || c.awaitMlsCore()?.backupKeys == null) return Result.success()
        val f = c.backups.localFiles().firstOrNull() ?: return Result.success()
        return when (c.backups.upload(f)) {
            is lk.codegen.risime.data.backup.UploadOutcome.Failed -> if (runAttemptCount < 5) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    companion object {
        const val NAME = "backup-upload"

        fun enqueue(context: Context, mobileData: Boolean) {
            val req = OneTimeWorkRequestBuilder<BackupUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
