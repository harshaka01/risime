package lk.codegen.risime.ui.lock

import android.app.Activity
import android.app.Application
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review of 064: no Recents thumbnail of the chats while the fingerprint lock is on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LockPrivacyTest {
    private fun secure(a: Activity) = a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test fun planPerApiLevel() {
        assertEquals(LockPrivacy.Plan(recentsScreenshot = false, flagSecure = false), LockPrivacy.plan(lockOn = true, sdk = 33))
        assertEquals(LockPrivacy.Plan(recentsScreenshot = true, flagSecure = false), LockPrivacy.plan(lockOn = false, sdk = 35))
        assertEquals(LockPrivacy.Plan(recentsScreenshot = null, flagSecure = true), LockPrivacy.plan(lockOn = true, sdk = 32))
        assertEquals(LockPrivacy.Plan(recentsScreenshot = null, flagSecure = false), LockPrivacy.plan(lockOn = false, sdk = 26))
    }

    @Config(sdk = [32])
    @Test fun flagSecureBelow33WhileTheLockIsOnAndRemovedWhenOff() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertFalse(secure(a))
        LockPrivacy.apply(a, lockOn = true, secureScreen = false, sharing = false)
        assertTrue(secure(a))
        LockPrivacy.apply(a, lockOn = false, secureScreen = false, sharing = false)
        assertFalse(secure(a))
    }

    @Config(sdk = [34])
    @Test fun api33PlusUsesTheRecentsSettingNotFlagSecure() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        LockPrivacy.apply(a, lockOn = true, secureScreen = false, sharing = false)
        assertFalse("screenshots by the user still work on 33+", secure(a))
        LockPrivacy.apply(a, lockOn = false, secureScreen = false, sharing = false)
        assertFalse(secure(a))
    }

    @Test fun theCallScreenIsNeverMadeSecure() {
        // Only MainActivity applies the lock privacy; CallActivity must stay visible/answerable.
        val src = java.io.File("src/main/java/lk/codegen/risime/calls/CallActivity.kt").readText()
        assertFalse(src.contains("LockPrivacy"))
        // Harsha's report: no FLAG_SECURE on the call screen while sharing either (the viewer sees it, as on WhatsApp).
        assertFalse(src.contains("FLAG_SECURE"))
        assertTrue(java.io.File("src/main/java/lk/codegen/risime/MainActivity.kt").readText().contains("LockPrivacy.apply"))
    }

    // ---- Screen sharing (Harsha's real-phone report): FLAG_SECURE only on the secure screens ----

    @Test fun onlyTheSecureScreensAreFlagSecureOnEveryApiLevel() {
        for (sdk in listOf(26, 32, 33, 35)) for (lock in listOf(false, true)) for (sharing in listOf(false, true)) {
            assertTrue("secure screen sdk=$sdk lock=$lock sharing=$sharing", LockPrivacy.plan(lock, sdk, secureScreen = true, sharing = sharing).flagSecure)
        }
        for (sdk in listOf(33, 35)) for (lock in listOf(false, true)) for (sharing in listOf(false, true)) {
            assertFalse("normal screen sdk=$sdk lock=$lock sharing=$sharing", LockPrivacy.plan(lock, sdk, secureScreen = false, sharing = sharing).flagSecure)
        }
    }

    @Test fun aShareNeverMakesANormalScreenSecureAndDropsThePre33LockFallback() {
        for (sdk in listOf(26, 30, 32, 33, 34, 35)) for (lock in listOf(false, true)) {
            assertFalse("sharing sdk=$sdk lock=$lock", LockPrivacy.plan(lock, sdk, secureScreen = false, sharing = true).flagSecure)
        }
        // Not sharing, below 33: the lock's Recents fallback stays.
        assertTrue(LockPrivacy.plan(lockOn = true, sdk = 32, secureScreen = false, sharing = false).flagSecure)
    }

    @Config(sdk = [34])
    @Test fun applyWhileSharingKeepsNormalScreensVisibleAndSecureScreensBlack() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        LockPrivacy.apply(a, lockOn = true, secureScreen = false, sharing = true)
        assertFalse("the chat list is visible in an entire-screen share", secure(a))
        LockPrivacy.apply(a, lockOn = true, secureScreen = true, sharing = true)
        assertTrue("a locked chat stays black", secure(a))
        LockPrivacy.apply(a, lockOn = true, secureScreen = false, sharing = true)
        assertFalse(secure(a))
    }

    @Config(sdk = [32])
    @Test fun pre33LockFallbackIsDroppedWhileSharing() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        LockPrivacy.apply(a, lockOn = true, secureScreen = false, sharing = false)
        assertTrue(secure(a))
        LockPrivacy.apply(a, lockOn = true, secureScreen = false, sharing = true)
        assertFalse(secure(a))
    }
}
