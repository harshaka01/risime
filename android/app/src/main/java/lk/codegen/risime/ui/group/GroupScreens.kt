package lk.codegen.risime.ui.group

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.ReactionChip
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.groups.groupDisplayName
import lk.codegen.risime.data.groups.systemLine
import lk.codegen.risime.data.groups.systemText
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.ui.chat.ChatItem
import lk.codegen.risime.ui.chat.Composer
import lk.codegen.risime.ui.chat.MessageActionsSheet
import lk.codegen.risime.ui.chat.ReactionChipsRow
import lk.codegen.risime.ui.chat.ReactionsSheet
import lk.codegen.risime.ui.chat.withDaySeparators
import lk.codegen.risime.ui.chats.connectionLabel
import lk.codegen.risime.ui.common.DaySeparator
import lk.codegen.risime.ui.common.EmptyState
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.MessageBubble
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.common.SectionHeader
import lk.codegen.risime.ui.common.SystemLineText
import lk.codegen.risime.ui.common.memberColor
import lk.codegen.risime.ui.common.timeOf
import lk.codegen.risime.ui.chats.groupTypingLabel
import lk.codegen.risime.ui.theme.Sizes
import lk.codegen.risime.ui.theme.Spacing

// ---- Create group ----

@Composable
fun CreateGroupScreen(vm: CreateGroupViewModel, onCreated: (String) -> Unit, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val created by vm.created.collectAsStateWithLifecycle()
    LaunchedEffect(created) { created?.let(onCreated) }
    val back = { if (!vm.onBackStep()) onBack() }
    BackHandler(onBack = back)
    CreateGroupContent(ui, vm::onQuery, vm::onToggle, vm::onNext, vm::onName, vm::onCreate, back)
}

// ---- Group chat ----

/** Show the sender above an incoming bubble only at the start of a run by the same sender. */
fun showSenderAt(items: List<ChatItem>, index: Int): Boolean {
    val m = (items[index] as? ChatItem.Msg)?.m ?: return false
    if (m.outgoing || m.system) return false
    val prev = (items.getOrNull(index - 1) as? ChatItem.Msg)?.m ?: return true
    return prev.system || prev.outgoing || !prev.from.equals(m.from, true)
}

@Composable
fun GroupChatScreen(vm: GroupChatViewModel, meId: String, onBack: () -> Unit, onInfo: () -> Unit) {
    val group by vm.group.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val typing by vm.typingNames.collectAsStateWithLifecycle()
    val encrypted by vm.encrypted.collectAsStateWithLifecycle()
    val reactions by vm.reactions.collectAsStateWithLifecycle()
    val readBy by vm.readBy.collectAsStateWithLifecycle()
    var reactionsFor by remember { mutableStateOf<String?>(null) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages, resumed) {
        if (resumed && messages.any { !it.outgoing && it.status != MessageStatus.READ.name }) vm.markRead()
    }
    val items = remember(messages) { withDaySeparators(messages, System.currentTimeMillis()) }

    val name = groupDisplayName(group?.name)
    val readOnly = group?.readOnly == true
    val names = members.associate { it.userId.lowercase() to it.displayName }
    val typingLabel = groupTypingLabel(typing)
    val count = members.count { it.current && it.state != GroupMember.STATE_PENDING_ADD }
    Scaffold(
        topBar = {
            RisiTopBar(
                title = name,
                subtitle = typingLabel ?: connectionLabel(conn) ?: buildString {
                    if (encrypted) append("🔒 ")
                    append(if (count == 1) "1 member" else "$count members")
                },
                emphasis = typingLabel != null,
                onBack = onBack,
                avatar = { Box(Modifier.clickable(onClickLabel = "Group info", onClick = onInfo)) { InitialsAvatar(name, size = Sizes.avatarSmall) } },
                actions = { IconButton(onClick = onInfo) { Icon(Icons.Default.Info, "Group info") } },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            if (!encrypted && !readOnly) {
                Text(
                    "Setting up end-to-end encryption…",
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lk.codegen.risime.ui.common.ChatMessageList(
                count = items.size,
                lastKey = messages.lastOrNull()?.clientMsgId,
                lastOutgoing = messages.lastOrNull()?.outgoing == true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
                spacing = Spacing.xs + Spacing.xxs,
                state = listState,
            ) {
                items(items.size, key = { items[it].key }) { i ->
                    when (val item = items[i]) {
                        is ChatItem.Day -> DaySeparator(item.label)
                        is ChatItem.Msg -> if (item.m.system) {
                            val line = item.m.systemLine()
                            SystemLineText(line?.let { l -> systemText(l, meId) { names[it.lowercase()] ?: "Someone" } } ?: item.m.body)
                        } else {
                            GroupBubble(
                                item.m,
                                sender = if (showSenderAt(items, i)) vm.nameOf(item.m.from) else null,
                                canAct = !readOnly,
                                chips = item.m.messageId?.let { reactions[it] }.orEmpty(),
                                onReact = { e, op -> item.m.messageId?.let { vm.react(it, e, op) } },
                                onOpenReactions = { reactionsFor = item.m.messageId },
                                onRetry = vm::retry, onDelete = vm::delete,
                                onInfo = { item.m.messageId?.let(vm::openReadBy) },
                            )
                        }
                    }
                }
                if (messages.isEmpty()) item { EmptyState("No messages yet. Say hello!") }
            }
            if (readOnly) {
                Surface(tonalElevation = 2.dp) {
                    Text(
                        if (group?.state == "left") "You left this group." else "You were removed from this group.",
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.lg),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Composer(
                    placeholder = if (encrypted) "Encrypted message" else "Message",
                    value = draft,
                    onValue = { draft = it; vm.onDraftChanged(it.text) },
                    onSend = {
                        if (draft.text.isNotBlank()) {
                            vm.send(draft.text)
                            draft = TextFieldValue("")
                        }
                    },
                )
            }
            reactionsFor?.let { target -> ReactionsSheet(reactions[target].orEmpty(), vm::nameOf) { reactionsFor = null } }
            readBy?.let { (mid, state) -> ReadBySheet(state, vm::nameOf, onRetry = { vm.openReadBy(mid) }, onDismiss = vm::closeReadBy) }
        }
    }
}

@Composable
private fun GroupBubble(
    m: MessageEntity,
    sender: String?,
    canAct: Boolean,
    chips: List<ReactionChip>,
    onReact: (String, String) -> Unit,
    onOpenReactions: () -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onInfo: () -> Unit,
) {
    val failed = m.status == MessageStatus.FAILED.name
    var sheet by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val retryable = failed && canAct && m.failReason != AuthErrors.NOT_MEMBER && m.failReason != AuthErrors.TOO_LONG
    MessageBubble(
        body = m.body,
        time = timeOf(m.localTs),
        mine = m.outgoing,
        status = if (m.outgoing) MessageStatus.valueOf(m.status) else null,
        note = when {
            !failed -> null
            m.failReason == AuthErrors.NOT_MEMBER -> "Not sent — you're not in this group"
            m.failReason == AuthErrors.TOO_LONG -> "Not sent — too long"
            retryable -> "Not sent — tap to retry or delete"
            else -> "Not sent — tap to delete"
        },
        tapOpensMenu = failed,
        onMenu = { sheet = true },
        footer = { ReactionChipsRow(chips, onOpenReactions) },
        sender = sender,
        senderColor = memberColor(m.from),
    )
    if (sheet) {
        val actions = buildList<Pair<String, () -> Unit>> {
            add("Copy" to { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", m.body))) } })
            if (m.outgoing && m.messageId != null) add("Info" to onInfo)
            if (retryable) add("Retry" to { onRetry(m.clientMsgId) })
            if (failed) add("Delete" to { onDelete(m.clientMsgId) })
        }
        MessageActionsSheet(
            canReact = canAct && m.messageId != null,
            myReactions = chips.filter { it.mine }.map { it.emoji }.toSet(),
            onReact = onReact, actions = actions, onDismiss = { sheet = false },
        )
    }
}

// ---- Group info ----

@Composable
fun GroupInfoScreen(vm: GroupInfoViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    GroupInfoContent(
        ui, onBack = onBack, onAdd = vm::add, onRemove = vm::remove, onSetAdmin = vm::setRole, onRename = vm::rename,
        onLeave = vm::leave, onReset = vm::reset, onDismissError = vm::dismissError,
    )
}

/**
 * Header (name, member count), members with an Admin chip and "Adding…"/"Removing…", admin actions
 * (add from friends, rename, remove, make/dismiss admin, reset), and Leave for everyone. Back always works.
 */
@Composable
fun GroupInfoContent(
    ui: GroupInfoUi,
    onBack: () -> Unit,
    onAdd: (Set<String>) -> Unit,
    onRemove: (String) -> Unit,
    onSetAdmin: (String, Boolean) -> Unit,
    onRename: (String) -> Unit,
    onLeave: () -> Unit,
    onReset: () -> Unit,
    onDismissError: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { RisiTopBar(title = "Group info", onBack = onBack) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
            item {
                Column(Modifier.fillMaxWidth().padding(Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
                    InitialsAvatar(ui.name, size = Sizes.avatarLarge)
                    Spacer(Modifier.heightIn(min = Spacing.sm))
                    Text(ui.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        ui.stateLine ?: if (ui.memberCount == 1) "1 member" else "${ui.memberCount} members",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (ui.iAmAdmin) TextButton(onClick = { renaming = true }) { Text("Rename") }
                }
            }
            ui.error?.let { e ->
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
                        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Text(e, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = onDismissError) { Text("OK") }
                        }
                    }
                }
            }
            item { SectionHeader("Members", Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) }
            if (ui.iAmAdmin) {
                item {
                    TextButton(onClick = { adding = true }, modifier = Modifier.padding(horizontal = Spacing.sm).heightIn(min = Sizes.minTouch)) { Text("Add members") }
                }
            }
            items(ui.members, key = { it.userId }) { m -> MemberRow(m, ui.iAmAdmin, onRemove, onSetAdmin) }
            item { HorizontalDivider(Modifier.padding(vertical = Spacing.sm)) }
            if (!ui.readOnly) {
                item {
                    TextButton(onClick = { confirmLeave = true }, modifier = Modifier.padding(horizontal = Spacing.sm).heightIn(min = Sizes.minTouch)) {
                        Text("Leave group", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (ui.iAmAdmin) {
                item {
                    TextButton(onClick = { confirmReset = true }, modifier = Modifier.padding(horizontal = Spacing.sm).heightIn(min = Sizes.minTouch)) {
                        Text("Reset encryption")
                    }
                }
            }
        }
    }
    if (confirmLeave) {
        ConfirmDialog("Leave group?", "You'll stop getting messages from ${ui.name}. The chat stays on this phone, read-only.", "Leave", onLeave) { confirmLeave = false }
    }
    if (confirmReset) {
        ConfirmDialog(
            "Reset encryption?", "Use this only if messages in this group keep failing. Everyone's phone sets up the group's encryption again; some messages may be missing.",
            "Reset", onReset,
        ) { confirmReset = false }
    }
    if (renaming) RenameDialog(ui.name, onRename) { renaming = false }
    if (adding) AddMembersDialog(ui.addCandidates, onAdd) { adding = false }
}

@Composable
private fun MemberRow(m: MemberUi, iAmAdmin: Boolean, onRemove: (String) -> Unit, onSetAdmin: (String, Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val canManage = iAmAdmin && !m.me && m.state == GroupMember.STATE_ACTIVE
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Sizes.listRowMin)
                .then(if (canManage) Modifier.clickable(onClickLabel = "Member options") { menu = true } else Modifier)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InitialsAvatar(m.name)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(if (m.me) "${m.name} (you)" else m.name, maxLines = 1)
                when (m.state) {
                    GroupMember.STATE_PENDING_ADD -> Text("Adding…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GroupMember.STATE_PENDING_REMOVE -> Text("Removing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (m.admin) AssistChip(onClick = {}, label = { Text("Admin") }, enabled = false)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (m.admin) "Dismiss as admin" else "Make admin") },
                onClick = { menu = false; onSetAdmin(m.userId, !m.admin) },
            )
            DropdownMenuItem(text = { Text("Remove ${m.name}") }, onClick = { menu = false; onRemove(m.userId) })
        }
    }
}

@Composable
private fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group name") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onRename(name); onDismiss() }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddMembersDialog(candidates: List<PickFriend>, onAdd: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add members") },
        text = {
            Box(Modifier.heightIn(max = 420.dp)) {
                FriendPicker(candidates, selected, query, { query = it }, { id -> selected = if (id in selected) selected - id else selected + id },
                    emptyText = "All your friends are already in this group.")
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(selected); onDismiss() }, enabled = selected.isNotEmpty()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
