package lk.codegen.risime.ui.tabs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import lk.codegen.risime.data.calendar.CalendarResult
import lk.codegen.risime.data.calendar.DayTimeline
import lk.codegen.risime.data.calendar.EventCardView
import lk.codegen.risime.data.calendar.RisiCalendar
import lk.codegen.risime.data.calendar.RisiCalendarTimeline
import lk.codegen.risime.data.calendar.RisiCalendarViews
import lk.codegen.risime.data.calendar.RisiEventCards
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiToolCards
import lk.codegen.risime.net.RisiCalendarCard
import lk.codegen.risime.net.RisiEvent
import lk.codegen.risime.net.RisiEventStatus
import lk.codegen.risime.net.RisiKinds129
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * v1.29 §29.8–§29.10 the Risi Calendar cards: native cards (never text chips). The mini day timeline is
 * computed here from the phone's own Risi Calendar cache; nothing from a render is sent anywhere.
 */

private val NO_EVENTS = MutableStateFlow<List<RisiEvent>>(emptyList())

/** The red of a clash (the theme's error colour). */
@Composable
private fun clashColor() = MaterialTheme.colorScheme.error

fun risiCalendarHeader(r: RisiMeta): String? = when (r.kind) {
    RisiKinds129.EVENT_CARD -> if (r.mode == "official") "Risi · Meeting" else "Risi · Added to your Risi Calendar"
    RisiKinds129.CALENDAR_INVITE -> "Risi · Invitation"
    RisiKinds129.CALENDAR_SUGGESTION -> "Risi · New time suggested"
    RisiKinds129.CALENDAR_REMINDER -> "Risi · Reminder"
    else -> if (r.kind == "confirm" && r.tool == RisiKinds129.TOOL_RISI_CALENDAR_ADD) "Risi · Add to Risi Calendar" else null
}

/** Shared state of an event-like card: its view now and the cache. */
@Composable
private fun rememberCardView(card: RisiCalendarCard, ctx: RisiCardContext): Pair<EventCardView?, List<RisiEvent>> {
    val events by (ctx.host.risiCalendar?.events ?: NO_EVENTS).collectAsState()
    val cached = card.eventId?.let { id -> events.firstOrNull { it.eventId.equals(id, true) } }
    val updates = remember(ctx.messages) { RisiEventCards.updates(ctx.messages) }
    return RisiEventCards.view(card, updates, cached) to events
}

@Composable
private fun Line(label: String, value: String, tag: String? = null, onClick: (() -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).let { m -> tag?.let { m.testTag(it) } ?: m }) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

/** Title, when, with, from: the body every event card shares. */
@Composable
private fun EventBody(v: EventCardView, sourceConversationId: String?, ctx: RisiCardContext, zone: ZoneId) {
    Text(
        v.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        color = if (v.cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("risi_event_title"),
    )
    Text(RisiCalendarViews.whenLabel(v.start, v.end, v.allDay, zone), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("risi_event_when"))
    RisiCalendarViews.withLine(v.participants, ctx.host.me, ctx.nameOf)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("risi_event_with")) }
    sourceConversationId?.let { conv ->
        val name = ctx.host.conversationName(conv)
        if (name != null) Line("From:", name, "risi_event_from") { ctx.host.openChat(conv, null) }
    }
    if (v.cancelled) Text("Cancelled", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("risi_event_cancelled"))
}

/** §29.9 the mini day timeline (07:00–21:00, widened to the event): mine grey (proposed hatched), this one in the accent, clashes red. */
@Composable
fun MiniDayTimeline(t: DayTimeline, modifier: Modifier = Modifier) {
    val accent = OfficialAccent
    val grey = MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.surfaceVariant
    val red = clashColor()
    val span = (t.toMin - t.fromMin).coerceAtLeast(60).toFloat()
    Column(modifier.testTag("risi_event_timeline"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(Modifier.fillMaxWidth().height(18.dp).semantics { contentDescription = "Your day" + (t.clashText?.let { ": $it" } ?: "") }) {
            val w = size.width
            val h = size.height
            drawRect(track, Offset.Zero, Size(w, h))
            // Hour ticks.
            var m = ((t.fromMin + 59) / 60) * 60
            while (m < t.toMin) {
                val x = (m - t.fromMin) / span * w
                drawLine(grey.copy(alpha = 0.35f), Offset(x, h * 0.7f), Offset(x, h), 1f)
                m += 60
            }
            for (b in t.blocks) {
                val x0 = ((b.startMin - t.fromMin) / span * w).coerceIn(0f, w)
                val x1 = ((b.endMin - t.fromMin) / span * w).coerceIn(0f, w)
                if (x1 - x0 <= 0f) continue
                val c = when {
                    b.self -> accent
                    b.clash -> red
                    else -> grey
                }
                if (!b.self && b.proposed) {
                    drawRect(c.copy(alpha = 0.25f), Offset(x0, 0f), Size(x1 - x0, h))
                    clipRect(x0, 0f, x1, h) {
                        var x = x0 - h
                        while (x < x1) { drawLine(c, Offset(x, h), Offset(x + h, 0f), 2f); x += 6f }
                    }
                } else {
                    drawRect(if (b.self) c else c.copy(alpha = 0.8f), Offset(x0, if (b.self) 0f else h * 0.15f), Size(x1 - x0, if (b.self) h else h * 0.7f))
                    if (b.self && b.clash) drawRect(red, Offset(x0, h - 3f), Size(x1 - x0, 3f))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("%02d:%02d".format(t.fromMin / 60, t.fromMin % 60), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t.allDay) Text("All day", style = MaterialTheme.typography.labelSmall)
            Text("%02d:%02d".format((t.toMin / 60) % 24, t.toMin % 60).let { if (t.toMin >= 1440) "24:00" else it }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        t.clashText?.let { Text(it, color = clashColor(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("risi_event_clash")) }
    }
}

private fun resultNote(r: CalendarResult): String? = when (r) {
    is CalendarResult.Ok -> null
    is CalendarResult.Conflict -> if (r.current == null) RisiCalendar.errorText("not_found") else RisiCalendar.errorText("version_conflict")
    is CalendarResult.Failed -> RisiCalendar.errorText(r.code)
}

/** §29.9/§29.10 `event_card` (added | official) and `calendar_invite`. */
@Composable
internal fun RisiEventCard(row: MessageEntity, card: RisiCalendarCard, ctx: RisiCardContext) {
    val zone = remember { ZoneId.systemDefault() }
    val (view, cache) = rememberCardView(card, ctx)
    val v = view ?: run { Text(row.body); return }
    val port = ctx.host.risiCalendar
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var suggesting by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val invite = card.kind == RisiKinds129.CALENDAR_INVITE
    val added = card.kind == RisiKinds129.EVENT_CARD && card.mode != "official"
    val expired = invite && RisiEventCards.expired(card, ctx.nowMs)

    fun run(block: suspend () -> CalendarResult) {
        busy = true
        note = null
        scope.launch {
            val r = runCatching { block() }.getOrElse { CalendarResult.Failed("network") }
            note = resultNote(r)
            busy = false
        }
    }

    Column(Modifier.testTag(if (invite) "risi_calendar_invite" else "risi_event_card"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (invite && card.reason == "time_changed") Text("The time changed", style = MaterialTheme.typography.labelMedium, color = OfficialAccent)
        EventBody(v, card.sourceConversationId, ctx, zone)
        if (!v.cancelled && v.start != null) {
            val t = remember(v, cache) { RisiCalendarTimeline.compute(v.eventId, v.start!!, v.end ?: v.start!!, v.allDay, cache, zone) }
            t?.let { MiniDayTimeline(it) }
        }
        val mine = v.statusOf(ctx.host.me)
        val answer = if (ctx.readOnly || port == null || busy) emptyList() else RisiEventCards.answerButtons(card, v, ctx.host.me, ctx.nowMs)
        when {
            expired && mine == RisiEventStatus.PROPOSED -> Text("Expired", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_event_state"))
            mine != null && answer.isEmpty() && !v.cancelled -> Text(RisiCalendarViews.statusLabel(mine), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("risi_event_state"))
        }
        note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("risi_event_note")) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if ("accept" in answer) FilledTonalButton(onClick = { run { port!!.respond(v.eventId, "accept", v.version, v.start, v.end) } }, modifier = Modifier.testTag("risi_event_accept")) { Text("Accept") }
            if ("decline" in answer) OutlinedButton(onClick = { run { port!!.respond(v.eventId, "decline", v.version, v.start, v.end) } }, modifier = Modifier.testTag("risi_event_decline")) { Text("Decline") }
            if ("suggest" in answer) OutlinedButton(onClick = { suggesting = true }, modifier = Modifier.testTag("risi_event_suggest")) { Text("Suggest another time") }
            if (added && port != null && !ctx.readOnly) {
                val b = RisiEventCards.addedButtons(card, v, ctx.host.me)
                if ("open" in b) OutlinedButton(onClick = { port.open(v.eventId) }, modifier = Modifier.testTag("risi_event_open")) { Text("Open") }
                // [Edit] opens the event in the Calendar tab (title, time, reminder).
                if ("edit" in b) OutlinedButton(onClick = { port.open(v.eventId) }, enabled = !busy, modifier = Modifier.testTag("risi_event_edit")) { Text("Edit") }
                if ("delete" in b) OutlinedButton(onClick = { askDelete = true }, enabled = !busy, modifier = Modifier.testTag("risi_event_delete")) { Text("Delete") }
            } else if (!added && answer.isEmpty() && port != null && !ctx.readOnly) {
                // Official: everyone else sees the participants line and [Open in Calendar].
                OutlinedButton(onClick = { port.open(v.eventId) }, modifier = Modifier.testTag("risi_event_open")) { Text(if (invite) "Open" else "Open in Calendar") }
            }
        }
    }
    if (suggesting) {
        val (s0, dur) = RisiEventCards.suggestDefault(v.start, v.end, zone)
        SuggestTimeDialog(s0.toLocalDate(), s0.toLocalTime(), dur, v.allDay, zone, onPick = { start, end, allDay ->
            suggesting = false
            run { port!!.respond(v.eventId, "suggest", v.version, v.start, v.end, Triple(start, end, allDay)) }
        }, onDismiss = { suggesting = false })
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete event?") },
            text = { Text("It is cancelled for everyone invited.") },
            confirmButton = { TextButton(onClick = { askDelete = false; run { port!!.delete(v.eventId) } }, modifier = Modifier.testTag("risi_event_delete_confirm")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
    }
}

/** §29.10 `calendar_suggestion` to the owner: [Use] moves the event, [Keep] keeps the original time. */
@Composable
internal fun RisiCalendarSuggestionCard(row: MessageEntity, card: RisiCalendarCard, ctx: RisiCardContext) {
    val zone = remember { ZoneId.systemDefault() }
    val events by (ctx.host.risiCalendar?.events ?: NO_EVENTS).collectAsState()
    val cached = card.eventId?.let { id -> events.firstOrNull { it.eventId.equals(id, true) } }
    val port = ctx.host.risiCalendar
    val scope = rememberCoroutineScope()
    var answered by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val buttons = if (ctx.readOnly || port == null) emptyList() else RisiEventCards.suggestionButtons(card, ctx.host.me, setOfNotNull(answered?.let { card.suggestionId?.lowercase() }), cached)
    Column(Modifier.testTag("risi_calendar_suggestion"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        val who = card.by?.let(ctx.nameOf) ?: "Someone"
        val title = cached?.title ?: card.title
        Text("$who suggests ${RisiCalendarViews.whenLabel(card.start, card.end, card.allDay, zone)}" + (title?.let { " for '$it'" } ?: ""), style = MaterialTheme.typography.bodyMedium)
        if (card.start != null) {
            RisiCalendarTimeline.compute(card.eventId, card.start!!, card.end ?: card.start!!, card.allDay, events, zone)?.let { MiniDayTimeline(it) }
        }
        answered?.let { Text(if (it == "use") "Moved to the suggested time" else "Kept the original time", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("risi_suggestion_state")) }
        note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            val sid = card.suggestionId
            if (sid != null) for (b in buttons) {
                val label = if (b == "use") "Use this time" else "Keep original"
                val click = {
                    scope.launch {
                        val r = port!!.resolve(sid, b)
                        note = resultNote(r)
                        if (r is CalendarResult.Ok) answered = b
                    }
                    Unit
                }
                if (b == "use") FilledTonalButton(onClick = click, modifier = Modifier.testTag("risi_suggestion_use")) { Text(label) }
                else OutlinedButton(onClick = click, modifier = Modifier.testTag("risi_suggestion_keep")) { Text(label) }
            }
        }
    }
}

/** §29.10 `calendar_reminder`: "In 30 min: Interview (14:00)" with [Open]. */
@Composable
internal fun RisiCalendarReminderCard(card: RisiCalendarCard, ctx: RisiCardContext) {
    val zone = remember { ZoneId.systemDefault() }
    Column(Modifier.testTag("risi_calendar_reminder"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(RisiEventCards.reminderText(card, zone), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val port = ctx.host.risiCalendar
        val id = card.eventId
        if (port != null && id != null && "open" in card.buttons.ifEmpty { listOf("open") }) {
            OutlinedButton(onClick = { port.open(id) }, modifier = Modifier.testTag("risi_event_open")) { Text("Open") }
        }
    }
}

/**
 * §29.8 the action card: `confirm` with `tool: "risi_calendar_add"` → [Add] [Edit] [Cancel]. [Add] is
 * `confirm_write` (the server creates the event at once: no device permission, no model call); [Edit]
 * is `confirm_write` with the corrected title/start/end/all_day; [Cancel] is `cancel_write`.
 */
@Composable
internal fun RisiCalendarAddCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val zone = remember { ZoneId.systemDefault() }
    val a = r.args
    fun str(k: String) = (a?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    val w = r.confirmWhen()
    val title = str("title") ?: r.text ?: ""
    val start = str("start") ?: w?.start
    val end = str("end") ?: w?.end
    val allDay = (a?.get("all_day") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: w?.allDay ?: false
    val with = (a?.get("with") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
    val reminder = (a?.get("reminder_min") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
    val events by (ctx.host.risiCalendar?.events ?: NO_EVENTS).collectAsState()
    val state = RisiToolCards.confirmState(r, ctx.messages, ctx.nowMs)
    val wid = r.writeId
    val base = if (ctx.readOnly || wid == null) emptyList() else RisiToolCards.confirmButtons(ctx.host.me, r, state, ctx.awaiting)
    val buttons = base + if ("add" in base && "edit" in r.buttons) listOf("edit") else emptyList()
    var editing by remember { mutableStateOf(false) }
    val dim = if (state == RisiToolCards.ConfirmState.OPEN || state == RisiToolCards.ConfirmState.CONFIRMED) 1f else 0.55f
    Column(Modifier.testTag("risi_calendar_add_card"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim), modifier = Modifier.testTag("risi_event_title"))
        Text(RisiCalendarViews.whenLabel(start, end, allDay, zone), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("risi_event_when"))
        RisiCalendarViews.withLine(with.map { lk.codegen.risime.net.CardParticipant(it) }, ctx.host.me, ctx.nameOf)?.let {
            Text(it.replace(" ?", ""), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("risi_event_with"))
        }
        Text("Risi Calendar · " + RisiCalendarViews.reminderLabel(reminder), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state == RisiToolCards.ConfirmState.OPEN && start != null) {
            RisiCalendarTimeline.compute(null, start, end ?: start, allDay, events, zone)?.let { MiniDayTimeline(it) }
        }
        when (state) {
            RisiToolCards.ConfirmState.CANCELLED -> Text("Cancelled", modifier = Modifier.testTag("risi_confirm_state"))
            RisiToolCards.ConfirmState.EXPIRED -> Text("Expired", modifier = Modifier.testTag("risi_confirm_state"))
            RisiToolCards.ConfirmState.CONFIRMED -> Text("Adding to your Risi Calendar…", modifier = Modifier.testTag("risi_confirm_state"))
            RisiToolCards.ConfirmState.OPEN -> if (wid != null && wid.lowercase() in ctx.awaiting) Text("Sending…")
        }
        if (buttons.isNotEmpty() && wid != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if ("add" in buttons) FilledTonalButton(onClick = { ctx.host.act(wid, "confirm_write") }, modifier = Modifier.testTag("risi_confirm_add")) { Text("Add") }
                if ("edit" in buttons) OutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("risi_calendar_edit")) { Text("Edit") }
                if ("cancel" in buttons) OutlinedButton(onClick = { ctx.host.act(wid, "cancel_write") }, modifier = Modifier.testTag("risi_confirm_cancel")) { Text("Cancel") }
            }
        }
    }
    if (editing && wid != null) {
        val s = start?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.ofEpochMilli(ctx.nowMs)
        val e = end?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: s.plusSeconds(3600)
        CalendarEditDialog(
            title, s.atZone(zone).toLocalDate(), s.atZone(zone).toLocalTime(), ((e.epochSecond - s.epochSecond) / 60).toInt().coerceAtLeast(15), allDay,
            calendarLabel = null, onChooseCalendar = null,
            onSend = { edit -> editing = false; ctx.host.confirmEdited(wid, edit) },
            onDismiss = { editing = false },
        )
    }
}

/** The `event_update` row: no bubble when a card of that event is in the chat, else a small line. */
@Composable
internal fun RisiEventUpdateLine(row: MessageEntity, card: RisiCalendarCard, ctx: RisiCardContext) {
    if (RisiEventCards.updateHasCard(card, ctx.messages)) return
    lk.codegen.risime.ui.common.SystemLineText(RisiEventCards.updateLine(card, ctx.host.me, ctx.nameOf))
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val WIRE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).withZone(ZoneOffset.UTC)

/** [Suggest another time]: a date and time picker (prefilled +1 h), the same length as the event. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SuggestTimeDialog(date0: LocalDate, time0: LocalTime, durationMin: Int, allDay0: Boolean, zone: ZoneId, onPick: (String, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(date0) }
    var time by remember { mutableStateOf(time0) }
    var allDay by remember { mutableStateOf(allDay0) }
    var duration by remember { mutableStateOf(durationMin.coerceIn(5, 1440)) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Suggest another time") },
        text = {
            Column(Modifier.testTag("risi_suggest_form"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.testTag("risi_suggest_day")) { Text(DAY.format(date)) }
                    if (!allDay) OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.testTag("risi_suggest_time")) { Text(HM.format(time)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allDay, onCheckedChange = { allDay = it })
                    Text("All day")
                }
                if (!allDay) FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf(30, 60, 90, 120).forEach { m -> FilterChip(selected = duration == m, onClick = { duration = m }, label = { Text(if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "1 h 30 min") }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = if (allDay) date.atStartOfDay(zone) else date.atTime(time).atZone(zone)
                val e = if (allDay) date.plusDays(1).atStartOfDay(zone) else s.plusMinutes(duration.toLong())
                onPick(WIRE.format(s.toInstant()), WIRE.format(e.toInstant()), allDay)
            }, modifier = Modifier.testTag("risi_suggest_send")) { Text("Suggest") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; pickDate = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(st) }
    }
    if (pickTime) {
        val st = rememberTimePickerState(time.hour, time.minute, true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(st.hour, st.minute); pickTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(st) },
        )
    }
}

/** The calendar kinds inside [RisiCardRow]'s card frame. */
@Composable
internal fun RisiCalendarCardBody(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val card = remember(row.systemJson) { RisiCalendarCard.parse(row.systemJson) } ?: run { Text(row.body); return }
    when (card.kind) {
        RisiKinds129.EVENT_CARD, RisiKinds129.CALENDAR_INVITE -> RisiEventCard(row, card, ctx)
        RisiKinds129.CALENDAR_SUGGESTION -> RisiCalendarSuggestionCard(row, card, ctx)
        RisiKinds129.CALENDAR_REMINDER -> RisiCalendarReminderCard(card, ctx)
        else -> Text(row.body)
    }
}
