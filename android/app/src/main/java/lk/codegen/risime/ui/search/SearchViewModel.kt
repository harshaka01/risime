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

data class MessageHit(val message: MessageEntity, val peerId: String, val peerName: String)

/** Contacts whose name or company match, and messages whose body matches (newest first). */
fun searchResults(query: String, contacts: List<ContactEntity>, messages: List<MessageEntity>): SearchResults {
    val q = query.trim()
    if (q.isEmpty()) return SearchResults(q)
    val byId = contacts.filter { it.userId != null }.associateBy { it.userId!!.lowercase() }
    val people = contacts.filter {
        it.registered && it.userId != null &&
            (it.displayName.contains(q, ignoreCase = true) || it.company.contains(q, ignoreCase = true))
    }
    val hits = messages.mapNotNull { m ->
        val peer = (if (m.outgoing) m.to else m.from).lowercase()
        val c = byId[peer] ?: return@mapNotNull null
        MessageHit(m, c.userId!!, c.displayName)
    }
    return SearchResults(q, people, hits)
}

/** Locked chats never show up in search (WhatsApp): their people and messages are dropped. Unknown locked list: nothing. */
fun withoutLocked(r: SearchResults, locked: Set<String>?, meId: String): SearchResults {
    if (locked == null) return SearchResults(r.query)
    if (locked.isEmpty()) return r
    fun hidden(peer: String) = dmConversationId(meId, peer) in locked
    return r.copy(
        contacts = r.contacts.filterNot { it.userId != null && hidden(it.userId) },
        messages = r.messages.filterNot { hidden(it.peerId) },
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
                combine(c.contacts.contacts, c.db.messages().search(likePattern(q), LIMIT)) { contacts, msgs ->
                    searchResults(q, contacts, msgs)
                }
            }
        },
        c.lockedChats.ids,
    ) { r, locked -> withoutLocked(r, locked, meId) }
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
