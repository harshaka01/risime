package lk.codegen.risime.push

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import lk.codegen.risime.RisiMeApp

/**
 * FCM entry point: data-only wake-ups (`{"type":"inbox"}` / `{"type":"call"}`). Nothing from the
 * payload is shown.
 *
 * P0 background delivery: a high-priority push gives the app a short window (~10 s, Doze temp
 * allowlist: network and starts allowed). The inbox sync runs **directly** here, inside that window,
 * and notifies as soon as the join's catch-up is applied; the expedited worker only finishes the
 * rest (friends, owed group ops). Before, the sync ran only in the worker, which a restricted
 * standby bucket or an exhausted expedited quota defers (and `KEEP` then dropped every later
 * wake-up behind the deferred one), so nothing showed until the app was opened.
 */
class RisiMeMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        Log.i("RisiMe", pushReceivedLine(message.data, message.sentTime, System.currentTimeMillis()) + " priority=${message.priority}/${message.originalPriority}")
        val c = (application as RisiMeApp).container
        // §16.8: the call push has its own handler: the phoneCall service starts at once (never the inbox worker).
        if (lk.codegen.risime.net.PushPayload.fromData(message.data)?.isCall == true) {
            c.calls.onCallPush()
            return
        }
        if (!isInboxWakeUp(message.data)) return
        // onMessageReceived runs on FCM's own background thread: blocking here keeps the service
        // (and the process) alive for the sync.
        val done = runBlocking {
            withTimeoutOrNull(DIRECT_PUSH_SYNC_MS) { runCatching { c.syncAndNotify(quick = true) }; true } ?: false
        }
        if (!done) Log.i("RisiMe", "RisiMe push: direct sync didn't finish in ${DIRECT_PUSH_SYNC_MS} ms; the worker continues")
        InboxSyncWorker.enqueue(applicationContext)
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
        private const val NAME = "inbox-sync"

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<InboxSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            val wm = WorkManager.getInstance(context)
            // Coalesce bursts: a sync already RUNNING covers the new wake-up. One that is only queued
            // (deferred: out of quota, Doze) is replaced, so a stale request never swallows new pushes.
            val running = runCatching { wm.getWorkInfosForUniqueWork(NAME).get() }.getOrNull().orEmpty()
                .any { it.state == WorkInfo.State.RUNNING }
            wm.enqueueUniqueWork(NAME, if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, req)
        }
    }
}
