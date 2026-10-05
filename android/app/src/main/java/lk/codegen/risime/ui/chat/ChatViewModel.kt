package lk.codegen.risime.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.realtime.ConnectionState

class ChatViewModel(private val c: AppContainer, meId: String, val peerId: String) : ViewModel() {
    val conversationId = dmConversationId(meId, peerId)

    val messages: StateFlow<List<MessageEntity>> = c.db.messages().conversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val peer: StateFlow<ContactEntity?> = c.contacts.contact(peerId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val connection: StateFlow<ConnectionState> = c.realtime.state

    init {
        viewModelScope.launch { c.behaviour.chatOpen(peerId) }
    }

    fun send(text: String) {
        viewModelScope.launch { c.engine.sendText(peerId, text) }
    }

    /** Called while the chat is on screen (resumed): incoming → read, then ack. */
    fun markRead() {
        c.scope.launch { c.engine.markConversationRead(conversationId) }
    }
}
