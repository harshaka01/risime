package lk.codegen.risime.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")

    val results: StateFlow<SearchResults> = query.debounce(150).flatMapLatest { q ->
        if (q.isBlank()) {
            flowOf(SearchResults(q))
        } else {
            combine(c.contacts.contacts, c.db.messages().search(likePattern(q), LIMIT)) { contacts, msgs ->
                searchResults(q, contacts, msgs)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    fun onQuery(q: String) {
        query.value = q
    }

    private companion object {
        const val LIMIT = 100
    }
}
