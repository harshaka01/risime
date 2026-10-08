package lk.codegen.risime.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.Cipher

const val USE_PHONE_PIN = "Use phone PIN/pattern"

/** The negative button of the fingerprint prompt on Android 9–10 (BIOMETRIC_STRONG only): the phone's screen lock instead. */
const val USE_PIN_NEGATIVE = "Use PIN"

const val LOCK_TOO_MANY_ATTEMPTS = "Too many attempts — use your phone PIN/pattern"
const val LOCK_CANCELLED = "Unlock cancelled — tap Unlock to try again"
const val LOCK_TIMED_OUT = "Timed out — tap Unlock to try again"
const val LOCK_NOT_SHOWN = "The unlock prompt didn't open — tap Unlock to try again"

/** One answer from the system prompt (or the screen-lock confirmation). */
sealed interface LockOutcome {
    data class Succeeded(val cipher: Cipher? = null) : LockOutcome

    /** One fingerprint that didn't match; the prompt stays open (not an end). */
    data object Failed : LockOutcome

    data class Error(val code: Int, val message: String) : LockOutcome
}

/** What to show: the system prompt with a strong biometric (+ the device credential on API 30+). */
data class LockPromptRequest(
    val title: String,
    val subtitle: String? = null,
    /** API 30+: BIOMETRIC_STRONG or DEVICE_CREDENTIAL (no negative button). */
    val allowCredential: Boolean,
    /** API < 30: the negative button ([USE_PIN_NEGATIVE] or "Cancel"). */
    val negativeText: String?,
    val cipher: Cipher? = null,
)

/**
 * The seam between the lock logic and Android's BiometricPrompt / KeyguardManager
 * ([BiometricLockAuthenticator]); a fake in tests.
 */
interface LockAuthenticator {
    val sdkInt: Int

    /** `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`. */
    fun strongBiometric(): Int

    /** `KeyguardManager.isDeviceSecure`: a PIN, pattern or password is set. */
    fun deviceSecure(): Boolean

    /** The activity is at least RESUMED (BiometricPrompt silently ignores authenticate() otherwise). */
    fun isResumed(): Boolean

    /** A prompt (or the screen-lock activity) is in front of the app now. */
    fun promptShowing(): Boolean

    /** Shows the biometric prompt. Null when shown; otherwise why authenticate() refused (no callback will come). */
    fun showBiometric(req: LockPromptRequest, onOutcome: (LockOutcome) -> Unit): String?

    /** The phone's PIN/pattern/password: API 30+ the system prompt with DEVICE_CREDENTIAL, older the Keyguard confirmation. */
    fun showCredential(title: String, onOutcome: (LockOutcome) -> Unit): String?

    fun cancel()
}

/** The message under the button for a prompt error. */
fun lockErrorText(code: Int): String = when (code) {
    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> LOCK_TOO_MANY_ATTEMPTS
    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON -> LOCK_CANCELLED
    BiometricPrompt.ERROR_TIMEOUT -> LOCK_TIMED_OUT
    else -> "Fingerprint unavailable — use your phone PIN/pattern"
}

/**
 * The lock screen's unlock attempts (decision 064, nightly.35 lock-out hotfix). A lock screen never
 * traps the user:
 *  - a prompt is started only while the activity is RESUMED; a request while STOPPED waits for
 *    [onResumed] (BiometricPrompt drops authenticate() after onSaveInstanceState, with no callback);
 *  - no unbounded wait: a request while an attempt has no visible prompt drops it and starts again,
 *    and a watchdog clears an attempt whose prompt never appeared;
 *  - a tap is never ignored; every callback is logged; every error leaves a message and the
 *    [USE_PHONE_PIN] button;
 *  - the phone's screen lock always unlocks (API 30+ in the same prompt; API < 30 via "Use PIN");
 *  - no fingerprint and no screen lock at all → [onNoWayToUnlock] (the lock turns itself off).
 * Main thread only.
 */
class LockPrompter(
    private val auth: LockAuthenticator,
    private val onUnlocked: () -> Unit,
    private val onNoWayToUnlock: () -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val now: () -> Long,
    private val log: (String) -> Unit,
    private val title: String = "Unlock RisiMe",
) {
    enum class Kind { AUTO, TAP, PIN }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _showPin = MutableStateFlow(false)
    val showPin: StateFlow<Boolean> = _showPin.asStateFlow()

    private var attempt = 0
    private var inFlight = false
    private var startedAt = 0L
    private var pending: Kind? = null

    /** The next resume prompts by itself (a new screen, or back from the background); off after an attempt. */
    private var armed = true

    /** The in-flight attempt is the Keyguard activity (API < 30): its onStop is not "the user left". */
    private var credentialActivity = false
    private var ignoreCanceledUntil = 0L

    /** The lock screen became visible/resumed. Prompts unless an attempt already ran since the user came back. */
    fun autoPrompt() {
        if (!armed && pending == null) return
        request(Kind.AUTO)
    }

    /** "Unlock with fingerprint": always does something. */
    fun tap() = request(Kind.TAP)

    /** [USE_PHONE_PIN]. */
    fun usePin() = request(Kind.PIN)

    /** Activity ON_RESUME: run a request that came while not resumed. */
    fun onResumed() {
        val k = pending ?: return
        pending = null
        log("RisiMe lock: resumed: running the deferred prompt")
        request(k)
    }

    /** Activity ON_STOP: the user left; prompt again on return. */
    fun onStopped() {
        if (!(inFlight && credentialActivity)) armed = true
    }

    /** The lock engaged again (e.g. after an unlock): the next resume prompts. */
    fun onLocked() {
        armed = true
    }

    private fun request(kind: Kind) {
        if (!auth.isResumed()) {
            if (pending != Kind.PIN) pending = kind
            log("RisiMe lock: prompt deferred until the app is resumed")
            return
        }
        if (inFlight) {
            if (auth.promptShowing()) return // the prompt is up; nothing to do
            if (kind == Kind.AUTO && now() - startedAt < RESTART_GRACE_MS) return
            log("RisiMe lock: the previous attempt has no prompt on screen: starting again")
            inFlight = false
            credentialActivity = false
            runCatching { auth.cancel() }
            ignoreCanceledUntil = now() + STALE_CANCEL_MS
        }
        start(kind)
    }

    private fun start(kind: Kind) {
        val bio = runCatching { auth.strongBiometric() }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)
        val secure = runCatching { auth.deviceSecure() }.getOrDefault(false)
        if (bio != BiometricManager.BIOMETRIC_SUCCESS && !secure) {
            log("RisiMe lock: no fingerprint (canAuthenticate=$bio) and no screen lock: the app lock turns itself off")
            _message.value = null
            _showPin.value = false
            onNoWayToUnlock()
            return
        }
        armed = false
        val id = ++attempt
        inFlight = true
        startedAt = now()
        val useCredential = kind == Kind.PIN || bio != BiometricManager.BIOMETRIC_SUCCESS
        credentialActivity = useCredential && auth.sdkInt < 30
        val cb: (LockOutcome) -> Unit = { o -> onOutcome(id, o) }
        val refused = runCatching {
            if (useCredential) {
                auth.showCredential(title, cb)
            } else {
                val api30 = auth.sdkInt >= 30
                auth.showBiometric(LockPromptRequest(title, allowCredential = api30, negativeText = if (api30) null else USE_PIN_NEGATIVE), cb)
            }
        }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
        if (refused != null) {
            log("RisiMe lock: authenticate() refused: $refused")
            if (attempt == id) {
                inFlight = false
                credentialActivity = false
            }
            _message.value = LOCK_NOT_SHOWN
            _showPin.value = secure && !useCredential
            return
        }
        log("RisiMe lock: prompt shown (${if (useCredential) "screen lock" else "fingerprint"}, ${kind.name.lowercase()})")
        schedule(WATCHDOG_MS) {
            if (attempt == id && inFlight && !auth.promptShowing()) {
                log("RisiMe lock: authenticate() refused: no prompt on screen ${WATCHDOG_MS} ms after authenticate()")
                inFlight = false
                credentialActivity = false
                _message.value = LOCK_NOT_SHOWN
                _showPin.value = secure
            }
        }
    }

    private fun onOutcome(id: Int, o: LockOutcome) {
        when (o) {
            is LockOutcome.Succeeded -> {
                log("RisiMe lock: auth succeeded")
                if (attempt == id) {
                    inFlight = false
                    credentialActivity = false
                }
                _message.value = null
                _showPin.value = false
                onUnlocked() // a late success still unlocks
            }
            LockOutcome.Failed -> log("RisiMe lock: auth failed (no match)")
            is LockOutcome.Error -> {
                log("RisiMe lock: auth error code=${o.code} msg=${o.message}")
                if (attempt != id) return
                if (o.code == BiometricPrompt.ERROR_CANCELED && now() < ignoreCanceledUntil) return
                inFlight = false
                credentialActivity = false
                val secure = runCatching { auth.deviceSecure() }.getOrDefault(false)
                if (o.code == BiometricPrompt.ERROR_NEGATIVE_BUTTON && auth.sdkInt < 30 && secure) {
                    request(Kind.PIN) // "Use PIN" on Android 9–10
                    return
                }
                if (!secure && o.code in GONE_CODES) {
                    val bio = runCatching { auth.strongBiometric() }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)
                    if (bio != BiometricManager.BIOMETRIC_SUCCESS) {
                        log("RisiMe lock: no fingerprint and no screen lock: the app lock turns itself off")
                        onNoWayToUnlock()
                        return
                    }
                }
                _message.value = lockErrorText(o.code)
                _showPin.value = secure
            }
        }
    }

    companion object {
        const val WATCHDOG_MS = 2_000L
        const val RESTART_GRACE_MS = 1_500L
        const val STALE_CANCEL_MS = 700L
        private val GONE_CODES = setOf(
            BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_HW_UNAVAILABLE,
        )
    }
}
