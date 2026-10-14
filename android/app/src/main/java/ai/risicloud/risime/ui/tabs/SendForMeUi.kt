package ai.risicloud.risime.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.tabs.RisiToolCards
import ai.risicloud.risime.data.tabs.SendForMeRules
import ai.risicloud.risime.net.RisiMeta
import ai.risicloud.risime.net.RisiSend149
import ai.risicloud.risime.ui.theme.Spacing

/*
 * Proposal 2026-10-14-send-for-me §42.2–§42.4: the one-tap card. "To: Upali · 🔒 Private", the text, [Send] (or
 * [Schedule]) and [Edit]; Cancel is a small text button. [Send] is the only confirmation; [Edit] opens the text
 * prefilled and its Send sends `confirm_write` with `edit: {"text"}`. Under [Send] the tick "Send to Upali without
 * asking next time" (off by default) is saved for that contact only when [Send] is tapped with it ticked.
 */

@Composable
internal fun SendMessageCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val state = RisiToolCards.confirmState(r, ctx.messages, ctx.nowMs)
    val open = state == RisiToolCards.ConfirmState.OPEN
    val dim = if (open || state == RisiToolCards.ConfirmState.CONFIRMED) 1f else 0.55f
    val text = SendForMeRules.text(r) ?: row.body
    val wid = r.writeId
    val buttons = if (ctx.readOnly || wid == null) emptyList() else SendForMeRules.buttons(ctx.host.me, r, state, ctx.awaiting)
    val scheduled = r.tool == ai.risicloud.risime.net.RisiToolCall.TOOL_SCHEDULE_MESSAGE
    var tick by remember(wid) { mutableStateOf(false) }
    var editing by remember(wid) { mutableStateOf(false) }
    val store = ctx.host.autoSend
    val to = r.send?.to
    val tab = r.send?.tab ?: ai.risicloud.risime.net.RisiSendTabs.PRIVATE
    fun confirm(edited: String?) {
        val w = wid ?: return
        if (!scheduled && tick && store != null && to?.userId != null) store.set(to.userId!!, tab, to.name, true)
        val edit = edited?.let { SendForMeRules.edit(text, it) }
        if (edit != null) ctx.host.confirmEdited(w, edit) else ctx.host.act(w, "confirm_write")
    }
    Column(Modifier.testTag("risi_send_card"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        SendForMeRules.toLine(r)?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim), modifier = Modifier.testTag("risi_send_to"))
        }
        if (scheduled) r.confirmWhen()?.start?.let { formatDue(it)?.let { w -> Text(w, style = MaterialTheme.typography.labelMedium) } }
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim), modifier = Modifier.padding(Spacing.sm).testTag("risi_send_text"))
        }
        if (scheduled && open && ctx.host.scheduleMayBeLate()) {
            Text("May be a few minutes late on this phone (exact alarms are off).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val auto = wid != null && ctx.host.autoSent(wid)
        when {
            auto && to != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sent without asking (your choice for ${SendForMeRules.firstName(to.name)}) · ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Ask me next time",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("risi_send_ask_next").clickable { to.userId?.let { store?.set(it, tab, to.name, false) } },
                )
            }
            state == RisiToolCards.ConfirmState.CONFIRMED -> Text(if (scheduled) "Scheduling…" else "Sending…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("risi_confirm_state"))
            else -> RisiToolCards.closedText(state)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_confirm_state")) }
        }
        if (buttons.isNotEmpty() && wid != null) {
            if (!scheduled && store != null && to?.userId != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("risi_send_tick_row")) {
                    Checkbox(checked = tick, onCheckedChange = { tick = it }, modifier = Modifier.testTag("risi_send_tick"))
                    Text(SendForMeRules.tickLabel(r).orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                if (RisiSend149.BUTTON_SEND in buttons) Button(onClick = { confirm(null) }, modifier = Modifier.testTag("risi_send_send")) { Text("Send", maxLines = 1) }
                if (RisiSend149.BUTTON_SCHEDULE in buttons) Button(onClick = { confirm(null) }, modifier = Modifier.testTag("risi_send_schedule")) { Text("Schedule", maxLines = 1) }
                if (RisiSend149.BUTTON_EDIT in buttons) OutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("risi_send_edit")) { Text("Edit", maxLines = 1) }
                if (RisiSend149.BUTTON_CANCEL in buttons) TextButton(onClick = { ctx.host.act(wid, "cancel_write") }, modifier = Modifier.testTag("risi_send_cancel")) { Text("Cancel", maxLines = 1) }
            }
        } else if (open && wid != null && wid.lowercase() in ctx.awaiting) {
            Text("Sending…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (editing) SendEditDialog(text, if (scheduled) "Schedule" else "Send", onDismiss = { editing = false }) { edited ->
        editing = false
        confirm(edited)
    }
}

@Composable
private fun SendEditDialog(initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit the message") },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("risi_send_edit_field"))
        },
        confirmButton = {
            Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank(), modifier = Modifier.testTag("risi_send_edit_confirm")) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } },
    )
}

/** §42.2 `message_sent`: "✓ Sent to Upali Perera", tappable to open that chat. Never the text. */
@Composable
internal fun MessageSentLine(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val line = SendForMeRules.sentLine(r, row.body)
    val conv = r.conversationId
    Text(
        line,
        style = MaterialTheme.typography.bodyLarge,
        color = if (conv != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("risi_message_sent").then(if (conv != null) Modifier.clickable { ctx.host.openConversation(conv) } else Modifier),
    )
}
