package lk.codegen.risime.ui.login

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.BuildConfig
import lk.codegen.risime.data.auth.AuthOverride
import lk.codegen.risime.data.auth.SignInChoice
import lk.codegen.risime.data.auth.signInChoice
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.ui.phone.CodeLimits
import lk.codegen.risime.ui.settings.Checked
import lk.codegen.risime.ui.settings.checkServerUrl

data class LoginUiState(
    val serverUrl: String = "",
    val phone: String = "+94",
    val email: String = "",
    val code: String = "",
    /** Normalised E.164 phone once a code was requested; non-null means the OTP step is showing. */
    val codeSentTo: String? = null,
    val resendInSec: Int = 0,
    /** Wall-clock deadline for Send/Resend (server cooldown; survives rotation and process death via LoginFlow). */
    val resendUntilMs: Long = 0,
    /** The code on screen can't be used any more (expired, too many attempts): only a new code helps. */
    val codeLocked: Boolean = false,
    val attemptsLeft: Int? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val serverError: String? = null,
    /** From GET /auth/config; null while loading. */
    val choice: SignInChoice? = null,
    /** Debug with both modes: the dev OTP form is behind "Developer sign-in (OTP)". */
    val devFormOpen: Boolean = false,
) {
    val canVerify: Boolean get() = !busy && !codeLocked && code.length == 6
    val canSend: Boolean get() = !busy && resendInSec == 0
}

class LoginViewModel(private val c: AppContainer, private val clock: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state = _state.asStateFlow()
    private var timer: kotlinx.coroutines.Job? = null

    /** Saved cooldown after rotation / process death (LoginFlow keeps it in rememberSaveable). */
    fun restore(resendUntilMs: Long) {
        _state.update { it.copy(resendUntilMs = maxOf(it.resendUntilMs, resendUntilMs)) }
        tick()
    }

    init {
        // Follows the stored URL (also when Settings switched servers while this ViewModel lived on),
        // and asks that server which sign-in modes it offers (§6.1).
        viewModelScope.launch {
            combine(c.sessionStore.serverUrl.distinctUntilChanged(), c.sessionStore.authOverride) { url, o -> url to o }
                .collectLatest { (url, o) ->
                    _state.update { it.copy(serverUrl = url, serverError = null, choice = null) }
                    loadChoice(o)
                }
        }
        // This ViewModel outlives a login; start clean when the user comes back after logout.
        viewModelScope.launch {
            c.sessionStore.session.collect { s ->
                if (s != null) _state.value = LoginUiState(serverUrl = s.serverUrl, phone = s.user.phone, resendUntilMs = _state.value.resendUntilMs)
            }
        }
    }

    private suspend fun loadChoice(override: String?) {
        val o = runCatching { AuthOverride.valueOf(override ?: "AUTO") }.getOrDefault(AuthOverride.AUTO)
        val choice = signInChoice(c.api.authConfig(), BuildConfig.DEBUG, o)
        _state.update { it.copy(choice = choice) }
    }

    fun retryChoice() {
        _state.update { it.copy(choice = null) }
        viewModelScope.launch { loadChoice(c.sessionStore.authOverride.first()) }
    }

    /** Validate and store an edited server URL, then re-check its sign-in modes. */
    fun applyServer() {
        when (val r = checkServerUrl(_state.value.serverUrl)) {
            is Checked.Invalid -> _state.update { it.copy(serverError = r.message) }
            is Checked.Valid -> viewModelScope.launch {
                c.sessionStore.setServerUrl(r.value)
                retryChoice()
            }
        }
    }

    fun toggleDevForm() = _state.update { it.copy(devFormOpen = !it.devFormOpen) }

    fun onServerUrl(v: String) = _state.update { it.copy(serverUrl = v, error = null, serverError = null) }
    fun onPhone(v: String) = _state.update { it.copy(phone = v, error = null) }
    fun onEmail(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onCode(v: String) {
        if (_state.value.codeLocked) return
        _state.update { it.copy(code = v.filter(Char::isDigit).take(6), error = null) }
    }
    fun back() = _state.update { it.copy(codeSentTo = null, code = "", error = null) }

    fun requestCode() {
        val s = _state.value
        if (!s.canSend || CodeLimits.secondsLeft(s.resendUntilMs, clock()) > 0) return
        val phone = c.phone.normalize(s.phone)
        val email = s.email.trim()
        val server = when (val r = checkServerUrl(s.serverUrl)) {
            is Checked.Invalid -> return _state.update { it.copy(serverError = r.message) }
            is Checked.Valid -> r.value
        }
        when {
            phone == null -> return _state.update { it.copy(error = "Enter a valid phone number") }
            !EMAIL.matches(email) -> return _state.update { it.copy(error = "Enter a valid email address") }
        }
        _state.update { it.copy(busy = true, error = null, serverError = null, phone = phone, serverUrl = server) }
        viewModelScope.launch {
            c.sessionStore.setServerUrl(server)
            when (val r = c.api.requestCode(phone, email)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(busy = false, codeSentTo = phone, code = "", codeLocked = false, attemptsLeft = null) }
                    startResendTimer(RESEND_SECONDS.toLong())
                }
                is ApiResult.Error -> {
                    _state.update { it.copy(busy = false, error = message(r)) }
                    if (r.httpStatus == 429) startResendTimer(CodeLimits.retryAfterSec(r))
                }
                is ApiResult.NetworkError -> _state.update { it.copy(busy = false, error = "Can't reach the server at $server") }
            }
        }
    }

    /** One request at a time: a second tap while busy (or with a locked code) is ignored. */
    fun verify() {
        val s = _state.value
        val phone = s.codeSentTo ?: return
        if (s.busy || s.codeLocked) return
        if (s.code.length != 6) return _state.update { it.copy(error = "Enter the 6-digit code") }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = c.api.verify(phone, s.code, Build.MODEL ?: "Android")) {
                is ApiResult.Ok -> c.onLoggedIn(r.value.token, r.value.user) // session flow switches to the chats
                is ApiResult.Error -> {
                    val locked = r.httpStatus == 410 || r.code == "too_many_attempts" || (r.code == "invalid_code" && r.attemptsLeft == 0)
                    _state.update {
                        it.copy(
                            busy = false, error = message(r), code = "",
                            codeLocked = it.codeLocked || locked, attemptsLeft = r.attemptsLeft ?: it.attemptsLeft,
                        )
                    }
                    if (r.httpStatus == 429) startResendTimer(CodeLimits.retryAfterSec(r))
                }
                is ApiResult.NetworkError -> _state.update { it.copy(busy = false, error = "Can't reach the server") }
            }
        }
    }

    /** Moves the resend deadline [sec] from now (never earlier: the server's wait wins). */
    private fun startResendTimer(sec: Long) {
        val now = clock()
        _state.update { it.copy(resendUntilMs = CodeLimits.later(it.resendUntilMs, now, sec)) }
        tick()
    }

    private fun tick() {
        timer?.cancel()
        val update = { _state.update { it.copy(resendInSec = CodeLimits.secondsLeft(it.resendUntilMs, clock())) } }
        update()
        if (_state.value.resendInSec == 0) return
        timer = viewModelScope.launch {
            while (_state.value.resendInSec > 0) {
                kotlinx.coroutines.delay(1_000)
                update()
            }
        }
    }

    private fun message(e: ApiResult.Error) = when (e.code) {
        "invalid_phone" -> "That phone number isn't valid"
        "invalid_email" -> "That email address isn't valid"
        "rate_limited" -> "Too many requests. Wait for the countdown, then try again"
        "invalid_code" -> CodeLimits.wrongCode(e.attemptsLeft)
        "expired" -> "That code has expired. Request a new one"
        "too_many_attempts" -> "Too many attempts. Request a new code"
        else -> "Something went wrong (${e.code})"
    }

    private companion object {
        const val RESEND_SECONDS = 60
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
