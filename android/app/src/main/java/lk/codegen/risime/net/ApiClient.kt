package lk.codegen.risime.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
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
    data class Error(val httpStatus: Int, val code: String, val message: String) : ApiResult<Nothing>
    data class NetworkError(val cause: IOException) : ApiResult<Nothing>
}

/** REST client for PROTOCOL.md §1. The server base URL and token are read on every call. */
class ApiClient(
    private val http: OkHttpClient,
    private val serverUrl: suspend () -> String,
    private val token: suspend () -> String?,
) {
    suspend fun requestCode(phone: String, email: String): ApiResult<AuthRequestReply> =
        call("POST", "auth/request", AuthRequest(phone, email), auth = false)

    suspend fun verify(phone: String, code: String, deviceName: String): ApiResult<AuthVerifyReply> =
        call("POST", "auth/verify", AuthVerify(phone, code, deviceName), auth = false)

    suspend fun me(): ApiResult<MeReply> = call<Unit, MeReply>("GET", "me", null)

    suspend fun updateDisplayName(name: String): ApiResult<MeReply> = call("PATCH", "me", PatchMe(name))

    suspend fun contacts(): ApiResult<ContactsReply> = call<Unit, ContactsReply>("GET", "contacts", null)

    suspend fun logout(): ApiResult<Unit> = call<Unit, Unit>("POST", "auth/logout", null)

    private suspend inline fun <reified B, reified R> call(
        method: String,
        path: String,
        body: B?,
        auth: Boolean = true,
    ): ApiResult<R> = execute(method, path, body?.let { encode(serializer<B>(), it) }, auth, serializer<R>())

    private fun <B> encode(s: KSerializer<B>, body: B): RequestBody =
        ProtocolJson.encodeToString(s, body).toRequestBody(JSON)

    private suspend fun <R> execute(
        method: String,
        path: String,
        body: RequestBody?,
        auth: Boolean,
        resSerializer: KSerializer<R>,
    ): ApiResult<R> {
        val url = serverUrl().trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/v1/$path")
            .build()
        val builder = Request.Builder().url(url)
            .method(method, body ?: if (method == "GET") null else ByteArray(0).toRequestBody(JSON))
        if (auth) token()?.let { builder.header("Authorization", "Bearer $it") }
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
                        ApiResult.Error(res.code, err?.code ?: "http_${res.code}", err?.message ?: "")
                    }
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e)
            }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
