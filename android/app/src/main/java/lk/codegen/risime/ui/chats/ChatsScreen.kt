package lk.codegen.risime.ui.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import lk.codegen.risime.ui.theme.Sizes
import lk.codegen.risime.ui.theme.Spacing

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
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val groupsAvailable by vm.groupsAvailable.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val err by vm.refreshError.collectAsStateWithLifecycle()
    val friends by vm.friendsState.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var askLogout by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    if (askLogout) {
        lk.codegen.risime.ui.common.LogoutConfirmDialog(
            onConfirm = { confirmed ->
                askLogout = false
                vm.logout(confirmed)
            },
            onDismiss = { askLogout = false },
        )
    }
    Scaffold(
        topBar = {
            RisiTopBar(
                title = "RisiMe",
                subtitle = connectionLabel(conn),
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Refresh friends") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Invites") }, onClick = {
                            menu = false
                            onInvites()
                        })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = {
                            menu = false
                            onSettings()
                        })
                        DropdownMenuItem(text = { Text("Log out") }, onClick = {
                            menu = false
                            askLogout = true
                        })
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.md)) {
                if (groupsAvailable && tab == 0) {
                    ExtendedFloatingActionButton(
                        onClick = onNewGroup,
                        icon = { Icon(Icons.Default.Create, null) },
                        text = { Text("New group") },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                ExtendedFloatingActionButton(
                    onClick = onAddFriend,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add friend") },
                )
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chats") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = {
                        BadgedBox(badge = {
                            if (friends.incoming.isNotEmpty()) Badge { Text(friends.incoming.size.toString()) }
                        }) { Text("Requests") }
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Requests" + if (friends.incoming.isNotEmpty()) ", ${friends.incoming.size} new" else ""
                    },
                )
            }
            if (tab == 1) {
                RequestsTab(friendsVm, onAddFriend)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    err?.let { e -> item { ErrorState(e, onRetry = vm::refresh) } }
                    if (rows.isEmpty()) {
                        item { EmptyState("No friends yet. Add a friend by phone number.", actionLabel = "Add friend", onAction = onAddFriend) }
                    }
                    items(rows, key = { it.key }) { row ->
                        ChatRowItem(row, onClick = { row.target?.takeIf { row.openable }?.let(onOpen) })
                        HorizontalDivider(
                            Modifier.padding(start = Spacing.lg + Sizes.avatar + Spacing.md + Spacing.xxs),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** A DM row's preview: "You: …" for mine; a §13.3 marker or other system line as is (shown muted, no ticks). */
fun dmPreview(last: lk.codegen.risime.data.db.LastMessage): String = when {
    last.kind == lk.codegen.risime.data.db.MessageEntity.KIND_SYSTEM -> last.body
    last.kind == lk.codegen.risime.data.db.MessageEntity.KIND_DELETED -> last.body // §15.6 tombstone text (forPreview)
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

@Composable
private fun ChatRowItem(row: ChatRow, onClick: () -> Unit) {
    val presence = presenceLabel(row.presence, System.currentTimeMillis())
    if (row.group) return GroupRowItem(row, onClick)
    val sub = when {
        !row.friend -> "Not friends any more"
        !row.registered -> "Waiting for them to confirm their phone"
        row.typing -> TYPING_LABEL
        row.last != null -> dmPreview(row.last)
        presence != null -> presence
        else -> row.company
    }
    ListRow(
        title = row.name,
        subtitle = sub,
        leading = { InitialsAvatar(row.name, enabled = row.registered, online = row.presence?.online == true) },
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
    )
}


/** §12 group row: decrypted name ("Rejoining group…" until the Welcome), "Kamal: …" last line, typing, unread. */
@Composable
private fun GroupRowItem(row: ChatRow, onClick: () -> Unit) {
    val last = row.last
    val lastText = last?.let { lk.codegen.risime.push.bodyPreview(it.kind, it.body) }.orEmpty()
    val sub = when {
        row.typingLabel != null -> row.typingLabel
        row.stateLine != null -> row.stateLine
        last == null || lastText.isEmpty() -> "Group"
        last.kind == lk.codegen.risime.data.db.MessageEntity.KIND_DELETED -> lastText
        last.outgoing -> "You: $lastText"
        row.lastSender != null -> "${row.lastSender}: $lastText"
        else -> lastText
    }
    ListRow(
        title = row.name,
        subtitle = sub,
        leading = { InitialsAvatar(row.name) },
        meta = last?.takeIf { lastText.isNotEmpty() }?.let { shortStamp(it.localTs) },
        strong = row.unread > 0,
        subtitleColor = when {
            row.typingLabel != null -> MaterialTheme.colorScheme.primary
            last?.kind == lk.codegen.risime.data.db.MessageEntity.KIND_SYSTEM -> lk.codegen.risime.ui.theme.RisiTheme.colors.textMuted
            else -> null
        },
        badge = if (row.unread > 0) ({ UnreadBadge(row.unread) }) else null,
        onClick = onClick,
    )
}
