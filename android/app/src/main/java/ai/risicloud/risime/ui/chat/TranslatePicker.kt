package ai.risicloud.risime.ui.chat

import ai.risicloud.risime.ui.theme.Spacing
import ai.risicloud.risime.voice.RecentTranslateLanguages
import ai.risicloud.risime.voice.TranslateLanguages
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/*
 * Architect 2026-10-16 14:05 item 3: the one Translate picker (transcripts, typed messages, the always-translate
 * setting): Sinhala / English / Tamil buttons without the text's own language, then "More languages…", a searchable
 * list with this user's recent picks on top.
 */

/** The default buttons plus "More languages…" (only when the server translates to more). [label] names a button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslateLanguageButtons(
    source: String?,
    supported: List<String>,
    recent: RecentTranslateLanguages,
    label: (String) -> String,
    onPick: (String) -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.sm)) {
        for (l in TranslateLanguages.buttons(source, supported)) {
            PickerLink(label(l), "translate_$l") {
                recent.record(l)
                onPick(l)
            }
        }
        if (TranslateLanguages.hasMore(supported)) PickerLink(TranslateLanguages.MORE, "translate_more") { more = true }
    }
    if (more) {
        LanguagePickerDialog(source, supported, recent, onDismiss = { more = false }) {
            more = false
            onPick(it)
        }
    }
}

/** "More languages…": search, the recent picks first, every other supported language by name. */
@Composable
fun LanguagePickerDialog(source: String?, supported: List<String>, recent: RecentTranslateLanguages, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val recents = remember { recent.list() }
    val (top, rest) = remember(query, supported) { TranslateLanguages.list(query, supported, recents, source) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Translate to") },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search languages") },
                    modifier = Modifier.fillMaxWidth().testTag("translate_search"),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = Spacing.sm).testTag("translate_list")) {
                    if (top.isNotEmpty()) {
                        item(key = "recent_header") { Header(TranslateLanguages.RECENT) }
                        items(top, key = { "r_$it" }) { l -> Row(l, "translate_pick_recent_$l") { recent.record(l); onPick(l) } }
                        if (rest.isNotEmpty()) item(key = "all_header") { Header("All languages") }
                    }
                    items(rest, key = { "a_$it" }) { l -> Row(l, "translate_pick_$l") { recent.record(l); onPick(l) } }
                    if (top.isEmpty() && rest.isEmpty()) item(key = "none") { Text("No language matches", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Header(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.xs))

@Composable
private fun Row(code: String, tag: String, onClick: () -> Unit) =
    Text(
        TranslateLanguages.name(code), style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.sm).testTag(tag),
    )

@Composable
private fun PickerLink(text: String, tag: String, onClick: () -> Unit) =
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag(tag).clickable(onClick = onClick),
    )
