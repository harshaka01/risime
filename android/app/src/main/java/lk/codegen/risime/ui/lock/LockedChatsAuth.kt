package lk.codegen.risime.ui.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

const val LOCK_CHAT_LABEL = "Lock chat"
const val UNLOCK_CHAT_LABEL = "Unlock chat"
const val LOCKED_CHATS_TITLE = "Locked chats"
const val SCREEN_LOCK_NEEDED_TITLE = "Set a screen lock first"
const val SCREEN_LOCK_NEEDED_TEXT =
    "Locked chats are opened with your fingerprint, PIN, pattern or password. Set a screen lock in your phone's Settings, then try again."

/** What the phone can confirm with right now, for the Locked chats gate. */
enum class ChatLockAuthStatus { READY, NEEDS_SCREEN_LOCK }

/** Confirms the user (fingerprint, or the PIN/pattern/password) before a chat is locked or the folder opens. */
interface ChatLockAuth {
    fun status(): ChatLockAuthStatus

    /** True when the user confirmed; false on cancel or failure. */
    suspend fun confirm(title: String, subtitle: String? = null): Boolean
}

/**
 * The authenticators for the Locked chats gate: BIOMETRIC_STRONG | DEVICE_CREDENTIAL (API 30+, so a
 * phone with no fingerprint can use its PIN/pattern). Android 8-10 can't combine them in one
 * prompt, so there only a strong biometric works.
 */
fun lockAuthenticators(sdk: Int): Int = if (sdk >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG

fun chatLockStatus(canAuthenticate: Int): ChatLockAuthStatus =
    if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) ChatLockAuthStatus.READY else ChatLockAuthStatus.NEEDS_SCREEN_LOCK

class BiometricChatLockAuth(private val context: Context, private val sdk: Int = Build.VERSION.SDK_INT) : ChatLockAuth {
    override fun status(): ChatLockAuthStatus =
        runCatching { chatLockStatus(BiometricManager.from(context).canAuthenticate(lockAuthenticators(sdk))) }
            .getOrDefault(ChatLockAuthStatus.NEEDS_SCREEN_LOCK)

    override suspend fun confirm(title: String, subtitle: String?): Boolean {
        val activity = context.findFragmentActivity() ?: return false
        if (status() != ChatLockAuthStatus.READY) return false
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { subtitle?.let { setSubtitle(it) } }
            .setAllowedAuthenticators(lockAuthenticators(sdk))
            .apply { if (sdk < 30) setNegativeButtonText("Cancel") } // not allowed together with DEVICE_CREDENTIAL
            .build()
        return suspendCancellableCoroutine { cont ->
            val p = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            runCatching { p.authenticate(info) }.onFailure { if (cont.isActive) cont.resume(false) }
            cont.invokeOnCancellation { runCatching { p.cancelAuthentication() } }
        }
    }
}

/** Tests (and previews) replace the real prompt through this. */
val LocalChatLockAuth = compositionLocalOf<ChatLockAuth?> { null }

@Composable
fun rememberChatLockAuth(): ChatLockAuth {
    val override = LocalChatLockAuth.current
    val ctx = LocalContext.current
    return override ?: remember(ctx) { BiometricChatLockAuth(ctx) }
}

/**
 * Runs an action only after the confirmation; explains when the phone has no screen lock at all.
 * One per screen: [run] from a click, [LockGateDialog] once in the layout.
 */
class LockGate(private val scope: CoroutineScope, private val auth: ChatLockAuth) {
    var needsScreenLock by mutableStateOf(false)
        internal set
    private var busy = false

    fun run(title: String, subtitle: String? = null, onConfirmed: () -> Unit) {
        if (busy) return
        if (auth.status() != ChatLockAuthStatus.READY) {
            needsScreenLock = true
            return
        }
        busy = true
        scope.launch {
            try {
                if (auth.confirm(title, subtitle)) onConfirmed()
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun rememberLockGate(): LockGate {
    val scope = rememberCoroutineScope()
    val auth = rememberChatLockAuth()
    return remember(scope, auth) { LockGate(scope, auth) }
}

@Composable
fun LockGateDialog(gate: LockGate) {
    if (!gate.needsScreenLock) return
    AlertDialog(
        onDismissRequest = { gate.needsScreenLock = false },
        title = { Text(SCREEN_LOCK_NEEDED_TITLE) },
        text = { Text(SCREEN_LOCK_NEEDED_TEXT) },
        confirmButton = { TextButton(onClick = { gate.needsScreenLock = false }) { Text("OK") } },
    )
}

/** Handed to a chat screen: whether this chat is locked and the menu action (confirmation included). */
class ChatLockControl(val locked: Boolean, val onToggle: () -> Unit)

/** A locked chat reached without the folder (notification, call record, link): confirm first, then it opens. */
@Composable
fun LockedChatGate(gate: LockGate, onUnlock: () -> Unit, onBack: () -> Unit, loading: Boolean = false) {
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Text("This chat is locked", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        if (!loading) {
            androidx.compose.material3.Button(onClick = { gate.run(LOCKED_CHATS_TITLE, onConfirmed = onUnlock) }) { Text("Unlock") }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
    LockGateDialog(gate)
}
