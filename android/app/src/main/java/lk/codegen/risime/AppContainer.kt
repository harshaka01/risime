package lk.codegen.risime

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import lk.codegen.risime.data.BehaviourLog
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.ContactsRepository
import lk.codegen.risime.data.PhoneNormalizer
import lk.codegen.risime.data.SessionStore
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.AppDatabase
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.User
import lk.codegen.risime.realtime.ConnectionState
import lk.codegen.risime.realtime.PhoenixRealtimeClient
import lk.codegen.risime.realtime.RealtimeClient
import lk.codegen.risime.realtime.RealtimeSession
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore(name = "risime")

/** Manual DI for 0.1 (no framework). One instance per process, owned by [RisiMeApp]. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.w("RisiMe", "background task failed", e) },
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val sessionStore = SessionStore(context.dataStore, BuildConfig.DEFAULT_SERVER_URL)
    val db = AppDatabase.create(context)
    val api = ApiClient(http, { sessionStore.currentServerUrl() }, { sessionStore.currentToken() })
    val phone = PhoneNormalizer(PhoneNumberUtil.createInstance(context))
    val behaviour = BehaviourLog(db.behaviour(), { sessionStore.installSalt() })
    val contacts = ContactsRepository(api, db.contacts())

    val engine: ChatEngine = ChatEngine(
        messages = db.messages(),
        sync = db.sync(),
        tx = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
        },
        scope = scope,
        realtime = { realtime },
        meId = { sessionStore.current()?.user?.id },
        behaviour = behaviour,
    )

    val realtime: RealtimeClient = PhoenixRealtimeClient(http, scope, engine)

    private val foreground = MutableStateFlow(false)

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground.value = true
                scope.launch { behaviour.appOpen() }
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground.value = false
            }
        })
        // Connected only while in the foreground and logged in (A3, 0.1: no background connection).
        scope.launch {
            combine(foreground, sessionStore.session) { fg, s -> if (fg && s != null) s else null }
                .distinctUntilChanged()
                .collect { s ->
                    realtime.stop()
                    if (s != null) realtime.start(RealtimeSession(s.serverUrl, s.token, s.user.id))
                }
        }
        scope.launch {
            realtime.state.collect { if (it == ConnectionState.AuthFailed) clearLocal() }
        }
    }

    suspend fun onLoggedIn(token: String, user: User) {
        val previous = sessionStore.current()?.user?.id
        if (previous != null && previous != user.id) wipeDb()
        sessionStore.saveLogin(token, user)
    }

    /** POST /auth/logout (best effort), then forget the token and local data. */
    suspend fun logout() {
        api.logout()
        clearLocal()
    }

    /** Token revoked or rejected: back to login. */
    suspend fun clearLocal() {
        realtime.stop()
        sessionStore.clearLogin()
        wipeDb()
    }

    /** REST 401 from an authenticated call means the token is gone. */
    suspend fun handleAuthError(r: ApiResult<*>) {
        if (r is ApiResult.Error && r.httpStatus == 401) clearLocal()
    }

    /** Chat data only; the local behaviour log stays on the device. */
    private suspend fun wipeDb() = db.withTransaction {
        db.wipe().messages()
        db.wipe().contacts()
        db.wipe().syncState()
        db.wipe().seenEvents()
    }
}
