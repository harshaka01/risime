package lk.codegen.risime.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Locked chats: "RisiMe" / "New message", no sender, no text, no style, the tap opens the app only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LockedChatNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(NotificationManager::class.java)

    private val dm = ChatNotification("dm:a_b", "b", "Shenika", listOf("my secret plan"), 1, 5L)
    private val group = ChatNotification(
        "grp:g1", "", "Pilot team", listOf("Kamal: hi", "Nimal: there"), 2, 6L, group = true,
        messages = listOf(NotifLine("Kamal", "hi", 1), NotifLine("Nimal", "there", 2)),
    )
    private val other = ChatNotification("dm:c_d", "d", "Dilani", listOf("hello"), 1, 7L)

    @Test fun redactionKeepsOnlyTheIdAndMarksItLocked() {
        val r = redactLockedChat(group)
        assertEquals("RisiMe", r.title)
        assertEquals(listOf("New message"), r.lines)
        assertEquals(1, r.count)
        assertFalse(r.group)
        assertTrue(r.messages.isEmpty())
        assertTrue(r.locked)
        assertEquals(group.conversationId, r.conversationId) // same notification id: clearing/opening the chat removes it
        assertFalse(r.toString().contains("Pilot") || r.toString().contains("Kamal") || r.toString().contains("hi"))
    }

    @Test fun aLockedChatPostsAsABareNewMessageThatOpensTheAppOnly() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val n = Notifier(app, isLockedChat = { it == "dm:a_b" || it == "grp:g1" })
        n.postChats(listOf(dm, group, other))
        val posted = nm.activeNotifications.associateBy { it.id }

        val d = posted[Notifier.chatId("dm:a_b")]!!.notification
        assertEquals("RisiMe", d.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("New message", d.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(d.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)) // no InboxStyle lines
        assertNull(d.extras.getString(Notification.EXTRA_TEMPLATE)) // no style at all
        assertNull(d.extras.getParcelableArray(Notification.EXTRA_MESSAGES))
        assertNull(d.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
        assertNotNull(d.publicVersion)
        assertEquals("New message", d.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(dump(d).contains("Shenika") || dump(d).contains("secret"))
        // The tap opens the app, not the chat.
        val i = shadowOf(d.contentIntent).savedIntent
        assertFalse(i.hasExtra(Notifier.EXTRA_OPEN_CHAT))

        val g = posted[Notifier.chatId("grp:g1")]!!.notification
        assertEquals("RisiMe", g.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("New message", g.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(g.extras.getString(Notification.EXTRA_TEMPLATE))
        assertNull(g.extras.getParcelableArray(Notification.EXTRA_MESSAGES))
        assertFalse(dump(g).contains("Pilot") || dump(g).contains("Kamal"))
        assertFalse(shadowOf(g.contentIntent).savedIntent.hasExtra(Notifier.EXTRA_OPEN_CHAT))

        // An unlocked chat is unchanged.
        val o = posted[Notifier.chatId("dm:c_d")]!!.notification
        assertEquals("Dilani", o.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("dm:c_d", shadowOf(o.contentIntent).savedIntent.getStringExtra(Notifier.EXTRA_OPEN_CHAT))
    }

    @Test fun refreshKeepsALockedChatRedacted() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val n = Notifier(app, isLockedChat = { it == "dm:a_b" })
        n.postChats(listOf(dm))
        n.refreshChats(listOf(dm.copy(lines = listOf("another secret"), count = 2)))
        val d = nm.activeNotifications.first { it.id == Notifier.chatId("dm:a_b") }.notification
        assertEquals("RisiMe", d.extras.getString(Notification.EXTRA_TITLE))
        assertFalse(dump(d).contains("secret"))
    }

    @Test fun lockedBeatsTheGlobalHideAndBothHideContent() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val n = Notifier(app, hideContent = { true }, isLockedChat = { it == "dm:a_b" })
        n.postChats(listOf(dm, other))
        val posted = nm.activeNotifications.associateBy { it.id }
        assertEquals("RisiMe", posted[Notifier.chatId("dm:a_b")]!!.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("RisiMe", posted[Notifier.chatId("dm:c_d")]!!.notification.extras.getString(Notification.EXTRA_TITLE))
    }

    private fun dump(n: Notification): String =
        n.extras.keySet().joinToString("|") { k -> "$k=${n.extras.get(k)?.let { v -> if (v is Array<*>) v.joinToString() else v.toString() }}" }
}
