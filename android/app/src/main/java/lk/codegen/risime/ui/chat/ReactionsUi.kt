package lk.codegen.risime.ui.chat

import android.view.ContextThemeWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import lk.codegen.risime.data.ReactionChip
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.theme.Spacing

/** §11.3 quick reactions. */
val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * The emoji2 picker (recent emojis, skin tones) in a bottom sheet. The view is created once, so its
 * listener always calls the **latest** [onPick] (a stale one kept the composer text of the first
 * pick: every further emoji replaced the previous one). [onBackspace] adds a ⌫ that deletes one
 * whole grapheme, for the composer, where the sheet stays open between picks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit, onBackspace: (() -> Unit)? = null) {
    val pick by androidx.compose.runtime.rememberUpdatedState(onPick)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (onBackspace != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onBackspace) {
                    Text("⌫", fontSize = 22.sp, modifier = Modifier.semantics { contentDescription = "Delete" })
                }
            }
        }
        AndroidView(
            factory = { ctx -> EmojiPickerView(ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat_DayNight)).apply { setOnEmojiPickedListener { pick(it.emoji) } } },
            modifier = Modifier.fillMaxWidth().height(380.dp),
        )
    }
}

/** Long-press: 6 quick reactions + "+", then Copy, Retry, Delete. My active reactions are highlighted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    canReact: Boolean,
    myReactions: Set<String>,
    onReact: (emoji: String, op: String) -> Unit,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    var picker by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Spacing.lg)) {
            if (canReact) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    QUICK_REACTIONS.forEach { e ->
                        val mine = e in myReactions
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(48.dp).clickable {
                                onReact(e, if (mine) "remove" else "add")
                                onDismiss()
                            }.semantics { contentDescription = if (mine) "Remove reaction $e" else "React $e" },
                        ) { Text(e, fontSize = 26.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
                    }
                    IconButton(onClick = { picker = true }) { Icon(Icons.Default.Add, "More reactions") }
                }
            }
            actions.forEach { (label, act) ->
                ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable { act(); onDismiss() })
            }
        }
    }
    if (picker) {
        EmojiPickerSheet(onPick = { e ->
            picker = false
            onReact(e, if (e in myReactions) "remove" else "add")
            onDismiss()
        }, onDismiss = { picker = false })
    }
}

/** "👍 2 ❤️ 1" under a bubble; mine outlined; tap → who reacted. */
@Composable
fun ReactionChipsRow(chips: List<ReactionChip>, onOpen: () -> Unit) {
    if (chips.isEmpty()) return
    Row(
        Modifier.padding(top = 2.dp).clickable(onClick = onOpen).semantics {
            contentDescription = "Reactions: " + chips.joinToString { "${it.emoji} ${it.count}" }
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chips.forEach { c ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = if (c.mine) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Text("${c.emoji} ${c.count}", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** "Reactions": All + one tab per emoji, listing people (a user once, however many devices). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionsSheet(chips: List<ReactionChip>, nameOf: (String) -> String, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Reactions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.lg))
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = Spacing.lg) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("All ${chips.sumOf { it.count }}") })
            chips.forEachIndexed { i, c -> Tab(selected = tab == i + 1, onClick = { tab = i + 1 }, text = { Text("${c.emoji} ${c.count}") }) }
        }
        val rows: List<Pair<String, String>> = if (tab == 0) chips.flatMap { c -> c.reactors.map { it to c.emoji } } else chips[tab - 1].let { c -> c.reactors.map { it to c.emoji } }
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Spacing.lg)) {
            items(rows) { (user, emoji) ->
                val name = nameOf(user)
                ListItem(
                    leadingContent = { InitialsAvatar(name, size = 36.dp) },
                    headlineContent = { Text(name) },
                    trailingContent = { Text(emoji, fontSize = 22.sp) },
                )
            }
        }
    }
}
