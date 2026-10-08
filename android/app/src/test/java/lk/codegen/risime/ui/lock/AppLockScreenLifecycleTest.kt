package lk.codegen.risime.ui.lock

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The nightly.35 lock-out: the lock screen prompts only when RESUMED, every time it is resumed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppLockScreenLifecycleTest {
    @get:Rule val rule = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun autoPromptWaitsForResumeAndRepeatsOnEveryResume() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.CREATED } // the activity is STOPPED
        var autos = 0
        var taps = 0
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RisiMeTheme { AppLockScreen(onUnlock = { taps++ }, onAutoPrompt = { autos++ }) }
            }
        }
        rule.waitForIdle()
        assertEquals(0, autos) // shown while stopped: no prompt (BiometricPrompt would drop it)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
        assertEquals(1, autos)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
        assertEquals(2, autos) // back from the background: prompts again
        rule.onNodeWithText(UNLOCK_WITH_FINGERPRINT).performClick()
        rule.onNodeWithText(UNLOCK_WITH_FINGERPRINT).performClick()
        assertEquals(2, taps) // every tap goes through
    }

    @Test fun anErrorShowsTheMessageAndThePinButton() {
        var pins = 0
        rule.setContent {
            RisiMeTheme { AppLockScreen(onUnlock = {}, autoPrompt = false, message = LOCK_TOO_MANY_ATTEMPTS, showPin = true, onUsePin = { pins++ }) }
        }
        rule.onNodeWithText(LOCK_TOO_MANY_ATTEMPTS).assertIsDisplayed()
        rule.onNodeWithText(USE_PHONE_PIN).assertIsDisplayed().performClick()
        assertEquals(1, pins)
    }

    @Test fun noPinButtonBeforeAnyError() {
        rule.setContent { RisiMeTheme { AppLockScreen(onUnlock = {}, autoPrompt = false) } }
        rule.onNodeWithText(USE_PHONE_PIN).assertDoesNotExist()
    }

    /** The real authenticator refuses (with a reason, so it is logged and shown) instead of calling authenticate() while stopped. */
    @Test fun theAndroidAuthenticatorRefusesWhileStopped() {
        val ctl = Robolectric.buildActivity(FragmentActivity::class.java).create()
        val a = BiometricLockAuthenticator(ctl.get()) // as AuthUi: made in onCreate
        ctl.start().resume().pause().stop()
        assertTrue(a.promptShowing() || !a.isResumed())
        var called = false
        val why = a.showBiometric(LockPromptRequest("Unlock RisiMe", allowCredential = true, negativeText = null)) { called = true }
        assertTrue(why!!.startsWith("activity not resumed"))
        val why2 = a.showCredential("Unlock RisiMe") { called = true }
        assertTrue(why2 != null)
        assertTrue(!called)
    }
}
