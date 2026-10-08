package lk.codegen.risime.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

const val LOGOUT_CONFIRM_TITLE = "Log out?"
const val LOGOUT_CONFIRM_TEXT =
    "Your chats stay on this phone, encrypted, and come back when you sign in again with this account."

/** Decision 050: the separate, clearly labelled wipe (menu item, dialog title and button). */
const val LOGOUT_DELETE_LABEL = "Log out and delete chats from this phone"
const val LOGOUT_DELETE_TITLE = "Log out and delete chats?"
const val LOGOUT_DELETE_TEXT =
    "This deletes all chats, photos and encryption keys on this phone. Signing in again starts with " +
        "no earlier messages on this phone. This can't be undone."

/** §22.7: a backup is made before the wipe; the user can keep a file of their own. */
const val LOGOUT_DELETE_BACKUP_TEXT =
    "A backup is made first and uploaded if server backup is on. To keep a copy yourself, save a backup file before you continue."
const val SAVE_BACKUP_FILE = "Save a backup file"

/** "Save a backup file" (export to Downloads; the system picker on Android 8–9). */
@Composable
fun SaveBackupFileButton() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val c = (context.applicationContext as? lk.codegen.risime.RisiMeApp)?.container ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var state by remember { mutableStateOf<String?>(null) }
    val create = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.let { c.backups.export(it) } ?: false }
            state = if (ok) "Backup file saved" else "Couldn't save the backup file"
        }
    }
    TextButton(
        enabled = state == null || state == "Couldn't save the backup file",
        onClick = {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                state = "Saving…"
                scope.launch { state = if (lk.codegen.risime.ui.backup.exportToDownloads(context, c)) "Saved to Downloads" else "Couldn't save the backup file" }
            } else {
                create.launch(lk.codegen.risime.data.backup.BackupManager.exportName(System.currentTimeMillis()))
            }
        },
    ) { Text(state ?: SAVE_BACKUP_FILE) }
}

/**
 * The confirmation itself (stateless); logging out is never one tap. [deleteChats] picks the
 * labelled wipe; otherwise it is the plain logout that keeps the chats (decision 050).
 */
@Composable
fun LogoutConfirmDialog(
    onConfirm: (lk.codegen.risime.data.UserConfirmation) -> Unit,
    onDismiss: () -> Unit,
    deleteChats: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deleteChats) LOGOUT_DELETE_TITLE else LOGOUT_CONFIRM_TITLE) },
        text = {
            if (!deleteChats) {
                Text(LOGOUT_CONFIRM_TEXT)
            } else {
                androidx.compose.foundation.layout.Column {
                    Text(LOGOUT_DELETE_TEXT)
                    Text(LOGOUT_DELETE_BACKUP_TEXT, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    SaveBackupFileButton()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(lk.codegen.risime.data.UserConfirmation.fromConfirmDialog(deleteChats)) }) {
                Text(if (deleteChats) "Delete chats and log out" else "Log out")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Every "Log out" / "Sign out" goes through [LogoutConfirmDialog] (P0 nightly.10). [content] gets
 * the function that opens it; keep [content] in the composition while the dialog is open.
 */
@Composable
fun ConfirmLogout(onConfirm: (lk.codegen.risime.data.UserConfirmation) -> Unit, content: @Composable (askLogout: () -> Unit) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    content { asking = true }
    if (asking) {
        LogoutConfirmDialog(
            onConfirm = { confirmed ->
                asking = false
                onConfirm(confirmed)
            },
            onDismiss = { asking = false },
        )
    }
}

fun accountSwitchText(previousName: String, newName: String) =
    "Chats from $previousName are on this phone. Signing in as $newName deletes them. Continue?"

/** A different account signs in: ask before deleting the previous account's chats (cancel keeps them). */
@Composable
fun AccountSwitchDialog(previousName: String, newName: String, onContinue: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Switch account?") },
        text = { Text(accountSwitchText(previousName, newName)) },
        confirmButton = { TextButton(onClick = onContinue) { Text("Delete chats and continue") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
