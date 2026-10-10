package lk.codegen.risime.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import lk.codegen.risime.data.tabs.RisiControl
import lk.codegen.risime.data.tabs.SummaryPeriods
import lk.codegen.risime.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The Official menu's "Summarise": Today / Last 7 days / Last 30 days / Date range… (at most 31 days back). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummarisePeriodDialog(host: RisiHost, onDismiss: () -> Unit) {
    var range by remember { mutableStateOf(false) }
    if (!range) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(SUMMARISE_LABEL) },
            text = {
                Column(Modifier.testTag("risi_summarise_periods")) {
                    SummaryPeriods.CHOICES.forEach { (label, period) ->
                        Text(
                            label,
                            Modifier.fillMaxWidth().clickable {
                                if (period == null) range = true else { onDismiss(); host.summarisePeriod(period) }
                            }.padding(vertical = Spacing.md).testTag("risi_summarise_${period ?: "range"}"),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
        )
        return
    }
    val zone = ZoneId.systemDefault()
    val todayUtc = java.time.LocalDate.now(zone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val earliest = todayUtc - (RisiControl.RANGE_MAX_DAYS - 1) * 86_400_000L
    val st = rememberDateRangePickerState(
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in earliest..todayUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = st.selectedStartDateMillis != null,
                onClick = {
                    val from = st.selectedStartDateMillis ?: return@TextButton
                    val to = st.selectedEndDateMillis ?: from
                    // Picker millis are UTC midnights of the chosen days: the local days from 00:00 to the next 00:00.
                    val fromDay = Instant.ofEpochMilli(from).atZone(ZoneOffset.UTC).toLocalDate()
                    val toDay = Instant.ofEpochMilli(to).atZone(ZoneOffset.UTC).toLocalDate()
                    onDismiss()
                    host.summariseRange(fromDay.atStartOfDay(zone).toInstant().toEpochMilli(), toDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
                },
                modifier = Modifier.testTag("risi_summarise_range_ok"),
            ) { Text(SUMMARISE_LABEL) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
    ) { DateRangePicker(st, Modifier.testTag("risi_summarise_range_picker")) }
}
