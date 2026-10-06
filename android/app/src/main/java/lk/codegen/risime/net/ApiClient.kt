package lk.codegen.risime.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /** The server answered with an error envelope (§0) or a bare status. */
    data class Error(
        val httpStatus: Int,
        val code: String,
        val message: String,
        /** §7.1: `Retry-After` seconds on a 429 (null when absent/unparseable). */
        val retryAfterSec: Long? = null,
        val attemptsLeft: Int? = null,
        /** §10.2: 409 epoch_conflict's current epoch; 409 not_ready's missing devices. */
        val epoch: Long? = null,
        val missing: List<MlsMissing>? = null,
    ) : ApiResult<Nothing>
    data class NetworkError(val cause: IOException) : ApiResult<Nothing>
}

/** REST client for PROTOCOL.md §1. The server base URL and token are read on every call. */
class ApiClient(
    private val http: OkHttpClient,
    private val serverUrl: suspend () -> String,
    private val token: suspend () -> String?,
    /** A 401 on an authenticated call: try to refresh; true = retry the call once (§6.1). */
    private val onUnauthorized: suspend () -> Boolean = { false },
) {
    suspend fun requestCode(phone: String, email: String): ApiResult<AuthRequestReply> =
        call("POST", "auth/request", AuthRequest(phone, email), auth = false)

    suspend fun verify(phone: String, code: String, deviceName: String): ApiResult<AuthVerifyReply> =
        call("POST", "auth/verify", AuthVerify(phone, code, deviceName), auth = false)

    /** §6.1: which sign-in modes the server offers. A pre-v1.3 server answers 404. */
    suspend fun authConfig(): ApiResult<AuthConfig> = call<Unit, AuthConfig>("GET", "auth/config", null, auth = false)

    /** §7.1: SMS a code to the user's allowlisted phone. */
    suspend fun requestPhoneCode(): ApiResult<PhoneVerifyRequestReply> =
        call("POST", "me/phone/verify/request", JsonObject(emptyMap()))

    suspend fun confirmPhoneCode(code: String): ApiResult<MeReply> =
        call("POST", "me/phone/verify/confirm", PhoneVerifyConfirm(code))

    /** §8.1: register/refresh this install's push token (idempotent). 204 without MLS. */
    suspend fun putDevice(deviceId: String, body: DevicePut): ApiResult<Unit> =
        call<DevicePut, Unit>("PUT", "me/devices/$deviceId", body)

    /** §10.1: with `mls`, the reply is 200 {"attestation"}; 503 mls_unavailable when E2EE is off. */
    suspend fun putMlsDevice(deviceId: String, body: DevicePut): ApiResult<DevicePutReply> =
        call("PUT", "me/devices/$deviceId", body)

    suspend fun attestationKeys(): ApiResult<AttestationKeys> = call<Unit, AttestationKeys>("GET", "mls/attestation_keys", null, auth = false)

    suspend fun uploadKeyPackages(deviceId: String, body: KeyPackagesUpload): ApiResult<Unit> =
        call("POST", "me/devices/$deviceId/key_packages", body)

    suspend fun keyPackageCount(deviceId: String): ApiResult<KeyPackageCount> =
        call<Unit, KeyPackageCount>("GET", "me/devices/$deviceId/key_packages/count", null)

    /** §10.2: [deviceId] (X-Device-Id) excludes the calling device from my own devices. */
    suspend fun claimKeyPackages(userIds: List<String>, deviceId: String? = null): ApiResult<KeyPackagesClaimReply> =
        call("POST", "mls/key_packages/claim", KeyPackagesClaim(userIds), headers = deviceId?.let { mapOf(DEVICE_HEADER to it) }.orEmpty())

    suspend fun mlsGroup(conversationId: String): ApiResult<MlsGroup> = call<Unit, MlsGroup>("GET", "mls/groups/$conversationId", null)

    /** §10.2: X-Device-Id is required here (it names from_device). */
    suspend fun mlsCommit(conversationId: String, body: MlsCommitRequest, deviceId: String): ApiResult<MlsCommitReply> =
        call("POST", "mls/groups/$conversationId/commit", body, headers = mapOf(DEVICE_HEADER to deviceId))

    suspend fun mlsCommits(conversationId: String, sinceEpoch: Long): ApiResult<MlsCommitsReply> =
        call<Unit, MlsCommitsReply>("GET", "mls/groups/$conversationId/commits?since_epoch=$sinceEpoch", null)

    /** §8.1: at logout (idempotent). */
    suspend fun deleteDevice(deviceId: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "me/devices/$deviceId", null)

    // ---- §9 invites and friends ----
    suspend fun createInvite(body: InviteCreate): ApiResult<InviteReply> = call("POST", "invites", body)

    suspend fun invites(): ApiResult<InvitesReply> = call<Unit, InvitesReply>("GET", "invites", null)

    suspend fun revokeInvite(id: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "invites/$id", null)

    suspend fun requestFriend(phone: String): ApiResult<FriendRequestReply> = call("POST", "friends/requests", FriendRequestCreate(phone))

    suspend fun friends(): ApiResult<FriendsReply> = call<Unit, FriendsReply>("GET", "friends", null)

    suspend fun acceptRequest(id: String): ApiResult<FriendAcceptReply> = call<Unit, FriendAcceptReply>("POST", "friends/requests/$id/accept", null)

    suspend fun declineRequest(id: String): ApiResult<Unit> = call<Unit, Unit>("POST", "friends/requests/$id/decline", null)

    suspend fun cancelRequest(id: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "friends/requests/$id", null)

    suspend fun unfriend(userId: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "friends/$userId", null)

    suspend fun block(userId: String): ApiResult<Unit> = call("POST", "blocks", BlockCreate(userId))

    suspend fun unblock(userId: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "blocks/$userId", null)

    suspend fun me(): ApiResult<MeReply> = call<Unit, MeReply>("GET", "me", null)

    suspend fun updateDisplayName(name: String): ApiResult<MeReply> = call("PATCH", "me", PatchMe(name))

    suspend fun contacts(): ApiResult<ContactsReply> = call<Unit, ContactsReply>("GET", "contacts", null)

    suspend fun logout(): ApiResult<Unit> = call<Unit, Unit>("POST", "auth/logout", null)

    private suspend inline fun <reified B, reified R> call(
        method: String,
        path: String,
        body: B?,
        auth: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): ApiResult<R> = execute(method, path, body?.let { encode(serializer<B>(), it) }, auth, serializer<R>(), headers)

    private fun <B> encode(s: KSerializer<B>, body: B): RequestBody =
        ProtocolJson.encodeToString(s, body).toRequestBody(JSON)

    private suspend fun <R> execute(
        method: String,
        path: String,
        body: RequestBody?,
        auth: Boolean,
        resSerializer: KSerializer<R>,
        headers: Map<String, String> = emptyMap(),
    ): ApiResult<R> {
        val first = executeOnce(method, path, body, auth, resSerializer, headers)
        if (auth && first is ApiResult.Error && first.httpStatus == 401 && onUnauthorized()) {
            return executeOnce(method, path, body, auth, resSerializer, headers)
        }
        return first
    }

    private suspend fun <R> executeOnce(
        method: String,
        path: String,
        body: RequestBody?,
        auth: Boolean,
        resSerializer: KSerializer<R>,
        headers: Map<String, String>,
    ): ApiResult<R> {
        val url = serverUrl().trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/v1/${path.substringBefore('?')}")
            .apply { if ('?' in path) encodedQuery(path.substringAfter('?')) }
            .build()
        val builder = Request.Builder().url(url)
            .method(method, body ?: if (method == "GET") null else ByteArray(0).toRequestBody(JSON))
        if (auth) token()?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> builder.header(k, v) }
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(builder.build()).execute().use { res ->
                    val text = res.body.string()
                    if (res.isSuccessful) {
                        @Suppress("UNCHECKED_CAST")
                        val value = if (res.code == 204 || text.isBlank()) Unit as R else ProtocolJson.decodeFromString(resSerializer, text)
                        ApiResult.Ok(value)
                    } else {
                        val err = runCatching { ProtocolJson.decodeFromString<ApiErrorEnvelope>(text).error }.getOrNull()
                        ApiResult.Error(
                            res.code, err?.code ?: "http_${res.code}", err?.message ?: "",
                            retryAfterSec = parseRetryAfter(res.header("Retry-After"), System.currentTimeMillis()),
                            attemptsLeft = err?.attemptsLeft,
                            epoch = err?.epoch,
                            missing = err?.missing,
                        )
                    }
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e)
            }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** §10.2: the calling device on MLS REST calls. */
        const val DEVICE_HEADER = "X-Device-Id"
    }
}

/** `Retry-After`: delta-seconds or an HTTP-date (RFC 9110); null if absent or unparseable. */
fun parseRetryAfter(value: String?, nowMs: Long): Long? {
    val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    v.toLongOrNull()?.let { return it.coerceAtLeast(0) }
    return runCatching {
        val at = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        ((at - nowMs + 999) / 1000).coerceAtLeast(0)
    }.getOrNull()
}
