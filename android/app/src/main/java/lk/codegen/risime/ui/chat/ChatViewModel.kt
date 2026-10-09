package lk.codegen.risime.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lk.codegen.risime.data.mls.E2eeRetryBackoff
import lk.codegen.risime.data.mls.E2eeState
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.TypingSender
import lk.codegen.risime.net.Presence
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.realtime.ConnectionState

class ChatViewModel(private val c: AppContainer, private val meId: String, val peerId: String) : ViewModel() {
    val conversationId = dmConversationId(meId, peerId)

    /** §25.4 a Risi draft's [Use] for this chat (taken once; never sent). */
    fun takeDraft(): String? = c.risiUi.takeDraft(conversationId)

    /** §26.6 this chat's scheduled messages (the sender's phone only). */
    val scheduledCtl = lk.codegen.risime.ui.chat.ScheduledControls(
        c.scheduled, c.db.scheduled().open(), c.db.scheduled().sends(), viewModelScope, conversationId,
        afterSend = { c.flushOutboxAfterUpload() },
    )

    /** "Scheduled messages" in the chat ⋮: on a risi_skills phone, or while this chat has any. */
    fun scheduledMenu(): Boolean = c.risiSkillsOn()

    /** My user id (tombstone wording, §15.6). */
    val me: String get() = meId

    val messages: StateFlow<List<MessageEntity>> = c.db.messages().conversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** §17.12 the gap marker's state (null with the feature off). */
    val historyMarker: StateFlow<lk.codegen.risime.data.history.HistoryMarkerState?> =
        if (lk.codegen.risime.BuildConfig.HISTORY_SHARE_ENABLED) {
            c.history.markerState(conversationId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        } else {
            kotlinx.coroutines.flow.MutableStateFlow(null)
        }

    fun requestHistory(sources: String) {
        c.scope.launch { c.history.request(conversationId, sources) }
    }

    fun escalateHistory() {
        c.scope.launch { c.history.escalate(conversationId) }
    }

    val peer: StateFlow<ContactEntity?> = c.contacts.contact(peerId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** §21.4 (v1.20): the peer's phone is self-asserted and not SMS-verified. */
    val peerPhoneUnconfirmed: StateFlow<Boolean> = c.contacts.friendsState
        .map { peerId.lowercase() in it.unconfirmed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    /** §11.2: reaction chips per target message_id. */
    val reactions: StateFlow<Map<String, List<lk.codegen.risime.data.ReactionChip>>> =
        c.db.reactions().forConversation(conversationId)
            .map { rows -> rows.groupBy { it.targetMessageId }.mapValues { (_, rs) -> lk.codegen.risime.data.chipsFor(rs, meId) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun react(targetMessageId: String, emoji: String, op: String) {
        c.scope.launch { c.engine.react(peerId, targetMessageId, emoji, op) }
    }

    fun nameOf(userId: String): String = when {
        userId.equals(meId, true) -> "You"
        userId.equals(peerId, true) -> peer.value?.displayName ?: "Friend"
        else -> "Former friend"
    }

    /** §10.4 / decision 048: header lock, or the "Not end-to-end encrypted yet: <reason>" strip. */
    private val _e2ee = kotlinx.coroutines.flow.MutableStateFlow<E2eeState>(E2eeState.Checking)
    val e2ee: StateFlow<E2eeState> = _e2ee

    /** This install's device id (the strip says "this phone" for it). */
    var myDeviceId: String? = null
        private set

    private val e2eeKick = Channel<Unit>(Channel.CONFLATED)

    /**
     * Re-check now (chat open/resume, a tapped disabled button, `mls_membership`, reconnect).
     * Opening (or sending to) a chat that isn't e2ee yet tries the upgrade (claim → epoch-0 commit).
     */
    fun refreshE2ee() {
        e2eeKick.trySend(Unit)
    }

    /** P0-1: the upgrade retries on its own while the chat is open and not E2EE (backoff 5 s … 5 min). */
    private suspend fun e2eeLoop() {
        val backoff = E2eeRetryBackoff()
        myDeviceId = runCatching { c.sessionStore.deviceId() }.getOrNull()
        // v1.21 §12.12.2: a kick (open, resume, reconnect) refreshes the rejoin reply of a waiting DM.
        var kickedNow = true
        while (true) {
            if (c.mlsEngine == null) {
                _e2ee.value = E2eeState.Unavailable
            } else if (peer.value?.friend != false) {
                val was = _e2ee.value
                // Engine calls are synchronous Room transactions: never on the main thread.
                val now = withContext(Dispatchers.IO) { c.mlsUpgrader.ensure(conversationId, meId, peerId, verify = true, kicked = kickedNow) }
                _e2ee.value = now
                if (now is E2eeState.Encrypted && was !is E2eeState.Encrypted) withContext(Dispatchers.IO) { c.engine.flushOutbox() }
            }
            val wait = backoff.delayAfter(_e2ee.value)
            val kicked = if (wait == null) e2eeKick.receive() else withTimeoutOrNull(wait) { e2eeKick.receive() }
            if (kicked != null) backoff.reset()
            kickedNow = kicked != null
        }
    }

    /** §15.7 deletes in this chat (message actions behind DeleteFeature.sendEnabled; Clear/Delete chat always). */
    val del = DeleteController(c, viewModelScope, meId, conversationId).also { it.refreshReady() }

    /** §16: the call button. */
    val calls = CallActions(c, viewModelScope, conversationId)

    fun startCall() = calls.start()

    /** §19.6 the DM header's video button (or "Video call back" on a video line). */
    fun startVideoCall(camera: Boolean) = calls.startVideo(camera)

    /** §16.6 "Delete for me" on a call line. */
    fun deleteCallLine(clientMsgId: String) {
        c.scope.launch { c.engine.deleteForMe(conversationId, listOf(clientMsgId)) }
    }

    /** §14: photos in this chat. */
    val imgs = ImageActions(c, viewModelScope, meId, conversationId)

    init {
        viewModelScope.launch { e2eeLoop() }
        viewModelScope.launch {
            c.mlsMembershipSeen.collect { e ->
                if (e.conversationId == conversationId || e.userId.equals(peerId, true) || e.userId.equals(meId, true)) refreshE2ee()
            }
        }
        viewModelScope.launch {
            c.realtime.state.collect { if (it == ConnectionState.Live && _e2ee.value !is E2eeState.Encrypted) refreshE2ee() }
        }
        imgs.refreshImagesReady()
        calls.refresh()
        viewModelScope.launch { c.mlsMembershipSeen.collect { e -> if (e.conversationId == conversationId) calls.refresh() } }
        viewModelScope.launch { c.realtime.state.collect { if (it == ConnectionState.Live) calls.refresh() } }
        viewModelScope.launch { c.behaviour.chatOpen(peerId) }
        c.openConversation.value = conversationId
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

    private fun isImage(id: String) = messages.value.firstOrNull { it.clientMsgId == id }?.image == true

    fun retry(clientMsgId: String) {
        if (isImage(clientMsgId)) return imgs.retry(clientMsgId)
        c.scope.launch { c.engine.retry(clientMsgId) }
    }

    fun delete(clientMsgId: String) {
        if (isImage(clientMsgId)) return imgs.delete(clientMsgId)
        c.scope.launch { c.engine.deleteFailed(clientMsgId) }
    }

    override fun onCleared() {
        typingSender.stop()
        c.openConversation.compareAndSet(conversationId, null)
    }

    /** Called while the chat is on screen (resumed): incoming → read, then ack. */
    fun markRead() {
        c.scope.launch { c.engine.markConversationRead(conversationId) }
    }
}
