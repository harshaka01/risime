package lk.codegen.risime.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import lk.codegen.risime.RisiMeApp

/** FCM entry point: a data-only `{"type":"inbox"}` wake-up → an expedited sync. Nothing is shown from it. */
class RisiMeMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (isInboxWakeUp(message.data)) InboxSyncWorker.enqueue(applicationContext)
    }

    override fun onNewToken(token: String) {
        val c = (application as RisiMeApp).container
        c.scope.launch { c.push.register(token, c.sessionStore.current()?.user?.phoneVerified ?: false) }
    }
}

/** Runs the normal channel sync in the background, then posts local notifications. */
class InboxSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RisiMeApp).container
        c.syncAndNotify()
        return Result.success()
    }

    /**
     * Expedited work runs as a short foreground service only before Android 12 (API 31+ uses
     * expedited jobs), where no service type is required.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifier.SYNC_ID, (applicationContext as RisiMeApp).container.notifier.syncNotification())

    companion object {
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<InboxSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // Coalesce bursts: a sync already queued/running covers the new wake-up.
            WorkManager.getInstance(context).enqueueUniqueWork("inbox-sync", ExistingWorkPolicy.KEEP, req)
        }
    }
}
