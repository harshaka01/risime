package lk.codegen.risime.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.CalendarAddRecord
import lk.codegen.risime.data.tabs.CalendarProposal
import lk.codegen.risime.data.tabs.CalendarSelection
import lk.codegen.risime.data.tabs.PhoneCalendarInfo
import lk.codegen.risime.data.tabs.RisiCalendarCards
import lk.codegen.risime.data.tabs.RisiCalendarPort
import lk.codegen.risime.data.tabs.RisiSkillCards
import lk.codegen.risime.data.tabs.RisiToolCards
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.RisiSkillIds
import lk.codegen.risime.ui.theme.Spacing
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * P0 (Harsha's phone): the calendar action card — title, date, start–end and calendar with
 * [Add] [Edit] [Cancel]. [Add] sends `confirm_write` (the phone then adds the event; no model turn);
 * on first use it opens the calendar picker first. Afterwards the card is the success card
 * "Added to your Google Calendar: … [Open] [Undo]" from this phone's own record, or the exact reason.
 */

private val NO_RECORDS = MutableStateFlow<Map<String, CalendarAddRecord>>(emptyMap())
private val NO_CHOICE = MutableStateFlow<Long?>(null)

@Composable
internal fun CalendarActionCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val p = RisiCalendarCards.proposal(r) ?: return
    val zone = remember { ZoneId.systemDefault() }
    val port = ctx.host.calendar
    val records by (port?.records ?: NO_RECORDS).collectAsState()
    val chosenId by (port?.chosenId ?: NO_CHOICE).collectAsState()
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<PhoneCalendarInfo?>(null) }
    var picker by remember { mutableStateOf<List<PhoneCalendarInfo>?>(null) }
    var afterPick by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var pendingEdit by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    val hint = r.calendarHint()
    // The phone's remembered pick, else the server's hint (proposal 2026-10-09 §1) if it names a calendar here.
    LaunchedEffect(port, chosenId) { chosen = port?.chosen() ?: port?.matchHint(hint) }

    val state = RisiToolCards.confirmState(r, ctx.messages, ctx.nowMs)
    val wid = r.writeId
    val rec = wid?.let { records[it.lowercase()] }
    val dim = if (state == RisiToolCards.ConfirmState.OPEN || state == RisiToolCards.ConfirmState.CONFIRMED) 1f else 0.55f
    val buttons = if (ctx.readOnly || wid == null) emptyList() else RisiToolCards.confirmButtons(ctx.host.me, r, state, ctx.awaiting)

    /** confirm_write (with the [Edit] corrections, if any); a calendar matched from the hint is remembered first. */
    fun confirmNow(c: PhoneCalendarInfo?) {
        val w = wid ?: return
        val edit = pendingEdit
        scope.launch {
            if (port != null && c != null && chosenId != c.id) port.choose(c.id)
            if (edit != null) ctx.host.confirmEdited(w, edit) else ctx.host.act(w, "confirm_write")
            pendingEdit = null
        }
    }

    fun openPicker(thenAdd: Boolean) {
        val port0 = port ?: return
        scope.launch {
            if (!port0.hasPermission()) { note = "Calendar permission is off on this phone."; return@launch }
            val opts = port0.options()
            if (opts.isEmpty()) { note = "No writable Google calendar on this phone. Add your Google account in Android Settings → Accounts."; return@launch }
            afterPick = thenAdd
            picker = opts
        }
    }

    Column(Modifier.testTag("risi_calendar_card"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (state == RisiToolCards.ConfirmState.CONFIRMED && rec != null && rec.added && !rec.removed) {
            AddedBlock(rec, r, ctx, port, zone)
            return@Column
        }
        Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim), modifier = Modifier.testTag("risi_calendar_title"))
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().testTag("risi_confirm_args")) {
            Column(Modifier.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Field("Date", if (p.allDay) RisiCalendarCards.whenText(p, zone) else RisiCalendarCards.dateText(p.start, zone))
                if (!p.allDay) Field("Time", RisiCalendarCards.timeRange(p.start, p.end, zone))
                if (port != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("Calendar", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val c = chosen
                        if (c != null) {
                            Text(CalendarSelection.label(c), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false).testTag("risi_calendar_chosen"))
                        } else if (hint != null && !port.hasPermission()) {
                            Text(listOfNotNull(hint.account, hint.name).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false).testTag("risi_calendar_hint"))
                        }
                        if (state == RisiToolCards.ConfirmState.OPEN && buttons.isNotEmpty()) {
                            TextButton(onClick = { openPicker(false) }, modifier = Modifier.testTag("risi_calendar_choose")) { Text(if (c == null) "Choose calendar" else "Change") }
                        } else if (c == null) {
                            Text("—", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        when (state) {
            RisiToolCards.ConfirmState.CANCELLED -> StateLine("Cancelled")
            RisiToolCards.ConfirmState.EXPIRED -> StateLine("Expired")
            RisiToolCards.ConfirmState.CONFIRMED -> when {
                rec?.removed == true -> StateLine("Removed from your calendar", "risi_calendar_removed")
                rec?.failureText != null -> {
                    Text("Couldn't add it: ${rec.failureText}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("risi_calendar_failed"))
                    if (rec.failure == "NO_PERMISSION") OutlinedButton(onClick = { ctx.host.openSkills(RisiSkillIds.CALENDAR) }) { Text("Open Risi skills", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
                else -> StateLine("Adding to your calendar…", "risi_calendar_adding")
            }
            RisiToolCards.ConfirmState.OPEN -> Unit
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("risi_calendar_note")) }
        if (buttons.isNotEmpty() && wid != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if ("add" in buttons) {
                    FilledTonalButton(onClick = {
                        // First use: the picker, then the add. Otherwise straight to confirm_write (the phone adds it).
                        pendingEdit = null
                        if (port != null && chosen == null) openPicker(true) else confirmNow(chosen)
                    }, modifier = Modifier.testTag("risi_confirm_add")) { Text("Add", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    OutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("risi_calendar_edit")) { Text("Edit", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
                if ("cancel" in buttons) OutlinedButton(onClick = { ctx.host.act(wid, "cancel_write") }, modifier = Modifier.testTag("risi_confirm_cancel")) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        } else if (state == RisiToolCards.ConfirmState.OPEN && wid != null && wid.lowercase() in ctx.awaiting) {
            StateLine("Sending…")
        }
    }

    picker?.let { opts ->
        CalendarPickerDialog(opts, chosen?.id, onPick = { c ->
            picker = null
            scope.launch {
                port?.choose(c.id)
                chosen = c
                note = null
                if (afterPick) confirmNow(c)
                afterPick = false
            }
        }, onDismiss = { picker = null; afterPick = false })
    }
    if (editing && wid != null) {
        CalendarEditDialog(p.title, p.start.atZone(zone).toLocalDate(), p.start.atZone(zone).toLocalTime(), ((p.end.epochSecond - p.start.epochSecond) / 60).toInt().coerceAtLeast(15), p.allDay,
            calendarLabel = chosen?.let(CalendarSelection::label), onChooseCalendar = if (port != null) ({ openPicker(false) }) else null,
            onSend = { edit ->
                // Proposal 2026-10-09 §4: [Add] with the corrections (confirm_write + edit); no new model turn.
                editing = false
                pendingEdit = edit
                if (port != null && chosen == null) openPicker(true) else confirmNow(chosen)
            }, onDismiss = { editing = false })
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StateLine(text: String, tag: String = "risi_confirm_state") =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(tag))

/** "Added to your Google Calendar: … [Open] [Undo]" (Undo with the matching `skill_done`'s token). */
@Composable
private fun AddedBlock(rec: CalendarAddRecord, r: RisiMeta, ctx: RisiCardContext, port: RisiCalendarPort?, zone: ZoneId) {
    // With the server's skill_done below, that line carries the success text, [Open] and [Undo] (its undo
    // token): the card only says it is done. Without one (a v1.25 answer), the card is the success card.
    if (RisiCalendarCards.skillDoneFor(r, ctx.messages) != null) {
        Text("✓ Added · " + RisiCalendarCards.whenText(CalendarProposal(rec.title, java.time.Instant.parse(rec.start), java.time.Instant.parse(rec.end), rec.allDay), zone),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("risi_calendar_added_short"))
        rec.calendarName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    Text(RisiCalendarCards.addedText(rec, zone), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("risi_calendar_added"))
    rec.calendarName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val eid = rec.eventId
    if (port != null && eid != null) FilledTonalButton(onClick = { port.open(eid) }, modifier = Modifier.testTag("risi_calendar_open")) { Text("Open", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
}

/** First use (and Change): the writable calendars as "harsha@… · Google"; read-only ones never listed. */
@Composable
fun CalendarPickerDialog(options: List<PhoneCalendarInfo>, selectedId: Long?, onPick: (PhoneCalendarInfo) -> Unit, onDismiss: () -> Unit) {
    var sel by remember { mutableStateOf(options.firstOrNull { it.id == selectedId } ?: options.firstOrNull()) }
    val localOnly = options.none(CalendarSelection::isGoogle)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a calendar", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("risi_calendar_picker"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("Risi adds events here. You can change it in Settings → Risi skills → Calendar.", style = MaterialTheme.typography.bodySmall)
                options.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().selectable(sel?.id == c.id, onClick = { sel = c }).testTag("risi_calendar_option_${c.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(sel?.id == c.id, onClick = null)
                        Column(Modifier.padding(start = Spacing.sm)) {
                            Text(CalendarSelection.label(c), style = MaterialTheme.typography.bodyLarge)
                            CalendarSelection.subLabel(c)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                if (localOnly) Text("No Google calendar on this phone: these stay on this phone only and are not synced.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { sel?.let(onPick) }, enabled = sel != null, modifier = Modifier.testTag("risi_calendar_pick")) { Text("Use this calendar", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
    )
}

private val DATE_IN = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME_IN = DateTimeFormatter.ofPattern("HH:mm")

/** [Edit]: title, date, time, duration and calendar; [Add] confirms the card with the corrected event. */
@Composable
internal fun CalendarEditDialog(
    title0: String, date0: LocalDate, time0: LocalTime, duration0: Int, allDay0: Boolean,
    calendarLabel: String?, onChooseCalendar: (() -> Unit)?, onSend: (kotlinx.serialization.json.JsonObject) -> Unit, onDismiss: () -> Unit,
    heading: String = "Edit event", confirmLabel: String = "Add",
) {
    var title by remember { mutableStateOf(title0) }
    var date by remember { mutableStateOf(DATE_IN.format(date0)) }
    var time by remember { mutableStateOf(TIME_IN.format(time0)) }
    var duration by remember { mutableStateOf(duration0) }
    var allDay by remember { mutableStateOf(allDay0) }
    val d = runCatching { LocalDate.parse(date.trim(), DATE_IN) }.getOrNull()
    val t = runCatching { LocalTime.parse(time.trim(), TIME_IN) }.getOrNull()
    val valid = title.isNotBlank() && title.length <= 200 && d != null && (allDay || t != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("risi_calendar_edit_form"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("risi_calendar_edit_title"))
                OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, singleLine = true, isError = d == null, modifier = Modifier.fillMaxWidth().testTag("risi_calendar_edit_date"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allDay, onCheckedChange = { allDay = it })
                    Text("All day", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (!allDay) {
                    OutlinedTextField(time, { time = it }, label = { Text("Start (HH:MM)", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, singleLine = true, isError = t == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("risi_calendar_edit_time"))
                    Text("Duration", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        listOf(15, 30, 45, 60, 90, 120).forEach { m ->
                            FilterChip(selected = duration == m, onClick = { duration = m }, label = { Text(if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "${m / 60} h ${m % 60} min") })
                        }
                    }
                }
                if (onChooseCalendar != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Calendar: ${calendarLabel ?: "not chosen"}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onChooseCalendar) { Text(if (calendarLabel == null) "Choose" else "Change") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSend(RisiCalendarCards.editArgs(title, d!!, t ?: LocalTime.MIDNIGHT, duration, allDay, ZoneId.systemDefault())) }, modifier = Modifier.testTag("risi_calendar_edit_send")) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
    )
}

/** An Allowed add (no card): [Open] on its `skill_done`, from this phone's record of that request. */
@Composable
internal fun CalendarOpenButton(r: RisiMeta, ctx: RisiCardContext) {
    val port = ctx.host.calendar ?: return
    val records by port.records.collectAsState()
    val rec = RisiCalendarCards.recordForRequest(r.requestId, records) ?: return
    val eid = rec.eventId ?: return
    if (rec.removed) return
    FilledTonalButton(onClick = { port.open(eid) }, modifier = Modifier.testTag("risi_calendar_open")) { Text("Open", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
}
