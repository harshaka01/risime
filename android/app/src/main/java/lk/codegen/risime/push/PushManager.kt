package lk.codegen.risime.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import lk.codegen.risime.BuildConfig
import lk.codegen.risime.data.SessionStore
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.DevicePut
import kotlin.coroutines.resume

/**
 * FCM token ↔ `PUT/DELETE /me/devices/{device_id}` (contract v1.5 §8.1). Everything is a no-op when
 * Firebase isn't configured (no google-services.json → no FirebaseApp).
 */
class PushManager(
    private val context: Context,
    private val api: ApiClient,
    private val store: SessionStore,
    private val registrar: lk.codegen.risime.data.mls.DeviceRegistrar,
    private val mlsAvailable: () -> Boolean = { false },
    /** False while an OIDC session is locked: no bearer, so a PUT would only get 401. */
    private val canAuthenticate: suspend () -> Boolean = { true },
) {
    val available: Boolean
        get() = BuildConfig.PUSH_CONFIGURED && runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    suspend fun currentToken(): String? = fcmToken()

    private suspend fun fcmToken(): String? {
        if (!available) return null
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                if (cont.isActive) cont.resume(if (t.isSuccessful) t.result else null)
            }
        }
    }

    /** At sign-in (verified) and on every FCM token refresh. */
    suspend fun register(token: String? = null, phoneVerified: Boolean = true): lk.codegen.risime.data.mls.Registration {
        val session = store.current()
        if (session == null || !phoneVerified) return lk.codegen.risime.data.mls.Registration.Skipped
        // Locked: the registration loop runs again right after the unlock (never a 401 here).
        if (!canAuthenticate()) return lk.codegen.risime.data.mls.Registration.Failed("locked")
        val t = token ?: fcmToken()
        // Push needs Firebase; an MLS-capable app registers even without it (contract §10.1).
        if (!shouldRegisterDevice(available, true, true, t) && !mlsAvailable()) return lk.codegen.risime.data.mls.Registration.Skipped
        val r = registrar.register(t)
        if (r is lk.codegen.risime.data.mls.Registration.Failed) Log.w("RisiMe", "device registration failed: ${r.code}")
        return r
    }

    /** At logout, while the token is still valid (idempotent on the server). */
    suspend fun unregister() {
        if ((!available && !mlsAvailable()) || store.current() == null) return
        api.deleteDevice(store.deviceId())
    }
}
