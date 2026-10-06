package lk.codegen.risime.ui.chat

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.material3.TextButton
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.ui.chats.connectionLabel
import lk.codegen.risime.ui.common.DaySeparator
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
    val requested by vm.requested.collectAsStateWithLifecycle()
    val e2ee by vm.e2ee.collectAsStateWithLifecycle()
    val encrypted = e2ee is lk.codegen.risime.data.mls.E2eeState.Encrypted
    val typing by vm.peerTyping.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val scroll = lk.codegen.risime.ui.common.rememberChatScrollState()
    var draftValue by rememberSaveable(stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    val reactions by vm.reactions.collectAsStateWithLifecycle()
    val selection by vm.del.selected.collectAsStateWithLifecycle()
    var clearAsk by remember { mutableStateOf<Boolean?>(null) }
    var reactionsFor by remember { mutableStateOf<String?>(null) }
    val media by vm.imgs.media.collectAsStateWithLifecycle()
    val imagesReady by vm.imgs.imagesReady.collectAsStateWithLifecycle()
    val missingIsMe by vm.imgs.missingIsMe.collectAsStateWithLifecycle()
    val toast by vm.imgs.toast.collectAsStateWithLifecycle()
    val pickPhoto = rememberImageLayer(vm.imgs, messages)

    // Read acks only while this chat is actually on screen.
    LaunchedEffect(messages, resumed) {
        if (resumed && messages.any { !it.outgoing && it.status != MessageStatus.READ.name }) vm.markRead()
    }

    val name = peer?.displayName ?: "Chat"
    // Decision 048: the real per-chat state (no global banner).
    val stripText = lk.codegen.risime.data.mls.e2eeStripText(
        e2ee, nameOf = { id -> if (id.equals(vm.peerId, true)) name else "Someone" },
        isMe = { id -> id.equals(vm.me, true) }, myDeviceId = vm.myDeviceId,
    )
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) DmChatInfoDialog(name, encrypted, stripText) { showInfo = false }
    // Back on screen: re-check readiness at once (P0-1).
    LaunchedEffect(resumed) { if (resumed && !encrypted) vm.refreshE2ee() }
    // Unknown while loading: assume friend (the server refuses non-friends anyway).
    val isFriend = peer?.friend != false
    Scaffold(
        topBar = {
            RisiTopBar(
                title = name,
                subtitle = when {
                    typing -> TYPING_LABEL
                    else -> connectionLabel(conn)
                        ?: if (encrypted) "🔒 End-to-end encrypted" else null
                        ?: presenceLabel(presence, System.currentTimeMillis())
                        ?: peer?.vouchedByName?.let { "vouched by $it" }
                        ?: (peer?.company ?: "")
                },
                emphasis = typing,
                onBack = onBack,
                avatar = { InitialsAvatar(name, size = Sizes.avatarSmall, online = presence?.online == true) },
                actions = {
                    E2eeHeaderLock(encrypted) { showInfo = true }
                    IconButton(onClick = { showInfo = true }) { Icon(Icons.Default.Info, "Chat info") }
                    ChatOverflowMenu(onClear = { clearAsk = false }, onDelete = { clearAsk = true })
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            SelectionBarFor(vm.del, messages, selection)
            DeleteHost(vm.del, clearAsk, onClearAskDone = { clearAsk = null }, onDeletedChat = onBack)
            E2eeStrip(stripText)
            lk.codegen.risime.ui.common.ChatMessageList(
                messages = messages,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
                spacing = Spacing.xs + Spacing.xxs,
                scroll = scroll,
            ) { items, i ->
                when (val item = items[i]) {
                    is ChatItem.Day -> DaySeparator(item.label)
                    is ChatItem.Msg -> DmMessageRow(item.m) {
                        if (item.m.showsAsDeleted) {
                            val s = vm.del.selectFor(item.m, selection)
                            TombstoneBubble(
                                item.m, vm.me, selected = s?.selected == true,
                                onMenu = s?.let { x -> { if (x.selecting) x.onToggle() else x.onDelete() } },
                                onTap = s?.takeIf { it.selecting }?.onToggle,
                            )
                            return@DmMessageRow
                        }
                        Bubble(
                            item.m, canRetry = isFriend, onRetry = vm::retry, onDelete = vm::delete,
                            media = media[item.m.clientMsgId], loader = vm.imgs.loader,
                            onImageTap = { vm.imgs.tap(item.m) }, onImageVisible = vm.imgs::onVisible,
                            chips = item.m.messageId?.let { reactions[it] }.orEmpty(),
                            canReact = isFriend && item.m.messageId != null,
                            onReact = { e, op -> item.m.messageId?.let { vm.react(it, e, op) } },
                            onOpenReactions = { reactionsFor = item.m.messageId },
                            sel = vm.del.selectFor(item.m, selection),
                        )
                    }
                }
            }
            toast?.let { ImageToast(it) }
            if (!isFriend) {
                NotFriendsBar(name, requested, onAddFriend = vm::requestFriend)
            } else Composer(
                attach = if (!vm.imgs.available) null else ({
                    val blocked = dmImagesBlockedText(encrypted, imagesReady, missingIsMe, name)
                    AttachButton(enabled = blocked == null) {
                        if (blocked == null) {
                            pickPhoto()
                        } else {
                            vm.imgs.toast.value = blocked
                            vm.imgs.refreshImagesReady()
                            if (!encrypted) vm.refreshE2ee()
                        }
                    }
                }),
                placeholder = if (encrypted) "Encrypted message" else "Message",
                value = draftValue,
                onValue = {
                    draftValue = it
                    vm.onDraftChanged(it.text)
                },
                onSend = {
                    if (draftValue.text.isNotBlank()) {
                        vm.send(draftValue.text)
                        draftValue = androidx.compose.ui.text.input.TextFieldValue("")
                    }
                },
            )
            reactionsFor?.let { target ->
                ReactionsSheet(reactions[target].orEmpty(), vm::nameOf) { reactionsFor = null }
            }
        }
    }
}

/** One DM row: a §13.3 marker (or other system line) is centred text with no bubble, ticks or actions. */
@Composable
internal fun DmMessageRow(m: MessageEntity, bubble: @Composable () -> Unit) {
    if (m.system) lk.codegen.risime.ui.common.SystemLineText(m.body) else bubble()
}

@Composable
internal fun Bubble(
    m: MessageEntity,
    canRetry: Boolean,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    chips: List<lk.codegen.risime.data.ReactionChip>,
    canReact: Boolean,
    onReact: (String, String) -> Unit,
    onOpenReactions: () -> Unit,
    media: lk.codegen.risime.data.db.MediaEntity? = null,
    loader: ImageLoader? = null,
    onImageTap: () -> Unit = {},
    onImageVisible: (String) -> Unit = {},
    /** §15.7 select/delete hooks (null while the delete UI is off). */
    sel: MsgSelect? = null,
) {
    val failed = m.status == MessageStatus.FAILED.name
    var sheet by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val retryable = failed && canRetry && m.failReason != AuthErrors.NOT_FRIENDS && m.failReason != AuthErrors.TOO_LONG
    MessageBubble(
        body = m.body,
        time = timeOf(m.localTs),
        mine = m.outgoing,
        status = if (m.outgoing) MessageStatus.valueOf(m.status) else null,
        note = when {
            !failed -> if (m.deleteUnverified) lk.codegen.risime.data.deletes.DeleteRules.UNVERIFIED_NOTE else null
            m.failReason == AuthErrors.NOT_FRIENDS -> "Not sent — you're not friends"
            m.failReason == AuthErrors.TOO_LONG -> "Not sent — too long"
            retryable -> "Not sent — tap to retry or delete"
            else -> "Not sent — tap to delete"
        },
        tapOpensMenu = failed && sel?.selecting != true,
        onMenu = { if (sel?.selecting == true) sel.onToggle() else sheet = true },
        selected = sel?.selected == true,
        noteIsInfo = !failed,
        footer = { ReactionChipsRow(chips, onOpenReactions) },
        image = if (m.image) ({ ImageBubbleContent(media, loader, onImageVisible) }) else null,
        onTap = if (sel?.selecting == true) sel.onToggle else if (m.image) onImageTap else null,
        tapLabel = if (m.image) imageTapLabel(imageTap(media)) else null,
    )
    if (sheet) {
        val actions = buildList<Pair<String, () -> Unit>> {
            if (!m.image || m.body.isNotBlank()) add("Copy" to { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", m.body))) } })
            if (retryable) add("Retry" to { onRetry(m.clientMsgId) })
            // §15.7 (android S-d): with the delete UI on, a never-accepted row's action is "Discard".
            if (failed) add((if (sel != null) "Discard" else "Delete") to { onDelete(m.clientMsgId) })
            if (sel != null && !failed) {
                add("Delete" to sel.onDelete)
                add("Select" to sel.onSelect)
            }
            // §14.7: cancelling an uploading photo deletes it (and an uploaded blob).
            if (m.image && m.outgoing && m.status == MessageStatus.PENDING.name) add("Cancel" to { onDelete(m.clientMsgId) })
        }
        MessageActionsSheet(
            canReact = canReact,
            myReactions = chips.filter { it.mine }.map { it.emoji }.toSet(),
            onReact = onReact,
            actions = actions,
            onDismiss = { sheet = false },
        )
    }
}

/** §9: a former friend's chat is read-only; offer a new friend request instead of a composer. */
@Composable
private fun NotFriendsBar(name: String, requested: Boolean, onAddFriend: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("You're not friends with $name any more.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAddFriend, enabled = !requested) { Text(if (requested) "Request sent" else "Add friend") }
        }
    }
}

/** Composer: emoji picker (inserts at the cursor), grapheme counter from 3,900, send disabled when too long (§11.1). */
@Composable
internal fun Composer(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValue: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onSend: () -> Unit,
    placeholder: String = "Message",
    /** §14: the attach-photo button (null: photos aren't available in this app/chat). */
    attach: (@Composable () -> Unit)? = null,
) {
    var picker by remember { mutableStateOf(false) }
    val text = value.text
    // Graphemes ≤ chars: only count when it could matter.
    val limits = if (text.length < lk.codegen.risime.data.BodyLimits.COUNTER_FROM) null else lk.codegen.risime.data.BodyLimits.of(text, lk.codegen.risime.data.IcuGraphemes)
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.sm, vertical = Spacing.xs + Spacing.xxs)) {
            if (limits?.showCounter == true) {
                Text(
                    "%,d / %,d".format(limits.graphemes, lk.codegen.risime.data.BodyLimits.MAX_GRAPHEMES) + if (limits.tooLong) " · too long" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (limits.tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End).padding(end = Spacing.sm),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                attach?.invoke()
                IconButton(onClick = { picker = true }) {
                    Text("🙂", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "Emoji" })
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValue,
                    placeholder = { Text(placeholder) },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                    shape = RisiShapes.input,
                )
                IconButton(onClick = onSend, enabled = text.isNotBlank() && limits?.tooLong != true) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (picker) {
        EmojiPickerSheet(onPick = { e ->
            val sel = value.selection
            val newText = text.replaceRange(sel.min, sel.max, e)
            onValue(androidx.compose.ui.text.input.TextFieldValue(newText, androidx.compose.ui.text.TextRange(sel.min + e.length)))
        }, onDismiss = { picker = false })
    }
}

/** §14.1: attach a photo; a disabled-looking button still answers a tap with the reason (and refetches readiness). */
@Composable
internal fun AttachButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Default.Add,
            if (enabled) "Attach photo" else "Attach photo (unavailable)",
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/** A short notice above the composer (refusals, "Saved to Pictures/RisiMe"). */
@Composable
internal fun ImageToast(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}
