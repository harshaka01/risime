package lk.codegen.risime.ui.search

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lk.codegen.risime.ui.common.EmptyState
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.ListRow
import lk.codegen.risime.ui.common.SectionHeader
import lk.codegen.risime.ui.theme.Spacing
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
            if (r.query.isEmpty()) {
                item { EmptyState("Search your contacts and the messages on this device.") }
            } else if (r.contacts.isEmpty() && r.messages.isEmpty()) {
                item { EmptyState("No results for “${r.query}”") }
            }
            if (r.contacts.isNotEmpty()) item { Header("People") }
            items(r.contacts, key = { "c:" + it.phone }) { c ->
                ListRow(
                    title = c.displayName,
                    subtitle = c.company,
                    leading = { InitialsAvatar(c.displayName) },
                    onClick = { c.userId?.let(onOpen) },
                )
            }
            if (r.messages.isNotEmpty()) item { Header("Messages") }
            items(r.messages, key = { "m:" + it.message.clientMsgId }) { h ->
                ListRow(
                    title = h.peerName,
                    subtitle = (if (h.message.outgoing) "You: " else "") + lk.codegen.risime.push.bodyPreview(h.message.kind, h.message.body),
                    leading = { InitialsAvatar(h.peerName) },
                    meta = shortStamp(h.message.localTs),
                    onClick = { onOpen(h.peerId) },
                )
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    SectionHeader(text, Modifier.padding(start = Spacing.lg, top = Spacing.lg, bottom = Spacing.xs))
}
