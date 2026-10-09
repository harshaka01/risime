package lk.codegen.risime.ui.auth

import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import androidx.lifecycle.withResumed
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

    // ---- Fingerprint / screen-lock prompts (decision 064; the nightly.35 lock-out hotfix) ----

    /** One BiometricPrompt for the activity (made in onCreate), shared by the lock, its turn-on and the migration. */
    private val authenticator = lk.codegen.risime.ui.lock.BiometricLockAuthenticator(activity)

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun prompter(title: String, onSuccess: () -> Unit, onNoWay: () -> Unit) = lk.codegen.risime.ui.lock.LockPrompter(
        auth = authenticator,
        onUnlocked = onSuccess,
        onNoWayToUnlock = onNoWay,
        schedule = { ms, f -> mainHandler.postDelayed(f, ms) },
        now = android.os.SystemClock::elapsedRealtime,
        log = { Log.i("RisiMe", it) },
        title = title,
    )

    /** The lock screen's attempts: prompt only when resumed, every tap retries, PIN fallback, errors shown. */
    val lock = prompter(
        "Unlock RisiMe",
        onSuccess = { c.appLock.unlocked() },
        // Neither a fingerprint nor a screen lock: the lock turns itself off and the app opens.
        onNoWay = { activity.lifecycleScope.launch { c.appLock.offIfNoWayToUnlockNow() } },
    )

    /** Settings → Privacy → Fingerprint lock: turning it on asks once (fingerprint, or the screen lock). */
    val lockTurnOn = prompter(
        "Turn on fingerprint lock",
        onSuccess = { activity.lifecycleScope.launch { c.appLock.setEnabled(true) } },
        onNoWay = {},
    )

    /** Locked chats ("Lock chat", the folder, its settings): the same prompter rules as the app lock. */
    val chatLock = lk.codegen.risime.ui.lock.PrompterChatLockAuth(
        authenticator,
        schedule = { ms, f -> mainHandler.postDelayed(f, ms) },
        now = android.os.SystemClock::elapsedRealtime,
        log = { Log.i("RisiMe", it.replace("RisiMe lock:", "RisiMe chat lock:")) },
    )

    init {
        activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                lock.onResumed()
                lockTurnOn.onResumed()
                chatLock.onResumed()
            }

            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                lock.onStopped()
            }
        })
        // The lock engaged again (after an unlock in this activity): the next resume prompts by itself.
        activity.lifecycleScope.launch {
            var was: Boolean? = null
            c.appLock.locked.collect { now ->
                if (now == true && was == false) lock.onLocked()
                was = now
            }
        }
    }

    /** The lock screen became visible/resumed: prompt (deferred until RESUMED; at most once per return). */
    fun autoUnlockApp() = lock.autoPrompt()

    /** "Unlock with fingerprint": never ignored (a stale attempt is dropped and a new prompt starts). */
    fun unlockApp() = lock.tap()

    /** "Use phone PIN/pattern". */
    fun unlockAppWithPin() = lock.usePin()

    /** Settings: turn the lock on after one confirmation. */
    fun turnOnLock() = lockTurnOn.tap()

    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG

    /**
     * Fingerprint (or device PIN on API 30+) → the authenticated Cipher, or null if cancelled /
     * unavailable / refused. Starts only when the activity is RESUMED, never waits without bound
     * (a prompt that never appeared is given up after [LockPrompter.WATCHDOG_MS]) and logs every callback.
     */
    private suspend fun prompt(title: String, cipher: Cipher): Cipher? {
        if (BiometricManager.from(activity).canAuthenticate(authenticators()) != BiometricManager.BIOMETRIC_SUCCESS) return null
        activity.lifecycle.withResumed { }
        val result = CompletableDeferred<lk.codegen.risime.ui.lock.LockOutcome>()
        val req = lk.codegen.risime.ui.lock.LockPromptRequest(
            title, "RisiCloud account",
            allowCredential = Build.VERSION.SDK_INT >= 30,
            negativeText = if (Build.VERSION.SDK_INT < 30) "Cancel" else null,
            cipher = cipher,
        )
        val refused = authenticator.showBiometric(req) { o ->
            when (o) {
                is lk.codegen.risime.ui.lock.LockOutcome.Succeeded -> Log.i("RisiMe", "RisiMe auth: migration prompt: auth succeeded")
                lk.codegen.risime.ui.lock.LockOutcome.Failed -> Log.i("RisiMe", "RisiMe auth: migration prompt: auth failed (no match)")
                is lk.codegen.risime.ui.lock.LockOutcome.Error -> Log.i("RisiMe", "RisiMe auth: migration prompt: auth error code=${o.code} msg=${o.message}")
            }
            if (o !is lk.codegen.risime.ui.lock.LockOutcome.Failed) result.complete(o)
        }
        if (refused != null) {
            Log.w("RisiMe", "RisiMe auth: migration prompt: authenticate() refused: $refused")
            _notice.value = lk.codegen.risime.ui.lock.LOCK_NOT_SHOWN
            return null
        }
        return try {
            coroutineScope {
                val watchdog = launch {
                    delay(lk.codegen.risime.ui.lock.LockPrompter.WATCHDOG_MS)
                    if (!result.isCompleted && !authenticator.promptShowing()) {
                        Log.w("RisiMe", "RisiMe auth: migration prompt: authenticate() refused: no prompt on screen")
                        result.complete(lk.codegen.risime.ui.lock.LockOutcome.Error(-1, "no prompt on screen"))
                    }
                }
                val o = result.await()
                watchdog.cancel()
                when (o) {
                    is lk.codegen.risime.ui.lock.LockOutcome.Succeeded -> {
                        _notice.value = null
                        o.cipher
                    }
                    is lk.codegen.risime.ui.lock.LockOutcome.Error -> {
                        _notice.value = if (o.code == -1) lk.codegen.risime.ui.lock.LOCK_NOT_SHOWN else lk.codegen.risime.ui.lock.lockErrorText(o.code).replace("tap Unlock", "tap Continue")
                        null
                    }
                    lk.codegen.risime.ui.lock.LockOutcome.Failed -> null
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            authenticator.cancel()
            throw e
        }
    }

    private var migrationJob: Job? = null

    /**
     * Decision 064: the one migration prompt for a pre-064 vault. Cancel/failure keeps the old vault
     * (asked again at the next open) and never signs out; an invalidated key (new enrolment) or a
     * phone without biometrics now: sign in again, chats kept (as before). A tap while an earlier
     * attempt has no prompt on screen drops it and starts again (never ignored).
     */
    fun finishMigration() {
        if (migrationJob?.isActive == true) {
            if (authenticator.promptShowing()) return // the prompt is up, or waiting for the app to resume
            Log.i("RisiMe", "RisiMe auth: migration prompt: the previous attempt has no prompt on screen: starting again")
            migrationJob?.cancel()
        }
        migrationJob = activity.lifecycleScope.launch {
            _busy.value = true
            try {
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
                val authed = prompt(lk.codegen.risime.ui.auth.MIGRATION_TITLE, oldCipher)
                if (authed != null) {
                    if (c.auth.migrate(authed) == lk.codegen.risime.data.auth.MigrationResult.Unreadable) {
                        c.signOutKeepData("Sign in again — your chats are kept.", lk.codegen.risime.data.auth.SignOutTrigger.VAULT_UNREADABLE)
                    }
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /** Migration screen → Sign out (keeps your chats): revoke if the prompt opens the old set, forget the tokens either way. */
    fun signOutLocked() {
        migrationJob?.cancel()
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
