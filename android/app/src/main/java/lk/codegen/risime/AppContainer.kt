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
import kotlinx.coroutines.sync.withLock
import lk.codegen.risime.data.BehaviourLog
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.ContactsRepository
import lk.codegen.risime.data.AuthKind
import lk.codegen.risime.data.LegacyServerUrlMigration
import lk.codegen.risime.data.mls.DeviceRegistrar
import lk.codegen.risime.data.mls.KeystoreDbKeyWrapper
import lk.codegen.risime.data.mls.KvSealer
import lk.codegen.risime.data.mls.MembershipExecutor
import lk.codegen.risime.data.mls.MlsDbKey
import lk.codegen.risime.data.mls.MlsEngineFactory
import lk.codegen.risime.data.mls.Registration
import lk.codegen.risime.data.mls.SupportKvSql
import lk.codegen.risime.data.mls.MlsApi
import lk.codegen.risime.data.mls.MlsEngine
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.data.mls.MlsUpgrader
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
import lk.codegen.risime.push.Notifier
import lk.codegen.risime.push.PushManager
import lk.codegen.risime.push.newRequests
import lk.codegen.risime.push.planChatNotifications
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
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

    val presence = PresenceTracker(
        scope,
        onFriendSignal = { requestFriendsRefresh() },
        onKeyPackagesLow = { scope.launch { deviceRegistrar.topUp() } },
    )

    // ---- Push (contract v1.5, decision 026) ----
    val notifier = Notifier(context)
    // ---- E2EE (contract v1.7, decisions 035, 037). The engine loads only once the server offers
    // attestation keys; until then (pilot: mls_unavailable) the app behaves exactly like v1.6.
    val mlsEngineState = MutableStateFlow<MlsEngine?>(null)
    var mlsEngine: MlsEngine?
        get() = mlsEngineState.value
        set(v) { mlsEngineState.value = v }

    /** §12: groups UI only with a groups-capable core (the same condition as the `groups` capability). */
    val groupsAvailable = mlsEngineState.map { it?.groupsSupported == true }
    private val mlsDbKey = MlsDbKey(File(context.noBackupFilesDir, "mls_dbkey.bin"), KeystoreDbKeyWrapper())
    private val mlsApi = object : MlsApi {
        override suspend fun group(conversationId: String) = api.mlsGroup(conversationId)
        override suspend fun claim(userIds: List<String>) = api.claimKeyPackages(userIds, sessionStore.deviceId())
        override suspend fun commit(conversationId: String, body: lk.codegen.risime.net.MlsCommitRequest) =
            api.mlsCommit(conversationId, body, sessionStore.deviceId())
    }
    val membershipExecutor by lazy { MembershipExecutor({ mlsEngine }, mlsApi) { conv -> catchUpCommits(conv) } }
    val mlsPipeline by lazy {
        MlsPipeline(
            { mlsEngine }, db.mlsPending(),
            onMembership = { a ->
                scope.launch {
                    delay(a.delayMs)
                    val r = membershipExecutor.execute(a)
                    Log.i("RisiMe", "mls_membership ${a.event.change}: $r")
                }
            },
            log = { Log.w("RisiMe", "mls: $it") },
            onJoined = { scope.launch { deviceRegistrar.topUp() } },
            onParkedAhead = { conv -> scope.launch { catchUpCommits(conv) } },
        )
    }
    val mlsUpgrader by lazy { MlsUpgrader({ mlsEngine }, mlsApi) }

    // ---- Groups (contract v1.9 §12). Enabled only with a groups-capable MLS core. ----
    val groupStore by lazy {
        lk.codegen.risime.data.groups.GroupStore(
            db.groups(), db.groupOps(), db.messages(), { sessionStore.deviceId() },
            metaOf = { conv -> mlsEngine?.groupMeta(conv) },
            needsRefresh = { conv -> scope.launch { refreshGroup(conv) } },
            onAddedMe = { conv, actor -> scope.launch { notifyAddedToGroup(conv, actor) } },
            onOpQueued = { kickGroupOps() },
            onReset = { conv, generation ->
                mlsEngine?.let { e -> e.group(conv)?.takeIf { it.generation < generation }?.let { e.deleteGroup(conv) } }
                db.mlsPending().dropOlderGenerations(conv, generation)
            },
        )
    }

    private val groupApi = object : lk.codegen.risime.data.groups.GroupApi {
        private suspend fun dev() = sessionStore.deviceId()
        override suspend fun create(clientGroupId: String, memberIds: List<String>) =
            api.createGroup(lk.codegen.risime.net.GroupCreate(clientGroupId, memberIds), dev()).map { it.group }
        override suspend fun group(id: String) = api.group(id).map { it.group }
        override suspend fun addMembers(id: String, userIds: List<String>) = api.addGroupMembers(id, userIds, dev()).map { it.group }
        override suspend fun removeMember(id: String, userId: String) = api.removeGroupMember(id, userId, dev())
        override suspend fun leave(id: String) = api.leaveGroup(id, dev())
        override suspend fun setRole(id: String, userId: String, role: String) = api.setGroupRole(id, userId, role, dev()).map { it.group }
        override suspend fun rejoin(id: String) = api.rejoinGroup(id, dev()).map { it.group }
        override suspend fun reset(id: String, generation: Long) = api.resetGroup(id, generation, dev()).map { it.generation }
        override suspend fun claim(userIds: List<String>, conversationId: String?) =
            api.claimKeyPackages(userIds, dev(), conversationId).map { it.devices }
        override suspend fun commit(id: String, body: lk.codegen.risime.net.GroupCommitRequest) = api.groupCommit(id, body, dev()).map { it.epoch }
        override suspend fun uploadBlob(conversationId: String, bytes: ByteArray) = api.uploadBlob(conversationId, bytes).map { it.ref() }
    }

    private val dbTx = object : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
    }

    val groupOps by lazy {
        lk.codegen.risime.data.groups.GroupOpsExecutor(
            { mlsEngine }, groupApi, db.groupOps(), db.groups(), groupStore, dbTx,
            me = { sessionStore.current()?.user?.id }, deviceId = { sessionStore.deviceId() },
            catchUp = { conv -> catchUpCommits(conv) },
            log = { Log.i("RisiMe", it) },
        )
    }

    private val groupOpsRun = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Run due group ops now (and again when the earliest retry is due). */
    fun kickGroupOps() {
        groupOpsRun.tryEmit(Unit)
    }

    /** GET /groups on every (re)join: server truth, groups I'm no longer in, and owed ops after a restart (R5). */
    private suspend fun syncGroups() {
        if (mlsEngine?.groupsSupported != true) return
        val me = sessionStore.current()?.user?.id ?: return
        val r = api.groups() as? ApiResult.Ok ?: return
        val listed = r.value.groups.map { it.id }.toSet()
        db.withTransaction {
            r.value.groups.forEach { groupStore.applyServerGroup(it, me) }
            db.groups().allNow().filter { it.conversationId !in listed && !it.readOnly }.forEach { groupStore.markGone(it.conversationId) }
        }
        kickGroupOps()
    }

    /** S4 notification (chunk 8). */
    private suspend fun notifyAddedToGroup(conversationId: String, actor: String) = Unit

    /** GET /groups/{id}: server truth for members, roles and owed ops; 404 keeps the local snapshot read-only (S3). */
    suspend fun refreshGroup(conversationId: String) {
        val me = sessionStore.current()?.user?.id ?: return
        when (val r = api.group(conversationId)) {
            is ApiResult.Ok -> db.withTransaction { groupStore.applyServerGroup(r.value.group, me) }
            is ApiResult.Error -> if (r.httpStatus == 404) db.withTransaction { groupStore.markGone(conversationId) }
            is ApiResult.NetworkError -> Unit
        }
    }

    /** §12.6: download, then check size and SHA-256. */
    private suspend fun fetchBlob(ref: lk.codegen.risime.net.BlobRef): lk.codegen.risime.data.BlobFetch = when (val r = api.downloadBlob(ref.blobId)) {
        is ApiResult.Ok -> if (lk.codegen.risime.data.groups.blobMatches(r.value, ref)) lk.codegen.risime.data.BlobFetch.Ok(r.value) else lk.codegen.risime.data.BlobFetch.Gone
        is ApiResult.Error -> if (r.httpStatus == 404) lk.codegen.risime.data.BlobFetch.Gone else lk.codegen.risime.data.BlobFetch.Transient
        is ApiResult.NetworkError -> lk.codegen.risime.data.BlobFetch.Transient
    }

    /** §12.8: this device's group state is lost or broken: rejoin (a `devices` op re-adds it). */
    private fun onGroupUnrecoverable(conversationId: String) {
        Log.w("RisiMe", "group $conversationId unrecoverable: rejoining")
        scope.launch { groupStore.queueLocal(conversationId, lk.codegen.risime.data.groups.GroupOpType.REJOIN) }
    }
    val deviceRegistrar by lazy {
        DeviceRegistrar(
            api, { sessionStore.deviceId() }, BuildConfig.VERSION_NAME, { mlsEngine },
            groupsReplacedFor = { sessionStore.groupsKeyPackagesFor() },
            setGroupsReplacedFor = { sessionStore.setGroupsKeyPackagesFor(it) },
        )
    }

    /**
     * Load the MLS core if this build has it and the server offers attestation keys (E2EE on), then
     * register with the MLS key (→ attestation → key packages). Otherwise nothing changes.
     */
    suspend fun activateMls() {
        if (mlsEngine != null || !BuildConfig.CRYPTO_AVAILABLE) return
        val session = sessionStore.current()?.takeIf { it.user.phoneVerified } ?: return
        val factory = MlsEngineFactory.get() ?: return
        val served = (api.attestationKeys() as? ApiResult.Ok)?.value?.keys.orEmpty()
        if (served.isEmpty()) return // mls_unavailable / no key: E2EE is off
        val pinned = BuildConfig.MLS_PINNED_KEYS.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val trusted = pinned + served.map { it.toString() }
        val engine = runCatching {
            factory.open(
                SupportKvSql(db.openHelper.writableDatabase), KvSealer(mlsDbKey.get()),
                { block -> if (db.inTransaction()) block() else db.runInTransaction(java.util.concurrent.Callable { block() }) },
                session.user.id, sessionStore.deviceId(), trusted,
            )
        }.getOrElse {
            Log.w("RisiMe", "MLS core unavailable: ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        mlsEngine = engine
        when (val r = deviceRegistrar.register(runCatching { push.currentToken() }.getOrNull())) {
            is Registration.Mls -> Log.i("RisiMe", "MLS device registered, ${r.keyPackages} key packages")
            else -> {
                Log.i("RisiMe", "MLS not active: $r")
                mlsEngine = null // the server turned it down: stay a v1.6 client
            }
        }
    }

    val push by lazy { PushManager(context, api, sessionStore, deviceRegistrar) { mlsEngine != null } }

    init {
        // E2EE: try once per signed-in, verified session (and again after sign-in).
        scope.launch {
            sessionStore.session.map { s -> s?.takeIf { it.user.phoneVerified }?.user?.id }.distinctUntilChanged().collect { id ->
                if (id == null) mlsEngine = null else runCatching { activateMls() }
            }
        }
    }

    /** A short background connection for a push wake-up (the socket is otherwise foreground-only). */
    private val backgroundSync = MutableStateFlow(false)

    /** Notification tap → open this chat (MainActivity sets it, MainNav consumes it). */
    val openChatRequest = MutableStateFlow<String?>(null)

    /** Release-only self-updater (decision 016); disabled in debug builds. */
    val updater = Updater(context, http)

    /** The conversation on screen (`dm:`/`grp:`), if any: its notifications are suppressed, a DM peer is watched. */
    val openConversation = MutableStateFlow<String?>(null)

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
        mls = mlsPipeline,
        mlsEngine = { mlsEngine },
        catchUp = { conv -> catchUpCommits(conv) },
        reactionsDao = db.reactions(),
        groupsEnabled = { mlsEngine?.groupsSupported == true },
        groups = groupStore,
        blobs = { ref -> fetchBlob(ref) },
        onUnrecoverable = { conv -> onGroupUnrecoverable(conv) },
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
                // Everything that arrived while the app was open has been seen in the app.
                scope.launch { sessionStore.setNotifiedUpTo(System.currentTimeMillis()) }
            }
        })
        // Connected only while in the foreground, signed in and unlocked (no background connection).
        scope.launch {
            combine(combine(foreground, backgroundSync) { f, b -> f || b }, sessionStore.session, auth.unlocked, blocked) { fg, s, unlocked, b ->
                if (shouldConnect(fg, s, unlocked, b)) s!!.serverUrl to s.user.id else null
            }
                .distinctUntilChanged()
                .collect { s ->
                    realtime.stop()
                    if (s != null) {
                        realtime.start(
                            RealtimeSession(s.first, s.second, sessionStore.deviceId(), BuildConfig.VERSION_NAME) { force -> bearer(force) },
                        )
                    }
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
            combine(contacts.contacts, openConversation, sessionStore.session) { list, open, s ->
                val friends = list.filter { it.friend }.mapNotNull { it.userId }
                val peer = s?.user?.id?.let { me -> open?.let { lk.codegen.risime.net.dmPeer(it, me) } }
                watchList(friends, peer?.takeIf { o -> friends.any { it.equals(o, ignoreCase = true) } })
            }.distinctUntilChanged().collect { realtime.setWatch(it) }
        }
        // §8.1: register this install for push once signed in and verified (no-op without Firebase).
        scope.launch {
            sessionStore.session.map { s -> s?.takeIf { it.user.phoneVerified }?.user?.id }.distinctUntilChanged().collect { id ->
                if (id != null) push.register()
            }
        }
        // §9.3: refetch GET /friends after every (re)join and on `friend` signals, debounced.
        scope.launch {
            realtime.state.collect { if (it == ConnectionState.Live) requestFriendsRefresh() }
        }
        // §12: group state and owed ops after every (re)join.
        scope.launch {
            realtime.state.collect { if (it == ConnectionState.Live) runCatching { syncGroups() } }
        }
        // The group-op outbox: one runner; a queued retry re-arms the timer.
        scope.launch {
            groupOpsRun.collectLatest {
                var nextAt = runCatching { groupOps.runDue() }.getOrNull()
                while (nextAt != null) {
                    delay((nextAt - System.currentTimeMillis()).coerceAtLeast(250))
                    nextAt = runCatching { groupOps.runDue() }.getOrNull()
                }
            }
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
        runCatching { push.unregister() } // DELETE /me/devices while the token still works
        notifier.cancelAll()
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

    /**
     * Push wake-up (background): join + sync over the normal channel, refetch friends, then post
     * local notifications. A fingerprint-locked session can't sync: a content-free notice instead.
     */
    suspend fun syncAndNotify() {
        val s = sessionStore.current() ?: return
        if (!s.user.phoneVerified) return
        if (s.kind == AuthKind.OIDC && !auth.unlocked.value) {
            notifier.postLocked()
            return
        }
        if (!foreground.value) {
            backgroundSync.value = true
            try {
                withTimeoutOrNull(25_000) { realtime.state.first { it == ConnectionState.Live } }
                delay(1_500) // let live events and the friends refetch land
                contacts.refresh()
            } finally {
                backgroundSync.value = false
            }
        }
        notifyFromLocal()
    }

    private suspend fun notifyFromLocal() {
        val open = openConversation.value.takeIf { foreground.value }
        val since = sessionStore.notifiedUpTo()
        val contactList = contacts.contacts.first()
        val me = sessionStore.current()?.user?.id ?: return
        val names = contactList.filter { it.userId != null }.associate { it.userId!!.lowercase() to it.displayName }
        val reactionAdds = db.reactions().addsSince(since)
        val targets = reactionAdds.map { it.targetMessageId }.distinct().associateWith { db.messages().byMessageId(it) }
        val plan = lk.codegen.risime.push.mergeReactionNotifications(
            planChatNotifications(db.messages().unreadIncoming(), contactList, since, open),
            reactionAdds, { targets[it] }, { id -> names[id.lowercase()] ?: "Someone" }, me, open,
        )
        if (!foreground.value) notifier.postChats(plan)
        plan.maxOfOrNull { it.newestTs }?.let { sessionStore.setNotifiedUpTo(maxOf(it, sessionStore.notifiedUpTo())) }
        val incoming = contacts.friendsState.value.incoming
        val fresh = newRequests(incoming, sessionStore.notifiedRequests())
        if (!foreground.value) notifier.postRequests(fresh)
        sessionStore.setNotifiedRequests(incoming.map { it.id }.toSet())
    }

    private val catchUpLock = kotlinx.coroutines.sync.Mutex()

    /**
     * §10.2/§12.8 recovery: fetch commits since our epoch and apply them out of band (no cursor
     * move), page by page while `has_more`. A `grp:` log that no longer reaches our epoch (410
     * log_expired) is unrecoverable: rejoin.
     */
    private suspend fun catchUpCommits(conversationId: String): Unit = catchUpLock.withLock {
        var pages = 0
        while (pages++ < 40) {
            val g = mlsEngine?.group(conversationId) ?: return // no group yet: our Welcome comes through the inbox
            val r = when (val res = api.mlsCommits(conversationId, g.epoch, if (lk.codegen.risime.net.isGroupConversation(conversationId)) 50 else null)) {
                is ApiResult.Ok -> res.value
                is ApiResult.Error -> {
                    if (res.code == AuthErrors.LOG_EXPIRED || res.httpStatus == 410) onGroupUnrecoverable(conversationId)
                    return
                }
                is ApiResult.NetworkError -> return
            }
            engine.applyOutOfBand(
                r.commits.map { c ->
                    lk.codegen.risime.net.Event(
                        "catchup:$conversationId:${g.generation}:${c.epoch}", lk.codegen.risime.net.Event.KIND_MLS_COMMIT,
                        lk.codegen.risime.net.ProtocolJson.encodeToJsonElement(
                            lk.codegen.risime.net.MlsCommitEvent.serializer(),
                            lk.codegen.risime.net.MlsCommitEvent(conversationId, g.generation, c.epoch, c.commit, c.fromDevice, c.commitRef),
                        ) as kotlinx.serialization.json.JsonObject,
                    )
                },
            )
            val after = mlsEngine?.group(conversationId)?.epoch ?: return
            if (!r.hasMore || after <= g.epoch) return
        }
    }

    /** Chat data only; the local behaviour log stays on the device. */
    private fun clearFriendsMemory() = contacts.clearMemory()

    private suspend fun wipeDb() = db.withTransaction {
        db.wipe().messages()
        db.wipe().contacts()
        db.wipe().syncState()
        db.wipe().seenEvents()
        db.wipe().mlsKv()
        db.wipe().mlsPending()
        db.wipe().reactions()
        db.wipe().groups()
        db.wipe().groupMembers()
        db.wipe().groupOps()
        mlsEngine = null
        mlsDbKey.destroy()
    }
}

private inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(f(value))
    is ApiResult.Error -> this
    is ApiResult.NetworkError -> this
}
