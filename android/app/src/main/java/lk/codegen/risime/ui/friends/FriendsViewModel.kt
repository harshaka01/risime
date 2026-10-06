package lk.codegen.risime.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.FriendsState
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.Invite
import lk.codegen.risime.net.InviteCreate
import lk.codegen.risime.ui.phone.waitText

/** User-facing text for request/invite errors (§9.1/§9.2). Null = success. */
fun friendsError(r: ApiResult<*>): String? = when (r) {
    is ApiResult.Ok -> null
    is ApiResult.NetworkError -> "Can't reach the server. Try again."
    is ApiResult.Error -> when {
        r.code == AuthErrors.INVALID_PHONE -> "That phone number isn't valid"
        r.code == AuthErrors.INVALID_EMAIL -> "That email address isn't valid"
        r.code == AuthErrors.INVALID_NAME -> "Enter a name (1–64 characters)"
        r.httpStatus == 429 -> "Too many requests. Try again in ${waitText((r.retryAfterSec ?: 60L).toInt())}."
        r.httpStatus == 404 -> "That request is no longer there"
        else -> "Something went wrong (${r.code})"
    }
}

/** The friend-request reply is always the same (§9.2): never reveals whether the number is on RisiMe. */
fun requestSentText(e164: String) = "Request sent to $e164. They'll see it when they're on RisiMe."

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** Local checks before POST /invites; the server validates again. */
fun inviteFormError(phoneE164: String?, email: String, name: String): String? = when {
    phoneE164 == null -> "Enter a valid phone number"
    !EMAIL.matches(email.trim()) -> "Enter the email they'll sign in with"
    name.trim().isEmpty() || name.trim().length > 64 -> "Enter their name (1–64 characters)"
    else -> null
}

data class AddFriendState(
    val phone: String = "+94",
    val busy: Boolean = false,
    val error: String? = null,
    /** After a request: the normalised number it went to (offer the invite next). */
    val sentTo: String? = null,
    val inviting: Boolean = false,
    val inviteName: String = "",
    val inviteEmail: String = "",
)

class FriendsViewModel(private val c: AppContainer) : ViewModel() {
    val friendsState: StateFlow<FriendsState> = c.contacts.friendsState

    private val _add = MutableStateFlow(AddFriendState())
    val add = _add.asStateFlow()

    private val _invites = MutableStateFlow<List<Invite>>(emptyList())
    val invites = _invites.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /** A created invite to hand to the share sheet. */
    private val _share = MutableSharedFlow<Invite>(extraBufferCapacity = 1)
    val share = _share.asSharedFlow()

    fun onPhone(v: String) = _add.update { it.copy(phone = v, error = null) }
    fun onInviteName(v: String) = _add.update { it.copy(inviteName = v, error = null) }
    fun onInviteEmail(v: String) = _add.update { it.copy(inviteEmail = v, error = null) }
    fun startInvite() = _add.update { it.copy(inviting = true, error = null) }
    fun resetAdd() { _add.value = AddFriendState() }
    fun clearMessage() { _message.value = null }

    fun sendRequest() {
        val phone = c.phone.normalize(_add.value.phone) ?: return _add.update { it.copy(error = "Enter a valid phone number") }
        _add.update { it.copy(busy = true, error = null, phone = phone) }
        viewModelScope.launch {
            val r = c.api.requestFriend(phone)
            c.handleAuthError(r)
            val err = friendsError(r)
            _add.update { it.copy(busy = false, error = err, sentTo = if (err == null) phone else it.sentTo) }
            if (err == null) c.requestFriendsRefresh()
        }
    }

    fun sendInvite() {
        val s = _add.value
        val phone = c.phone.normalize(s.sentTo ?: s.phone)
        inviteFormError(phone, s.inviteEmail, s.inviteName)?.let { e -> return _add.update { it.copy(error = e) } }
        _add.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val r = c.api.createInvite(InviteCreate(phone!!, s.inviteEmail.trim(), s.inviteName.trim()))
            c.handleAuthError(r)
            when (r) {
                is ApiResult.Ok -> {
                    _add.update { it.copy(busy = false) }
                    _share.tryEmit(r.value.invite)
                    loadInvites()
                }
                else -> _add.update { it.copy(busy = false, error = friendsError(r)) }
            }
        }
    }

    fun loadInvites() {
        viewModelScope.launch {
            when (val r = c.api.invites()) {
                is ApiResult.Ok -> _invites.value = r.value.invites
                else -> { c.handleAuthError(r); _message.value = friendsError(r) }
            }
        }
    }

    fun revoke(inviteId: String) = act({ c.api.revokeInvite(inviteId) }) { loadInvites() }

    fun accept(requestId: String) = act({ c.api.acceptRequest(requestId) })

    fun decline(requestId: String) = act({ c.api.declineRequest(requestId) })

    fun cancel(requestId: String) = act({ c.api.cancelRequest(requestId) })

    fun block(userId: String) = act({ c.api.block(userId) })

    fun unblock(userId: String) = act({ c.api.unblock(userId) })

    fun unfriend(userId: String) = act({ c.api.unfriend(userId) })

    private fun act(call: suspend () -> ApiResult<*>, then: () -> Unit = {}) {
        viewModelScope.launch {
            val r = call()
            c.handleAuthError(r)
            _message.value = friendsError(r)
            c.contacts.refresh()
            then()
        }
    }
}
