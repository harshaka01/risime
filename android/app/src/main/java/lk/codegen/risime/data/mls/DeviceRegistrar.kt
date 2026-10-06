package lk.codegen.risime.data.mls

import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.DeviceMls
import lk.codegen.risime.net.DevicePut
import lk.codegen.risime.net.KeyPackagesUpload
import java.util.Base64

sealed interface Registration {
    /** Push-only (v1.5) or nothing to register. */
    data object PushOnly : Registration

    data object Skipped : Registration

    /** MLS-capable: attested, key packages topped up to [keyPackages]. */
    data class Mls(val keyPackages: Int) : Registration

    /** The server has E2EE off (503 mls_unavailable): behave exactly as before. */
    data object MlsUnavailable : Registration

    data class Failed(val code: String) : Registration
}

/**
 * `PUT /me/devices/{id}` with or without MLS (contract §8.1, §10.1): push_token may be null when
 * the MLS key is present (an app without Firebase still joins the census as MLS-capable).
 */
class DeviceRegistrar(
    private val api: ApiClient,
    private val deviceId: suspend () -> String,
    private val appVersion: String,
    private val engine: () -> MlsEngine?,
    private val topUpTo: Int = 50,
    private val lowWater: Int = 20,
) {
    private val b64 = Base64.getEncoder()

    suspend fun register(pushToken: String?): Registration {
        val id = deviceId()
        val mls = engine()
        if (mls == null) {
            if (pushToken.isNullOrBlank()) return Registration.Skipped
            return when (val r = api.putDevice(id, DevicePut(DevicePut.PLATFORM_ANDROID, pushToken, appVersion))) {
                is ApiResult.Ok -> Registration.PushOnly
                is ApiResult.Error -> Registration.Failed(r.code)
                is ApiResult.NetworkError -> Registration.Failed("network")
            }
        }
        val body = DevicePut(DevicePut.PLATFORM_ANDROID, pushToken?.takeIf { it.isNotBlank() }, appVersion, DeviceMls(b64.encodeToString(mls.signatureKey())))
        return when (val r = api.putMlsDevice(id, body)) {
            is ApiResult.Ok -> {
                mls.setAttestation(r.value.attestation)
                topUp(id, mls)
            }
            is ApiResult.Error -> when {
                r.code == AuthErrors.MLS_UNAVAILABLE -> {
                    // E2EE is off on the server: register for push only, as a v1.6 app would.
                    if (!pushToken.isNullOrBlank()) api.putDevice(id, DevicePut(DevicePut.PLATFORM_ANDROID, pushToken, appVersion))
                    Registration.MlsUnavailable
                }
                else -> Registration.Failed(r.code)
            }
            is ApiResult.NetworkError -> Registration.Failed("network")
        }
    }

    /** Top up to [topUpTo] when below [lowWater] (on sign-in, join, after a Welcome, on the low signal). */
    suspend fun topUp(id: String? = null, mls: MlsEngine? = engine()): Registration {
        mls ?: return Registration.Skipped
        val dev = id ?: deviceId()
        val count = (api.keyPackageCount(dev) as? ApiResult.Ok)?.value?.count ?: return Registration.Failed("count")
        if (count >= lowWater) return Registration.Mls(count)
        val n = (topUpTo - count).coerceIn(0, 100)
        // The core stores each package's private part before we upload it (crash-safe).
        val pkgs = mls.createKeyPackages(n).map(b64::encodeToString)
        val last = b64.encodeToString(mls.lastResortKeyPackage())
        return when (val r = api.uploadKeyPackages(dev, KeyPackagesUpload(pkgs, last))) {
            is ApiResult.Ok -> Registration.Mls(count + n)
            is ApiResult.Error -> Registration.Failed(r.code)
            is ApiResult.NetworkError -> Registration.Failed("network")
        }
    }
}
