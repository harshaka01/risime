package lk.codegen.risime.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.MeReply
import lk.codegen.risime.net.PhoneVerifyRequestReply
import lk.codegen.risime.net.User

/** "+94771234522" → "+9477•••••22" (same style as the server's `to`). */
fun maskPhone(e164: String): String {
    if (e164.length <= 7) return e164
    return e164.take(5) + "•".repeat(e164.length - 7) + e164.takeLast(2)
}

/** How one error from request/confirm shows up (contract §7.1, the Android review's table). */
data class PhoneErrorUi(
    val message: String?,
    val clearCode: Boolean = false,
    /** Seconds until "Send code" is enabled again (null = leave the timer as is). */
    val resendInSec: Int? = null,
    /** 409 already_verified: treat as success, re-fetch /me. */
    val alreadyVerified: Boolean = false,
)

const val RESEND_FALLBACK_SEC = 60

fun phoneError(r: ApiResult<*>): PhoneErrorUi = when (r) {
    is ApiResult.Ok -> PhoneErrorUi(null)
    is ApiResult.NetworkError -> PhoneErrorUi("Can't reach the server. Check your connection and try again.")
    is ApiResult.Error -> when {
        r.code == AuthErrors.ALREADY_VERIFIED -> PhoneErrorUi(null, alreadyVerified = true)
        r.httpStatus == 401 && r.code == AuthErrors.INVALID_CODE -> PhoneErrorUi(
            "Wrong code" + (r.attemptsLeft?.let { n -> " — $n attempt${if (n == 1) "" else "s"} left" } ?: ""),
            clearCode = true,
        )
        r.httpStatus == 410 -> PhoneErrorUi("That code has expired. Send a new one.", clearCode = true, resendInSec = 0)
        r.httpStatus == 429 && r.code == AuthErrors.TOO_MANY_ATTEMPTS ->
            PhoneErrorUi("Too many attempts. Send a new code.", clearCode = true, resendInSec = (r.retryAfterSec ?: 0).toInt())
        r.httpStatus == 429 -> {
            val sec = (r.retryAfterSec ?: RESEND_FALLBACK_SEC.toLong()).toInt()
            PhoneErrorUi("Too many codes requested. Try again in ${waitText(sec)}.", resendInSec = sec)
        }
        r.httpStatus == 503 -> PhoneErrorUi("Couldn't send the SMS right now. Try again in a minute.", resendInSec = 0)
        else -> PhoneErrorUi("Something went wrong (${r.code})")
    }
}

fun waitText(sec: Int): String = when {
    sec < 60 -> "$sec s"
    else -> "${(sec + 59) / 60} min"
}

/** What the screen needs from the app (AppContainer in the app, a fake in tests). */
interface PhoneVerifyBackend {
    suspend fun request(): ApiResult<PhoneVerifyRequestReply>

    suspend fun confirm(code: String): ApiResult<MeReply>

    suspend fun me(): ApiResult<MeReply>

    /** Confirmed: store the verified user; the gate moves on to Chats. */
    suspend fun verified(user: User)

    suspend fun signOut()
}

class AppPhoneBackend(private val c: AppContainer) : PhoneVerifyBackend {
    override suspend fun request() = c.api.requestPhoneCode().also { c.handleAuthError401(it) }
    override suspend fun confirm(code: String) = c.api.confirmPhoneCode(code).also { c.handleAuthError401(it) }
    override suspend fun me() = c.api.me()
    override suspend fun verified(user: User) = c.onPhoneVerified(user)
    override suspend fun signOut() {
        c.scope.launch { c.logout() }.join()
    }
}

data class PhoneVerifyState(
    val maskedPhone: String,
    val sent: Boolean = false,
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val resendInSec: Int = 0,
)

class PhoneVerifyViewModel(private val backend: PhoneVerifyBackend, phone: String) : ViewModel() {
    private val _state = MutableStateFlow(PhoneVerifyState(maskPhone(phone)))
    val state = _state.asStateFlow()
    private var timer: Job? = null

    fun sendCode() {
        val s = _state.value
        if (s.busy || s.resendInSec > 0) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = backend.request()) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(busy = false, sent = true, code = "", maskedPhone = r.value.to.ifBlank { it.maskedPhone }) }
                    startTimer(RESEND_FALLBACK_SEC)
                }
                else -> applyError(phoneError(r))
            }
        }
    }

    fun onCode(v: String) {
        val digits = v.filter(Char::isDigit).take(6)
        _state.update { it.copy(code = digits, error = null) }
        if (digits.length == 6) confirm()
    }

    fun confirm() {
        val s = _state.value
        if (s.busy || s.code.length != 6) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = backend.confirm(s.code)) {
                is ApiResult.Ok -> done(r.value.user)
                else -> applyError(phoneError(r))
            }
        }
    }

    fun signOut() {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch { backend.signOut() }
    }

    private suspend fun applyError(e: PhoneErrorUi) {
        if (e.alreadyVerified) {
            when (val me = backend.me()) {
                is ApiResult.Ok -> return done(me.value.user)
                else -> return _state.update { it.copy(busy = false, error = phoneError(me).message) }
            }
        }
        _state.update { it.copy(busy = false, error = e.message, code = if (e.clearCode) "" else it.code) }
        e.resendInSec?.let { startTimer(it) }
    }

    private suspend fun done(user: User) {
        backend.verified(user)
        _state.update { it.copy(busy = false, error = null) }
    }

    private fun startTimer(sec: Int) {
        timer?.cancel()
        _state.update { it.copy(resendInSec = sec.coerceAtLeast(0)) }
        if (sec <= 0) return
        timer = viewModelScope.launch {
            for (left in sec - 1 downTo 0) {
                delay(1_000)
                _state.update { it.copy(resendInSec = left) }
            }
        }
    }
}
