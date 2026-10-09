package lk.codegen.risime.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

const val LOCK_CHAT_LABEL = "Lock chat"
const val UNLOCK_CHAT_LABEL = "Unlock chat"
const val LOCKED_CHATS_TITLE = "Locked chats"
const val SCREEN_LOCK_NEEDED_TITLE = "Set a screen lock first"
const val SCREEN_LOCK_NEEDED_TEXT =
    "Locked chats are opened with your fingerprint, PIN, pattern or password. Set a screen lock in your phone's Settings, then try again."
const val CHAT_LOCK_PROMPT_NOT_SHOWN = "The fingerprint or PIN prompt didn't open. Try again."

/** What the phone can confirm with right now, for the Locked chats gate. */
enum class ChatLockAuthStatus { READY, NEEDS_SCREEN_LOCK }

/** Confirms the user (fingerprint, or the PIN/pattern/password) before a chat is locked or the folder opens. */
interface ChatLockAuth {
    fun status(): ChatLockAuthStatus

    /** True when the user confirmed; false on cancel or failure. */
    suspend fun confirm(title: String, subtitle: String? = null): Boolean

    /** Why the last [confirm] ended without success, worth telling the user (null: the user cancelled). */
    fun lastProblem(): String? = null
}

/**
 * The authenticators for the Locked chats gate: BIOMETRIC_STRONG | DEVICE_CREDENTIAL (API 30+, so a
 * phone with no fingerprint can use its PIN/pattern). Android 8-10 can't combine them in one
 * prompt; there [LockPrompter] falls back to the Keyguard PIN confirmation.
 */
fun lockAuthenticators(sdk: Int): Int = if (sdk >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG

fun chatLockStatus(canAuthenticate: Int): ChatLockAuthStatus =
    if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) ChatLockAuthStatus.READY else ChatLockAuthStatus.NEEDS_SCREEN_LOCK

/** The user-facing text for a prompter message that ended a chat-lock confirmation (null: cancelled, say nothing). */
fun chatLockProblemText(message: String): String? = when (message) {
    LOCK_CANCELLED, LOCK_TIMED_OUT -> null
    LOCK_NOT_SHOWN -> CHAT_LOCK_PROMPT_NOT_SHOWN
    LOCK_TOO_MANY_ATTEMPTS -> "Too many attempts. Try again with your phone's PIN or pattern."
    else -> message
}

/**
 * The Locked chats confirmation on the app lock's [LockPrompter] (one per activity, over the
 * BiometricPrompt made in onCreate): a prompt only while RESUMED (a request while stopped waits for
 * [onResumed]), a watchdog for a prompt that never appeared, the phone's PIN on every API level. Every
 * [confirm] ends: success, cancel, error or "didn't open" — never a coroutine that waits forever
 * (the old per-call BiometricPrompt silently dropped authenticate() after onSaveInstanceState and
 * left the gate busy, so every later tap was ignored). Main thread only.
 */
class PrompterChatLockAuth(
    private val auth: LockAuthenticator,
    schedule: (Long, () -> Unit) -> Unit,
    now: () -> Long,
    log: (String) -> Unit,
) : ChatLockAuth {
    private var waiter: CompletableDeferred<Boolean>? = null
    private var problem: String? = null

    private val prompter = LockPrompter(
        auth = auth,
        onUnlocked = { finish(true, null) },
        onNoWayToUnlock = { finish(false, SCREEN_LOCK_NEEDED_TEXT) },
        schedule = schedule,
        now = now,
        log = log,
        title = LOCK_CHAT_LABEL,
        onMessage = { m -> finish(false, chatLockProblemText(m)) },
    )

    private fun finish(ok: Boolean, why: String?) {
        val w = waiter ?: return
        waiter = null
        problem = if (ok) null else why
        w.complete(ok)
    }

    override fun status(): ChatLockAuthStatus {
        val bio = runCatching { auth.strongBiometric() }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)
        val secure = runCatching { auth.deviceSecure() }.getOrDefault(false)
        return if (bio == BiometricManager.BIOMETRIC_SUCCESS || secure) ChatLockAuthStatus.READY else ChatLockAuthStatus.NEEDS_SCREEN_LOCK
    }

    override suspend fun confirm(title: String, subtitle: String?): Boolean {
        finish(false, null) // a confirmation still waiting is superseded (its prompt is reused or restarted)
        val d = CompletableDeferred<Boolean>()
        waiter = d
        problem = null
        prompter.title = title
        prompter.subtitle = subtitle
        prompter.tap()
        return try {
            d.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (waiter === d) {
                waiter = null
                runCatching { auth.cancel() }
            }
            throw e
        }
    }

    override fun lastProblem(): String? = problem

    /** Activity ON_RESUME: a confirmation asked for while not resumed prompts now. */
    fun onResumed() = prompter.onResumed()
}

/** Tests (and previews) replace the real prompt through this. */
val LocalChatLockAuth = compositionLocalOf<ChatLockAuth?> { null }

/** No activity prompt to use (a preview, a test without a fake): nothing can be confirmed. */
private object NoChatLockAuth : ChatLockAuth {
    override fun status() = ChatLockAuthStatus.NEEDS_SCREEN_LOCK

    override suspend fun confirm(title: String, subtitle: String?) = false
}

@Composable
fun rememberChatLockAuth(): ChatLockAuth {
    val override = LocalChatLockAuth.current
    val ctx = LocalContext.current
    return override ?: remember(ctx) { (ctx.findFragmentActivity() as? lk.codegen.risime.MainActivity)?.authUi?.chatLock ?: NoChatLockAuth }
}

/**
 * Runs an action only after the confirmation; explains when the phone has no screen lock at all,
 * or when the prompt couldn't be shown. One per screen: [run] from a click, [LockGateDialog] once in the layout.
 */
class LockGate(private val scope: CoroutineScope, private val auth: ChatLockAuth) {
    var needsScreenLock by mutableStateOf(false)
        internal set

    /** Why the last confirmation failed (shown by [LockGateDialog]); null: nothing to say. */
    var problem by mutableStateOf<String?>(null)
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
                if (auth.confirm(title, subtitle)) onConfirmed() else problem = auth.lastProblem()
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
    if (gate.needsScreenLock) {
        AlertDialog(
            onDismissRequest = { gate.needsScreenLock = false },
            title = { Text(SCREEN_LOCK_NEEDED_TITLE) },
            text = { Text(SCREEN_LOCK_NEEDED_TEXT) },
            confirmButton = { TextButton(onClick = { gate.needsScreenLock = false }) { Text("OK") } },
        )
        return
    }
    val p = gate.problem ?: return
    AlertDialog(
        onDismissRequest = { gate.problem = null },
        text = { Text(p) },
        confirmButton = { TextButton(onClick = { gate.problem = null }) { Text("OK") } },
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
