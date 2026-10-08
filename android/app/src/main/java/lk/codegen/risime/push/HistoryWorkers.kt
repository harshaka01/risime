package lk.codegen.risime.push

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import lk.codegen.risime.RisiMeApp
import lk.codegen.risime.data.history.ExportOutcome
import java.util.concurrent.TimeUnit

/**
 * §17.16 (android R7) the provider's export: a long-running worker in the foreground (service type
 * `dataSync`, an ongoing "Sharing chat history…" notification) that holds the realtime connection
 * for `history:respond`/`history:deliver` and resumes per part after a kill. Only after an explicit
 * Share or an option-A approval, and only with a bearer (since decision 064 also in a background
 * process; only a pre-064 vault waiting for its migration has none: the start-up sweep resumes it).
 */
class HistoryExportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RisiMeApp).container
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        if (!c.canExportHistory()) return Result.success()
        runCatching { setForeground(getForegroundInfo()) }
        return c.withHistoryConnection {
            when (c.history.runExport(id)) {
                ExportOutcome.DONE, ExportOutcome.GONE -> Result.success()
                ExportOutcome.RETRY -> Result.retry()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = (applicationContext as RisiMeApp).container.notifier.historyExportNotification()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifier.HISTORY_EXPORT_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.HISTORY_EXPORT_ID, n)
        }
    }

    companion object {
        const val KEY_ID = "request_id"

        fun name(id: String) = "history-export-$id"

        fun enqueue(context: Context, requestId: String) {
            val req = OneTimeWorkRequestBuilder<HistoryExportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_ID to requestId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(requestId), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, requestId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(name(requestId))
        }
    }
}
