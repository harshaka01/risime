package lk.codegen.risime.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.deletes.DeleteRules
import lk.codegen.risime.ui.common.MessageBubble
import lk.codegen.risime.ui.common.timeOf

/** §15.6 the text of a tombstone row (or a row being deleted for everyone: mine, so "You deleted this message"). */
fun tombstoneText(m: MessageEntity, me: String): String =
    DeleteRules.tombstoneText(if (m.deleteState != null) me else m.deletedBy, me, m.deletedByAdmin)

/**
 * §15.6 a tombstone: the same position and side, no content, no ticks, no reactions; long-press
 * offers only "Delete for me" (when the delete UI is on, [onMenu] non-null).
 */
@Composable
fun TombstoneBubble(m: MessageEntity, me: String, sender: String? = null, onMenu: (() -> Unit)? = null, selected: Boolean = false, onTap: (() -> Unit)? = null, tail: Boolean = true) {
    MessageBubble(
        body = "🚫 " + tombstoneText(m, me),
        time = timeOf(m.localTs),
        mine = m.outgoing,
        status = null,
        onMenu = { onMenu?.invoke() },
        sender = sender,
        onTap = onTap,
        selected = selected,
        muted = true,
        tail = tail,
    )
}

/** What the delete dialog offers for a selection (§15.7). */
data class DeleteDialogState(
    val clientMsgIds: List<String>,
    /** Every selected row is eligible ("Delete for everyone" is hidden otherwise, android A3). */
    val canEveryone: Boolean,
    /** §15.1: not `deletes_ready`: "People on older app versions may still see it". */
    val oldAppsHint: Boolean,
)

/** Pure: the dialog for [rows] (system rows and tombstones offer only "Delete for me"). */
fun deleteDialogFor(
    rows: List<MessageEntity>,
    me: String,
    group: Boolean,
    iAmAdmin: Boolean,
    deletesReady: Boolean?,
    deviceNowMs: Long,
    offsetMs: Long,
): DeleteDialogState? {
    if (rows.isEmpty()) return null
    val everyone = rows.all { r ->
        DeleteRules.eligibleForEveryone(
            r.messageId, lk.codegen.risime.data.deletes.TimeUuid.isoMs(r.serverTs), r.outgoing && r.from.equals(me, true),
            kindIsMessage = !r.system && !r.showsAsDeleted && r.status != lk.codegen.risime.data.MessageStatus.FAILED.name,
            group = group, iAmAdmin = iAmAdmin, deviceNowMs = deviceNowMs, offsetMs = offsetMs,
        )
    }
    return DeleteDialogState(rows.map { it.clientMsgId }, everyone, everyone && deletesReady != true)
}

/**
 * §15.7 delete state for one chat screen (DM or group): select mode, the dialog, notices, and
 * Clear/Delete chat. The message actions are shown only while [DeleteFeature.sendEnabled].
 */
class DeleteController(
    private val c: lk.codegen.risime.AppContainer,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val me: String,
    val conversationId: String,
    private val iAmAdmin: () -> Boolean = { false },
) {
    val enabled: Boolean get() = lk.codegen.risime.data.deletes.DeleteFeature.sendEnabled
    private val group = lk.codegen.risime.net.isGroupConversation(conversationId)

    val selected = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val dialog = kotlinx.coroutines.flow.MutableStateFlow<DeleteDialogState?>(null)
    val notice = kotlinx.coroutines.flow.MutableStateFlow<lk.codegen.risime.data.ChatEngine.DeleteNotice?>(null)
    val deletesReady = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)

    init {
        scope.launchSafely { c.engine.deleteNotices.collect { n -> if (n.conversationId == conversationId) notice.value = n } }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchSafely(block: suspend () -> Unit): kotlinx.coroutines.Job {
        val self: kotlinx.coroutines.CoroutineScope = this
        return self.launch { block() }
    }

    /** §15.1: refetched on chat open (a hint only). */
    fun refreshReady() {
        if (!enabled) return
        scope.launchSafely {
            (c.api.mlsGroup(conversationId) as? lk.codegen.risime.net.ApiResult.Ok)?.let { deletesReady.value = it.value.deletesReady }
        }
    }

    fun select(id: String) { selected.value = selected.value + id }

    fun toggle(id: String) { selected.value = if (id in selected.value) selected.value - id else selected.value + id }

    fun clearSelection() { selected.value = emptySet() }

    /** Long-press → Delete on one row, or the bin in select mode. */
    fun ask(rows: List<MessageEntity>) {
        dialog.value = deleteDialogFor(rows, me, group, iAmAdmin(), deletesReady.value, System.currentTimeMillis(), c.serverClock.offsetMs)
    }

    fun deleteForMe(ids: List<String>) {
        dialog.value = null
        notice.value = null
        clearSelection()
        c.scope.launchSafely { c.engine.deleteForMe(conversationId, ids) }
    }

    fun deleteForEveryone(ids: List<String>) {
        dialog.value = null
        clearSelection()
        c.scope.launchSafely { c.engine.deleteForEveryone(conversationId, ids) }
    }

    fun dismiss() { dialog.value = null }

    /** Per-bubble hooks; null while the delete UI is off. */
    fun selectFor(m: MessageEntity, selection: Set<String>): MsgSelect? =
        if (!enabled) null else MsgSelect(
            selecting = selection.isNotEmpty(),
            selected = m.clientMsgId in selection,
            onToggle = { toggle(m.clientMsgId) },
            onSelect = { select(m.clientMsgId) },
            onDelete = { ask(listOf(m)) },
        )

    fun dismissNotice() { notice.value = null }

    /** Clear chat (keeps the chat listed) or Delete chat (hidden until a new message). Always on. */
    fun clearChat(hide: Boolean) {
        c.notifier.cancelChat(conversationId)
        c.scope.launchSafely { c.engine.clearChat(conversationId, hide) }
    }
}

/** The §15.7 dialog: Delete for me / Delete for everyone (when every row is eligible) / Cancel, with the small print. */
@Composable
fun DeleteMessagesDialog(state: DeleteDialogState, onMe: () -> Unit, onEveryone: () -> Unit, onDismiss: () -> Unit) {
    val n = state.clientMsgIds.size
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(if (n == 1) "Delete message?" else "Delete $n messages?") },
        text = {
            if (state.canEveryone) {
                androidx.compose.material3.Text(
                    DeleteRules.SEEN_HINT + if (state.oldAppsHint) ". " + DeleteRules.OLD_APPS_HINT + "." else ".",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                if (state.canEveryone) androidx.compose.material3.TextButton(onClick = onEveryone) { androidx.compose.material3.Text("Delete for everyone") }
                androidx.compose.material3.TextButton(onClick = onMe) { androidx.compose.material3.Text("Delete for me") }
                androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("Cancel") }
            }
        },
    )
}

/** After a refusal: "Couldn't delete for everyone" (or the 48 h text), offering "Delete for me". */
@Composable
fun DeleteNoticeDialog(notice: lk.codegen.risime.data.ChatEngine.DeleteNotice, onMe: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(DeleteRules.FAILED) },
        text = { if (notice.text != DeleteRules.FAILED) androidx.compose.material3.Text(notice.text) },
        confirmButton = {
            if (notice.failedClientMsgIds.isNotEmpty()) androidx.compose.material3.TextButton(onClick = onMe) { androidx.compose.material3.Text("Delete for me") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("OK") } },
    )
}

/** Confirmation for Clear chat / Delete chat. */
@Composable
fun ClearChatDialog(hide: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(if (hide) "Delete this chat?" else "Clear this chat?") },
        text = {
            androidx.compose.material3.Text(
                if (hide) "Messages are removed from this phone and the chat leaves your list until a new message arrives."
                else "Messages are removed from this phone. The chat stays in your list.",
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirm) { androidx.compose.material3.Text(if (hide) "Delete chat" else "Clear chat") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("Cancel") } },
    )
}

/** Select mode's contextual top bar: count, Copy, Delete (bin), close. */
@Composable
fun SelectionTopBar(count: Int, onCopy: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    androidx.compose.material3.Surface(tonalElevation = 3.dp) {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.material3.IconButton(onClick = onClose) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Close, "Cancel selection") }
            androidx.compose.material3.Text("$count selected", androidx.compose.ui.Modifier.weight(1f), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            androidx.compose.material3.TextButton(onClick = onCopy) { androidx.compose.material3.Text("Copy") }
            androidx.compose.material3.IconButton(onClick = onDelete) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Delete, "Delete selected") }
        }
    }
}

/** The chat overflow menu: Clear chat / Delete chat (always on: local + my own inbox). */
@Composable
fun ChatOverflowMenu(onClear: () -> Unit, onDelete: () -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.IconButton(onClick = { open = true }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.MoreVert, "More options") }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { androidx.compose.material3.Text("Clear chat") }, onClick = { open = false; onClear() })
            androidx.compose.material3.DropdownMenuItem(text = { androidx.compose.material3.Text("Delete chat") }, onClick = { open = false; onDelete() })
        }
    }
}

/**
 * Everything a chat screen hosts for deletes: the dialog, the refusal notice and the Clear/Delete
 * chat confirmation ([clearAsk] = null, false = clear, true = delete).
 */
@Composable
fun DeleteHost(ctl: DeleteController, clearAsk: Boolean?, onClearAskDone: () -> Unit, onDeletedChat: () -> Unit) {
    val dialog by ctl.dialog.collectAsState()
    val notice by ctl.notice.collectAsState()
    dialog?.let { d ->
        DeleteMessagesDialog(d, onMe = { ctl.deleteForMe(d.clientMsgIds) }, onEveryone = { ctl.deleteForEveryone(d.clientMsgIds) }, onDismiss = ctl::dismiss)
    }
    notice?.let { n -> DeleteNoticeDialog(n, onMe = { ctl.deleteForMe(n.failedClientMsgIds) }, onDismiss = ctl::dismissNotice) }
    clearAsk?.let { hide ->
        ClearChatDialog(hide, onConfirm = {
            onClearAskDone()
            ctl.clearChat(hide)
            if (hide) onDeletedChat()
        }, onDismiss = onClearAskDone)
    }
}

/** Per-bubble select/delete hooks (null while the delete UI is off). */
class MsgSelect(val selecting: Boolean, val selected: Boolean, val onToggle: () -> Unit, val onSelect: () -> Unit, val onDelete: () -> Unit)

/** Copy in select mode: the selected text rows in order (tombstones and system lines carry no text). */
fun copyText(rows: List<MessageEntity>, selection: Set<String>): String =
    rows.filter { it.clientMsgId in selection && !it.system && !it.showsAsDeleted && it.body.isNotBlank() }.joinToString("\n") { it.body }

/** The select-mode bar wired to a controller and the chat's rows. */
@Composable
fun SelectionBarFor(ctl: DeleteController, rows: List<MessageEntity>, selection: Set<String>) {
    if (selection.isEmpty()) return
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.activity.compose.BackHandler { ctl.clearSelection() }
    SelectionTopBar(
        count = selection.size,
        onCopy = {
            val text = copyText(rows, selection)
            scope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("messages", text))) }
            ctl.clearSelection()
        },
        onDelete = { ctl.ask(rows.filter { it.clientMsgId in selection }) },
        onClose = ctl::clearSelection,
    )
}
