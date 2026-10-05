package lk.codegen.risime.ui.chats

import androidx.compose.foundation.layout.WindowInsets
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
fun ChatsScreen(vm: ChatsViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit, onSearch: () -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val err by vm.refreshError.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            RisiTopBar(
                title = "RisiMe",
                subtitle = connectionLabel(conn),
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Refresh contacts") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Settings") }, onClick = {
                            menu = false
                            onSettings()
                        })
                        DropdownMenuItem(text = { Text("Log out") }, onClick = {
                            menu = false
                            vm.logout()
                        })
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            err?.let { e -> item { ErrorState(e, onRetry = vm::refresh) } }
            if (rows.isEmpty()) {
                item { EmptyState("No contacts yet.", actionLabel = "Refresh", onAction = vm::refresh) }
            }
            items(rows, key = { it.userId ?: it.name }) { row ->
                ChatRowItem(row, onClick = { row.userId?.takeIf { row.registered }?.let(onOpen) })
                HorizontalDivider(
                    Modifier.padding(start = Spacing.lg + Sizes.avatar + Spacing.md + Spacing.xxs),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
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
    val sub = when {
        !row.registered -> "${row.company} · not on RisiMe yet"
        row.typing -> TYPING_LABEL
        row.last != null -> (if (row.last.outgoing) "You: " else "") + row.last.body
        presence != null -> presence
        else -> row.company
    }
    ListRow(
        title = row.name,
        subtitle = sub,
        leading = { InitialsAvatar(row.name, enabled = row.registered, online = row.presence?.online == true) },
        meta = row.last?.let { shortStamp(it.localTs) },
        strong = row.unread > 0,
        enabled = row.registered,
        subtitleColor = if (row.typing) MaterialTheme.colorScheme.primary else null,
        badge = if (row.unread > 0) ({ UnreadBadge(row.unread) }) else null,
        footer = presence?.takeIf { !row.typing && row.last != null },
        onClick = onClick,
    )
}

