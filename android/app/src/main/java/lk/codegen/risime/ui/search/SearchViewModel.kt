package lk.codegen.risime.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import lk.codegen.risime.net.dmConversationId
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.MessageEntity

data class SearchResults(
    val query: String = "",
    val contacts: List<ContactEntity> = emptyList(),
    val messages: List<MessageHit> = emptyList(),
)

/**
 * A message found by search. [tab] (§24.9, tabs on only): the tab the message is in, shown as a tab
 * icon; [openTarget] = what a tap opens (the DM peer as before; an Official message opens its chat
 * on the Official tab).
 */
data class MessageHit(
    val message: MessageEntity,
    val peerId: String,
    val peerName: String,
    val tab: lk.codegen.risime.data.tabs.Tab? = null,
    val openTarget: String = peerId,
)

/**
 * Contacts whose name or company match, and messages whose body matches (newest first). With tabs on
 * ([tabsOn]), every hit carries its tab ([tabOf]: the MLS-derived tab row of a conversation); an
 * Official message is named by its chat's peer (a 1:1) and opens that chat's Official tab.
 */
fun searchResults(
    query: String,
    contacts: List<ContactEntity>,
    messages: List<MessageEntity>,
    me: String = "",
    tabsOn: Boolean = false,
    tabOf: (String) -> lk.codegen.risime.data.db.ChatTabEntity? = { null },
): SearchResults {
    val q = query.trim()
    if (q.isEmpty()) return SearchResults(q)
    val byId = contacts.filter { it.userId != null }.associateBy { it.userId!!.lowercase() }
    val people = contacts.filter {
        it.registered && it.userId != null &&
            (it.displayName.contains(q, ignoreCase = true) || it.company.contains(q, ignoreCase = true))
    }
    val hits = messages.mapNotNull { m ->
        val row = tabOf(m.conversationId.lowercase())
        val official = row?.official == true
        val peer = (if (official) lk.codegen.risime.net.dmPeer(row!!.chatId, me) ?: (if (m.outgoing) null else m.from) else if (m.outgoing) m.to else m.from)
            ?.lowercase() ?: return@mapNotNull null
        val c = byId[peer] ?: return@mapNotNull null
        val tab = when {
            !tabsOn -> null
            official -> lk.codegen.risime.data.tabs.Tab.OFFICIAL
            else -> lk.codegen.risime.data.tabs.Tab.PRIVATE
        }
        MessageHit(m, c.userId!!, c.displayName, tab, if (official) m.conversationId else c.userId)
    }
    return SearchResults(q, people, hits)
}

/**
 * Locked chats never show up in search (WhatsApp): their people and messages are dropped. Unknown
 * locked list: nothing. [chatOf]: a conversation's chat (§24: an Official message of a locked chat
 * is hidden too).
 */
fun withoutLocked(r: SearchResults, locked: Set<String>?, meId: String, chatOf: (String) -> String = { it }): SearchResults {
    if (locked == null) return SearchResults(r.query)
    if (locked.isEmpty()) return r
    fun hidden(peer: String) = dmConversationId(meId, peer) in locked
    return r.copy(
        contacts = r.contacts.filterNot { it.userId != null && hidden(it.userId) },
        messages = r.messages.filterNot { hidden(it.peerId) || chatOf(it.message.conversationId).lowercase() in locked },
    )
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val c: AppContainer, private val meId: String = "") : ViewModel() {
    val query = MutableStateFlow("")

    val results: StateFlow<SearchResults> = combine(
        query.debounce(150).flatMapLatest { q ->
            if (q.isBlank()) {
                flowOf(SearchResults(q))
            } else {
                combine(c.contacts.contacts, c.db.messages().search(likePattern(q), LIMIT), c.chatTabs.rows, c.chatTabs.uiOn) { contacts, msgs, rows, tabsOn ->
                    searchResults(q, contacts, msgs, meId, tabsOn) { conv -> rows?.get(conv) }
                }
            }
        },
        c.lockedChats.ids,
    ) { r, locked -> withoutLocked(r, locked, meId) { conv -> c.chatTabs.chatId(conv) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    /**
     * The exact secret code typed in the search box: the Locked chats entry appears (the fingerprint
     * or screen lock is still asked on tap). Compared by hash, never stored or logged.
     */
    val codeEntry: StateFlow<Boolean> = combine(query, c.lockedChats.hasCode) { q, has -> q to has }
        .debounce(150)
        .mapLatest { (q, has) ->
            has && q.length >= lk.codegen.risime.data.lock.SecretCode.MIN_LENGTH &&
                withContext(Dispatchers.Default) { c.lockedChats.codeMatches(q) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val lockedCount: StateFlow<Int> = c.lockedChats.ids.map { it?.size ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun openLockedFolder() = c.lockedChats.openFolder()

    fun onQuery(q: String) {
        query.value = q
    }

    private companion object {
        const val LIMIT = 100
    }
}
