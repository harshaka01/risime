package lk.codegen.risime.ui.auth

import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import lk.codegen.risime.AppContainer
import lk.codegen.risime.BuildConfig
import lk.codegen.risime.EndSession
import lk.codegen.risime.data.auth.AppAuthGateway
import lk.codegen.risime.data.auth.MigrationStep
import lk.codegen.risime.data.auth.OIDC_SCOPES
import lk.codegen.risime.data.auth.migrationStep
import lk.codegen.risime.data.lock.BiometricStatus
import lk.codegen.risime.net.AuthConfig
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.ResponseTypeValues
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * Activity-side auth actions (decision 014): AppAuth browser sign-in (PKCE S256, Custom Tabs),
 * end_session, and the BiometricPrompt unlock with a CryptoObject. Construct in onCreate.
 */
class AuthUi(private val activity: FragmentActivity, private val c: AppContainer) {
    private val service = AuthorizationService(activity)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** A retryable fingerprint problem on the lock / migration screen ([lk.codegen.risime.ui.lock.FINGERPRINT_UNAVAILABLE]). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val signInLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult(), ::onSignInResult)

    private val endSessionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    init {
        activity.lifecycleScope.launch { c.endSessionRequests.collect { endSession(it) } }
    }

    fun clearError() {
        _error.value = null
    }

    /** Opens RisiCloud sign-in in a Custom Tab. [fresh] forces the login form (another account). */
    fun signIn(config: AuthConfig, fresh: Boolean = false) {
        val issuer = config.issuer ?: return
        val clientId = config.clientId ?: return
        _busy.value = true
        _error.value = null
        activity.lifecycleScope.launch {
            val cfg = c.oidc.configuration(issuer)
            if (cfg == null) {
                _busy.value = false
                _error.value = "Can't reach RisiCloud sign-in"
                return@launch
            }
            // AppAuth generates the PKCE verifier and uses S256 by default.
            val req = AuthorizationRequest.Builder(cfg, clientId, ResponseTypeValues.CODE, Uri.parse(BuildConfig.OIDC_REDIRECT_URI))
                .setScope(OIDC_SCOPES)
                .apply { if (fresh) setPrompt(AuthorizationRequest.Prompt.LOGIN) }
                .build()
            signInLauncher.launch(service.getAuthorizationRequestIntent(req))
        }
    }

    private fun onSignInResult(result: ActivityResult) {
        val data = result.data
        val resp = data?.let { AuthorizationResponse.fromIntent(it) }
        if (resp == null) {
            _busy.value = false
            val ex = data?.let { AuthorizationException.fromIntent(it) }
            _error.value = when {
                ex == null || ex.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code -> null
                else -> "Sign-in failed (${ex.error ?: ex.errorDescription ?: ex.code})"
            }
            return
        }
        service.performTokenRequest(resp.createTokenExchangeRequest()) { tokenResp, ex ->
            activity.lifecycleScope.launch {
                val issuer = resp.request.configuration.discoveryDoc?.issuer
                if (tokenResp?.accessToken == null || issuer == null) {
                    _busy.value = false
                    _error.value = "Sign-in failed (${ex?.error ?: "token exchange"})"
                    return@launch
                }
                _error.value = c.completeOidcSignIn(issuer, resp.request.clientId, AppAuthGateway.tokens(tokenResp))
                _busy.value = false
            }
        }
    }

    private fun endSession(e: EndSession) {
        activity.lifecycleScope.launch {
            val cfg = c.oidc.configuration(e.issuer) ?: return@launch
            if (cfg.endSessionEndpoint == null) return@launch
            val req = EndSessionRequest.Builder(cfg)
                .setIdTokenHint(e.idToken)
                .setPostLogoutRedirectUri(Uri.parse(BuildConfig.OIDC_LOGOUT_REDIRECT_URI))
                .build()
            runCatching { endSessionLauncher.launch(service.getEndSessionRequestIntent(req)) }
        }
    }

    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG

    /** Fingerprint (or device PIN on API 30+) → the authenticated Cipher, or null if cancelled/unavailable. */
    private suspend fun prompt(title: String, cipher: Cipher): Cipher? {
        if (BiometricManager.from(activity).canAuthenticate(authenticators()) != BiometricManager.BIOMETRIC_SUCCESS) return null
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("RisiCloud account")
            .setAllowedAuthenticators(authenticators())
            .apply { if (Build.VERSION.SDK_INT < 30) setNegativeButtonText("Cancel") }
            .build()
        return suspendCancellableCoroutine { cont ->
            val p = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(result.cryptoObject?.cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(null)
                }
            })
            p.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            cont.invokeOnCancellation { p.cancelAuthentication() }
        }
    }

    /**
     * Decision 064: the one migration prompt for a pre-064 vault. Cancel/failure keeps the old vault
     * (asked again at the next open) and never signs out; an invalidated key (new enrolment) or a
     * phone without biometrics now: sign in again, chats kept (as before).
     */
    fun finishMigration() {
        if (_busy.value) return
        activity.lifecycleScope.launch {
            val cipher = c.auth.migrationCipher()
            val bio = runCatching { BiometricStatus.of(BiometricManager.from(activity).canAuthenticate(authenticators())) }.getOrDefault(BiometricStatus.UNAVAILABLE_NOW)
            when (migrationStep(cipherAvailable = cipher != null, bio)) {
                MigrationStep.SIGN_IN_AGAIN -> {
                    c.auth.revokeStored(null)
                    c.signOutKeepData("Sign in again — your chats are kept.", lk.codegen.risime.data.auth.SignOutTrigger.KEY_INVALIDATED)
                    return@launch
                }
                // The sensor is busy (or needs an update) now: keep the old vault, ask again.
                MigrationStep.TRY_AGAIN -> {
                    Log.i("RisiMe", "RisiMe auth: migration: fingerprint unavailable now: old vault kept")
                    _notice.value = lk.codegen.risime.ui.lock.FINGERPRINT_UNAVAILABLE
                    return@launch
                }
                MigrationStep.PROMPT -> _notice.value = null
            }
            val oldCipher = cipher ?: return@launch
            _busy.value = true
            val authed = prompt(lk.codegen.risime.ui.auth.MIGRATION_TITLE, oldCipher)
            if (authed != null) {
                if (c.auth.migrate(authed) == lk.codegen.risime.data.auth.MigrationResult.Unreadable) {
                    c.signOutKeepData("Sign in again — your chats are kept.", lk.codegen.risime.data.auth.SignOutTrigger.VAULT_UNREADABLE)
                }
            }
            _busy.value = false
        }
    }

    /** The optional fingerprint lock (decision 064): one BIOMETRIC_STRONG match unlocks the UI. */
    fun unlockApp() {
        if (_busy.value) return
        activity.lifecycleScope.launch {
            _busy.value = true
            try {
                when (lk.codegen.risime.ui.lock.strongBiometricStatus(activity)) {
                    BiometricStatus.AVAILABLE -> {
                        _notice.value = null
                        if (lk.codegen.risime.ui.lock.confirmFingerprint(activity, "Unlock RisiMe")) c.appLock.unlocked()
                    }
                    // No fingerprint left (removed, no sensor): the lock turns itself off.
                    BiometricStatus.GONE -> c.appLock.onForeground()
                    // Busy sensor, security update pending: the lock stays on; the user taps again.
                    BiometricStatus.UNAVAILABLE_NOW -> _notice.value = lk.codegen.risime.ui.lock.FINGERPRINT_UNAVAILABLE
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /** Migration screen → Sign out (keeps your chats): revoke if the prompt opens the old set, forget the tokens either way. */
    fun signOutLocked() {
        activity.lifecycleScope.launch {
            val cipher = c.auth.migrationCipher()
            val authed = cipher?.let { prompt("Sign out of RisiMe", it) }
            c.auth.revokeStored(authed)
            c.signOutKeepChats()
        }
    }

    fun dispose() {
        service.dispose()
    }
}
