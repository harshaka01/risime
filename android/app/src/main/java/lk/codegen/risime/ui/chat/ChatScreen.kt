package lk.codegen.risime.ui.chat

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.ui.chats.connectionLabel
import lk.codegen.risime.ui.common.DaySeparator
import lk.codegen.risime.ui.common.EmptyState
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.MessageBubble
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.common.TYPING_LABEL
import lk.codegen.risime.ui.common.presenceLabel
import lk.codegen.risime.ui.common.timeOf
import lk.codegen.risime.ui.theme.RisiShapes
import lk.codegen.risime.ui.theme.Sizes
import lk.codegen.risime.ui.theme.Spacing

@Composable
fun ChatScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val peer by vm.peer.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val presence by vm.peerPresence.collectAsStateWithLifecycle()
    val typing by vm.peerTyping.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val listState = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }

    // Read acks only while this chat is actually on screen.
    LaunchedEffect(messages, resumed) {
        if (resumed && messages.any { !it.outgoing && it.status != MessageStatus.READ.name }) vm.markRead()
    }
    val items = remember(messages) { withDaySeparators(messages, System.currentTimeMillis()) }
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }

    val name = peer?.displayName ?: "Chat"
    Scaffold(
        topBar = {
            RisiTopBar(
                title = name,
                subtitle = when {
                    typing -> TYPING_LABEL
                    else -> connectionLabel(conn)
                        ?: presenceLabel(presence, System.currentTimeMillis())
                        ?: (peer?.company ?: "")
                },
                emphasis = typing,
                onBack = onBack,
                avatar = { InitialsAvatar(name, size = Sizes.avatarSmall, online = presence?.online == true) },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs + Spacing.xxs),
            ) {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.Day -> DaySeparator(item.label)
                        is ChatItem.Msg -> Bubble(item.m, onRetry = vm::retry, onDelete = vm::delete)
                    }
                }
                if (messages.isEmpty()) item { EmptyState("No messages yet. Say hello!") }
            }
            InputBar(
                draft = draft,
                onDraft = {
                    if (it.length <= 4096) {
                        draft = it
                        vm.onDraftChanged(it)
                    }
                },
                onSend = {
                    if (draft.isNotBlank()) {
                        vm.send(draft)
                        draft = ""
                    }
                },
            )
        }
    }
}

@Composable
private fun Bubble(m: MessageEntity, onRetry: (String) -> Unit, onDelete: (String) -> Unit) {
    val failed = m.status == MessageStatus.FAILED.name
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    MessageBubble(
        body = m.body,
        time = timeOf(m.localTs),
        mine = m.outgoing,
        status = if (m.outgoing) MessageStatus.valueOf(m.status) else null,
        note = if (failed) "Not sent — tap to retry or delete" else null,
        tapOpensMenu = failed,
        onMenu = { menu = true },
        menu = {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy") }, onClick = {
                    menu = false
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", m.body))) }
                })
                if (failed) {
                    DropdownMenuItem(text = { Text("Retry") }, onClick = {
                        menu = false
                        onRetry(m.clientMsgId)
                    })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = {
                        menu = false
                        onDelete(m.clientMsgId)
                    })
                }
            }
        },
    )
}

@Composable
private fun InputBar(draft: String, onDraft: (String) -> Unit, onSend: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.sm, vertical = Spacing.xs + Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                shape = RisiShapes.input,
            )
            IconButton(onClick = onSend, enabled = draft.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
