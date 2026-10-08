package lk.codegen.risime.calls

import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import lk.codegen.risime.MainActivity
import lk.codegen.risime.push.Notifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Missed-call notification: type + time + name, "Call back" (same type) and "Message"; hidden content under the app lock (decision 064). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MissedCallNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val at = java.time.ZonedDateTime.of(2026, 10, 8, 14, 5, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test fun voiceAndVideoShowTypeNameAndTime() {
        val n = CallNotifications(app)
        val v = n.missed("dm:a:b", "Kamal", video = false, atMs = at)
        assertEquals("Missed voice call", title(v))
        assertEquals("Kamal · 14:05", text(v))
        assertEquals(CallNotifications.CH_MISSED, v.channelId)
        val w = n.missed("dm:a:b", "Kamal", video = true, atMs = at)
        assertEquals("Missed video call", title(w))
        assertEquals(at, w.`when`)
    }

    @Test fun actionsCallBackWithTheSameTypeAndMessage() {
        val n = CallNotifications(app)
        for (video in listOf(false, true)) {
            val m = n.missed("dm:a:b", "Kamal", video, at)
            assertEquals(listOf("Call back", "Message"), m.actions.map { it.title.toString() })
            val callBack = Shadows.shadowOf(m.actions[0].actionIntent).savedIntent
            assertEquals(MainActivity::class.java.name, callBack.component!!.className)
            assertEquals("dm:a:b", callBack.getStringExtra(Notifier.EXTRA_OPEN_CHAT))
            assertEquals(if (video) "video" else "voice", callBack.getStringExtra(CallNotifications.EXTRA_CALL_BACK))
            val message = Shadows.shadowOf(m.actions[1].actionIntent).savedIntent
            assertEquals("dm:a:b", message.getStringExtra(Notifier.EXTRA_OPEN_CHAT))
            assertEquals(null, message.getStringExtra(CallNotifications.EXTRA_CALL_BACK))
        }
    }

    @Test fun hiddenContentHasNoNameButKeepsTypeAndTime() {
        val m = CallNotifications(app) { true }.missed("dm:a:b", "Kamal", video = true, atMs = at)
        assertEquals("Missed video call", title(m))
        assertFalse(text(m).contains("Kamal"))
        assertTrue(text(m).contains("14:05"))
        assertNotNull(m.actions)
    }
}
