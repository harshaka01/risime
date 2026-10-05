package lk.codegen.risime.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.db.LastMessage
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.Presence
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.realtime.ConnectionState

data class ChatRow(
    val userId: String?,
    val name: String,
    val company: String,
    val registered: Boolean,
    val last: LastMessage?,
    val presence: Presence? = null,
    val typing: Boolean = false,
)

class ChatsViewModel(private val c: AppContainer, private val meId: String) : ViewModel() {
    val refreshError = MutableStateFlow<String?>(null)
    val connection: StateFlow<ConnectionState> = c.realtime.state

    /** Registered contacts first (most recent chat on top), then unregistered greyed out. */
    val rows: StateFlow<List<ChatRow>> = combine(
        c.contacts.contacts,
        c.db.messages().lastMessages(),
        c.presence.presence,
        c.presence.typing,
    ) { contacts, lasts, presence, typing ->
        val byConv = lasts.associateBy { it.conversationId }
        contacts.map { ct ->
            val id = ct.userId?.lowercase()
            ChatRow(ct.userId, ct.displayName, ct.company, ct.registered && ct.userId != null,
                ct.userId?.let { byConv[dmConversationId(meId, it)] },
                presence = id?.let { presence[it] },
                typing = id != null && id in typing)
        }.sortedWith(
            compareByDescending<ChatRow> { it.registered }
                .thenByDescending { it.last?.localTs ?: 0L }
                .thenBy { it.name.lowercase() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val r = c.contacts.refresh()
            c.handleAuthError(r)
            refreshError.value = when (r) {
                is ApiResult.Ok -> null
                is ApiResult.Error -> "Couldn't load contacts (${r.code})"
                is ApiResult.NetworkError -> "Offline — showing saved contacts"
            }
        }
    }

    fun logout() {
        viewModelScope.launch { c.logout() }
    }
}
