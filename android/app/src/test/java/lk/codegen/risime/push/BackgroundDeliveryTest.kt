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

    /** Screen off: the socket closes at once (no grace), while the phone still has network. */
    @Test fun screenOffClosesWithoutTheGrace() {
        assertTrue(foregroundHold(foregroundOrGrace = true, foreground = true, screenOn = true))
        assertTrue("app switch, screen on: the grace", foregroundHold(foregroundOrGrace = true, foreground = false, screenOn = true))
        assertFalse("screen off inside the grace: closed now", foregroundHold(foregroundOrGrace = true, foreground = false, screenOn = false))
        assertFalse(foregroundHold(foregroundOrGrace = false, foreground = false, screenOn = true))
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

    @Test fun messagesChannelV2KeepsTheUsersImportance() {
        assertEquals("fresh install", android.app.NotificationManager.IMPORTANCE_HIGH, Notifier.messagesImportance(null))
        assertEquals("a Silent/default choice is kept", android.app.NotificationManager.IMPORTANCE_DEFAULT, Notifier.messagesImportance(android.app.NotificationManager.IMPORTANCE_DEFAULT))
        assertEquals(android.app.NotificationManager.IMPORTANCE_LOW, Notifier.messagesImportance(android.app.NotificationManager.IMPORTANCE_LOW))
        assertEquals(android.app.NotificationManager.IMPORTANCE_NONE, Notifier.messagesImportance(android.app.NotificationManager.IMPORTANCE_NONE))
        assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, Notifier.messagesImportance(android.app.NotificationManager.IMPORTANCE_UNSPECIFIED))
        assertTrue(Notifier.CH_MESSAGES != Notifier.CH_MESSAGES_OLD)
    }

    /** Review finding: sound, vibration, lights and DND carry over from `messages`; only the lock-screen visibility changes. */
    @Test fun messagesChannelV2CopiesEverySettingOfTheOldChannel() {
        val pattern = longArrayOf(0, 100, 50, 100)
        val old = ChannelSpec(sound = "content://sound/custom", audioAttributes = "attrs", vibration = false, vibrationPattern = pattern, lights = true, lightColor = 0xFF00FF, bypassDnd = true, importance = android.app.NotificationManager.IMPORTANCE_DEFAULT)
        val v2 = Notifier.messagesChannelSpec(old)
        assertEquals("content://sound/custom", v2.sound)
        assertEquals("attrs", v2.audioAttributes)
        assertFalse(v2.vibration)
        assertTrue(v2.vibrationPattern.contentEquals(pattern))
        assertTrue(v2.lights)
        assertEquals(0xFF00FF, v2.lightColor)
        assertTrue(v2.bypassDnd)
        assertEquals(android.app.NotificationManager.IMPORTANCE_DEFAULT, v2.importance)
        assertTrue(v2.copied)
        val fresh = Notifier.messagesChannelSpec<String, String>(null)
        assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, fresh.importance)
        assertFalse("fresh: the system default sound", fresh.copied)
        assertTrue(fresh.vibration)
    }

    /** Review finding: Home → screen off → screen on inside the grace doesn't reopen the socket. */
    @Test fun screenOnAfterScreenOffInsideTheGraceDoesNotReopen() = runTest {
        val fg = MutableStateFlow(true)
        val screen = MutableStateFlow(true)
        val seen = mutableListOf<Pair<Long, Boolean>>()
        val job = launch { foregroundHoldFlow(fg, screen, 5_000) { testScheduler.currentTime }.collect { seen += testScheduler.currentTime to it } }
        runCurrent()
        fg.value = false; runCurrent() // Home: grace
        advanceTimeBy(1_000); screen.value = false; runCurrent() // screen off: closed at once
        advanceTimeBy(1_000); screen.value = true; runCurrent() // on again, still inside the 5 s
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(0L to true, 1_000L to false), seen)
        fg.value = true; runCurrent()
        assertEquals(12_000L to true, seen.last())
        job.cancel()
    }

    @Test fun holdFlowGraceAndQuickSwitch() = runTest {
        val fg = MutableStateFlow(true)
        val screen = MutableStateFlow(true)
        val seen = mutableListOf<Pair<Long, Boolean>>()
        val job = launch { foregroundHoldFlow(fg, screen, 5_000) { testScheduler.currentTime }.collect { seen += testScheduler.currentTime to it } }
        runCurrent()
        fg.value = false; runCurrent(); advanceTimeBy(3_000)
        fg.value = true; runCurrent() // quick switch: never dropped
        fg.value = false; runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals(listOf(0L to true, 8_000L to false), seen)
        job.cancel()
    }

    @Test fun holdFlowPushStartedProcessHoldsNothing() = runTest {
        val seen = mutableListOf<Boolean>()
        val screen = MutableStateFlow(false)
        val job = launch { foregroundHoldFlow(MutableStateFlow(false), screen, 5_000) { testScheduler.currentTime }.collect { seen += it } }
        runCurrent(); screen.value = true; runCurrent(); advanceTimeBy(6_000); runCurrent()
        assertEquals(listOf(false), seen)
        job.cancel()
    }

    /** Review finding: one socket session per wake-up; the worker skips its own when the direct sync went live. */
    @Test fun workerSkipsTheSocketWhenTheDirectSyncCoveredItsWakeUp() {
        var now = 1_000L
        val t = PushWakeTracker { now }
        val stamp = t.stamp()
        assertFalse("direct sync not live yet", t.workerCanSkipSocket(stamp))
        now = 1_500; t.directLive()
        assertTrue(t.workerCanSkipSocket(stamp))
        now = 2_000
        val later = t.stamp()
        assertFalse("a newer wake-up not covered yet", t.workerCanSkipSocket(later))
        assertFalse("no stamp (old request): always syncs", t.workerCanSkipSocket(0))
        val fresh = PushWakeTracker { now }
        assertFalse("a new process never skips on an old stamp", fresh.workerCanSkipSocket(stamp))
    }
}
