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
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.LastMessage
import lk.codegen.risime.data.db.UnreadCount
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
    val unread: Int = 0,
)

/**
 * Registered contacts first (most recent chat on top), then unregistered greyed out.
 * Unread = incoming messages not yet read in that DM; shown bold with a badge.
 */
fun buildChatRows(
    meId: String,
    contacts: List<ContactEntity>,
    lasts: List<LastMessage>,
    unread: List<UnreadCount>,
    presence: Map<String, Presence> = emptyMap(),
    typing: Set<String> = emptySet(),
): List<ChatRow> {
    val byConv = lasts.associateBy { it.conversationId }
    val unreadByConv = unread.associate { it.conversationId to it.unread }
    return contacts.map { ct ->
        val id = ct.userId?.lowercase()
        val conv = ct.userId?.let { dmConversationId(meId, it) }
        ChatRow(
            ct.userId, ct.displayName, ct.company, ct.registered && ct.userId != null,
            conv?.let { byConv[it] },
            presence = id?.let { presence[it] },
            typing = id != null && id in typing,
            unread = conv?.let { unreadByConv[it] } ?: 0,
        )
    }.sortedWith(
        compareByDescending<ChatRow> { it.registered }
            .thenByDescending { it.last?.localTs ?: 0L }
            .thenBy { it.name.lowercase() },
    )
}

class ChatsViewModel(private val c: AppContainer, private val meId: String) : ViewModel() {
    val refreshError = MutableStateFlow<String?>(null)
    val connection: StateFlow<ConnectionState> = c.realtime.state

    val rows: StateFlow<List<ChatRow>> = combine(
        c.contacts.contacts,
        c.db.messages().lastMessages(),
        c.db.messages().unreadCounts(),
        c.presence.presence,
        c.presence.typing,
    ) { contacts, lasts, unread, presence, typing ->
        buildChatRows(meId, contacts, lasts, unread, presence, typing)
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
