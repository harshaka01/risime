package lk.codegen.risime.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
    /** §24.9: the tab of the shown last message (its small icon), set only while tabs are on and the chat has an Official conversation. */
    val lastTab: lk.codegen.risime.data.tabs.Tab? = null,
    /** §24: the chat's Official conversation merged into this row (null: none on this device). */
    val officialConversationId: String? = null,
    /** §25.2 the user's Risi chat (its conversation, or the "Risi" entry before it exists: [conversationId] null). */
    val risi: Boolean = false,
) {
    /** Open the chat: friends, and former friends with history (read-only); every group; the Risi chat. */
    val openable: Boolean get() = group || risi || (userId != null && (friend || last != null))

    /** What the chats list navigates to (a conversation id, a DM peer, or [RISI_NEW_TARGET]). */
    val target: String? get() = conversationId ?: userId ?: RISI_NEW_TARGET.takeIf { risi }

    val key: String get() = conversationId ?: userId ?: if (risi) RISI_NEW_TARGET else name
}

/** The chat list's "Risi" entry before the Risi chat exists: opening it creates the chat (§25.2). */
const val RISI_NEW_TARGET = "risi:new"

/**
 * §25.2 the Risi chat in the chat list, only on a `risi_tools` device ([risiOn]): its conversation
 * (MLS says `chat_kind: "risi"`) is one row named "Risi", pinned first; with none that is still usable
 * (none, or only ones I left), a "Risi" entry that creates it on first open. Without `risi_tools` no
 * Risi chat is listed at all (its history stays on the phone, hard rule 9).
 */
fun applyRisiRows(rows: List<ChatRow>, tabs: Map<String, lk.codegen.risime.data.db.ChatTabEntity>?, risiOn: Boolean): List<ChatRow> {
    fun isRisi(r: ChatRow) = r.group && r.conversationId?.let { lk.codegen.risime.data.tabs.isRisiChat(it, tabs) } == true
    val (risi, others) = rows.partition(::isRisi)
    if (!risiOn) return others
    val marked = risi.map { it.copy(name = lk.codegen.risime.data.tabs.RISI_CHAT_NAME, risi = true, lastTab = null, lastSender = null) }
        .sortedByDescending { it.last?.localTs ?: 0L }
    val usable = marked.any { it.stateLine == null || it.stateLine == "Creating…" }
    val entry = if (usable || tabs == null) emptyList() else listOf(
        ChatRow(userId = null, name = lk.codegen.risime.data.tabs.RISI_CHAT_NAME, company = "", registered = true, last = null, risi = true),
    )
    return entry + marked + others
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

/** The row's conversation id (`grp:` as is; a DM from the peer). */
fun ChatRow.conversationOf(meId: String): String? = conversationId ?: userId?.let { dmConversationId(meId, it) }

/** §15.7 Delete chat: hidden rows stay off the list until `chat_state.hidden` is cleared by a new message. */
fun hideDeletedChats(rows: List<ChatRow>, states: List<lk.codegen.risime.data.db.ChatStateEntity>, meId: String): List<ChatRow> {
    val hidden = states.filter { it.hidden }.map { it.conversationId }.toSet()
    if (hidden.isEmpty()) return rows
    return rows.filter { it.conversationOf(meId) !in hidden }
}

/** The list split for the Locked chats folder. [locked] null = not read yet: nothing is shown (never a locked chat by mistake). */
data class LockedSplit(val visible: List<ChatRow> = emptyList(), val locked: List<ChatRow> = emptyList())

fun splitLocked(rows: List<ChatRow>, locked: Set<String>?, meId: String): LockedSplit {
    if (locked == null) return LockedSplit()
    if (locked.isEmpty()) return LockedSplit(rows)
    val (l, v) = rows.partition { it.conversationOf(meId)?.lowercase() in locked }
    return LockedSplit(v, l)
}

/** The chats list order: friends first, then the most recent activity, then the name. */
val chatRowOrder: Comparator<ChatRow> = compareByDescending<ChatRow> { it.friend }
    .thenByDescending { it.last?.localTs ?: 0L }
    .thenBy { it.name.lowercase() }

/**
 * §24.9 one row per `chat_id`: an Official conversation (MLS says `tab: official`, or the server lists it
 * as Official and this device can't read it yet) is never a row of its own. It merges into its chat's
 * row: the newer last message of the two tabs (with its tab icon when [showTabIcon]) and the unread sum.
 * An Official conversation whose chat has no row here stays hidden.
 */
fun mergeTabRows(
    rows: List<ChatRow>,
    tabs: Map<String, lk.codegen.risime.data.db.ChatTabEntity>?,
    pendingOfficial: Map<String, String>,
    meId: String,
    showTabIcon: Boolean,
): List<ChatRow> {
    fun officialChat(r: ChatRow): String? {
        val conv = r.conversationId?.lowercase()?.takeIf { r.group } ?: return null
        val t = tabs?.get(conv)
        // §25.2 a Risi chat is its own row (applyRisiRows), never merged into another chat.
        return if (t != null) t.chatId.lowercase().takeIf { t.official && !t.risi } else pendingOfficial[conv]?.lowercase()
    }
    val official = rows.mapNotNull { r -> officialChat(r)?.let { it to r } }
    if (official.isEmpty()) return rows
    val byChat = official.groupBy({ it.first }, { it.second })
    val officialKeys = official.map { it.second.key }.toSet()
    return rows.filter { it.key !in officialKeys }.map { r ->
        val off = byChat[r.conversationOf(meId)?.lowercase()]?.firstOrNull() ?: return@map r
        val offLast = off.last?.takeIf { it.from.isNotEmpty() } // a group row with no message carries a placeholder
        val offNewer = offLast != null && offLast.localTs > (r.last?.localTs ?: Long.MIN_VALUE)
        r.copy(
            last = if (offNewer) offLast else r.last,
            lastSender = if (offNewer) off.lastSender else r.lastSender,
            unread = r.unread + off.unread,
            lastTab = if (!showTabIcon) null else if (offNewer) lk.codegen.risime.data.tabs.Tab.OFFICIAL else lk.codegen.risime.data.tabs.Tab.PRIVATE,
            officialConversationId = off.conversationId,
            typingLabel = r.typingLabel ?: off.typingLabel,
        )
    }.sortedWith(chatRowOrder)
}

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
    }.plus(groupRows).sortedWith(chatRowOrder)
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

    private val allRows: StateFlow<List<ChatRow>> = combine(
        combine(
            c.contacts.contacts,
            c.db.messages().lastMessages(),
            c.db.messages().unreadCounts(),
            combine(c.presence.presence, c.presence.typing) { p, t -> p to t },
            groupRows,
        ) { contacts, lasts, unread, (presence, typing), groups ->
            buildChatRows(meId, contacts, lasts, unread, presence, typing, groups)
        },
        c.db.deletes().observeChatStates(),
        c.chatTabs.rows,
        c.chatTabs.pendingOfficial,
        combine(c.chatTabs.uiOn, c.risiTools.on) { tabsOn, risiOn -> tabsOn to (tabsOn && risiOn) },
    ) { rows, states, tabs, pending, (tabsOn, risiOn) ->
        applyRisiRows(mergeTabRows(hideDeletedChats(rows, states, meId), tabs, pending, meId, tabsOn), tabs, risiOn)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The Calls tab: 1:1 call records grouped like WhatsApp ("Name (3)"), newest first. */
    val calls: StateFlow<List<CallRow>> = combine(c.db.messages().observeCallLines(), c.contacts.contacts) { lines, contacts ->
        val byConv = contacts.filter { it.userId != null }.associateBy { dmConversationId(meId, it.userId!!) }
        buildCallRows(lines.mapNotNull { lk.codegen.risime.calls.CallRecords.of(it) }, byConv)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Why a call can't start from here (null = it can). */
    fun callBlocked(): String? = runCatching { c.calls.unsupportedReason() }.getOrNull()

    /** Call back from the Calls tab with the same type as the record. */
    fun callBack(conversationId: String, video: Boolean, camera: Boolean) {
        c.calls.placeCall(conversationId, video = video, camera = camera)
        c.calls.openCallScreen()
    }

    fun hasCamera(): Boolean = c.calls.hasCameraPermission()

    private val split = combine(allRows, c.lockedChats.ids) { rows, locked -> splitLocked(rows, locked, meId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LockedSplit())

    /** The main list: locked chats are not in it (nothing at all until the locked list is known). */
    val rows: StateFlow<List<ChatRow>> = split.map { it.visible }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The Locked chats folder's rows. */
    val lockedRows: StateFlow<List<ChatRow>> = split.map { it.locked }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** A secret code is set: the pull-down entry stays hidden. */
    val lockedHasCode: StateFlow<Boolean> = c.lockedChats.hasCode

    fun openLockedFolder() = c.lockedChats.openFolder()

    fun unlockChat(row: ChatRow) {
        val conv = row.conversationOf(meId) ?: return
        c.scope.launch { c.lockedChats.unlock(conv) }
    }

    fun lockChat(row: ChatRow) {
        val conv = row.conversationOf(meId) ?: return
        c.notifier.cancelChat(conv) // the shade must not keep showing the chat's name or text
        c.scope.launch { c.lockedChats.lock(conv) }
    }

    /** §15.7 chat-list long-press: Clear chat (row stays) / Delete chat (hidden until a new message). */
    fun clearChat(row: ChatRow, hide: Boolean) {
        val conv = row.conversationOf(meId) ?: return
        c.notifier.cancelChat(conv)
        c.scope.launch { c.engine.clearChat(conv, hide) }
    }

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

/** A Calls-tab row: the person, the grouped records, and whether a tap can open the chat. */
data class CallRow(val group: lk.codegen.risime.calls.CallGroup, val name: String, val userId: String?) {
    val key: String get() = group.latest.clientMsgId
    val title: String get() = if (group.count > 1) "$name (${group.count})" else name
}

fun buildCallRows(records: List<lk.codegen.risime.calls.CallRecord>, contactsByConv: Map<String, ContactEntity>): List<CallRow> =
    lk.codegen.risime.calls.groupCalls(records).map { g ->
        val ct = contactsByConv[g.conversationId]
        CallRow(g, ct?.displayName ?: "Unknown", ct?.userId)
    }
