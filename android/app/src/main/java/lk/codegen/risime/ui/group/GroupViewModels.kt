package lk.codegen.risime.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.IcuGraphemes
import lk.codegen.risime.data.TypingSender
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.db.GroupMemberEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.groups.CreatePayload
import lk.codegen.risime.data.groups.GroupOpType
import lk.codegen.risime.data.groups.RenamePayload
import lk.codegen.risime.data.groups.RolePayload
import lk.codegen.risime.data.groups.UsersPayload
import lk.codegen.risime.data.groups.groupDisplayName
import lk.codegen.risime.data.groups.groupOpErrorText
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.ProtocolJson
import java.util.UUID

fun pickFriends(contacts: List<ContactEntity>, exclude: Set<String> = emptySet()): List<PickFriend> =
    contacts.filter { it.friend && it.userId != null && it.userId.lowercase() !in exclude }
        .map { PickFriend(it.userId!!, it.displayName, it.company, it.groupReady) }

class CreateGroupViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(CreateGroupUi())
    val ui: StateFlow<CreateGroupUi> = combine(_ui, c.contacts.contacts) { u, contacts -> u.copy(friends = pickFriends(contacts)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CreateGroupUi())

    /** The new conversation id once its epoch-0 commit landed (the screen opens the chat). */
    val created = MutableStateFlow<String?>(null)

    fun onQuery(q: String) = _ui.update { it.copy(query = q) }

    fun onToggle(id: String) = _ui.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id, error = null) }

    fun onNext() = _ui.update { it.copy(step = 1, nameError = groupNameError(it.name, IcuGraphemes)) }

    /** Back from the name step returns to the picker; from the picker, leaves (false). */
    fun onBackStep(): Boolean {
        if (_ui.value.step == 1 && !_ui.value.busy) {
            _ui.update { it.copy(step = 0) }
            return true
        }
        return false
    }

    fun onName(n: String) = _ui.update { it.copy(name = n, nameError = groupNameError(n, IcuGraphemes), error = null) }

    /** Queues the create op (persisted: survives process death) and waits for it. */
    fun onCreate() {
        val u = _ui.value
        if (!u.canCreate) return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val payload = ProtocolJson.encodeToString(CreatePayload.serializer(), CreatePayload(u.name.trim(), u.selected.toList()))
            val id = c.groupStore.queueLocal(null, GroupOpType.CREATE, payload, clientGroupId = UUID.randomUUID().toString())
            val done = c.db.groupOps().observe(id).filterNotNull().first { it.state != GroupOpType.QUEUED || it.conversationId != null && it.attempts > 2 }
            when (done.state) {
                GroupOpType.DONE -> created.value = done.conversationId
                GroupOpType.FAILED -> _ui.update { it.copy(busy = false, error = groupOpErrorText(done.lastError)) }
                // Still retrying in the background (offline): open it; it shows "Creating…" until it lands.
                else -> created.value = done.conversationId
            }
        }
    }
}

/** A member as the info screen shows it. */
data class MemberUi(
    val userId: String,
    val name: String,
    val admin: Boolean,
    /** active | pending_add | pending_remove */
    val state: String,
    val me: Boolean,
    val joinedAt: String?,
)

data class GroupInfoUi(
    val name: String = "",
    val members: List<MemberUi> = emptyList(),
    val iAmAdmin: Boolean = false,
    val readOnly: Boolean = false,
    val stateLine: String? = null,
    val addCandidates: List<PickFriend> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
    /** §14.1 `missing_images`: members whose app can't show photos yet. */
    val photosNeedUpdate: List<String> = emptyList(),
) {
    val memberCount: Int get() = members.count { it.state != GroupMember.STATE_PENDING_ADD }

    /** §12.3 last_admin: the UI suggests the longest-standing other member. */
    val suggestedAdmin: MemberUi?
        get() = members.filter { !it.me && !it.admin && it.state == GroupMember.STATE_ACTIVE }.minByOrNull { it.joinedAt ?: "9999" }
}

fun groupInfoUi(meId: String, g: GroupEntity?, members: List<GroupMemberEntity>, contacts: List<ContactEntity>): GroupInfoUi {
    val current = members.filter { it.current }
    val ms = current.map { MemberUi(it.userId, it.displayName, it.role == GroupMember.ROLE_ADMIN, it.state, it.userId.equals(meId, true), it.joinedAt) }
        .sortedWith(compareByDescending<MemberUi> { it.me }.thenByDescending { it.admin }.thenBy { it.name.lowercase() })
    val readOnly = g?.readOnly == true
    return GroupInfoUi(
        name = groupDisplayName(g?.name),
        members = if (readOnly) ms.filter { !it.me } else ms,
        iAmAdmin = g?.myRole == GroupMember.ROLE_ADMIN && !readOnly,
        readOnly = readOnly,
        stateLine = when (g?.state) {
            GroupEntity.STATE_LEFT -> "You left this group"
            GroupEntity.STATE_REMOVED -> "You were removed from this group"
            GroupEntity.STATE_CREATING -> "Creating…"
            else -> null
        },
        addCandidates = pickFriends(contacts, current.map { it.userId.lowercase() }.toSet()),
    )
}

class GroupInfoViewModel(private val c: AppContainer, private val meId: String, val conversationId: String) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)
    private val missingImages = MutableStateFlow<List<String>>(emptyList())

    val ui: StateFlow<GroupInfoUi> = combine(
        c.db.groups().observe(conversationId),
        c.db.groups().observeMembers(conversationId),
        c.contacts.contacts,
        error,
        missingImages,
    ) { g, members, contacts, err, missing ->
        val names = missing.distinctBy { it.lowercase() }.map { id ->
            if (id.equals(meId, true)) "Your other phone" else members.firstOrNull { it.userId.equals(id, true) }?.displayName ?: "Someone"
        }
        groupInfoUi(meId, g, members, contacts).copy(error = err, photosNeedUpdate = names)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupInfoUi())

    init {
        viewModelScope.launch { c.refreshGroup(conversationId) }
        if (c.mediaCrypto != null) viewModelScope.launch {
            (c.api.mlsGroup(conversationId) as? ApiResult.Ok)?.let { r -> missingImages.value = r.value.missingImages.map { it.userId } }
        }
    }

    /** Queue an op and report its failure here (success shows through the group's own state). */
    private fun queue(type: String, payload: String = "{}") {
        error.value = null
        viewModelScope.launch {
            val id = c.groupStore.queueLocal(conversationId, type, payload)
            val done = c.db.groupOps().observe(id).filterNotNull().first { it.state != GroupOpType.QUEUED }
            if (done.state == GroupOpType.FAILED) {
                error.value = if (done.lastError == AuthErrors.LAST_ADMIN) {
                    val s = ui.value.suggestedAdmin
                    groupOpErrorText(done.lastError) + (s?.let { " Make ${it.name} an admin, then leave." } ?: "")
                } else {
                    groupOpErrorText(done.lastError)
                }
            }
        }
    }

    fun add(userIds: Collection<String>) = queue(GroupOpType.ADD, ProtocolJson.encodeToString(UsersPayload.serializer(), UsersPayload(userIds.toList())))

    fun remove(userId: String) = queue(GroupOpType.REMOVE, ProtocolJson.encodeToString(UsersPayload.serializer(), UsersPayload(listOf(userId))))

    fun setRole(userId: String, admin: Boolean) =
        queue(GroupOpType.ROLE, ProtocolJson.encodeToString(RolePayload.serializer(), RolePayload(userId, if (admin) GroupMember.ROLE_ADMIN else GroupMember.ROLE_MEMBER)))

    fun rename(name: String) {
        groupNameError(name, IcuGraphemes)?.let { error.value = it; return }
        queue(GroupOpType.RENAME, ProtocolJson.encodeToString(RenamePayload.serializer(), RenamePayload(name.trim())))
    }

    /** Leaving is immediate locally (read-only chat); undone if the server says last_admin. */
    fun leave() {
        viewModelScope.launch {
            c.db.groups().get(conversationId)?.let { c.db.groups().upsert(it.copy(state = GroupEntity.STATE_LEFT)) }
            queue(GroupOpType.LEAVE)
        }
    }

    /** §12.8 admin reset: rebuilds the whole group's encryption (when commits keep failing). */
    fun reset() = queue(GroupOpType.RESET)

    fun dismissError() {
        error.value = null
    }
}

/** The group chat's composer: on, or off with the reason shown in its place (never a silent failure). */
sealed interface GroupComposer {
    data object Enabled : GroupComposer

    data class Disabled(val reason: String) : GroupComposer
}

const val COMPOSER_REJOINING = "Rejoining… you can send once this phone is back in the group"

/**
 * [encrypted] = this device holds the group's MLS state (null = not checked yet). An active group
 * without it is waiting for its rejoin Welcome (§12.8): sends would only queue, so the composer
 * says so. A group still being created keeps the composer (its sends wait for the epoch-0 commit).
 */
fun groupComposer(g: GroupEntity?, encrypted: Boolean?): GroupComposer = when {
    g == null -> GroupComposer.Enabled
    g.readOnly -> GroupComposer.Disabled(if (g.state == GroupEntity.STATE_LEFT) "You left this group." else "You were removed from this group.")
    g.state == GroupEntity.STATE_ACTIVE && encrypted == false -> GroupComposer.Disabled(COMPOSER_REJOINING)
    else -> GroupComposer.Enabled
}

class GroupChatViewModel(private val c: AppContainer, private val meId: String, val conversationId: String) : ViewModel() {
    val group: StateFlow<GroupEntity?> = c.db.groups().observe(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val members: StateFlow<List<GroupMemberEntity>> = c.db.groups().observeMembers(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val messages: StateFlow<List<MessageEntity>> = c.db.messages().conversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connection = c.realtime.state

    val typingNames: StateFlow<List<String>> = combine(c.presence.groupTyping, members) { t, ms ->
        val names = ms.associate { it.userId.lowercase() to it.displayName }
        t[conversationId].orEmpty().filter { !it.equals(meId, true) }.map { names[it] ?: "Someone" }.sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val reactions: StateFlow<Map<String, List<lk.codegen.risime.data.ReactionChip>>> =
        c.db.reactions().forConversation(conversationId)
            .map { rows -> rows.groupBy { it.targetMessageId }.mapValues { (_, rs) -> lk.codegen.risime.data.chipsFor(rs, meId) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The local MLS group exists (null until first checked; false: rejoining / setting up until the Welcome). */
    private val _encrypted = MutableStateFlow<Boolean?>(null)
    val encrypted: StateFlow<Boolean?> = _encrypted

    /** The composer, or why it's off (left/removed, or rejoining after a sign-in, §12.8). */
    val composer: StateFlow<GroupComposer> = combine(group, _encrypted) { g, e -> groupComposer(g, e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupComposer.Enabled)

    private val typingSender = TypingSender(viewModelScope, System::currentTimeMillis, { typing ->
        c.scope.launch { c.realtime.typing(conversationId, typing) }
    })

    /** §15.7 deletes (message actions behind DeleteFeature.sendEnabled; admins delete any message, §15.4). */
    val del = lk.codegen.risime.ui.chat.DeleteController(c, viewModelScope, meId, conversationId, iAmAdmin = { group.value?.myRole == lk.codegen.risime.net.GroupMember.ROLE_ADMIN })
        .also { it.refreshReady() }

    /** §14: photos in this group. */
    val imgs = lk.codegen.risime.ui.chat.ImageActions(c, viewModelScope, meId, conversationId)

    init {
        c.openConversation.value = conversationId
        c.notifier.cancelChat(conversationId)
        viewModelScope.launch { c.refreshGroup(conversationId) }
        imgs.refreshImagesReady()
        viewModelScope.launch {
            // Re-checked on new rows, on group row changes and whenever a Welcome/commit changed the MLS state.
            combine(messages, group, c.groupStore.stateChanges) { _, _, _ -> }.collect {
                _encrypted.value = withContext(Dispatchers.IO) { c.mlsEngine?.group(conversationId) != null }
            }
        }
    }

    fun nameOf(userId: String): String = when {
        userId.equals(meId, true) -> "You"
        else -> members.value.firstOrNull { it.userId.equals(userId, true) }?.displayName ?: "Former member"
    }

    fun onDraftChanged(text: String) {
        if (group.value?.readOnly != true) typingSender.onInput(text)
    }

    fun send(text: String) {
        typingSender.stop()
        viewModelScope.launch { c.engine.sendText(conversationId, text) }
    }

    fun react(targetMessageId: String, emoji: String, op: String) {
        c.scope.launch { c.engine.react(conversationId, targetMessageId, emoji, op) }
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

    fun markRead() {
        c.scope.launch { c.engine.markConversationRead(conversationId) }
    }

    /** "Read by" for my message: fetched on open, never stored (R10). */
    private val _readBy = MutableStateFlow<Pair<String, ReadByState>?>(null)
    val readBy: StateFlow<Pair<String, ReadByState>?> = _readBy

    fun openReadBy(messageId: String) {
        _readBy.value = messageId to ReadByState.Loading
        viewModelScope.launch {
            _readBy.value = messageId to when (val r = c.api.groupReceipts(conversationId, messageId)) {
                is ApiResult.Ok -> ReadByState.Loaded(r.value)
                is ApiResult.Error -> ReadByState.Error(if (r.httpStatus == 404) "No longer available" else "Couldn't load (${r.code})")
                is ApiResult.NetworkError -> ReadByState.Error("Offline — try again")
            }
        }
    }

    fun closeReadBy() {
        _readBy.value = null
    }

    override fun onCleared() {
        typingSender.stop()
        c.openConversation.compareAndSet(conversationId, null)
    }
}
