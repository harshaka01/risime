package ai.risicloud.risime.ui.tabs

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.tabs.CommitmentView
import ai.risicloud.risime.data.tabs.RisiCards
import ai.risicloud.risime.data.tabs.RisiKinds
import ai.risicloud.risime.net.RisiMeta
import ai.risicloud.risime.ui.common.SystemLineText
import ai.risicloud.risime.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DUE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)

/** "Fri 9 Oct, 5:00 pm" in the phone's zone; null when [iso] doesn't parse. */
fun formatDue(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
    iso?.let { runCatching { DUE_FORMAT.format(Instant.parse(it).atZone(zone)) }.getOrNull() }

/** "Kamal: send the quote" -> ("Kamal", "send the quote") when the prefix names a member (§24.11 summary action items). */
fun splitOwner(item: String, names: Collection<String>): Pair<String?, String> {
    val i = item.indexOf(':')
    if (i <= 0) return null to item
    val head = item.substring(0, i).trim()
    return if (names.any { it.equals(head, true) }) head to item.substring(i + 1).trim() else null to item
}

/**
 * §24.11 one Risi message as a Material 3 card in the Official accent. Only called for a row whose `risi`
 * object was honoured when it arrived (agent leaf, Official); any other message is a plain bubble.
 */
@Composable
fun RisiCardRow(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext, modifier: Modifier = Modifier) {
    if (r.kind == RisiKinds.COMMITMENT_UPDATE) {
        // The original card shows the new state; here only the small line.
        SystemLineText(row.body)
        return
    }
    if (r.kind == ai.risicloud.risime.net.RisiKinds127.ITEM_UPDATE) {
        // §27.5 applied to the summary card it belongs to (no bubble); without that card, a small line.
        if (!ai.risicloud.risime.data.tabs.RisiLedger.updateHasCard(r, ctx.messages)) SystemLineText(row.body)
        return
    }
    if (r.kind == ai.risicloud.risime.net.RisiKinds135.CONFIRM_UPDATE) {
        // v1.35 §34.2 applied to the card with that write_id (greyed, "Replaced by your newer request"); no bubble.
        // Without that card on this phone, a small line.
        if (!ai.risicloud.risime.data.tabs.RisiP0Cards.confirmUpdateHasCard(r, ctx.messages)) SystemLineText(row.body)
        return
    }
    if (r.kind == ai.risicloud.risime.net.RisiKinds129.EVENT_UPDATE) {
        // §29.10 applied to the cards of that event and the cache (no bubble); without such a card, a small line.
        ai.risicloud.risime.net.RisiCalendarCard.parse(row.systemJson)?.let { RisiEventUpdateLine(row, it, ctx) }
        return
    }
    // v1.39 §36.5 call transcription: the consent line, the transcript lines and the developer comparison.
    if (r.kind == ai.risicloud.risime.net.RisiKinds139.CALL_TRANSCRIBE) {
        val l = remember(row.systemJson) { ai.risicloud.risime.net.RisiTranscriptKinds.consentLine(row.systemJson) }
        val zone = remember { java.time.ZoneId.systemDefault() }
        Box(Modifier.testTag("risi_call_transcribe")) { SystemLineText(l?.let { ai.risicloud.risime.data.tabs.RisiTranscriptLines.consent(it, ctx.nameOf, zone, row.body) } ?: row.body) }
        return
    }
    // v1.41 §37.6: a voice note's transcript is drawn under the note's bubble (collapsed); without the note, a small line.
    if (r.kind == ai.risicloud.risime.net.RisiKinds141.VOICE_TRANSCRIPT) {
        if (!ai.risicloud.risime.ui.chat.transcriptHasNote(row, ctx.messages)) {
            val t = remember(row.systemJson) { ai.risicloud.risime.net.RisiVoiceTranscript.parse(row.systemJson) }
            Box(Modifier.testTag("risi_voice_transcript_line")) { SystemLineText(if (t?.ok == true) row.body else ai.risicloud.risime.ui.chat.VoiceStrings.COULDNT) }
        }
        return
    }
    // Proposal 2026-10-14-call-risi §41.5: a Call Risi's whole transcript (it has `lines`; §36.5's lines don't).
    if (r.kind == ai.risicloud.risime.net.RisiKinds139.CALL_TRANSCRIPT || r.kind == ai.risicloud.risime.calls.risi.CallTranscript.ALT_KIND) {
        val call = remember(row.systemJson) { ai.risicloud.risime.calls.risi.CallTranscript.parseJson(row.systemJson) }
        if (call != null) {
            Box(modifier.fillMaxWidth().padding(end = 32.dp), contentAlignment = Alignment.CenterStart) {
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, border = BorderStroke(1.dp, OfficialAccent.copy(alpha = 0.6f))) {
                    ai.risicloud.risime.calls.risi.CallTranscriptCard(call, Modifier.padding(Spacing.md))
                }
            }
            return
        }
    }
    if (r.kind == ai.risicloud.risime.net.RisiKinds139.CALL_TRANSCRIPT) {
        CallTranscriptLine(row, ctx, modifier)
        return
    }
    if (r.kind == ai.risicloud.risime.net.RisiKinds139.CALL_TRANSCRIPT_COMPARE) {
        CallTranscriptCompareCard(row, ctx, modifier)
        return
    }
    Box(modifier.fillMaxWidth().padding(end = 32.dp), contentAlignment = Alignment.CenterStart) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, OfficialAccent.copy(alpha = 0.6f)),
            modifier = Modifier.testTag("risi_card_${r.kind}"),
        ) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RisiCardHeader(headerOf(r), r, ctx)
                when (r.kind) {
                    RisiKinds.COMMITMENT -> CommitmentCard(row, r, ctx)
                    RisiKinds.REMINDER -> ReminderCard(row, r, ctx)
                    RisiKinds.ESCALATION -> EscalationCard(row, r, ctx)
                    RisiKinds.DIGEST -> if (r.scope == "personal") PersonalDigestCard(r, ctx) else DigestCard(r, ctx)
                    RisiKinds.ANSWER -> if (r.clarify != null) RisiClarifyCard(row, r, ctx) else AnswerCard(row, r, ctx)
                    RisiKinds.SUMMARY -> SummaryCard(r, ctx)
                    RisiKinds.REPORT -> ReportCard(r)
                    RisiKinds.OFFER -> OfferCard(r, ctx)
                    RisiKinds.ERROR -> RisiErrorCard(row, r, ctx)
                    RisiKinds.CONFIRM -> if (r.tool == ai.risicloud.risime.net.RisiKinds129.TOOL_RISI_CALENDAR_ADD) RisiCalendarAddCard(row, r, ctx)
                        else if (ai.risicloud.risime.data.tabs.RisiCalendarCards.isCalendarAdd(r) && ai.risicloud.risime.data.tabs.RisiCalendarCards.proposal(r) != null) CalendarActionCard(row, r, ctx) else ConfirmCard(row, r, ctx)
                    RisiKinds.REMINDER_SET -> ReminderSetCard(row, r, ctx)
                    RisiKinds.DRAFT -> DraftCard(row, r, ctx)
                    RisiKinds.SKILL_DONE -> SkillDoneCard(row, r, ctx)
                    RisiKinds.SKILL_NEEDED -> SkillNeededCard(row, r, ctx)
                    ai.risicloud.risime.net.RisiKinds127.DISCUSSION_SUMMARY -> DiscussionSummaryCard(r, ctx)
                    ai.risicloud.risime.net.RisiKinds127.DISCUSSION_CARD -> DiscussionCard(row, r, ctx)
                    ai.risicloud.risime.net.RisiKinds127.ITEM_DUE, ai.risicloud.risime.net.RisiKinds127.ITEM_OVERDUE, ai.risicloud.risime.net.RisiKinds127.ITEM_NUDGE -> ItemReminderCard(row, r, ctx)
                    ai.risicloud.risime.net.RisiKinds127.ITEM_CLARIFY -> ItemClarifyCard(row, r, ctx)
                    in ai.risicloud.risime.net.RisiKinds129.ALL -> RisiCalendarCardBody(row, r, ctx)
                    ai.risicloud.risime.net.RisiKinds130.NOTE_CARD -> ai.risicloud.risime.ui.notes.NoteCardBody(row, r, ctx)
                    ai.risicloud.risime.net.RisiKinds130.NOTES_SAVED -> ai.risicloud.risime.ui.notes.NotesSavedCardBody(row, r, ctx)
                    ai.risicloud.risime.net.RisiKinds131.GOOGLE_RECONNECT -> GoogleReconnectCard(row, r, ctx)
                    // v1.42 §38.3/§38.4 action items (a malformed card shows its body).
                    ai.risicloud.risime.net.RisiKinds142.ACTION_ITEMS, ai.risicloud.risime.net.RisiKinds142.ACTION_CONFIRM -> {
                        val card = remember(row.systemJson) { ai.risicloud.risime.net.RisiActionCard.parse(row.systemJson) }
                        when {
                            card == null -> ai.risicloud.risime.ui.common.RisiMarkdownText(row.body)
                            card.kind == ai.risicloud.risime.net.RisiKinds142.ACTION_ITEMS -> ActionItemsCardBody(row, card, ctx)
                            else -> ActionConfirmCardBody(row, card, ctx)
                        }
                    }
                    else -> ai.risicloud.risime.ui.common.RisiMarkdownText(row.body)
                }
                FeedbackRow(r, ctx)
            }
        }
    }
}

private fun headerOf(r: RisiMeta) = risiCalendarHeader(r) ?: when (r.kind) {
    RisiKinds.COMMITMENT -> "Risi · Track this?"
    RisiKinds.REMINDER -> "Risi · Reminder"
    RisiKinds.ESCALATION -> "Risi · Overdue"
    RisiKinds.DIGEST -> (if (r.scope == "personal") "Risi · Your items" else "Risi · Open items") + (r.date?.let { " · $it" } ?: "")
    RisiKinds.ANSWER -> if (r.clarify != null) ai.risicloud.risime.data.tabs.RisiP0Cards.CLARIFY_HEADER else "Risi"
    RisiKinds.SUMMARY -> "Risi · Summary" + if (r.partial) " (partial)" else ""
    RisiKinds.REPORT -> "Risi · Report"
    RisiKinds.OFFER -> "Risi"
    // Server item 8: a proactive offer (origin "offer") asks "Add to calendar?" / "Remind me?".
    RisiKinds.CONFIRM -> ai.risicloud.risime.data.tabs.RisiOffers.header(r)?.let { "Risi · $it" }
        ?: if (ai.risicloud.risime.data.tabs.RisiCalendarCards.isCalendarAdd(r)) "Risi · Add to calendar" else "Risi · Confirm"
    RisiKinds.REMINDER_SET -> "Risi · Reminder set"
    RisiKinds.DRAFT -> "Risi · Draft"
    RisiKinds.SKILL_DONE -> "Risi · Done"
    RisiKinds.SKILL_NEEDED -> "Risi · Skill needed"
    ai.risicloud.risime.net.RisiKinds127.DISCUSSION_SUMMARY -> "Risi · Follow-ups"
    ai.risicloud.risime.net.RisiKinds127.DISCUSSION_CARD -> "Risi · Discussion summary"
    ai.risicloud.risime.net.RisiKinds127.ITEM_DUE, ai.risicloud.risime.net.RisiKinds127.ITEM_OVERDUE, ai.risicloud.risime.net.RisiKinds127.ITEM_NUDGE ->
        ai.risicloud.risime.data.tabs.RisiLedger.reminderHeader(r)
    ai.risicloud.risime.net.RisiKinds127.ITEM_CLARIFY -> "Risi · When is it due?"
    ai.risicloud.risime.net.RisiKinds130.NOTE_CARD -> "Risi · Notes"
    ai.risicloud.risime.net.RisiKinds130.NOTES_SAVED -> "Risi · Notes saved"
    ai.risicloud.risime.net.RisiKinds131.GOOGLE_RECONNECT -> "Risi · Google Calendar"
    ai.risicloud.risime.net.RisiKinds142.ACTION_ITEMS -> ai.risicloud.risime.data.tabs.RisiActionItems.HEADER_ITEMS
    ai.risicloud.risime.net.RisiKinds142.ACTION_CONFIRM -> ai.risicloud.risime.data.tabs.RisiActionItems.HEADER_CONFIRM
    else -> "Risi"
}

@Composable
private fun OwnerChip(owner: String?, ctx: RisiCardContext) {
    if (owner == null) return
    // v1.32 item 7: a label, not a chip (a chip with no action is removed).
    Text(ctx.nameOf(owner), style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.testTag("risi_owner"))
}

@Composable
private fun CommitmentCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val id = r.commitmentId?.lowercase()
    val view = (id?.let { ctx.states[it] }) ?: CommitmentView(r.state ?: "proposed", r.text, r.due, null)
    val expired = RisiCards.expired(row, view, ctx.nowMs)
    var editing by remember { mutableStateOf(false) }
    val dim = if (expired || view.state in setOf("declined", "cancelled")) 0.55f else 1f
    Column(Modifier.testTag("risi_commitment"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(view.text ?: r.text ?: row.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs), itemVerticalAlignment = Alignment.CenterVertically) {
            OwnerChip(r.owner, ctx)
            (formatDue(view.due) ?: r.dueText)?.let { Text("Due $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("risi_due")) }
        }
        Text(RisiCards.stateLabel(view, expired, ctx.nameOf), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_state"))
        val buttons = if (ctx.readOnly) emptyList() else RisiCards.buttons(ctx.host.me, r, view, row, ctx.awaiting, ctx.nowMs)
        if (buttons.isNotEmpty() && id != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if ("confirm" in buttons) FilledTonalButton(onClick = { ctx.host.act(id, "confirm") }, modifier = Modifier.testTag("risi_confirm")) { Text("✓ Confirm", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                if ("decline" in buttons) OutlinedButton(onClick = { ctx.host.act(id, "decline") }, modifier = Modifier.testTag("risi_decline")) { Text("✗ Decline", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                if ("edit" in buttons) OutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("risi_edit")) { Text("✎ Edit", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                if ("done" in buttons) FilledTonalButton(onClick = { ctx.host.act(id, "done") }, modifier = Modifier.testTag("risi_done")) { Text("✓ Done", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }
    }
    if (editing && id != null) {
        RisiEditDialog(
            initialText = view.text ?: r.text.orEmpty(), initialDue = view.due ?: r.due, ownerName = r.owner?.let(ctx.nameOf),
            onSave = { t, d -> editing = false; ctx.host.act(id, "edit", t, d) },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun ReminderCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val view = r.commitmentId?.lowercase()?.let { ctx.states[it] }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(view?.text ?: row.body, style = MaterialTheme.typography.bodyLarge)
        (formatDue(r.due))?.let { Text("Due $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun EscalationCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val view = r.commitmentId?.lowercase()?.let { ctx.states[it] }
    val late = r.overdueBy?.let { s -> if (s >= 86_400) "${s / 86_400} day" + (if (s / 86_400 == 1L) "" else "s") else "${maxOf(1, s / 3600)} h" }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(view?.text ?: row.body, style = MaterialTheme.typography.bodyLarge)
        late?.let { Text("Overdue by $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun DigestCard(r: RisiMeta, ctx: RisiCardContext) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        r.items.forEach { it ->
            Column(Modifier.testTag("risi_digest_item")) {
                Text(it.text, style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), itemVerticalAlignment = Alignment.CenterVertically) {
                    OwnerChip(it.owner, ctx)
                    formatDue(it.due)?.let { d -> Text(d, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun AnswerCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ai.risicloud.risime.ui.common.RisiMarkdownText(ai.risicloud.risime.data.gcal.GcalNames.render(r.answer ?: row.body, r.sources, ctx.host.gcalNames), style = MaterialTheme.typography.bodyLarge)
        // Sources: a tappable quote of each message (sender + first words) that scrolls to it; nothing for one not on this phone.
        ctx.quotes(r).forEachIndexed { i, q ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { ctx.onRef(q.messageId) }.testTag("risi_ref_$i"),
            ) {
                Text(
                    "${q.sender}: “${q.excerpt}”",
                    Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
        }
        AnswerExtras(r, ctx)
    }
}

@Composable
private fun SummaryCard(r: RisiMeta, ctx: RisiCardContext) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ai.risicloud.risime.data.tabs.SummaryPeriods.periodLine(r.period)?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("risi_summary_period"))
        }
        r.summary?.let { ai.risicloud.risime.ui.common.RisiMarkdownText(it, style = MaterialTheme.typography.bodyLarge) }
        if (r.days.isNotEmpty()) {
            Text(
                "Based on: " + r.days.joinToString(", ") { ai.risicloud.risime.data.tabs.SummaryPeriods.dayLabel(it) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("risi_summary_days"),
            )
        }
        Section("Decisions", r.decisions, "risi_decisions")
        if (r.actionItems.isNotEmpty()) {
            Text("Action items", style = MaterialTheme.typography.titleSmall)
            r.actionItems.forEach { item ->
                val (owner, text) = splitOwner(item, ctx.knownNames)
                Row(Modifier.testTag("risi_action_item"), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (owner != null) Text(owner, style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.testTag("risi_action_owner"))
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Section("Open questions", r.openQuestions, "risi_questions")
    }
}

@Composable
private fun Section(title: String, items: List<String>, tag: String) {
    if (items.isEmpty()) return
    Column(Modifier.testTag(tag)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ReportCard(r: RisiMeta) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        r.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        r.sections.forEach { s ->
            Column(Modifier.testTag("risi_report_section")) {
                Text(s.heading, style = MaterialTheme.typography.titleSmall)
                Text(s.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun OfferCard(r: RisiMeta, ctx: RisiCardContext) {
    val id = r.offerId
    val buttons = if (ctx.readOnly) emptyList() else RisiCards.offerButtons(ctx.host.me, r, ctx.awaiting)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(r.question ?: r.topic.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        if (id != null && buttons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if ("offer_yes" in buttons) Button(onClick = { ctx.host.act(id, "offer_yes") }, modifier = Modifier.testTag("risi_yes")) { Text("Yes", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                if ("offer_not_now" in buttons) OutlinedButton(onClick = { ctx.host.act(id, "offer_not_now") }, modifier = Modifier.testTag("risi_not_now")) { Text("Not now", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * §27.1 the card's header: a tap on the Risi name or ⓘ opens "Made by Risi · 09:14" with the 👍/👎 of
 * §24.11. No model or vendor names here: those are for a Developer view only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisiCardHeader(text: String, r: RisiMeta, ctx: RisiCardContext) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.clickable(onClickLabel = "Made by") { open = true }.testTag("risi_made_by_open"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = OfficialAccent, fontWeight = FontWeight.SemiBold)
        Text("ⓘ", style = MaterialTheme.typography.labelMedium, color = OfficialAccent, modifier = Modifier.testTag("risi_made_by_info"))
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(Spacing.lg).testTag("risi_made_by_sheet"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(ai.risicloud.risime.data.tabs.MadeByLabels.title(r.madeBy, ctx.nowMs), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("risi_made_by"))
                r.callRef?.let { ref ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text("Was this helpful?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("👍", Modifier.clickable { open = false; ctx.host.feedback(ref, "up", null) }.padding(Spacing.sm).testTag("risi_made_by_up"))
                        Text("👎", Modifier.clickable { open = false; ctx.host.feedback(ref, "down", null) }.padding(Spacing.sm).testTag("risi_made_by_down"))
                    }
                }
            }
        }
    }
}

/** 👍/👎 for the model call behind a card (`call_ref`); private REST, nothing shows in the chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackRow(r: RisiMeta, ctx: RisiCardContext) {
    val ref = r.callRef ?: return
    var reasonFor by remember(ref) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Text("👍", Modifier.clickable { ctx.host.feedback(ref, "up", null) }.padding(Spacing.sm).testTag("risi_up"))
        Text("👎", Modifier.clickable { reasonFor = true }.padding(Spacing.sm).testTag("risi_down"))
    }
    if (reasonFor) {
        var reason by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { reasonFor = false; ctx.host.feedback(ref, "down", null) }) {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(Spacing.lg).testTag("risi_reason_sheet"), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("What went wrong? (optional)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(reason, { reason = it.take(500) }, Modifier.fillMaxWidth().testTag("risi_reason"), placeholder = { Text("Wrong due date, not relevant, …") })
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = { reasonFor = false; ctx.host.feedback(ref, "down", reason) }, modifier = Modifier.testTag("risi_reason_send")) { Text("Send", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    TextButton(onClick = { reasonFor = false; ctx.host.feedback(ref, "down", null) }) { Text("Skip", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/** ✎ Edit: the text and the due date and time (the owner stays as Risi proposed it; the contract's `edit` carries text and due only). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisiEditDialog(initialText: String, initialDue: String?, ownerName: String?, onSave: (text: String, due: String?) -> Unit, onDismiss: () -> Unit, title: String = "Edit commitment") {
    val zone = ZoneId.systemDefault()
    val initial = remember(initialDue) { initialDue?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDateTime() }.getOrNull() } }
    var text by remember { mutableStateOf(initialText) }
    var date by remember { mutableStateOf(initial?.toLocalDate()) }
    var time by remember { mutableStateOf(initial?.toLocalTime()) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedTextField(text, { text = it.take(300) }, Modifier.fillMaxWidth().testTag("risi_edit_text"), label = { Text("What", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })
                ownerName?.let { Text("Owner: $it", style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.testTag("risi_edit_date")) { Text(date?.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) ?: "Pick a date") }
                    OutlinedButton(onClick = { pickTime = true }, enabled = date != null, modifier = Modifier.testTag("risi_edit_time")) { Text(time?.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) ?: "Pick a time") }
                }
                if (date != null) TextButton(onClick = { date = null; time = null }) { Text("No due date", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val due = date?.let { d -> LocalDateTime.of(d, time ?: LocalTime.of(17, 0)).atZone(zone).toInstant().toEpochMilli() }
                    onSave(text.trim(), due?.let { ai.risicloud.risime.data.tabs.RisiControl.iso(it) })
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("risi_edit_save"),
            ) { Text("Save", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
    )
    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; pickDate = false }) { Text("OK", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
        ) { DatePicker(st) }
    }
    if (pickTime) {
        val t0 = time ?: LocalTime.of(17, 0)
        val st = rememberTimePickerState(t0.hour, t0.minute, false)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(st.hour, st.minute); pickTime = false }) { Text("OK", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
            text = { TimePicker(st) },
        )
    }
}

/** v1.39 §36.5 one transcript turn: "Harsha 17:52: …" (name from this phone, time in its zone); a quiet line, no card. */
@Composable
private fun CallTranscriptLine(row: MessageEntity, ctx: RisiCardContext, modifier: Modifier = Modifier) {
    val t = remember(row.systemJson) { ai.risicloud.risime.net.RisiTranscriptKinds.transcript(row.systemJson) }
    val zone = remember { ZoneId.systemDefault() }
    Box(modifier.fillMaxWidth().lineFlash(row, ctx, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)).padding(end = 32.dp).testTag("risi_call_transcript"), contentAlignment = Alignment.CenterStart) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), border = BorderStroke(1.dp, OfficialAccent.copy(alpha = 0.35f))) {
            Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)) {
                val speaker = t?.speaker
                if (t != null && speaker != null) {
                    Text(
                        listOfNotNull(ctx.nameOf(speaker), ai.risicloud.risime.data.tabs.RisiTranscriptLines.hm(t.start, zone)).joinToString(" "),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = OfficialAccent,
                    )
                    Text(t.text, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(row.body, style = MaterialTheme.typography.bodyMedium)
                }
                // Ownership rule (0598707): no model names outside the Developer view.
                Text("Risi transcript", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** v1.39 §36.5 developer compare mode: every model's text and time on one chunk, the routed one first. */
@Composable
private fun CallTranscriptCompareCard(row: MessageEntity, ctx: RisiCardContext, modifier: Modifier = Modifier) {
    val c = remember(row.systemJson) { ai.risicloud.risime.net.RisiTranscriptKinds.compare(row.systemJson) }
    val zone = remember { ZoneId.systemDefault() }
    Box(modifier.fillMaxWidth().padding(end = 16.dp).testTag("risi_call_transcript_compare"), contentAlignment = Alignment.CenterStart) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, border = BorderStroke(1.dp, OfficialAccent.copy(alpha = 0.6f))) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("Risi · Model comparison", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = OfficialAccent)
                if (c == null) {
                    Text(row.body, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(ai.risicloud.risime.data.tabs.RisiTranscriptLines.compareHeader(c, ctx.nameOf, zone), style = MaterialTheme.typography.labelMedium)
                    ai.risicloud.risime.data.tabs.RisiTranscriptLines.compareRows(c).forEach { r ->
                        Column(Modifier.fillMaxWidth().testTag("risi_compare_row")) {
                            Text(
                                r.model + (if (r.chosen) " (routed)" else "") + " · ${r.elapsedMs} ms",
                                style = MaterialTheme.typography.labelSmall, fontWeight = if (r.chosen) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(r.text, style = MaterialTheme.typography.bodyMedium, color = if (r.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            r.script?.let { label ->
                                // v1.40 §36.11: the Sinhala script check, for judging each model.
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (r.scriptOk == true) OfficialAccent else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("risi_compare_script"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
