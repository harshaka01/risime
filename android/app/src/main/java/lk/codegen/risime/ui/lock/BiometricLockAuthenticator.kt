package lk.codegen.risime.ui.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle

/** `KeyguardManager.isDeviceSecure` (a PIN, pattern or password is set); false if it can't be asked. */
fun deviceSecure(context: Context): Boolean =
    runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }.getOrDefault(false)

/**
 * The app lock can be unlocked on this phone now: a strong biometric or the device credential
 * (`canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`, API 30+), or any screen lock at all.
 * False only when neither exists: then the lock turns itself off (never a dead end).
 */
fun lockUnlockable(context: Context): Boolean {
    val bm = runCatching { BiometricManager.from(context) }.getOrNull()
    val combined = runCatching {
        bm?.canAuthenticate(if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG)
    }.getOrNull()
    return combined == BiometricManager.BIOMETRIC_SUCCESS || deviceSecure(context)
}

/**
 * [LockAuthenticator] on Android: one BiometricPrompt made in onCreate (as androidx.biometric
 * wants: it survives configuration changes), routing to the current attempt; on API < 30 the
 * screen lock is the Keyguard confirmation activity. Construct in the activity's onCreate.
 */
class BiometricLockAuthenticator(private val activity: FragmentActivity) : LockAuthenticator {
    private var current: ((LockOutcome) -> Unit)? = null
    private var credentialCb: ((LockOutcome) -> Unit)? = null

    private val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            current?.invoke(LockOutcome.Succeeded(result.cryptoObject?.cipher))
        }

        override fun onAuthenticationFailed() {
            current?.invoke(LockOutcome.Failed)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            current?.invoke(LockOutcome.Error(errorCode, errString.toString()))
        }
    })

    private val keyguardLauncher: ActivityResultLauncher<android.content.Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val cb = credentialCb
            credentialCb = null
            cb?.invoke(
                if (r.resultCode == Activity.RESULT_OK) LockOutcome.Succeeded() else LockOutcome.Error(BiometricPrompt.ERROR_USER_CANCELED, "screen lock cancelled (result ${r.resultCode})"),
            )
        }

    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    override fun strongBiometric(): Int = BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG)

    override fun deviceSecure(): Boolean = deviceSecure(activity)

    override fun isResumed(): Boolean = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    override fun promptShowing(): Boolean = credentialCb != null || !isResumed() || !activity.hasWindowFocus()

    private fun notReady(): String? = when {
        !isResumed() -> "activity not resumed (${activity.lifecycle.currentState})"
        activity.supportFragmentManager.isStateSaved -> "called after onSaveInstanceState"
        else -> null
    }

    override fun showBiometric(req: LockPromptRequest, onOutcome: (LockOutcome) -> Unit): String? {
        notReady()?.let { return it }
        val authenticators = if (req.allowCredential && sdkInt >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(req.title)
            .apply { req.subtitle?.let { setSubtitle(it) } }
            .setAllowedAuthenticators(authenticators)
            .apply { if (authenticators and DEVICE_CREDENTIAL == 0) setNegativeButtonText(req.negativeText ?: "Cancel") }
            .build()
        current = onOutcome
        return runCatching {
            if (req.cipher != null) prompt.authenticate(info, BiometricPrompt.CryptoObject(req.cipher)) else prompt.authenticate(info)
        }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
    }

    override fun showCredential(title: String, onOutcome: (LockOutcome) -> Unit): String? {
        notReady()?.let { return it }
        if (!deviceSecure()) return "no screen lock set"
        if (sdkInt >= 30) {
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(DEVICE_CREDENTIAL)
                .build()
            current = onOutcome
            return runCatching { prompt.authenticate(info) }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
        }
        @Suppress("DEPRECATION")
        val intent = activity.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, null)
            ?: return "no Keyguard confirmation intent"
        credentialCb = onOutcome
        return runCatching { keyguardLauncher.launch(intent) }.exceptionOrNull()?.let {
            credentialCb = null
            "${it.javaClass.simpleName}: ${it.message}"
        }
    }

    override fun cancel() {
        runCatching { prompt.cancelAuthentication() }
        credentialCb = null
    }
}
