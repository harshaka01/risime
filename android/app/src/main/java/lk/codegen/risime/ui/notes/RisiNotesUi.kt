package lk.codegen.risime.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.notes.NOTES_EMPTY
import lk.codegen.risime.data.notes.NOTES_NO_MATCH
import lk.codegen.risime.data.notes.NOTES_OFFLINE
import lk.codegen.risime.data.notes.NoteItemControl
import lk.codegen.risime.data.notes.NoteView
import lk.codegen.risime.data.notes.NotesListUi
import lk.codegen.risime.data.notes.RisiNotes
import lk.codegen.risime.data.notes.ShareUi
import lk.codegen.risime.data.tabs.LedgerItemView
import lk.codegen.risime.data.tabs.RisiControl
import lk.codegen.risime.data.tabs.RisiLedger
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.RisiNote
import lk.codegen.risime.net.RisiNoteEvent
import lk.codegen.risime.net.RisiNoteSummary
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.tabs.RisiCardContext
import lk.codegen.risime.ui.tabs.RisiEditDialog
import lk.codegen.risime.ui.theme.Spacing
import java.time.Instant

/*
 * v1.29 §30 Risi Notes: the note card in the Risi chat, the short "Notes saved" card in Official, the note
 * screen, the Notes list and the chat picker for sharing. Rules in data/notes/RisiNotes.kt.
 */

/** What an item row can do (the card and the screen pass their own senders). */
class NoteItemActions(
    val tick: (itemId: String, checked: Boolean) -> Unit,
    val send: (itemId: String, action: String, text: String?, due: String?, allDay: Boolean) -> Unit,
)

/** One agreed item: a tick-box (tracked/done), ✓ ✗ ✎ (my proposed), "waiting" or its closed state; owner and due under it. */
@Composable
fun NoteItemRow(item: LedgerItemView, control: NoteItemControl, me: String, nameOf: (String) -> String, actions: NoteItemActions) {
    var editing by remember(item.itemId) { mutableStateOf(false) }
    val dim = if (control is NoteItemControl.Closed) 0.55f else 1f
    Row(Modifier.fillMaxWidth().testTag("risi_note_item"), verticalAlignment = Alignment.Top) {
        if (control is NoteItemControl.Tick) {
            Checkbox(
                checked = control.checked, enabled = control.enabled,
                onCheckedChange = { actions.tick(item.itemId, it) },
                modifier = Modifier.testTag(if (control.checked) "risi_note_item_ticked" else "risi_note_item_tick"),
            )
        } else {
            Text("•", modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xxs))
        }
        Column(Modifier.weight(1f).padding(top = if (control is NoteItemControl.Tick) Spacing.sm else 0.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(item.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim))
            val state = when (control) {
                is NoteItemControl.Waiting -> "waiting"
                is NoteItemControl.Closed -> control.label
                else -> null
            }
            val line = listOfNotNull(RisiNotes.ownerDue(item, me, nameOf).takeIf { it.isNotEmpty() }, state).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_note_item_meta"))
            if (control is NoteItemControl.Decide) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilledTonalButton(onClick = { actions.send(item.itemId, RisiLedger.CONFIRM, null, null, false) }, modifier = Modifier.testTag("risi_note_item_confirm")) { Text("✓", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    OutlinedButton(onClick = { actions.send(item.itemId, RisiLedger.DECLINE, null, null, false) }, modifier = Modifier.testTag("risi_note_item_decline")) { Text("✗", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    OutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("risi_note_item_edit")) { Text("✎", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
            }
        }
    }
    if (editing) {
        RisiEditDialog(
            initialText = item.text, initialDue = item.due, ownerName = null,
            onSave = { t, d ->
                editing = false
                val same = d != null && item.due != null && runCatching { Instant.parse(d) == Instant.parse(item.due) }.getOrDefault(false)
                actions.send(item.itemId, RisiLedger.EDIT, t.take(RisiControl.MAX_ITEM_TEXT), d, item.allDay && same)
            },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun MeetingRow(e: RisiNoteEvent, onAccept: (() -> Unit)?, onOpen: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().testTag("risi_note_event"), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(RisiNotes.eventLine(e), style = MaterialTheme.typography.bodyLarge)
        e.myStatus?.let { Text(statusWord(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (onAccept != null || onOpen != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (onAccept != null) FilledTonalButton(onClick = onAccept, modifier = Modifier.testTag("risi_note_event_accept")) { Text("Accept", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                if (onOpen != null) OutlinedButton(onClick = onOpen, modifier = Modifier.testTag("risi_note_event_open")) { Text("Open", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }
    }
}

private fun statusWord(s: String) = when (s) {
    "proposed" -> "Proposed"
    "accepted" -> "Accepted"
    "declined" -> "Declined"
    else -> s.replaceFirstChar { it.uppercase() }
}

/** §30.4 `note_card` in my Risi chat: the title, a preview of the key points, the agreed items (live), the meetings; tap → the note. */
@Composable
fun NoteCardBody(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val note = remember(row.systemJson) { RisiNote.parse(row.systemJson) }
    if (note == null) {
        Text(row.body)
        return
    }
    val me = ctx.host.me
    val items = remember(ctx.messages, note) { RisiNotes.liveItems(note.items, note.noteId, ctx.messages, null) }
    val pending = remember(ctx.messages, ctx.nowMs) { RisiNotes.pending(ctx.messages, me, ctx.nowMs) }
    val expired = RisiLedger.expired(r, ctx.nowMs)
    val title = RisiNotes.title(me, ctx.host.myName, note.withUsers, ctx.nameOf, note.topic, note.endedAt)
    val actions = NoteItemActions(
        tick = { id, checked -> ctx.host.actItem(id, RisiNotes.tickAction(checked)) },
        send = { id, a, t, d, all -> ctx.host.actItem(id, a, t, d, all) },
    )
    Column(Modifier.fillMaxWidth().testTag("risi_note_card"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        // The title and the key points open the note (the items keep their own controls).
        Column(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Open note") { ctx.host.openNote(note.noteId) },
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("risi_note_title"))
            note.keyPoints.take(PREVIEW_POINTS).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("risi_note_key_point")) }
            if (note.keyPoints.size > PREVIEW_POINTS) Text("…", style = MaterialTheme.typography.bodyMedium)
        }
        if (items.isNotEmpty()) {
            Text("Agreed", style = MaterialTheme.typography.titleSmall)
            items.forEach { i -> NoteItemRow(i, RisiNotes.control(me, i, expired, pending[i.itemId.lowercase()], ctx.readOnly), me, ctx.nameOf, actions) }
        }
        if (note.events.isNotEmpty()) {
            Text("Meetings", style = MaterialTheme.typography.titleSmall)
            note.events.forEach { Text("• " + RisiNotes.eventLine(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("risi_note_event")) }
        }
        TextButton(onClick = { ctx.host.openNote(note.noteId) }, modifier = Modifier.testTag("risi_note_open")) { Text("Open note", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
    }
}

private const val PREVIEW_POINTS = 3

/** §30.4 `notes_saved` in Official: the short line; [Open] (my note) for participants on a notes device. */
@Composable
fun NotesSavedCardBody(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    Column(Modifier.testTag("risi_notes_saved"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(r.summary ?: row.body, style = MaterialTheme.typography.bodyLarge)
        val participant = r.withUsers.any { it.equals(ctx.host.me, true) }
        if (participant) {
            Text(
                RisiNotes.countsLine(r.itemsCount ?: 0, null, r.eventsCount ?: 0), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_notes_saved_counts"),
            )
            val id = r.noteId
            if (id != null && ctx.host.notesOn) {
                TextButton(onClick = { ctx.host.openNote(id) }, modifier = Modifier.testTag("risi_notes_saved_open")) { Text(RisiNotes.SAVED_LINE) }
            }
        }
    }
}

/** The source line under a note's title: "Chat · 09:12–09:31", "Video call · 32 min", "Asked Risi". */
fun noteSourceLine(n: RisiNote): String = when (n.source) {
    RisiNote.SOURCE_CALL -> (if (n.media == "video") "Video call" else "Call") + (n.durationS?.let { " · ${maxOf(1L, (it + 30) / 60)} min" } ?: "")
    RisiNote.SOURCE_REQUEST -> "Asked Risi"
    else -> "Chat"
}

/** §30.6 the full note. */
@Composable
fun NoteScreen(
    view: NoteView?,
    title: String?,
    loading: Boolean,
    error: String?,
    notice: String?,
    me: String,
    nameOf: (String) -> String,
    canDelete: Boolean,
    canAnswerEvents: Boolean,
    actions: NoteItemActions,
    onAcceptEvent: (String) -> Unit,
    onOpenEvent: ((String) -> Unit)?,
    onOpenChat: ((RisiNote) -> Unit)?,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    /** v1.34 §33.14 ⋮ → "Export PDF" (null: no item). */
    onExportPdf: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            RisiTopBar(title = "Note", onBack = onBack, actions = {
                if (view != null) IconButton(onClick = onShare, modifier = Modifier.testTag("risi_note_share")) { Icon(Icons.Filled.Share, "Share into a chat") }
                if ((canDelete || onExportPdf != null) && view != null) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("risi_note_menu")) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        onExportPdf?.let { f -> DropdownMenuItem(text = { Text("Export PDF", maxLines = 1) }, onClick = { menu = false; f() }, modifier = Modifier.testTag("risi_note_export_pdf")) }
                        if (canDelete) DropdownMenuItem(text = { Text("Delete from my notes", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, onClick = { menu = false; confirmDelete = true }, modifier = Modifier.testTag("risi_note_delete"))
                    }
                }
            })
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = Spacing.xl)) {
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("risi_note_notice")) }
            when {
                view == null && loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(Spacing.xl))
                view == null -> Text(error ?: "", modifier = Modifier.padding(vertical = Spacing.lg).testTag("risi_note_error"))
                else -> LazyColumn(Modifier.fillMaxSize().testTag("risi_note_screen"), contentPadding = WindowInsets.navigationBars.asPaddingValues(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    val n = view.note
                    item { Text(title ?: n.topic, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = Spacing.md).testTag("risi_note_screen_title")) }
                    item { Text(noteSourceLine(n), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (n.keyPoints.isNotEmpty()) {
                        item { Text("Key points", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm)) }
                        items(n.keyPoints) { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("risi_note_key_point")) }
                    }
                    if (view.items.isNotEmpty()) {
                        item { Text("Agreed", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm)) }
                        items(view.items, key = { "i:" + it.itemId }) { i ->
                            NoteItemRow(i, RisiNotes.control(me, i, view.expired, view.pending[i.itemId.lowercase()]), me, nameOf, actions)
                        }
                    }
                    if (n.events.isNotEmpty()) {
                        item { Text("Meetings", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm)) }
                        items(n.events, key = { "e:" + it.eventId }) { e ->
                            MeetingRow(
                                e,
                                onAccept = if (canAnswerEvents && RisiNotes.canAccept(e)) ({ onAcceptEvent(e.eventId) }) else null,
                                onOpen = onOpenEvent?.let { o -> { o(e.eventId) } },
                            )
                        }
                    }
                    if (onOpenChat != null && n.conversationId != null) {
                        item { TextButton(onClick = { onOpenChat(n) }, modifier = Modifier.testTag("risi_note_open_chat")) { Text("Open chat", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } }
                    }
                    item {
                        HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                        Text(RisiNotes.INFO_LINE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.xl))
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            text = { Text("It leaves your Notes list. Agreed items, promises and meetings stay; so does the card in your Risi chat.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }, modifier = Modifier.testTag("risi_note_delete_confirm")) { Text("Delete", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
        )
    }
}

/** §30.6 the Notes list: search (server), open, share, delete, delete all. */
@Composable
fun NotesListScreen(
    s: NotesListUi,
    titleOf: (RisiNoteSummary) -> String,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMore: () -> Unit,
    onAskDeleteAll: () -> Unit,
    onConfirmDeleteAll: () -> Unit,
    onCancelDeleteAll: () -> Unit,
    onBack: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            RisiTopBar(title = RisiNotes.NOTES_TITLE, onBack = onBack, actions = {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("risi_notes_menu")) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Delete all notes", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, onClick = { menu = false; onAskDeleteAll() }, enabled = !s.local, modifier = Modifier.testTag("risi_notes_delete_all"))
                }
            })
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = Spacing.xl, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                value = s.query, onValueChange = onQuery, singleLine = true, placeholder = { Text("Search notes", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                modifier = Modifier.fillMaxWidth().testTag("risi_notes_search"),
            )
            Text(RisiNotes.INFO_LINE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_notes_info"))
            if (s.local) Text(NOTES_OFFLINE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("risi_notes_local"))
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("risi_notes_error")) }
            when {
                s.loading && s.notes.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                s.notes.isEmpty() && s.error == null -> Text(if (s.query.isBlank()) NOTES_EMPTY else NOTES_NO_MATCH, modifier = Modifier.testTag("risi_notes_empty"))
                else -> LazyColumn(Modifier.weight(1f).testTag("risi_notes_list"), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
                    items(s.notes, key = { it.noteId }) { n -> NoteListRow(n, titleOf(n), onOpen, onShare, onDelete) }
                    if (s.hasMore) item { TextButton(onClick = onMore, enabled = !s.loading, modifier = Modifier.fillMaxWidth().testTag("risi_notes_more")) { Text("More", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } }
                }
            }
        }
    }
    if (s.confirmAll) {
        AlertDialog(
            onDismissRequest = onCancelDeleteAll,
            title = { Text("Delete all notes?", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            text = { Text("Your Notes list becomes empty. Agreed items, promises and meetings stay; so do the cards in your Risi chat.") },
            confirmButton = { TextButton(onClick = onConfirmDeleteAll, modifier = Modifier.testTag("risi_notes_delete_all_confirm")) { Text("Delete all", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
            dismissButton = { TextButton(onClick = onCancelDeleteAll) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
        )
    }
}

@Composable
private fun NoteListRow(n: RisiNoteSummary, title: String, onOpen: (String) -> Unit, onShare: (String) -> Unit, onDelete: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open note") { onOpen(n.noteId) }.padding(vertical = Spacing.sm).testTag("risi_notes_row"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                RisiNotes.countsLine(n.itemsCount, n.openItemsCount, n.eventsCount) + " · " + sourceWord(n.source),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("risi_notes_row_menu")) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Share into a chat", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, onClick = { menu = false; onShare(n.noteId) }, modifier = Modifier.testTag("risi_notes_row_share"))
            DropdownMenuItem(text = { Text("Delete", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, onClick = { menu = false; onDelete(n.noteId) }, modifier = Modifier.testTag("risi_notes_row_delete"))
        }
    }
    HorizontalDivider()
}

private fun sourceWord(s: String) = when (s) {
    RisiNote.SOURCE_CALL -> "call"
    RisiNote.SOURCE_REQUEST -> "asked"
    else -> "chat"
}

/** §30.6 the chat picker: the chats on this phone (Risi chats excluded); the note goes out as my own message. */
@Composable
fun ShareChatPicker(share: ShareUi, chats: List<Pair<String, String>>, onPick: (String, String) -> Unit, onCancel: () -> Unit) {
    if (share.text == null) return
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Share into a chat", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("It is sent as your own message.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val shown = chats
                // A plain scrolling column (a dialog measures its content intrinsically; a lazy list can't be).
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).testTag("risi_share_chats")) {
                    shown.forEach { (conv, name) ->
                        Text(
                            name, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(conv, name) }.padding(vertical = Spacing.md).testTag("risi_share_chat"),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
    )
}

/** Shows a share/notice line once. */
@Composable
fun ShareNotice(share: ShareUi, clear: () -> Unit) {
    val n = share.notice ?: return
    LaunchedEffect(n) {
        kotlinx.coroutines.delay(3_000)
        clear()
    }
    Text(n, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(Spacing.md).testTag("risi_share_notice"))
}
