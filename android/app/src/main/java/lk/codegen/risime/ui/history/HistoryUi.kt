package lk.codegen.risime.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import lk.codegen.risime.data.db.HistoryProvideEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.history.HistoryMarkerState
import lk.codegen.risime.net.HistoryRequestPush
import lk.codegen.risime.net.HistoryState
import lk.codegen.risime.net.isGroupConversation
import lk.codegen.risime.ui.common.SystemLineText
import lk.codegen.risime.ui.theme.Spacing

// ---- §17.8 the provider's prompts ----

const val HISTORY_OWN_SMALL_PRINT = "If this isn't you, someone may be using your number. Tap Not now."

/** §17.8 the prompt text (android S1): own approval, member in a group, member in a DM. */
fun historyPromptText(p: HistoryProvideEntity, name: String): String = when {
    p.own -> "Your new phone wants your chat history. Allow?"
    isGroupConversation(p.conversationId) -> "$name wants the group history from when they were in the group. Share?"
    else -> "$name wants your chat history. Share?"
}

/** android S2: how much, never the content. */
fun historyAmountText(gapCount: Int): String? = gapCount.takeIf { it > 0 }?.let { "About $it ${if (it == 1) "message" else "messages"}" }

/** The approval/share prompt: [Allow]/[Share] and [Not now]. */
@Composable
fun HistoryPromptDialog(p: HistoryProvideEntity, name: String, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (p.own) "Chat history" else "Share chat history?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(historyPromptText(p, name))
                historyAmountText(p.gapCount)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (p.own) Text(HISTORY_OWN_SMALL_PRINT, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(if (p.own) "Allow" else "Share") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Not now") } },
    )
}

// ---- §17.12 the requester's marker ----

/** The progress line under the marker (android S1); null when there's nothing to say. */
fun historyProgressText(s: HistoryMarkerState): String? {
    val r = s.request ?: return null
    if (r.closed) {
        return when (r.state) {
            HistoryState.UNAVAILABLE, HistoryState.EXPIRED, HistoryState.FAILED -> "No device could share this history right now"
            else -> null
        }
    }
    return when (r.state) {
        HistoryState.NEW, HistoryState.SEARCHING, HistoryState.REFRESH -> s.ownDevices.firstOrNull()?.deviceName?.let { "Open RisiMe on $it to share" }
            ?: if (r.sources == HistoryRequestPush.SOURCES_OWN || s.ownDevices.isNotEmpty()) "Looking for your other phone…" else "Asking group members…"
        HistoryState.WAITING_FOR_MEMBER -> "Asking group members…"
        HistoryState.ACCEPTED -> "Receiving history…"
        HistoryState.RECEIVING -> if (r.parts > 1) "Receiving history… ${minOf(r.partsDone + 1, r.parts)} of ${r.parts}" else "Receiving history…"
        else -> null
    }
}

/** What "Request history" offers: (label, sources). After a residual share, only the other source (android R6). */
fun historySourceOptions(s: HistoryMarkerState, conversationId: String, me: String, peerName: String?): List<Pair<String, String>> {
    val own = "Your other phone" to HistoryRequestPush.SOURCES_OWN
    val any = (if (isGroupConversation(conversationId)) "Your other phone or group members" else "Your other phone or ${peerName ?: "your contact"}") to HistoryRequestPush.SOURCES_ANY
    val askedOwn = s.askedFrom.any { it.equals(me, true) }
    val askedMember = s.askedFrom.any { !it.equals(me, true) }
    return when {
        askedOwn && !askedMember -> listOf(any)
        askedMember && !askedOwn -> listOf(own)
        else -> listOf(own, any)
    }
}

const val REQUEST_HISTORY = "Request history"
const val ASK_MEMBERS_NOW = "Ask group members now"
const val TRY_AGAIN = "Try again"

/**
 * The §13.3 gap marker with the v1.15 action: "Request history" while the chat has gap rows and no
 * open request (→ the source sheet), the progress line, "Ask group members now", "Try again".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryMarkerRow(
    text: String,
    state: HistoryMarkerState?,
    conversationId: String,
    me: String,
    peerName: String?,
    onRequest: (sources: String) -> Unit,
    onEscalate: () -> Unit,
) {
    var sheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SystemLineText(text)
        if (state != null) {
            historyProgressText(state)?.let { Text(it, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (state.canRequest) {
                    val failed = state.request?.closed == true && state.request.state in setOf(HistoryState.UNAVAILABLE, HistoryState.EXPIRED, HistoryState.FAILED)
                    TextButton(onClick = { sheet = true }) { Text(if (failed) TRY_AGAIN else REQUEST_HISTORY) }
                }
                val r = state.request
                if (r != null && !r.closed && r.sources == HistoryRequestPush.SOURCES_ANY && r.state in setOf(HistoryState.NEW, HistoryState.SEARCHING)) {
                    TextButton(onClick = onEscalate) { Text(ASK_MEMBERS_NOW) }
                }
            }
        }
    }
    if (sheet && state != null) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Spacing.lg)) {
                Text("Get earlier messages from:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
                historySourceOptions(state, conversationId, me, peerName).forEach { (label, sources) ->
                    ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable { sheet = false; onRequest(sources) })
                }
            }
        }
    }
}

/** §17.12 "Shared by <provider>" for an imported row not sent by the provider; own-device restores carry no label. */
fun sharedByLabel(m: MessageEntity, nameOf: (String) -> String): String? {
    if (m.origin != MessageEntity.ORIGIN_SHARED) return null
    val by = m.sharedBy ?: return null
    if (by.equals(m.from, true)) return null
    return "Shared by ${nameOf(by)}"
}
