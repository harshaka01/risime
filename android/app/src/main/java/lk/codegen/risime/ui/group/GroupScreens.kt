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
import lk.codegen.risime.ui.common.ChatMessageList
import lk.codegen.risime.ui.common.ChatScrollState
import lk.codegen.risime.ui.common.rememberChatScrollState
import lk.codegen.risime.data.groups.systemLine
import lk.codegen.risime.data.groups.systemText
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.ui.chat.ChatItem
import lk.codegen.risime.ui.chat.Composer
import lk.codegen.risime.ui.chat.MessageActionsSheet
import lk.codegen.risime.ui.chat.ReactionChipsRow
import lk.codegen.risime.ui.chat.ReactionsSheet
import lk.codegen.risime.ui.chats.connectionLabel
import lk.codegen.risime.ui.common.DaySeparator
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.MessageBubble
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.common.SectionHeader
import lk.codegen.risime.ui.common.SystemLineText
import lk.codegen.risime.ui.common.startsRun
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
    val composer by vm.composer.collectAsStateWithLifecycle()
    val reactions by vm.reactions.collectAsStateWithLifecycle()
    val readBy by vm.readBy.collectAsStateWithLifecycle()
    val selection by vm.del.selected.collectAsStateWithLifecycle()
    var clearAsk by remember { mutableStateOf<Boolean?>(null) }
    var reactionsFor by remember { mutableStateOf<String?>(null) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val scroll = rememberChatScrollState()
    val media by vm.imgs.media.collectAsStateWithLifecycle()
    val imagesReady by vm.imgs.imagesReady.collectAsStateWithLifecycle()
    val toast by vm.imgs.toast.collectAsStateWithLifecycle()
    val uploads by vm.imgs.uploads.collectAsStateWithLifecycle()
    // §14.1: groups may send while not ready; the sheet says who can't see photos yet.
    val pickPhoto = lk.codegen.risime.ui.chat.rememberImageLayer(
        vm.imgs, messages, groupNotice = if (imagesReady == false) lk.codegen.risime.ui.chat.GROUP_IMAGES_NOTICE else null,
    )

    LaunchedEffect(messages, resumed) {
        if (resumed && messages.any { !it.outgoing && it.status != MessageStatus.READ.name }) vm.markRead()
    }

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
                    if (encrypted == true) append("🔒 ")
                    append(if (count == 1) "1 member" else "$count members")
                },
                emphasis = typingLabel != null,
                onBack = onBack,
                avatar = { InitialsAvatar(name, size = Sizes.avatarSmall + 4.dp, photoKey = vm.conversationId) },
                onTitleClick = onInfo,
                titleClickLabel = "Group info",
                actions = {
                    lk.codegen.risime.ui.chat.E2eeHeaderLock(encrypted == true, onInfo)
                    lk.codegen.risime.ui.chat.ChatOverflowMenu(onClear = { clearAsk = false }, onDelete = { clearAsk = true })
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            lk.codegen.risime.ui.chat.SelectionBarFor(vm.del, messages, selection)
            lk.codegen.risime.ui.chat.DeleteHost(vm.del, clearAsk, onClearAskDone = { clearAsk = null }, onDeletedChat = onBack)
            if (encrypted == false && !readOnly && composer == GroupComposer.Enabled) {
                Text(
                    "${lk.codegen.risime.data.mls.NOT_E2EE_PREFIX}: setting up end-to-end encryption…",
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GroupMessageList(
                messages = messages,
                meId = meId,
                nameOf = vm::nameOf,
                memberName = { names[it.lowercase()] },
                readOnly = readOnly,
                reactions = reactions,
                onReact = vm::react,
                onOpenReactions = { reactionsFor = it },
                onRetry = vm::retry,
                onDelete = vm::delete,
                onInfo = vm::openReadBy,
                scroll = scroll,
                historyMarker = if (lk.codegen.risime.BuildConfig.HISTORY_SHARE_ENABLED) ({ m ->
                    val st by vm.historyMarker.collectAsStateWithLifecycle()
                    lk.codegen.risime.ui.history.HistoryMarkerRow(m.body, st, vm.conversationId, meId, null, vm::requestHistory, vm::escalateHistory)
                }) else null,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                media = media,
                loader = vm.imgs.loader,
                onImageTap = vm.imgs::tap,
                onImageVisible = vm.imgs::onVisible,
                del = vm.del,
                selection = selection,
                uploads = uploads,
            )
            toast?.let { lk.codegen.risime.ui.chat.ImageToast(it) }
            val off = composer as? GroupComposer.Disabled
            if (off != null) {
                Surface(tonalElevation = 2.dp) {
                    Text(
                        off.reason,
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.lg),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Composer(
                    attach = if (!vm.imgs.available || encrypted != true) null else ({
                        lk.codegen.risime.ui.chat.AttachButton(enabled = true) {
                            vm.imgs.refreshImagesReady()
                            pickPhoto()
                        }
                    }),
                    placeholder = if (encrypted == true) "Encrypted message" else "Message",
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

/** The group chat's message list: the shared [ChatMessageList] (same scroll rules as a DM) with group rows. */
@Composable
internal fun GroupMessageList(
    messages: List<MessageEntity>,
    meId: String,
    nameOf: (String) -> String,
    memberName: (String) -> String?,
    readOnly: Boolean,
    reactions: Map<String, List<ReactionChip>>,
    onReact: (messageId: String, emoji: String, op: String) -> Unit,
    onOpenReactions: (String?) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onInfo: (String) -> Unit,
    modifier: Modifier = Modifier,
    scroll: ChatScrollState = rememberChatScrollState(),
    media: Map<String, lk.codegen.risime.data.db.MediaEntity> = emptyMap(),
    loader: lk.codegen.risime.ui.chat.ImageLoader? = null,
    onImageTap: (MessageEntity) -> Unit = {},
    onImageVisible: (String) -> Unit = {},
    /** §15.7 select/delete (null: off). */
    del: lk.codegen.risime.ui.chat.DeleteController? = null,
    selection: Set<String> = emptySet(),
    /** Upload progress of this phone's photos going out (client_msg_id → 0..1). */
    uploads: Map<String, Float> = emptyMap(),
    /** §17.12 the gap marker with "Request history" (null: a plain line, the feature off). */
    historyMarker: (@Composable (MessageEntity) -> Unit)? = null,
) {
    ChatMessageList(
        messages = messages,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
        spacing = Spacing.xxs,
        scroll = scroll,
    ) { items, i ->
        when (val item = items[i]) {
            is ChatItem.Day -> DaySeparator(item.label)
            is ChatItem.Msg -> if (item.m.system && historyMarker != null && item.m.clientMsgId == lk.codegen.risime.data.HistoryMarkers.historyId(item.m.conversationId)) {
                historyMarker(item.m)
            } else if (item.m.system) {
                val line = item.m.systemLine()
                // §17.12 local history lines keep their stored text (it changes with imports).
                SystemLineText(line?.takeIf { it.action !in lk.codegen.risime.data.groups.SystemLine.LOCAL_ACTIONS }?.let { l -> systemText(l, meId) { memberName(it) ?: "Someone" } } ?: item.m.body)
            } else if (item.m.showsAsDeleted) {
                // §15.5 crypto S2: a server-placed tombstone shows no sender unless the sender deleted it.
                val placed = item.m.clientMsgId.startsWith(lk.codegen.risime.data.deletes.DeleteApplier.PLACEHOLDER)
                val attributed = !placed || item.m.deletedBy.equals(item.m.from, true)
                val s = del?.selectFor(item.m, selection)
                lk.codegen.risime.ui.chat.TombstoneBubble(
                    item.m, meId, sender = if (attributed && showSenderAt(items, i)) nameOf(item.m.from) else null, selected = s?.selected == true,
                    onMenu = s?.let { x -> { if (x.selecting) x.onToggle() else x.onDelete() } }, onTap = s?.takeIf { it.selecting }?.onToggle,
                    tail = startsRun(items, i),
                )
            } else {
                GroupBubble(
                    item.m,
                    sender = if (showSenderAt(items, i)) nameOf(item.m.from) else null,
                    canAct = !readOnly,
                    chips = item.m.messageId?.let { reactions[it] }.orEmpty(),
                    onReact = { e, op -> item.m.messageId?.let { onReact(it, e, op) } },
                    onOpenReactions = { onOpenReactions(item.m.messageId) },
                    onRetry = onRetry, onDelete = onDelete,
                    onInfo = { item.m.messageId?.let(onInfo) },
                    media = media[item.m.clientMsgId], loader = loader,
                    onImageTap = { onImageTap(item.m) }, onImageVisible = onImageVisible,
                    sel = del?.selectFor(item.m, selection),
                    upload = uploads[item.m.clientMsgId],
                    sharedBy = lk.codegen.risime.ui.history.sharedByLabel(item.m, nameOf),
                    tail = startsRun(items, i),
                )
            }
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
    media: lk.codegen.risime.data.db.MediaEntity? = null,
    loader: lk.codegen.risime.ui.chat.ImageLoader? = null,
    onImageTap: () -> Unit = {},
    onImageVisible: (String) -> Unit = {},
    sharedBy: String? = null,
    sel: lk.codegen.risime.ui.chat.MsgSelect? = null,
    upload: Float? = null,
    tail: Boolean = true,
) {
    val failed = m.status == MessageStatus.FAILED.name
    var sheet by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val retryable = failed && canAct && m.failReason != AuthErrors.NOT_MEMBER && m.failReason != AuthErrors.TOO_LONG
    val send = if (m.image) lk.codegen.risime.ui.chat.photoSendState(m, media, upload) else null
    // The photo's tap target: a failed photo (when a retry can help) or one waiting in its backoff.
    val retryPhoto: (() -> Unit)? = if (canAct && (retryable || !failed) && (send is lk.codegen.risime.ui.chat.PhotoSend.Failed || send == lk.codegen.risime.ui.chat.PhotoSend.Waiting)) ({ onRetry(m.clientMsgId) }) else null
    MessageBubble(
        body = m.body,
        time = timeOf(m.localTs),
        mine = m.outgoing,
        status = if (m.outgoing) MessageStatus.valueOf(m.status) else null,
        note = when {
            !failed -> if (m.deleteUnverified) lk.codegen.risime.data.deletes.DeleteRules.UNVERIFIED_NOTE else null
            m.failReason == AuthErrors.NOT_MEMBER -> "Not sent — you're not in this group"
            m.failReason == AuthErrors.TOO_LONG -> "Not sent — too long"
            retryable -> "Not sent — tap to retry or delete"
            else -> "Not sent — tap to delete"
        },
        tapOpensMenu = failed && sel?.selecting != true,
        onMenu = { if (sel?.selecting == true) sel.onToggle() else sheet = true },
        selected = sel?.selected == true,
        noteIsInfo = !failed,
        footer = { ReactionChipsRow(chips, onOpenReactions) },
        sender = sender,
        senderColor = memberColor(m.from),
        image = if (m.image) ({ lk.codegen.risime.ui.chat.ImageBubbleContent(media, loader, onImageVisible, send = send, onRetry = retryPhoto) }) else null,
        imageStatus = if (m.image) send?.let { lk.codegen.risime.ui.chat.photoSendText(it) } ?: lk.codegen.risime.ui.chat.imageStatusText(media) else null,
        imageAction = retryPhoto?.let { lk.codegen.risime.ui.chat.PHOTO_RETRY to it },
        onTap = if (sel?.selecting == true) sel.onToggle else if (m.image) onImageTap else null,
        tapLabel = if (m.image) lk.codegen.risime.ui.chat.imageTapLabel(lk.codegen.risime.ui.chat.imageTap(media)) else null,
        tail = tail,
    )
    if (sheet) {
        val actions = buildList<Pair<String, () -> Unit>> {
            if (!m.image || m.body.isNotBlank()) add("Copy" to { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", m.body))) } })
            if (m.outgoing && m.messageId != null) add("Info" to onInfo)
            if (retryable) add("Retry" to { onRetry(m.clientMsgId) })
            if (failed) add((if (sel != null) "Discard" else "Delete") to { onDelete(m.clientMsgId) })
            if (sel != null && !failed && canAct) {
                add("Delete" to sel.onDelete)
                add("Select" to sel.onSelect)
            }
            if (m.image && m.outgoing && m.status == MessageStatus.PENDING.name) add("Cancel" to { onDelete(m.clientMsgId) })
        }
        MessageActionsSheet(
            canReact = canAct && m.messageId != null,
            myReactions = chips.filter { it.mine }.map { it.emoji }.toSet(),
            onReact = onReact, actions = actions, onDismiss = { sheet = false }, info = sharedBy,
        )
    }
}

// ---- Group info ----

@Composable
fun GroupInfoScreen(vm: GroupInfoViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pick = lk.codegen.risime.ui.common.rememberPhotoCropper("Move and scale", onCropped = vm::setPhoto, onError = vm::photoError)
    GroupInfoContent(
        ui, onBack = onBack, onAdd = vm::add, onRemove = vm::remove, onSetAdmin = vm::setRole, onRename = vm::rename,
        onLeave = vm::leave, onReset = vm::reset, onDismissError = vm::dismissError,
        onSetPhoto = pick.takeIf { c -> ui.iAmAdmin && !ui.readOnly }, onRemovePhoto = vm::removePhoto,
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
    /** §18.7 admins only: tap the photo → set/change (picker and crop); null = no edit control. */
    onSetPhoto: (() -> Unit)? = null,
    onRemovePhoto: () -> Unit = {},
) {
    var photoMenu by remember { mutableStateOf(false) }
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
                    Box {
                        Box(
                            if (onSetPhoto != null) Modifier.clickable(onClickLabel = "Change group photo") { if (ui.hasPhoto) photoMenu = true else onSetPhoto() } else Modifier,
                        ) { InitialsAvatar(ui.name, size = Sizes.avatarLarge, photoKey = ui.conversationId) }
                        androidx.compose.material3.DropdownMenu(photoMenu, onDismissRequest = { photoMenu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Change photo") }, onClick = { photoMenu = false; onSetPhoto?.invoke() })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Remove photo") }, onClick = { photoMenu = false; onRemovePhoto() })
                        }
                    }
                    ui.photoBusy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.heightIn(min = Spacing.sm))
                    Text(ui.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        ui.stateLine ?: if (ui.memberCount == 1) "1 member" else "${ui.memberCount} members",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // §12.0: groups are E2EE-only (decision 048: chat info states it).
                    lk.codegen.risime.ui.chat.E2eeInfoLine(encrypted = true, notEncryptedText = null)
                    if (ui.photosNeedUpdate.isNotEmpty()) {
                        Text(
                            "Can't see photos yet: " + ui.photosNeedUpdate.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
            InitialsAvatar(m.name, photoKey = m.userId)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(if (m.me) "${m.name} (you)" else m.name, maxLines = 1)
                when (m.state) {
                    GroupMember.STATE_PENDING_ADD -> Text("Adding…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GroupMember.STATE_PENDING_REMOVE -> Text("Removing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> if (m.newPhone) Text("${m.name}'s new phone is being added", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
