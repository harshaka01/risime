package lk.codegen.risime.data

import androidx.datastore.core.DataMigration
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
/**
 * One-time rewrite of the never-live 0.2 default `https://risicloud.ai/risime` to the current
 * default (`https://risime.risicloud.ai` in release). Only that exact stored value changes;
 * user-chosen URLs stay. Runs inside DataStore before the first read.
 */
class LegacyServerUrlMigration(private val defaultServerUrl: String) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[SessionStore.SERVER_URL_KEY]?.trimEnd('/') == LEGACY_URL && defaultServerUrl != LEGACY_URL

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { this[SessionStore.SERVER_URL_KEY] = defaultServerUrl }

    override suspend fun cleanUp() = Unit

    companion object {
        const val LEGACY_URL = "https://risicloud.ai/risime"
    }
}

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

    /** The display name of [lastUserId] (for "Chats from <name> are on this phone"). */
    suspend fun lastUserName(): String? = store.data.first()[LAST_USER_NAME]

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
            it[LAST_USER] = AccountIds.stable(user.id) ?: user.id
            it[LAST_USER_NAME] = user.displayName
        }
    }

    /** OIDC sign-in: only the user is stored here; tokens live in memory and the sealed vault. */
    suspend fun saveOidcLogin(user: User) {
        store.edit {
            it.remove(TOKEN)
            it[KIND] = AuthKind.OIDC.name
            it[USER] = ProtocolJson.encodeToString(User.serializer(), user)
            it[LAST_USER] = AccountIds.stable(user.id) ?: user.id
            it[LAST_USER_NAME] = user.displayName
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
            it.remove(LAST_USER_NAME)
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
            if (forgetUser) {
                it.remove(LAST_USER)
                it.remove(LAST_USER_NAME)
            }
        }
    }

    /** §8.1: stable per-install device id (UUID), created on first use; kept across sign-outs. */
    suspend fun deviceId(): String {
        store.data.first()[DEVICE_ID]?.let { return it }
        val fresh = java.util.UUID.randomUUID().toString()
        var result = fresh
        store.edit { p -> p[DEVICE_ID]?.let { result = it } ?: run { p[DEVICE_ID] = fresh } }
        return result
    }

    /** Push notifications: newest local_ts already notified, and notified request ids. */
    /** §15.7 the server-clock offset (ms), persisted for a cold start. */
    suspend fun serverOffset(): Long? = store.data.first()[SERVER_OFFSET]?.toLongOrNull()

    suspend fun setServerOffset(ms: Long) {
        store.edit { it[SERVER_OFFSET] = ms.toString() }
    }

    suspend fun notifiedUpTo(): Long = store.data.first()[NOTIFIED_UP_TO]?.toLongOrNull() ?: 0L

    suspend fun setNotifiedUpTo(ts: Long) {
        store.edit { it[NOTIFIED_UP_TO] = ts.toString() }
    }

    suspend fun notifiedRequests(): Set<String> =
        store.data.first()[NOTIFIED_REQUESTS]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    suspend fun setNotifiedRequests(ids: Set<String>) {
        store.edit { it[NOTIFIED_REQUESTS] = ids.toList().takeLast(200).joinToString(",") }
    }

    /** §12.1: the MLS signature key whose key packages were last re-uploaded with `replace: true` (groups-capable). */
    suspend fun groupsKeyPackagesFor(): String? = store.data.first()[GROUPS_KP]

    suspend fun setGroupsKeyPackagesFor(signatureKey: String) {
        store.edit { it[GROUPS_KP] = signatureKey }
    }

    /** The one-time inbox replay ([LocalAccount.HISTORY_REPLAY_VERSION]) done on this install. */
    suspend fun historyReplayVersion(): Int = store.data.first()[HISTORY_REPLAY]?.toIntOrNull() ?: 0

    suspend fun setHistoryReplayVersion(v: Int) {
        store.edit { it[HISTORY_REPLAY] = v.toString() }
    }

    /** The cursor at which an empty message table last triggered a replay (no replay loop). */
    suspend fun emptyReplayCursor(): String? = store.data.first()[EMPTY_REPLAY_CURSOR]

    suspend fun setEmptyReplayCursor(c: String) {
        store.edit { it[EMPTY_REPLAY_CURSOR] = c }
    }

    suspend fun notificationsPrompted(): Boolean = store.data.first()[NOTIF_PROMPTED] == "1"

    suspend fun setNotificationsPrompted() {
        store.edit { it[NOTIF_PROMPTED] = "1" }
    }

    /** §17.8 Settings → Privacy: share with other members' new devices (on = ask, off = decline without prompting). */
    val historyMembers: Flow<Boolean> = store.data.map { it[HISTORY_MEMBERS] != "0" }

    /** §17.8 Settings → Privacy: my own new phones (on = one approval, then automatic; off = decline). */
    val historyOwn: Flow<Boolean> = store.data.map { it[HISTORY_OWN] != "0" }

    suspend fun setHistoryMembers(on: Boolean) {
        store.edit { it[HISTORY_MEMBERS] = if (on) "1" else "0" }
    }

    suspend fun setHistoryOwn(on: Boolean) {
        store.edit { it[HISTORY_OWN] = if (on) "1" else "0" }
    }

    suspend fun installSalt(): String {
        store.data.first()[SALT]?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val fresh = bytes.joinToString("") { "%02x".format(it) }
        var result = fresh
        store.edit { p -> p[SALT]?.let { result = it } ?: run { p[SALT] = fresh } }
        return result
    }

    companion object {
        val SERVER_URL_KEY = stringPreferencesKey("server_url")
        private val TOKEN = stringPreferencesKey("token")
        private val USER = stringPreferencesKey("user")
        private val SERVER_URL = SERVER_URL_KEY
        private val SALT = stringPreferencesKey("install_salt")
        private val KIND = stringPreferencesKey("auth_kind")
        private val LAST_USER = stringPreferencesKey("last_user_id")
        private val LAST_USER_NAME = stringPreferencesKey("last_user_name")
        private val AUTH_OVERRIDE = stringPreferencesKey("auth_override")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val NOTIFIED_UP_TO = stringPreferencesKey("notified_up_to")
        private val SERVER_OFFSET = stringPreferencesKey("server_offset_ms")
        private val NOTIFIED_REQUESTS = stringPreferencesKey("notified_requests")
        private val NOTIF_PROMPTED = stringPreferencesKey("notif_prompted")
        private val HISTORY_REPLAY = stringPreferencesKey("history_replay_version")
        private val EMPTY_REPLAY_CURSOR = stringPreferencesKey("empty_replay_cursor")
        private val GROUPS_KP = stringPreferencesKey("groups_kp_replaced_for")
        private val HISTORY_MEMBERS = stringPreferencesKey("history_share_members")
        private val HISTORY_OWN = stringPreferencesKey("history_share_own")
    }
}
