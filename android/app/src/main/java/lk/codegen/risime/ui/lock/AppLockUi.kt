package lk.codegen.risime.ui.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.R
import lk.codegen.risime.data.lock.AppLockSettings
import lk.codegen.risime.data.lock.BiometricStatus
import lk.codegen.risime.data.lock.AutoLock
import lk.codegen.risime.ui.common.SectionHeader
import lk.codegen.risime.ui.theme.Spacing
import lk.codegen.risime.ui.theme.WordmarkStyle

const val FINGERPRINT_LOCK = "Fingerprint lock"
const val UNLOCK_WITH_FINGERPRINT = "Unlock with fingerprint"
const val AUTO_LOCK_TITLE = "Automatically lock"
const val SHOW_CONTENT_TITLE = "Show content in notifications"

const val FINGERPRINT_UNAVAILABLE = "Fingerprint unavailable — try again"

/** `canAuthenticate(BIOMETRIC_STRONG)` now; an error asking is "unavailable now" (never "gone"). */
fun strongBiometricStatus(context: Context): BiometricStatus =
    runCatching { BiometricStatus.of(BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) }.getOrDefault(BiometricStatus.UNAVAILABLE_NOW)

/** Decision 064: the lock is offered only when a strong biometric is enrolled and usable. */
fun strongBiometricAvailable(context: Context): Boolean = strongBiometricStatus(context) == BiometricStatus.AVAILABLE

tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/**
 * The locked screen (decision 064): the RisiMe logo and "Unlock with fingerprint". It prompts by
 * itself every time it is RESUMED ([onAutoPrompt]; never while the activity is stopped: the
 * nightly.35 lock-out), and on every tap. [message]: the last error; [showPin]: the
 * [USE_PHONE_PIN] button (after any error), so the user always has a way in.
 */
@Composable
fun AppLockScreen(
    onUnlock: () -> Unit,
    autoPrompt: Boolean = true,
    message: String? = null,
    onAutoPrompt: () -> Unit = onUnlock,
    showPin: Boolean = false,
    onUsePin: () -> Unit = {},
) {
    // FLAG_SECURE here only (and on the Locked chats folder and an open locked chat): never in a share or screenshot.
    SecureWindow(SecureScreen.APP_LOCK)
    if (autoPrompt) {
        LifecycleResumeEffect(Unit) {
            onAutoPrompt()
            onPauseOrDispose { }
        }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    ) {
        Image(painterResource(R.drawable.brand_mark), "RisiMe logo", Modifier.size(112.dp))
        Text("RisiMe", style = WordmarkStyle, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
        Text("RisiMe is locked", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(UNLOCK_WITH_FINGERPRINT) }
        if (showPin) {
            OutlinedButton(onClick = onUsePin, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(USE_PHONE_PIN) }
        }
    }
}

/** Settings → Privacy rows. Nothing at all when the phone has no usable strong biometric. */
@Composable
fun FingerprintLockContent(
    available: Boolean,
    settings: AppLockSettings,
    onToggle: (Boolean) -> Unit,
    onAutoLock: (AutoLock) -> Unit,
    onShowContent: (Boolean) -> Unit,
) {
    if (!available) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(FINGERPRINT_LOCK, style = MaterialTheme.typography.bodyMedium)
            Text(
                "When on, your fingerprint is needed to open RisiMe. You can still answer calls.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = settings.enabled, onCheckedChange = onToggle)
    }
    if (settings.enabled) {
        Text(AUTO_LOCK_TITLE, style = MaterialTheme.typography.titleSmall)
        AutoLock.entries.forEach { a ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = settings.autoLock == a, role = Role.RadioButton, onClick = { onAutoLock(a) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.autoLock == a, onClick = null)
                Text(a.label, Modifier.padding(start = Spacing.sm), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.fillMaxWidth().clickable { onShowContent(!settings.showContent) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(SHOW_CONTENT_TITLE, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Off: notifications say \"New message\" without the name or text.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.showContent, onCheckedChange = onShowContent)
        }
    }
}

/** True when this phone offers the lock now (checked when the screen opens). */
@Composable
fun rememberLockAvailable(): Boolean {
    val ctx = LocalContext.current
    return remember { strongBiometricAvailable(ctx) }
}

/**
 * The host: wires [FingerprintLockContent] to [AppContainer.appLock]; turning it on asks once
 * (the same prompt rules as the lock screen: a fingerprint, or the phone's screen lock).
 */
@Composable
fun FingerprintLockSection(c: AppContainer, available: Boolean, showHeader: Boolean = true) {
    if (!available) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.appLock.settings.collectAsState()
    val authUi = remember(ctx) { (ctx.findFragmentActivity() as? lk.codegen.risime.MainActivity)?.authUi }
    val turnOnMessage = authUi?.lockTurnOn?.message?.collectAsState()
    if (showHeader) SectionHeader("Privacy")
    FingerprintLockContent(
        available = true,
        settings = s ?: AppLockSettings(),
        onToggle = { on ->
            if (!on) {
                scope.launch { c.appLock.setEnabled(false) }
            } else {
                authUi?.turnOnLock()
            }
        },
        onAutoLock = { a -> scope.launch { c.appLock.setAutoLock(a) } },
        onShowContent = { v -> scope.launch { c.appLock.setShowContent(v) } },
    )
    turnOnMessage?.value?.takeIf { s?.enabled != true }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}
