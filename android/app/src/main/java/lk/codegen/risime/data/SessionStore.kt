package lk.codegen.risime.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.User
import java.security.SecureRandom

enum class AuthKind { DEV, OIDC }

/**
 * A signed-in user. [token] is the dev token for [AuthKind.DEV]; OIDC access tokens are never
 * persisted (they live in [lk.codegen.risime.data.auth.AuthManager] memory).
 */
data class Session(val serverUrl: String, val token: String?, val user: User, val kind: AuthKind = AuthKind.DEV)

/** Token, user and server URL in DataStore (never in logs). Also holds the per-install salt for A6. */
class SessionStore(private val store: DataStore<Preferences>, private val defaultServerUrl: String) {

    val session: Flow<Session?> = store.data.map { p ->
        val kind = if (p[KIND] == AuthKind.OIDC.name) AuthKind.OIDC else AuthKind.DEV
        val token = p[TOKEN]
        if (kind == AuthKind.DEV && token == null) return@map null
        val user = p[USER]?.let { runCatching { ProtocolJson.decodeFromString<User>(it) }.getOrNull() }
            ?: return@map null
        Session(p[SERVER_URL] ?: defaultServerUrl, token, user, kind)
    }

    /** Debug-only sign-in override (Settings). */
    val authOverride: Flow<String?> = store.data.map { it[AUTH_OVERRIDE] }

    suspend fun setAuthOverride(v: String) {
        store.edit { it[AUTH_OVERRIDE] = v }
    }

    /** The user whose chats are on this device (survives sign-outs that keep data). */
    suspend fun lastUserId(): String? = store.data.first()[LAST_USER]

    val serverUrl: Flow<String> = store.data.map { it[SERVER_URL] ?: defaultServerUrl }

    suspend fun currentServerUrl(): String = serverUrl.first()

    suspend fun currentToken(): String? = store.data.first()[TOKEN]

    suspend fun current(): Session? = session.first()

    suspend fun setServerUrl(url: String) {
        store.edit { it[SERVER_URL] = url.trim().trimEnd('/') }
    }

    suspend fun saveLogin(token: String, user: User) {
        store.edit {
            it[TOKEN] = token
            it[KIND] = AuthKind.DEV.name
            it[USER] = ProtocolJson.encodeToString(User.serializer(), user)
            it[LAST_USER] = user.id
        }
    }

    /** OIDC sign-in: only the user is stored here; tokens live in memory and the sealed vault. */
    suspend fun saveOidcLogin(user: User) {
        store.edit {
            it.remove(TOKEN)
            it[KIND] = AuthKind.OIDC.name
            it[USER] = ProtocolJson.encodeToString(User.serializer(), user)
            it[LAST_USER] = user.id
        }
    }

    suspend fun updateUser(user: User) {
        store.edit { it[USER] = ProtocolJson.encodeToString(User.serializer(), user) }
    }

    /**
     * Forgets the login and points the app at another server in one atomic edit, so nothing ever
     * sees the old token paired with the new server URL.
     */
    suspend fun clearLoginAndSetServerUrl(url: String) {
        store.edit {
            it.remove(TOKEN)
            it.remove(USER)
            it.remove(KIND)
            it.remove(LAST_USER)
            it[SERVER_URL] = url.trim().trimEnd('/')
        }
    }

    /**
     * Clears the login. The server URL and install salt stay. [forgetUser] = the local chat data
     * is being wiped too; otherwise the next sign-in by the same user keeps it.
     */
    suspend fun clearLogin(forgetUser: Boolean = true) {
        store.edit {
            it.remove(TOKEN)
            it.remove(USER)
            it.remove(KIND)
            if (forgetUser) it.remove(LAST_USER)
        }
    }

    suspend fun installSalt(): String {
        store.data.first()[SALT]?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val fresh = bytes.joinToString("") { "%02x".format(it) }
        var result = fresh
        store.edit { p -> p[SALT]?.let { result = it } ?: run { p[SALT] = fresh } }
        return result
    }

    private companion object {
        val TOKEN = stringPreferencesKey("token")
        val USER = stringPreferencesKey("user")
        val SERVER_URL = stringPreferencesKey("server_url")
        val SALT = stringPreferencesKey("install_salt")
        val KIND = stringPreferencesKey("auth_kind")
        val LAST_USER = stringPreferencesKey("last_user_id")
        val AUTH_OVERRIDE = stringPreferencesKey("auth_override")
    }
}
