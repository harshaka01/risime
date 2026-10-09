package lk.codegen.risime.ui.tabs

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.ui.chat.ChatItem
import lk.codegen.risime.ui.chat.withDaySeparators
import lk.codegen.risime.ui.common.ChatScrollState
import lk.codegen.risime.ui.theme.Spacing

const val RISI_CHIP_LABEL = "@Risi"
const val RISI_ASK_PLACEHOLDER = "Ask Risi…"
const val SUMMARISE_LABEL = "Summarise"
const val REPORT_LABEL = "Report"

/** The Official composer's placeholder ("Risi is listening" is the strip under the tab bar, not the hint). */
const val OFFICIAL_COMPOSER_HINT = "Message"

/** A 1:1 Official's subtitle (never a member count). */
const val OFFICIAL_DM_SUBTITLE = "Official"

/** A 1:1 Official's title: "Kumu · Risi". */
fun officialDmTitle(peerName: String): String = "$peerName$OFFICIAL_DM_TITLE_SUFFIX"

/** The always-visible tail of a 1:1 Official's title (the peer name ellipsizes, this does not). */
const val OFFICIAL_DM_TITLE_SUFFIX = " · Risi"

/**
 * §24.9 the Official composer's "@Risi" chip. Tapping it puts the chip into the message (selected, with
 * "Ask Risi…"); sending then builds a structured `risi_request` `ask`. Free text is never parsed for
 * intent: without the chip a message is an ordinary message, whatever it says [decision 066].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisiChipRow(active: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.padding(bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = active,
            onClick = { onToggle(!active) },
            label = { Text(RISI_CHIP_LABEL) },
            modifier = Modifier.testTag("risi_chip"),
        )
    }
}

/**
 * The composer's send: with the @Risi chip (Official only) the text is a structured `ask`; in every
 * other case it is an ordinary message, whatever it says ("free text is never parsed for intent").
 * @return true when the chip was used (the caller clears it).
 */
fun sendFromComposer(risi: RisiHost?, chip: Boolean, text: String, plain: (String) -> Unit, risiChat: Boolean = false): Boolean {
    // §25.2: in the Risi chat every message is a `risi_request` `ask`, never plain text.
    if (risiChat) {
        risi?.ask(text)
        return false
    }
    if (risi != null && chip) {
        risi.ask(text)
        return true
    }
    plain(text)
    return false
}

/** "Continuing with Risi ×" above the Official composer while a follow-up is open (× sends a normal message instead). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisiFollowUpChip(onDismiss: () -> Unit) {
    Row(Modifier.padding(bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.InputChip(
            selected = true,
            onClick = onDismiss,
            label = { Text(lk.codegen.risime.data.tabs.RisiFollowUp.CHIP_LABEL) },
            trailingIcon = {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Clear,
                    contentDescription = lk.codegen.risime.data.tabs.RisiFollowUp.CHIP_DISMISS,
                    modifier = Modifier.testTag("risi_follow_up_dismiss"),
                )
            },
            modifier = Modifier.testTag("risi_follow_up"),
        )
    }
}

/** The Official chat menu's two Risi items. */
@Composable
fun RisiMenuItems(host: RisiHost, enabled: Boolean, close: () -> Unit) {
    DropdownMenuItem(text = { Text(SUMMARISE_LABEL) }, enabled = enabled, onClick = { close(); host.summarise() }, modifier = Modifier.testTag("risi_menu_summarise"))
    DropdownMenuItem(text = { Text(REPORT_LABEL) }, enabled = enabled, onClick = { close(); host.report() }, modifier = Modifier.testTag("risi_menu_report"))
}

/** Scrolls the (reversed) message list to the message with server id [messageId]; false when it isn't on this phone. */
suspend fun scrollToMessage(scroll: ChatScrollState, messages: List<MessageEntity>, messageId: String): Boolean {
    val items = withDaySeparators(messages, System.currentTimeMillis())
    val i = items.indexOfFirst { it is ChatItem.Msg && it.m.messageId.equals(messageId, true) }
    if (i < 0) return false
    scroll.list.animateScrollToItem(items.lastIndex - i)
    return true
}
