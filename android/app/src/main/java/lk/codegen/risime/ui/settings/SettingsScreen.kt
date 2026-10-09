package lk.codegen.risime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lk.codegen.risime.BuildConfig
import lk.codegen.risime.net.PROTOCOL_VERSION
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.common.SectionHeader
import lk.codegen.risime.ui.theme.Sizes
import lk.codegen.risime.ui.theme.Spacing

@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, onBackups: () -> Unit = {}, onNotificationHealth: () -> Unit = {}, onRisiKnows: () -> Unit = {}, onMyPromises: () -> Unit = {}, onRisiSkills: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { RisiTopBar(title = "Settings", onBack = onBack) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            ProfileSection(s, vm)
            HorizontalDivider()
            ServerSection(s, vm)
            HorizontalDivider()
            if (BuildConfig.DEBUG) {
                DebugAuthSection(s.authOverride, vm::setAuthOverride)
                HorizontalDivider()
            }
            SectionHeader(lk.codegen.risime.ui.backup.BACKUPS_TITLE)
            Text(
                "Your chats, encrypted on this phone: daily and before every update. Export a file or back up to the server.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onBackups, modifier = Modifier.fillMaxWidth()) { Text(lk.codegen.risime.ui.backup.MANAGE_BACKUPS) }
            HorizontalDivider()
            lk.codegen.risime.calls.CallsSettingsSection()
            HorizontalDivider()
            SectionHeader("Notifications")
            Text(
                "Check what could stop messages and calls from reaching you while RisiMe is closed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onNotificationHealth, modifier = Modifier.fillMaxWidth()) { Text(NOTIFICATION_HEALTH_TITLE) }
            HorizontalDivider()
            // Privacy: the optional fingerprint lock (decision 064; only with a usable strong biometric) and history sharing.
            val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as? lk.codegen.risime.RisiMeApp)?.container
            // A lock that is on stays reachable while the sensor is busy (it only turns itself off when no fingerprint is left).
            val lockAvailable = lk.codegen.risime.ui.lock.rememberLockAvailable() || container?.appLock?.settings?.value?.enabled == true
            // §24.11 "What Risi knows about me" and "My promises": only while the two tabs are on.
            val tabsOn = container?.chatTabs?.uiOn?.collectAsStateWithLifecycle()?.value == true
            if ((lockAvailable && container != null) || BuildConfig.HISTORY_SHARE_ENABLED || tabsOn) {
                SectionHeader("Privacy")
                if (container != null) lk.codegen.risime.ui.lock.FingerprintLockSection(container, lockAvailable, showHeader = false)
                if (BuildConfig.HISTORY_SHARE_ENABLED) lk.codegen.risime.ui.history.HistoryPrivacySection(showHeader = false)
                if (tabsOn) {
                    OutlinedButton(onClick = onRisiKnows, modifier = Modifier.fillMaxWidth().testTag("settings_risi_knows")) { Text(RISI_KNOWS_TITLE) }
                    OutlinedButton(onClick = onMyPromises, modifier = Modifier.fillMaxWidth().testTag("settings_my_promises")) { Text(MY_PROMISES_TITLE) }
                    // §26.9: Settings → Risi skills only while the server switch is on and this phone advertises risi_skills.
                    val skillsOn = container?.risiSkills?.on?.collectAsStateWithLifecycle()?.value == true &&
                        container.risiTools.on.collectAsStateWithLifecycle().value
                    if (skillsOn) OutlinedButton(onClick = onRisiSkills, modifier = Modifier.fillMaxWidth().testTag("settings_risi_skills")) { Text(RISI_SKILLS_TITLE) }
                }
                HorizontalDivider()
            }
            if (container != null) {
                lk.codegen.risime.ui.chats.LockedChatsResetSection(container)
                HorizontalDivider()
            }
            AboutSection()
            HorizontalDivider()
            OutlinedButton(
                onClick = vm::askLogout,
                enabled = !s.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Log out") }
            androidx.compose.material3.TextButton(
                onClick = vm::askLogoutAndDelete,
                enabled = !s.busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(lk.codegen.risime.ui.common.LOGOUT_DELETE_LABEL) }
            Spacer(Modifier.height(24.dp))
        }
    }

    s.confirmServer?.let { url ->
        AlertDialog(
            onDismissRequest = vm::cancelServerChange,
            title = { Text("Switch server?") },
            text = {
                Column {
                    Text("You'll be logged out, because your login belongs to the current server. " +
                        "Chats on this device are removed. New server: $url")
                    Text(lk.codegen.risime.ui.common.LOGOUT_DELETE_BACKUP_TEXT, style = MaterialTheme.typography.bodySmall)
                    lk.codegen.risime.ui.common.SaveBackupFileButton()
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmServerChange) { Text("Log out and switch") } },
            dismissButton = { TextButton(onClick = vm::cancelServerChange) { Text("Cancel") } },
        )
    }
    if (s.confirmLogout || s.confirmDeleteChats) {
        lk.codegen.risime.ui.common.LogoutConfirmDialog(onConfirm = vm::logout, onDismiss = vm::cancelLogout, deleteChats = s.confirmDeleteChats)
    }
}


@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProfileSection(s: SettingsUiState, vm: SettingsViewModel) {
    SectionHeader("Profile")
    val user = s.user ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        InitialsAvatar(user.displayName, size = Sizes.avatarLarge, photoKey = user.id)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(user.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(user.company, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    ProfilePhotoControls(user.id)
    OutlinedTextField(
        value = s.nameDraft,
        onValueChange = vm::onNameDraft,
        label = { Text("Display name") },
        singleLine = true,
        isError = s.nameError != null,
        supportingText = {
            when {
                s.nameError != null -> Text(s.nameError)
                s.nameSaved -> Text("Saved")
                else -> Text("Shown to your contacts")
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.saveName() }),
        modifier = Modifier.fillMaxWidth(),
    )
    FilledTonalButton(
        onClick = vm::saveName,
        enabled = !s.busy && s.nameDraft.trim() != user.displayName,
    ) { Text("Save name") }
    InfoRow("Phone", user.phone)
    if (!user.phoneConfirmed) lk.codegen.risime.ui.auth.PhoneNotVerifiedNote()
    InfoRow("Company", user.company)
}

@Composable
private fun ServerSection(s: SettingsUiState, vm: SettingsViewModel) {
    SectionHeader("Server")
    OutlinedTextField(
        value = s.serverDraft,
        onValueChange = vm::onServerDraft,
        label = { Text("Server URL") },
        singleLine = true,
        isError = s.serverError != null,
        supportingText = {
            Text(s.serverError ?: "Changing the server logs you out. Default: ${BuildConfig.DEFAULT_SERVER_URL}")
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.saveServer() }),
        modifier = Modifier.fillMaxWidth(),
    )
    FilledTonalButton(
        onClick = vm::saveServer,
        enabled = !s.busy && s.serverDraft.trim().trimEnd('/') != s.serverUrl,
    ) { Text("Change server") }
}

/** Decision 048: About states the real rule; each chat shows its own state. */
const val ABOUT_E2EE_TEXT =
    "Chats are end-to-end encrypted (MLS) once everyone in them runs a current RisiMe. Each chat shows its state."

@Composable
internal fun AboutSection() {
    SectionHeader("About")
    InfoRow("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    UpdateCheckSection()
    InfoRow("Build", if (BuildConfig.DEBUG) "Debug" else "Release")
    InfoRow("Protocol", "v$PROTOCOL_VERSION")
    if (BuildConfig.DEBUG) CryptoSelfTest()
    Text(
        ABOUT_E2EE_TEXT,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Debug builds only: force the login screen's sign-in mode (applies after sign-out). */
@Composable
private fun DebugAuthSection(current: String, onPick: (String) -> Unit) {
    SectionHeader("Sign-in mode (debug)")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        listOf("AUTO" to "Server decides", "FORCE_OIDC" to "RisiCloud", "FORCE_DEV" to "Developer OTP").forEach { (v, label) ->
            androidx.compose.material3.FilterChip(
                selected = current == v,
                onClick = { onPick(v) },
                label = { Text(label) },
            )
        }
    }
}

/** Debug only (decision 031): proves the native MLS core loads and runs on this device. */
@Composable
private fun CryptoSelfTest() {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var result by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var running by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            running = true
            result = null
            scope.launch {
                result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    lk.codegen.risime.CryptoProbes.runSelfTest(lk.codegen.risime.CryptoProbes.get())
                }
                running = false
            }
        },
        enabled = !running,
    ) { Text(if (running) "Running crypto self-test…" else "Crypto self-test") }
    result?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (it.startsWith("ok")) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
    }
}
