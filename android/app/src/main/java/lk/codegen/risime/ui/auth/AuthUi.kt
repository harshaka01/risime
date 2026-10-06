package lk.codegen.risime.ui.auth

import android.net.Uri
import android.os.Build
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
import lk.codegen.risime.data.auth.OIDC_SCOPES
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

    /** Locked screen: one prompt per process start. */
    fun unlock() {
        if (_busy.value) return
        activity.lifecycleScope.launch {
            val cipher = c.auth.unlockCipher()
            if (cipher == null || BiometricManager.from(activity).canAuthenticate(authenticators()) != BiometricManager.BIOMETRIC_SUCCESS) {
                // Key invalidated (new enrolment) or no biometrics: browser sign-in, chats kept.
                c.auth.revokeStored(null)
                c.signOutKeepData("Sign in again — your chats are kept.")
                return@launch
            }
            _busy.value = true
            val authed = prompt("Unlock RisiMe", cipher)
            if (authed != null) c.auth.unlock(authed)
            _busy.value = false
        }
    }

    /** Locked screen → Sign out: unlock once to revoke; a cancel deletes the key anyway. */
    fun signOutLocked() {
        activity.lifecycleScope.launch {
            val cipher = c.auth.unlockCipher()
            val authed = cipher?.let { prompt("Sign out of RisiMe", it) }
            c.auth.revokeStored(authed)
            c.logout()
        }
    }

    fun dispose() {
        service.dispose()
    }
}
