package lk.codegen.risime.ui.chat

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
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
    val uploads by vm.imgs.uploads.collectAsStateWithLifecycle()
    val pickPhoto = rememberImageLayer(vm.imgs, messages)
    val historyMarker by vm.historyMarker.collectAsStateWithLifecycle()

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
                avatar = { InitialsAvatar(name, size = Sizes.avatarSmall + 4.dp, online = presence?.online == true) },
                onTitleClick = { showInfo = true },
                titleClickLabel = "Chat info",
                actions = {
                    val callsReady by vm.calls.callsReady.collectAsStateWithLifecycle()
                    CallHeaderButton(
                        blocked = vm.calls.blockedText(encrypted, callsReady, name),
                        onBlocked = { t -> vm.imgs.toast.value = t; vm.calls.refresh() },
                        onCall = vm::startCall,
                    )
                    E2eeHeaderLock(encrypted) { showInfo = true }
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
                spacing = Spacing.xxs,
                scroll = scroll,
            ) { items, i ->
                when (val item = items[i]) {
                    is ChatItem.Day -> DaySeparator(item.label)
                    is ChatItem.Msg -> DmMessageRow(
                        item.m, onCallBack = vm::startCall.takeIf { isFriend }, onDeleteForMe = vm::deleteCallLine,
                        historyMarker = if (lk.codegen.risime.BuildConfig.HISTORY_SHARE_ENABLED) ({ m ->
                            lk.codegen.risime.ui.history.HistoryMarkerRow(m.body, historyMarker, vm.conversationId, vm.me, name, vm::requestHistory, vm::escalateHistory)
                        }) else null,
                    ) {
                        if (item.m.showsAsDeleted) {
                            val s = vm.del.selectFor(item.m, selection)
                            TombstoneBubble(
                                item.m, vm.me, selected = s?.selected == true,
                                onMenu = s?.let { x -> { if (x.selecting) x.onToggle() else x.onDelete() } },
                                onTap = s?.takeIf { it.selecting }?.onToggle,
                                tail = lk.codegen.risime.ui.common.startsRun(items, i),
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
                            upload = uploads[item.m.clientMsgId],
                            sharedBy = lk.codegen.risime.ui.history.sharedByLabel(item.m) { id -> if (id.equals(vm.peerId, true)) name else "your contact" },
                            tail = lk.codegen.risime.ui.common.startsRun(items, i),
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
internal fun DmMessageRow(
    m: MessageEntity,
    onCallBack: (() -> Unit)? = null,
    onDeleteForMe: ((String) -> Unit)? = null,
    /** §17.12: the gap marker carries "Request history" (null: a plain line). */
    historyMarker: (@Composable (MessageEntity) -> Unit)? = null,
    bubble: @Composable () -> Unit,
) {
    when {
        m.system && historyMarker != null && m.clientMsgId == lk.codegen.risime.data.HistoryMarkers.historyId(m.conversationId) -> historyMarker(m)
        m.system -> lk.codegen.risime.ui.common.SystemLineText(m.body)
        // §16.6 a call-history line: centred, "Call back", only "Delete for me", no reactions.
        m.call -> lk.codegen.risime.calls.CallLineRow(
            m.body, missed = !m.outgoing && m.body == lk.codegen.risime.calls.CallLines.MISSED,
            onCallBack = onCallBack, onDeleteForMe = onDeleteForMe?.let { f -> { f(m.clientMsgId) } },
        )
        else -> bubble()
    }
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
    /** This photo's upload progress (0..1) while it goes out. */
    upload: Float? = null,
    /** §17.12 "Shared by <provider>" for an imported row (the info line of the sheet). */
    sharedBy: String? = null,
    /** The first bubble of a run (WhatsApp tail). */
    tail: Boolean = true,
) {
    val failed = m.status == MessageStatus.FAILED.name
    var sheet by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val retryable = failed && canRetry && m.failReason != AuthErrors.NOT_FRIENDS && m.failReason != AuthErrors.TOO_LONG
    val send = if (m.image) photoSendState(m, media, upload) else null
    // The photo's tap target: a failed photo (when a retry can help) or one waiting in its backoff.
    val retryPhoto: (() -> Unit)? = if (canRetry && (retryable || !failed) && (send is PhotoSend.Failed || send == PhotoSend.Waiting)) ({ onRetry(m.clientMsgId) }) else null
    MessageBubble(
        body = m.body,
        time = timeOf(m.localTs),
        mine = m.outgoing,
        status = if (m.outgoing) MessageStatus.valueOf(m.status) else null,
        note = when {
            !failed -> if (m.deleteUnverified) lk.codegen.risime.data.deletes.DeleteRules.UNVERIFIED_NOTE else null
            m.failReason == AuthErrors.NOT_FRIENDS -> "Not sent — you're not friends"
            m.failReason == AuthErrors.TOO_LONG -> "Not sent — too long"
            m.failReason == lk.codegen.risime.data.ChatEngine.E2EE_NOT_READY -> "Not sent — encryption with this chat isn't ready. Tap to retry"
            retryable -> "Not sent — tap to retry or delete"
            else -> "Not sent — tap to delete"
        },
        tapOpensMenu = failed && sel?.selecting != true,
        onMenu = { if (sel?.selecting == true) sel.onToggle() else sheet = true },
        selected = sel?.selected == true,
        noteIsInfo = !failed,
        footer = { ReactionChipsRow(chips, onOpenReactions) },
        image = if (m.image) ({ ImageBubbleContent(media, loader, onImageVisible, send = send, onRetry = retryPhoto) }) else null,
        imageStatus = if (m.image) send?.let { photoSendText(it) } ?: imageStatusText(media) else null,
        imageAction = retryPhoto?.let { PHOTO_RETRY to it },
        onTap = if (sel?.selecting == true) sel.onToggle else if (m.image) onImageTap else null,
        tapLabel = if (m.image) imageTapLabel(imageTap(media)) else null,
        tail = tail,
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
            info = sharedBy,
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

/**
 * The input bar (WhatsApp-style, full width on the chat wallpaper): a rounded field holding the
 * emoji button, the text and the "+" attach button, then the round Send button. Emoji picker
 * inserts at the cursor; grapheme counter from 3,900; Send disabled when too long (§11.1).
 */
@Composable
internal fun Composer(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValue: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onSend: () -> Unit,
    placeholder: String = "Message",
    /** §14: the "+" attach button (null: photos aren't available in this app/chat). */
    attach: (@Composable () -> Unit)? = null,
    /** The picker view inside the emoji sheet (a fake in tests). */
    emojiPicker: (@Composable (onPick: (String) -> Unit) -> Unit)? = null,
) {
    var picker by remember { mutableStateOf(false) }
    val text = value.text
    // Graphemes ≤ chars: only count when it could matter.
    val limits = if (text.length < lk.codegen.risime.data.BodyLimits.COUNTER_FROM) null else lk.codegen.risime.data.BodyLimits.of(text, lk.codegen.risime.data.IcuGraphemes)
    val colors = lk.codegen.risime.ui.theme.RisiTheme.colors
    val dark = colors.bubbleTheirs.luminance() < 0.5f
    Surface(color = colors.chatBackground) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = Spacing.sm, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.sm)) {
            if (limits?.showCounter == true) {
                Text(
                    "%,d / %,d".format(limits.graphemes, lk.codegen.risime.data.BodyLimits.MAX_GRAPHEMES) + if (limits.tooLong) " · too long" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (limits.tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End).padding(end = Spacing.sm),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Surface(
                    shape = RisiShapes.input,
                    color = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest,
                    shadowElevation = if (dark) 0.dp else 1.dp,
                    modifier = Modifier.weight(1f),
                ) {
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = Spacing.xxs)) {
                        IconButton(onClick = { picker = true }) {
                            Text("🙂", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "Emoji" })
                        }
                        androidx.compose.material3.TextField(
                            value = value,
                            onValueChange = onValue,
                            placeholder = { Text(placeholder) },
                            modifier = Modifier.weight(1f),
                            maxLines = 6,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = androidx.compose.material3.TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                        )
                        attach?.invoke()
                    }
                }
                Spacer(Modifier.width(Spacing.xs + Spacing.xxs))
                val canSend = text.isNotBlank() && limits?.tooLong != true
                androidx.compose.material3.FilledIconButton(
                    onClick = onSend,
                    enabled = canSend,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.size(Sizes.minTouch + 4.dp),
                    colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", Modifier.size(22.dp))
                }
            }
        }
    }
    if (picker) {
        // Stays open: each pick goes in at the cursor after the previous one; ⌫ deletes whole graphemes.
        EmojiPickerSheet(
            onPick = { e -> onValue(insertAtCursor(value, e)) },
            onDismiss = { picker = false },
            onBackspace = { onValue(deleteBeforeCursor(value, IcuGraphemeBoundary)) },
            picker = emojiPicker ?: { EmojiPickerAndroidView(it) },
        )
    }
}

/** §14.1 the "+" attach button (opens the attachment sheet); a disabled-looking button still answers a tap with the reason. */
@Composable
internal fun AttachButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Default.Add,
            if (enabled) "Attach" else "Attach (unavailable)",
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(26.dp),
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
