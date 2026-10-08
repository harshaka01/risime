package lk.codegen.risime.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import lk.codegen.risime.MainActivity
import lk.codegen.risime.R
import lk.codegen.risime.net.FriendRequest

/**
 * Local notifications (decision 026): built from the local DB, grouped by chat, tap → that chat.
 * [hideContent]: the fingerprint lock is on with "Show content in notifications" off (decision 064):
 * "New message" with no name or text.
 */
class Notifier(private val context: Context, private val hideContent: () -> Boolean = { false }) {
    private val nm = NotificationManagerCompat.from(context)

    private fun shown(plan: List<ChatNotification>): List<ChatNotification> = if (hideContent()) plan.map(::redactForLock) else plan

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val m = context.getSystemService(NotificationManager::class.java)
        // P0 background delivery: "messages_v2" fixes the lock-screen visibility (private: the sender
        // only on a secure lock screen), which can't be changed on the old channel. Everything else the
        // user chose on the old channel (importance, sound, vibration, lights, Do Not Disturb) is
        // carried over; a fresh install gets high importance.
        if (m.getNotificationChannel(CH_MESSAGES) == null) {
            val old = m.getNotificationChannel(CH_MESSAGES_OLD)
            val spec = messagesChannelSpec(
                old?.let {
                    ChannelSpec(
                        it.importance, it.sound, it.audioAttributes, it.shouldVibrate(), it.vibrationPattern,
                        it.shouldShowLights(), it.lightColor, it.canBypassDnd(),
                    )
                },
            )
            m.createNotificationChannel(
                NotificationChannel(CH_MESSAGES, "Messages", spec.importance).apply {
                    description = "New messages and reactions"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                    if (spec.copied) setSound(spec.sound, spec.audioAttributes)
                    enableVibration(spec.vibration)
                    spec.vibrationPattern?.let { vibrationPattern = it }
                    enableLights(spec.lights)
                    if (spec.copied) lightColor = spec.lightColor
                    setBypassDnd(spec.bypassDnd)
                },
            )
            if (old != null) runCatching { m.deleteNotificationChannel(CH_MESSAGES_OLD) }
        }
        m.createNotificationChannel(NotificationChannel(CH_REQUESTS, "Friend requests", NotificationManager.IMPORTANCE_DEFAULT))
        m.createNotificationChannel(NotificationChannel(CH_SYNC, "Background sync", NotificationManager.IMPORTANCE_MIN))
        m.createNotificationChannel(NotificationChannel(CH_BACKUP, "Backups", NotificationManager.IMPORTANCE_DEFAULT))
        if (lk.codegen.risime.BuildConfig.HISTORY_SHARE_ENABLED) {
            m.createNotificationChannel(NotificationChannel(CH_HISTORY, "History requests", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun allowed(): Boolean =
        nm.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** [conversationId]: the chat to open (`dm:`/`grp:`; older intents carried a peer id, still accepted). */
    private fun openIntent(conversationId: String?, code: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { conversationId?.let { putExtra(EXTRA_OPEN_CHAT, it) } }
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Suppress("MissingPermission") // checked in allowed()
    fun postChats(plan: List<ChatNotification>): Boolean {
        if (plan.isEmpty() || !allowed()) return false
        ensureChannels()
        shown(plan).forEach { n -> nm.notify(chatId(n.conversationId), chatBuilder(n, silent = false).build()) }
        postSummary(silent = false)
        return true
    }

    /** Ids of the chat notifications currently in the shade (not "added you", requests or the summary). */
    fun activeChatIds(): Set<Int> = runCatching {
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.notification.extras?.getString(EXTRA_KIND) == KIND_CHAT }.map { it.id }.toSet()
    }.getOrDefault(emptySet())

    /**
     * §15.6 (android R7) after a delete: re-plan every chat that has a posted notification from [plan]
     * (built from all its unread rows) and repost it **silently** (`setOnlyAlertOnce`, `setSilent`);
     * a chat left empty is cancelled; with no chat notification left, the summary goes too.
     */
    @Suppress("MissingPermission")
    fun refreshChats(plan: List<ChatNotification>) {
        val active = activeChatIds()
        if (active.isEmpty()) return
        val r = planNotificationRefresh(active, shown(plan).associateBy { chatId(it.conversationId) })
        r.cancel.forEach { nm.cancel(it) }
        if (r.repost.isNotEmpty() && allowed()) {
            r.repost.forEach { n -> nm.notify(chatId(n.conversationId), chatBuilder(n, silent = true).build()) }
            postSummary(silent = true)
        }
        if (r.repost.isEmpty()) nm.cancel(SUMMARY_ID)
    }

    private fun postSummary(silent: Boolean) {
        @Suppress("MissingPermission")
        nm.notify(
            SUMMARY_ID,
            NotificationCompat.Builder(context, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_risime)
                .setContentTitle("RisiMe")
                .setContentText("New messages")
                .setGroup(GROUP_MESSAGES)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(silent)
                .setSilent(silent)
                .setContentIntent(openIntent(null, SUMMARY_ID))
                .build(),
        )
    }

    private fun chatBuilder(n: ChatNotification, silent: Boolean): NotificationCompat.Builder {
        run {
            val style: NotificationCompat.Style = if (n.group) {
                // §12: one notification per group conversation, a sender Person per line.
                NotificationCompat.MessagingStyle(androidx.core.app.Person.Builder().setName("You").build())
                    .setConversationTitle(n.title)
                    .setGroupConversation(true)
                    .also { st -> n.messages.forEach { m -> st.addMessage(m.text, m.ts, androidx.core.app.Person.Builder().setName(m.sender).build()) } }
            } else {
                NotificationCompat.InboxStyle().also { st ->
                    n.lines.forEach { st.addLine(it) }
                    if (n.count > n.lines.size) st.setSummaryText("${n.count} new messages")
                }
            }
            val b = NotificationCompat.Builder(context, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_risime)
                .setContentTitle(n.title)
                .setContentText(n.lines.lastOrNull())
                .setStyle(style)
                .setNumber(n.count)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup(GROUP_MESSAGES)
                .setAutoCancel(true)
                .setWhen(n.newestTs)
                .setContentIntent(openIntent(n.conversationId, chatId(n.conversationId)))
                .setOnlyAlertOnce(silent)
                .setSilent(silent)
                .addExtras(android.os.Bundle().apply { putString(EXTRA_KIND, KIND_CHAT) })
                // Secure lock screen: the sender (or group) only, never the text.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CH_MESSAGES)
                        .setSmallIcon(R.drawable.ic_stat_risime)
                        .setContentTitle(n.title)
                        .setContentText(if (n.count > 1) "${n.count} new messages" else "New message")
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .build(),
                )
            return b
        }
    }

    /** §12.7 S4: "Kamal added you to Pilot team" (opens the group). */
    @Suppress("MissingPermission")
    fun postAddedToGroup(conversationId: String, text: String) {
        if (!allowed()) return
        ensureChannels()
        nm.notify(
            chatId(conversationId),
            NotificationCompat.Builder(context, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_risime)
                .setContentTitle(if (hideContent()) "RisiMe" else "Added to a group")
                .setContentText(if (hideContent()) LOCKED_CONTENT_TEXT else text)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setGroup(GROUP_MESSAGES)
                .setAutoCancel(true)
                .setContentIntent(openIntent(conversationId, chatId(conversationId)))
                .build(),
        )
    }

    /** Couldn't sync in the background (fingerprint-locked): say so, without any content. */
    @Suppress("MissingPermission")
    fun postLocked() {
        if (!allowed()) return
        ensureChannels()
        nm.notify(
            LOCKED_ID,
            NotificationCompat.Builder(context, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_risime)
                .setContentTitle("New messages")
                .setContentText("Open RisiMe to read them")
                .setAutoCancel(true)
                .setContentIntent(openIntent(null, LOCKED_ID))
                .build(),
        )
    }

    @Suppress("MissingPermission")
    fun postRequests(fresh: List<FriendRequest>) {
        val (title, text) = requestNotificationText(fresh)?.let { if (hideContent()) "RisiMe" to "New notification" else it } ?: return
        if (!allowed()) return
        ensureChannels()
        nm.notify(
            REQUESTS_ID,
            NotificationCompat.Builder(context, CH_REQUESTS)
                .setSmallIcon(R.drawable.ic_stat_risime)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setAutoCancel(true)
                .setContentIntent(openIntent(null, REQUESTS_ID))
                .build(),
        )
    }

    /** §17.8 a request waiting for the user's answer ("History requests"; the tap goes through the fingerprint gate). */
    @Suppress("MissingPermission")
    fun postHistoryPrompt(text: String, conversationId: String?) {
        if (!allowed()) return
        ensureChannels()
        nm.notify(
            HISTORY_ID,
            NotificationCompat.Builder(context, CH_HISTORY)
                .setSmallIcon(R.drawable.ic_stat_risime)
                .setContentTitle("Chat history request")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openIntent(conversationId, HISTORY_ID))
                .build(),
        )
    }

    fun cancelHistoryPrompt() = nm.cancel(HISTORY_ID)

    /** §17.8 option A afterwards: a quiet "Shared chat history with your new phone". */
    @Suppress("MissingPermission")
    fun postHistoryShared() {
        if (!allowed()) return
        ensureChannels()
        nm.notify(
            HISTORY_DONE_ID,
            NotificationCompat.Builder(context, CH_HISTORY)
                .setSmallIcon(R.drawable.ic_stat_risime)
                .setContentTitle("Shared chat history with your new phone")
                .setSilent(true)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** §22.7 the pre-update backup failed: the update still goes ahead; uninstalling would lose the chats. */
    @Suppress("MissingPermission")
    fun postBackupFailed() {
        if (!allowed()) return
        ensureChannels()
        nm.notify(
            BACKUP_ID,
            NotificationCompat.Builder(context, CH_BACKUP)
                .setSmallIcon(R.drawable.ic_stat_risime)
                .setContentTitle(BACKUP_FAILED_TITLE)
                .setContentText(BACKUP_FAILED_TEXT)
                .setStyle(NotificationCompat.BigTextStyle().bigText(BACKUP_FAILED_TEXT))
                .setContentIntent(openIntent(null, BACKUP_ID))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** §22.7 the backup worker's ongoing notification. */
    fun backupNotification() = NotificationCompat.Builder(context, CH_SYNC)
        .setSmallIcon(R.drawable.ic_stat_risime)
        .setContentTitle("Backing up your chats…")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build().also { ensureChannels() }

    /** §17.16 the export worker's ongoing notification. */
    fun historyExportNotification() = NotificationCompat.Builder(context, CH_SYNC)
        .setSmallIcon(R.drawable.ic_stat_risime)
        .setContentTitle("Sharing chat history…")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build().also { ensureChannels() }

    fun syncNotification() = NotificationCompat.Builder(context, CH_SYNC)
        .setSmallIcon(R.drawable.ic_stat_risime)
        .setContentTitle("Checking for messages")
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build().also { ensureChannels() }

    fun cancelChat(conversationId: String) {
        nm.cancel(chatId(conversationId))
        nm.cancel(LOCKED_ID)
    }

    fun cancelAll() = nm.cancelAll()

    companion object {
        const val EXTRA_OPEN_CHAT = "lk.codegen.risime.OPEN_CHAT"
        const val EXTRA_KIND = "lk.codegen.risime.KIND"
        const val KIND_CHAT = "chat"
        const val CH_MESSAGES = "messages_v2"
        /** Until nightly.31: no explicit lock-screen visibility (deleted once v2 exists). */
        const val CH_MESSAGES_OLD = "messages"

        /** The new channel keeps the old one's importance (the user's choice, off included); a fresh install gets high. */
        fun messagesImportance(oldImportance: Int?): Int =
            oldImportance?.takeIf { it != NotificationManager.IMPORTANCE_UNSPECIFIED } ?: NotificationManager.IMPORTANCE_HIGH

        /** `messages_v2` from the old `messages` channel's settings ([old] null: a fresh install). Only the lock-screen visibility changes. */
        fun <S, A> messagesChannelSpec(old: ChannelSpec<S, A>?): ChannelSpec<S, A> =
            old?.copy(importance = messagesImportance(old.importance))
                ?: ChannelSpec(NotificationManager.IMPORTANCE_HIGH, null, null, vibration = true, vibrationPattern = null, lights = false, lightColor = 0, bypassDnd = false, copied = false)
        const val CH_REQUESTS = "requests"
        const val CH_SYNC = "sync"
        const val GROUP_MESSAGES = "lk.codegen.risime.MESSAGES"
        const val SUMMARY_ID = 1
        const val REQUESTS_ID = 2
        const val LOCKED_ID = 3
        const val SYNC_ID = 4
        const val CH_HISTORY = "history"
        const val HISTORY_ID = 5
        const val HISTORY_EXPORT_ID = 6
        const val HISTORY_DONE_ID = 7
        const val CH_BACKUP = "backup"
        const val BACKUP_ID = 8
        const val BACKUP_WORK_ID = 9
        const val BACKUP_FAILED_TITLE = "Backup failed — don't uninstall"
        const val BACKUP_FAILED_TEXT = "Don't uninstall RisiMe: your chats are only safe if they're backed up. Open RisiMe → Settings → Backups → Back up now."

        fun chatId(conversationId: String) = 1000 + (conversationId.hashCode() and 0x7fffffff) % 1_000_000
    }
}

/**
 * A notification channel's user-visible settings ([S] the sound Uri, [A] its AudioAttributes: opaque
 * here, so the carry-over is testable on the JVM). [copied]: taken from an existing channel.
 */
data class ChannelSpec<S, A>(
    val importance: Int,
    val sound: S?,
    val audioAttributes: A?,
    val vibration: Boolean,
    val vibrationPattern: LongArray?,
    val lights: Boolean,
    val lightColor: Int,
    val bypassDnd: Boolean,
    val copied: Boolean = true,
)
