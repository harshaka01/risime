package lk.codegen.risime.ui.group

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import lk.codegen.risime.data.GraphemeCounter
import lk.codegen.risime.net.GroupReceiptsReply
import lk.codegen.risime.ui.common.EmptyState
import lk.codegen.risime.ui.common.ErrorState
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.common.SectionHeader
import lk.codegen.risime.ui.common.timeOf
import lk.codegen.risime.ui.theme.RisiTheme
import lk.codegen.risime.ui.theme.Sizes
import lk.codegen.risime.ui.theme.Spacing

// ---- Shared pieces of the group screens (contract v1.9 §12, review "Android open points") ----

/** A friend in a picker. [ready] = `group_ready` (§12.1); others are greyed with "needs to update". */
data class PickFriend(val userId: String, val name: String, val company: String, val ready: Boolean)

const val GROUP_NAME_MAX = 100

/** §12.2: a group name is 1–100 grapheme clusters. Null when valid. */
fun groupNameError(name: String, counter: GraphemeCounter): String? {
    val t = name.trim()
    if (t.isEmpty()) return "Enter a group name"
    return if (counter.count(t) > GROUP_NAME_MAX) "At most $GROUP_NAME_MAX characters" else null
}

/** Friends matching [query] (name or company), ready ones first, then by name. */
fun filterFriends(friends: List<PickFriend>, query: String): List<PickFriend> {
    val q = query.trim()
    return friends.filter { q.isEmpty() || it.name.contains(q, true) || it.company.contains(q, true) }
        .sortedWith(compareByDescending<PickFriend> { it.ready }.thenBy { it.name.lowercase() })
}

/** Searchable multi-select of accepted friends; non-ready friends can't be picked. */
@Composable
fun FriendPicker(
    friends: List<PickFriend>,
    selected: Set<String>,
    query: String,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "No friends to add yet.",
) {
    val shown = filterFriends(friends, query)
    var notice by remember { mutableStateOf<String?>(null) }
    Column(modifier) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text("Search friends") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
        notice?.let {
            Text(
                it,
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = true)) {
            if (shown.isEmpty()) item { EmptyState(if (friends.isEmpty()) emptyText else "No match") }
            items(shown, key = { it.userId }) { f ->
                val checked = f.userId in selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Sizes.listRowMin)
                        .then(
                            // The whole row (≥ 48dp) toggles. A friend who isn't group-ready can't be
                            // ticked (§12.1), but a tap says why instead of doing nothing.
                            if (f.ready) {
                                Modifier.toggleable(value = checked, role = Role.Checkbox) {
                                    notice = null
                                    onToggle(f.userId)
                                }
                            } else {
                                Modifier.clickable(onClickLabel = "Why can't I add ${f.name}?") { notice = notReadyExplanation(f.name) }
                            },
                        )
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InitialsAvatar(f.name, enabled = f.ready)
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = if (f.ready) MaterialTheme.colorScheme.onSurface else RisiTheme.colors.textMuted)
                        Text(
                            if (f.ready) f.company else "${f.name} needs to update RisiMe",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (f.ready) MaterialTheme.colorScheme.onSurfaceVariant else RisiTheme.colors.textMuted,
                        )
                    }
                    Checkbox(checked = checked, onCheckedChange = null, enabled = f.ready)
                }
            }
        }
    }
}

/** Shown when a greyed friend is tapped (the server's §12.1 readiness: every app instance in 30 days). */
fun notReadyExplanation(name: String) =
    "$name can't be added yet: a device of theirs used an older RisiMe in the last 30 days. They need to update RisiMe on every device they use."

// ---- Create group: pick friends → name → Create ----

data class CreateGroupUi(
    val friends: List<PickFriend> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    /** 0 = pick members, 1 = name. */
    val step: Int = 0,
    val name: String = "",
    val nameError: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canContinue: Boolean get() = selected.isNotEmpty() && !busy
    val canCreate: Boolean get() = canContinue && nameError == null && name.isNotBlank()
}

/**
 * The New group flow. Every state keeps a working, reachable way out (decision 016): Back is
 * always in the top bar, and the bottom action stays above the keyboard and navigation bar.
 */
@Composable
fun CreateGroupContent(
    ui: CreateGroupUi,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
    onNext: () -> Unit,
    onName: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            RisiTopBar(
                title = "New group",
                subtitle = if (ui.step == 0) {
                    if (ui.selected.isEmpty()) "Add members" else "${ui.selected.size} selected"
                } else {
                    "Name the group"
                },
                onBack = onBack,
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            if (ui.step == 0) {
                FriendPicker(ui.friends, ui.selected, ui.query, onQuery, onToggle, Modifier.weight(1f), "No friends yet. Add friends first.")
            } else {
                Column(Modifier.weight(1f).padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = ui.name, onValueChange = onName, singleLine = true,
                        label = { Text("Group name") },
                        isError = ui.nameError != null && ui.name.isNotEmpty(),
                        supportingText = { Text(ui.nameError?.takeIf { ui.name.isNotEmpty() } ?: "${ui.selected.size + 1} members, you included") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !ui.busy,
                    )
                    Text(
                        "Messages in this group are end-to-end encrypted. New members don't see earlier messages.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ui.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs))
            }
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.md),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ui.busy) {
                        CircularProgressIndicator(Modifier.padding(end = Spacing.md).semantics { contentDescription = "Creating group" })
                    }
                    if (ui.step == 0) {
                        Button(onClick = onNext, enabled = ui.canContinue) { Text("Next") }
                    } else {
                        Button(onClick = onCreate, enabled = ui.canCreate) { Text("Create") }
                    }
                }
            }
        }
    }
}

// ---- "Read by" sheet (long-press your own message → Info) ----

sealed interface ReadByState {
    data object Loading : ReadByState

    data class Error(val message: String) : ReadByState

    data class Loaded(val reply: GroupReceiptsReply) : ReadByState
}

private fun isoMs(s: String?): Long? = s?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

/** Two lists from `GET …/receipts`, fetched on open and not stored: "Read by" and "Delivered to". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadBySheet(state: ReadByState, nameOf: (String) -> String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ReadByContent(state, nameOf, onRetry, onDismiss)
    }
}

@Composable
fun ReadByContent(state: ReadByState, nameOf: (String) -> String, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Spacing.lg)) {
        Text("Message info", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
        when (state) {
            ReadByState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(Spacing.lg).semantics { contentDescription = "Loading" })
            is ReadByState.Error -> ErrorState(state.message, onRetry = onRetry)
            is ReadByState.Loaded -> {
                val rs = state.reply.receipts
                val read = rs.filter { it.readAt != null }.sortedBy { isoMs(it.readAt) }
                val delivered = rs.filter { it.readAt == null && it.deliveredAt != null }.sortedBy { isoMs(it.deliveredAt) }
                val waiting = rs.count { it.deliveredAt == null && it.readAt == null }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    item { SectionHeader("Read by ${read.size} of ${state.reply.of}", Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) }
                    if (read.isEmpty()) item { Text("Nobody yet", Modifier.padding(horizontal = Spacing.lg), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(read, key = { "r" + it.userId }) { ReceiptRow(nameOf(it.userId), isoMs(it.readAt)) }
                    item { HorizontalDivider(Modifier.padding(vertical = Spacing.sm)) }
                    item { SectionHeader("Delivered to ${delivered.size}", Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) }
                    if (delivered.isEmpty()) item { Text("Nobody else", Modifier.padding(horizontal = Spacing.lg), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(delivered, key = { "d" + it.userId }) { ReceiptRow(nameOf(it.userId), isoMs(it.deliveredAt)) }
                    if (waiting > 0) {
                        item {
                            Text(
                                "Not delivered yet: $waiting", Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(end = Spacing.md)) { Text("Close") }
    }
}

@Composable
private fun ReceiptRow(name: String, at: Long?) {
    Row(Modifier.fillMaxWidth().heightIn(min = Sizes.minTouch).padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        InitialsAvatar(name, size = Sizes.avatarSmall)
        Spacer(Modifier.width(Spacing.md))
        Text(name, Modifier.weight(1f))
        at?.let { Text(timeOf(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A confirm dialog with a working cancel. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
