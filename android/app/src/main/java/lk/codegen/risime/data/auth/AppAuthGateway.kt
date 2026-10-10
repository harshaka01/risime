package lk.codegen.risime.data.auth

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import net.openid.appauth.connectivity.ConnectionBuilder
import java.net.HttpURLConnection
import java.net.URL
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Scopes for RisiMe (decision 014). `offline_access` gives the refresh token the vault keeps. */
const val OIDC_SCOPES = "openid email profile offline_access"

/** Keycloak connection timeouts (P0 2026-10-10: a stalled connect costs 5 s, then the refresh retries). */
object KeycloakTimeouts {
    const val CONNECT_MS = 5_000
    const val READ_MS = 10_000
}

/**
 * AppAuth connections with [KeycloakTimeouts]. https only; in debug builds http on loopback is also
 * allowed (the device gates run a stand-in issuer on adb-reverse loopback).
 */
class TimedConnections(private val allowLoopbackHttp: Boolean) : ConnectionBuilder {
    private val loopback = setOf("127.0.0.1", "10.0.2.2", "localhost")

    override fun openConnection(uri: Uri): HttpURLConnection {
        require(uri.scheme == "https" || (allowLoopbackHttp && uri.scheme == "http" && uri.host in loopback)) {
            "only https (or http on loopback in debug)"
        }
        return (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = KeycloakTimeouts.CONNECT_MS
            readTimeout = KeycloakTimeouts.READ_MS
            instanceFollowRedirects = false
        }
    }
}

/** AppAuth-backed Keycloak calls: discovery (cached per issuer), refresh, RFC 7009 revocation. */
class AppAuthGateway(context: Context, private val http: OkHttpClient) : OidcGateway {
    private val connections: ConnectionBuilder = TimedConnections(allowLoopbackHttp = lk.codegen.risime.BuildConfig.DEBUG)
    private val service = AuthorizationService(context.applicationContext, AppAuthConfiguration.Builder().setConnectionBuilder(connections).build())
    private val configs = ConcurrentHashMap<String, AuthorizationServiceConfiguration>()

    suspend fun configuration(issuer: String): AuthorizationServiceConfiguration? {
        configs[issuer]?.let { return it }
        val cfg = suspendCancellableCoroutine<AuthorizationServiceConfiguration?> { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(issuer), { c, _ -> cont.resume(c) }, connections)
        } ?: return null
        configs[issuer] = cfg
        return cfg
    }

    override suspend fun refresh(issuer: String, clientId: String, refreshToken: String): RefreshResult {
        val cfg = configuration(issuer) ?: return RefreshResult.Failed("discovery")
        val req = TokenRequest.Builder(cfg, clientId)
            .setGrantType(GrantTypeValues.REFRESH_TOKEN)
            .setRefreshToken(refreshToken)
            .setScope(OIDC_SCOPES)
            .build()
        return suspendCancellableCoroutine { cont ->
            service.performTokenRequest(req) { resp, ex -> cont.resume(toResult(resp, ex)) }
        }
    }

    override suspend fun revoke(issuer: String, clientId: String, refreshToken: String) {
        val endpoint = configuration(issuer)?.discoveryDoc?.docJson?.optString("revocation_endpoint")
            ?.takeIf { it.startsWith("https://") } ?: return
        val body = FormBody.Builder()
            .add("token", refreshToken)
            .add("token_type_hint", "refresh_token")
            .add("client_id", clientId)
            .build()
        withContext(Dispatchers.IO) {
            runCatching { http.newCall(Request.Builder().url(endpoint).post(body).build()).execute().close() }
        }
    }

    companion object {
        fun toResult(resp: TokenResponse?, ex: AuthorizationException?): RefreshResult {
            if (resp?.accessToken != null) return RefreshResult.Ok(tokens(resp))
            return if (ex?.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR && ex.error == "invalid_grant") {
                RefreshResult.InvalidGrant
            } else {
                RefreshResult.Failed(ex?.error ?: ex?.errorDescription ?: "token request failed")
            }
        }

        /** expires_in at receipt: AppAuth stores receipt time + expires_in as accessTokenExpirationTime. */
        fun tokens(resp: TokenResponse): OidcTokens {
            val expiresIn = resp.accessTokenExpirationTime
                ?.let { ((it - System.currentTimeMillis()) / 1_000).coerceAtLeast(0) } ?: 300L
            return OidcTokens(resp.accessToken!!, expiresIn, resp.refreshToken, resp.idToken)
        }
    }
}
