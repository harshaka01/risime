package lk.codegen.risime.ui.tabs

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
fun sendFromComposer(risi: RisiHost?, chip: Boolean, text: String, plain: (String) -> Unit): Boolean {
    if (risi != null && chip) {
        risi.ask(text)
        return true
    }
    plain(text)
    return false
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
