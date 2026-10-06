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
    /** §14.1: advertise `images` once this app can receive and render images. */
    private val imagesSupported: () -> Boolean = { false },
    private val lowWater: Int = 20,
    /** §12.1: the signature key (b64) whose key packages were already replaced with 0xFA01 ones. */
    private val groupsReplacedFor: suspend () -> String? = { null },
    private val setGroupsReplacedFor: suspend (String) -> Unit = {},
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
        val sigKey = b64.encodeToString(mls.signatureKey())
        // §12.1: advertise `groups` only with a core that really does groups (0xFA01 key packages).
        // §15.1: `deletes` once the core answers AAD + sender_is_admin and the app applies delete events.
        val caps = when {
            mls.groupsSupported -> listOfNotNull(
                DeviceMls.CAP_GROUPS,
                DeviceMls.CAP_IMAGES.takeIf { imagesSupported() },
                DeviceMls.CAP_DELETES.takeIf { mls.deletesSupported },
            )
            else -> null
        }
        val body = DevicePut(DevicePut.PLATFORM_ANDROID, pushToken?.takeIf { it.isNotBlank() }, appVersion, DeviceMls(sigKey, caps))
        return when (val r = api.putMlsDevice(id, body)) {
            is ApiResult.Ok -> {
                mls.setAttestation(r.value.attestation)
                if (caps != null && groupsReplacedFor() != sigKey) replaceForGroups(id, mls, sigKey) else topUp(id, mls)
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

    /**
     * The first time this device advertises `groups`: replace every stored normal key package with
     * fresh 0xFA01 ones and regenerate the last-resort package (§12.1, crypto README).
     */
    private suspend fun replaceForGroups(id: String, mls: MlsEngine, sigKey: String): Registration {
        val pkgs = mls.createKeyPackages(topUpTo).map(b64::encodeToString)
        val last = b64.encodeToString(mls.lastResortKeyPackage())
        return when (val r = api.uploadKeyPackages(id, KeyPackagesUpload(pkgs, last, replace = true))) {
            is ApiResult.Ok -> {
                setGroupsReplacedFor(sigKey)
                Registration.Mls(topUpTo)
            }
            is ApiResult.Error -> Registration.Failed(r.code)
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
