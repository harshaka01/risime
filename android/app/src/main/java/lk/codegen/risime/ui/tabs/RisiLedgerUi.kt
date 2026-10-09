package lk.codegen.risime.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.LedgerItemView
import lk.codegen.risime.data.tabs.RisiLedger
import lk.codegen.risime.net.RisiDigestItem
import lk.codegen.risime.net.RisiItemDue
import lk.codegen.risime.net.RisiItemStates
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.ui.theme.Spacing
import java.time.Instant

/*
 * Contract v1.27 §27.3–§27.6 (A14–A16): the per-person discussion summary in the Risi chat, the reminders and
 * the personal digest there, and the short discussion card in Official.
 */

/** Item rows of one owner with their state or (mine / counterpart) buttons. */
@Composable
private fun ItemRows(items: List<LedgerItemView>, mine: Boolean, expired: Boolean, ctx: RisiCardContext) {
    items.forEach { item ->
        var editing by remember(item.itemId) { mutableStateOf(false) }
        val notTracked = item.state == RisiItemStates.EXPIRED || (item.state == RisiItemStates.PROPOSED && expired)
        val dim = if (notTracked || item.state in setOf(RisiItemStates.DECLINED, RisiItemStates.CANCELLED)) 0.55f else 1f
        Column(Modifier.testTag(if (mine) "risi_my_item" else "risi_other_item"), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text("• " + item.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim))
            val due = RisiLedger.dueLabel(item.due, item.allDay, item.dueText)
            val buttons = if (ctx.readOnly) emptyList() else RisiLedger.itemButtons(ctx.host.me, item, expired, ctx.awaiting)
            val state = if (mine && item.state == RisiItemStates.PROPOSED && RisiLedger.CONFIRM in buttons) null else RisiLedger.stateLabel(item.state, expired)
            val line = listOfNotNull(due, state).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_item_state"))
            if (buttons.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (RisiLedger.CONFIRM in buttons) FilledTonalButton(onClick = { ctx.host.actItem(item.itemId, RisiLedger.CONFIRM) }, modifier = Modifier.testTag("risi_item_confirm")) { Text("✓") }
                    if (RisiLedger.DECLINE in buttons) OutlinedButton(onClick = { ctx.host.actItem(item.itemId, RisiLedger.DECLINE) }, modifier = Modifier.testTag("risi_item_decline")) { Text("✗") }
                    if (RisiLedger.EDIT in buttons) OutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("risi_item_edit")) { Text("✎") }
                    if (RisiLedger.DONE in buttons) FilledTonalButton(onClick = { ctx.host.actItem(item.itemId, RisiLedger.DONE) }, modifier = Modifier.testTag("risi_item_done")) { Text("Done") }
                }
            }
        }
        if (editing) ItemEditDialog(item.itemId, item.text, item.due, item.allDay, ctx) { editing = false }
    }
}

/** ✎ / [New date]: `item_edit` with text, due and `all_day` (an untouched date keeps its `all_day`). */
@Composable
private fun ItemEditDialog(itemId: String, text: String, due: String?, allDay: Boolean, ctx: RisiCardContext, close: () -> Unit) {
    RisiEditDialog(
        initialText = text, initialDue = due, ownerName = null,
        onSave = { t, d ->
            close()
            val same = d != null && due != null && runCatching { Instant.parse(d) == Instant.parse(due) }.getOrDefault(false)
            ctx.host.actItem(itemId, RisiLedger.EDIT, t.take(lk.codegen.risime.data.tabs.RisiControl.MAX_ITEM_TEXT), d, allDay && same)
        },
        onDismiss = close,
    )
}

/** §27.3 the per-person summary in my Risi chat. */
@Composable
internal fun DiscussionSummaryCard(r: RisiMeta, ctx: RisiCardContext) {
    val me = ctx.host.me
    val views = remember(ctx.messages) { RisiLedger.items(ctx.messages) }
    val items = r.items.map { i -> views[i.id.lowercase()] ?: LedgerItemView(i.id, r.summaryId, i.owner, i.counterpart, i.text, i.due, i.allDay, i.dueText, i.state ?: RisiItemStates.PROPOSED, null) }
    val expired = RisiLedger.expired(r, ctx.nowMs)
    Column(Modifier.testTag("risi_discussion_summary"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(RisiLedger.summaryHeader(r, me, ctx.nameOf), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("risi_summary_header"))
        r.keyPoints.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("risi_key_point")) }
        val (mine, others) = items.partition { it.owner.equals(me, true) }
        if (mine.isNotEmpty()) {
            Text("You agreed:", style = MaterialTheme.typography.titleSmall)
            ItemRows(mine, mine = true, expired = expired, ctx = ctx)
        }
        others.groupBy { it.owner?.lowercase() }.forEach { (owner, its) ->
            Text("${owner?.let(ctx.nameOf) ?: "Someone"} agreed:", style = MaterialTheme.typography.titleSmall)
            ItemRows(its, mine = false, expired = expired, ctx = ctx)
        }
        r.conversationId?.let { conv ->
            TextButton(onClick = { ctx.host.openChat(conv, r.startedAt) }, modifier = Modifier.testTag("risi_open_chat")) { Text("Open chat") }
        }
    }
}

/** §27.4 the short card in Official: one line, the count, [Open Risi chat] for participants. */
@Composable
internal fun DiscussionCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    Column(Modifier.testTag("risi_discussion_card"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(r.summary ?: row.body, style = MaterialTheme.typography.bodyLarge)
        val participant = r.withUsers.any { it.equals(ctx.host.me, true) }
        if (participant) {
            Text(RisiLedger.cardCountLine(r.itemsCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_card_count"))
            val sid = r.summaryId
            if (sid != null && (ctx.risiChatReady || ctx.host.risiChatAvailable())) {
                TextButton(onClick = { ctx.host.openRisiChat(sid) }, modifier = Modifier.testTag("risi_open_risi_chat")) { Text("Open Risi chat") }
            }
        }
    }
}

/** §27.6 `item_due` / `item_overdue` / `item_nudge` in my Risi chat. */
@Composable
internal fun ItemReminderCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val views = remember(ctx.messages) { RisiLedger.items(ctx.messages) }
    val item = r.itemId?.lowercase()?.let { views[it] }
    val summary = remember(ctx.messages) { r.summaryId?.lowercase()?.let { RisiLedger.summaries(ctx.messages)[it]?.second } }
    var newDate by remember { mutableStateOf(false) }
    Column(Modifier.testTag("risi_item_reminder"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(row.body, style = MaterialTheme.typography.bodyLarge)
        RisiLedger.dueLabel(r.due, r.allDay == true, null)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        item?.takeIf { !RisiItemStates.tracked(it.state) && it.state != RisiItemStates.PROPOSED }?.let { Text(RisiLedger.stateLabel(it.state, false), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("risi_item_state")) }
        val buttons = if (ctx.readOnly) emptyList() else RisiLedger.reminderButtons(ctx.host.me, r, item, ctx.awaiting)
        val id = r.itemId
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), itemVerticalAlignment = Alignment.CenterVertically) {
            if (id != null) {
                if (RisiItemDue.BUTTON_DONE in buttons) FilledTonalButton(onClick = { ctx.host.actItem(id, RisiLedger.DONE) }, modifier = Modifier.testTag("risi_reminder_done")) { Text("Done") }
                if (RisiItemDue.BUTTON_NEW_DATE in buttons) OutlinedButton(onClick = { newDate = true }, modifier = Modifier.testTag("risi_reminder_new_date")) { Text("New date") }
                if (RisiLedger.MARK_DONE in buttons) OutlinedButton(onClick = { ctx.host.actItem(id, RisiLedger.DONE) }, modifier = Modifier.testTag("risi_reminder_mark_done")) { Text("Mark done") }
            }
            summary?.conversationId?.let { conv ->
                TextButton(onClick = { ctx.host.openChat(conv, summary.startedAt) }, modifier = Modifier.testTag("risi_reminder_open_chat")) { Text("Open chat") }
            }
        }
    }
    val itemId = r.itemId
    if (newDate && itemId != null) ItemEditDialog(itemId, item?.text ?: r.text.orEmpty(), item?.due ?: r.due, item?.allDay ?: (r.allDay == true), ctx) { newDate = false }
}

/** §27.6 the personal digest: my open items, then what is owed to me. */
@Composable
internal fun PersonalDigestCard(r: RisiMeta, ctx: RisiCardContext) {
    val (mine, owed) = RisiLedger.digestSplit(r, ctx.host.me)
    Column(Modifier.testTag("risi_personal_digest"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (mine.isNotEmpty()) {
            Text("Your open items", style = MaterialTheme.typography.titleSmall)
            mine.forEach { DigestLine(it, null) }
        }
        if (owed.isNotEmpty()) {
            Text("Owed to you", style = MaterialTheme.typography.titleSmall)
            owed.forEach { DigestLine(it, it.owner?.let(ctx.nameOf)) }
        }
    }
}

@Composable
private fun DigestLine(i: RisiDigestItem, owner: String?) {
    Row(Modifier.testTag("risi_digest_item"), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Column {
            Text((owner?.let { "$it: " } ?: "") + i.text, style = MaterialTheme.typography.bodyLarge)
            RisiLedger.dueLabel(i.due, i.allDay, i.dueText)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
