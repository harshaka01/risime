package lk.codegen.risime.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import lk.codegen.risime.data.backup.BackupRecord
import lk.codegen.risime.data.backup.BackupStatus
import lk.codegen.risime.data.backup.PassphraseFloor
import lk.codegen.risime.data.backup.SecretKind
import lk.codegen.risime.net.Backup
import lk.codegen.risime.ui.common.SectionHeader
import lk.codegen.risime.ui.theme.Spacing

// ---- texts (root's upgrade gate drives these; keep them stable) ----

const val BACKUPS_TITLE = "Backups"
const val BACK_UP_NOW = "Back up now"
const val EXPORT_BACKUP = "Export backup file"
const val RESTORE_FROM_FILE = "Restore from file"
const val SERVER_BACKUP_LABEL = "Back up to the RisiMe server"
const val USE_MOBILE_DATA = "Use mobile data"
const val SHOW_RECOVERY_KEY = "Show recovery key"
const val CHANGE_RECOVERY_KEY = "Change recovery key"
const val RESET_BACKUP_KEY = "Reset backup key"
const val RESTORE_TITLE = "Restore your chats"
const val RESTORE_BUTTON = "Restore"
const val SKIP_BUTTON = "Skip"
const val RECOVERY_KEY_LABEL = "Recovery key"
const val PASSPHRASE_LABEL = "Passphrase"
const val USE_PASSPHRASE = "Use passphrase instead"
const val USE_RECOVERY_KEY = "Use recovery key instead"
const val I_SAVED_IT = "I saved it"
const val DONT_UNINSTALL_BACKUP_TEXT = "Don't uninstall RisiMe: your chats are only safe if they're backed up."
const val RECOVERY_KEY_EXPLAIN =
    "This recovery key opens your backups on a new phone. Write it down or save it in a password manager. " +
        "RisiMe can't show it to anyone else and can't recover it for you."
const val BACKUPS_EXPLAIN =
    "Backups are encrypted on this phone. Only your phones and your recovery key (or passphrase) can open them."
const val SKIP_CONFIRM_TEXT =
    "This phone starts without your earlier chats. Your server backup stays, and this phone won't replace it until you back up again from Settings → Backups."
const val PASSPHRASE_EXPLAIN = "Optional: a passphrase you can remember, as another way to open your backups. The recovery key is the stronger option."

/** The two groups the user types back (A7): the 2nd and the last of the seven. */
val CONFIRM_GROUPS = listOf(1, 6)

fun recoveryGroups(key: String): List<String> = key.split('-').map { it.trim() }

/** The typed groups match (case-insensitive; O→0, I/L→1 like the core's input rules). */
fun confirmGroupsMatch(key: String, typed: List<String>): Boolean {
    fun norm(s: String) = s.trim().uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
    val g = recoveryGroups(key)
    return g.size == 7 && typed.size == CONFIRM_GROUPS.size && CONFIRM_GROUPS.zip(typed).all { (i, t) -> norm(t) == norm(g[i]) }
}

/** §22.2 the passphrase floor (core rules + the bundled common-password list); null when it passes. */
fun passphraseProblem(p: String, floor: PassphraseFloor?, common: Set<String>): String? = when {
    p.isBlank() -> "Enter a passphrase"
    floor == PassphraseFloor.TooShort -> "Use at least 14 characters, or 4 words of 3 or more letters"
    floor == PassphraseFloor.PhoneNumber -> "Don't use your phone number in the passphrase"
    p.trim().lowercase() in common -> "That's one of the most common passwords"
    else -> null
}

fun sizeText(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

fun whenText(ms: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

fun recordText(r: BackupRecord?, none: String): String = r?.let { "${whenText(it.at)} · ${sizeText(it.size)}" } ?: none

fun backupText(b: Backup): String {
    val at = lk.codegen.risime.data.backup.BackupTime.ms(b.createdAt)?.let(::whenText) ?: b.createdAt
    return "${b.deviceName ?: "Another phone"} · $at · ${sizeText(b.size)}"
}

// ---- Settings → Backups ----

@Composable
fun BackupsContent(
    s: BackupStatus,
    serverAvailable: Boolean,
    onBackUpNow: () -> Unit,
    onExport: () -> Unit,
    onRestoreFile: () -> Unit,
    onServer: (Boolean) -> Unit,
    onMobileData: (Boolean) -> Unit,
    onShowKey: () -> Unit,
    onChangeKey: () -> Unit,
    onResetKey: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(BACKUPS_EXPLAIN, style = MaterialTheme.typography.bodyMedium)
        if (!s.available) {
            Text("Setting up encryption on this phone… Backups are available once it's ready.", color = MaterialTheme.colorScheme.error)
        }
        SectionHeader("On this phone")
        Text("Last backup: ${recordText(s.lastLocal, "none yet")}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Kept on this phone: they survive updates, not an uninstall. Export a file or turn on server backup to keep a copy elsewhere.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val busy = s.running != null
        Button(onClick = onBackUpNow, enabled = s.available && !busy, modifier = Modifier.fillMaxWidth().testTag("backup_now")) { Text(BACK_UP_NOW) }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onExport, enabled = s.available && !busy, modifier = Modifier.weight(1f)) { Text(EXPORT_BACKUP) }
            OutlinedButton(onClick = onRestoreFile, enabled = s.available && !busy, modifier = Modifier.weight(1f)) { Text(RESTORE_FROM_FILE) }
        }
        s.running?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                Text(it)
            }
        }
        s.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("backup_message")) }
        SectionHeader("Server backup")
        if (!serverAvailable) {
            Text("This server doesn't offer backups.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(SERVER_BACKUP_LABEL, Modifier.weight(1f))
                Switch(checked = s.serverOn, onCheckedChange = onServer, enabled = s.available && !busy, modifier = Modifier.testTag("server_switch"))
            }
            Text("Last server backup: ${recordText(s.lastServer, if (s.serverOn) "none yet" else "off")}", style = MaterialTheme.typography.bodyMedium)
            if (s.serverOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(USE_MOBILE_DATA, Modifier.weight(1f))
                    Switch(checked = s.mobileData, onCheckedChange = onMobileData)
                }
            }
        }
        SectionHeader(RECOVERY_KEY_LABEL)
        TextButton(onClick = onShowKey, enabled = s.available) { Text(SHOW_RECOVERY_KEY) }
        TextButton(onClick = onChangeKey, enabled = s.available && !busy) { Text(CHANGE_RECOVERY_KEY) }
        TextButton(
            onClick = onResetKey, enabled = s.available && !busy,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(RESET_BACKUP_KEY) }
        Text(
            "Forgot the recovery key? Reset backup key deletes your server backups and starts again with a new key.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A dialog for text fields: a plain Dialog + Surface + Column. Material's AlertDialog measures its
 * text slot by intrinsics, which an OutlinedTextField (label cut-out) keeps re-measuring.
 */
@Composable
fun FieldDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest) {
        androidx.compose.material3.Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                text()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    dismissButton()
                    confirmButton()
                }
            }
        }
    }
}

// ---- the recovery key: shown once, confirmed by typing two groups back (A7) ----

@Composable
fun RecoveryKeyContent(key: String, onCopy: () -> Unit, onConfirmed: () -> Unit, onCancel: () -> Unit, confirm: Boolean = true) {
    var typed by remember { mutableStateOf(List(CONFIRM_GROUPS.size) { "" }) }
    var asking by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    FieldDialog(
        onDismissRequest = onCancel,
        title = { Text(if (asking) "Type two groups back" else "Your recovery key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (!asking) {
                    Text(RECOVERY_KEY_EXPLAIN, style = MaterialTheme.typography.bodyMedium)
                    Text(key, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("recovery_key"))
                    TextButton(onClick = onCopy) { Text("Copy") }
                } else {
                    Text("To check you saved it, type group ${CONFIRM_GROUPS[0] + 1} and group ${CONFIRM_GROUPS[1] + 1} of your recovery key.")
                    CONFIRM_GROUPS.forEachIndexed { n, i ->
                        OutlinedTextField(
                            value = typed[n],
                            onValueChange = { v -> typed = typed.toMutableList().also { it[n] = v.take(4) }; wrong = false },
                            label = { Text("Group ${i + 1}") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.testTag("confirm_group_$n"),
                        )
                    }
                    if (wrong) Text("That doesn't match. Look at the key again.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    !confirm -> onConfirmed()
                    !asking -> asking = true
                    confirmGroupsMatch(key, typed) -> onConfirmed()
                    else -> wrong = true
                }
            }) { Text(if (!confirm) "Done" else if (asking) "Confirm" else I_SAVED_IT) }
        },
        dismissButton = {
            TextButton(onClick = { if (asking) asking = false else onCancel() }) { Text(if (asking) "Show key again" else "Cancel") }
        },
    )
}

@Composable
fun PassphraseContent(check: (String) -> String?, onSet: (String) -> Unit, onSkip: () -> Unit) {
    var p by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    FieldDialog(
        onDismissRequest = onSkip,
        title = { Text("Add a passphrase?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(PASSPHRASE_EXPLAIN, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = p, onValueChange = { p = it; problem = null }, label = { Text(PASSPHRASE_LABEL) },
                    visualTransformation = PasswordVisualTransformation(), isError = problem != null,
                    supportingText = { Text(problem ?: "At least 14 characters, or 4 words") }, modifier = Modifier.testTag("passphrase"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                problem = check(p)
                if (problem == null) onSet(p)
            }) { Text("Add passphrase") }
        },
        dismissButton = { TextButton(onClick = onSkip) { Text("Not now") } },
    )
}

/** The recovery key (or passphrase) to open a backup or the account's key record. */
@Composable
fun SecretEntryContent(title: String, text: String, error: String?, busy: Boolean, normalize: (String) -> String?, onSubmit: (String, SecretKind) -> Unit, onCancel: () -> Unit) {
    var secret by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(SecretKind.RecoveryKey) }
    val live = if (kind == SecretKind.RecoveryKey && secret.replace("-", "").replace(" ", "").length >= 28) normalize(secret) else null
    FieldDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it },
                    label = { Text(if (kind == SecretKind.RecoveryKey) RECOVERY_KEY_LABEL else PASSPHRASE_LABEL) },
                    singleLine = kind == SecretKind.RecoveryKey,
                    visualTransformation = if (kind == SecretKind.Passphrase) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(capitalization = if (kind == SecretKind.RecoveryKey) KeyboardCapitalization.Characters else KeyboardCapitalization.None),
                    isError = error != null || live != null,
                    supportingText = { (error ?: live)?.let { Text(it) } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("secret"),
                )
                TextButton(onClick = { kind = if (kind == SecretKind.RecoveryKey) SecretKind.Passphrase else SecretKind.RecoveryKey; secret = "" }, enabled = !busy) {
                    Text(if (kind == SecretKind.RecoveryKey) USE_PASSPHRASE else USE_RECOVERY_KEY)
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                        Text("Opening the backup…")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(secret, kind) }, enabled = !busy && secret.isNotBlank()) { Text(RESTORE_BUTTON) } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } },
    )
}

// ---- §22.7 the first-sign-in restore gate ----

@Composable
fun RestoreGateContent(backup: Backup, busy: String?, message: String?, onRestore: () -> Unit, onFile: () -> Unit, onSkip: () -> Unit) {
    var confirmSkip by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(RESTORE_TITLE, style = MaterialTheme.typography.headlineSmall)
        Text("A backup of your chats is on the server:", style = MaterialTheme.typography.bodyMedium)
        Text(backupText(backup), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("gate_backup"))
        Text("You'll need your recovery key (or passphrase).", style = MaterialTheme.typography.bodySmall)
        if (busy != null) {
            CircularProgressIndicator()
            Text(busy)
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = onRestore, enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text(RESTORE_BUTTON) }
        FilledTonalButton(onClick = onFile, enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text(RESTORE_FROM_FILE) }
        TextButton(onClick = { confirmSkip = true }, enabled = busy == null) { Text(SKIP_BUTTON) }
    }
    if (confirmSkip) {
        AlertDialog(
            onDismissRequest = { confirmSkip = false },
            title = { Text("Skip restore?") },
            text = { Text(SKIP_CONFIRM_TEXT) },
            confirmButton = { TextButton(onClick = { confirmSkip = false; onSkip() }) { Text(SKIP_BUTTON) } },
            dismissButton = { TextButton(onClick = { confirmSkip = false }) { Text("Cancel") } },
        )
    }
}
