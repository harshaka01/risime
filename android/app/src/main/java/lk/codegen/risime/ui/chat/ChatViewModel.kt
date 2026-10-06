package lk.codegen.risime.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.TypingSender
import lk.codegen.risime.net.Presence
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.realtime.ConnectionState

class ChatViewModel(private val c: AppContainer, private val meId: String, val peerId: String) : ViewModel() {
    val conversationId = dmConversationId(meId, peerId)

    val messages: StateFlow<List<MessageEntity>> = c.db.messages().conversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val peer: StateFlow<ContactEntity?> = c.contacts.contact(peerId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val connection: StateFlow<ConnectionState> = c.realtime.state

    val peerPresence: StateFlow<Presence?> = c.presence.presence.map { it[peerId.lowercase()] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val peerTyping: StateFlow<Boolean> = c.presence.typing.map { peerId.lowercase() in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Fire-and-forget on the app scope: typing is never queued, and a final `false` must not be
    // cancelled with this ViewModel.
    private val typingSender = TypingSender(viewModelScope, System::currentTimeMillis, { typing ->
        c.scope.launch { c.realtime.typing(peerId, typing) }
    })

    /** §10.4: lock / "not end-to-end encrypted yet" strip. Unavailable without an MLS core. */
    private val _e2ee = kotlinx.coroutines.flow.MutableStateFlow<lk.codegen.risime.data.mls.E2eeState>(lk.codegen.risime.data.mls.E2eeState.Unavailable)
    val e2ee: StateFlow<lk.codegen.risime.data.mls.E2eeState> = _e2ee

    /** Opening (or sending to) a chat that isn't e2ee yet tries the upgrade (claim → epoch-0 commit). */
    fun refreshE2ee() {
        if (c.mlsEngine == null || peer.value?.friend == false) return
        viewModelScope.launch {
            // Engine calls are synchronous Room transactions: never on the main thread.
            _e2ee.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                c.mlsUpgrader.ensure(conversationId, meId, peerId)
            }
            if (_e2ee.value is lk.codegen.risime.data.mls.E2eeState.Encrypted) c.engine.flushOutbox()
        }
    }

    init {
        refreshE2ee()
        viewModelScope.launch { c.behaviour.chatOpen(peerId) }
        c.openChatPeer.value = peerId
        c.notifier.cancelChat(conversationId)
    }

    /** Typing goes to friends only (§9.3: non-friends get not_friends). */
    fun onDraftChanged(text: String) {
        if (peer.value?.friend != false) typingSender.onInput(text)
    }

    private val _requested = kotlinx.coroutines.flow.MutableStateFlow(false)
    val requested: StateFlow<Boolean> = _requested

    /** Read-only former-friend chat → "Add friend" (always the same 202 reply). */
    fun requestFriend() {
        val phone = peer.value?.phone ?: return
        viewModelScope.launch {
            if (c.api.requestFriend(phone) is lk.codegen.risime.net.ApiResult.Ok) {
                _requested.value = true
                c.requestFriendsRefresh()
            }
        }
    }

    fun send(text: String) {
        typingSender.stop()
        viewModelScope.launch { c.engine.sendText(peerId, text) }
    }

    fun retry(clientMsgId: String) {
        c.scope.launch { c.engine.retry(clientMsgId) }
    }

    fun delete(clientMsgId: String) {
        c.scope.launch { c.engine.deleteFailed(clientMsgId) }
    }

    override fun onCleared() {
        typingSender.stop()
        c.openChatPeer.compareAndSet(peerId, null)
    }

    /** Called while the chat is on screen (resumed): incoming → read, then ack. */
    fun markRead() {
        c.scope.launch { c.engine.markConversationRead(conversationId) }
    }
}
