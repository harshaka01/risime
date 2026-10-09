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
        val stateText = when (state) {
            RisiToolCards.ConfirmState.CONFIRMED -> "Confirmed"
            RisiToolCards.ConfirmState.CANCELLED -> "Cancelled"
            RisiToolCards.ConfirmState.EXPIRED -> "Expired"
            RisiToolCards.ConfirmState.OPEN -> null
        }
        stateText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_confirm_state")) }
        val wid = r.writeId
        val buttons = if (ctx.readOnly || wid == null) emptyList() else RisiToolCards.confirmButtons(ctx.host.me, r, state, ctx.awaiting)
        if (buttons.isNotEmpty() && wid != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if ("add" in buttons) FilledTonalButton(onClick = { ctx.host.act(wid, "confirm_write") }, modifier = Modifier.testTag("risi_confirm_add")) { Text("Add") }
                if ("allow" in buttons) FilledTonalButton(onClick = { ctx.host.act(wid, "confirm_write") }, modifier = Modifier.testTag("risi_confirm_allow")) { Text("Allow") }
                if ("cancel" in buttons) OutlinedButton(onClick = { ctx.host.act(wid, "cancel_write") }, modifier = Modifier.testTag("risi_confirm_cancel")) { Text("Cancel") }
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
                if ("me_too" in buttons) FilledTonalButton(onClick = { ctx.host.act(rid, "me_too") }, modifier = Modifier.testTag("risi_me_too")) { Text("Me too") }
                if ("not_me" in buttons) OutlinedButton(onClick = { ctx.host.act(rid, "not_me") }, modifier = Modifier.testTag("risi_not_me")) { Text("Not me") }
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
            FilledTonalButton(onClick = { ctx.host.useDraft(target, r.text!!) }, modifier = Modifier.testTag("risi_draft_use")) { Text("Use") }
        }
    }
}

@Composable
internal fun SkillDoneCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val context = LocalContext.current
    Column(Modifier.testTag("risi_skill_done"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(row.body.ifBlank { r.summary.orEmpty() }, style = MaterialTheme.typography.bodyLarge)
        // P0: the success line ("Added to your Google Calendar: …") with [Open] and [Undo]; the action card above shows "Added".
        if (r.skillId == lk.codegen.risime.net.RisiSkillIds.CALENDAR) CalendarOpenButton(r, ctx)
        val entry = r.entryId
        val skill = r.skillId
        val token = r.undoToken
        if (!ctx.readOnly && entry != null && skill != null && token != null && RisiSkillCards.canUndo(r, ctx.nowMs, ctx.host.undone)) {
            OutlinedButton(onClick = { ctx.host.undo(skill, entry, token) }, modifier = Modifier.testTag("risi_undo")) { Text("Undo") }
        } else if (entry != null && entry.lowercase() in ctx.host.undone) {
            Text("Undo requested", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_undo_state"))
        }
        RisiSkillCards.manualHint(r)?.let { hint ->
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_undo_hint"))
            if (RisiSkillCards.opensClock(r)) {
                OutlinedButton(
                    onClick = { runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    modifier = Modifier.testTag("risi_open_clock"),
                ) { Text("Open Clock") }
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
    val next = RisiSkillCards.nextSteps(r)
    if (next.isNotEmpty() && !ctx.readOnly) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            next.forEachIndexed { i, s -> AssistChip(onClick = { ctx.prefill(s) }, label = { Text(s) }, modifier = Modifier.testTag("risi_next_$i")) }
        }
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
