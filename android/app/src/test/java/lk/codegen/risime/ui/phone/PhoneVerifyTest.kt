package lk.codegen.risime.ui.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.MeReply
import lk.codegen.risime.net.PhoneVerifyRequestReply
import lk.codegen.risime.net.User
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneVerifyTest {
    private val dispatcher = StandardTestDispatcher()
    private val me = User("u1", "+94771234522", "H", "C", phoneVerified = false)

    private class Backend(val me: User) : PhoneVerifyBackend {
        var requestReply: ApiResult<PhoneVerifyRequestReply> = ApiResult.Ok(PhoneVerifyRequestReply("sent", 300, "+9477•••••22"))
        var confirmReply: ApiResult<MeReply>? = null
        var meReply: ApiResult<MeReply> = ApiResult.Ok(MeReply(me.copy(phoneVerified = true)))
        val codes = mutableListOf<String>()
        var requests = 0
        var verifiedUser: User? = null
        var signedOut = false
        override suspend fun request() = requestReply.also { requests++ }
        override suspend fun confirm(code: String): ApiResult<MeReply> {
            codes += code
            return confirmReply ?: ApiResult.Ok(MeReply(me.copy(phoneVerified = true)))
        }
        override suspend fun me() = meReply
        override suspend fun verified(user: User) { verifiedUser = user }
        override suspend fun signOut(confirmed: lk.codegen.risime.data.UserConfirmation) { signedOut = true }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(b: Backend) = PhoneVerifyViewModel(b, me.phone) { testScheduler.currentTime }.also { runCurrent() }

    @Test fun masking() {
        assertEquals("+9477•••••22", maskPhone("+94771234522"))
        assertEquals("+123", maskPhone("+123"))
    }

    @Test fun errorTable() {
        val wrong = phoneError(ApiResult.Error(401, "invalid_code", "x", attemptsLeft = 3))
        assertEquals("Wrong code — 3 attempts left", wrong.message)
        assertTrue(wrong.clearCode)
        assertEquals("Wrong code — 1 attempt left", phoneError(ApiResult.Error(401, "invalid_code", "x", attemptsLeft = 1)).message)
        assertEquals("Wrong code", phoneError(ApiResult.Error(401, "invalid_code", "x")).message)
        val expired = phoneError(ApiResult.Error(410, "expired", ""))
        assertEquals(0, expired.resendInSec)
        assertTrue(expired.clearCode)
        val attempts = phoneError(ApiResult.Error(429, "too_many_attempts", ""))
        assertEquals("Too many attempts. Send a new code.", attempts.message)
        val limited = phoneError(ApiResult.Error(429, "rate_limited", "", retryAfterSec = 900))
        assertEquals("Too many codes requested. Try again in 15 min.", limited.message)
        assertEquals(900, limited.resendInSec)
        assertEquals(60, phoneError(ApiResult.Error(429, "rate_limited", "")).resendInSec) // no Retry-After
        assertTrue(phoneError(ApiResult.Error(409, "already_verified", "")).alreadyVerified)
        assertEquals("Couldn't send the SMS right now. Try again in a minute.", phoneError(ApiResult.Error(503, "sms_unavailable", "")).message)
        assertTrue(phoneError(ApiResult.NetworkError(IOException())).message!!.startsWith("Can't reach"))
        assertEquals("45 s", waitText(45))
        assertEquals("2 min", waitText(61))
    }

    @Test fun sendStartsTheResendTimerAndCodeAutoSubmits() = runTest(dispatcher) {
        val b = Backend(me)
        val vm = vm(b)
        assertEquals("+9477•••••22", vm.state.value.maskedPhone)
        vm.sendCode(); runCurrent()
        assertTrue(vm.state.value.sent)
        assertEquals(60, vm.state.value.resendInSec)
        vm.sendCode(); runCurrent() // ignored while the timer runs
        assertEquals(1, b.requests)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(30, vm.state.value.resendInSec)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(0, vm.state.value.resendInSec)

        vm.onCode("12 34 5"); runCurrent()
        assertTrue(b.codes.isEmpty())
        vm.onCode("123456"); runCurrent() // 6th digit submits
        assertEquals(listOf("123456"), b.codes)
        assertTrue(b.verifiedUser!!.phoneVerified)
    }

    @Test fun wrongCodeClearsAndShowsAttempts() = runTest(dispatcher) {
        val b = Backend(me).apply { confirmReply = ApiResult.Error(401, "invalid_code", "x", attemptsLeft = 2) }
        val vm = vm(b)
        vm.sendCode(); runCurrent()
        vm.onCode("000000"); runCurrent()
        assertEquals("", vm.state.value.code)
        assertEquals("Wrong code — 2 attempts left", vm.state.value.error)
        assertNull(b.verifiedUser)
        assertFalse(vm.state.value.busy)
    }

    @Test fun rateLimitUsesRetryAfterAnd409CountsAsSuccess() = runTest(dispatcher) {
        val b = Backend(me).apply { requestReply = ApiResult.Error(429, "rate_limited", "", retryAfterSec = 120) }
        val vm = vm(b)
        vm.sendCode(); runCurrent()
        assertEquals(120, vm.state.value.resendInSec)
        assertFalse(vm.state.value.sent)

        val b2 = Backend(me).apply { requestReply = ApiResult.Error(409, "already_verified", "") }
        val vm2 = vm(b2)
        vm2.sendCode(); runCurrent()
        assertTrue(b2.verifiedUser!!.phoneVerified) // re-fetched /me
    }

    @Test fun smsUnavailableAllowsImmediateRetryAndSignOutWorks() = runTest(dispatcher) {
        val b = Backend(me).apply { requestReply = ApiResult.Error(503, "sms_unavailable", "") }
        val vm = vm(b)
        vm.sendCode(); runCurrent()
        assertEquals(0, vm.state.value.resendInSec)
        assertTrue(vm.state.value.error!!.contains("SMS"))
        b.requestReply = ApiResult.Ok(PhoneVerifyRequestReply("sent", 300, "+9477•••••22"))
        vm.sendCode(); runCurrent()
        assertTrue(vm.state.value.sent)
        vm.signOut(lk.codegen.risime.data.testConfirmation()); runCurrent()
        assertTrue(b.signedOut)
    }
}
