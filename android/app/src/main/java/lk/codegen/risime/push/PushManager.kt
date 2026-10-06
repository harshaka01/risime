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
class PushManager(private val context: Context, private val api: ApiClient, private val store: SessionStore) {
    val available: Boolean
        get() = BuildConfig.PUSH_CONFIGURED && runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    private suspend fun fcmToken(): String? {
        if (!available) return null
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                if (cont.isActive) cont.resume(if (t.isSuccessful) t.result else null)
            }
        }
    }

    /** At sign-in (verified) and on every FCM token refresh. */
    suspend fun register(token: String? = null, phoneVerified: Boolean = true) {
        val session = store.current()
        val t = token ?: fcmToken()
        if (!shouldRegisterDevice(available, session != null, phoneVerified, t)) return
        val r = api.putDevice(store.deviceId(), DevicePut(DevicePut.PLATFORM_ANDROID, t!!, BuildConfig.VERSION_NAME))
        if (r !is ApiResult.Ok) Log.w("RisiMe", "device registration failed: ${(r as? ApiResult.Error)?.code ?: "network"}")
    }

    /** At logout, while the token is still valid (idempotent on the server). */
    suspend fun unregister() {
        if (!available || store.current() == null) return
        api.deleteDevice(store.deviceId())
    }
}
