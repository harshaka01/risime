package lk.codegen.risime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import lk.codegen.risime.data.tabs.RisiItems
import lk.codegen.risime.data.tabs.RisiItemsLocal
import lk.codegen.risime.data.tabs.RisiItemsModel
import lk.codegen.risime.data.tabs.RisiItemsRest
import lk.codegen.risime.net.RisiItem
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.theme.Spacing
import java.time.ZoneId
import java.util.Locale

/*
 * Contract v1.35 §34.4 (Phase 2): Settings → Risi skills → Calendar → "Risi's items". The upcoming items from
 * `GET /risi/items`, grouped by day under bold headers, one line each (ellipsized). Open / Edit / Delete are deep
 * links or dialogs as each item's `actions` allow; no button sends text to Risi. Delete asks first.
 */

/** Where Open (and the Edit of events, which happens on their own screens) goes. */
interface RisiItemOpener {
    /** phone_event_added: ACTION_VIEW; risi_calendar_event: the event screen; reminder: the Risi chat;
     *  scheduled_message: the chat's "Scheduled messages"; promise / follow_up: My promises. */
    fun open(item: RisiItem)

    /** phone_event_added: ACTION_EDIT; risi_calendar_event: the event screen (its Edit is the §29.3 PATCH). */
    fun editElsewhere(item: RisiItem)
}

class RisiItemsViewModel(rest: RisiItemsRest, local: RisiItemsLocal) : ViewModel() {
    val model = RisiItemsModel(rest, local, viewModelScope, errorText = { risiErrorMessage(it) }).also { it.load() }
}

@Composable
fun RisiItemsScreen(model: RisiItemsModel, opener: RisiItemOpener?, onBack: () -> Unit) {
    val s by model.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<RisiItem?>(null) }
    var editing by remember { mutableStateOf<RisiItem?>(null) }
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()
    val pattern = remember(locale) { runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM") }.getOrDefault(lk.codegen.risime.data.tabs.LocalEvents.DEFAULT_DAY_PATTERN) }
    val h24 = android.text.format.DateFormat.is24HourFormat(context)
    Scaffold(topBar = { RisiTopBar(title = RisiItems.TITLE, onBack = onBack) }, contentWindowInsets = WindowInsets(0)) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = Spacing.xl, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(RisiItems.BLURB, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("risi_items_error")) }
            s.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("risi_items_note"))
                LaunchedEffect(it) { kotlinx.coroutines.delay(4_000); model.noteShown() }
            }
            when {
                s.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                s.items.isEmpty() && s.error == null -> Text(RisiItems.EMPTY, modifier = Modifier.testTag("risi_items_empty"))
                else -> LazyColumn(Modifier.weight(1f).testTag("risi_items_list")) {
                    RisiItems.groupByDay(s.items, zone, locale, pattern).forEach { (day, items) ->
                        item(key = "h:$day") {
                            Text(day, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs).testTag("risi_items_day"))
                        }
                        items(items, key = { it.id }) { i ->
                            RisiItemRow(
                                i, s.deviceId, i.scheduleId?.let { s.localTexts[it] }, zone, h24, busy = i.id in s.busy,
                                onOpen = opener?.let { o -> { o.open(i) } },
                                onEdit = {
                                    when (i.kind) {
                                        RisiItem.REMINDER, RisiItem.SCHEDULED_MESSAGE, RisiItem.PROMISE -> editing = i
                                        else -> opener?.editElsewhere(i)
                                    }
                                },
                                onDelete = { deleting = i },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
    deleting?.let { i ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(RisiItems.DELETE, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text(RisiItems.deleteQuestion(i), modifier = Modifier.testTag("risi_item_delete_question")) },
            confirmButton = {
                TextButton(onClick = { deleting = null; model.delete(i) }, modifier = Modifier.testTag("risi_item_delete_confirm")) {
                    Text(RisiItems.DELETE, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(RisiItems.CANCEL, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        )
    }
    editing?.let { i ->
        when (i.kind) {
            RisiItem.REMINDER -> lk.codegen.risime.ui.tabs.RisiEditDialog(
                initialText = i.title.orEmpty(), initialDue = i.start, ownerName = null, title = RisiItems.EDIT_REMINDER_TITLE,
                onSave = { t, due -> editing = null; model.editReminder(i, due, t) }, onDismiss = { editing = null },
            )
            RisiItem.PROMISE -> lk.codegen.risime.ui.tabs.RisiEditDialog(
                initialText = i.title.orEmpty(), initialDue = i.start, ownerName = null, title = "Edit promise",
                onSave = { t, due -> editing = null; model.editPromise(i, t, due) }, onDismiss = { editing = null },
            )
            RisiItem.SCHEDULED_MESSAGE -> ScheduledTextDialog(
                initial = i.scheduleId?.let { s.localTexts[it] }.orEmpty(),
                onSave = { t -> editing = null; model.editScheduled(i, t) }, onDismiss = { editing = null },
            )
            else -> LaunchedEffect(i) { editing = null }
        }
    }
}

/** One item: a bold kind + time line, the title (one line, ellipsized), the calendar, then its buttons. */
@Composable
fun RisiItemRow(
    item: RisiItem,
    deviceId: String?,
    localText: String?,
    zone: ZoneId,
    h24: Boolean,
    busy: Boolean,
    onOpen: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val buttons = RisiItems.buttons(item, deviceId)
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm).testTag("risi_item_${item.kind}"), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        val head = listOfNotNull(RisiItems.kindLabel(item.kind), RisiItems.timeText(item, zone, h24)).joinToString(" · ")
        Text(head, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("risi_item_head"))
        Text(RisiItems.titleText(item, deviceId, localText), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("risi_item_title"))
        RisiItems.calendarText(item)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (buttons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (RisiItem.OPEN in buttons && onOpen != null) TextButton(onClick = onOpen, enabled = !busy, modifier = Modifier.testTag("risi_item_open")) { Text(RisiItems.OPEN, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (RisiItem.EDIT in buttons) TextButton(onClick = onEdit, enabled = !busy, modifier = Modifier.testTag("risi_item_edit")) { Text(RisiItems.EDIT, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (RisiItem.DELETE in buttons) TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.testTag("risi_item_delete")) { Text(RisiItems.DELETE, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun ScheduledTextDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(RisiItems.EDIT_MESSAGE_TITLE, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("risi_item_message_text")) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("risi_item_message_save")) { Text(RisiItems.SAVE, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(RisiItems.CANCEL, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    )
}
