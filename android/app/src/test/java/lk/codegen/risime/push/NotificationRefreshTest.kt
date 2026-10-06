package lk.codegen.risime.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** §15.6 (android R7): after a delete, posted chat notifications are rebuilt silently or cancelled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationRefreshTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(NotificationManager::class.java)

    private fun chat(conv: String, lines: List<String>, ts: Long) = ChatNotification(conv, "u-$conv", "Name $conv", lines, lines.size, ts)

    @Test fun planTouchesOnlyPostedChats() {
        val a = chat("dm:a", listOf("x"), 2)
        val b = chat("dm:b", listOf("y"), 1)
        val r = planNotificationRefresh(setOf(10, 11, 12), mapOf(10 to a, 11 to b, 99 to chat("dm:new", listOf("z"), 3)))
        assertEquals(listOf(a, b), r.repost)
        assertEquals(setOf(12), r.cancel)
    }

    @Test fun refreshRepostsSilentlyAndCancelsEmptyChatsAndTheSummary() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val n = Notifier(app)
        n.postChats(listOf(chat("dm:a", listOf("one", "two"), 2), chat("dm:b", listOf("secret"), 1)))
        assertEquals(setOf(Notifier.chatId("dm:a"), Notifier.chatId("dm:b")), n.activeChatIds())
        // "secret" was deleted (dm:b now has no unread rows), "two" too.
        n.refreshChats(listOf(chat("dm:a", listOf("one"), 2)))
        val posted = nm.activeNotifications.associateBy { it.id }
        assertTrue(Notifier.chatId("dm:b") !in posted)
        val a = posted[Notifier.chatId("dm:a")]!!.notification
        assertTrue("only alert once", a.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(listOf("one"), a.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() })
        assertTrue(Notifier.SUMMARY_ID in posted)
        // The last chat empties: the summary goes too.
        n.refreshChats(emptyList())
        assertTrue(nm.activeNotifications.isEmpty())
    }

    @Test fun addedToGroupNotificationsAreNotTouched() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val n = Notifier(app)
        n.postAddedToGroup("grp:g", "Kamal added you to Pilot")
        n.refreshChats(emptyList())
        assertEquals(1, nm.activeNotifications.size)
    }
}
