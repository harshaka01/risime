package lk.codegen.risime.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiP0Cards
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.ui.theme.Spacing

/*
 * Contract v1.35 §34 Phase 2 cards: the error card with its `next_actions` ("Ask me again" re-asks the original
 * text), and the native name-check card from `answer.clarify` (a tap sends the same chip text).
 */

/** §34.3 an `error`: its line, then its `next_actions` as chips (the same chips as on an answer). */
@Composable
internal fun RisiErrorCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    Column(Modifier.testTag("risi_error_card"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(RisiP0Cards.errorText(r.code, row.body), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("risi_error_text"))
        NextActionChips(r, ctx)
    }
}

/**
 * §34.5 the name question: "Did you mean Shirazi?" with one button per option and [Keep "<said>"]. A tap sends
 * the `ask` text of the matching `next_actions` entry as the user's own Risi request. Once answered (the confirm
 * card came) or closed (`confirm_update`), the buttons go and the card is greyed.
 */
@Composable
internal fun RisiClarifyCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val state = RisiP0Cards.clarifyState(r, ctx.messages)
    val open = state == RisiP0Cards.ClarifyState.OPEN
    val dim = if (open) 1f else 0.55f
    val send = ctx.sendChip
    val buttons = if (ctx.readOnly || send == null || !open || (r.forUsers.isNotEmpty() && r.forUsers.none { it.equals(ctx.host.me, true) })) emptyList() else RisiP0Cards.clarifyButtons(r)
    Column(Modifier.testTag("risi_clarify_card"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(r.answer ?: row.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim), modifier = Modifier.testTag("risi_clarify_question"))
        if (buttons.isNotEmpty() && send != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                buttons.forEachIndexed { i, b ->
                    if (b.keep) {
                        OutlinedButton(onClick = { send(b.text) }, modifier = Modifier.testTag("risi_clarify_keep")) { Text(b.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else {
                        FilledTonalButton(onClick = { send(b.text) }, modifier = Modifier.testTag("risi_clarify_option_$i")) { Text(b.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        when (state) {
            RisiP0Cards.ClarifyState.CLOSED -> Text(lk.codegen.risime.data.tabs.RisiToolCards.SUPERSEDED_TEXT, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("risi_clarify_state"))
            RisiP0Cards.ClarifyState.ANSWERED -> Text(CLARIFY_ANSWERED, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("risi_clarify_state"))
            RisiP0Cards.ClarifyState.OPEN -> {}
        }
        // Other next actions (not the name buttons) stay chips.
        val rest = RisiP0Cards.chipsBesideClarify(r)
        if (!rest.isNullOrEmpty() && open) NextActionChips(r.copy(nextActions = rest), ctx)
    }
}

const val CLARIFY_ANSWERED = "Answered"
