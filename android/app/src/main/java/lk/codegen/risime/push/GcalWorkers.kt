package lk.codegen.risime.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import lk.codegen.risime.RisiMeApp
import lk.codegen.risime.data.gcal.CopyOutcome
import java.util.concurrent.TimeUnit

/**
 * v1.31 §31.6 the one WorkManager unique job `gcal-copy` (network required): copies the user's accepted Risi
 * Calendar events to the picked Google calendar. It runs after the Risi Calendar applies a change, after connect
 * and reconnect, and at most once a day on app start (the reconcile). A failed run is retried by this worker
 * itself with a back-off from 30 s doubling up to 1 hour (WorkManager's own back-off cannot be capped at 1 h).
 */
class GcalCopyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RisiMeApp).container
        if (c.sessionStore.current() == null) return Result.success()
        val full = inputData.getBoolean(KEY_FULL, false)
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        val out = runCatching { c.gcal.runCopies(full) }.getOrElse { CopyOutcome.RETRY }
        if (out == CopyOutcome.RETRY) {
            enqueue(applicationContext, full, attempt + 1, delaySeconds = backoffSeconds(attempt))
        } else if (full && out == CopyOutcome.DONE) {
            applicationContext.getSharedPreferences("risime_gcal", Context.MODE_PRIVATE).edit().putLong("reconcile_at", System.currentTimeMillis()).apply()
        }
        return Result.success()
    }

    companion object {
        const val NAME = "gcal-copy"
        const val KEY_FULL = "full"
        const val KEY_ATTEMPT = "attempt"

        /** 30 s, 60 s, 120 s ... capped at 1 hour. */
        fun backoffSeconds(attempt: Int): Long = minOf(3600L, 30L shl minOf(attempt, 7))

        fun enqueue(context: Context, full: Boolean, attempt: Int = 0, delaySeconds: Long = 0) {
            val req = OneTimeWorkRequestBuilder<GcalCopyWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putBoolean(KEY_FULL, full).putInt(KEY_ATTEMPT, attempt).build())
                .apply { if (delaySeconds > 0) setInitialDelay(delaySeconds, TimeUnit.SECONDS) }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
