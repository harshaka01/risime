package lk.codegen.risime.push

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import lk.codegen.risime.RisiMeApp
import lk.codegen.risime.data.media.DownloadOutcome
import lk.codegen.risime.data.media.NetKind
import lk.codegen.risime.data.media.UploadOutcome
import java.util.concurrent.TimeUnit

/**
 * §14.7 Sending 6 (android review, open point 1): uploads one encrypted image in the background,
 * unique per client_msg_id (KEEP), expedited where quota allows (a short foreground service before
 * API 31), on any network. It never touches the picker URI: the ciphertext file is already stored.
 * After the upload the outbox sends the envelope, even if the app was killed meanwhile.
 */
class MediaUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RisiMeApp).container
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        return when (c.imageUploader.run(id)) {
            is UploadOutcome.Done -> {
                c.flushOutboxAfterUpload()
                Result.success()
            }
            is UploadOutcome.Retry -> Result.retry()
            is UploadOutcome.Failed -> Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifier.SYNC_ID, (applicationContext as RisiMeApp).container.notifier.syncNotification())

    companion object {
        const val KEY_ID = "client_msg_id"

        fun name(id: String) = "media-up-$id"

        fun enqueue(context: Context, clientMsgId: String) {
            val req = OneTimeWorkRequestBuilder<MediaUploadWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_ID to clientMsgId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(clientMsgId), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, clientMsgId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(name(clientMsgId))
        }
    }
}

/**
 * §14.7 Receiving 7: background auto-download on unmetered networks only (newest first, at most
 * 3 at a time). Metered networks download only what's visible in an open chat; that is queued
 * here too, so the photo arrives on the next Wi-Fi before its 30-day TTL.
 */
class MediaDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RisiMeApp).container
        val outcomes = c.downloadAllImages()
        return if (outcomes.any { it is DownloadOutcome.Retry }) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("media-download", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

/** The current network for the auto-download policy: Data Saver and roaming are tap-only. */
fun currentNetKind(context: Context): NetKind {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetKind.OFFLINE
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetKind.OFFLINE
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetKind.OFFLINE
    val roaming = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
    val dataSaver = cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    return when {
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetKind.UNMETERED
        roaming || dataSaver -> NetKind.RESTRICTED
        else -> NetKind.METERED
    }
}
