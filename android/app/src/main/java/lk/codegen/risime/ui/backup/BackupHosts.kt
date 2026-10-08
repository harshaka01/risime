package lk.codegen.risime.ui.backup

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.backup.BackupException
import lk.codegen.risime.data.backup.BackupManager
import lk.codegen.risime.data.backup.RestoreGate
import lk.codegen.risime.data.backup.RestoreOutcome
import lk.codegen.risime.data.backup.RestoreSource
import lk.codegen.risime.data.backup.SecretKind
import lk.codegen.risime.data.backup.ServerSetup
import lk.codegen.risime.data.backup.UploadOutcome
import lk.codegen.risime.ui.common.RisiTopBar

/** The dialogs of the Backups screen and the restore gate. */
sealed interface BackupFlow {
    data object None : BackupFlow

    data class ShowKey(val key: String, val confirm: Boolean, val next: BackupFlow = None) : BackupFlow

    data object Passphrase : BackupFlow

    data class Unlock(val error: String? = null, val busy: Boolean = false) : BackupFlow

    data class Secret(val source: RestoreSource, val error: String? = null, val busy: Boolean = false) : BackupFlow

    data class Replace(val deviceName: String) : BackupFlow

    data object ConfirmReset : BackupFlow

    data object ConfirmOff : BackupFlow

    data class Info(val title: String, val text: String) : BackupFlow
}

/** §22.4 the exported file: MediaStore Downloads on API 29+ (no picker); 26–28 use the system picker. */
suspend fun exportToDownloads(context: Context, c: AppContainer): Boolean = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < 29) return@withContext false
    val name = BackupManager.exportName(System.currentTimeMillis())
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
    val ok = runCatching { resolver.openOutputStream(uri)?.let { c.backups.export(it) } ?: false }.getOrDefault(false)
    if (ok) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    } else {
        resolver.delete(uri, null, null)
    }
    ok
}

/** Copy marks the clip as sensitive (A7): hidden from the clipboard preview and keyboard suggestions. */
fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText("RisiMe recovery key", text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(if (Build.VERSION.SDK_INT >= 33) android.content.ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

private fun uploadFlow(r: UploadOutcome): Pair<BackupFlow, String?> = when (r) {
    is UploadOutcome.Done -> BackupFlow.None to "Backed up"
    is UploadOutcome.OtherDevice -> BackupFlow.Replace(r.deviceName) to null
    UploadOutcome.NeedsUnlock -> BackupFlow.Unlock() to null
    is UploadOutcome.Skipped -> BackupFlow.None to null
    is UploadOutcome.Failed -> BackupFlow.None to r.message
}

/** Settings → Backups (§22.7). */
@Composable
fun BackupsScreen(c: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.backups.status.collectAsState()
    var serverAvailable by remember { mutableStateOf(false) }
    var flow by remember { mutableStateOf<BackupFlow>(BackupFlow.None) }
    LaunchedEffect(Unit) {
        c.backups.refresh(null)
        serverAvailable = withContext(Dispatchers.IO) { runCatching { c.backupServer.switchOn() }.getOrDefault(false) }
    }
    fun note(text: String?) = c.backups.refresh(text)
    fun upload(block: suspend () -> UploadOutcome) = scope.launch {
        val (f, msg) = uploadFlow(block())
        flow = f
        msg?.let(::note)
    }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.let { c.backups.export(it) } } }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val staged = runCatching { withContext(Dispatchers.IO) { c.backups.stageFile(context.contentResolver.openInputStream(uri)!!) } }.getOrNull()
                ?: return@launch note("Couldn't read that file")
            val src = RestoreSource.LocalFile(staged)
            when (val r = c.backups.restore(src)) {
                RestoreOutcome.NeedsSecret -> flow = BackupFlow.Secret(src)
                is RestoreOutcome.Done -> flow = BackupFlow.Info("Chats restored", "Restored ${r.result.imported} messages.")
                is RestoreOutcome.Failed -> note(r.message)
            }
        }
    }
    var pendingKey by remember { mutableStateOf(false) }
    val credential = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && pendingKey) scope.launch { c.backups.recoveryKey()?.let { flow = BackupFlow.ShowKey(it, confirm = false) } }
        pendingKey = false
    }
    Scaffold(topBar = { RisiTopBar(title = BACKUPS_TITLE, onBack = onBack) }, contentWindowInsets = WindowInsets(0)) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            BackupsContent(
                s, serverAvailable,
                onBackUpNow = { upload { c.backups.backupNow() } },
                onExport = {
                    scope.launch {
                        if (Build.VERSION.SDK_INT >= 29) {
                            if (!exportToDownloads(context, c)) note("Export failed") else note("Saved to Downloads: ${BackupManager.exportName(System.currentTimeMillis())}")
                            if (!s.keySetUp) c.backups.recoveryKey()?.let { flow = BackupFlow.ShowKey(it, confirm = true) }
                        } else {
                            createDoc.launch(BackupManager.exportName(System.currentTimeMillis()))
                        }
                    }
                },
                onRestoreFile = { openDoc.launch(arrayOf("*/*")) },
                onServer = { on ->
                    if (!on) {
                        flow = BackupFlow.ConfirmOff
                    } else {
                        scope.launch {
                            when (val r = c.backups.startServerSetup()) {
                                is ServerSetup.ShowKey -> flow = BackupFlow.ShowKey(r.recoveryKey, confirm = true, next = BackupFlow.Passphrase)
                                ServerSetup.Unlock -> flow = BackupFlow.Unlock()
                                is ServerSetup.Failed -> note(r.message)
                            }
                        }
                    }
                },
                onMobileData = c.backups::setMobileData,
                onShowKey = {
                    val km = context.getSystemService(KeyguardManager::class.java)
                    @Suppress("DEPRECATION")
                    val i = km?.takeIf { it.isDeviceSecure }?.createConfirmDeviceCredentialIntent("Show recovery key", "Confirm it's you")
                    if (i != null) {
                        pendingKey = true
                        credential.launch(i)
                    } else {
                        scope.launch { c.backups.recoveryKey()?.let { flow = BackupFlow.ShowKey(it, confirm = false) } }
                    }
                },
                onChangeKey = {
                    scope.launch {
                        val (key, err) = c.backups.changeRecoveryKey()
                        if (key != null) flow = BackupFlow.ShowKey(key, confirm = true)
                        note(err ?: "New recovery key. Backup files made before still open with the old key.")
                    }
                },
                onResetKey = { flow = BackupFlow.ConfirmReset },
            )
        }
    }
    BackupDialogs(c, flow, { flow = it }, ::note)
}

@Composable
private fun BackupDialogs(c: AppContainer, flow: BackupFlow, set: (BackupFlow) -> Unit, note: (String?) -> Unit) {
    val context = LocalContext.current
    val session by c.sessionStore.session.collectAsState(null)
    val phone = session?.user?.phone
    val scope = rememberCoroutineScope()
    fun upload(block: suspend () -> UploadOutcome) = scope.launch {
        val (f, msg) = uploadFlow(block())
        set(f)
        msg?.let(note)
    }
    when (flow) {
        BackupFlow.None -> Unit
        is BackupFlow.ShowKey -> RecoveryKeyContent(
            flow.key, onCopy = { copySensitive(context, flow.key) },
            onConfirmed = {
                c.backups.markKeyShown()
                set(flow.next)
            },
            onCancel = { set(BackupFlow.None) },
            confirm = flow.confirm,
        )
        BackupFlow.Passphrase -> PassphraseContent(
            check = { p ->
                val floor = c.backupTools()?.passphraseFloor(p, phone)
                passphraseProblem(p, floor, c.commonPasswords)
            },
            onSet = { p -> upload { c.backups.finishServerSetup(p) } },
            onSkip = { upload { c.backups.finishServerSetup(null) } },
        )
        is BackupFlow.Unlock -> SecretEntryContent(
            title = "Unlock your backups", busy = flow.busy, error = flow.error,
            text = "Your account already has a backup key (from another phone or before a reinstall). Enter its recovery key or passphrase.",
            normalize = { input -> normalizeProblem(c, input) },
            onSubmit = { secret, kind ->
                set(flow.copy(busy = true, error = null))
                scope.launch {
                    val err = c.backups.unlockAccountKey(secret, kind)
                    if (err != null) set(BackupFlow.Unlock(err)) else upload { c.backups.backupNow() }
                }
            },
            onCancel = { set(BackupFlow.None) },
        )
        is BackupFlow.Secret -> SecretEntryContent(
            title = RESTORE_TITLE, busy = flow.busy, error = flow.error,
            text = "Enter the recovery key (or passphrase) of this backup.",
            normalize = { input -> normalizeProblem(c, input) },
            onSubmit = { secret, kind ->
                set(flow.copy(busy = true, error = null))
                scope.launch {
                    when (val r = c.backups.restore(flow.source, secret, kind)) {
                        is RestoreOutcome.Done -> set(BackupFlow.Info("Chats restored", "Restored ${r.result.imported} messages."))
                        RestoreOutcome.NeedsSecret -> set(BackupFlow.Secret(flow.source, "Enter the recovery key"))
                        is RestoreOutcome.Failed -> set(BackupFlow.Secret(flow.source, r.message))
                    }
                }
            },
            onCancel = { set(BackupFlow.None) },
        )
        is BackupFlow.Replace -> AlertDialog(
            onDismissRequest = { set(BackupFlow.None) },
            title = { Text("Back up this phone instead?") },
            text = { Text("Back up this phone instead of ${flow.deviceName}? Only one phone backs up your chats to the server.") },
            confirmButton = { TextButton(onClick = { upload { c.backups.replaceDevice() } }) { Text("Back up this phone") } },
            dismissButton = { TextButton(onClick = { set(BackupFlow.None) }) { Text("Cancel") } },
        )
        BackupFlow.ConfirmReset -> AlertDialog(
            onDismissRequest = { set(BackupFlow.None) },
            title = { Text("Reset backup key?") },
            text = { Text("This deletes your backups on the server and the backup key on this phone. Backup files you exported before can't be opened without the old recovery key.") },
            confirmButton = { TextButton(onClick = { set(BackupFlow.None); scope.launch { note(c.backups.resetBackupKey()) } }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { set(BackupFlow.None) }) { Text("Cancel") } },
        )
        BackupFlow.ConfirmOff -> AlertDialog(
            onDismissRequest = { set(BackupFlow.None) },
            title = { Text("Turn off server backup?") },
            text = { Text("Your last server backups stay unless you delete them. Local backups on this phone continue.") },
            confirmButton = { TextButton(onClick = { set(BackupFlow.None); scope.launch { note(c.backups.turnOffServer(delete = false)) } }) { Text("Turn off") } },
            dismissButton = { TextButton(onClick = { set(BackupFlow.None); scope.launch { note(c.backups.turnOffServer(delete = true)) } }) { Text("Turn off and delete server backup") } },
        )
        is BackupFlow.Info -> AlertDialog(
            onDismissRequest = { set(BackupFlow.None) },
            title = { Text(flow.title) },
            text = { Text(flow.text) },
            confirmButton = { TextButton(onClick = { set(BackupFlow.None) }) { Text("OK") } },
        )
    }
}

/** The core's live check of a typed recovery key: null when fine, else what to fix. */
private fun normalizeProblem(c: AppContainer, input: String): String? = try {
    c.backupTools()?.normalizeRecoveryKey(input)
    null
} catch (e: BackupException) {
    c.backups.describe(e)
}

/**
 * §22.7 the first-sign-in gate: on a fresh install with a server backup, "Restore your chats"
 * replaces the chat list until the user restored or skipped (no server backup meanwhile).
 */
@Composable
fun RestoreGateHost(c: AppContainer, content: @Composable () -> Unit) {
    val gate by c.backups.gate.collectAsState()
    LaunchedEffect(gate) {
        while (c.backups.gate.value == RestoreGate.Checking) {
            runCatching { c.backups.checkGate() }
            if (c.backups.gate.value != RestoreGate.Checking) break
            delay(30_000)
        }
    }
    val offer = gate as? RestoreGate.Offer
    if (offer == null) {
        content()
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var flow by remember { mutableStateOf<BackupFlow>(BackupFlow.None) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val newest = offer.backups.firstOrNull { it.current } ?: offer.backups.first()
    suspend fun engineReady(): Boolean {
        if (c.mlsEngine?.backupKeys != null) return true
        busy = "Setting up encryption on this phone…"
        val ok = withTimeoutOrNull(90_000) { c.mlsEngineState.first { it?.backupKeys != null } } != null
        busy = null
        if (!ok) message = "Encryption isn't ready yet. Check your connection and try again."
        return ok
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            if (!engineReady()) return@launch
            val staged = runCatching { withContext(Dispatchers.IO) { c.backups.stageFile(context.contentResolver.openInputStream(uri)!!) } }.getOrNull()
                ?: run { message = "Couldn't read that file"; return@launch }
            val src = RestoreSource.LocalFile(staged)
            when (val r = c.backups.restore(src)) {
                RestoreOutcome.NeedsSecret -> flow = BackupFlow.Secret(src)
                is RestoreOutcome.Done -> c.backups.openGate()
                is RestoreOutcome.Failed -> message = r.message
            }
        }
    }
    RestoreGateContent(
        newest, busy, message,
        onRestore = { scope.launch { if (engineReady()) flow = BackupFlow.Secret(RestoreSource.Server(newest)) } },
        onFile = { openDoc.launch(arrayOf("*/*")) },
        onSkip = { c.backups.openGate() },
    )
    BackupDialogs(c, flow, { flow = it }) { t -> message = t }
}
