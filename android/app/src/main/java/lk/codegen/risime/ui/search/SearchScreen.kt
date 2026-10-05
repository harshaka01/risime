package lk.codegen.risime.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.shortStamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: SearchViewModel, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val q by vm.query.collectAsStateWithLifecycle()
    val r by vm.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    TextField(
                        value = q,
                        onValueChange = vm::onQuery,
                        placeholder = { Text("Search chats and people") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            if (r.query.isNotEmpty() && r.contacts.isEmpty() && r.messages.isEmpty()) {
                item {
                    Text("No results for “${r.query}”", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (r.contacts.isNotEmpty()) item { Header("People") }
            items(r.contacts, key = { "c:" + it.phone }) { c ->
                ResultRow(c.displayName, c.company, null, onClick = { c.userId?.let(onOpen) })
            }
            if (r.messages.isNotEmpty()) item { Header("Messages") }
            items(r.messages, key = { "m:" + it.message.clientMsgId }) { h ->
                ResultRow(
                    h.peerName,
                    (if (h.message.outgoing) "You: " else "") + h.message.body,
                    shortStamp(h.message.localTs),
                    onClick = { onOpen(h.peerId) },
                )
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ResultRow(title: String, subtitle: String, stamp: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsAvatar(title, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        stamp?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
