package lk.codegen.risime.data.lock

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.auth.MigrationStep
import lk.codegen.risime.data.auth.migrationStep
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
    private var bio = BiometricStatus.AVAILABLE
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
        bio = BiometricStatus.GONE
        val l = lock(store)
        l.load()
        assertEquals(false, l.locked.value)
        assertFalse(store.s.enabled)
        assertTrue(logs.single().contains("turned off"))
        // On resume too.
        bio = BiometricStatus.AVAILABLE
        val store2 = Store(AppLockSettings(enabled = true, autoLock = AutoLock.ONE_MINUTE))
        val l2 = lock(store2)
        l2.load()
        l2.unlocked()
        bio = BiometricStatus.GONE
        l2.onBackground()
        now += 120_000
        l2.onForeground()
        assertEquals(false, l2.locked.value)
        assertFalse(store2.s.enabled)
    }

    /** Review of 064: a busy sensor / pending security update / unsupported state never turns the lock off. */
    @Test fun aTransientBiometricStateKeepsTheLockOn() = runTest {
        val store = Store(AppLockSettings(enabled = true, autoLock = AutoLock.ONE_MINUTE))
        bio = BiometricStatus.UNAVAILABLE_NOW
        val l = lock(store)
        l.load()
        assertEquals(true, l.locked.value) // still locked at start
        assertTrue(store.s.enabled)
        l.onForeground()
        assertEquals(true, l.locked.value)
        assertTrue(store.s.enabled)
        assertTrue(logs.any { it.contains("stays on") })
        // Turning it on still needs a usable fingerprint now.
        val off = Store()
        lock(off).setEnabled(true)
        assertFalse(off.s.enabled)
    }

    @Test fun onlyNoneEnrolledOrNoHardwareCountAsGone() {
        assertEquals(BiometricStatus.AVAILABLE, BiometricStatus.of(androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(BiometricStatus.GONE, BiometricStatus.of(androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(BiometricStatus.GONE, BiometricStatus.of(androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        listOf(
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            androidx.biometric.BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
        ).forEach { assertEquals("code $it", BiometricStatus.UNAVAILABLE_NOW, BiometricStatus.of(it)) }
    }

    /** Review of 064: the migration deletes the old vault only when it can never open again. */
    @Test fun migrationKeepsTheOldVaultOnATransientBiometricError() {
        assertEquals(MigrationStep.PROMPT, migrationStep(cipherAvailable = true, BiometricStatus.AVAILABLE))
        assertEquals(MigrationStep.TRY_AGAIN, migrationStep(cipherAvailable = true, BiometricStatus.UNAVAILABLE_NOW))
        assertEquals(MigrationStep.SIGN_IN_AGAIN, migrationStep(cipherAvailable = true, BiometricStatus.GONE))
        assertEquals(MigrationStep.SIGN_IN_AGAIN, migrationStep(cipherAvailable = false, BiometricStatus.AVAILABLE))
    }

    /** Review of 064: a push-started process posts before the async load: the settings are read first. */
    @Test fun hiddenContentHoldsBeforeTheSettingsAreLoaded() = runTest {
        val l = lock(Store(AppLockSettings(enabled = true, showContent = false)))
        assertNull(l.settings.value) // nothing loaded yet
        assertTrue(l.hideNotificationContent())
        val blocking = lock(Store(AppLockSettings(enabled = true, showContent = false)))
        assertTrue(blocking.hideNotificationContentBlocking())
        // Off by default: shown, also before the load.
        assertFalse(lock(Store()).hideNotificationContentBlocking())
        // Settings that can't be read at all: hidden (never shown against the user's choice).
        val broken = object : AppLockStore {
            override suspend fun load(): AppLockSettings = kotlinx.coroutines.awaitCancellation()
            override suspend fun save(s: AppLockSettings) = Unit
        }
        val stuck = AppLock(broken, Stamp(), { BiometricStatus.AVAILABLE }, { now })
        assertTrue(stuck.hideNotificationContent())
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
        bio = BiometricStatus.GONE
        l.setEnabled(true)
        assertFalse(store.s.enabled)
    }

    @Test fun unreadableSettingsNeverKeepTheUserOut() = runTest {
        val broken = object : AppLockStore {
            override suspend fun load(): AppLockSettings = throw java.io.IOException("corrupt")
            override suspend fun save(s: AppLockSettings) = Unit
        }
        val l = AppLock(broken, Stamp(), { BiometricStatus.AVAILABLE }, { now })
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

    // ---- The nightly.35 lock-out hotfix: never a dead end ----

    @Test fun aScreenLockKeepsTheLockUsableWhenTheFingerprintIsGone() = runTest {
        val store = Store(AppLockSettings(enabled = true))
        bio = BiometricStatus.GONE
        val l = AppLock(store, Stamp(), { bio }, { now }, { logs += it }, canUnlock = { true })
        l.load()
        assertEquals(true, l.locked.value) // the phone's PIN/pattern unlocks it
        assertTrue(store.s.enabled)
    }

    @Test fun noFingerprintAndNoScreenLockTurnsTheLockOffAndOpensTheApp() = runTest {
        val store = Store(AppLockSettings(enabled = true))
        var unlockable = true
        val l = AppLock(store, Stamp(), { bio }, { now }, { logs += it }, canUnlock = { unlockable })
        l.load()
        assertEquals(true, l.locked.value)
        unlockable = false // the screen lock was removed while RisiMe was locked
        l.offIfNoWayToUnlockNow()
        assertEquals(false, l.locked.value)
        assertFalse(store.s.enabled)
        assertTrue(logs.any { it.contains("no fingerprint and no screen lock") })
    }

    @Test fun turningOnClearsTheBackgroundStamp() = runTest {
        val stamp = Stamp(now - 5_000) // e.g. the screen-lock activity covered RisiMe while confirming
        val l = lock(Store(), stamp)
        l.load()
        l.setEnabled(true)
        assertNull(stamp.v)
        l.checkOnReturn()
        assertEquals(false, l.locked.value) // not locked right after turning it on
    }

    /** Settings written by 0.2.0-nightly.35 (same DataStore keys) with the lock on: locked at start, and an unlock lets the user in. */
    @Test fun nightly35SettingsWithTheLockOnLoadAndUnlock() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("applock").toFile()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        try {
            val prefs = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) { java.io.File(dir, "risime.preferences_pb") }
            prefs.edit {
                it[androidx.datastore.preferences.core.booleanPreferencesKey("app_lock_enabled")] = true
                it[androidx.datastore.preferences.core.longPreferencesKey("app_lock_after_ms")] = 0L
                it[androidx.datastore.preferences.core.booleanPreferencesKey("app_lock_show_content")] = true
            }
            val l = AppLock(DataStoreAppLockStore(prefs), Stamp(), { bio }, { now }, { logs += it }, canUnlock = { true })
            l.load()
            assertEquals(AppLockSettings(true, AutoLock.IMMEDIATELY, true), l.settings.value)
            assertEquals(true, l.locked.value)
            l.unlocked() // what the prompt's success does
            assertEquals(false, l.locked.value)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /** The lock exists only if the user turned it on: an empty store (fresh install, update, migration) is off. */
    @Test fun neverEnabledByDefault() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("applock").toFile()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        try {
            val prefs = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) { java.io.File(dir, "risime.preferences_pb") }
            val l = AppLock(DataStoreAppLockStore(prefs), Stamp(), { bio }, { now }, { logs += it })
            l.load()
            assertFalse(l.settings.value!!.enabled)
            assertEquals(false, l.locked.value)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /**
     * No code path turns the lock on except the Settings toggle (AppLock.setEnabled after the prompt)
     * and the debug-build device-test receiver: not the migration, sign-in, updates or a restore.
     */
    @Test fun onlyTheSettingsToggleEnablesTheLock() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        val enabling = Regex("""setEnabled\(\s*true|debugSet\(|enabled\s*=\s*true|\[ENABLED]\s*=\s*true""")
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.path.contains("/lock/") || it.isFile && it.name in setOf("AppContainer.kt", "AuthUi.kt") }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (enabling.containsMatchIn(line) && !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//")) "${f.name}:${i + 1}" else null } }
            .map { it.substringBefore(":") }.toSet()
        // AppLock.kt: setEnabled/debugSet themselves; AuthUi: the turn-on prompt's success; AppContainer: the debug receiver (BuildConfig.DEBUG only).
        assertEquals(setOf("AppLock.kt", "AuthUi.kt", "AppContainer.kt"), hits)
        val container = java.io.File(root, "lk/codegen/risime/AppContainer.kt").readText()
        assertTrue(container.contains("if (BuildConfig.DEBUG) registerDebugAppLock(context)"))
        val migration = java.io.File(root, "lk/codegen/risime/data/auth").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(Regex("""appLock\.(set|debug)""").containsMatchIn(migration))
    }
}
