package ai.risicloud.risime.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.risicloud.risime.data.tabs.RisiTalk
import ai.risicloud.risime.ui.theme.Spacing

/** PART 4: "What can Risi do?" at the top of an empty Risi chat; a tap fills the composer (never sends). */
@Composable
fun RisiExamplesCard(onPick: (RisiTalk.Example) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, OfficialAccent.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm).testTag("risi_examples"),
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RisiAvatar(28.dp)
                Text(RisiTalk.EXAMPLES_TITLE, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            for (ex in RisiTalk.EXAMPLES) {
                Text(
                    ex.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OfficialAccent,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) { onPick(ex) }
                        .padding(vertical = Spacing.xs)
                        .testTag("risi_example"),
                )
            }
        }
    }
}

/** PART 4: the "@" picker above the Official composer: it offers Risi while "@…" can still become "@Risi". */
@Composable
fun RisiMentionPicker(onPick: () -> Unit) {
    Row(Modifier.padding(bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = onPick,
            label = { Text(RisiTalk.PICKER_LABEL) },
            leadingIcon = { RisiAvatar(20.dp) },
            modifier = Modifier.semantics { contentDescription = RisiTalk.PICKER_DESC }.testTag("risi_mention_pick"),
        )
    }
}

/** PART 4: Private never reaches Risi; "@Risi" typed there offers to move the text to Official. */
@Composable
fun RisiPrivateHint(onMove: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs).testTag("risi_private_hint"),
    ) {
        Row(Modifier.padding(start = Spacing.md, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(RisiTalk.PRIVATE_HINT, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = Spacing.sm))
            TextButton(onClick = onMove, modifier = Modifier.testTag("risi_private_move")) { Text(RisiTalk.PRIVATE_MOVE) }
        }
    }
}
