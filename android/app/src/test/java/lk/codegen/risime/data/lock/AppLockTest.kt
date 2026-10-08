package lk.codegen.risime.data.lock

import kotlinx.coroutines.test.runTest
import lk.codegen.risime.push.ChatNotification
import lk.codegen.risime.push.LOCKED_CONTENT_TEXT
import lk.codegen.risime.push.NotifLine
import lk.codegen.risime.push.redactForLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decision 064: the optional fingerprint lock is a UI gate only, off by default. */
class AppLockTest {
    private class Store(var s: AppLockSettings = AppLockSettings()) : AppLockStore {
        var saves = 0
        override suspend fun load() = s
        override suspend fun save(s: AppLockSettings) {
            this.s = s
            saves++
        }
    }

    private class Stamp(var v: Long? = null) : LockStamp {
        override fun get() = v
        override fun set(v: Long?) { this.v = v }
    }

    private var now = 1_000_000L
    private var bio = true
    private val logs = mutableListOf<String>()

    private fun lock(store: Store, stamp: Stamp = Stamp()) = AppLock(store, stamp, { bio }, { now }, { logs += it })

    @Test fun defaultIsOffAndNothingLocks() = runTest {
        assertEquals(AppLockSettings(enabled = false, autoLock = AutoLock.IMMEDIATELY, showContent = true), AppLockSettings())
        val l = lock(Store())
        assertNull(l.locked.value) // the UI waits for the settings (no flash of content)
        l.load()
        assertEquals(false, l.locked.value)
        l.onBackground()
        now += 3_600_000
        l.onForeground()
        assertEquals(false, l.locked.value)
        assertFalse(l.hideNotificationContent())
    }

    @Test fun immediately() {
        val s = AppLockSettings(enabled = true, autoLock = AutoLock.IMMEDIATELY)
        assertTrue(AppLockPolicy.lockedOnReturn(s, backgroundAt = now, now = now))
        assertTrue(AppLockPolicy.lockedAtStart(s, backgroundAt = now - 1, now = now))
        assertFalse(AppLockPolicy.lockedOnReturn(s, backgroundAt = null, now = now)) // never left the screen
    }

    @Test fun afterOneMinuteAndThirtyMinutes() {
        val one = AppLockSettings(enabled = true, autoLock = AutoLock.ONE_MINUTE)
        assertFalse(AppLockPolicy.lockedOnReturn(one, now - 59_999, now))
        assertTrue(AppLockPolicy.lockedOnReturn(one, now - 60_000, now))
        val thirty = AppLockSettings(enabled = true, autoLock = AutoLock.THIRTY_MINUTES)
        assertFalse(AppLockPolicy.lockedOnReturn(thirty, now - 29 * 60_000, now))
        assertTrue(AppLockPolicy.lockedOnReturn(thirty, now - 30 * 60_000, now))
        // A new process (e.g. started by a push) within the time: not locked; no stamp: locked.
        assertFalse(AppLockPolicy.lockedAtStart(thirty, now - 60_000, now))
        assertTrue(AppLockPolicy.lockedAtStart(thirty, null, now))
        // elapsedRealtime restarts at boot: a stamp "in the future" means a reboot → locked.
        assertTrue(AppLockPolicy.lockedAtStart(thirty, now + 5_000, now))
        assertFalse(AppLockPolicy.lockedAtStart(thirty.copy(enabled = false), null, now))
    }

    @Test fun runtimeLocksAfterTheDelayAndUnlocksWithTheFingerprint() = runTest {
        val stamp = Stamp()
        val l = lock(Store(AppLockSettings(enabled = true, autoLock = AutoLock.ONE_MINUTE)), stamp)
        l.load()
        assertEquals(true, l.locked.value) // a new process with the lock on
        l.unlocked()
        assertEquals(false, l.locked.value)
        l.onBackground()
        now += 30_000
        l.checkOnReturn()
        l.onForeground()
        assertEquals(false, l.locked.value) // back within a minute
        l.onBackground()
        now += 61_000
        l.checkOnReturn() // synchronous, before the first frame
        assertEquals(true, l.locked.value)
    }

    @Test fun immediatelyLocksAsSoonAsTheAppLeavesTheScreen() = runTest {
        val l = lock(Store(AppLockSettings(enabled = true)))
        l.load()
        l.unlocked()
        l.onBackground()
        assertEquals(true, l.locked.value)
    }

    @Test fun biometricRemovedTurnsTheLockOffSilently() = runTest {
        val store = Store(AppLockSettings(enabled = true))
        bio = false
        val l = lock(store)
        l.load()
        assertEquals(false, l.locked.value)
        assertFalse(store.s.enabled)
        assertTrue(logs.single().contains("turned off"))
        // On resume too.
        bio = true
        val store2 = Store(AppLockSettings(enabled = true, autoLock = AutoLock.ONE_MINUTE))
        val l2 = lock(store2)
        l2.load()
        l2.unlocked()
        bio = false
        l2.onBackground()
        now += 120_000
        l2.onForeground()
        assertEquals(false, l2.locked.value)
        assertFalse(store2.s.enabled)
    }

    @Test fun enablingNeedsBiometricsAndDisablingUnlocks() = runTest {
        val store = Store()
        val l = lock(store)
        l.load()
        l.setEnabled(true)
        assertTrue(store.s.enabled)
        l.setAutoLock(AutoLock.THIRTY_MINUTES)
        l.setShowContent(false)
        assertEquals(AppLockSettings(true, AutoLock.THIRTY_MINUTES, false), store.s)
        assertTrue(l.hideNotificationContent())
        l.setEnabled(false)
        assertEquals(false, l.locked.value)
        assertFalse(l.hideNotificationContent()) // the content rule applies only with the lock on
        bio = false
        l.setEnabled(true)
        assertFalse(store.s.enabled)
    }

    @Test fun unreadableSettingsNeverKeepTheUserOut() = runTest {
        val broken = object : AppLockStore {
            override suspend fun load(): AppLockSettings = throw java.io.IOException("corrupt")
            override suspend fun save(s: AppLockSettings) = Unit
        }
        val l = AppLock(broken, Stamp(), { true }, { now })
        l.load()
        assertEquals(false, l.locked.value)
    }

    @Test fun theLockNeverTouchesTheSession() {
        // The lock's whole surface: no tokens, no sign-out, no wipe, no network (decision 064).
        val methods = AppLock::class.java.declaredMethods.map { it.name.lowercase() }
        listOf("signout", "logout", "wipe", "token", "delete").forEach { bad ->
            assertTrue("AppLock must not have a '$bad' operation", methods.none { it.contains(bad) })
        }
    }

    @Test fun notificationContentRuleWhileLocked() {
        val n = ChatNotification(
            "grp:1", "", "Pilot team", listOf("Kamal: hi", "Nimal: there"), 2, 5L, group = true,
            messages = listOf(NotifLine("Kamal", "hi", 1), NotifLine("Nimal", "there", 2)),
        )
        val r = redactForLock(n)
        assertEquals("RisiMe", r.title)
        assertEquals(listOf("2 new messages"), r.lines)
        assertTrue(r.messages.isEmpty())
        assertFalse(r.group)
        assertEquals(n.conversationId, r.conversationId) // same notification id and tap target
        val one = redactForLock(ChatNotification("dm:a:b", "b", "Shenika", listOf("secret"), 1, 1L))
        assertEquals(listOf(LOCKED_CONTENT_TEXT), one.lines)
        assertEquals("New message", LOCKED_CONTENT_TEXT)
        assertFalse(one.toString().contains("Shenika") || one.toString().contains("secret"))
        // Off by default: content shown.
        assertFalse(AppLockPolicy.hideNotificationContent(AppLockSettings()))
        assertFalse(AppLockPolicy.hideNotificationContent(AppLockSettings(enabled = false, showContent = false)))
        assertTrue(AppLockPolicy.hideNotificationContent(AppLockSettings(enabled = true, showContent = false)))
        assertFalse(AppLockPolicy.hideNotificationContent(null))
    }
}
