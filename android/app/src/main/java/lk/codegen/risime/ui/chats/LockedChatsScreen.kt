package lk.codegen.risime.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.lock.SecretCode
import lk.codegen.risime.ui.common.EmptyState
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.common.SectionHeader
import lk.codegen.risime.ui.lock.LOCKED_CHATS_TITLE
import lk.codegen.risime.ui.lock.UNLOCK_CHAT_LABEL
import lk.codegen.risime.ui.lock.LockGate
import lk.codegen.risime.ui.lock.LockGateDialog
import lk.codegen.risime.ui.lock.rememberLockGate
import lk.codegen.risime.ui.theme.Spacing

const val LOCKED_CHATS_SETTINGS = CHAT_LOCK_SETTINGS
const val HIDE_LOCKED_CHATS = "Hide locked chats"
const val SECRET_CODE = "Secret code"
const val SET_SECRET_CODE = "Set secret code"
const val CHANGE_SECRET_CODE = "Change secret code"
const val REMOVE_SECRET_CODE = "Remove secret code"
const val RESET_LOCKED_CHATS = "Unlock all chats and remove the secret code"

/**
 * The pull-down gesture on the chat list: a downward drag that the list can't scroll any further (it is at
 * the top) reveals the "Locked chats" entry; scrolling back up hides it.
 */
class PullToRevealState(private val thresholdPx: Float = 96f) {
    var revealed by mutableStateOf(false)
        private set
    private var pulled = 0f

    fun reset() {
        revealed = false
        pulled = 0f
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (revealed && available.y < 0f && source == NestedScrollSource.UserInput) reset()
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0f) {
                pulled += available.y
                if (pulled >= thresholdPx) revealed = true
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            pulled = 0f
            return Velocity.Zero
        }
    }
}

/** The row the pull-down reveals: lock icon, "Locked chats" and how many. */
@Composable
fun LockedFolderEntry(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .semantics { contentDescription = "$LOCKED_CHATS_TITLE, $count" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
        Text(LOCKED_CHATS_TITLE, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The Locked chats folder (opened after the confirmation): the locked chats; tap opens, long-press unlocks. */
@Composable
fun LockedChatsScreen(
    vm: ChatsViewModel,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    // FLAG_SECURE while the folder is on screen (black in a screen share, a screenshot, Recents).
    lk.codegen.risime.ui.lock.SecureWindow(lk.codegen.risime.ui.lock.SecureScreen.LOCKED_CHATS_FOLDER)
    val rows by vm.lockedRows.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var unlockFor by remember { mutableStateOf<ChatRow?>(null) }
    unlockFor?.let { r ->
        AlertDialog(
            onDismissRequest = { unlockFor = null },
            title = { Text(r.name) },
            text = { Text("Unlocking moves this chat back to your chat list. Nothing is deleted.") },
            confirmButton = { TextButton(onClick = { unlockFor = null; vm.unlockChat(r) }) { Text(UNLOCK_CHAT_LABEL) } },
            dismissButton = { TextButton(onClick = { unlockFor = null }) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
        )
    }
    Scaffold(
        topBar = {
            RisiTopBar(
                title = LOCKED_CHATS_TITLE,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(LOCKED_CHATS_SETTINGS) }, onClick = { menu = false; onSettings() })
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            if (rows.isEmpty()) item { EmptyState("No locked chats. Long-press a chat in your list and choose Lock chat.") }
            items(rows, key = { it.key }) { row ->
                ChatRowItem(row, onClick = { row.target?.takeIf { row.openable }?.let(onOpen) }, onLongClick = { unlockFor = row })
            }
        }
    }
}

/** Locked chats settings: the optional secret code that hides the pull-down entry. */
@Composable
fun LockedChatsSettingsScreen(c: AppContainer, onBack: () -> Unit) {
    val hasCode by c.lockedChats.hasCode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        SecretCodeDialog(
            onSave = { code -> scope.launch { if (c.lockedChats.setSecretCode(code)) editing = false } },
            onDismiss = { editing = false },
        )
    }
    LockedChatsSettingsContent(
        hasCode = hasCode,
        onBack = onBack,
        onHide = { hide -> if (hide) editing = true else scope.launch { c.lockedChats.clearSecretCode() } },
        onSecretCode = { editing = true },
    )
}

/**
 * WhatsApp's chat lock settings: "Hide locked chats" (the Locked chats row leaves the chat list; it then
 * needs a secret code, typed in the chat-list search to find them) and "Secret code" (set or change).
 * Turning "Hide locked chats" off removes the code (the row shows on pull-down again). Nothing is deleted.
 */
@Composable
fun LockedChatsSettingsContent(hasCode: Boolean, onBack: () -> Unit, onHide: (Boolean) -> Unit, onSecretCode: () -> Unit) {
    Scaffold(
        topBar = { RisiTopBar(title = LOCKED_CHATS_SETTINGS, onBack = onBack) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(vertical = Spacing.sm)) {
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = hasCode, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onHide)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(HIDE_LOCKED_CHATS, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (hasCode) "Locked chats are hidden from your chat list. Type your secret code in the search box on the chat list to find them; " +
                            "your fingerprint or screen lock is still asked after that."
                        else "Hide the Locked chats row from your chat list. You'll set a secret code to find them from the search box.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(checked = hasCode, onCheckedChange = null)
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onSecretCode).padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(SECRET_CODE, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (hasCode) CHANGE_SECRET_CODE else "Not set. $SET_SECRET_CODE to hide your locked chats.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun SecretCodeDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val error = when {
        code.isNotEmpty() && !SecretCode.valid(code) -> "Use at least ${SecretCode.MIN_LENGTH} characters."
        again.isNotEmpty() && code != again -> "The codes don't match."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SET_SECRET_CODE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "If you forget it, Settings → Locked chats can unlock all chats after your fingerprint or screen lock.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    code, { code = it }, label = { Text("Secret code", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    again, { again = it }, label = { Text("Type it again", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(code) }, enabled = SecretCode.valid(code) && code == again) { Text("Save", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
    )
}

/**
 * Settings: the way back when the secret code is forgotten (or just to undo everything). After the
 * fingerprint/screen-lock confirmation every chat is unlocked and the code removed; no message is touched.
 */
@Composable
fun LockedChatsResetSection(c: AppContainer, gate: LockGate = rememberLockGate()) {
    val ids by c.lockedChats.ids.collectAsStateWithLifecycle()
    val hasCode by c.lockedChats.hasCode.collectAsStateWithLifecycle()
    if (ids.isNullOrEmpty() && !hasCode) return
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    LockGateDialog(gate)
    SectionHeader(LOCKED_CHATS_TITLE)
    Text(
        "${ids?.size ?: 0} locked chat(s) on this phone. This only changes where chats are listed; no message is deleted.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = { gate.run(LOCKED_CHATS_TITLE) { ask = true } }, modifier = Modifier.fillMaxWidth()) { Text(RESET_LOCKED_CHATS) }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text(RESET_LOCKED_CHATS) },
            text = { Text("Every locked chat goes back to your chat list. Messages stay as they are.") },
            confirmButton = {
                TextButton(onClick = {
                    ask = false
                    scope.launch { c.lockedChats.resetAll() }
                }) { Text("Unlock all", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
        )
    }
}
