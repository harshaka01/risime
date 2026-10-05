package lk.codegen.risime.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lk.codegen.risime.realtime.ConnectionState
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.shortStamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(vm: ChatsViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val err by vm.refreshError.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RisiMe", fontWeight = FontWeight.Bold)
                        connectionLabel(conn)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Refresh contacts") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            err?.let { e ->
                item {
                    Text(e, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (rows.isEmpty()) {
                item {
                    Text(
                        "No contacts yet. Pull them with the refresh button.",
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rows, key = { it.userId ?: it.name }) { row ->
                ChatRowItem(row, onClick = { row.userId?.takeIf { row.registered }?.let(onOpen) })
                HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.surfaceVariant)
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
    val dim = !row.registered
    val main = if (dim) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(enabled = row.registered, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsAvatar(row.name, enabled = row.registered)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, color = main, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                row.last?.let {
                    Text(shortStamp(it.localTs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val sub = when {
                dim -> "${row.company} · not on RisiMe yet"
                row.last != null -> (if (row.last.outgoing) "You: " else "") + row.last.body
                else -> row.company
            }
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
