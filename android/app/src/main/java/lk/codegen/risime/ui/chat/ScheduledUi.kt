package lk.codegen.risime.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items


import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lk.codegen.risime.data.HistoryMarkers
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.ScheduledMessageEntity
import lk.codegen.risime.data.db.ScheduledSendEntity
import lk.codegen.risime.data.tabs.ScheduledMessages
import lk.codegen.risime.data.tabs.sentLate
import lk.codegen.risime.ui.theme.Spacing

/*
 * Contract v1.26 §26.6 in the chat (the sender's phone only): a pending scheduled message is an
 * outgoing bubble with a clock icon ("Scheduled for Tue 06:00 (every day)"); tapping it offers Edit,
 * Send now and Cancel (local, until it is sent); a one-off over 12 h late says "Not sent: your phone
 * was off" with [Send now] [Discard]; a send more than 2 minutes late shows "Sent late". The chat ⋮
 * has "Scheduled messages" (this chat's list).
 */

const val SCHEDULED_MESSAGES_TITLE = "Scheduled messages"
const val SENT_LATE = "Sent late"

/** One chat's scheduled messages and the actions on them. */
class ScheduledControls(
    private val scheduled: ScheduledMessages,
    open: Flow<List<ScheduledMessageEntity>>,
    sends: Flow<List<ScheduledSendEntity>>,
    private val scope: CoroutineScope,
    conversationId: String,
    private val afterSend: suspend () -> Unit = {},
) {
    val here: StateFlow<List<ScheduledMessageEntity>> = open.map { l -> l.filter { it.conversationId.equals(conversationId, true) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val sendsById: StateFlow<Map<String, ScheduledSendEntity>> = sends.map { l -> l.associateBy { it.clientMsgId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun edit(id: String, text: String) { scope.launch { scheduled.edit(id, text) } }

    fun sendNow(id: String) { scope.launch { if (scheduled.sendNow(id)) afterSend() } }

    fun cancel(id: String) { scope.launch { scheduled.cancel(id) } }

    fun discard(id: String) { scope.launch { scheduled.discard(id) } }
}

/** Whether a message on screen was a scheduled send that went out more than 2 minutes late. */
fun isSentLate(m: MessageEntity, sends: Map<String, ScheduledSendEntity>): Boolean =
    m.outgoing && sentLate(sends[m.clientMsgId], HistoryMarkers.epochMs(m.serverTs), m.localTs)

@Composable
fun SentLateLabel() {
    Text(SENT_LATE, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Spacing.sm).testTag("sent_late"))
}

/** A bubble followed by "Sent late" when it was (only then is it wrapped). */
@Composable
fun WithSentLate(late: Boolean, bubble: @Composable () -> Unit) {
    if (!late) return bubble()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        bubble()
        SentLateLabel()
    }
}

/** The chat's pending (and missed) scheduled messages, as outgoing bubbles under the conversation. */
@Composable
fun ScheduledBubbles(list: List<ScheduledMessageEntity>, ctl: ScheduledControls) {
    list.forEach { ScheduledBubble(it, ctl) }
}

@Composable
fun ScheduledBubble(s: ScheduledMessageEntity, ctl: ScheduledControls) {
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val missed = s.state == ScheduledMessageEntity.STATE_MISSED
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xxs), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.widthIn(max = 300.dp).clickable { menu = true }.testTag("scheduled_bubble"),
        ) {
            Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                Text(s.text, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Icon(androidx.compose.ui.res.painterResource(lk.codegen.risime.R.drawable.ic_schedule), contentDescription = null, modifier = Modifier.size(14.dp).semantics { contentDescription = "Scheduled" })
                    Text(
                        ScheduledMessages.label(s), style = MaterialTheme.typography.labelSmall,
                        color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("scheduled_label"),
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!missed) DropdownMenuItem(text = { Text("Edit", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, onClick = { menu = false; editing = true })
            DropdownMenuItem(text = { Text("Send now", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, onClick = { menu = false; ctl.sendNow(s.scheduleId) })
            DropdownMenuItem(text = { Text(if (missed) "Discard" else "Cancel") }, onClick = { menu = false; if (missed) ctl.discard(s.scheduleId) else ctl.cancel(s.scheduleId) })
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(s.text) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit scheduled message", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("scheduled_edit_text")) },
            confirmButton = { TextButton(onClick = { editing = false; ctl.edit(s.scheduleId, text) }, enabled = text.isNotBlank()) { Text("Save", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
        )
    }
}

/** The chat ⋮ → "Scheduled messages": this chat's list (each row has the bubble's actions). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledMessagesSheet(list: List<ScheduledMessageEntity>, ctl: ScheduledControls, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(Spacing.lg).testTag("scheduled_sheet"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(SCHEDULED_MESSAGES_TITLE, style = MaterialTheme.typography.titleMedium)
            if (list.isEmpty()) Text("Nothing scheduled in this chat.", style = MaterialTheme.typography.bodyMedium)
            LazyColumn { items(list, key = { it.scheduleId }) { ScheduledBubble(it, ctl) } }
        }
    }
}
