package lk.codegen.risime.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import lk.codegen.risime.MainActivity
import lk.codegen.risime.R

/**
 * P0-1 update notifications ("App updates" channel): the download's progress (the worker's
 * foreground notification), "Installing … the app will restart" before the commit, "RisiMe updated
 * to X" after MY_PACKAGE_REPLACED, a failure with its real error, and "tap to finish" when Android
 * needs the user's confirmation while the app is in the background.
 */
class UpdateNotifications(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun allowed(): Boolean =
        nm.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun openApp(code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openWebsite(): PendingIntent = PendingIntent.getActivity(
        context, STATUS_ID + 100,
        Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_PAGE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun builder() = NotificationCompat.Builder(context, CH_UPDATES).setSmallIcon(R.drawable.ic_stat_risime).also { ensureChannel() }

    /** The worker's foreground notification: "Downloading RisiMe X… 42%" (+ Cancel). */
    fun progress(info: UpdateInfo?, step: String, percent: Int?, cancel: PendingIntent?): Notification = builder()
        .setContentTitle(info?.let { "Updating RisiMe to ${it.versionName}" } ?: "Updating RisiMe")
        .setContentText(step)
        .setProgress(100, percent ?: 0, percent == null)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(openApp(PROGRESS_ID))
        .apply { cancel?.let { addAction(0, "Cancel", it) } }
        .build()

    @Suppress("MissingPermission")
    fun showProgress(n: Notification) {
        if (allowed()) nm.notify(PROGRESS_ID, n)
    }

    @Suppress("MissingPermission")
    private fun status(n: Notification) {
        if (allowed()) nm.notify(STATUS_ID, n)
    }

    /** Right before the commit: a silent update closes the app, so say why first. */
    fun installing(info: UpdateInfo) = status(
        builder().setContentTitle("Installing RisiMe ${info.versionName} — the app will restart")
            .setContentText(NEVER_UNINSTALL_TEXT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(NEVER_UNINSTALL_TEXT))
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(STATUS_ID))
            .build(),
    )

    /** After MY_PACKAGE_REPLACED. */
    fun updated(versionName: String) = status(
        builder().setContentTitle("RisiMe updated to $versionName — tap to open")
            .setContentText("Your chats are kept.")
            .setAutoCancel(true)
            .setContentIntent(openApp(STATUS_ID))
            .build(),
    )

    /** A failure while the app isn't on screen (on screen, the banner shows it). */
    fun failed(info: UpdateInfo, error: String) = status(
        builder().setContentTitle("RisiMe ${info.versionName} wasn't installed")
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$error\n$NEVER_UNINSTALL_TEXT"))
            .setAutoCancel(true)
            .setContentIntent(openApp(STATUS_ID))
            .addAction(0, DOWNLOAD_FROM_WEBSITE, openWebsite())
            .build(),
    )

    /** Android asks for a confirmation (STATUS_PENDING_USER_ACTION) while the app is in the background. */
    fun confirm(info: UpdateInfo) = status(
        builder().setContentTitle("Tap to finish updating RisiMe to ${info.versionName}")
            .setContentText(NEVER_UNINSTALL_TEXT)
            .setAutoCancel(true)
            .setContentIntent(openApp(STATUS_ID))
            .build(),
    )

    fun cancelStatus() = nm.cancel(STATUS_ID)

    fun cancelProgress() = nm.cancel(PROGRESS_ID)

    companion object {
        const val CH_UPDATES = "updates"
        const val PROGRESS_ID = 20
        const val STATUS_ID = 21
    }
}
