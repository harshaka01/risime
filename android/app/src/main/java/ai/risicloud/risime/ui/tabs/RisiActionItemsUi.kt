package ai.risicloud.risime.ui.tabs

import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.tabs.RisiActionItems
import ai.risicloud.risime.data.tabs.RisiLedger
import ai.risicloud.risime.net.ActionItem
import ai.risicloud.risime.net.RisiActionCard
import ai.risicloud.risime.net.RisiActions142
import ai.risicloud.risime.net.RisiItemStates
import ai.risicloud.risime.net.RisiKinds142
import ai.risicloud.risime.ui.theme.Spacing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/*
 * v1.42 §38.3/§38.4 (decision 082): the Official `action_items` card (what Risi heard, for everyone; a tap jumps to the
 * line) and the owner's `action_confirm` card in their Risi chat (Add to My promises / Add to calendar / Remind me /
 * Not mine / Edit). Every write asks first: the calendar and the reminder show exactly what will be written, and with
 * no usable time the picker comes first (never a guessed time).
 */

/** The `action_items` body inside the Risi card. */
@Composable
internal fun ActionItemsCardBody(row: MessageEntity, card: RisiActionCard, ctx: RisiCardContext) {
    val me = ctx.host.me
    val sender = remember(row.messageId, ctx.messages) { RisiActionItems.noteSender(card, ctx.messages) }
    Column(Modifier.testTag("risi_action_items"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val src = RisiActionItems.sourceLine(card, me, sender, ctx.nameOf, null)
        val whole = card.sourceRef?.takeIf { it.jumpable }
        Text(
            src, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("risi_action_source").then(
                if (whole != null && ctx.jumpLine != null) Modifier.clickable(onClickLabel = "Go to the voice note") { ctx.jumpLine.invoke(null, whole) } else Modifier,
            ),
        )
        if (card.unclear) {
            Text(RisiActionItems.unclearText(card), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("risi_action_items_unclear"))
            return@Column
        }
        card.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        // v1.43 §38.9 the live owner: a claimed item shows its claimer.
        val shown = card.items.map { RisiActionItems.live(it, ctx.itemViews) }.filter { RisiActionItems.visible(it, RisiActionItems.state(it, ctx.itemViews), me) }
        if (shown.isEmpty()) {
            Text("No open items.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val posted = remember(row.serverTs) { ai.risicloud.risime.data.HistoryMarkers.epochMs(row.serverTs) }
        var claimed by remember(row.messageId) { mutableStateOf(emptySet<String>()) }
        RisiActionItems.grouped(shown).forEach { (kind, items) ->
            Text(RisiActionItems.kindTitle(kind), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("risi_action_kind_$kind"))
            items.forEach { item ->
                ActionItemLine(item, RisiActionItems.state(item, ctx.itemViews), expired = false, ctx = ctx, conversationId = null)
                if (RisiActionItems.canClaim(item, ctx.itemViews, posted, ctx.nowMs, ctx.awaiting + claimed, ctx.readOnly) && ctx.host.risiChatAvailable()) {
                    OutlinedButton(
                        onClick = { claimed = claimed + item.itemId.lowercase(); ctx.host.claimItem(item.itemId) },
                        modifier = Modifier.testTag("risi_action_claim"),
                    ) { Text(RisiActionItems.ITS_MINE, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        if (shown.any { it.ledger }) {
            Text(RisiActionItems.OWNERS_CONFIRM, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val mineOpen = shown.any { it.owner.equals(me, true) && (RisiActionItems.state(it, ctx.itemViews) ?: RisiItemStates.PROPOSED) == RisiItemStates.PROPOSED }
            val sid = card.summaryId
            if (mineOpen && sid != null && !ctx.readOnly && (ctx.risiChatReady || ctx.host.risiChatAvailable())) {
                TextButton(onClick = { ctx.host.openRisiChat(sid) }, modifier = Modifier.testTag("risi_action_open_risi_chat")) { Text("Open Risi chat", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** One item: text, then owner · due · state; a tap jumps to its line ("Line no longer available" when it's gone). */
@Composable
private fun ActionItemLine(item: ActionItem, state: String?, expired: Boolean, ctx: RisiCardContext, conversationId: String?) {
    val me = ctx.host.me
    val meta = listOfNotNull(
        RisiActionItems.ownerLabel(item, me, ctx.nameOf),
        RisiActionItems.dueLabel(item),
        RisiActionItems.stateLabel(item, state, expired),
    ).joinToString(" · ")
    val ref = item.sourceRef?.takeIf { it.jumpable }
    val jump = ctx.jumpLine
    val dim = if (state == RisiItemStates.DECLINED || state == RisiItemStates.EXPIRED) 0.55f else 1f
    Column(
        Modifier.fillMaxWidth()
            .then(if (ref != null && jump != null) Modifier.clickable(onClickLabel = "Go to the line") { jump(conversationId, ref) } else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = "Action item: ${item.text}" + (if (meta.isNotEmpty()) " · $meta" else "") }
            .padding(vertical = Spacing.xxs)
            .testTag("risi_action_item"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text("• " + item.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim))
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_action_item_meta"))
    }
}

private enum class Ask { CALENDAR, REMIND }

/** The `action_confirm` body: my items from one call or voice note, each with its buttons. */
@Composable
internal fun ActionConfirmCardBody(row: MessageEntity, card: RisiActionCard, ctx: RisiCardContext) {
    val me = ctx.host.me
    val expired = RisiActionItems.expired(card, ctx.nowMs)
    val local = remember(ctx.messages) { RisiLedger.items(ctx.messages) }
    val views = ctx.itemViews + local
    val notMine = remember(ctx.messages) { RisiActionItems.notMineSent(ctx.messages, me) }
    val conv = card.conversationId
    Column(Modifier.testTag("risi_action_confirm"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            // The note itself is in the Official chat (its sender isn't known here): "From a voice note in <chat>".
            RisiActionItems.sourceLine(card, me, null, ctx.nameOf, conv?.let(ctx.conversationName)),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_action_source"),
        )
        var left = 0
        card.items.forEach { item ->
            val state = RisiActionItems.state(item, views)
            if (state == RisiItemStates.CANCELLED) return@forEach
            if (state == RisiItemStates.DECLINED || item.itemId.lowercase() in notMine) {
                Text(RisiActionItems.NOT_MINE_DONE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_action_not_mine_done"))
                return@forEach
            }
            left++
            ConfirmItem(item, state, expired, ctx, conv)
            RisiActionItems.remindersNote(card, RisiActionItems.buttons(item, state, me, expired, ctx.awaiting, ctx.readOnly))?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_action_reminders_note"))
            }
        }
        if (left == 0 && card.items.isNotEmpty() && card.items.all { RisiActionItems.state(it, views) == RisiItemStates.CANCELLED }) {
            Text(RisiActionItems.NOTHING_LEFT, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConfirmItem(item: ActionItem, state: String?, expired: Boolean, ctx: RisiCardContext, conv: String?) {
    var ask by remember(item.itemId) { mutableStateOf<Ask?>(null) }
    var picking by remember(item.itemId) { mutableStateOf<Ask?>(null) }
    var chosen by remember(item.itemId) { mutableStateOf<Long?>(null) }
    var editing by remember(item.itemId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        ActionItemLine(item, state, expired, ctx, conv)
        val buttons = RisiActionItems.buttons(item, state, ctx.host.me, expired, ctx.awaiting, ctx.readOnly)
        if (buttons.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (RisiKinds142.BUTTON_ADD_PROMISE in buttons) FilledTonalButton(onClick = { ctx.host.actItem(item.itemId, RisiLedger.CONFIRM) }, modifier = Modifier.testTag("risi_action_add_promise")) { Text(RisiActionItems.ADD_PROMISE, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (RisiKinds142.BUTTON_ADD_CALENDAR in buttons) OutlinedButton(onClick = {
                    chosen = null
                    if (RisiActionItems.calendarSlot(item, ctx.nowMs) != null) ask = Ask.CALENDAR else picking = Ask.CALENDAR
                }, modifier = Modifier.testTag("risi_action_add_calendar")) { Text(RisiActionItems.ADD_CALENDAR, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (RisiKinds142.BUTTON_REMIND in buttons) OutlinedButton(onClick = {
                    chosen = null
                    if (RisiActionItems.remindAt(item, ctx.nowMs) != null) ask = Ask.REMIND else picking = Ask.REMIND
                }, modifier = Modifier.testTag("risi_action_remind")) { Text(RisiActionItems.REMIND, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (RisiKinds142.BUTTON_NOT_MINE in buttons) OutlinedButton(onClick = { ctx.host.actItem142(item.itemId, RisiActions142.ITEM_NOT_MINE, null) }, modifier = Modifier.testTag("risi_action_not_mine")) { Text(RisiActionItems.NOT_MINE, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (RisiKinds142.BUTTON_EDIT in buttons) TextButton(onClick = { editing = true }, modifier = Modifier.testTag("risi_action_edit")) { Text(RisiActionItems.EDIT, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    val now = ctx.nowMs
    when (ask) {
        Ask.CALENDAR -> {
            val slot = chosen?.let { RisiActionItems.Slot(it, it + RisiActionItems.EVENT_MS, false) } ?: RisiActionItems.calendarSlot(item, now)
            if (slot == null) { ask = null; picking = Ask.CALENDAR } else ConfirmWriteDialog(
                title = "Add to calendar?", text = RisiActionItems.calendarQuestion(item, slot), ok = "Add",
                onOk = { ask = null; ctx.host.actItem142(item.itemId, RisiActions142.ITEM_CALENDAR, RisiActionItems.calendarEdit(slot)) },
                onChange = { ask = null; picking = Ask.CALENDAR }, onCancel = { ask = null },
            )
        }
        Ask.REMIND -> {
            val at = chosen ?: RisiActionItems.remindAt(item, now)
            if (at == null) { ask = null; picking = Ask.REMIND } else ConfirmWriteDialog(
                title = "Set a reminder?", text = RisiActionItems.remindQuestion(item, at), ok = "Remind me",
                onOk = { ask = null; ctx.host.actItem142(item.itemId, RisiActions142.ITEM_REMIND, RisiActionItems.remindEdit(at)) },
                onChange = { ask = null; picking = Ask.REMIND }, onCancel = { ask = null },
            )
        }
        null -> {}
    }
    picking?.let { what ->
        PickDateTime(
            initialMs = chosen ?: item.due?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            onPicked = { ms -> picking = null; chosen = ms; ask = what },
            onDismiss = { picking = null },
        )
    }
    if (editing) {
        RisiEditDialog(
            initialText = item.text, initialDue = item.due, ownerName = null, title = "Edit item",
            onSave = { t, d ->
                editing = false
                val same = d != null && item.due != null && runCatching { Instant.parse(d) == Instant.parse(item.due) }.getOrDefault(false)
                ctx.host.actItem(item.itemId, RisiLedger.EDIT, t.take(ai.risicloud.risime.data.tabs.RisiControl.MAX_ITEM_TEXT), d, item.allDay && same)
            },
            onDismiss = { editing = false },
        )
    }
}

/** Confirm-before-write: what will be written, [ok] / Change time / Cancel. */
@Composable
private fun ConfirmWriteDialog(title: String, text: String, ok: String, onOk: () -> Unit, onChange: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(text, modifier = Modifier.testTag("risi_action_dialog_text"))
                TextButton(onClick = onChange, modifier = Modifier.testTag("risi_action_dialog_change")) { Text("Change time", maxLines = 1) }
            }
        },
        confirmButton = { TextButton(onClick = onOk, modifier = Modifier.testTag("risi_action_dialog_ok")) { Text(ok, maxLines = 1) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag("risi_action_dialog_cancel")) { Text("Cancel", maxLines = 1) } },
        modifier = Modifier.testTag("risi_action_dialog"),
    )
}

/** The picker when there is no usable time (a date, then a time); never a guessed one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDateTime(initialMs: Long?, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val init = initialMs?.let { Instant.ofEpochMilli(it).atZone(zone) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    if (date == null) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (init?.toLocalDate() ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } }) { Text("Next", maxLines = 1) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1) } },
        ) { DatePicker(st) }
    } else {
        val t0 = init?.toLocalTime() ?: LocalTime.of(9, 0)
        val st = rememberTimePickerState(t0.hour, t0.minute, false)
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { onPicked(date!!.atTime(st.hour, st.minute).atZone(zone).toInstant().toEpochMilli()) }) { Text("OK", maxLines = 1) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1) } },
            text = { TimePicker(st) },
        )
    }
}

/** A call transcript line highlighted after a jump (§38.3). */
internal fun Modifier.lineFlash(row: MessageEntity, ctx: RisiCardContext, color: androidx.compose.ui.graphics.Color): Modifier =
    if (ctx.flash != null && ctx.flash == row.clientMsgId) this.background(color) else this
