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
        LockPrivacy.apply(a, lockOn = true)
        assertTrue(secure(a))
        LockPrivacy.apply(a, lockOn = false)
        assertFalse(secure(a))
    }

    @Config(sdk = [34])
    @Test fun api33PlusUsesTheRecentsSettingNotFlagSecure() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        LockPrivacy.apply(a, lockOn = true)
        assertFalse("screenshots by the user still work on 33+", secure(a))
        LockPrivacy.apply(a, lockOn = false)
        assertFalse(secure(a))
    }

    @Test fun theCallScreenIsNeverMadeSecure() {
        // Only MainActivity applies the lock privacy; CallActivity must stay visible/answerable.
        val src = java.io.File("src/main/java/lk/codegen/risime/calls/CallActivity.kt").readText()
        assertFalse(src.contains("FLAG_SECURE") || src.contains("LockPrivacy"))
        assertTrue(java.io.File("src/main/java/lk/codegen/risime/MainActivity.kt").readText().contains("LockPrivacy.apply"))
    }
}
