package lk.codegen.risime.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.Session
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.User

/** What Settings needs from the app; [AppContainer] in the app, a fake in unit tests. */
interface SettingsBackend {
    val session: Flow<Session?>

    /** PATCH /me; on success the stored user is updated. */
    suspend fun updateDisplayName(name: String): ApiResult<User>

    /** Log out of the current server (its token is useless elsewhere) and switch to [url]. */
    suspend fun switchServer(url: String)

    suspend fun logout(confirmed: lk.codegen.risime.data.UserConfirmation)

    /** Debug-only sign-in override (AuthOverride name). */
    val authOverride: Flow<String?>

    suspend fun setAuthOverride(v: String)
}

class AppSettingsBackend(private val c: AppContainer) : SettingsBackend {
    override val session: Flow<Session?> = c.sessionStore.session

    override suspend fun updateDisplayName(name: String): ApiResult<User> {
        val r = c.api.updateDisplayName(name)
        c.handleAuthError(r)
        return when (r) {
            is ApiResult.Ok -> {
                c.sessionStore.updateUser(r.value.user)
                ApiResult.Ok(r.value.user)
            }
            is ApiResult.Error -> r
            is ApiResult.NetworkError -> r
        }
    }

    // App scope: the logout clears the session, which tears down this screen and its ViewModel.
    override suspend fun switchServer(url: String) {
        c.scope.launch { c.switchServer(url) }.join()
    }

    override suspend fun logout(confirmed: lk.codegen.risime.data.UserConfirmation) {
        c.scope.launch { c.logout(confirmed) }.join()
    }

    override val authOverride: Flow<String?> = c.sessionStore.authOverride

    override suspend fun setAuthOverride(v: String) = c.sessionStore.setAuthOverride(v)
}

data class SettingsUiState(
    val user: User? = null,
    /** The server the app currently talks to. */
    val serverUrl: String = "",
    val serverDraft: String = "",
    val serverError: String? = null,
    /** Non-null while the "log out and switch server?" dialog is showing. */
    val confirmServer: String? = null,
    val nameDraft: String = "",
    val nameError: String? = null,
    val nameSaved: Boolean = false,
    val confirmLogout: Boolean = false,
    /** Decision 050: the labelled wipe's confirmation is open. */
    val confirmDeleteChats: Boolean = false,
    val busy: Boolean = false,
    val authOverride: String = "AUTO",
)

class SettingsViewModel(private val backend: SettingsBackend) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            backend.authOverride.collect { v -> _state.update { it.copy(authOverride = v ?: "AUTO") } }
        }
        viewModelScope.launch {
            backend.session.collect { s ->
                if (s == null) return@collect
                _state.update { st ->
                    st.copy(
                        user = s.user,
                        serverUrl = s.serverUrl,
                        // Keep what the user is typing; fill the drafts the first time.
                        serverDraft = if (st.serverUrl.isEmpty()) s.serverUrl else st.serverDraft,
                        nameDraft = if (st.user == null) s.user.displayName else st.nameDraft,
                    )
                }
            }
        }
    }

    fun setAuthOverride(v: String) {
        _state.update { it.copy(authOverride = v) }
        viewModelScope.launch { backend.setAuthOverride(v) }
    }

    fun onServerDraft(v: String) = _state.update { it.copy(serverDraft = v, serverError = null) }

    fun onNameDraft(v: String) = _state.update { it.copy(nameDraft = v, nameError = null, nameSaved = false) }

    /** Validates the URL; a different server needs a confirmed logout first. */
    fun saveServer() {
        val s = _state.value
        when (val r = checkServerUrl(s.serverDraft)) {
            is Checked.Invalid -> _state.update { it.copy(serverError = r.message) }
            is Checked.Valid ->
                if (sameServer(r.value, s.serverUrl)) {
                    _state.update { it.copy(serverDraft = s.serverUrl, serverError = null) }
                } else {
                    _state.update { it.copy(confirmServer = r.value, serverError = null) }
                }
        }
    }

    fun cancelServerChange() = _state.update { it.copy(confirmServer = null) }

    fun confirmServerChange() {
        val url = _state.value.confirmServer ?: return
        _state.update { it.copy(confirmServer = null, busy = true) }
        viewModelScope.launch { backend.switchServer(url) }
    }

    fun saveName() {
        val s = _state.value
        val name = when (val r = checkDisplayName(s.nameDraft)) {
            is Checked.Invalid -> return _state.update { it.copy(nameError = r.message) }
            is Checked.Valid -> r.value
        }
        if (name == s.user?.displayName) return _state.update { it.copy(nameDraft = name, nameError = null) }
        _state.update { it.copy(busy = true, nameError = null, nameSaved = false) }
        viewModelScope.launch {
            when (val r = backend.updateDisplayName(name)) {
                is ApiResult.Ok -> _state.update { it.copy(busy = false, user = r.value, nameDraft = r.value.displayName, nameSaved = true) }
                is ApiResult.Error -> _state.update {
                    it.copy(busy = false, nameError = if (r.code == "invalid_display_name") "Display name must be 1–64 characters" else "Couldn't save (${r.code})")
                }
                is ApiResult.NetworkError -> _state.update { it.copy(busy = false, nameError = "Can't reach the server") }
            }
        }
    }

    fun askLogout() = _state.update { it.copy(confirmLogout = true, confirmDeleteChats = false) }

    /** Decision 050: "Log out and delete chats from this phone". */
    fun askLogoutAndDelete() = _state.update { it.copy(confirmLogout = false, confirmDeleteChats = true) }

    fun cancelLogout() = _state.update { it.copy(confirmLogout = false, confirmDeleteChats = false) }

    fun logout(confirmed: lk.codegen.risime.data.UserConfirmation) {
        _state.update { it.copy(confirmLogout = false, confirmDeleteChats = false, busy = true) }
        viewModelScope.launch { backend.logout(confirmed) }
    }
}
