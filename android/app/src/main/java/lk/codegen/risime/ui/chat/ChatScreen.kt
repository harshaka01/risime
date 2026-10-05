package lk.codegen.risime.ui.chat

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.ui.chats.connectionLabel
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.PendingClock
import lk.codegen.risime.ui.common.TYPING_LABEL
import lk.codegen.risime.ui.common.presenceLabel
import lk.codegen.risime.ui.common.timeOf
import lk.codegen.risime.ui.theme.Saffron

@OptIn(ExperimentalMaterial3Api::class)
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
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InitialsAvatar(name, size = 36.dp, online = presence?.online == true)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    typing -> TYPING_LABEL
                                    else -> connectionLabel(conn)
                                        ?: presenceLabel(presence, System.currentTimeMillis())
                                        ?: (peer?.company ?: "")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (typing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.Day -> DaySeparator(item.label)
                        is ChatItem.Msg -> Bubble(item.m, onRetry = vm::retry, onDelete = vm::delete)
                    }
                }
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
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: MessageEntity, onRetry: (String) -> Unit, onDelete: (String) -> Unit) {
    val mine = m.outgoing
    val failed = m.status == MessageStatus.FAILED.name
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
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
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp).combinedClickable(
                onClickLabel = if (failed) "Show retry options" else null,
                onLongClickLabel = "Message options",
                onClick = { if (failed) menu = true },
                onLongClick = { menu = true },
            ),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(m.body, style = MaterialTheme.typography.bodyLarge)
                if (failed) {
                    Text("Not sent — tap to retry or delete", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        timeOf(m.localTs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                    if (mine) {
                        Spacer(Modifier.size(4.dp))
                        Ticks(MessageStatus.valueOf(m.status))
                    }
                }
            }
        }
    }
}

/** PROTOCOL.md §3: pending clock, ✓ sent, ✓✓ delivered, ✓✓ in accent for read. */
@Composable
fun Ticks(status: MessageStatus) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (status) {
        MessageStatus.PENDING -> PendingClock(muted)
        MessageStatus.SENT -> Icon(Icons.Default.Check, "Sent", Modifier.size(15.dp), tint = muted)
        MessageStatus.DELIVERED -> DoubleCheck(muted, "Delivered")
        MessageStatus.READ -> DoubleCheck(Saffron, "Read")
        MessageStatus.FAILED -> Icon(Icons.Default.Warning, "Not sent", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DoubleCheck(tint: Color, label: String) {
    Box(Modifier.size(width = 20.dp, height = 15.dp).semantics { contentDescription = label }) {
        Icon(Icons.Default.Check, null, Modifier.size(15.dp), tint = tint)
        Icon(Icons.Default.Check, null, Modifier.size(15.dp).offset(x = 5.dp), tint = tint)
    }
}

@Composable
private fun InputBar(draft: String, onDraft: (String) -> Unit, onSend: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
            )
            IconButton(onClick = onSend, enabled = draft.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
