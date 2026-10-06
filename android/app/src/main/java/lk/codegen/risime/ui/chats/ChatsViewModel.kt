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
    /** DM: the friend's user id. Group: null. */
    val userId: String?,
    val name: String,
    val company: String,
    val registered: Boolean,
    val last: LastMessage?,
    val presence: Presence? = null,
    val typing: Boolean = false,
    val unread: Int = 0,
    /** §9: an accepted friend. False = a former friend whose old chat stays visible, read-only. */
    val friend: Boolean = true,
    val vouchedBy: String? = null,
    /** §12: a `grp:` row (conversationId set, userId null). */
    val conversationId: String? = null,
    val group: Boolean = false,
    /** Group: "Kamal is typing…" / "2 people are typing…". */
    val typingLabel: String? = null,
    /** Group: the last line's sender name ("Kamal: …"), null for mine or a system line. */
    val lastSender: String? = null,
    /** Group state line instead of the last message: "Creating…", "You left", "You were removed". */
    val stateLine: String? = null,
) {
    /** Open the chat: friends, and former friends with history (read-only); every group. */
    val openable: Boolean get() = group || (userId != null && (friend || last != null))

    /** What the chats list navigates to (a conversation id, or a DM peer). */
    val target: String? get() = conversationId ?: userId

    val key: String get() = conversationId ?: userId ?: name
}

/** "Kamal is typing…", "Kamal and Nimal are typing…", "3 people are typing…". */
fun groupTypingLabel(names: List<String>): String? = when (names.size) {
    0 -> null
    1 -> "${names[0]} is typing…"
    2 -> "${names[0]} and ${names[1]} are typing…"
    else -> "${names.size} people are typing…"
}

/** §15.6: a tombstone (or a row being deleted) previews as its tombstone text. */
fun LastMessage.forPreview(me: String): LastMessage =
    if (kind != lk.codegen.risime.data.db.MessageEntity.KIND_DELETED && deleteState == null) this
    else copy(
        body = lk.codegen.risime.data.deletes.DeleteRules.tombstoneText(if (deleteState != null) me else deletedBy, me, deletedByAdmin),
        kind = lk.codegen.risime.data.db.MessageEntity.KIND_DELETED,
    )

/** One row per group, ordered with the DMs by last activity (a new group by when it appeared). */
fun buildGroupRows(
    meId: String,
    groups: List<lk.codegen.risime.data.db.GroupEntity>,
    members: List<lk.codegen.risime.data.db.GroupMemberEntity>,
    lasts: List<LastMessage>,
    unread: List<UnreadCount>,
    typing: Map<String, Set<String>> = emptyMap(),
): List<ChatRow> {
    val byConv = lasts.associateBy { it.conversationId }.mapValues { it.value.forPreview(meId) }
    val unreadByConv = unread.associate { it.conversationId to it.unread }
    val names = members.groupBy { it.conversationId }.mapValues { (_, ms) -> ms.associate { it.userId.lowercase() to it.displayName } }
    return groups.map { g ->
        val last = byConv[g.conversationId]
        val nameOf = { id: String -> names[g.conversationId]?.get(id.lowercase()) ?: "Someone" }
        ChatRow(
            userId = null,
            name = lk.codegen.risime.data.groups.groupDisplayName(g.name),
            company = "",
            registered = true,
            last = last ?: LastMessage(g.conversationId, "", g.localTs, false, "READ"),
            unread = unreadByConv[g.conversationId] ?: 0,
            conversationId = g.conversationId,
            group = true,
            typingLabel = groupTypingLabel(typing[g.conversationId].orEmpty().filter { !it.equals(meId, true) }.map(nameOf).sorted()),
            lastSender = last?.takeIf { !it.outgoing && it.kind != lk.codegen.risime.data.db.MessageEntity.KIND_SYSTEM && it.kind != lk.codegen.risime.data.db.MessageEntity.KIND_DELETED }?.let { nameOf(it.from) },
            stateLine = when (g.state) {
                lk.codegen.risime.data.db.GroupEntity.STATE_CREATING -> "Creating…"
                lk.codegen.risime.data.db.GroupEntity.STATE_LEFT -> "You left"
                lk.codegen.risime.data.db.GroupEntity.STATE_REMOVED -> "You were removed"
                else -> null
            }.takeIf { last == null || g.readOnly },
        )
    }
}

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
    /** §12: group rows (from [buildGroupRows]), mixed in by last activity. */
    groupRows: List<ChatRow> = emptyList(),
): List<ChatRow> {
    val byConv = lasts.associateBy { it.conversationId }.mapValues { it.value.forPreview(meId) }
    val unreadByConv = unread.associate { it.conversationId to it.unread }
    return contacts.filter { ct ->
        // Former friends only while there's a conversation to show.
        ct.friend || (ct.userId != null && byConv[dmConversationId(meId, ct.userId)] != null)
    }.map { ct ->
        val id = ct.userId?.lowercase()
        val conv = ct.userId?.let { dmConversationId(meId, it) }
        ChatRow(
            ct.userId, ct.displayName, ct.company, ct.registered && ct.userId != null,
            conv?.let { byConv[it] },
            presence = id?.let { presence[it] },
            typing = id != null && id in typing,
            unread = conv?.let { unreadByConv[it] } ?: 0,
            friend = ct.friend,
            vouchedBy = ct.vouchedByName,
        )
    }.plus(groupRows).sortedWith(
        compareByDescending<ChatRow> { it.friend }
            .thenByDescending { it.last?.localTs ?: 0L }
            .thenBy { it.name.lowercase() },
    )
}

class ChatsViewModel(private val c: AppContainer, private val meId: String) : ViewModel() {
    val refreshError = MutableStateFlow<String?>(null)

    /** Incoming/outgoing requests and blocks (badge + Requests tab). */
    val friendsState = c.contacts.friendsState
    val connection: StateFlow<ConnectionState> = c.realtime.state

    private val groupRows = combine(
        c.db.groups().all(),
        c.db.groups().observeAllMembers(),
        c.db.messages().lastMessages(),
        c.db.messages().unreadCounts(),
        c.presence.groupTyping,
    ) { groups, members, lasts, unread, typing -> buildGroupRows(meId, groups, members, lasts, unread, typing) }

    val rows: StateFlow<List<ChatRow>> = combine(
        c.contacts.contacts,
        c.db.messages().lastMessages(),
        c.db.messages().unreadCounts(),
        combine(c.presence.presence, c.presence.typing) { p, t -> p to t },
        groupRows,
    ) { contacts, lasts, unread, (presence, typing), groups ->
        buildChatRows(meId, contacts, lasts, unread, presence, typing, groups)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "New group" is offered only with a groups-capable MLS core (§12.1). */
    val groupsAvailable: StateFlow<Boolean> = c.groupsAvailable.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val r = c.contacts.refresh()
            c.handleAuthError(r)
            refreshError.value = when (r) {
                is ApiResult.Ok -> null
                is ApiResult.Error -> "Couldn't load friends (${r.code})"
                is ApiResult.NetworkError -> "Offline — showing saved friends"
            }
        }
    }

    fun logout(confirmed: lk.codegen.risime.data.UserConfirmation) {
        viewModelScope.launch { c.logout(confirmed) }
    }
}
