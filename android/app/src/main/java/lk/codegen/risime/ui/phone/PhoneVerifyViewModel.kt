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
    /** The current code can't be used any more (expired, attempts used up): Confirm stays off until a new code. */
    val lockCode: Boolean = false,
    /** `attempts_left` from a 401 invalid_code. */
    val attemptsLeft: Int? = null,
)

const val RESEND_FALLBACK_SEC = 60

fun phoneError(r: ApiResult<*>): PhoneErrorUi = when (r) {
    is ApiResult.Ok -> PhoneErrorUi(null)
    is ApiResult.NetworkError -> PhoneErrorUi("Can't reach the server. Check your connection and try again.")
    is ApiResult.Error -> when {
        r.code == AuthErrors.ALREADY_VERIFIED -> PhoneErrorUi(null, alreadyVerified = true)
        r.httpStatus == 401 && r.code == AuthErrors.INVALID_CODE -> PhoneErrorUi(
            CodeLimits.wrongCode(r.attemptsLeft),
            clearCode = true,
            lockCode = r.attemptsLeft == 0,
            attemptsLeft = r.attemptsLeft,
        )
        r.httpStatus == 410 -> PhoneErrorUi("That code has expired. Send a new one.", clearCode = true, resendInSec = 0, lockCode = true)
        r.httpStatus == 429 && r.code == AuthErrors.TOO_MANY_ATTEMPTS ->
            PhoneErrorUi("Too many attempts. Send a new code.", clearCode = true, resendInSec = CodeLimits.retryAfterSec(r).toInt(), lockCode = true)
        r.httpStatus == 429 -> {
            val sec = CodeLimits.retryAfterSec(r).toInt()
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

    suspend fun signOut(confirmed: lk.codegen.risime.data.UserConfirmation)
}

class AppPhoneBackend(private val c: AppContainer) : PhoneVerifyBackend {
    override suspend fun request() = c.api.requestPhoneCode().also { c.handleAuthError401(it) }
    override suspend fun confirm(code: String) = c.api.confirmPhoneCode(code).also { c.handleAuthError401(it) }
    override suspend fun me() = c.api.me()
    override suspend fun verified(user: User) = c.onPhoneVerified(user)
    override suspend fun signOut(confirmed: lk.codegen.risime.data.UserConfirmation) {
        c.scope.launch { c.logout(confirmed) }.join()
    }
}

data class PhoneVerifyState(
    val maskedPhone: String,
    val sent: Boolean = false,
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** Seconds left on [resendUntilMs] (ticked once a second while it runs). */
    val resendInSec: Int = 0,
    /** Wall-clock deadline for Send/Resend: the server's cooldown, kept across rotation and process death. */
    val resendUntilMs: Long = 0,
    /** The code on screen can't be confirmed any more: only a new code helps. */
    val codeLocked: Boolean = false,
    val attemptsLeft: Int? = null,
) {
    val canConfirm: Boolean get() = sent && !busy && !codeLocked && code.length == 6
    val canSend: Boolean get() = !busy && resendInSec == 0
}

class PhoneVerifyViewModel(
    private val backend: PhoneVerifyBackend,
    phone: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(PhoneVerifyState(maskPhone(phone)))
    val state = _state.asStateFlow()
    private var timer: Job? = null

    /** Saved state after rotation / process death (the screen keeps it in rememberSaveable). */
    fun restore(resendUntilMs: Long, sent: Boolean, codeLocked: Boolean) {
        _state.update {
            it.copy(
                resendUntilMs = maxOf(it.resendUntilMs, resendUntilMs),
                sent = it.sent || sent,
                codeLocked = it.codeLocked || codeLocked,
            )
        }
        tick()
    }

    fun sendCode() {
        val s = _state.value
        if (!s.canSend || CodeLimits.secondsLeft(s.resendUntilMs, clock()) > 0) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = backend.request()) {
                is ApiResult.Ok -> {
                    _state.update {
                        it.copy(busy = false, sent = true, code = "", codeLocked = false, attemptsLeft = null, maskedPhone = r.value.to.ifBlank { it.maskedPhone })
                    }
                    startTimer(RESEND_FALLBACK_SEC)
                }
                else -> applyError(phoneError(r))
            }
        }
    }

    fun onCode(v: String) {
        if (_state.value.busy || _state.value.codeLocked) return
        val digits = v.filter(Char::isDigit).take(6)
        _state.update { it.copy(code = digits, error = null) }
        if (digits.length == 6) confirm()
    }

    /** One request at a time: a second tap (or the auto-submit plus a tap) while busy is ignored. */
    fun confirm() {
        val s = _state.value
        if (!s.canConfirm) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = backend.confirm(s.code)) {
                is ApiResult.Ok -> done(r.value.user)
                else -> applyError(phoneError(r))
            }
        }
    }

    fun signOut(confirmed: lk.codegen.risime.data.UserConfirmation) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch { backend.signOut(confirmed) }
    }

    private suspend fun applyError(e: PhoneErrorUi) {
        if (e.alreadyVerified) {
            when (val me = backend.me()) {
                is ApiResult.Ok -> return done(me.value.user)
                else -> return _state.update { it.copy(busy = false, error = phoneError(me).message) }
            }
        }
        _state.update {
            it.copy(
                busy = false, error = e.message, code = if (e.clearCode) "" else it.code,
                codeLocked = it.codeLocked || e.lockCode, attemptsLeft = e.attemptsLeft ?: it.attemptsLeft,
            )
        }
        e.resendInSec?.let { startTimer(it) }
    }

    private suspend fun done(user: User) {
        backend.verified(user)
        _state.update { it.copy(busy = false, error = null) }
    }

    /** Moves the resend deadline [sec] from now (never earlier) and ticks the countdown. */
    private fun startTimer(sec: Int) {
        val now = clock()
        _state.update { it.copy(resendUntilMs = CodeLimits.later(it.resendUntilMs, now, sec.toLong())) }
        tick()
    }

    private fun tick() {
        timer?.cancel()
        val update = { _state.update { it.copy(resendInSec = CodeLimits.secondsLeft(it.resendUntilMs, clock())) } }
        update()
        if (_state.value.resendInSec == 0) return
        timer = viewModelScope.launch {
            while (_state.value.resendInSec > 0) {
                delay(1_000)
                update()
            }
        }
    }
}
