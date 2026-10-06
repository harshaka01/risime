package lk.codegen.risime.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

const val LOGOUT_CONFIRM_TITLE = "Log out and delete chats?"
const val LOGOUT_CONFIRM_TEXT =
    "Chats on this phone are deleted. To keep them, cancel: your chats stay when you just sign in again."

/** The confirmation itself (stateless): "Log out" deletes local chats, so it is never one tap. */
@Composable
fun LogoutConfirmDialog(onConfirm: (lk.codegen.risime.data.UserConfirmation) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(LOGOUT_CONFIRM_TITLE) },
        text = { Text(LOGOUT_CONFIRM_TEXT) },
        confirmButton = { TextButton(onClick = { onConfirm(lk.codegen.risime.data.UserConfirmation.fromConfirmDialog()) }) { Text("Log out") } },
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
