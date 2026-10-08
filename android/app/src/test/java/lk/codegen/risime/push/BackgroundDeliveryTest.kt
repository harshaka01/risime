package lk.codegen.risime.push

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundDeliveryTest {
    /** The socket closes 5 s after Home (before the 10-s freezer), so the server pushes. */
    @Test fun socketClosesAfterTheGraceInTheBackground() = runTest {
        val fg = MutableStateFlow(true)
        val seen = mutableListOf<Pair<Long, Boolean>>()
        val job = launch { fg.withBackgroundGrace(5_000).collect { seen += testScheduler.currentTime to it } }
        runCurrent()
        assertEquals(listOf(0L to true), seen)
        advanceTimeBy(1_000); fg.value = false; runCurrent()
        advanceTimeBy(4_999); runCurrent()
        assertEquals("still up inside the grace", listOf(0L to true), seen)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf(0L to true, 6_000L to false), seen)
        assertTrue("shorter than Android's 10-s cached-app freezer", BACKGROUND_SOCKET_GRACE_MS < 10_000)
        job.cancel()
    }

    /** Back in the foreground inside the grace: the socket never drops. */
    @Test fun aQuickAppSwitchKeepsTheSocket() = runTest {
        val fg = MutableStateFlow(true)
        val seen = mutableListOf<Boolean>()
        val job = launch { fg.withBackgroundGrace(5_000).collect { seen += it } }
        runCurrent()
        fg.value = false; runCurrent(); advanceTimeBy(3_000); runCurrent()
        fg.value = true; runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(true), seen)
        job.cancel()
    }

    /** A process started by a push is in the background from the start: no grace, false at once. */
    @Test fun aPushStartedProcessIsBackgroundAtOnce() = runTest {
        val fg = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        val job = launch { fg.withBackgroundGrace(5_000).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(false), seen)
        job.cancel()
    }

    @Test fun whatKeepsTheSocketUp() {
        assertTrue(socketWanted(foregroundOrGrace = true, pushSync = false, callActive = false, historyExport = false))
        assertTrue(socketWanted(false, pushSync = true, callActive = false, historyExport = false))
        assertTrue("a call keeps it regardless of foreground", socketWanted(false, false, callActive = true, historyExport = false))
        assertTrue(socketWanted(false, false, false, historyExport = true))
        assertFalse("background, nothing running: closed so the server pushes", socketWanted(false, false, false, false))
    }

    @Test fun aSocketMessageNotifiesOnlyWhenNotInTheForeground() {
        assertTrue(shouldNotifyFromSocket(foreground = false, replayingFresh = false))
        assertFalse("the app is open: the chat list/chat shows it", shouldNotifyFromSocket(foreground = true, replayingFresh = false))
        assertFalse("§13.3 a fresh install's replay never notifies", shouldNotifyFromSocket(foreground = false, replayingFresh = true))
    }

    @Test fun pushLogLineHasTheKindAndNoContent() {
        assertEquals("RisiMe push: received kind=inbox", pushReceivedLine(mapOf("type" to "inbox", "v" to "1"), null, 1_000))
        assertEquals("RisiMe push: received kind=call delay_ms=250", pushReceivedLine(mapOf("type" to "call"), 750, 1_000))
        assertEquals("RisiMe push: received kind=inbox delay_ms=40", pushReceivedLine(mapOf("type" to "inbox", "ts" to "960"), 750, 1_000))
        assertEquals("RisiMe push: received kind=unknown", pushReceivedLine(emptyMap(), 0, 1_000))
    }

    @Test fun theDirectSyncFitsTheHighPriorityWindow() {
        assertTrue(DIRECT_PUSH_SYNC_MS <= 10_000)
    }

    @Test fun messagesChannelV2KeepsAUsersOff() {
        assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, Notifier.messagesImportance(null))
        assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, Notifier.messagesImportance(android.app.NotificationManager.IMPORTANCE_DEFAULT))
        assertEquals(android.app.NotificationManager.IMPORTANCE_NONE, Notifier.messagesImportance(android.app.NotificationManager.IMPORTANCE_NONE))
        assertTrue(Notifier.CH_MESSAGES != Notifier.CH_MESSAGES_OLD)
    }
}
