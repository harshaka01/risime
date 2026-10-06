package lk.codegen.risime

import android.content.Context
import android.os.SystemClock
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import lk.codegen.risime.data.BehaviourLog
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.ContactsRepository
import lk.codegen.risime.data.AuthKind
import lk.codegen.risime.data.LegacyServerUrlMigration
import lk.codegen.risime.data.PhoneNormalizer
import lk.codegen.risime.data.auth.AppAuthGateway
import lk.codegen.risime.data.auth.AuthManager
import lk.codegen.risime.data.auth.Blocked
import lk.codegen.risime.data.auth.KeystoreWrappingKey
import lk.codegen.risime.data.auth.MeOutcome
import lk.codegen.risime.data.auth.OidcTokens
import lk.codegen.risime.data.auth.TokenVault
import lk.codegen.risime.data.auth.meOutcome
import lk.codegen.risime.data.auth.shouldConnect
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.realtime.PushResult
import lk.codegen.risime.data.PresenceTracker
import lk.codegen.risime.data.watchList
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
import lk.codegen.risime.update.Updater
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** A Keycloak end_session to open in the browser (id_token_hint + post-logout redirect). */
data class EndSession(val issuer: String, val idToken: String)

private val Context.dataStore by preferencesDataStore(
    name = "risime",
    produceMigrations = { listOf(LegacyServerUrlMigration(BuildConfig.DEFAULT_SERVER_URL)) },
)

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
    // ---- Auth (contract v1.3, decision 014) ----
    val oidc = AppAuthGateway(context, http)
    val auth = AuthManager(
        gateway = oidc,
        vault = TokenVault(File(context.noBackupFilesDir, "tokens.bin"), KeystoreWrappingKey()),
        elapsed = SystemClock::elapsedRealtime,
        onNewAccessToken = { t ->
            scope.launch {
                val r = realtime.refreshAuth(t)
                // §7.2: verification was reset while connected.
                if (r is PushResult.Rejected && r.reason == AuthErrors.PHONE_UNVERIFIED) markPhoneUnverified(null)
            }
        },
    )

    /** 403 not_allowlisted / 409 identity_conflict from GET /me: a blocking screen. */
    val blocked = MutableStateFlow<Blocked?>(null)

    /** One-line notice on the sign-in screen (e.g. "session ended, chats kept"). */
    val signInNotice = MutableStateFlow<String?>(null)

    /** Keycloak end_session requests for the activity to open in the browser. */
    val endSessionRequests = MutableSharedFlow<EndSession>(extraBufferCapacity = 1)

    /** The bearer for REST and the socket: the in-memory OIDC token, else the stored dev token. */
    suspend fun bearer(forceRefresh: Boolean = false): String? =
        if (auth.unlocked.value) auth.bearer(forceRefresh) else sessionStore.current()?.takeIf { it.kind == AuthKind.DEV }?.token

    val api = ApiClient(
        http,
        { sessionStore.currentServerUrl() },
        { bearer() },
        onUnauthorized = { auth.unlocked.value && auth.bearer(forceRefresh = true) != null },
    )
    val phone = PhoneNormalizer(PhoneNumberUtil.createInstance(context))
    val behaviour = BehaviourLog(db.behaviour(), { sessionStore.installSalt() })
    val contacts = ContactsRepository(api, db.contacts())

    /** Debounced `GET /friends` triggers (friend signal, every (re)join). */
    private val friendsRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun requestFriendsRefresh() {
        friendsRefresh.tryEmit(Unit)
    }

    val presence = PresenceTracker(scope, onFriendSignal = { requestFriendsRefresh() })

    /** Release-only self-updater (decision 016); disabled in debug builds. */
    val updater = Updater(context, http)

    /** The peer of the chat on screen, if any; always included in the presence watch. */
    val openChatPeer = MutableStateFlow<String?>(null)

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
        onIncomingFrom = presence::onMessageFrom,
    )

    val realtime: RealtimeClient = PhoenixRealtimeClient(
        http, scope, engine, signals = presence, refusals = { onSocketRefused() },
    )

    private val foreground = MutableStateFlow(false)

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground.value = true
                scope.launch {
                    updater.onForeground()
                    updater.maybeCheck(SystemClock.elapsedRealtime())
                }
                scope.launch { behaviour.appOpen() }
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground.value = false
            }
        })
        // Connected only while in the foreground, signed in and unlocked (no background connection).
        scope.launch {
            combine(foreground, sessionStore.session, auth.unlocked, blocked) { fg, s, unlocked, b ->
                if (shouldConnect(fg, s, unlocked, b)) s!!.serverUrl to s.user.id else null
            }
                .distinctUntilChanged()
                .collect { s ->
                    realtime.stop()
                    if (s != null) realtime.start(RealtimeSession(s.first, s.second) { force -> bearer(force) })
                }
        }
        // Updater: launch check happens in onStart; then at most every 6 h while in the foreground.
        scope.launch {
            foreground.collectLatest { fg ->
                while (fg) {
                    delay(6 * 60 * 60 * 1000L)
                    updater.maybeCheck(SystemClock.elapsedRealtime())
                }
            }
        }
        // §6.2: refresh about 60 s before expiry (from expires_in); the new token is pushed as auth:refresh.
        scope.launch {
            combine(foreground, auth.unlocked) { fg, u -> fg && u }.distinctUntilChanged().collectLatest { active ->
                while (active) {
                    delay(auth.refreshDueInMs() ?: break)
                    auth.bearer(forceRefresh = true)
                }
            }
        }
        // The offline session ended (invalid_grant / invalidated key): back to sign-in, chats kept.
        scope.launch {
            auth.signInNeeded.collect { needed ->
                if (needed) {
                    realtime.stop()
                    sessionStore.clearLogin(forgetUser = false)
                    signInNotice.value = "Your RisiCloud session ended. Sign in again — your chats are kept."
                    auth.acknowledgeSignInNeeded()
                }
            }
        }
        // An OIDC session from a previous process with nothing to unlock (memory-only device).
        scope.launch {
            val s = sessionStore.current()
            if (s?.kind == AuthKind.OIDC && !auth.canUnlock() && !auth.unlocked.value) {
                sessionStore.clearLogin(forgetUser = false)
                signInNotice.value = "Sign in again — your chats are kept."
            }
        }
        // §2.5/§9.3: watch friends only (plus the open chat if it's a friend).
        scope.launch {
            combine(contacts.contacts, openChatPeer) { list, open ->
                val friends = list.filter { it.friend }.mapNotNull { it.userId }
                watchList(friends, open?.takeIf { o -> friends.any { it.equals(o, ignoreCase = true) } })
            }.distinctUntilChanged().collect { realtime.setWatch(it) }
        }
        // §9.3: refetch GET /friends after every (re)join and on `friend` signals, debounced.
        scope.launch {
            realtime.state.collect { if (it == ConnectionState.Live) requestFriendsRefresh() }
        }
        scope.launch {
            friendsRefresh.collectLatest {
                delay(500)
                if (sessionStore.current() != null) handleAuthError(contacts.refresh())
            }
        }
    }

    /** Dev OTP login (DEV_LOCAL_AUTH servers). A different user than last time wipes local chats. */
    suspend fun onLoggedIn(token: String, user: User) {
        val previous = sessionStore.lastUserId()
        if (previous != null && previous != user.id) wipeDb()
        signInNotice.value = null
        sessionStore.saveLogin(token, user)
    }

    /**
     * Browser sign-in finished (code exchanged): verify with GET /me, then keep the tokens.
     * Returns an error message for the sign-in screen, or null on success / a blocking screen.
     */
    suspend fun completeOidcSignIn(issuer: String, clientId: String, tokens: OidcTokens): String? {
        auth.adopt(issuer, clientId, tokens)
        return when (val o = meOutcome(api.me())) {
            is MeOutcome.Ok -> {
                adoptOidcUser(o.user)
                null
            }
            is MeOutcome.NeedsPhone -> {
                // Signed in; the gate shows "Confirm your phone" before anything connects.
                val user = o.user ?: (api.me() as? ApiResult.Ok)?.value?.user?.copy(phoneVerified = false)
                if (user == null) {
                    auth.signOut()
                    "Can't reach the RisiMe server. Try again."
                } else {
                    adoptOidcUser(user)
                    null
                }
            }
            is MeOutcome.Refused -> {
                blocked.value = o.blocked // tokens stay in memory so "Sign out" can revoke them
                null
            }
            MeOutcome.Unauthorized -> {
                auth.signOut()
                "The RisiMe server didn't accept this RisiCloud sign-in."
            }
            is MeOutcome.Transient -> {
                auth.signOut()
                "Can't reach the RisiMe server. Try again."
            }
        }
    }

    private suspend fun adoptOidcUser(user: User) {
        val previous = sessionStore.lastUserId()
        if (previous != null && previous != user.id) wipeDb()
        blocked.value = null
        signInNotice.value = null
        sessionStore.saveOidcLogin(user)
    }

    /**
     * §7: the phone isn't (or no longer) verified. Stop the socket and let the gate show
     * "Confirm your phone". Never wipes data.
     */
    suspend fun markPhoneUnverified(user: User?) {
        realtime.stop()
        val current = sessionStore.current() ?: return
        val fresh = user ?: (api.me() as? ApiResult.Ok)?.value?.user
        // Server truth when reachable (it may already be verified again); else assume unverified.
        sessionStore.updateUser(fresh ?: current.user.copy(phoneVerified = false))
    }

    /** "Confirm your phone" succeeded (200 or 409 already_verified + GET /me). */
    suspend fun onPhoneVerified(user: User) {
        if (sessionStore.current() != null) sessionStore.updateUser(user.copy(phoneVerified = true))
    }

    /** Blocked screen → "Use another account": drop this account's tokens; the UI starts a fresh sign-in. */
    suspend fun abandonAccount() {
        auth.signOut()
        blocked.value = null
    }

    /**
     * Logout (decision 014): revoke the refresh token, end the Keycloak session in the browser,
     * delete the key pair, wipe local chat data. Dev tokens: POST /auth/logout as before.
     */
    suspend fun logout() {
        val end = auth.issuerAndClient()?.let { (issuer, _) -> auth.idToken()?.let { EndSession(issuer, it) } }
        if (sessionStore.current()?.kind == AuthKind.DEV) api.logout()
        auth.signOut()
        end?.let { endSessionRequests.tryEmit(it) }
        blocked.value = null
        signInNotice.value = null
        clearFriendsMemory()
        clearLocal()
    }

    /** §6: token rejected (refresh already tried): back to sign-in, keeping local chats. */
    suspend fun signOutKeepData(notice: String) {
        realtime.stop()
        if (auth.unlocked.value) auth.signOut()
        sessionStore.clearLogin(forgetUser = false)
        signInNotice.value = notice
    }

    /** Socket upgrade refused / join unauthorized: ask GET /me why (contract §6.2). */
    private suspend fun onSocketRefused(): Boolean = when (val o = meOutcome(api.me())) {
        is MeOutcome.Ok -> true
        is MeOutcome.NeedsPhone -> {
            // §7.2: show the phone screen; don't reconnect in a loop.
            markPhoneUnverified(o.user)
            false
        }
        MeOutcome.Unauthorized -> {
            signOutKeepData("Sign in again — your chats are kept.")
            false
        }
        is MeOutcome.Refused -> {
            blocked.value = o.blocked
            false
        }
        is MeOutcome.Transient -> true
    }

    /**
     * The token belongs to the server that issued it: revoke it there (best effort), then forget
     * it and the local chat data, and switch to [url].
     */
    suspend fun switchServer(url: String) {
        if (sessionStore.current()?.kind == AuthKind.DEV) api.logout()
        auth.signOut()
        blocked.value = null
        realtime.stop()
        sessionStore.clearLoginAndSetServerUrl(url)
        wipeDb()
    }

    /** Token revoked or rejected: back to login. */
    suspend fun clearLocal() {
        realtime.stop()
        sessionStore.clearLogin()
        wipeDb()
    }

    /** Only a 401 (refresh already tried) matters on the phone screen: back to sign-in, data kept. */
    suspend fun handleAuthError401(r: ApiResult<*>) {
        if (r is ApiResult.Error && r.httpStatus == 401 && r.code != AuthErrors.INVALID_CODE) {
            signOutKeepData("Sign in again — your chats are kept.")
        }
    }

    /** REST errors after the client already tried a refresh: map like GET /me (§6.1). */
    suspend fun handleAuthError(r: ApiResult<*>) {
        if (r !is ApiResult.Error) return
        when {
            r.httpStatus == 401 -> signOutKeepData("Sign in again — your chats are kept.")
            r.httpStatus == 403 || r.httpStatus == 409 -> onSocketRefused()
        }
    }

    /** Chat data only; the local behaviour log stays on the device. */
    private fun clearFriendsMemory() = contacts.clearMemory()

    private suspend fun wipeDb() = db.withTransaction {
        db.wipe().messages()
        db.wipe().contacts()
        db.wipe().syncState()
        db.wipe().seenEvents()
    }
}
