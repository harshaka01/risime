package lk.codegen.risime.ui.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import lk.codegen.risime.ui.friends.FriendsViewModel
import lk.codegen.risime.ui.friends.RequestsTab
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lk.codegen.risime.realtime.ConnectionState
import lk.codegen.risime.ui.common.EmptyState
import lk.codegen.risime.ui.common.ErrorState
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.ListRow
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.common.TYPING_LABEL
import lk.codegen.risime.ui.common.UnreadBadge
import lk.codegen.risime.ui.common.presenceLabel
import lk.codegen.risime.ui.common.shortStamp
import lk.codegen.risime.ui.theme.Spacing
import androidx.compose.ui.input.nestedscroll.nestedScroll

@Composable
fun ChatsScreen(
    vm: ChatsViewModel,
    friendsVm: FriendsViewModel,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onAddFriend: () -> Unit,
    onInvites: () -> Unit,
    onNewGroup: () -> Unit = {},
    onLockedFolder: () -> Unit = {},
    onChatLockSettings: () -> Unit = {},
    /** §29 the Calendar tab (shown only while this device is a `risi_events` device). */
    calendar: lk.codegen.risime.ui.calendar.RisiCalendarViewModel? = null,
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val lockedRows by vm.lockedRows.collectAsStateWithLifecycle()
    val lockedHasCode by vm.lockedHasCode.collectAsStateWithLifecycle()
    val lockGate = lk.codegen.risime.ui.lock.rememberLockGate()
    lk.codegen.risime.ui.lock.LockGateDialog(lockGate)
    val pull = remember { PullToRevealState() }
    val groupsAvailable by vm.groupsAvailable.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val err by vm.refreshError.collectAsStateWithLifecycle()
    val friends by vm.friendsState.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var askLogout by remember { mutableStateOf(false) }
    var askLogoutDelete by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val calendarOn by (calendar?.on ?: NO_CALENDAR).collectAsStateWithLifecycle()
    val calendarFocus by (calendar?.focus ?: NO_FOCUS).collectAsStateWithLifecycle()
    // §29: the switch off → v1.28 exactly (no Calendar tab); an [Open] on an event card shows the tab.
    androidx.compose.runtime.LaunchedEffect(calendarOn) { if (!calendarOn && tab == CALENDAR_TAB) tab = 0 }
    androidx.compose.runtime.LaunchedEffect(calendarFocus, calendarOn) { if (calendarFocus != null && calendarOn) tab = CALENDAR_TAB }
    val callsUi = remember { CallsUi() }
    val callRows by vm.calls.collectAsStateWithLifecycle()
    val callBack = rememberCallBack(vm)
    var callsMenu by remember { mutableStateOf(false) }
    // Back leaves call info / New call first, then the selection.
    androidx.activity.compose.BackHandler(enabled = callsUi.selecting && tab == 1) { callsUi.selected = emptySet() }
    // Leaving the Calls tab ends a selection.
    androidx.compose.runtime.LaunchedEffect(tab) { if (tab != 1) callsUi.selected = emptySet() }
    CallsDialogs(callsUi, callRows, onDelete = vm::hideCalls, onClear = vm::clearCallLog)
    // WhatsApp: a long-press selects the chat; the top bar becomes the selection bar (Delete, ⋮ → Lock chat / Clear chat).
    var selected by remember { mutableStateOf<ChatRow?>(null) }
    var clearAsk by remember { mutableStateOf<Pair<ChatRow, Boolean>?>(null) }
    androidx.activity.compose.BackHandler(enabled = selected != null) { selected = null }
    // A selected row that left the list (locked, deleted elsewhere) ends the selection.
    androidx.compose.runtime.LaunchedEffect(rows, selected) { selected?.let { s -> if (rows.none { it.key == s.key }) selected = null } }
    clearAsk?.let { (r, hide) ->
        lk.codegen.risime.ui.chat.ClearChatDialog(hide, onConfirm = { clearAsk = null; vm.clearChat(r, hide) }, onDismiss = { clearAsk = null })
    }
    if (askLogout || askLogoutDelete) {
        lk.codegen.risime.ui.common.LogoutConfirmDialog(
            onConfirm = { confirmed ->
                askLogout = false
                askLogoutDelete = false
                vm.logout(confirmed)
            },
            onDismiss = { askLogout = false; askLogoutDelete = false },
            deleteChats = askLogoutDelete,
        )
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    // Call info / New call are full pages: the list underneath is not composed (nor reachable by accessibility) meanwhile.
    if (callsUi.info == null && !callsUi.newCall) Scaffold(
        topBar = {
            val sel = selected
            if (tab == 1 && callsUi.selecting) {
                CallsSelectionBar(
                    count = callsUi.selected.size,
                    onClose = { callsUi.selected = emptySet() },
                    onSelectAll = { callsUi.selected = callRows.map { it.key }.toSet() },
                    onDelete = { callsUi.askDelete = true },
                )
            } else if (sel != null) {
                ChatSelectionBar(
                    onClose = { selected = null },
                    onDelete = { selected = null; clearAsk = sel to true },
                    onClear = { selected = null; clearAsk = sel to false },
                    onLock = if (sel.conversationId != null || sel.userId != null) ({
                        selected = null
                        lockGate.run(lk.codegen.risime.ui.lock.LOCK_CHAT_LABEL, sel.name) { vm.lockChat(sel) }
                    }) else null,
                )
            } else RisiTopBar(
                title = "RisiMe",
                subtitle = connectionLabel(conn),
                brand = true,
                avatar = {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(lk.codegen.risime.R.drawable.brand_mark), null,
                        Modifier.size(34.dp),
                    )
                },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Refresh friends") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (tab == 1 && callRows.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("Clear call log") }, onClick = {
                                menu = false
                                callsUi.askClear = true
                            })
                        }
                        DropdownMenuItem(text = { Text("Invites") }, onClick = {
                            menu = false
                            onInvites()
                        })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = {
                            menu = false
                            onSettings()
                        })
                        // WhatsApp: ⋮ → Chat lock settings ("Hide locked chats", "Secret code"), after the confirmation.
                        DropdownMenuItem(text = { Text(CHAT_LOCK_SETTINGS) }, onClick = {
                            menu = false
                            lockGate.run(lk.codegen.risime.ui.lock.LOCKED_CHATS_TITLE) {
                                vm.openLockedFolder()
                                onChatLockSettings()
                            }
                        })
                        DropdownMenuItem(text = { Text("Log out") }, onClick = {
                            menu = false
                            askLogout = true
                        })
                        DropdownMenuItem(
                            text = { Text(lk.codegen.risime.ui.common.LOGOUT_DELETE_LABEL, color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                askLogoutDelete = true
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            Column(Modifier.navigationBarsPadding(), horizontalAlignment = androidx.compose.ui.Alignment.End, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.md)) {
                if (groupsAvailable && tab == 0) {
                    ExtendedFloatingActionButton(
                        onClick = onNewGroup,
                        icon = { Icon(Icons.Default.Create, null) },
                        text = { Text("New group") },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (tab == 1) {
                    if (!callsUi.selecting) {
                        ExtendedFloatingActionButton(
                            onClick = { callsUi.newCall = true },
                            modifier = Modifier.semantics { contentDescription = "New call" },
                            icon = { Icon(Icons.Default.Call, null) },
                            text = { Text("New call") },
                        )
                    }
                } else if (tab != CALENDAR_TAB) {
                    ExtendedFloatingActionButton(
                        onClick = onAddFriend,
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text("Add friend") },
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = if (tab == CALENDAR_TAB && !calendarOn) 0 else tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chats") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Calls") })
                Tab(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    text = {
                        BadgedBox(badge = {
                            if (friends.incoming.isNotEmpty()) Badge { Text(friends.incoming.size.toString()) }
                        }) { Text("Requests") }
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Requests" + if (friends.incoming.isNotEmpty()) ", ${friends.incoming.size} new" else ""
                    },
                )
                if (calendarOn) Tab(selected = tab == CALENDAR_TAB, onClick = { tab = CALENDAR_TAB }, text = { Text("Calendar") }, modifier = Modifier.semantics { contentDescription = "Calendar" })
            }
            lk.codegen.risime.calls.FullScreenIntentPrompt()
            if (tab == CALENDAR_TAB && calendarOn && calendar != null) {
                lk.codegen.risime.ui.calendar.RisiCalendarTab(calendar)
            } else if (tab == 1) {
                CallsTab(vm, callsUi)
            } else if (tab == 2) {
                RequestsTab(friendsVm, onAddFriend)
            } else {
                LazyColumn(Modifier.fillMaxSize().nestedScroll(pull.connection)) {
                    if (pull.revealed && lockedRows.isNotEmpty() && !lockedHasCode) {
                        item(key = "locked-folder") {
                            LockedFolderEntry(lockedRows.size) {
                                lockGate.run(lk.codegen.risime.ui.lock.LOCKED_CHATS_TITLE) {
                                    vm.openLockedFolder()
                                    pull.reset()
                                    onLockedFolder()
                                }
                            }
                        }
                    }
                    err?.let { e -> item { ErrorState(e, onRetry = vm::refresh) } }
                    if (rows.isEmpty()) {
                        item { EmptyState("No friends yet. Add a friend by phone number.", actionLabel = "Add friend", onAction = onAddFriend) }
                    }
                    items(rows, key = { it.key }) { row ->
                        val isSel = selected?.key == row.key
                        androidx.compose.foundation.layout.Box(
                            if (isSel) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier,
                        ) {
                            ChatRowItem(
                                row,
                                // While selecting, a tap selects that chat instead (one at a time); else it opens.
                                onClick = { if (selected != null) selected = row else row.target?.takeIf { row.openable }?.let(onOpen) },
                                onLongClick = { selected = row },
                            )
                        }
                    }
                }
            }
        }
    }
    CallsOverlays(vm, callsUi, callRows, callBack, onOpen)
    }
}

/** §29 the Calendar tab's index (after Chats, Calls, Requests). */
const val CALENDAR_TAB = 3

private val NO_CALENDAR = kotlinx.coroutines.flow.MutableStateFlow(false)
private val NO_FOCUS = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

/** Full-screen Calls pages over the chat list: call info and New call. */
@Composable
private fun CallsOverlays(vm: ChatsViewModel, ui: CallsUi, rows: List<CallRow>, callBack: (String, Boolean) -> Unit, onOpen: (String) -> Unit) {
    val shown = ui.info
    // The row for the opened group, live (its oldest call id is stable while newer calls join the front).
    val live = shown?.let { s -> rows.firstOrNull { r -> r.group.calls.last().clientMsgId == s.group.calls.last().clientMsgId } }
    androidx.compose.runtime.LaunchedEffect(live == null && shown != null) { if (live == null && shown != null) ui.info = null }
    androidx.activity.compose.BackHandler(enabled = shown != null) { ui.info = null }
    androidx.activity.compose.BackHandler(enabled = ui.newCall) { ui.newCall = false }
    if (live != null) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CallInfoScreen(
                live, nowMs = System.currentTimeMillis(), onBack = { ui.info = null }, onCallBack = callBack,
                onMessage = { ui.info = null; onOpen(live.group.conversationId) },
                onRemove = { ui.info = null; vm.hideCalls(live.group.calls) },
            )
        }
    } else if (ui.newCall) {
        val contacts by vm.callContacts.collectAsStateWithLifecycle()
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            NewCallScreen(contacts, onBack = { ui.newCall = false }, onCall = { conv, video -> ui.newCall = false; callBack(conv, video) })
        }
    }
}

/** The chat list ⋮ entry for the Locked chats settings (WhatsApp's name). */
const val CHAT_LOCK_SETTINGS = "Chat lock settings"

/**
 * WhatsApp's selection bar after a long-press on a chat: back (ends the selection), the count,
 * Delete, and ⋮ with "Lock chat" and "Clear chat".
 */
@Composable
fun ChatSelectionBar(onClose: () -> Unit, onDelete: () -> Unit, onClear: () -> Unit, onLock: (() -> Unit)?) {
    var more by remember { mutableStateOf(false) }
    RisiTopBar(
        title = "1",
        onBack = onClose,
        actions = {
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete chat") }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { more = true }) {
                    Icon(Icons.Default.MoreVert, "More options")
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    onLock?.let { f ->
                        DropdownMenuItem(text = { Text(lk.codegen.risime.ui.lock.LOCK_CHAT_LABEL) }, onClick = { more = false; f() })
                    }
                    DropdownMenuItem(text = { Text("Clear chat") }, onClick = { more = false; onClear() })
                }
            }
        },
    )
}

/** A DM row's preview: "You: …" for mine; a §13.3 marker or other system line as is (shown muted, no ticks). */
fun dmPreview(last: lk.codegen.risime.data.db.LastMessage): String = when {
    last.kind == lk.codegen.risime.data.db.MessageEntity.KIND_SYSTEM -> last.body
    last.kind == lk.codegen.risime.data.db.MessageEntity.KIND_DELETED -> last.body // §15.6 tombstone text (forPreview)
    last.kind == lk.codegen.risime.data.db.MessageEntity.KIND_CALL -> lk.codegen.risime.push.bodyPreview(last.kind, last.body) // §16.6, no "You:"
    last.outgoing -> "You: " + lk.codegen.risime.push.bodyPreview(last.kind, last.body)
    else -> lk.codegen.risime.push.bodyPreview(last.kind, last.body)
}

fun connectionLabel(s: ConnectionState): String? = when (s) {
    ConnectionState.Live -> null
    ConnectionState.Syncing -> "Syncing…"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Disconnected -> "Waiting for network…"
    ConnectionState.AuthFailed -> "Signed out"
}

/** §24.9 the small tab icon before a chat's last message (only while tabs are on and the chat has an Official tab). */
fun tabIcon(tab: lk.codegen.risime.data.tabs.Tab?): String = when (tab) {
    lk.codegen.risime.data.tabs.Tab.PRIVATE -> "🔒 "
    lk.codegen.risime.data.tabs.Tab.OFFICIAL -> "● "
    null -> ""
}

@Composable
internal fun ChatRowItem(row: ChatRow, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val presence = presenceLabel(row.presence, System.currentTimeMillis())
    if (row.risi) return RisiRowItem(row, onClick)
    if (row.group) return GroupRowItem(row, onClick, onLongClick)
    val sub = when {
        !row.friend -> "Not friends any more"
        !row.registered -> "Waiting for them to confirm their phone"
        row.typing -> TYPING_LABEL
        row.last != null -> tabIcon(row.lastTab) + dmPreview(row.last)
        presence != null -> presence
        else -> row.company
    }
    ListRow(
        title = row.name,
        subtitle = sub,
        leading = { InitialsAvatar(row.name, enabled = row.registered, online = row.presence?.online == true, photoKey = row.userId) },
        meta = row.last?.let { shortStamp(it.localTs) },
        strong = row.unread > 0,
        enabled = row.openable,
        subtitleColor = when {
            row.typing -> MaterialTheme.colorScheme.primary
            row.last?.kind == lk.codegen.risime.data.db.MessageEntity.KIND_SYSTEM -> lk.codegen.risime.ui.theme.RisiTheme.colors.textMuted
            else -> null
        },
        badge = if (row.unread > 0) ({ UnreadBadge(row.unread) }) else null,
        footer = listOfNotNull(
            presence?.takeIf { !row.typing && row.last != null },
            row.vouchedBy?.let { "vouched by $it" },
        ).joinToString(" · ").ifEmpty { null },
        onClick = onClick,
        onLongClick = onLongClick?.takeIf { row.userId != null && (row.friend || row.last != null) },
    )
}


/** §25.2 the Risi chat's row (Official styling, Risi's avatar); before it exists, "Ask Risi anything". */
@Composable
private fun RisiRowItem(row: ChatRow, onClick: () -> Unit) {
    val last = row.last
    val lastText = last?.let { lk.codegen.risime.push.bodyPreview(it.kind, it.body) }.orEmpty()
    ListRow(
        title = row.name,
        subtitle = when {
            row.stateLine != null && row.stateLine != "Creating…" -> row.stateLine
            last == null || lastText.isEmpty() -> lk.codegen.risime.data.tabs.RISI_CHAT_EMPTY_PREVIEW
            last.outgoing -> "You: $lastText"
            else -> lastText
        },
        leading = { lk.codegen.risime.ui.tabs.RisiAvatar() },
        meta = last?.takeIf { lastText.isNotEmpty() }?.let { shortStamp(it.localTs) },
        strong = row.unread > 0,
        badge = if (row.unread > 0) ({ UnreadBadge(row.unread) }) else null,
        onClick = onClick,
    )
}

/** §12 group row: decrypted name ("Rejoining group…" until the Welcome), "Kamal: …" last line, typing, unread. */
@Composable
private fun GroupRowItem(row: ChatRow, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val last = row.last
    val lastText = last?.let { lk.codegen.risime.push.bodyPreview(it.kind, it.body) }.orEmpty()
    val sub = when {
        row.typingLabel != null -> row.typingLabel
        row.stateLine != null -> row.stateLine
        last == null || lastText.isEmpty() -> "Group"
        last.kind == lk.codegen.risime.data.db.MessageEntity.KIND_DELETED -> tabIcon(row.lastTab) + lastText
        last.outgoing -> tabIcon(row.lastTab) + "You: $lastText"
        row.lastSender != null -> tabIcon(row.lastTab) + "${row.lastSender}: $lastText"
        else -> tabIcon(row.lastTab) + lastText
    }
    ListRow(
        title = row.name,
        subtitle = sub,
        leading = { InitialsAvatar(row.name, photoKey = row.conversationId) },
        meta = last?.takeIf { lastText.isNotEmpty() }?.let { shortStamp(it.localTs) },
        strong = row.unread > 0,
        subtitleColor = when {
            row.typingLabel != null -> MaterialTheme.colorScheme.primary
            last?.kind == lk.codegen.risime.data.db.MessageEntity.KIND_SYSTEM -> lk.codegen.risime.ui.theme.RisiTheme.colors.textMuted
            else -> null
        },
        badge = if (row.unread > 0) ({ UnreadBadge(row.unread) }) else null,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}
