package lk.codegen.risime.ui.chats

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import lk.codegen.risime.ui.common.RisiTopBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lk.codegen.risime.calls.CallRecord
import lk.codegen.risime.calls.CallRecords
import lk.codegen.risime.ui.common.EmptyState
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.RisiIcons
import lk.codegen.risime.ui.theme.RisiTheme
import lk.codegen.risime.ui.theme.Sizes
import lk.codegen.risime.ui.theme.Spacing

/** The Calls tab's UI state, lifted so the chat list's top bar and floating button can follow it. */
class CallsUi {
    /** Selected rows ([CallRow.key]); non-empty = selection mode. */
    var selected by mutableStateOf(setOf<String>())
    var info by mutableStateOf<CallRow?>(null)
    var newCall by mutableStateOf(false)
    var askDelete by mutableStateOf(false)
    var askClear by mutableStateOf(false)
    val selecting: Boolean get() = selected.isNotEmpty()
}

/** The Calls tab: WhatsApp-style rows, grouped "Name (3)"; a long-press selects, a tap = call info, the icon = call back with the same type. */
@Composable
fun CallsTab(vm: ChatsViewModel, ui: CallsUi) {
    val rows by vm.calls.collectAsStateWithLifecycle()
    val callBack = rememberCallBack(vm)
    // Rows that left the list (deleted, cleared) leave the selection.
    androidx.compose.runtime.LaunchedEffect(rows) { ui.selected = ui.selected.filter { k -> rows.any { it.key == k } }.toSet() }
    CallsList(
        rows, nowMs = System.currentTimeMillis(), selected = ui.selected,
        onInfo = { r -> if (ui.selecting) ui.selected = ui.selected.toggle(r.key) else ui.info = r },
        onLongPress = { r -> ui.selected = ui.selected + r.key },
        onCallBack = callBack,
    )
}

fun Set<String>.toggle(k: String): Set<String> = if (k in this) this - k else this + k

/** Rows' records for the selected keys. */
fun selectedRecords(rows: List<CallRow>, selected: Set<String>) = rows.filter { it.key in selected }.flatMap { it.group.calls }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallsList(
    rows: List<CallRow>,
    nowMs: Long,
    onInfo: (CallRow) -> Unit,
    onCallBack: (conversationId: String, video: Boolean) -> Unit,
    selected: Set<String> = emptySet(),
    onLongPress: (CallRow) -> Unit = {},
) {
    if (rows.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { EmptyState("No calls yet. Calls you make and receive show up here.") }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.key }) { row -> CallRowItem(row, nowMs, row.key in selected, onInfo, onLongPress, onCallBack) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRowItem(row: CallRow, nowMs: Long, selected: Boolean, onInfo: (CallRow) -> Unit, onLongPress: (CallRow) -> Unit, onCallBack: (String, Boolean) -> Unit) {
    val r = row.group.latest
    val color = if (r.missed) MaterialTheme.colorScheme.error else RisiTheme.colors.textMuted
    Row(
        Modifier.fillMaxWidth().heightIn(min = Sizes.listRowMin)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = { onInfo(row) }, onLongClick = { onLongPress(row) }, onLongClickLabel = "Select call")
            .semantics { this.selected = selected }
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            InitialsAvatar(row.name, photoKey = row.userId)
            if (selected) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Check, "Selected", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            }
        }
        Spacer(Modifier.width(Spacing.md + Spacing.xxs))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, color = if (r.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${CallRecords.arrow(r)} ${CallRecords.stamp(r.atMs, nowMs)}", style = MaterialTheme.typography.bodyMedium, color = if (r.missed) color else directionGreen(r), maxLines = 1)
                // A small camera next to the time marks a video call (WhatsApp).
                if (r.video) {
                    Spacer(Modifier.width(Spacing.xs))
                    Icon(RisiIcons.Videocam, "Video", Modifier.size(14.dp), tint = if (r.missed) color else RisiTheme.colors.textMuted)
                }
            }
        }
        // The call-back button: a phone for a voice call, a camera for a video call; it calls back with the same type.
        IconButton(onClick = { onCallBack(r.conversationId, r.video) }) {
            Icon(
                if (r.video) RisiIcons.Videocam else Icons.Default.Call,
                if (r.video) "Video call ${row.name}" else "Voice call ${row.name}",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Incoming / outgoing arrows are green; missed ones use the error colour. */
@Composable
private fun directionGreen(r: CallRecord) = if (r.missed) MaterialTheme.colorScheme.error else callGreen()

@Composable
private fun callGreen(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF4CD58A) else Color(0xFF0B7A3B)

/** "Delete 2 calls?" (WhatsApp wording); [n] = the call records that will leave the Calls list. */
fun deleteCallsTitle(n: Int) = if (n == 1) "Delete 1 call?" else "Delete $n calls?"

/** WhatsApp's selection bar on the Calls tab: back, the count, Select all, Delete. */
@Composable
fun CallsSelectionBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onDelete: () -> Unit) {
    RisiTopBar(
        title = count.toString(),
        onBack = onClose,
        actions = {
            TextButton(onClick = onSelectAll, modifier = Modifier.semantics { contentDescription = "Select all" }) { Text("All") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
        },
    )
}

/** The delete / clear confirmations. Only the Calls list is touched; the chat's call rows stay. */
@Composable
fun CallsDialogs(ui: CallsUi, rows: List<CallRow>, onDelete: (List<CallRecord>) -> Unit, onClear: () -> Unit) {
    if (ui.askDelete) {
        val recs = selectedRecords(rows, ui.selected)
        AlertDialog(
            onDismissRequest = { ui.askDelete = false },
            title = { Text(deleteCallsTitle(recs.size)) },
            text = { Text("They are removed from the Calls list on this phone. The calls stay in the chats.") },
            confirmButton = { TextButton(onClick = { ui.askDelete = false; ui.selected = emptySet(); onDelete(recs) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { ui.askDelete = false }) { Text("Cancel") } },
        )
    }
    if (ui.askClear) {
        AlertDialog(
            onDismissRequest = { ui.askClear = false },
            title = { Text("Clear call log?") },
            text = { Text("Every call is removed from the Calls list on this phone. The calls stay in the chats.") },
            confirmButton = { TextButton(onClick = { ui.askClear = false; ui.selected = emptySet(); onClear() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { ui.askClear = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The full call info screen (not a dialog): the person, the date, every call of the group with direction,
 * voice/video, time and duration; Message / Voice call / Video call; ⋮ → "Remove from call log".
 */
@Composable
fun CallInfoScreen(
    row: CallRow,
    nowMs: Long,
    onBack: () -> Unit,
    onCallBack: (conversationId: String, video: Boolean) -> Unit,
    onMessage: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            RisiTopBar(
                title = "Call info",
                onBack = onBack,
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Remove from call log") }, onClick = { menu = false; onRemove() })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                InitialsAvatar(row.name, photoKey = row.userId)
                Spacer(Modifier.width(Spacing.md + Spacing.xxs))
                Column {
                    Text(row.name, style = MaterialTheme.typography.titleLarge)
                    Text(CallRecords.stamp(row.group.latest.atMs, nowMs), style = MaterialTheme.typography.bodyMedium, color = RisiTheme.colors.textMuted)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = onMessage) { Text("Message") }
                TextButton(onClick = { onCallBack(row.group.conversationId, false) }) { Text("Voice call") }
                TextButton(onClick = { onCallBack(row.group.conversationId, true) }) { Text("Video call") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(row.group.calls, key = { it.clientMsgId }) { c ->
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                            .semantics { contentDescription = "${c.label}, ${CallRecords.stamp(c.atMs, nowMs)}" },
                    ) {
                        Text(
                            (if (c.outgoing) "Outgoing " else "Incoming ") + (if (c.video) "video" else "voice") + " call",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (c.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${CallRecords.arrow(c)} ${CallRecords.stamp(c.atMs, nowMs)} · " + (c.durationS?.let { CallRecords.durationText(it) } ?: c.outcomeText()),
                            style = MaterialTheme.typography.bodySmall, color = RisiTheme.colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

/** "New call": pick a contact, then Voice or Video (a full screen, as WhatsApp's). */
@Composable
fun NewCallScreen(contacts: List<Pair<String, String>>, onBack: () -> Unit, onCall: (conversationId: String, video: Boolean) -> Unit) {
    Scaffold(topBar = { RisiTopBar(title = "New call", onBack = onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            if (contacts.isEmpty()) item { EmptyState("No friends to call yet. Add a friend first.") }
            items(contacts, key = { it.second }) { (name, conv) ->
                Row(Modifier.fillMaxWidth().heightIn(min = Sizes.listRowMin).padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    InitialsAvatar(name)
                    Spacer(Modifier.width(Spacing.md + Spacing.xxs))
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    IconButton(onClick = { onCall(conv, false) }) { Icon(Icons.Default.Call, "Voice call $name", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { onCall(conv, true) }) { Icon(RisiIcons.Videocam, "Video call $name", tint = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

/** The info list's second line when there is no duration. */
private fun CallRecord.outcomeText(): String = when (outcome) {
    lk.codegen.risime.calls.CallOutcome.MISSED -> "Missed"
    lk.codegen.risime.calls.CallOutcome.DECLINED -> "Declined"
    lk.codegen.risime.calls.CallOutcome.NO_ANSWER -> "No answer"
    lk.codegen.risime.calls.CallOutcome.CANCELLED -> "Cancelled"
    lk.codegen.risime.calls.CallOutcome.FAILED -> "Couldn't connect"
    lk.codegen.risime.calls.CallOutcome.ANSWERED -> "Answered"
}

/** Asks the microphone (and the camera for video) the first time, explains when calls can't work, then calls. */
@Composable
fun rememberCallBack(vm: ChatsViewModel): (String, Boolean) -> Unit {
    val ctx = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var pending by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val (conv, video) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        val mic = r[Manifest.permission.RECORD_AUDIO] ?: granted(Manifest.permission.RECORD_AUDIO)
        if (!mic) Toast.makeText(ctx, "RisiMe needs the microphone for calls", Toast.LENGTH_LONG).show()
        else vm.callBack(conv, video, camera = video && (r[Manifest.permission.CAMERA] ?: granted(Manifest.permission.CAMERA)))
    }
    return { conv, video ->
        val blocked = vm.callBlocked()
        when {
            blocked != null -> Toast.makeText(ctx, blocked, Toast.LENGTH_LONG).show()
            granted(Manifest.permission.RECORD_AUDIO) && (!video || granted(Manifest.permission.CAMERA)) -> vm.callBack(conv, video, camera = video)
            else -> {
                pending = conv to video
                ask.launch(if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        }
    }
}
