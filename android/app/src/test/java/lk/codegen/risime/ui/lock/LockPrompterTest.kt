package lk.codegen.risime.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nightly.35 lock-out (P0): the lock screen auto-prompted while the activity was STOPPED,
 * BiometricPrompt dropped authenticate() with no callback, the busy flag stayed set and every tap
 * was ignored. A lock screen never traps the user.
 */
class LockPrompterTest {
    private class FakeAuth(override var sdkInt: Int = 34) : LockAuthenticator {
        var bio = BiometricManager.BIOMETRIC_SUCCESS
        var secure = true
        var resumed = true
        var showing = false

        /** What authenticate() does: null = shows the prompt; a reason = refused (no callback, ever). */
        var refuse: String? = null
        val shown = mutableListOf<Any>()
        var cb: ((LockOutcome) -> Unit)? = null
        var cancels = 0

        override fun strongBiometric() = bio
        override fun deviceSecure() = secure
        override fun isResumed() = resumed
        override fun promptShowing() = showing

        override fun showBiometric(req: LockPromptRequest, onOutcome: (LockOutcome) -> Unit): String? {
            check(resumed) { "authenticate() while not resumed (the n35 bug)" }
            refuse?.let { return it }
            shown += req
            cb = onOutcome
            showing = true
            return null
        }

        override fun showCredential(title: String, onOutcome: (LockOutcome) -> Unit): String? {
            check(resumed)
            refuse?.let { return it }
            shown += "credential"
            cb = onOutcome
            showing = true
            return null
        }

        override fun cancel() {
            cancels++
            showing = false
        }

        fun answer(o: LockOutcome) {
            if (o !is LockOutcome.Failed) showing = false
            cb!!.invoke(o)
        }
    }

    private val auth = FakeAuth()
    private var unlocks = 0
    private var noWay = 0
    private var now = 10_000L
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val logs = mutableListOf<String>()

    private fun prompter(a: FakeAuth = auth) = LockPrompter(
        auth = a,
        onUnlocked = { unlocks++ },
        onNoWayToUnlock = { noWay++ },
        schedule = { ms, f -> timers += (now + ms) to f },
        now = { now },
        log = { logs += it },
    )

    private fun advance(ms: Long) {
        now += ms
        timers.filter { it.first <= now }.also { timers.removeAll(it) }.forEach { it.second() }
    }

    @Test fun promptAppearsWhenResumedAndSuccessUnlocks() {
        val p = prompter()
        p.autoPrompt()
        assertEquals(1, auth.shown.size)
        val req = auth.shown.single() as LockPromptRequest
        assertTrue(req.allowCredential) // API 30+: fingerprint or the phone's PIN in one prompt
        assertNull(req.negativeText)
        auth.answer(LockOutcome.Succeeded())
        assertEquals(1, unlocks)
        assertNull(p.message.value)
        assertTrue(logs.any { it == "RisiMe lock: auth succeeded" })
    }

    /** The exact nightly.35 bug: the lock engaged while the activity was STOPPED. */
    @Test fun aPromptRequestedWhileStoppedWaitsForResume() {
        val p = prompter()
        auth.resumed = false
        p.autoPrompt()
        assertTrue(auth.shown.isEmpty()) // never authenticate() while stopped
        assertTrue(logs.any { it.contains("deferred") })
        auth.resumed = true
        p.onResumed()
        assertEquals(1, auth.shown.size)
        p.autoPrompt() // the lock screen's own resume effect: no second prompt
        assertEquals(1, auth.shown.size)
        auth.answer(LockOutcome.Succeeded())
        assertEquals(1, unlocks)
    }

    @Test fun cancelThenTheNextTapPromptsAgain() {
        val p = prompter()
        p.autoPrompt()
        auth.answer(LockOutcome.Error(BiometricPrompt.ERROR_USER_CANCELED, "Cancelled"))
        assertEquals(LOCK_CANCELLED, p.message.value)
        assertTrue(p.showPin.value)
        assertTrue(logs.any { it == "RisiMe lock: auth error code=10 msg=Cancelled" })
        p.autoPrompt() // a resume right after our own prompt closed: no loop
        assertEquals(1, auth.shown.size)
        p.tap()
        assertEquals(2, auth.shown.size) // the tap is never ignored
        p.tap() // the prompt is on screen: nothing to do
        assertEquals(2, auth.shown.size)
        auth.answer(LockOutcome.Succeeded())
        assertEquals(1, unlocks)
        // Back from the background: it prompts by itself again.
        p.onStopped()
        p.autoPrompt()
        assertEquals(3, auth.shown.size)
    }

    @Test fun anAttemptThatNeverShowedIsDroppedByTheNextTap() {
        val p = prompter()
        p.autoPrompt()
        auth.showing = false // authenticate() silently ignored (no callback will ever come)
        p.tap()
        assertEquals(2, auth.shown.size)
        assertEquals(1, auth.cancels)
        auth.answer(LockOutcome.Succeeded())
        assertEquals(1, unlocks)
    }

    @Test fun theWatchdogClearsAPromptThatNeverAppeared() {
        val p = prompter()
        p.autoPrompt()
        auth.showing = false
        advance(LockPrompter.WATCHDOG_MS)
        assertEquals(LOCK_NOT_SHOWN, p.message.value)
        assertTrue(p.showPin.value)
        assertTrue(logs.any { it.startsWith("RisiMe lock: authenticate() refused:") })
        p.tap()
        assertEquals(2, auth.shown.size)
    }

    @Test fun aRefusedAuthenticateIsLoggedAndShown() {
        val p = prompter()
        auth.refuse = "called after onSaveInstanceState"
        p.autoPrompt()
        assertEquals(LOCK_NOT_SHOWN, p.message.value)
        assertTrue(p.showPin.value)
        assertTrue(logs.any { it == "RisiMe lock: authenticate() refused: called after onSaveInstanceState" })
        auth.refuse = null
        p.tap()
        assertEquals(1, auth.shown.size)
    }

    @Test fun lockoutShowsAMessageAndThePinButtonWhichUsesTheScreenLock() {
        val p = prompter()
        p.autoPrompt()
        auth.answer(LockOutcome.Failed)
        assertTrue(logs.any { it == "RisiMe lock: auth failed (no match)" })
        auth.answer(LockOutcome.Error(BiometricPrompt.ERROR_LOCKOUT, "Too many attempts"))
        assertEquals(LOCK_TOO_MANY_ATTEMPTS, p.message.value)
        assertTrue(p.showPin.value)
        p.usePin()
        assertEquals("credential", auth.shown.last())
        auth.answer(LockOutcome.Succeeded())
        assertEquals(1, unlocks)
        assertFalse(p.showPin.value)
    }

    @Test fun hardwareErrorsSayFingerprintUnavailable() {
        val p = prompter()
        p.autoPrompt()
        auth.answer(LockOutcome.Error(BiometricPrompt.ERROR_HW_UNAVAILABLE, "busy"))
        assertEquals("Fingerprint unavailable — use your phone PIN/pattern", p.message.value)
        assertTrue(p.showPin.value)
        assertEquals(0, noWay) // a screen lock exists: the lock stays on
    }

    @Test fun api29UsesTheUsePinNegativeButton() {
        val a = FakeAuth(sdkInt = 29)
        val p = prompter(a)
        p.autoPrompt()
        val req = a.shown.single() as LockPromptRequest
        assertFalse(req.allowCredential) // BIOMETRIC_STRONG | DEVICE_CREDENTIAL is API 30+
        assertEquals(USE_PIN_NEGATIVE, req.negativeText)
        a.answer(LockOutcome.Error(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "Use PIN"))
        assertEquals("credential", a.shown.last()) // the Keyguard confirmation
        a.answer(LockOutcome.Succeeded())
        assertEquals(1, unlocks)
    }

    @Test fun api29KeyguardActivityStopIsNotTheUserLeaving() {
        val a = FakeAuth(sdkInt = 29)
        val p = prompter(a)
        p.usePin()
        a.resumed = false
        p.onStopped() // the Keyguard activity covers ours
        a.resumed = true
        a.answer(LockOutcome.Error(BiometricPrompt.ERROR_USER_CANCELED, "cancelled"))
        p.autoPrompt() // back on the lock screen: no automatic re-prompt loop
        assertEquals(1, a.shown.size)
        p.tap()
        assertEquals(2, a.shown.size)
    }

    @Test fun noFingerprintButAScreenLockGoesStraightToTheCredential() {
        auth.bio = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        val p = prompter()
        p.autoPrompt()
        assertEquals("credential", auth.shown.single())
        auth.answer(LockOutcome.Succeeded())
        assertEquals(1, unlocks)
    }

    @Test fun noFingerprintAndNoScreenLockTurnsTheLockOff() {
        auth.bio = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        auth.secure = false
        val p = prompter()
        p.autoPrompt()
        assertTrue(auth.shown.isEmpty())
        assertEquals(1, noWay)
        assertEquals(0, unlocks)
    }

    @Test fun aStaleCancelAfterARestartIsIgnored() {
        val p = prompter()
        p.autoPrompt()
        val stale = auth.cb!!
        auth.showing = false
        p.tap() // restart; the old prompt's cancel may arrive late
        stale(LockOutcome.Error(BiometricPrompt.ERROR_CANCELED, "Cancelled"))
        assertNull(p.message.value)
        auth.answer(LockOutcome.Succeeded())
        assertEquals(1, unlocks)
    }
}
