package lk.codegen.risime.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiClarify
import lk.codegen.risime.data.tabs.RisiControl
import lk.codegen.risime.data.tabs.RisiLedger
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Server item 10 (2026-10-09): the one `item_clarify` question of a vague item. [New date] (the owner,
 * `new_date` in `buttons`) opens the date/time picker and sends §27.5 `item_edit` with the new `due` in
 * my Risi chat (where the card is); `buttons: []` (a v1.24 item) shows the question only.
 */

@Composable
internal fun ItemClarifyCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    var picking by remember { mutableStateOf(false) }
    val me = ctx.host.me
    val id = r.itemId
    val text = r.text
    Column(Modifier.testTag("risi_item_clarify"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(r.question ?: row.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("risi_clarify_question"))
        when {
            RisiClarify.answered(me, r, ctx.messages) ->
                Text("New date sent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_clarify_sent"))
            !ctx.readOnly && RisiClarify.canPickDate(me, r, ctx.messages, ctx.awaiting) ->
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.testTag("risi_clarify_new_date")) { Text("New date") }
        }
    }
    if (picking && id != null && text != null) {
        NewDateDialog(onPick = { due, allDay ->
            picking = false
            ctx.host.actItem(id, RisiLedger.EDIT, text.take(RisiControl.MAX_ITEM_TEXT), due, allDay)
        }, onDismiss = { picking = false })
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** [New date]: a day and a time (or all day); [Save] gives the `item_edit` `due` and `all_day`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewDateDialog(onPick: (due: String, allDay: Boolean) -> Unit, onDismiss: () -> Unit, initialDate: LocalDate? = null, initialTime: LocalTime? = null) {
    var date by remember { mutableStateOf(initialDate) }
    var time by remember { mutableStateOf(initialTime) }
    var allDay by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New date") },
        text = {
            Column(Modifier.testTag("risi_new_date_form"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.testTag("risi_new_date_day")) { Text(date?.format(DAY) ?: "Pick a date") }
                    if (!allDay) {
                        OutlinedButton(onClick = { pickTime = true }, enabled = date != null, modifier = Modifier.testTag("risi_new_date_time")) { Text(time?.format(TIME) ?: "Pick a time") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allDay, onCheckedChange = { allDay = it }, modifier = Modifier.testTag("risi_new_date_all_day"))
                    Text("All day")
                }
            }
        },
        confirmButton = {
            val d = date
            TextButton(
                enabled = d != null && (allDay || time != null),
                onClick = { if (d != null) RisiClarify.due(d, if (allDay) null else time).let { (due, ad) -> onPick(due, ad) } },
                modifier = Modifier.testTag("risi_new_date_save"),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; pickDate = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(st) }
    }
    if (pickTime) {
        val t0 = time ?: LocalTime.of(10, 0)
        val st = rememberTimePickerState(t0.hour, t0.minute, false)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(st.hour, st.minute); pickTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(st) },
        )
    }
}
