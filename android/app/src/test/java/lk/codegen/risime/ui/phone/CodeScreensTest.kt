package lk.codegen.risime.ui.phone

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.MeReply
import lk.codegen.risime.net.PhoneVerifyRequestReply
import lk.codegen.risime.net.User
import lk.codegen.risime.ui.login.LoginUiState
import lk.codegen.risime.ui.login.OTP_RESEND_TAG
import lk.codegen.risime.ui.login.OTP_VERIFY_TAG
import lk.codegen.risime.ui.login.OtpContent
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One-time-code screens (hotfix): a visible countdown with the button off until it ends, the
 * server's Retry-After, one request per tap burst, "1 attempt left", and the deadline kept across
 * rotation / process death.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CodeScreensTest {
    @get:Rule val rule = createComposeRule()

    private val me = User("u1", "+94771234522", "H", "C", phoneVerified = false)
    private var now = 1_000_000L

    private inner class Backend : PhoneVerifyBackend {
        var requestReply: ApiResult<PhoneVerifyRequestReply> = ApiResult.Ok(PhoneVerifyRequestReply("sent", 300, "+9477•••••22"))
        var confirmGate: CompletableDeferred<ApiResult<MeReply>>? = null
        var confirmReply: ApiResult<MeReply> = ApiResult.Ok(MeReply(me.copy(phoneVerified = true)))
        var requests = 0
        var confirms = 0
        override suspend fun request() = requestReply.also { requests++ }
        override suspend fun confirm(code: String): ApiResult<MeReply> {
            confirms++
            return confirmGate?.await() ?: confirmReply
        }
        override suspend fun me(): ApiResult<MeReply> = ApiResult.Ok(MeReply(me.copy(phoneVerified = true)))
        override suspend fun verified(user: User) = Unit
        override suspend fun signOut() = Unit
    }

    private fun phone(b: Backend): PhoneVerifyViewModel {
        val vm = PhoneVerifyViewModel(b, me.phone) { now }
        rule.setContent { RisiMeTheme { PhoneVerifyScreen(vm) } }
        return vm
    }

    @Test fun sendShowsAVisibleCountdownAndKeepsResendOff() {
        val b = Backend()
        phone(b)
        rule.onNodeWithTag(PHONE_RESEND_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Resend in 1:00").assertIsDisplayed()
        rule.onNodeWithTag(PHONE_RESEND_TAG).assertIsNotEnabled().performClick()
        assertEquals(1, b.requests)
    }

    @Test fun rateLimitedUsesTheServersRetryAfterAndShowsTheTimeLeft() {
        val b = Backend().apply { requestReply = ApiResult.Error(429, "rate_limited", "", retryAfterSec = 842) }
        phone(b)
        rule.onNodeWithTag(PHONE_RESEND_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Send code in 14:02").assertIsDisplayed()
        rule.onNodeWithTag(PHONE_RESEND_TAG).assertIsNotEnabled()
        // A 429 without Retry-After: the contract's 60 s, never less.
        assertEquals(60L, CodeLimits.retryAfterSec(ApiResult.Error(429, "rate_limited", "")))
    }

    @Test fun aDoubleSubmitSendsOneConfirm() {
        val b = Backend().apply { confirmGate = CompletableDeferred() }
        phone(b)
        rule.onNodeWithTag(PHONE_RESEND_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("6-digit code").performTextInput("123456") // the 6th digit auto-submits
        rule.waitForIdle()
        rule.onNodeWithTag(PHONE_CONFIRM_TAG).assertIsNotEnabled().performClick()
        rule.onNodeWithTag(PHONE_CONFIRM_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, b.confirms)
        b.confirmGate!!.complete(ApiResult.Error(401, "invalid_code", "", attemptsLeft = 1))
        rule.waitForIdle()
        rule.onNodeWithText("Wrong code — 1 attempt left").assertIsDisplayed()
    }

    @Test fun tooManyAttemptsLocksTheCodeUntilANewOneAfterRetryAfter() {
        val b = Backend().apply { confirmReply = ApiResult.Error(429, "too_many_attempts", "", retryAfterSec = 120) }
        phone(b)
        rule.onNodeWithTag(PHONE_RESEND_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("6-digit code").performTextInput("123456")
        rule.waitForIdle()
        rule.onNodeWithText("Too many attempts. Send a new code.").assertIsDisplayed()
        rule.onNodeWithTag(PHONE_CONFIRM_TAG).assertIsNotEnabled()
        rule.onNodeWithText("Resend in 2:00").assertIsDisplayed()
        assertEquals(1, b.confirms)
    }

    @Test fun theDeadlineSurvivesRotationAndProcessDeath() {
        val b = Backend()
        val restore = StateRestorationTester(rule)
        var created = 0
        restore.setContent {
            // A fresh ViewModel after restore, as after process death; only saved state is left.
            val vm = remember { created++; PhoneVerifyViewModel(b, me.phone) { now } }
            RisiMeTheme { PhoneVerifyScreen(vm) }
        }
        rule.onNodeWithTag(PHONE_RESEND_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Resend in 1:00").assertIsDisplayed()
        now += 18_000 // 18 s later the activity is recreated
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(2, created)
        rule.onNodeWithText("Resend in 0:42").assertIsDisplayed()
        rule.onNodeWithTag(PHONE_RESEND_TAG).assertIsNotEnabled()
        rule.onNodeWithText("6-digit code").assertIsDisplayed() // still on the code step
        assertEquals(1, b.requests)
    }

    @Test fun devOtpShowsTheCountdownLastAttemptAndOneVerifyPerTap() {
        var verifies = 0
        val start = LoginUiState(codeSentTo = "+94771234522", code = "123456", resendInSec = 42, attemptsLeft = 1)
        rule.setContent {
            var s by remember { mutableStateOf(start) }
            RisiMeTheme {
                OtpContent(
                    s, onCode = {}, onVerify = { if (s.canVerify) { verifies++; s = s.copy(busy = true) } },
                    onResend = {}, onBack = {},
                )
            }
        }
        rule.onNodeWithText("Resend in 0:42").assertIsDisplayed()
        rule.onNodeWithTag(OTP_RESEND_TAG).assertIsNotEnabled()
        rule.onNodeWithText("1 attempt left").assertIsDisplayed()
        rule.onNodeWithTag(OTP_VERIFY_TAG).assertIsEnabled().performClick()
        rule.onNodeWithTag(OTP_VERIFY_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, verifies)
        rule.onNodeWithTag(OTP_VERIFY_TAG).assertIsNotEnabled().assertTextContains("Verifying…")
    }

    @Test fun formats() {
        assertEquals("0:42", CodeLimits.countdown(42))
        assertEquals("14:02", CodeLimits.countdown(842))
        assertEquals(42, CodeLimits.secondsLeft(1_042_000, 1_000_000))
        assertEquals(1, CodeLimits.secondsLeft(1_000_001, 1_000_000))
        assertEquals("deadlines never move earlier", 2_000_000L, CodeLimits.later(2_000_000, 1_000_000, 10))
        assertEquals("Wrong code — 1 attempt left", CodeLimits.wrongCode(1))
    }
}
