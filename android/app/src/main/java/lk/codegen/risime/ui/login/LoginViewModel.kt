package lk.codegen.risime.ui.login

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.net.ApiResult
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
    val busy: Boolean = false,
    val error: String? = null,
    val serverError: String? = null,
)

class LoginViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state = _state.asStateFlow()

    init {
        // Follows the stored URL (also when Settings switched servers while this ViewModel lived on).
        viewModelScope.launch {
            c.sessionStore.serverUrl.distinctUntilChanged().collect { url ->
                _state.update { it.copy(serverUrl = url, serverError = null) }
            }
        }
        // This ViewModel outlives a login; start clean when the user comes back after logout.
        viewModelScope.launch {
            c.sessionStore.session.collect { s ->
                if (s != null) _state.value = LoginUiState(serverUrl = s.serverUrl, phone = s.user.phone)
            }
        }
    }

    fun onServerUrl(v: String) = _state.update { it.copy(serverUrl = v, error = null, serverError = null) }
    fun onPhone(v: String) = _state.update { it.copy(phone = v, error = null) }
    fun onEmail(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onCode(v: String) = _state.update { it.copy(code = v.filter(Char::isDigit).take(6), error = null) }
    fun back() = _state.update { it.copy(codeSentTo = null, code = "", error = null) }

    fun requestCode() {
        val s = _state.value
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
                    _state.update { it.copy(busy = false, codeSentTo = phone, code = "") }
                    startResendTimer()
                }
                is ApiResult.Error -> _state.update { it.copy(busy = false, error = message(r)) }
                is ApiResult.NetworkError -> _state.update { it.copy(busy = false, error = "Can't reach the server at $server") }
            }
        }
    }

    fun verify() {
        val s = _state.value
        val phone = s.codeSentTo ?: return
        if (s.code.length != 6) return _state.update { it.copy(error = "Enter the 6-digit code") }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = c.api.verify(phone, s.code, Build.MODEL ?: "Android")) {
                is ApiResult.Ok -> c.onLoggedIn(r.value.token, r.value.user) // session flow switches to the chats
                is ApiResult.Error -> _state.update { it.copy(busy = false, error = message(r)) }
                is ApiResult.NetworkError -> _state.update { it.copy(busy = false, error = "Can't reach the server") }
            }
        }
    }

    private fun startResendTimer() {
        viewModelScope.launch {
            for (left in RESEND_SECONDS downTo 0) {
                _state.update { it.copy(resendInSec = left) }
                if (left > 0) kotlinx.coroutines.delay(1_000)
            }
        }
    }

    private fun message(e: ApiResult.Error) = when (e.code) {
        "invalid_phone" -> "That phone number isn't valid"
        "invalid_email" -> "That email address isn't valid"
        "rate_limited" -> "Too many requests. Try again in a few minutes"
        "invalid_code" -> "Wrong code"
        "expired" -> "That code has expired. Request a new one"
        "too_many_attempts" -> "Too many attempts. Request a new code"
        else -> "Something went wrong (${e.code})"
    }

    private companion object {
        const val RESEND_SECONDS = 60
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
