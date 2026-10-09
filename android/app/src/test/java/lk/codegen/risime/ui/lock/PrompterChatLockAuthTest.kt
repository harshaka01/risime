package lk.codegen.risime.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locked chats' confirmation now runs on the app lock's [LockPrompter] (one BiometricPrompt per
 * activity). The old per-call BiometricPrompt had the nightly.35 dead-prompt pattern: authenticate()
 * dropped silently (after onSaveInstanceState) left the gate busy and every later "Lock chat" ignored.
 */
class PrompterChatLockAuthTest {
    private class FakeAuth : LockAuthenticator {
        override var sdkInt = 34
        var bio = BiometricManager.BIOMETRIC_SUCCESS
        var secure = true
        var resumed = true
        var showing = false
        var appear = true
        val shown = mutableListOf<Any>()
        var cb: ((LockOutcome) -> Unit)? = null
        var cancels = 0
        override fun strongBiometric() = bio
        override fun deviceSecure() = secure
        override fun isResumed() = resumed
        override fun promptShowing() = showing

        override fun showBiometric(req: LockPromptRequest, onOutcome: (LockOutcome) -> Unit): String? {
            check(resumed) { "authenticate() while not resumed" }
            shown += req
            cb = onOutcome
            showing = appear
            return null
        }

        override fun showCredential(title: String, onOutcome: (LockOutcome) -> Unit): String? {
            check(resumed)
            shown += "credential:$title"
            cb = onOutcome
            showing = appear
            return null
        }

        override fun cancel() {
            cancels++
            showing = false
        }

        fun answer(o: LockOutcome) {
            showing = false
            cb!!.invoke(o)
        }
    }

    private val auth = FakeAuth()
    private var now = 10_000L
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val chatAuth = PrompterChatLockAuth(auth, schedule = { ms, f -> timers += (now + ms) to f }, now = { now }, log = {})

    private fun advance(ms: Long) {
        now += ms
        timers.filter { it.first <= now }.also { timers.removeAll(it) }.forEach { it.second() }
    }

    @Test fun successConfirmsWithTheChatsTitleAndName() = runTest {
        val r = async { chatAuth.confirm(LOCK_CHAT_LABEL, "Kumu") }
        runCurrent()
        val req = auth.shown.single() as LockPromptRequest
        assertEquals(LOCK_CHAT_LABEL, req.title)
        assertEquals("Kumu", req.subtitle)
        assertTrue(req.allowCredential) // API 30+: fingerprint or the phone's PIN in the same prompt
        auth.answer(LockOutcome.Succeeded())
        assertTrue(r.await())
        assertNull(chatAuth.lastProblem())
    }

    @Test fun cancelIsFalseWithNothingToSay() = runTest {
        val r = async { chatAuth.confirm(LOCK_CHAT_LABEL) }
        runCurrent()
        auth.answer(LockOutcome.Error(BiometricPrompt.ERROR_USER_CANCELED, "cancelled"))
        assertFalse(r.await())
        assertNull(chatAuth.lastProblem())
    }

    @Test fun aPromptThatNeverAppearsEndsAndTheNextTapPromptsAgain() = runTest {
        auth.appear = false // authenticate() accepted, but no prompt ever shows (the dead prompt)
        val r = async { chatAuth.confirm(LOCK_CHAT_LABEL) }
        runCurrent()
        advance(LockPrompter.WATCHDOG_MS)
        assertFalse(r.await()) // never a confirmation that waits for ever
        assertEquals(CHAT_LOCK_PROMPT_NOT_SHOWN, chatAuth.lastProblem())
        auth.appear = true
        val again = async { chatAuth.confirm(LOCK_CHAT_LABEL) }
        runCurrent()
        assertEquals(2, auth.shown.size)
        auth.answer(LockOutcome.Succeeded())
        assertTrue(again.await())
    }

    @Test fun aRequestWhileStoppedWaitsForResume() = runTest {
        auth.resumed = false
        val r = async { chatAuth.confirm(LOCKED_CHATS_TITLE) }
        runCurrent()
        assertTrue(auth.shown.isEmpty())
        auth.resumed = true
        chatAuth.onResumed()
        assertEquals(1, auth.shown.size)
        auth.answer(LockOutcome.Succeeded())
        assertTrue(r.await())
    }

    @Test fun aPhoneWithOnlyAPinUsesThePinPrompt() = runTest {
        auth.bio = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        assertEquals(ChatLockAuthStatus.READY, chatAuth.status())
        val r = async { chatAuth.confirm(LOCK_CHAT_LABEL) }
        runCurrent()
        assertEquals("credential:$LOCK_CHAT_LABEL", auth.shown.single())
        auth.answer(LockOutcome.Succeeded())
        assertTrue(r.await())
    }

    @Test fun noFingerprintAndNoScreenLockAsksForOne() {
        auth.bio = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        auth.secure = false
        assertEquals(ChatLockAuthStatus.NEEDS_SCREEN_LOCK, chatAuth.status())
    }

    @Test fun problemTexts() {
        assertNull(chatLockProblemText(LOCK_CANCELLED))
        assertNull(chatLockProblemText(LOCK_TIMED_OUT))
        assertEquals(CHAT_LOCK_PROMPT_NOT_SHOWN, chatLockProblemText(LOCK_NOT_SHOWN))
        assertTrue(chatLockProblemText(LOCK_TOO_MANY_ATTEMPTS)!!.contains("PIN"))
    }
}
