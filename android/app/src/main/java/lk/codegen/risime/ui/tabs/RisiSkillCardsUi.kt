package lk.codegen.risime.ui.tabs

import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiProgressStore
import lk.codegen.risime.data.tabs.RisiSkillCards
import lk.codegen.risime.data.tabs.RisiStepLabels
import lk.codegen.risime.data.tabs.RisiToolCards
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.ui.theme.Spacing

/*
 * §25.4/§26.5 the v1.25 and v1.26 cards: confirm (exact args, buttons only for `for`, 24 h expiry),
 * reminder_set (Me too / Not me), draft ([Use] fills the composer, never sends), skill_done ([Undo] or
 * the manual hint), skill_needed ("Open Risi skills"), answer v2 steps and next-step chips, and the
 * progress bubble.
 */

@Composable
internal fun ConfirmCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val state = RisiToolCards.confirmState(r, ctx.messages, ctx.nowMs)
    val dim = if (state == RisiToolCards.ConfirmState.OPEN || state == RisiToolCards.ConfirmState.CONFIRMED) 1f else 0.55f
    Column(Modifier.testTag("risi_confirm_card"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(r.summary ?: row.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim), modifier = Modifier.testTag("risi_confirm_summary"))
        val lines = RisiSkillCards.argLines(r, { ctx.host.conversationName(it) })
        if (lines.isNotEmpty()) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().testTag("risi_confirm_args")) {
                Column(Modifier.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    lines.forEach { (k, v) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        if (r.tool == lk.codegen.risime.net.RisiToolCall.TOOL_SCHEDULE_MESSAGE && state == RisiToolCards.ConfirmState.OPEN && ctx.host.scheduleMayBeLate()) {
            Text("May be a few minutes late on this phone (exact alarms are off).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_confirm_late"))
        }
        val stateText = if (state == RisiToolCards.ConfirmState.CONFIRMED) "Confirmed" else RisiToolCards.closedText(state)
        stateText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_confirm_state")) }
        val wid = r.writeId
        val buttons = if (ctx.readOnly || wid == null) emptyList() else RisiToolCards.confirmButtons(ctx.host.me, r, state, ctx.awaiting)
        if (buttons.isNotEmpty() && wid != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if ("add" in buttons) FilledTonalButton(onClick = { ctx.host.act(wid, "confirm_write") }, modifier = Modifier.testTag("risi_confirm_add")) { Text("Add", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                if ("allow" in buttons) FilledTonalButton(onClick = { ctx.host.act(wid, "confirm_write") }, modifier = Modifier.testTag("risi_confirm_allow")) { Text("Allow", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                // v1.34 §33.15 an export_pdf card: [Send] confirms; the phone makes and sends the PDF when Risi asks.
                if ("send" in buttons) FilledTonalButton(onClick = { ctx.host.act(wid, "confirm_write") }, modifier = Modifier.testTag("risi_confirm_send")) { Text("Send", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                if ("cancel" in buttons) OutlinedButton(onClick = { ctx.host.act(wid, "cancel_write") }, modifier = Modifier.testTag("risi_confirm_cancel")) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        } else if (state == RisiToolCards.ConfirmState.OPEN && wid != null && wid.lowercase() in ctx.awaiting) {
            Text("Sending…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ReminderSetCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val participants = RisiToolCards.reminderParticipants(r, ctx.messages, emptySet())
    Column(Modifier.testTag("risi_reminder_set"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(r.text ?: row.body, style = MaterialTheme.typography.bodyLarge)
        formatDue(r.reminderWhen())?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (participants.isNotEmpty()) {
            Text("Reminding: " + participants.joinToString(", ") { ctx.nameOf(it) }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("risi_reminder_participants"))
        }
        val rid = r.reminderId
        val buttons = if (ctx.readOnly || rid == null || rid.lowercase() in ctx.awaiting) emptyList() else RisiToolCards.reminderButtons(ctx.host.me, r, participants, ctx.nowMs)
        if (rid != null && buttons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if ("me_too" in buttons) FilledTonalButton(onClick = { ctx.host.act(rid, "me_too") }, modifier = Modifier.testTag("risi_me_too")) { Text("Me too", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                if ("not_me" in buttons) OutlinedButton(onClick = { ctx.host.act(rid, "not_me") }, modifier = Modifier.testTag("risi_not_me")) { Text("Not me", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
internal fun DraftCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val usable = RisiToolCards.draftUsable(r) { ctx.host.hasConversation(it) }
    Column(Modifier.testTag("risi_draft"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        r.targetConversationId?.let { t -> ctx.host.conversationName(t)?.let { Text("Draft for $it", style = MaterialTheme.typography.labelMedium) } }
        Text(r.text ?: row.body, style = MaterialTheme.typography.bodyLarge)
        if (usable) {
            val target = r.targetConversationId!!
            FilledTonalButton(onClick = { ctx.host.useDraft(target, r.text!!) }, modifier = Modifier.testTag("risi_draft_use")) { Text("Use", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
    }
}

@Composable
internal fun SkillDoneCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val context = LocalContext.current
    Column(Modifier.testTag("risi_skill_done"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        lk.codegen.risime.ui.common.RisiMarkdownText(row.body.ifBlank { r.summary.orEmpty() }, style = MaterialTheme.typography.bodyLarge)
        // P0: the success line ("Added to your Google Calendar: …") with [Open] and [Undo]; the action card above shows "Added".
        // v1.32 §25.3: the success post's `added_event` → the card from this phone's own provider row.
        if (r.addedEvent != null) AddedEventCard(r.addedEvent!!.eventId, ctx)
        else if (r.skillId == lk.codegen.risime.net.RisiSkillIds.CALENDAR) CalendarOpenButton(r, ctx)
        val entry = r.entryId
        val skill = r.skillId
        val token = r.undoToken
        if (!ctx.readOnly && entry != null && skill != null && token != null && RisiSkillCards.canUndo(r, ctx.nowMs, ctx.host.undone)) {
            OutlinedButton(onClick = { ctx.host.undo(skill, entry, token) }, modifier = Modifier.testTag("risi_undo")) { Text("Undo", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        } else if (entry != null && entry.lowercase() in ctx.host.undone) {
            Text("Undo requested", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_undo_state"))
        }
        RisiSkillCards.manualHint(r)?.let { hint ->
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_undo_hint"))
            if (RisiSkillCards.opensClock(r)) {
                OutlinedButton(
                    onClick = { runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    modifier = Modifier.testTag("risi_open_clock"),
                ) { Text("Open Clock", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
internal fun SkillNeededCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    Column(Modifier.testTag("risi_skill_needed"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(RisiSkillCards.neededText(r, row.body), style = MaterialTheme.typography.bodyLarge)
        if ("open_skills" in r.buttons || r.buttons.isEmpty()) {
            // A skill never turned on: "Turn on Calendar" (opens Settings → Risi skills at it); after a revoke or
            // for a permission: "Open Risi skills".
            val label = if (r.reason == "off" && r.wasOn != true && r.skillId != null) "Turn on ${RisiSkillCards.skillTitle(r.skillId)}" else "Open Risi skills"
            Button(onClick = { ctx.host.openSkills(r.skillId) }, modifier = Modifier.testTag("risi_open_skills")) { Text(label) }
        }
    }
}

/** v1.31 §31.8 `google_reconnect`: the body, and [Reconnect] (opens Settings → Risi skills → Calendar, where the section offers it). */
@Composable
internal fun GoogleReconnectCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    Column(Modifier.testTag("risi_google_reconnect"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(row.body, style = MaterialTheme.typography.bodyLarge)
        if ("reconnect" in r.buttons && !ctx.readOnly) {
            Button(onClick = { ctx.host.openGoogleCalendarSettings() }, modifier = Modifier.testTag("risi_google_reconnect_button")) { Text("Reconnect", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
    }
}

/** §25.4 answer v2: the steps under the answer and up to 3 next-step chips (they pre-fill the composer). */
@Composable
internal fun AnswerExtras(r: RisiMeta, ctx: RisiCardContext) {
    // A calendar_add step next to its action card is the proposal, not the add: the card says what happened.
    val hasCard = r.steps.any { it.tool == lk.codegen.risime.net.RisiToolCall.TOOL_CALENDAR_ADD } &&
        lk.codegen.risime.data.tabs.RisiCalendarCards.hasActionCard(r, ctx.messages)
    val steps = RisiSkillCards.stepLines(if (hasCard) r.copy(steps = r.steps.filter { it.tool != lk.codegen.risime.net.RisiToolCall.TOOL_CALENDAR_ADD }) else r)
    if (steps.isNotEmpty()) {
        Column(Modifier.testTag("risi_steps")) {
            steps.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    r.addedEvent?.let { AddedEventCard(it.eventId, ctx) }
    if (lk.codegen.risime.data.tabs.LocalEvents.shows(r, ctx.host.me, ctx.risiChat)) YourEvents(r, ctx)
    // v1.35 §34.5: an answer with `clarify` shows its own card (RisiClarifyCard), with the remaining chips.
    if (r.clarify == null) NextActionChips(r, ctx)
}

/**
 * v1.32 §25.4 typed chips (answers; v1.35 §34.3 error cards too). `ask` RUNS the request (a `risi_request` ask, as
 * if typed and sent in the Risi chat); `open` is a deep link. Never a paste into the composer, never a plain message.
 */
@Composable
internal fun NextActionChips(r: RisiMeta, ctx: RisiCardContext) {
    val send = ctx.sendChip
    val chips = RisiChips.chips(r, ctx.host.me, ctx.readOnly, send != null)
    if (chips.isNotEmpty() && send != null) {
        val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { g ->
            if (g[android.Manifest.permission.READ_CALENDAR] != true) ctx.host.openTarget(lk.codegen.risime.net.RisiNextAction.SETTINGS_CALENDAR, null)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            chips.forEachIndexed { i, c ->
                AssistChip(
                    onClick = {
                        RisiChips.run(c, ctx.host, send) {
                            runCatching { ask.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)) }
                                .onFailure { ctx.host.openTarget(lk.codegen.risime.net.RisiNextAction.SETTINGS_CALENDAR, null) }
                        }
                    },
                    label = { Text(c.label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("risi_next_$i"),
                )
            }
        }
    }
}

const val ADDED_EVENT_GONE = "This event is no longer on this phone"
const val OPEN_IN_CALENDAR = "Open in Calendar"

/**
 * v1.32 §25.3 the event card: title, day and time, calendar, [Open in Calendar] (ACTION_VIEW on the event), read from
 * this phone's provider row; only on the phone that added it (its write log has the id). Gone: "This event is no
 * longer on this phone". Other devices: nothing (the body text says what happened).
 */
@Composable
internal fun AddedEventCard(eventId: String, ctx: RisiCardContext) {
    val port = ctx.host.calendar ?: return
    val id = eventId.toLongOrNull() ?: return
    val view by androidx.compose.runtime.produceState<lk.codegen.risime.data.tabs.AddedEventView?>(null, id) {
        value = runCatching { port.addedEvent(id) }.getOrDefault(lk.codegen.risime.data.tabs.AddedEventView.NotHere)
    }
    val context = LocalContext.current
    val ellipsis = androidx.compose.ui.text.style.TextOverflow.Ellipsis
    when (val v = view) {
        null, lk.codegen.risime.data.tabs.AddedEventView.NotHere -> {}
        lk.codegen.risime.data.tabs.AddedEventView.Gone ->
            Text(ADDED_EVENT_GONE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = ellipsis, modifier = Modifier.testTag("risi_added_event_gone"))
        is lk.codegen.risime.data.tabs.AddedEventView.Present -> androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("risi_added_event"),
        ) {
            Column(Modifier.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(v.event.title.ifBlank { lk.codegen.risime.data.tabs.LocalEvents.NO_TITLE }, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, overflow = ellipsis, modifier = Modifier.testTag("risi_added_event_title"))
                val locale = java.util.Locale.getDefault()
                val pattern = remember(locale) { runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM") }.getOrDefault(lk.codegen.risime.data.tabs.LocalEvents.DEFAULT_DAY_PATTERN) }
                Text(lk.codegen.risime.data.tabs.LocalEvents.whenText(v.event, java.time.ZoneId.systemDefault(), locale, android.text.format.DateFormat.is24HourFormat(context), pattern), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = ellipsis, modifier = Modifier.testTag("risi_added_event_when"))
                v.calendarLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = ellipsis, modifier = Modifier.testTag("risi_added_event_calendar")) }
                androidx.compose.material3.FilledTonalButton(onClick = { port.open(id) }, modifier = Modifier.testTag("risi_added_event_open")) { Text(OPEN_IN_CALENDAR, maxLines = 1, overflow = ellipsis) }
            }
        }
    }
}

/**
 * v1.32 §29.7 "Your events" under an answer with `local_events`, on the asker's own phone in the Risi chat: built when
 * shown (never stored), phone provider + cached Risi Calendar, sorted by start, one line per event, at most 30 + "+N more".
 */
@Composable
internal fun YourEvents(r: lk.codegen.risime.net.RisiMeta, ctx: RisiCardContext) {
    val (from, to) = lk.codegen.risime.data.tabs.LocalEvents.range(r.localEvents) ?: return
    var reload by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val result by androidx.compose.runtime.produceState<lk.codegen.risime.data.tabs.LocalEventsResult?>(null, from, to, reload) {
        value = runCatching { ctx.host.localEvents(from, to) }.getOrElse { lk.codegen.risime.data.tabs.LocalEventsResult.Failed }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { g ->
        if (g[android.Manifest.permission.READ_CALENDAR] == true) reload++ else ctx.host.openSkills(lk.codegen.risime.net.RisiSkillIds.CALENDAR)
    }
    val zone = java.time.ZoneId.systemDefault()
    val locale = java.util.Locale.getDefault()
    val pattern = remember(locale) { runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM") }.getOrDefault(lk.codegen.risime.data.tabs.LocalEvents.DEFAULT_DAY_PATTERN) }
    val h24 = android.text.format.DateFormat.is24HourFormat(context)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xs).testTag("risi_your_events"), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(lk.codegen.risime.data.tabs.LocalEvents.HEADER, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
        val ellipsis = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        when (val res = result) {
            null, lk.codegen.risime.data.tabs.LocalEventsResult.Unavailable -> {}
            lk.codegen.risime.data.tabs.LocalEventsResult.NoPermission -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lk.codegen.risime.data.tabs.LocalEvents.NO_PERMISSION, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2, overflow = ellipsis, modifier = Modifier.weight(1f).testTag("risi_your_events_permission"))
                androidx.compose.material3.TextButton(
                    onClick = { runCatching { ask.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)) }.onFailure { ctx.host.openSkills(lk.codegen.risime.net.RisiSkillIds.CALENDAR) } },
                    modifier = Modifier.testTag("risi_your_events_allow"),
                ) { Text(lk.codegen.risime.data.tabs.LocalEvents.ALLOW, maxLines = 1, overflow = ellipsis) }
            }
            lk.codegen.risime.data.tabs.LocalEventsResult.Failed ->
                Text(lk.codegen.risime.data.tabs.LocalEvents.FAILED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = ellipsis, modifier = Modifier.testTag("risi_your_events_failed"))
            is lk.codegen.risime.data.tabs.LocalEventsResult.Events -> {
                if (res.events.isEmpty()) {
                    Text(lk.codegen.risime.data.tabs.LocalEvents.emptyText(res.fromMs, res.toMs, zone, locale, pattern), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2, overflow = ellipsis, modifier = Modifier.testTag("risi_your_events_none"))
                } else {
                    val (rows, more) = lk.codegen.risime.data.tabs.LocalEvents.capped(res.events)
                    rows.forEachIndexed { i, e ->
                        Text(lk.codegen.risime.data.tabs.LocalEvents.rowText(e, zone, locale, h24, pattern), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = ellipsis, modifier = Modifier.testTag("risi_your_event_$i"))
                    }
                    more?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, modifier = Modifier.testTag("risi_your_events_more")) }
                }
            }
        }
    }
}

/** P0 2026-10-10 which `next_steps` chips an answer shows, and what a tap does. */
object RisiChips {
    sealed interface Action {
        /** Sent as the user's own Risi request. */
        data class Ask(val text: String) : Action

        /** A deep link (`settings.*`, `calendar.event`). */
        data class Open(val target: String, val eventId: String? = null) : Action

        /** v1.34 §33.15 `pdf`: make the PDF of [source] on this phone. */
        data class Pdf(val source: kotlinx.serialization.json.JsonObject) : Action
    }

    data class Chip(val label: String, val action: Action)

    /** An old `next_steps` line that tells the user to do something: it is a deep link or nothing, never text. */
    private val INSTRUCTION = Regex("\\b(settings|connect|turn on|allow|enable|open)\\b", RegexOption.IGNORE_CASE)

    fun isInstruction(s: String): Boolean = INSTRUCTION.containsMatchIn(s)

    /** The deep link an instruction means, or null (then the chip isn't shown). */
    fun deepLinkFor(s: String): String? {
        val t = s.lowercase()
        return when {
            "notification" in t -> lk.codegen.risime.net.RisiNextAction.SETTINGS_NOTIFICATIONS
            "calendar" in t && ("permission" in t || "access" in t || "allow" in t) -> lk.codegen.risime.net.RisiNextAction.SETTINGS_CALENDAR_PERMISSION
            "skill" in t -> lk.codegen.risime.net.RisiNextAction.SETTINGS_RISI_SKILLS
            "calendar" in t -> lk.codegen.risime.net.RisiNextAction.SETTINGS_CALENDAR
            else -> null
        }
    }

    /**
     * At most 3 chips, only on a screen that can send them, never read-only, never on someone else's answer.
     * `next_actions` when present (unknown actions/targets dropped); else the old `next_steps`: instructions become
     * deep links (or are dropped), questions/confirm phrases are dropped, the rest are asks.
     */
    fun chips(r: lk.codegen.risime.net.RisiMeta, me: String, readOnly: Boolean, canSend: Boolean): List<Chip> {
        if (readOnly || !canSend) return emptyList()
        if (r.forUsers.isNotEmpty() && r.forUsers.none { it.equals(me, true) }) return emptyList()
        r.nextActions?.let { list ->
            return list.mapNotNull { a ->
                when (a.action) {
                    lk.codegen.risime.net.RisiNextAction.ASK -> a.text?.trim()?.takeIf { it.isNotEmpty() }?.let { Chip(a.label.trim().ifEmpty { it }, Action.Ask(it)) }
                    lk.codegen.risime.net.RisiNextAction.OPEN -> a.target?.takeIf { it in lk.codegen.risime.net.RisiNextAction.TARGETS }
                        ?.takeIf { it != lk.codegen.risime.net.RisiNextAction.CALENDAR_EVENT || !a.eventId.isNullOrBlank() }
                        ?.let { t -> a.label.trim().takeIf { it.isNotEmpty() }?.let { Chip(it, Action.Open(t, a.eventId)) } }
                    lk.codegen.risime.net.RisiTools134.ACTION_PDF -> a.source?.takeIf { lk.codegen.risime.net.PdfSources.valid(it) }?.let { s -> Chip(a.label.trim().ifEmpty { "PDF" }, Action.Pdf(s)) }
                    else -> null
                }
            }.take(3)
        }
        return r.nextSteps.map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { s ->
            when {
                isInstruction(s) -> deepLinkFor(s)?.let { Chip(s, Action.Open(it)) }
                RisiSkillCards.chipUsable(s) -> Chip(s, Action.Ask(s))
                else -> null
            }
        }.take(3)
    }

    /** Old callers: the labels of the chips. */
    fun visible(r: lk.codegen.risime.net.RisiMeta, me: String, readOnly: Boolean, canSend: Boolean): List<String> = chips(r, me, readOnly, canSend).map { it.label }

    /** A tap. [askPermission] shows Android's calendar permission dialog (`settings.calendar_permission`). */
    fun run(c: Chip, host: RisiHost, send: (String) -> Unit, askPermission: () -> Unit = {}) {
        when (val a = c.action) {
            is Action.Ask -> send(a.text)
            is Action.Open -> if (a.target == lk.codegen.risime.net.RisiNextAction.SETTINGS_CALENDAR_PERMISSION) askPermission() else host.openTarget(a.target, a.eventId)
            is Action.Pdf -> host.exportPdf(a.source)
        }
    }

    /** An ask: the text to Risi as the user's own request (`risi_request` `ask`). There is no plain-message path. */
    fun send(host: RisiHost, text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) host.ask(t)
    }
}

/** §25.4 the progress bubble for a running request ("Checking your calendar…", "Still working…"). */
@Composable
fun RisiProgressBubble(shown: RisiProgressStore.Shown, nowMs: Long, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xxs), contentAlignment = Alignment.CenterStart) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, OfficialAccent.copy(alpha = 0.4f)),
            modifier = Modifier.testTag("risi_progress"),
        ) {
            Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = OfficialAccent)
                Text("Risi · ", style = MaterialTheme.typography.labelMedium, color = OfficialAccent, fontWeight = FontWeight.SemiBold)
                Text(RisiStepLabels.of(shown, nowMs), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("risi_progress_text"))
            }
        }
    }
}
