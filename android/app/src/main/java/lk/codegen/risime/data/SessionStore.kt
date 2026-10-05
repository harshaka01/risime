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

data class Session(val serverUrl: String, val token: String, val user: User)

/** Token, user and server URL in DataStore (never in logs). Also holds the per-install salt for A6. */
class SessionStore(private val store: DataStore<Preferences>, private val defaultServerUrl: String) {

    val session: Flow<Session?> = store.data.map { p ->
        val token = p[TOKEN] ?: return@map null
        val user = p[USER]?.let { runCatching { ProtocolJson.decodeFromString<User>(it) }.getOrNull() }
            ?: return@map null
        Session(p[SERVER_URL] ?: defaultServerUrl, token, user)
    }

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
            it[USER] = ProtocolJson.encodeToString(User.serializer(), user)
        }
    }

    suspend fun updateUser(user: User) {
        store.edit { it[USER] = ProtocolJson.encodeToString(User.serializer(), user) }
    }

    /** Clears the login. The server URL and install salt stay. */
    suspend fun clearLogin() {
        store.edit {
            it.remove(TOKEN)
            it.remove(USER)
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
    }
}
