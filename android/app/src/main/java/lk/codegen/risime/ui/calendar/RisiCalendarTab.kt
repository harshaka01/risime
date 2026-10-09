package lk.codegen.risime.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lk.codegen.risime.data.calendar.CalendarNav
import lk.codegen.risime.data.calendar.DayItem
import lk.codegen.risime.data.calendar.RisiCalendarViews
import lk.codegen.risime.net.RisiCalendarSettings
import lk.codegen.risime.net.RisiEvent
import lk.codegen.risime.net.RisiEventStatus
import lk.codegen.risime.ui.tabs.CalendarEditDialog
import lk.codegen.risime.ui.tabs.OfficialAccent
import lk.codegen.risime.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** §29.0 the info sheet, said once: where the calendar lives and that it is not end-to-end encrypted. */
const val RISI_CALENDAR_INFO =
    "Your Risi Calendar is stored on the RisiMe server, encrypted at rest. Risi can read it to answer you. It is not end-to-end encrypted."

/**
 * §29 the Calendar tab: Agenda (default), Day, Week, Month over the local cache. Each event shows title,
 * time, participants, source chat (tap opens it) and status; tap → detail (edit for the owner, delete,
 * my reminder). Nothing here needs a device calendar permission.
 */
@Composable
fun RisiCalendarScreen(
    events: List<RisiEvent>,
    me: String?,
    nameOf: (String) -> String,
    conversationName: (String) -> String?,
    settings: RisiCalendarSettings,
    showDeclined: Boolean,
    actions: RisiCalendarActions,
    focusEventId: String? = null,
    onFocusTaken: () -> Unit = {},
    notice: String? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
) {
    var modeName by rememberSaveable { mutableStateOf(RisiCalendarViews.Mode.AGENDA.name) }
    var anchorDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val nav = CalendarNav(RisiCalendarViews.Mode.valueOf(modeName), LocalDate.ofEpochDay(anchorDay))
    fun go(n: CalendarNav) { modeName = n.mode.name; anchorDay = n.anchor.toEpochDay() }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { actions.opened() }
    LaunchedEffect(focusEventId) {
        if (focusEventId != null) { detailId = focusEventId; onFocusTaken() }
    }
    // A Day/Week/Month outside the listed window is listed on demand.
    LaunchedEffect(modeName, anchorDay) { if (nav.mode != RisiCalendarViews.Mode.AGENDA) nav.range(zone).let { (a, b) -> actions.loadRange(a, b) } }
    val shown = RisiCalendarViews.visible(events, showDeclined)

    Box(Modifier.fillMaxSize().testTag("calendar_tab")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    for (m in RisiCalendarViews.Mode.entries) {
                        FilterChip(
                            selected = nav.mode == m,
                            onClick = { go(CalendarNav(m, if (m == RisiCalendarViews.Mode.AGENDA) today else nav.anchor)) },
                            label = { Text(modeLabel(m)) },
                            modifier = Modifier.testTag("calendar_mode_${m.name.lowercase()}"),
                        )
                    }
                }
                IconButton(onClick = { info = true }, modifier = Modifier.testTag("calendar_info")) { Icon(Icons.Default.Info, "About Risi Calendar") }
                IconButton(onClick = { settingsOpen = true }, modifier = Modifier.testTag("calendar_settings")) { Icon(Icons.Default.Settings, "Calendar settings") }
            }
            if (nav.mode != RisiCalendarViews.Mode.AGENDA) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { go(nav.prev()) }, modifier = Modifier.testTag("calendar_prev")) { Text("‹") }
                    Text(nav.title(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag("calendar_title"))
                    TextButton(onClick = { go(nav.next()) }, modifier = Modifier.testTag("calendar_next")) { Text("›") }
                    TextButton(onClick = { go(nav.copy(anchor = today)) }) { Text("Today") }
                }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Spacing.md).testTag("calendar_notice")) }
            val open = { e: RisiEvent -> detailId = e.eventId }
            when (nav.mode) {
                RisiCalendarViews.Mode.AGENDA -> AgendaView(shown, today, zone, me, nameOf, conversationName, actions, open)
                RisiCalendarViews.Mode.DAY -> DayView(RisiCalendarViews.day(shown, nav.anchor, zone), zone, me, nameOf, conversationName, actions, open)
                RisiCalendarViews.Mode.WEEK -> WeekView(shown, nav.anchor, zone, me, nameOf, conversationName, actions, open, today)
                RisiCalendarViews.Mode.MONTH -> MonthView(shown, YearMonth.from(nav.anchor), zone, today) { d -> go(CalendarNav(RisiCalendarViews.Mode.DAY, d)) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("New event") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.md).testTag("calendar_new"),
        )
    }

    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            title = { Text("Risi Calendar") },
            text = { Text(RISI_CALENDAR_INFO, modifier = Modifier.testTag("calendar_info_text")) },
            confirmButton = { TextButton(onClick = { info = false }) { Text("OK") } },
        )
    }
    if (settingsOpen) CalendarSettingsDialog(settings, showDeclined, actions) { settingsOpen = false }
    if (creating) {
        val start = (if (nav.mode == RisiCalendarViews.Mode.AGENDA) today else nav.anchor)
        CalendarEditDialog(
            "", start, LocalTime.now(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1), settings.defaultDurationMin.coerceIn(15, 120), false,
            calendarLabel = null, onChooseCalendar = null,
            onSend = { edit ->
                creating = false
                val s = RisiCalendarEdits.startMs(edit)
                val e = RisiCalendarEdits.endMs(edit)
                val t = RisiCalendarEdits.title(edit)
                if (s != null && e != null && t != null) actions.create(t, s, e, RisiCalendarEdits.allDay(edit))
            },
            onDismiss = { creating = false },
            heading = "New event", confirmLabel = "Save",
        )
    }
    detailId?.let { id ->
        val e = events.firstOrNull { it.eventId.equals(id, true) }
        if (e == null) detailId = null
        else EventDetailDialog(e, me, nameOf, conversationName, zone, actions) { detailId = null }
    }
}

private fun modeLabel(m: RisiCalendarViews.Mode) = when (m) {
    RisiCalendarViews.Mode.AGENDA -> "Agenda"
    RisiCalendarViews.Mode.DAY -> "Day"
    RisiCalendarViews.Mode.WEEK -> "Week"
    RisiCalendarViews.Mode.MONTH -> "Month"
}

@Composable
private fun AgendaView(
    events: List<RisiEvent>, today: LocalDate, zone: ZoneId, me: String?, nameOf: (String) -> String,
    conversationName: (String) -> String?, actions: RisiCalendarActions, open: (RisiEvent) -> Unit,
) {
    val days = RisiCalendarViews.agenda(events, today, zone)
    if (days.isEmpty()) {
        Text("Nothing in your Risi Calendar", modifier = Modifier.padding(Spacing.lg).testTag("calendar_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("calendar_agenda")) {
        for (d in days) {
            item(key = "h${d.date}") { DayHeader(d.date, today) }
            items(d.items, key = { "${d.date}:${it.event.eventId}" }) { EventRow(it.event, zone, me, nameOf, conversationName, actions, open) }
        }
        item { Box(Modifier.height(88.dp)) }
    }
}

@Composable
private fun DayHeader(d: LocalDate, today: LocalDate) {
    val label = when (d) {
        today -> "Today · " + RisiCalendarViews.dateLabel(d)
        today.plusDays(1) -> "Tomorrow · " + RisiCalendarViews.dateLabel(d)
        else -> RisiCalendarViews.dateLabel(d)
    }
    Text(label, style = MaterialTheme.typography.labelLarge, color = OfficialAccent, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.md, top = Spacing.md, bottom = Spacing.xs))
}

@Composable
private fun DayView(
    items: List<DayItem>, zone: ZoneId, me: String?, nameOf: (String) -> String,
    conversationName: (String) -> String?, actions: RisiCalendarActions, open: (RisiEvent) -> Unit,
) {
    if (items.isEmpty()) {
        Text("Nothing on this day", modifier = Modifier.padding(Spacing.lg).testTag("calendar_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("calendar_day")) {
        items(items, key = { it.event.eventId }) { EventRow(it.event, zone, me, nameOf, conversationName, actions, open) }
        item { Box(Modifier.height(88.dp)) }
    }
}

@Composable
private fun WeekView(
    events: List<RisiEvent>, anchor: LocalDate, zone: ZoneId, me: String?, nameOf: (String) -> String,
    conversationName: (String) -> String?, actions: RisiCalendarActions, open: (RisiEvent) -> Unit, today: LocalDate,
) {
    val week = RisiCalendarViews.week(events, anchor, zone)
    LazyColumn(Modifier.fillMaxSize().testTag("calendar_week")) {
        for (d in week) {
            item(key = "h${d.date}") { DayHeader(d.date, today) }
            if (d.items.isEmpty()) item(key = "e${d.date}") { Text("—", modifier = Modifier.padding(start = Spacing.md), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(d.items, key = { "${d.date}:${it.event.eventId}" }) { EventRow(it.event, zone, me, nameOf, conversationName, actions, open) }
        }
        item { Box(Modifier.height(88.dp)) }
    }
}

@Composable
private fun MonthView(events: List<RisiEvent>, month: YearMonth, zone: ZoneId, today: LocalDate, onDay: (LocalDate) -> Unit) {
    val grid = RisiCalendarViews.month(events, month, zone, today)
    Column(Modifier.fillMaxWidth().padding(Spacing.sm).testTag("calendar_month")) {
        Row(Modifier.fillMaxWidth()) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (week in grid) {
            Row(Modifier.fillMaxWidth()) {
                for (cell in week) {
                    val d = cell.date
                    Column(
                        Modifier.weight(1f).height(52.dp).padding(2.dp)
                            .let { if (cell.today) it.background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small) else it }
                            .let { m -> if (d != null) m.clickable { onDay(d) }.testTag("calendar_cell_$d") else m },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (d != null) {
                            Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium)
                            if (cell.count > 0) {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    repeat(minOf(cell.count, 3)) { Box(Modifier.size(5.dp).background(OfficialAccent, MaterialTheme.shapes.extraSmall)) }
                                }
                                if (cell.count > 3) Text("+${cell.count - 3}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Title, time, status, participants, source chat (tap opens it). Proposed: dashed outline and "Proposed". */
@Composable
private fun EventRow(
    e: RisiEvent, zone: ZoneId, me: String?, nameOf: (String) -> String, conversationName: (String) -> String?,
    actions: RisiCalendarActions, open: (RisiEvent) -> Unit,
) {
    val proposed = e.myStatus == RisiEventStatus.PROPOSED && !e.cancelled
    val dim = e.cancelled || e.myStatus == RisiEventStatus.DECLINED
    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (proposed) OfficialAccent.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = if (proposed) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 3.dp).clickable { open(e) }.testTag("calendar_event_${e.eventId}"),
    ) {
        Row(Modifier.padding(Spacing.sm), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(4.dp).height(40.dp).background(if (proposed) OfficialAccent.copy(alpha = 0.35f) else OfficialAccent, MaterialTheme.shapes.extraSmall))
            Column(Modifier.padding(start = Spacing.sm).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(e.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text(RisiCalendarViews.timeLabel(e, zone), style = MaterialTheme.typography.bodySmall)
                RisiCalendarViews.withLine(RisiCalendarViews.participantsOf(e), me, nameOf)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                e.source?.conversationId?.let { conv ->
                    conversationName(conv)?.let { n ->
                        Text("From: $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { actions.openChat(conv) }.testTag("calendar_event_from"))
                    }
                }
            }
            Text(
                RisiCalendarViews.eventStatus(e), style = MaterialTheme.typography.labelMedium,
                color = when {
                    e.cancelled || e.myStatus == RisiEventStatus.DECLINED -> MaterialTheme.colorScheme.error
                    proposed -> OfficialAccent
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag("calendar_event_status"),
            )
        }
    }
}

/** The event: time, participants, source, my status and reminder; owner: Edit; everyone: Delete. */
@Composable
private fun EventDetailDialog(
    e: RisiEvent, me: String?, nameOf: (String) -> String, conversationName: (String) -> String?, zone: ZoneId,
    actions: RisiCalendarActions, onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val owner = e.isOwner(me)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.title, modifier = Modifier.testTag("calendar_detail_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("calendar_detail"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(RisiCalendarViews.whenLabel(e.start, e.end, e.allDay, zone))
                Text("Status: " + RisiCalendarViews.eventStatus(e), modifier = Modifier.testTag("calendar_detail_status"))
                if (e.participants.size > 1) {
                    Text("People", style = MaterialTheme.typography.labelMedium)
                    for (p in e.participants) {
                        val who = if (p.userId.equals(me, true)) "You" else nameOf(p.userId)
                        Text("$who · ${RisiCalendarViews.statusLabel(p.status)}" + if (p.userId.equals(e.owner, true)) " · organiser" else "", style = MaterialTheme.typography.bodySmall)
                    }
                }
                e.source?.conversationId?.let { conv ->
                    conversationName(conv)?.let { n ->
                        TextButton(onClick = { onDismiss(); actions.openChat(conv) }, modifier = Modifier.testTag("calendar_detail_from")) { Text("From: $n") }
                    }
                }
                e.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
                if (!e.cancelled) {
                    Text("My reminder", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        RisiCalendarViews.REMINDER_CHOICES.forEach { m ->
                            FilterChip(selected = e.myReminderMin == m, onClick = { actions.setReminder(e, m) }, label = { Text(RisiCalendarViews.reminderLabel(m)) },
                                modifier = Modifier.testTag("calendar_reminder_${m ?: "none"}"))
                        }
                    }
                    run {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            if (e.myStatus != RisiEventStatus.ACCEPTED) FilledTonalButton(onClick = { actions.respond(e, "accept") }, modifier = Modifier.testTag("calendar_detail_accept")) { Text("Accept") }
                            if (e.myStatus != RisiEventStatus.DECLINED) OutlinedButton(onClick = { actions.respond(e, "decline") }, modifier = Modifier.testTag("calendar_detail_decline")) { Text("Decline") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (owner && !e.cancelled) TextButton(onClick = { editing = true }, modifier = Modifier.testTag("calendar_detail_edit")) { Text("Edit") }
                if (!e.cancelled) TextButton(onClick = { askDelete = true }, modifier = Modifier.testTag("calendar_detail_delete")) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
    if (editing) {
        val s = Instant.parse(e.start).atZone(zone)
        val end = Instant.parse(e.end).atZone(zone)
        CalendarEditDialog(
            e.title, s.toLocalDate(), s.toLocalTime(), ((end.toEpochSecond() - s.toEpochSecond()) / 60).toInt().coerceAtLeast(15), e.allDay,
            calendarLabel = null, onChooseCalendar = null,
            onSend = { edit -> editing = false; actions.edit(e, edit) },
            onDismiss = { editing = false },
            heading = "Edit event", confirmLabel = "Save",
        )
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete event?") },
            text = { Text(if (owner) "It is cancelled for everyone invited." else "It is declined and removed from your calendar.") },
            confirmButton = { TextButton(onClick = { askDelete = false; onDismiss(); actions.delete(e) }, modifier = Modifier.testTag("calendar_delete_confirm")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Settings: the default reminder (30 min), "Show declined" (this phone), events in the 09:00 digest. */
@Composable
private fun CalendarSettingsDialog(settings: RisiCalendarSettings, showDeclined: Boolean, actions: RisiCalendarActions, onDismiss: () -> Unit) {
    var reminder by remember { mutableStateOf(settings.defaultReminderMin) }
    var digest by remember { mutableStateOf(settings.digestEvents) }
    var declined by remember { mutableStateOf(showDeclined) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calendar settings") },
        text = {
            Column(Modifier.testTag("calendar_settings_form"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Default reminder", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    RisiCalendarViews.REMINDER_CHOICES.forEach { m ->
                        FilterChip(selected = reminder == m, onClick = { reminder = m }, label = { Text(RisiCalendarViews.reminderLabel(m)) },
                            modifier = Modifier.testTag("calendar_default_reminder_${m ?: "none"}"))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show declined", Modifier.weight(1f))
                    Switch(declined, { declined = it }, modifier = Modifier.testTag("calendar_show_declined"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Events in my morning digest", Modifier.weight(1f))
                    Switch(digest, { digest = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (declined != showDeclined) actions.setShowDeclined(declined)
                if (reminder != settings.defaultReminderMin || digest != settings.digestEvents) actions.saveSettings(settings.copy(defaultReminderMin = reminder, digestEvents = digest))
                onDismiss()
            }, modifier = Modifier.testTag("calendar_settings_save")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The tab with the app's view model. */
@Composable
fun RisiCalendarTab(vm: RisiCalendarViewModel) {
    val events by vm.events.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    val convs by vm.conversations.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val declined by vm.showDeclined.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    RisiCalendarScreen(
        events, vm.meId, { id -> names[id.lowercase()] ?: "Someone" }, { id -> convs[id.lowercase()] }, settings, declined, vm,
        focusEventId = focus, onFocusTaken = { vm.takeFocus() }, notice = notice,
    )
}
