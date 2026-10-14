package ai.risicloud.risime.ui.group

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
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ai.risicloud.risime.data.MessageStatus
import ai.risicloud.risime.data.ReactionChip
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.groups.groupDisplayName
import ai.risicloud.risime.ui.common.ChatMessageList
import ai.risicloud.risime.ui.common.ChatScrollState
import ai.risicloud.risime.ui.common.rememberChatScrollState
import ai.risicloud.risime.data.groups.systemLine
import ai.risicloud.risime.data.groups.systemText
import ai.risicloud.risime.net.AuthErrors
import ai.risicloud.risime.net.GroupMember
import ai.risicloud.risime.ui.chat.ChatItem
import ai.risicloud.risime.ui.chat.Composer
import ai.risicloud.risime.ui.chat.MessageActionsSheet
import ai.risicloud.risime.ui.chat.ReactionChipsRow
import ai.risicloud.risime.ui.chat.ReactionsSheet
import ai.risicloud.risime.ui.chats.connectionStatus
import ai.risicloud.risime.ui.common.DaySeparator
import ai.risicloud.risime.ui.common.InitialsAvatar
import ai.risicloud.risime.ui.common.MessageBubble
import ai.risicloud.risime.ui.common.RisiTopBar
import ai.risicloud.risime.ui.common.SectionHeader
import ai.risicloud.risime.ui.common.SystemLineText
import ai.risicloud.risime.ui.common.startsRun
import ai.risicloud.risime.ui.common.memberColor
import ai.risicloud.risime.ui.common.timeOf
import ai.risicloud.risime.ui.chats.groupTypingLabel
import ai.risicloud.risime.ui.theme.Sizes
import ai.risicloud.risime.ui.theme.Spacing

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
fun GroupChatScreen(
    vm: GroupChatViewModel,
    meId: String,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    /** §24.9: the Private | Official tab bar, directly under the header (null: tabs off, the screen as before). */
    tabBar: (@Composable () -> Unit)? = null,
    /** §24.1: a 1:1 Official has no name: the peer's name is shown. */
    titleOverride: String? = null,
    /** A 1:1 Official's subtitle instead of the member count. */
    subtitleOverride: String? = null,
    /** A 1:1 Official's " · Risi": always visible, the peer name ellipsizes first. */
    titleSuffix: String? = null,
    /** §24.4: Official turned off: its history is read-only (the composer is replaced by this line). */
    readOnlyReason: String? = null,
    /** §24.9: "Risi is listening" in Official. */
    composerHint: String? = null,
    /** §24.9/§24.11 Official only: Risi cards, the @Risi chip and the Summarise/Report menu items. Private passes null. */
    risi: ai.risicloud.risime.ui.tabs.RisiHost? = null,
    /** §25.2 the user's Risi chat: every message is an `ask`, no attachments, calls, chip or Summarise/Report. */
    risiChat: Boolean = false,
    /** v1.32 item 4: the ⋮ menu's "Lock chat" / "Unlock chat" (null: no item). */
    lock: ai.risicloud.risime.ui.lock.ChatLockControl? = null,
    /**
     * v1.33 §24.5/§24.9 a 1:1's Official tab: the 1:1 call buttons (§16/§19 on the `dm:`) instead of the
     * §20 group ones; the argument shows a tap's explanation. Null: a group's buttons.
     */
    dmCallButtons: (@Composable (toast: (String) -> Unit) -> Unit)? = null,
    /** PART 4: a 1:1's Official tab: "Risi, <question>" (addressed by name, no chip) is also a request to Risi. */
    directAddress: Boolean = false,
    /** PART 4: a group's Private tab (tabs on): "@Risi" typed here offers to move the text to Official. */
    onMoveToOfficial: ((String) -> Unit)? = null,
) {
    val group by vm.group.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val typing by vm.typingNames.collectAsStateWithLifecycle()
    val encrypted by vm.encrypted.collectAsStateWithLifecycle()
    val composer by vm.composer.collectAsStateWithLifecycle()
    val e2eeStrip by vm.e2eeStrip.collectAsStateWithLifecycle()
    val reactions by vm.reactions.collectAsStateWithLifecycle()
    val readBy by vm.readBy.collectAsStateWithLifecycle()
    val selection by vm.del.selected.collectAsStateWithLifecycle()
    var clearAsk by remember { mutableStateOf<Boolean?>(null) }
    var joinAsk by remember { mutableStateOf<Pair<ai.risicloud.risime.calls.GroupCallEnvelope, String>?>(null) }
    var reactionsFor by remember { mutableStateOf<String?>(null) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    // §25.4 a Risi draft's [Use] opened this chat: the composer starts with it; nothing is sent.
    LaunchedEffect(Unit) { vm.takeDraft()?.let { draft = TextFieldValue(it, androidx.compose.ui.text.TextRange(it.length)) } }
    val risiProgress by vm.risiProgress.collectAsStateWithLifecycle()
    val scheduledHere by vm.scheduledCtl.here.collectAsStateWithLifecycle()
    val scheduledSends by vm.scheduledCtl.sendsById.collectAsStateWithLifecycle()
    var scheduledSheet by remember { mutableStateOf(false) }
    var summariseAsk by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val undoneNow by vm.undoneState.collectAsStateWithLifecycle()
    var progressNow by remember { mutableStateOf(System.currentTimeMillis()) }
    if (risi != null && risiProgress.isNotEmpty()) {
        LaunchedEffect(risiProgress) {
            while (true) { progressNow = System.currentTimeMillis(); kotlinx.coroutines.delay(5_000) }
        }
    }
    val scroll = rememberChatScrollState()
    // Item 6: which encryption label this screen shows (Official: [risi] is passed; a 1:1's Official has its 1:1 call buttons).
    val encSurface = ai.risicloud.risime.ui.chat.EncSurface.of(
        official = risi != null, risiChat = risiChat, group = dmCallButtons == null && !directAddress,
    )
    val risiChatReady by vm.risiChatReady.collectAsStateWithLifecycle()
    val notesActive by vm.notesActive.collectAsStateWithLifecycle()
    // §27.3/§27.4 opened from a Risi card ([Open chat] / [Open Risi chat]): scroll there once the message is here.
    var focus by remember { mutableStateOf<ai.risicloud.risime.data.tabs.RisiUiBus.Focus?>(null) }
    var lineFocus by remember { mutableStateOf<Pair<MessageEntity, ai.risicloud.risime.net.SourceRef>?>(null) }
    LaunchedEffect(Unit) { focus = vm.takeFocus()?.let { f -> if (f.scheduled) { scheduledSheet = true; null } else f } }
    LaunchedEffect(focus, messages) {
        val f = focus ?: return@LaunchedEffect
        val target = ai.risicloud.risime.data.tabs.RisiLedger.focusTarget(messages, f) ?: return@LaunchedEffect
        if (ai.risicloud.risime.ui.tabs.scrollToMessage(scroll, messages, target)) {
            focus = null
            // v1.42 §38.3 opened at an action item's line: highlight it (a voice note also seeks and expands).
            f.line?.let { ref -> messages.firstOrNull { it.messageId.equals(target, true) }?.let { row -> lineFocus = row to ref } }
        }
    }
    // §38.3 the line never came (deleted, or older than this phone's history): say so instead of waiting.
    LaunchedEffect(focus?.line) {
        if (focus?.line == null) return@LaunchedEffect
        kotlinx.coroutines.delay(5_000)
        if (focus?.line != null) { focus = null; vm.imgs.toast.value = ai.risicloud.risime.data.tabs.RisiActionItems.LINE_GONE }
    }
    val media by vm.imgs.media.collectAsStateWithLifecycle()
    val imagesReady by vm.imgs.imagesReady.collectAsStateWithLifecycle()
    val toast by vm.imgs.toast.collectAsStateWithLifecycle()
    val uploads by vm.imgs.uploads.collectAsStateWithLifecycle()
    // §14.1: groups may send while not ready; the sheet says who can't see photos yet.
    val pickPhoto = ai.risicloud.risime.ui.chat.rememberImageLayer(
        vm.imgs, messages, groupNotice = if (imagesReady == false) ai.risicloud.risime.ui.chat.GROUP_IMAGES_NOTICE else null,
    )

    LaunchedEffect(messages, resumed) {
        if (resumed && messages.any { !it.outgoing && it.status != MessageStatus.READ.name }) vm.markRead()
    }

    val name = titleOverride ?: groupDisplayName(group?.name)
    val readOnly = group?.readOnly == true || readOnlyReason != null
    val names = members.associate { it.userId.lowercase() to it.displayName }
    var risiChip by rememberSaveable { mutableStateOf(false) }
    // PART 4: an example tapped on the empty Risi chat's card focuses the composer (cursor where the card left it).
    var focusComposer by remember { mutableStateOf(0) }
    // Follow-ups: my next message within 3 min of Risi's answer to me continues with Risi (chip, × to opt out).
    // Composition-scoped: only while an Official follow-up chip can show; cancelled on dispose or when keys change.
    var followUpNow by remember { mutableStateOf(System.currentTimeMillis()) }
    if (risi != null && !risiChat) {
        LaunchedEffect(Unit) {
            while (true) { kotlinx.coroutines.delay(10_000); followUpNow = System.currentTimeMillis() }
        }
    }
    val followUp = if (risi == null || risiChat) null else remember(messages, followUpNow) { ai.risicloud.risime.data.tabs.RisiFollowUp.active(messages, meId, followUpNow) }
    var followUpDismissed by rememberSaveable { mutableStateOf<String?>(null) }
    val continuing = followUp != null && followUp != followUpDismissed
    val risiScope = rememberCoroutineScope()
    var flash by remember { mutableStateOf<String?>(null) }
    val itemViews by vm.itemViews.collectAsStateWithLifecycle()
    // v1.42 §38.3 an action item's line here: scroll, highlight, and for a voice note seek + expand its transcript.
    val showLine: (MessageEntity, ai.risicloud.risime.net.SourceRef) -> Unit = { row, ref ->
        risiScope.launch {
            row.messageId?.let { ai.risicloud.risime.ui.tabs.scrollToMessage(scroll, messages, it) }
            // No ids, no text: the device gate reads that the jump happened.
            android.util.Log.i("RisiMe", "risi: action line jump ${ref.type}")
            ai.risicloud.risime.ui.chat.VoiceJump.apply(vm.container, row, ref)
            flash = row.clientMsgId
            kotlinx.coroutines.delay(1_600)
            if (flash == row.clientMsgId) flash = null
        }
    }
    val risiCtx = if (risi == null) null else ai.risicloud.risime.ui.tabs.risiCardContext(
        risi, messages, vm::nameOf, System.currentTimeMillis(),
        onRef = { id -> risiScope.launch { ai.risicloud.risime.ui.tabs.scrollToMessage(scroll, messages, id) } },
        knownNames = members.filter { it.current }.map { it.displayName },
        readOnly = readOnly,
        // §25.4 a next-step chip pre-fills the composer (as a question to Risi in the Risi chat and while continuing).
        prefill = { s -> draft = TextFieldValue(s, androidx.compose.ui.text.TextRange(s.length)); if (!risiChat) risiChip = true },
        risiChatReady = risiChatReady,
        // P0 2026-10-10 a next-step chip runs at once: a `risi_request` ask (in the Risi chat exactly what typing it
        // and pressing send does; in an Official chat the @Risi request), never pasted, never a plain message.
        sendChip = if (readOnly) null else ({ s -> ai.risicloud.risime.ui.tabs.RisiChips.send(risi, s) }),
        risiChat = risiChat,
        itemViews = itemViews,
        jumpLine = { conv, ref ->
            if (conv == null || conv.equals(vm.conversationId, true)) {
                val row = ai.risicloud.risime.data.tabs.RisiActionItems.lineRow(messages, ref)
                if (row != null) showLine(row, ref) else vm.imgs.toast.value = ai.risicloud.risime.data.tabs.RisiActionItems.LINE_GONE
            } else {
                risi.openLine(conv, ref)
            }
        },
        flash = flash,
    )
    LaunchedEffect(lineFocus) { lineFocus?.let { (row, ref) -> lineFocus = null; showLine(row, ref) } }
    val typingLabel = groupTypingLabel(typing)
    val count = members.count { it.current && it.state != GroupMember.STATE_PENDING_ADD }
    // v1.34 §33: stars, reply, forward, Info, files.
    val starred by vm.messaging.starred.collectAsStateWithLifecycle()
    val replyTo by vm.messaging.replyTo.collectAsStateWithLifecycle()
    var starredOpen by remember { mutableStateOf(false) }
    val x = ai.risicloud.risime.ui.chat.ChatMessaging(
        vm.container, vm.messaging, vm.files, messages, media, starred,
        nameOf = { id -> if (members.any { it.userId.equals(id, true) && it.kind == GroupMember.KIND_AGENT }) "Risi" else vm.nameOf(id) },
        onJump = { id ->
            risiScope.launch {
                if (ai.risicloud.risime.ui.chat.jumpTo(scroll, messages, id)) {
                    flash = id
                    kotlinx.coroutines.delay(1_200)
                    if (flash == id) flash = null
                } else {
                    vm.messaging.toast.value = ai.risicloud.risime.ui.chat.MessagingStrings.QUOTE_NOT_LOADED
                }
            }
        },
        canSend = !readOnly && !risiChat,
        flash = flash,
    )
    Scaffold(
        topBar = {
            RisiTopBar(
                title = name,
                titleSuffix = titleSuffix,
                // Item 6 (honesty): Private shows the lock; Official and the Risi chat show the Official lock and
                // "Encrypted · Risi can read" (Risi's keys are on RisiMe's server), never the Private lock.
                subtitle = typingLabel ?: connectionStatus(conn) ?: ai.risicloud.risime.ui.chat.headerEncryptionSubtitle(
                    encSurface, encrypted == true,
                    subtitleOverride ?: if (risiChat) "Risi" else if (count == 1) "1 member" else "$count members",
                ),
                emphasis = typingLabel != null,
                onBack = onBack,
                avatar = { if (risiChat) ai.risicloud.risime.ui.tabs.RisiAvatar(Sizes.avatarSmall + 4.dp) else InitialsAvatar(name, size = Sizes.avatarSmall + 4.dp, photoKey = vm.conversationId) },
                onTitleClick = onInfo,
                titleClickLabel = "Group info",
                actions = {
                    // §20.1 the group's call and video-call buttons (disabled until group_calls_ready; a tap explains).
                    if (dmCallButtons != null) {
                        if (!readOnly && !risiChat) dmCallButtons { vm.imgs.toast.value = it }
                    } else if (ai.risicloud.risime.BuildConfig.GROUP_CALLS_ENABLED && !readOnly && !risiChat) {
                        val ready by vm.groupCallsReady.collectAsStateWithLifecycle()
                        // v1.33 §20.1 no LiveKit on the server: disabled, a tap says so (recomposes when it changes).
                        @Suppress("UNUSED_VARIABLE") val unavailable by vm.groupCallsUnavailable.collectAsStateWithLifecycle()
                        val toastOf: (String) -> Unit = { vm.imgs.toast.value = it; vm.refreshGroupCalls() }
                        ai.risicloud.risime.ui.chat.VideoHeaderButton(vm.groupCallBlockedText(encrypted == true, ready, video = true), toastOf) { cam -> vm.startGroupCall(video = true, camera = cam) }
                        ai.risicloud.risime.ui.chat.CallHeaderButton(vm.groupCallBlockedText(encrypted == true, ready, video = false), toastOf) { vm.startGroupCall(video = false, camera = false) }
                    } else if (risiChat && !readOnly) {
                        // Proposal 2026-10-14-call-risi §41.1: Call Risi (User.call_risi + the developer flag + group_calls).
                        val flagOn by vm.container.callRisiFlag.on.collectAsStateWithLifecycle()
                        LaunchedEffect(flagOn) { if (flagOn) vm.refreshCallRisi() }
                        val callRisi by vm.callRisiVisible.collectAsStateWithLifecycle()
                        var explain by remember { mutableStateOf(false) }
                        if (callRisi) {
                            ai.risicloud.risime.calls.risi.CallRisiButton(onBlocked = { vm.imgs.toast.value = it }) {
                                if (vm.callRisiExplained()) vm.startRisiCall() else explain = true
                            }
                        }
                        if (explain) {
                            ai.risicloud.risime.calls.risi.RisiCallExplainer(onOk = { explain = false; vm.startRisiCall() }, onCancel = { explain = false })
                        }
                    }
                    ai.risicloud.risime.ui.chat.ChatOverflowMenu(
                        onClear = { clearAsk = false }, onDelete = { clearAsk = true }, lock = lock.takeIf { !risiChat },
                        extra = { close ->
                            risi?.takeIf { !risiChat }?.let { h -> ai.risicloud.risime.ui.tabs.RisiMenuItems(h, enabled = !readOnly, close = close, onSummarise = { summariseAsk = true }) }
                            // §33.11 this conversation's starred messages (each tab separate; device-local).
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(ai.risicloud.risime.ui.chat.MessagingStrings.STARRED_MESSAGES, maxLines = 1) }, onClick = { close(); starredOpen = true },
                                modifier = Modifier.testTag("chat_menu_starred"),
                            )
                            // §30.6 Risi chat ⋮ → Notes (only on a Risi Notes device).
                            if (risiChat && notesActive && risi != null) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Notes", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, onClick = { close(); risi.openNotes() },
                                    modifier = Modifier.testTag("risi_chat_menu_notes"),
                                )
                            }
                            // §26.6 "Scheduled messages" (never in the Risi chat: nothing is scheduled into it).
                            if (!risiChat && (vm.scheduledMenu() || scheduledHere.isNotEmpty())) {
                                androidx.compose.material3.DropdownMenuItem(text = { Text(ai.risicloud.risime.ui.chat.SCHEDULED_MESSAGES_TITLE) }, onClick = { close(); scheduledSheet = true })
                            }
                        },
                    )
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            // a11y (Official tab): the tab bar + "Risi is listening" strip sit above the message list in z-order, so a
            // half-scrolled message (its expanded touch bounds) never covers them in the accessibility tree.
            // (A Column, not a Box: whatever rows the tab bar emits stack, never overlap; P0 2026-10-12 bug C.)
            tabBar?.let { bar -> androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().zIndex(1f)) { bar() } }
            ai.risicloud.risime.ui.chat.SelectionBarFor(vm.del, messages, selection, x)
            ai.risicloud.risime.ui.chat.MessagingHost(x, starredOpen, onStarredClose = { starredOpen = false }, groupInfo = { m -> m.messageId?.let(vm::openReadBy) })
            ai.risicloud.risime.ui.chat.DeleteHost(vm.del, clearAsk, onClearAskDone = { clearAsk = null }, onDeletedChat = onBack)
            // Bug D: the Risi chat is one pane; while this phone (re)builds its encryption it says so plainly, never
            // "Setting up encryption on this phone…" (decision 048: no lock is shown until it is encrypted).
            (if (risiChat) e2eeStrip?.let { ai.risicloud.risime.data.tabs.RISI_CHAT_RECONNECTING } else e2eeStrip)?.let { strip ->
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        strip,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // v1.46 §12.12.3: a waiting strip always offers Try again (never a wait with no way out).
                    if (e2eeStripHasTryAgain(e2eeStrip)) {
                        androidx.compose.material3.TextButton(onClick = vm::retryRejoin, modifier = Modifier.testTag("e2ee_try_again")) {
                            Text(ai.risicloud.risime.ui.tabs.TRY_AGAIN_LABEL)
                        }
                    }
                }
            }
            // PART 4: "What can Risi do?" at the top of an empty Risi chat; a tap fills the composer, never sends.
            if (risiChat && !readOnly && ai.risicloud.risime.data.tabs.RisiTalk.emptyRisiChat(messages)) {
                ai.risicloud.risime.ui.tabs.RisiExamplesCard(onPick = { ex ->
                    draft = TextFieldValue(ex.text, androidx.compose.ui.text.TextRange(ex.cursor))
                    focusComposer++
                })
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
                historyMarker = if (ai.risicloud.risime.BuildConfig.HISTORY_SHARE_ENABLED) ({ m ->
                    val st by vm.historyMarker.collectAsStateWithLifecycle()
                    ai.risicloud.risime.ui.history.HistoryMarkerRow(m.body, st, vm.conversationId, meId, null, vm::requestHistory, vm::escalateHistory)
                }) else null,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                media = media,
                loader = vm.imgs.loader,
                onImageTap = vm.imgs::tap,
                onImageVisible = vm.imgs::onVisible,
                del = vm.del,
                selection = selection,
                uploads = uploads,
                onJoinCall = if (ai.risicloud.risime.BuildConfig.GROUP_CALLS_ENABLED && runCatching { vm.groupCallBlockedText(true, true, false) == null }.getOrDefault(false)) ({ env, starter ->
                    joinAsk = env to starter
                }) else null,
                risi = risiCtx,
                risiChat = risiChat,
                scheduledSends = scheduledSends,
                x = x,
            )
            if (!risiChat) {
                ai.risicloud.risime.ui.chat.ScheduledBubbles(scheduledHere, vm.scheduledCtl)
                if (scheduledSheet) ai.risicloud.risime.ui.chat.ScheduledMessagesSheet(scheduledHere, vm.scheduledCtl) { scheduledSheet = false }
                // Summarise: Today / 7 days / 30 days / Date range… (proposal 2026-10-09-risi-30day-summaries).
                if (summariseAsk && risi != null) ai.risicloud.risime.ui.tabs.SummarisePeriodDialog(risi) { summariseAsk = false }
            }
            // §25.4 the progress bubble of a request made here (ends with `done` or the turn's message).
            if (risi != null) risiProgress.forEach { ai.risicloud.risime.ui.tabs.RisiProgressBubble(it, progressNow) }
            joinAsk?.let { (env, starter) ->
                JoinCallPermissions(env.video, onDenied = { vm.imgs.toast.value = it; joinAsk = null }) { cam ->
                    joinAsk = null
                    vm.joinGroupCall(env, starter, cam)
                }
            }
            toast?.let { ai.risicloud.risime.ui.chat.ImageToast(it) }
            val off = readOnlyReason?.let { GroupComposer.Disabled(it) } ?: composer as? GroupComposer.Disabled
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
                    attach = if (risiChat || !vm.imgs.available || encrypted != true) null else ({
                        ai.risicloud.risime.ui.chat.AttachButton(enabled = true) {
                            vm.imgs.refreshImagesReady()
                            pickPhoto()
                        }
                    }),
                    placeholder = if (risiChat || continuing || (risi != null && risiChip)) ai.risicloud.risime.ui.tabs.RISI_ASK_PLACEHOLDER else composerHint ?: if (encrypted == true) "Encrypted message" else "Message",
                    value = draft,
                    onValue = { draft = it; vm.onDraftChanged(it.text) },
                    focusRequest = focusComposer,
                    onSend = {
                        if (draft.text.isNotBlank()) {
                            // §24.11: with the @Risi chip (or "@Risi" in the text; in a 1:1 Official also "Risi, …") the
                            // message is a structured `risi_request`; else an ordinary message.
                            if (ai.risicloud.risime.ui.tabs.sendFromComposer(risi, risiChip || continuing, draft.text, vm::send, risiChat, directAddress)) risiChip = false
                            draft = TextFieldValue("")
                        }
                    },
                    topSlot = if ((risi != null && !risiChat) || replyTo != null || (onMoveToOfficial != null && risi == null && ai.risicloud.risime.data.tabs.RisiTalk.mentionsRisi(draft.text))) ({
                        replyTo?.let { t -> ai.risicloud.risime.ui.chat.ReplyComposerBar(t, x.nameOf(t.from)) { vm.messaging.replyTo.value = null } }
                        if (risi != null && !risiChat) {
                            // PART 4: the "@" picker offers Risi while "@…" can still become "@Risi".
                            if (!risiChip && !continuing && ai.risicloud.risime.data.tabs.RisiTalk.pickerQuery(draft.text, draft.selection.start) != null) {
                                ai.risicloud.risime.ui.tabs.RisiMentionPicker {
                                    ai.risicloud.risime.data.tabs.RisiTalk.applyPick(draft.text, draft.selection.start)?.let { (t, c) ->
                                        draft = TextFieldValue(t, androidx.compose.ui.text.TextRange(c))
                                    }
                                    risiChip = true
                                    focusComposer++
                                }
                            }
                            if (continuing) ai.risicloud.risime.ui.tabs.RisiFollowUpChip { followUpDismissed = followUp }
                            else ai.risicloud.risime.ui.tabs.RisiChipRow(risiChip) { risiChip = it }
                        } else if (risi == null && onMoveToOfficial != null && ai.risicloud.risime.data.tabs.RisiTalk.mentionsRisi(draft.text)) {
                            // PART 4: Risi is never in Private; the hint only offers to move the text to Official.
                            ai.risicloud.risime.ui.tabs.RisiPrivateHint {
                                val text = draft.text
                                draft = TextFieldValue("")
                                vm.onDraftChanged("")
                                onMoveToOfficial(text)
                            }
                        }
                    }) else null,
                    // v1.41 §37.3 / §37.8: the mic in Send's place (groups, both tabs, and the Risi chat: a note is a request there).
                    voice = if (encrypted == true && vm.imgs.available) ai.risicloud.risime.ui.chat.rememberVoiceComposer(
                        vm.container, vm.conversationId, ai.risicloud.risime.ui.chat.voiceNotReadyText(dm = false, name = name),
                        reply = { vm.messaging.takeReply() }, toast = { vm.imgs.toast.value = it },
                    ) else null,
                )
            }
            reactionsFor?.let { target -> ReactionsSheet(reactions[target].orEmpty(), vm::nameOf) { reactionsFor = null } }
            readBy?.let { (mid, state) -> ReadBySheet(state, vm::nameOf, onRetry = { vm.openReadBy(mid) }, onDismiss = vm::closeReadBy, isAgent = vm::isAgent) }
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
    media: Map<String, ai.risicloud.risime.data.db.MediaEntity> = emptyMap(),
    loader: ai.risicloud.risime.ui.chat.ImageLoader? = null,
    onImageTap: (MessageEntity) -> Unit = {},
    onImageVisible: (String) -> Unit = {},
    /** §15.7 select/delete (null: off). */
    del: ai.risicloud.risime.ui.chat.DeleteController? = null,
    selection: Set<String> = emptySet(),
    /** Upload progress of this phone's photos going out (client_msg_id → 0..1). */
    uploads: Map<String, Float> = emptyMap(),
    /** §17.12 the gap marker with "Request history" (null: a plain line, the feature off). */
    historyMarker: (@Composable (MessageEntity) -> Unit)? = null,
    /** §20.4 Join on a running group call's line (null: this phone can't join group calls). */
    onJoinCall: ((ai.risicloud.risime.calls.GroupCallEnvelope, String) -> Unit)? = null,
    /** §24.11 Official only: draws Risi's messages as cards (null: every message is a bubble; Private never has one). */
    risi: ai.risicloud.risime.ui.tabs.RisiCardContext? = null,
    /** §25.2 the Risi chat: the user's own `ask`s are their bubbles (not system lines). */
    risiChat: Boolean = false,
    /** §26.6 scheduled sends ("Sent late" under a bubble that went out more than 2 minutes late). */
    scheduledSends: Map<String, ai.risicloud.risime.data.db.ScheduledSendEntity> = emptyMap(),
    /** v1.34 §33: forwarded label, quote, star, file card, menu items (null: off). */
    x: ai.risicloud.risime.ui.chat.ChatMessaging? = null,
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
            is ChatItem.Msg -> if (item.m.system && historyMarker != null && item.m.clientMsgId == ai.risicloud.risime.data.HistoryMarkers.historyId(item.m.conversationId)) {
                historyMarker(item.m)
            } else if (risiChat && item.m.risiCtl && item.m.outgoing && ai.risicloud.risime.data.tabs.RisiControl.askText(item.m.systemJson) != null) {
                // §25.2: in the Risi chat a request is the user's own message.
                GroupBubble(
                    item.m.copy(kind = MessageEntity.KIND_TEXT, body = ai.risicloud.risime.data.tabs.RisiControl.askText(item.m.systemJson)!!),
                    sender = null, canAct = false, chips = emptyList(), onReact = { _, _ -> }, onOpenReactions = {},
                    onRetry = onRetry, onDelete = onDelete, onInfo = {},
                    tail = startsRun(items, i),
                )
            } else if (item.m.risiCtl) {
                // §24.11 a member's request/action to Risi: a small system line ("Kamal confirmed").
                SystemLineText(ai.risicloud.risime.data.tabs.RisiControl.line(item.m.systemJson, nameOf(item.m.from)) ?: item.m.body)
            } else if (item.m.system) {
                val line = item.m.systemLine()
                // §17.12 local history lines keep their stored text (it changes with imports).
                SystemLineText(line?.takeIf { it.action !in ai.risicloud.risime.data.groups.SystemLine.LOCAL_ACTIONS }?.let { l -> systemText(l, meId) { memberName(it) ?: "Someone" } } ?: item.m.body)
            } else if (item.m.call) {
                // §20.4 a group call's line: centred, Join while the call runs, only "Delete for me".
                val env = ai.risicloud.risime.calls.GroupCallEnvelope.decode(item.m.systemJson)
                if (env == null) {
                    SystemLineText(item.m.body)
                } else {
                    val running = !ai.risicloud.risime.calls.GroupCallLines.over(item.m.systemJson)
                    // v1.45 §40.6: this device rang and nobody answered here.
                    val missedHere = ai.risicloud.risime.calls.GroupCallLines.missed(item.m.systemJson)
                    val text = ai.risicloud.risime.calls.GroupCallLines.text(env, nameOf(item.m.from), item.m.from.equals(meId, true), running, missedHere)
                    ai.risicloud.risime.calls.GroupCallLineRow(
                        text,
                        video = env.video,
                        missed = !item.m.from.equals(meId, true) && (env.reason == ai.risicloud.risime.calls.GroupCallEnvelope.R_TIMEOUT || ai.risicloud.risime.calls.CallLines.isMissed(text)),
                        onJoin = onJoinCall?.takeIf { ai.risicloud.risime.calls.GroupCallLines.joinable(env, running) }?.let { f -> { f(env, item.m.from) } },
                        onDeleteForMe = del?.let { d -> { d.deleteForMe(listOf(item.m.clientMsgId)) } },
                    )
                }
            } else if (item.m.showsAsDeleted) {
                // §15.5 crypto S2: a server-placed tombstone shows no sender unless the sender deleted it.
                val placed = item.m.clientMsgId.startsWith(ai.risicloud.risime.data.deletes.DeleteApplier.PLACEHOLDER)
                val attributed = !placed || item.m.deletedBy.equals(item.m.from, true)
                val s = del?.selectFor(item.m, selection)
                ai.risicloud.risime.ui.chat.TombstoneBubble(
                    item.m, meId, sender = if (attributed && showSenderAt(items, i)) nameOf(item.m.from) else null, selected = s?.selected == true,
                    onMenu = s?.let { x -> { if (x.selecting) x.onToggle() else x.onDelete() } }, onTap = s?.takeIf { it.selecting }?.onToggle,
                    tail = startsRun(items, i),
                )
            } else if (risi != null && ai.risicloud.risime.data.tabs.RisiMessages.meta(item.m) != null) {
                // §24.11: stored only when it came from an attested agent leaf in Official (RisiMessages.honoured).
                ai.risicloud.risime.ui.chat.RisiCardActions(item.m, x, del?.selectFor(item.m, selection)) {
                    ai.risicloud.risime.ui.tabs.RisiCardRow(item.m, ai.risicloud.risime.data.tabs.RisiMessages.meta(item.m)!!, risi)
                }
            } else {
                val bubble: @Composable () -> Unit = { GroupBubble(
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
                    sharedBy = ai.risicloud.risime.ui.history.sharedByLabel(item.m, nameOf),
                    tail = startsRun(items, i),
                    x = x,
                ) }
                // §18.6: the sender's photo (or initials) beside incoming group bubbles, on the first of a run.
                if (!item.m.outgoing) {
                    Row(verticalAlignment = Alignment.Top) {
                        if (startsRun(items, i)) {
                            InitialsAvatar(nameOf(item.m.from), size = 28.dp, photoKey = item.m.from)
                        } else {
                            Spacer(Modifier.width(28.dp))
                        }
                        Spacer(Modifier.width(Spacing.xs))
                        Box(Modifier.weight(1f)) { bubble() }
                    }
                } else {
                    ai.risicloud.risime.ui.chat.WithSentLate(ai.risicloud.risime.ui.chat.isSentLate(item.m, scheduledSends)) { bubble() }
                }
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
    media: ai.risicloud.risime.data.db.MediaEntity? = null,
    loader: ai.risicloud.risime.ui.chat.ImageLoader? = null,
    onImageTap: () -> Unit = {},
    onImageVisible: (String) -> Unit = {},
    sharedBy: String? = null,
    sel: ai.risicloud.risime.ui.chat.MsgSelect? = null,
    upload: Float? = null,
    tail: Boolean = true,
    x: ai.risicloud.risime.ui.chat.ChatMessaging? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val extras = x?.let { ai.risicloud.risime.ui.chat.bubbleExtras(m, it.rows, it.starred, it.nameOf, it.onJump) }
    var selectText by remember { mutableStateOf(false) }
    if (selectText) ai.risicloud.risime.ui.chat.SelectTextDialog(m.body) { selectText = false }
    val failed = m.status == MessageStatus.FAILED.name
    var sheet by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val retryable = failed && canAct && m.failReason != AuthErrors.NOT_MEMBER && m.failReason != AuthErrors.TOO_LONG
    val send = if (m.image) ai.risicloud.risime.ui.chat.photoSendState(m, media, upload) else null
    // The photo's tap target: a failed photo (when a retry can help) or one waiting in its backoff.
    val retryPhoto: (() -> Unit)? = if (canAct && (retryable || !failed) && (send is ai.risicloud.risime.ui.chat.PhotoSend.Failed || send == ai.risicloud.risime.ui.chat.PhotoSend.Waiting)) ({ onRetry(m.clientMsgId) }) else null
    // v1.44 §39.4 the card from the message's own `link_preview` (never fetched here).
    val lp = remember(m.linkPreview, m.body) { if (m.showsAsDeleted) null else ai.risicloud.risime.net.LinkPreview.decode(m.linkPreview, m.body) }
    // PART 11 (d) swipe right to reply, under the Reply action's rules (§33.9).
    ai.risicloud.risime.ui.chat.SwipeToReply(if (x != null && sel?.selecting != true && x.canSend && ai.risicloud.risime.data.messaging.SelectionRules.replyable(m)) ({ x.ctl.reply(m) }) else null) {
    MessageBubble(
        linkPreview = lp?.let { p -> { ai.risicloud.risime.ui.chat.LinkPreviewCard(p, m.outgoing) } },
        linkPreviewLabel = lp?.let(ai.risicloud.risime.ui.chat.LinkPreviewStrings::label),
        body = m.body,
        time = timeOf(m.localTs),
        mine = m.outgoing,
        status = if (m.outgoing) MessageStatus.valueOf(m.status) else null,
        note = when {
            !failed -> if (m.deleteUnverified) ai.risicloud.risime.data.deletes.DeleteRules.UNVERIFIED_NOTE else null
            m.failReason == AuthErrors.NOT_MEMBER -> "Not sent — you're not in this group"
            m.failReason == AuthErrors.TOO_LONG -> "Not sent — too long"
            retryable -> "Not sent — tap to retry or delete"
            else -> "Not sent — tap to delete"
        },
        tapOpensMenu = failed && sel?.selecting != true,
        onMenu = { if (sel?.selecting == true) sel.onToggle() else sheet = true },
        selected = sel?.selected == true || (x?.flash != null && x.flash == m.clientMsgId),
        noteIsInfo = !failed,
        footer = { ai.risicloud.risime.ui.chat.VoiceFooter(m, x); ReactionChipsRow(chips, onOpenReactions) },
        sender = sender,
        senderColor = memberColor(m.from),
        image = if (m.image) ({ ai.risicloud.risime.ui.chat.ImageBubbleContent(media, loader, onImageVisible, send = send, onRetry = retryPhoto) }) else null,
        imageStatus = if (m.image) send?.let { ai.risicloud.risime.ui.chat.photoSendText(it) } ?: ai.risicloud.risime.ui.chat.imageStatusText(media) else null,
        imageAction = retryPhoto?.let { ai.risicloud.risime.ui.chat.PHOTO_RETRY to it },
        onTap = if (sel?.selecting == true) sel.onToggle else if (m.image) onImageTap else if (m.file && x != null) ({ x.files.tap(context, m, media) })
            else if (m.voice && x != null) ({ ai.risicloud.risime.ui.chat.voiceTap(x.c, m) }) else null,
        tapLabel = if (m.image) ai.risicloud.risime.ui.chat.imageTapLabel(ai.risicloud.risime.ui.chat.imageTap(media)) else if (m.file) ai.risicloud.risime.ui.chat.FileStrings.OPEN
            else if (m.voice) ai.risicloud.risime.ui.chat.VoiceStrings.PLAY else null,
        tail = tail,
        forwardHops = extras?.forwardHops,
        quote = extras?.quote?.let { q -> { ai.risicloud.risime.ui.chat.QuoteBlock(q, extras.onQuoteTap) } },
        quoteLabel = extras?.quote?.let(ai.risicloud.risime.ui.chat.Quotes::label),
        starred = extras?.starred == true,
        attachment = if (m.file && x != null) ({ ai.risicloud.risime.ui.chat.FileCard(x.c, m, media) })
            else if (m.voice && x != null) ({ ai.risicloud.risime.ui.chat.VoiceBubbleContent(x.c, m, media) }) else null,
        attachmentLabel = if (m.file) "File, " + (ai.risicloud.risime.data.media.FileMeta.decode(m.systemJson)?.let { ai.risicloud.risime.data.media.FileEnvelope.displayName(it.name) } ?: "")
            else if (m.voice) ai.risicloud.risime.ui.chat.VoiceStrings.label(m, ai.risicloud.risime.data.media.VoiceMeta.decode(m.systemJson)) else null,
    )
    }
    if (sheet) {
        val actions = buildList<Pair<String, () -> Unit>> {
            if (x != null) {
                // §33.3 a single message copies its content alone; Reply, Forward, Star, Share, Info under the bar's rules.
                if (ai.risicloud.risime.data.messaging.CopyFormat.content(m, single = true) != null) add(ai.risicloud.risime.ui.chat.MessagingStrings.COPY to { x.ctl.copy(context, listOf(m), x.nameOrNull) })
                addAll(ai.risicloud.risime.ui.chat.messagingMenuItems(m, x, context) { selectText = true })
            } else {
                if (!m.image || m.body.isNotBlank()) add("Copy" to { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", m.body))) } })
                if (m.outgoing && m.messageId != null) add("Info" to onInfo)
            }
            if (retryable) add("Retry" to { onRetry(m.clientMsgId) })
            if (failed) add((if (sel != null) "Discard" else "Delete") to { onDelete(m.clientMsgId) })
            if (sel != null && !failed && canAct) {
                add("Delete" to sel.onDelete)
                add("Select" to sel.onSelect)
            }
            if (m.media && m.outgoing && m.status == MessageStatus.PENDING.name) add("Cancel" to { onDelete(m.clientMsgId) })
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
fun GroupInfoScreen(vm: GroupInfoViewModel, onBack: () -> Unit, tabsItems: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pick = ai.risicloud.risime.ui.common.rememberPhotoCropper("Move and scale", onCropped = vm::setPhoto, onError = vm::photoError, hasPhoto = ui.hasPhoto, onRemove = vm::removePhoto)
    GroupInfoContent(
        ui, onBack = onBack, onAdd = vm::add, onRemove = vm::remove, onSetAdmin = vm::setRole, onRename = vm::rename,
        onLeave = vm::leave, onReset = vm::reset, onDismissError = vm::dismissError,
        onSetPhoto = pick.takeIf { c -> ui.iAmAdmin && !ui.readOnly }, onRemovePhoto = vm::removePhoto,
        tabsItems = tabsItems,
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
    /** §24.4/§24.9 (tabs on): the Official switch, its history, the Official members and both tabs' media. */
    tabsItems: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
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
                    Box {
                        Box(
                            if (onSetPhoto != null) Modifier.clickable(onClickLabel = "Change group photo") { onSetPhoto() } else Modifier,
                        ) { InitialsAvatar(ui.name, size = Sizes.avatarLarge, photoKey = ui.conversationId) }
                    }
                    ui.photoBusy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.heightIn(min = Spacing.sm))
                    Text(ui.name, style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(
                        ui.stateLine ?: if (ui.memberCount == 1) "1 member" else "${ui.memberCount} members",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // §12.0: groups are E2EE-only (decision 048: chat info states it).
                    ai.risicloud.risime.ui.chat.E2eeInfoLine(encrypted = true, notEncryptedText = null)
                    if (ui.photosNeedUpdate.isNotEmpty()) {
                        Text(
                            "Can't see photos yet: " + ui.photosNeedUpdate.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (ui.callsNeedUpdate.isNotEmpty()) {
                        Text(
                            "Need to update for group calls: " + ui.callsNeedUpdate.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (ui.iAmAdmin) TextButton(onClick = { renaming = true }) { Text("Rename", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
            }
            ui.error?.let { e ->
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
                        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Text(e, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = onDismissError) { Text("OK", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            item { SectionHeader("Members", Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) }
            if (ui.iAmAdmin) {
                item {
                    TextButton(onClick = { adding = true }, modifier = Modifier.padding(horizontal = Spacing.sm).heightIn(min = Sizes.minTouch)) { Text("Add members", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
            }
            items(ui.members, key = { it.userId }) { m -> MemberRow(m, ui.iAmAdmin, onRemove, onSetAdmin) }
            item { HorizontalDivider(Modifier.padding(vertical = Spacing.sm)) }
            tabsItems?.let { extra ->
                extra()
                item { HorizontalDivider(Modifier.padding(vertical = Spacing.sm)) }
            }
            if (!ui.readOnly) {
                item {
                    TextButton(onClick = { confirmLeave = true }, modifier = Modifier.padding(horizontal = Spacing.sm).heightIn(min = Sizes.minTouch)) {
                        Text("Leave group", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (ui.iAmAdmin) {
                item {
                    // §12.12.3 (A6): held back while this phone is still being re-added (the server would refuse).
                    TextButton(onClick = { confirmReset = true }, enabled = !ui.resetBlocked, modifier = Modifier.padding(horizontal = Spacing.sm).heightIn(min = Sizes.minTouch)) {
                        Text("Reset encryption", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
            "Reset encryption?", ai.risicloud.risime.data.mls.RESET_CONFIRM_TEXT,
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
                Text(if (m.me) "${m.name} (you)" else m.name, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                when (m.state) {
                    GroupMember.STATE_PENDING_ADD -> Text("Adding…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GroupMember.STATE_PENDING_REMOVE -> Text("Removing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> if (m.newPhone) Text("${m.name}'s new phone is being added", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (m.admin) Text("Admin", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
        title = { Text("Group name", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onRename(name); onDismiss() }, enabled = name.isNotBlank()) { Text("Save", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
    )
}

@Composable
private fun AddMembersDialog(candidates: List<PickFriend>, onAdd: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add members", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        text = {
            Box(Modifier.heightIn(max = 420.dp)) {
                FriendPicker(candidates, selected, query, { query = it }, { id -> selected = if (id in selected) selected - id else selected + id },
                    emptyText = "All your friends are already in this group.")
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(selected); onDismiss() }, enabled = selected.isNotEmpty()) { Text("Add", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
    )
}

/**
 * §20.4 Join from a line: the microphone (and, for a video call, the camera: a denied camera joins
 * with it off, android A2), asked once, then [onGranted].
 */
@Composable
private fun JoinCallPermissions(video: Boolean, onDenied: (String) -> Unit, onGranted: (camera: Boolean) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val mic = r[android.Manifest.permission.RECORD_AUDIO] ?: granted(android.Manifest.permission.RECORD_AUDIO)
        if (!mic) onDenied("RisiMe needs the microphone for calls") else onGranted(video && (r[android.Manifest.permission.CAMERA] ?: granted(android.Manifest.permission.CAMERA)))
    }
    LaunchedEffect(Unit) {
        val perms = listOfNotNull(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA.takeIf { video })
        if (perms.all { granted(it) }) onGranted(video) else ask.launch(perms.toTypedArray())
    }
}
