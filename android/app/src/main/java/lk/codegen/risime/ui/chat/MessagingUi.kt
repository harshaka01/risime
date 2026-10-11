package lk.codegen.risime.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.StarEntity
import lk.codegen.risime.data.media.FileMeta
import lk.codegen.risime.data.messaging.CopyFormat
import lk.codegen.risime.data.messaging.ForwardRules
import lk.codegen.risime.data.messaging.ForwardTarget
import lk.codegen.risime.data.messaging.SelectionRules
import lk.codegen.risime.net.Forwarding
import lk.codegen.risime.net.ReplyRef
import lk.codegen.risime.ui.theme.Spacing

/** v1.34 §33 UI strings (docs/status/android.md lists them for the redroid gate). */
object MessagingStrings {
    const val COPY = "Copy"
    const val FORWARD = "Forward"
    const val SHARE = "Share"
    const val STAR = "Star"
    const val UNSTAR = "Unstar"
    const val REPLY = "Reply"
    const val INFO = "Info"
    const val DELETE = "Delete"
    const val SELECT_TEXT = "Select text"
    const val EXPORT_PDF = "Export PDF"
    const val COPIED = "Copied"
    fun copiedN(n: Int) = if (n == 1) COPIED else "Copied $n messages"
    const val FORWARDED = "Forwarded"
    const val FORWARDED_MANY = "Forwarded many times"
    const val QUOTE_MISSING = "Original message isn't on this phone"
    const val QUOTE_DELETED = "This message was deleted"
    const val QUOTE_NOT_LOADED = "Original message isn't loaded"
    fun replyTo(name: String) = "Reply to $name"
    const val STARRED_MESSAGES = "Starred messages"
    const val STARRED = "Starred"
    const val STARS_LOCAL = "Stars are kept on this phone"
    const val NO_STARS = "No starred messages"
    const val INFO_TITLE = "Message info"
    const val SENT = "Sent"
    const val DELIVERED = "Delivered"
    const val READ = "Read"
    const val READ_BY = "Read by"
    const val DELIVERED_TO = "Delivered to"
    const val WAITING = "Waiting"
    const val RECEIPTS_GONE = "Receipts are no longer available"
    const val COULDNT_REFRESH = "Couldn't refresh · Retry"
    const val SELECT_CANCEL = "Cancel selection"
    fun selected(n: Int) = "$n selected"
    const val MORE = "More options"
}

/**
 * Per chat (one conversation, one tab): stars, the reply target, the forward picker, Info and the
 * actions of the selection bar (§33.2–§33.12).
 */
class MessagingController(
    private val c: AppContainer,
    private val scope: CoroutineScope,
    val me: String,
    val conversationId: String,
) {
    /** §33.11 this conversation's starred message ids (lowercase). */
    val starred: StateFlow<Set<String>> = c.db.stars().idsIn(conversationId).map { l -> l.map { it.lowercase() }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** §33.9 the composer's quote bar. */
    val replyTo = MutableStateFlow<MessageEntity?>(null)

    /** §33.4 the open forward picker's sources (chat order), null: closed. */
    val forwarding = MutableStateFlow<List<MessageEntity>?>(null)

    /** §33.12 the open Info screen. */
    val info = MutableStateFlow<MessageEntity?>(null)
    val toast = MutableStateFlow<String?>(null)

    /** §24.1 Private unless MLS says Official. */
    val private: Boolean get() = c.chatTabs.private(conversationId)

    /** The user's own display name (§33.3: own lines read it, not "You"). */
    @Volatile private var ownName: String? = null

    init {
        scope.launch { ownName = runCatching { c.sessionStore.current()?.user?.displayName }.getOrNull() }
    }

    fun isStarred(m: MessageEntity): Boolean = m.messageId?.lowercase()?.let { it in starred.value } == true

    /** §33.3 Copy: the clipboard ("RisiMe"); Private copies are marked sensitive (API 33+); a toast on ≤ 32 only. */
    fun copy(context: android.content.Context, rows: List<MessageEntity>, nameOf: (String) -> String?) {
        val ownName = ownName ?: "Me"
        val text = CopyFormat.format(
            rows, me, ownName, nameOf, java.util.Locale.getDefault(), android.text.format.DateFormat.is24HourFormat(context),
            java.time.ZoneId.systemDefault(), CopyFormat.IcuDates,
        ) ?: return
        val clip = android.content.ClipData.newPlainText(CopyFormat.CLIP_LABEL, text)
        if (private && android.os.Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
        } else if (private) {
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(clip)
        if (android.os.Build.VERSION.SDK_INT <= 32) android.widget.Toast.makeText(context, MessagingStrings.copiedN(rows.size), android.widget.Toast.LENGTH_SHORT).show()
        scope.launch { runCatching { c.behaviour.copied(rows.size) } }
    }

    /** §33.11 Star / Unstar (device-local; only rows with a message_id). */
    fun setStar(rows: List<MessageEntity>, on: Boolean) {
        val ids = rows.mapNotNull { it.messageId }
        if (ids.isEmpty()) return
        scope.launch {
            if (on) c.db.stars().put(ids.map { StarEntity(it, conversationId, System.currentTimeMillis()) }) else c.db.stars().remove(ids)
        }
    }

    fun startForward(rows: List<MessageEntity>) {
        forwarding.value = rows
    }

    fun reply(m: MessageEntity) {
        if (SelectionRules.replyable(m)) replyTo.value = m
    }

    fun takeReply(): ReplyRef? = replyTo.value?.let { t ->
        replyTo.value = null
        t.messageId?.let { ReplyRef(it, t.from) }
    }

    fun openInfo(m: MessageEntity) {
        info.value = m
    }

    /** §33.10 the Android share sheet through the pipe-backed [lk.codegen.risime.ui.share.ShareProvider]. */
    fun share(context: android.content.Context, rows: List<MessageEntity>, nameOf: (String) -> String?) {
        val ownName = ownName ?: "Me"
        scope.launch {
            val r = lk.codegen.risime.ui.share.ShareIntents.build(context, c, rows, me, ownName, nameOf)
            when (r) {
                is lk.codegen.risime.ui.share.ShareIntents.Result.Ready -> {
                    runCatching { context.startActivity(android.content.Intent.createChooser(r.intent, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    runCatching { c.behaviour.shared(rows.size) }
                }
                is lk.codegen.risime.ui.share.ShareIntents.Result.Refused -> toast.value = r.why
            }
        }
    }
}

/**
 * §33.2 the selection bar: the selected count and Copy, Forward, Share, Star, Reply, Info, Delete.
 * Five fit as icons; Share and Info are in the overflow. A disabled action is never applied to a subset.
 */
@Composable
fun SelectionBar(
    actions: SelectionRules.Actions,
    deleteEnabled: Boolean,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onShare: () -> Unit,
    onStar: () -> Unit,
    onReply: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    onForwardBlocked: (String) -> Unit = {},
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.testTag("selection_bar")) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, MessagingStrings.SELECT_CANCEL) }
            Text(MessagingStrings.selected(actions.count), Modifier.weight(1f).testTag("selection_count"), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            IconButton(onClick = onReply, enabled = actions.reply, modifier = Modifier.testTag("sel_reply")) { Icon(lk.codegen.risime.ui.common.RisiIcons.Reply, MessagingStrings.REPLY) }
            IconButton(onClick = onStar, enabled = actions.star, modifier = Modifier.testTag("sel_star")) {
                Icon(if (actions.unstar) lk.codegen.risime.ui.common.RisiIcons.StarBorder else Icons.Default.Star, if (actions.unstar) MessagingStrings.UNSTAR else MessagingStrings.STAR)
            }
            IconButton(onClick = onCopy, enabled = actions.copy, modifier = Modifier.testTag("sel_copy")) { Icon(lk.codegen.risime.ui.common.RisiIcons.ContentCopy, MessagingStrings.COPY) }
            IconButton(
                onClick = { if (actions.forward) onForward() else actions.forwardWhyNot?.let(onForwardBlocked) },
                modifier = Modifier.testTag("sel_forward"),
            ) {
                Icon(lk.codegen.risime.ui.common.RisiIcons.Forward, MessagingStrings.FORWARD, tint = if (actions.forward) androidx.compose.material3.LocalContentColor.current else androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.38f))
            }
            IconButton(onClick = onDelete, enabled = deleteEnabled, modifier = Modifier.testTag("sel_delete")) { Icon(Icons.Default.Delete, "Delete selected") }
            var more by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.testTag("sel_more")) { Icon(Icons.Default.MoreVert, MessagingStrings.MORE) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(MessagingStrings.SHARE) }, onClick = { more = false; onShare() }, enabled = actions.share, modifier = Modifier.testTag("sel_share"))
                    DropdownMenuItem(text = { Text(MessagingStrings.INFO) }, onClick = { more = false; onInfo() }, enabled = actions.info, modifier = Modifier.testTag("sel_info"))
                }
            }
        }
    }
}


/** §33.4 the label above a forwarded bubble's content (the number is never shown). */
@Composable
fun ForwardedLabel(hops: Int?) {
    hops ?: return
    val many = hops >= Forwarding.MANY_TIMES
    Text(
        (if (many) "⇉ " else "↪ ") + (if (many) MessagingStrings.FORWARDED_MANY else MessagingStrings.FORWARDED),
        style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
        color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.65f),
        modifier = Modifier.testTag("forwarded_label"),
    )
}

/** What a quote shows (§33.9), resolved from this phone's own copy of the target. */
sealed interface QuoteView {
    data class Found(val name: String, val text: String, val clientMsgId: String) : QuoteView

    data class Missing(val name: String) : QuoteView

    data object Deleted : QuoteView
}

object Quotes {
    /** First 2 lines of the body or caption, or "📷 Photo" / "📄 <name>". */
    fun preview(m: MessageEntity): String = when {
        m.image -> m.body.takeIf { it.isNotBlank() }?.let { "📷 " + firstLines(it) } ?: "📷 Photo"
        m.file -> "📄 " + (FileMeta.decode(m.systemJson)?.let { lk.codegen.risime.data.media.FileEnvelope.displayName(it.name) } ?: "File")
        m.risiCtl -> CopyFormat.requestText(m)?.let(::firstLines) ?: ""
        else -> firstLines(m.body)
    }

    fun firstLines(s: String, n: Int = 2): String = CopyFormat.normalise(s).lines().take(n).joinToString("\n")

    /**
     * [rows] = this conversation's loaded rows; [hiddenDeleted] = the target is a hidden tombstone. A
     * malformed reply or one whose target is in another conversation shows no quote (null).
     */
    fun resolve(m: MessageEntity, rows: List<MessageEntity>, nameOf: (String) -> String, hiddenDeleted: Boolean): QuoteView? {
        val id = m.replyToMessageId ?: return null
        val target = rows.firstOrNull { it.messageId.equals(id, true) }
        if (target == null) return if (hiddenDeleted) QuoteView.Deleted else QuoteView.Missing(nameOf(m.replyToFrom ?: ""))
        if (target.conversationId != m.conversationId) return null
        if (target.showsAsDeleted) return QuoteView.Deleted
        // The target's authenticated sender (the local row), never the reply's `from`.
        return QuoteView.Found(nameOf(target.from), preview(target), target.clientMsgId)
    }
}

/** §33.9 the quote block above a reply's content; a tap scrolls to the target and flashes it. */
@Composable
fun QuoteBlock(q: QuoteView, onTap: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xxs).then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier).testTag("quote_block"),
    ) {
        Row {
            Box(Modifier.width(3.dp).heightIn(min = 36.dp).background(MaterialTheme.colorScheme.primary))
            Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs)) {
                when (q) {
                    is QuoteView.Found -> {
                        Text(q.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(q.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    is QuoteView.Missing -> {
                        Text(MessagingStrings.replyTo(q.name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        Text(MessagingStrings.QUOTE_MISSING, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic))
                    }
                    QuoteView.Deleted -> Text(MessagingStrings.QUOTE_DELETED, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic))
                }
            }
        }
    }
}

/** §33.9 the composer's quote bar: the name and first line, with an ✕. */
@Composable
fun ReplyComposerBar(target: MessageEntity, name: String, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs).testTag("reply_bar")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).heightIn(min = 40.dp).background(MaterialTheme.colorScheme.primary))
            Column(Modifier.weight(1f).padding(horizontal = Spacing.sm, vertical = Spacing.xxs)) {
                Text(MessagingStrings.replyTo(name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Quotes.firstLines(Quotes.preview(target), 1), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cancel reply") }
        }
    }
}

/**
 * §33.4 the forward picker: search, "Recent chats", then all chats A–Z; each tab its own target
 * ("🔒 Private" / "● Official"); up to 5 targets (1 when forwarded many times); an optional message.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ForwardPickerSheet(
    c: AppContainer,
    sources: List<MessageEntity>,
    sourcePrivate: Boolean,
    onDone: (Int) -> Unit,
    onDismiss: () -> Unit,
    noteText: (MessageEntity) -> String? = { null },
) {
    TargetPickerSheet(
        c, hasImage = sources.any { it.image }, hasFile = sources.any { it.file }, max = ForwardRules.maxTargets(sources),
        sourcePrivate = sourcePrivate, title = ForwardRules.PICKER_TITLE, allowMessage = true,
        onSend = { targets, add -> c.forward(sources, targets, add, noteText) }, onDone = onDone, onDismiss = onDismiss,
    )
}

/**
 * The §33.4 target picker (forward, and the PDF's "Send to chat…"): search, "Recent chats", all chats A–Z,
 * each tab its own target, at most [max]; the §33.8 hint when [sourcePrivate] and an Official target is picked.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TargetPickerSheet(
    c: AppContainer,
    hasImage: Boolean,
    hasFile: Boolean,
    max: Int,
    sourcePrivate: Boolean,
    title: String,
    allowMessage: Boolean,
    onSend: suspend (List<String>, String?) -> Int,
    onDone: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var targets by remember { mutableStateOf<List<ForwardTarget>?>(null) }
    var picked by remember { mutableStateOf(listOf<ForwardTarget>()) }
    var query by remember { mutableStateOf("") }
    var add by remember { mutableStateOf("") }
    var snack by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(hasImage, hasFile) {
        val cands = c.forwardCandidates(hasImage = hasImage, hasFile = hasFile)
        targets = ForwardRules.targets(cands, hasImage, hasFile)
    }
    fun send() {
        sending = true
        scope.launch {
            val n = onSend(picked.map { it.conversationId }, add.takeIf { allowMessage && it.isNotBlank() })
            onDone(n)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.lg).testTag("forward_picker")) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(Spacing.sm))
            OutlinedTextField(query, { query = it }, placeholder = { Text(ForwardRules.SEARCH_HINT) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("forward_search"))
            val all = targets
            if (all == null) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(Spacing.lg))
            } else {
                val (recent, az) = ForwardRules.lists(all, query)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    fun row(t: ForwardTarget, keyPrefix: String) = item(key = keyPrefix + t.conversationId) {
                        val on = picked.any { it.conversationId == t.conversationId }
                        ListItem(
                            headlineContent = { Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(t.disabledReason ?: t.label, style = MaterialTheme.typography.labelSmall) },
                            trailingContent = { Checkbox(checked = on, onCheckedChange = null, enabled = t.disabledReason == null) },
                            modifier = Modifier.clickable(enabled = t.disabledReason == null) {
                                picked = when {
                                    on -> picked.filterNot { it.conversationId == t.conversationId }
                                    picked.size >= max -> { snack = if (max == 1) ForwardRules.MANY_TIMES_SNACKBAR else "You can forward to up to $max chats"; picked }
                                    else -> picked + t
                                }
                            }.testTag("forward_target_${t.conversationId}"),
                        )
                    }
                    if (recent.isNotEmpty()) {
                        item(key = "h-recent") { Text(ForwardRules.RECENT, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = Spacing.xs)) }
                        recent.forEach { row(it, "r-") }
                        item(key = "h-all") { Text(ForwardRules.ALL, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = Spacing.xs)) }
                    }
                    az.forEach { row(it, "a-") }
                }
            }
            snack?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("forward_snack")) }
            if (allowMessage) OutlinedTextField(add, { add = it }, placeholder = { Text(ForwardRules.ADD_MESSAGE) }, modifier = Modifier.fillMaxWidth().testTag("forward_add_message"), maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    onClick = {
                        scope.launch {
                            val shown = c.privateToOfficialHintShown()
                            if (ForwardRules.hintDue(sourcePrivate, picked, shown)) hint = true else send()
                        }
                    },
                    enabled = picked.isNotEmpty() && !sending,
                    modifier = Modifier.testTag("forward_send"),
                ) { Text(ForwardRules.SEND) }
            }
        }
    }
    if (hint) {
        // §33.8: shown once per device, whatever the answer.
        AlertDialog(
            onDismissRequest = { hint = false; scope.launch { c.markPrivateToOfficialHintShown() } },
            title = { Text(ForwardRules.HINT_TITLE) },
            text = { Text(ForwardRules.HINT_BODY) },
            confirmButton = { TextButton(onClick = { hint = false; scope.launch { c.markPrivateToOfficialHintShown(); send() } }, modifier = Modifier.testTag("hint_forward")) { Text(ForwardRules.HINT_FORWARD) } },
            dismissButton = { TextButton(onClick = { hint = false; scope.launch { c.markPrivateToOfficialHintShown() } }) { Text(ForwardRules.HINT_CANCEL) } },
            modifier = Modifier.testTag("forward_hint"),
        )
    }
}

/** §33.12 Info for my own DM message: Sent, Delivered and Read times (older rows: the tick state with "—"). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DmInfoSheet(m: MessageEntity, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.lg, vertical = Spacing.sm).testTag("info_sheet")) {
            Text(MessagingStrings.INFO_TITLE, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(Spacing.sm))
            Surface(color = lk.codegen.risime.ui.theme.RisiTheme.colors.bubbleMine, shape = MaterialTheme.shapes.medium) {
                Text(Quotes.preview(m), Modifier.padding(Spacing.sm), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
            InfoRow(MessagingStrings.SENT, CopyFormat.timeOf(m).takeIf { m.serverTs != null })
            InfoRow(MessagingStrings.DELIVERED, m.deliveredAt, reached = m.status == "DELIVERED" || m.status == "READ")
            InfoRow(MessagingStrings.READ, m.readAt, reached = m.status == "READ")
        }
    }
}

@Composable
private fun InfoRow(label: String, at: Long?, reached: Boolean = at != null) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            at?.let { infoStamp(it) } ?: if (reached) "✓ —" else "—",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun infoStamp(ms: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm").format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

/** What a chat screen hands its bubbles and its selection bar for §33 (one per composition). */
class ChatMessaging(
    val c: AppContainer,
    val ctl: MessagingController,
    val files: FileActions,
    val rows: List<MessageEntity>,
    val media: Map<String, MediaEntity>,
    val starred: Set<String>,
    /** The name the chat shows for a sender. */
    val nameOf: (String) -> String,
    val onJump: (String) -> Unit,
    /** The composer is there (not read-only): Reply is offered. */
    val canSend: Boolean,
    /** The row flashed after a quote tap (client_msg_id). */
    val flash: String? = null,
) {
    val nameOrNull: (String) -> String? = { id -> nameOf(id).takeIf { it.isNotBlank() } }
}

/**
 * §33.2 the long-press menu's extra items (Reply, Forward, Star, Share, Info, Select text; Save for a
 * file) under the same rules as the selection bar.
 */
fun messagingMenuItems(
    m: MessageEntity,
    x: ChatMessaging,
    context: android.content.Context,
    onSelectText: () -> Unit,
): List<Pair<String, () -> Unit>> = buildList {
    val now = System.currentTimeMillis()
    val a = SelectionRules.actions(listOf(m), x.ctl.me, x.media, x.starred, now, x.canSend)
    if (a.reply) add(MessagingStrings.REPLY to { x.ctl.reply(m) })
    if (a.forward) add(MessagingStrings.FORWARD to { x.ctl.startForward(listOf(m)) })
    if (a.star) add((if (a.unstar) MessagingStrings.UNSTAR else MessagingStrings.STAR) to { x.ctl.setStar(listOf(m), !a.unstar) })
    if (a.share) add(MessagingStrings.SHARE to { x.ctl.share(context, listOf(m), x.nameOrNull) })
    if (a.info) add(MessagingStrings.INFO to { x.ctl.openInfo(m) })
    if (m.file && FileMeta.decode(m.systemJson)?.parts == false) add(FileStrings.SAVE to { x.files.save(context, m) })
    if (SelectionRules.selectable(m) && !m.file && (m.kind == MessageEntity.KIND_TEXT || (m.image && m.body.isNotBlank()))) add(MessagingStrings.SELECT_TEXT to onSelectText)
}

/**
 * §33.2 / §33.14 a Risi card's long-press menu: Copy, Forward, Star, Share, Export PDF (summary, report,
 * answer, digest, discussion_summary, note card) and Select; in select mode a tap toggles it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RisiCardActions(m: MessageEntity, x: ChatMessaging?, sel: MsgSelect?, content: @Composable () -> Unit) {
    if (x == null) { content(); return }
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val kind = SelectionRules.risiKind(m)
    Box(
        Modifier.fillMaxWidth()
            .then(if (sel?.selected == true || (x.flash != null && x.flash == m.clientMsgId)) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)) else Modifier)
            .androidxCombinedClickable(
                onClick = { if (sel?.selecting == true) sel.onToggle() },
                onLongClick = { if (sel?.selecting == true) sel.onToggle() else menu = true },
            )
            .testTag("risi_card_actions"),
    ) {
        content()
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val a = SelectionRules.actions(listOf(m), x.ctl.me, x.media, x.starred, System.currentTimeMillis(), x.canSend)
            if (a.copy) DropdownMenuItem(text = { Text(MessagingStrings.COPY) }, onClick = { menu = false; x.ctl.copy(context, listOf(m), x.nameOrNull) })
            if (a.forward) DropdownMenuItem(text = { Text(MessagingStrings.FORWARD) }, onClick = { menu = false; x.ctl.startForward(listOf(m)) })
            if (a.star) DropdownMenuItem(text = { Text(if (a.unstar) MessagingStrings.UNSTAR else MessagingStrings.STAR) }, onClick = { menu = false; x.ctl.setStar(listOf(m), !a.unstar) })
            if (a.share) DropdownMenuItem(text = { Text(MessagingStrings.SHARE) }, onClick = { menu = false; x.ctl.share(context, listOf(m), x.nameOrNull) })
            if (a.reply) DropdownMenuItem(text = { Text(MessagingStrings.REPLY) }, onClick = { menu = false; x.ctl.reply(m) })
            if (kind in lk.codegen.risime.ui.pdf.PdfExport.KINDS && m.messageId != null) {
                DropdownMenuItem(
                    text = { Text(MessagingStrings.EXPORT_PDF) },
                    onClick = { menu = false; x.c.requestPdf(lk.codegen.risime.net.PdfSources.message(m.conversationId, m.messageId)) },
                    modifier = Modifier.testTag("menu_export_pdf"),
                )
            }
            sel?.let { s -> DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; s.onSelect() }) }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.androidxCombinedClickable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))

/** §33.2 "Select text": the body in a SelectionContainer (the system copy / select-all toolbar works on part of it). */
@Composable
fun SelectTextDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(text, modifier = Modifier.testTag("select_text")) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * Everything a chat screen hosts for §33: the forward picker (and its hint), Info ([groupInfo] for a
 * group: the receipts sheet), the "Starred messages" sheet, the APK warning, the 26–28 save dialog and
 * the toasts.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MessagingHost(
    x: ChatMessaging,
    starredOpen: Boolean,
    onStarredClose: () -> Unit,
    groupInfo: ((MessageEntity) -> Unit)? = null,
    noteText: (MessageEntity) -> String? = { null },
) {
    val context = LocalContext.current
    val forwarding by x.ctl.forwarding.collectAsState()
    val info by x.ctl.info.collectAsState()
    val apk by x.files.apkWarning.collectAsState()
    val pendingSave by x.files.pendingSave.collectAsState()
    forwarding?.let { sources ->
        ForwardPickerSheet(
            x.c, sources, sourcePrivate = x.ctl.private,
            onDone = { n -> x.ctl.forwarding.value = null; x.ctl.toast.value = ForwardRules.done(n) },
            onDismiss = { x.ctl.forwarding.value = null },
            noteText = noteText,
        )
    }
    info?.let { m ->
        if (groupInfo != null) {
            LaunchedEffect(m.clientMsgId) { x.ctl.info.value = null; groupInfo(m) }
        } else {
            DmInfoSheet(x.rows.firstOrNull { it.clientMsgId == m.clientMsgId } ?: m) { x.ctl.info.value = null }
        }
    }
    if (starredOpen) {
        ModalBottomSheet(onDismissRequest = onStarredClose) {
            Text(MessagingStrings.STARRED_MESSAGES, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.lg))
            val rows = x.rows.filter { r -> r.messageId?.lowercase()?.let { it in x.starred } == true }.reversed()
            StarredList(rows.map { StarredRow(it, 0, "", "") }, showChat = false, onOpen = { onStarredClose(); x.onJump(it.clientMsgId) }, onUnstar = { x.ctl.setStar(listOf(it), false) })
            Spacer(Modifier.size(Spacing.lg))
        }
    }
    apk?.let { m ->
        AlertDialog(
            onDismissRequest = { x.files.apkWarning.value = null },
            text = { Text(FileStrings.APK_WARNING) },
            confirmButton = { TextButton(onClick = { x.files.apkWarning.value = null; x.files.save(context, m) }) { Text(FileStrings.SAVE) } },
            dismissButton = { TextButton(onClick = { x.files.apkWarning.value = null }) { Text("Cancel") } },
        )
    }
    val createDoc = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val m = x.files.pendingSave.value
        if (uri != null && m != null) x.files.saveToUri(context, m, uri) else x.files.pendingSave.value = null
    }
    LaunchedEffect(pendingSave) {
        pendingSave?.let { m -> createDoc.launch(FileMeta.decode(m.systemJson)?.let { lk.codegen.risime.data.media.FileEnvelope.displayName(it.name) } ?: "file") }
    }
    MessagingToast(x.ctl)
    val ft by x.files.toast.collectAsState()
    ft?.let { t ->
        ImageToast(t)
        LaunchedEffect(t) { kotlinx.coroutines.delay(3_000); if (x.files.toast.value == t) x.files.toast.value = null }
    }
    // §33.13: opened-file temporaries go when the user returns to the app.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) FileActions.cleanupOpened(context) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}

/** §33.9 jump to a message (the quote's target) and flash it; the list is reverse-laid-out with day rows. */
suspend fun jumpTo(scroll: lk.codegen.risime.ui.common.ChatScrollState, messages: List<MessageEntity>, clientMsgId: String): Boolean {
    val items = withDaySeparators(messages, System.currentTimeMillis())
    val i = items.indexOfFirst { it.key == clientMsgId }
    if (i < 0) return false
    scroll.list.animateScrollToItem(items.lastIndex - i)
    return true
}

/** The per-row hooks a bubble needs for §33 (null pieces: nothing to show). */
class BubbleExtras(
    val forwardHops: Int?,
    val quote: QuoteView?,
    val starred: Boolean,
    val onQuoteTap: (() -> Unit)?,
)

/** §33 the extras of [m]: label, quote (resolved locally) and the star icon. */
@Composable
fun bubbleExtras(m: MessageEntity, rows: List<MessageEntity>, starred: Set<String>, nameOf: (String) -> String, onJump: (String) -> Unit): BubbleExtras {
    val hidden by androidx.compose.runtime.produceState(false, m.replyToMessageId, rows) {
        val id = m.replyToMessageId
        value = id != null && rows.none { it.messageId.equals(id, true) } && runCatching { lk.codegen.risime.ui.chat.MessagingHidden.isHiddenTombstone(id) }.getOrDefault(false)
    }
    val q = remember(m.replyToMessageId, rows, hidden) { Quotes.resolve(m, rows, nameOf, hidden) }
    return BubbleExtras(
        forwardHops = m.forwardHops,
        quote = q,
        starred = m.messageId?.lowercase()?.let { it in starred } == true,
        onQuoteTap = (q as? QuoteView.Found)?.let { f -> { onJump(f.clientMsgId) } },
    )
}

/** §33.9 a hidden tombstone (`deleted_ids`) for a quote whose target isn't stored (set once by the app). */
object MessagingHidden {
    @Volatile var lookup: suspend (String) -> Boolean = { false }

    suspend fun isHiddenTombstone(messageId: String): Boolean = lookup(messageId.lowercase())
}

/** Starred row for the Starred screens. */
data class StarredRow(val m: MessageEntity, val starredAt: Long, val chatName: String, val tabLabel: String)

/**
 * §33.11 "Starred messages" (one conversation) and the chat list's "Starred" (all, newest star first,
 * with the chat name and tab). "Stars are kept on this phone". A tap jumps to the message.
 */
@Composable
fun StarredList(rows: List<StarredRow>, showChat: Boolean, onOpen: (MessageEntity) -> Unit, onUnstar: (MessageEntity) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(MessagingStrings.STARS_LOCAL, Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (rows.isEmpty()) {
            Text(MessagingStrings.NO_STARS, Modifier.padding(Spacing.lg).testTag("starred_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth().testTag("starred_list")) {
            items(rows, key = { it.m.clientMsgId }) { r ->
                ListItem(
                    overlineContent = if (showChat) ({ Text("${r.chatName} · ${r.tabLabel}", maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
                    headlineContent = { Text(Quotes.preview(r.m), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(infoStamp(CopyFormat.timeOf(r.m)), style = MaterialTheme.typography.labelSmall) },
                    trailingContent = { IconButton(onClick = { onUnstar(r.m) }) { Icon(Icons.Default.Star, MessagingStrings.UNSTAR) } },
                    modifier = Modifier.clickable { onOpen(r.m) },
                )
            }
        }
    }
}

/** The media rows a selection needs for its rules (client_msg_id → media). */
fun selectionActions(rows: List<MessageEntity>, selection: Set<String>, me: String, media: Map<String, MediaEntity>, starred: Set<String>, canSend: Boolean): SelectionRules.Actions {
    val selected = rows.filter { it.clientMsgId in selection }
    return SelectionRules.actions(selected, me, media, starred, System.currentTimeMillis(), canSend)
}

@Composable
fun MessagingToast(ctl: MessagingController) {
    val t by ctl.toast.collectAsState()
    t?.let { text ->
        ImageToast(text)
        LaunchedEffect(text) {
            kotlinx.coroutines.delay(3_000)
            if (ctl.toast.value == text) ctl.toast.value = null
        }
    }
}

@Composable
fun rememberContext(): android.content.Context = LocalContext.current

/** A test tag modifier (for files that don't import testTag). */
fun Modifier.Companion.testTagOf(tag: String): Modifier = Modifier.testTag(tag)
