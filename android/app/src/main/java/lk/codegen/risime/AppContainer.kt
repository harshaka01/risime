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
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import lk.codegen.risime.data.BehaviourLog
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.ContactsRepository
import lk.codegen.risime.data.AuthKind
import lk.codegen.risime.data.LegacyServerUrlMigration
import lk.codegen.risime.data.LocalAccount
import lk.codegen.risime.data.WipeReason
import lk.codegen.risime.data.mls.DeviceRegistrar
import lk.codegen.risime.data.mls.KeystoreDbKeyWrapper
import lk.codegen.risime.data.mls.KvSealer
import lk.codegen.risime.data.mls.MembershipExecutor
import lk.codegen.risime.data.mls.MembershipOutcome
import lk.codegen.risime.data.mls.MlsDbKey
import lk.codegen.risime.data.mls.MlsEngineFactory
import lk.codegen.risime.data.mls.Registration
import lk.codegen.risime.data.mls.RegistrationRetry
import lk.codegen.risime.data.mls.settled
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Bound on each best-effort server call at logout (the local logout never waits longer). */
private const val LOGOUT_NETWORK_MS = 5_000L

private const val SWITCH_CANCELLED = "Sign-in cancelled — the chats on this phone are kept."

/** A Keycloak end_session to open in the browser (id_token_hint + post-logout redirect). */
data class EndSession(val issuer: String, val idToken: String)

private val Context.dataStore by preferencesDataStore(
    name = "risime",
    produceMigrations = { listOf(LegacyServerUrlMigration(BuildConfig.DEFAULT_SERVER_URL)) },
)

/** Manual DI for 0.1 (no framework). One instance per process, owned by [RisiMeApp]. */
class AppContainer(
    context: Context,
    /** Tests open the same file with the bundled SQLite driver (no framework SQLite on the JVM). */
    openDb: (Context) -> AppDatabase = AppDatabase::create,
    prefs: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> = context.dataStore,
    /**
     * The engine's transactions: Room's withTransaction on the app's framework SQLite. A JVM test on
     * the bundled driver (no SupportSQLiteOpenHelper) passes its own runner.
     */
    transactions: ((AppDatabase) -> TransactionRunner)? = null,
) {
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.w("RisiMe", "background task failed", e) },
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val sessionStore = SessionStore(prefs, BuildConfig.DEFAULT_SERVER_URL)
    val db = openDb(context)

    /** The only place that decides to delete local chats (logout, server change, confirmed other account). */
    val localAccount = LocalAccount(sessionStore, { reason -> wipeDb(reason) }, { Log.w("RisiMe", it) })
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
        override suspend fun rejoin(conversationId: String) = api.mlsRejoin(conversationId, sessionStore.deviceId())
        override suspend fun reset(conversationId: String, generation: Long) =
            when (val r = api.resetGroup(conversationId, generation, sessionStore.deviceId())) {
                is ApiResult.Ok -> ApiResult.Ok(r.value.generation)
                is ApiResult.Error -> r
                is ApiResult.NetworkError -> r
            }
    }
    /** §10.3 `mls_membership` seen (any conversation): open chats that aren't E2EE re-check readiness (P0-1). */
    val mlsMembershipSeen = kotlinx.coroutines.flow.MutableSharedFlow<lk.codegen.risime.net.MlsMembershipEvent>(extraBufferCapacity = 16)
    val membershipExecutor by lazy { MembershipExecutor({ mlsEngine }, mlsApi) { conv -> catchUpCommits(conv) } }
    val mlsPipeline by lazy {
        MlsPipeline(
            { mlsEngine }, db.mlsPending(),
            onMembership = { a ->
                mlsMembershipSeen.tryEmit(a.event)
                // §17.8: a device that left my device set loses its history approval.
                if (a.event.change == "removed" && BuildConfig.HISTORY_SHARE_ENABLED) scope.launch(Dispatchers.IO) {
                    if (a.event.userId.equals(sessionStore.current()?.user?.id, true)) runCatching { history.onOwnDeviceRemoved(a.event.deviceId) }
                }
                scope.launch {
                    delay(a.delayMs)
                    val r = membershipExecutor.execute(a)
                    Log.i("RisiMe", "mls_membership ${a.event.change}: $r")
                }
            },
            onDmOp = { o ->
                // v1.16: named to re-add a DM member's device (after this event's transaction).
                scope.launch(Dispatchers.IO) {
                    val r = runCatching { membershipExecutor.executeOp(o) }.getOrElse { MembershipOutcome.Failed(it.message ?: "error") }
                    Log.i("RisiMe", "mls_dm_op: $r")
                }
            },
            log = { Log.w("RisiMe", "mls: $it") },
            onJoined = { scope.launch { deviceRegistrar.topUp() } },
            onParkedAhead = { conv -> scope.launch { catchUpCommits(conv) } },
        )
    }
    val mlsUpgrader by lazy { MlsUpgrader({ mlsEngine }, mlsApi) }

    /** v1.16: DM repairs in flight (one per conversation; later kicks while it runs are dropped). */
    private val dmRepairs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** v1.16 §5: an e2ee DM send couldn't be encrypted: check against the server, re-add or reset; flush when encrypted. */
    fun repairDm(conversationId: String) {
        if (!dmRepairs.add(conversationId)) return
        scope.launch(Dispatchers.IO) {
            try {
                val me = sessionStore.current()?.user?.id ?: return@launch
                val peer = lk.codegen.risime.net.dmPeer(conversationId, me) ?: return@launch
                val s = mlsUpgrader.ensure(conversationId, me, peer, verify = true)
                Log.i("RisiMe", "dm repair: $s")
                if (s is lk.codegen.risime.data.mls.E2eeState.Encrypted) engine.flushOutbox()
            } catch (t: Throwable) {
                Log.w("RisiMe", "dm repair failed: ${t.message}")
            } finally {
                dmRepairs.remove(conversationId)
            }
        }
    }

    // ---- Groups (contract v1.9 §12). Enabled only with a groups-capable MLS core. ----
    val groupStore by lazy {
        lk.codegen.risime.data.groups.GroupStore(
            db.groups(), db.groupOps(), db.messages(), { sessionStore.deviceId() },
            metaOf = { conv -> mlsEngine?.groupMeta(conv) },
            needsRefresh = { conv -> scope.launch { refreshGroup(conv) } },
            // §13.3: decided at apply time, so a fresh-install replay never posts "added you" later.
            onAddedMe = { conv, actor -> if (!engine.replayingFresh) scope.launch { notifyAddedToGroup(conv, actor) } },
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
        override suspend fun rejoin(id: String) = api.rejoinGroup(id, dev())
        override suspend fun reset(id: String, generation: Long) = api.resetGroup(id, generation, dev()).map { it.generation }
        override suspend fun claim(userIds: List<String>, conversationId: String?) =
            api.claimKeyPackages(userIds, dev(), conversationId).map { it.devices }
        override suspend fun commit(id: String, body: lk.codegen.risime.net.GroupCommitRequest) = api.groupCommit(id, body, dev()).map { it.epoch }
        override suspend fun uploadBlob(conversationId: String, bytes: ByteArray) = api.uploadBlob(conversationId, bytes).map { it.ref() }
    }

    private val dbTx: TransactionRunner = transactions?.invoke(db) ?: object : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
    }

    // ---- Images (contract v1.11 §14, decision 042). Only with the native core (media API). ----
    private val appContext: Context = context.applicationContext

    fun appContentResolver(): android.content.ContentResolver = appContext.contentResolver
    val mediaCrypto: lk.codegen.risime.data.media.MediaCrypto? by lazy {
        if (BuildConfig.CRYPTO_AVAILABLE) lk.codegen.risime.data.media.MediaCrypto.get() else null
    }
    private val mediaFiles = lk.codegen.risime.data.media.MediaFiles(File(context.noBackupFilesDir, "media"))
    private val mediaSealer = lk.codegen.risime.data.media.MediaSealer { KvSealer(mlsDbKey.get()) }
    val images = lk.codegen.risime.data.media.ImageRepository(
        db.media(), db.messages(), dbTx, mediaSealer, mediaFiles, { mediaCrypto }, api,
        enqueueUpload = { id -> lk.codegen.risime.push.MediaUploadWorker.enqueue(appContext, id) },
        restartUpload = { id -> lk.codegen.risime.push.MediaUploadWorker.enqueue(appContext, id, replace = true) },
        cancelUpload = { id -> lk.codegen.risime.push.MediaUploadWorker.cancel(appContext, id) },
        enqueueDownloads = { scheduleImageDownloads() },
        log = { Log.w("RisiMe", it) },
    )
    val imageUploader = lk.codegen.risime.data.media.ImageUploader(api, db.media(), db.messages(), mediaFiles)
    val imageDownloader = lk.codegen.risime.data.media.ImageDownloader(api, db.media(), mediaFiles, mediaSealer, { mediaCrypto })
    val imageLoader by lazy { lk.codegen.risime.ui.chat.ImageLoader(images) }

    // ---- §18 profile photos and group icons (v1.17) ----

    private val photoPrefs by lazy { appContext.getSharedPreferences("profile_photos", Context.MODE_PRIVATE) }
    private val avatarApi = object : lk.codegen.risime.data.profile.AvatarApi {
        override suspend fun uploadAvatar(clientBlobId: String, file: File) = api.uploadAvatarBlob(clientBlobId, file)
        override suspend fun uploadIcon(conversationId: String, clientBlobId: String, file: File) =
            api.uploadMediaBlob(conversationId, clientBlobId, file, purpose = "icon")
        override suspend fun download(blobId: String, into: File, etagHex: String) = api.downloadBlobTo(blobId, into, 0, etagHex)
        override suspend fun delete(blobId: String) = api.deleteBlob(blobId)
    }
    val profilePhotos: lk.codegen.risime.data.profile.ProfilePhotos by lazy {
        lk.codegen.risime.data.profile.ProfilePhotos(
            db.profilePhotos(), dbTx, { KvSealer(mlsDbKey.get()) },
            lk.codegen.risime.data.profile.AvatarFiles(File(context.noBackupFilesDir, "avatars")), { mediaCrypto }, avatarApi,
            sendSilent = { conv, plaintext -> engine.sendSilent(conv, plaintext) },
            mls = { mlsEngine },
            conversations = { photoConversations() },
            me = { sessionStore.current()?.user?.id },
            serverNow = { serverClock.serverNow() },
            scope = scope,
            kv = object : lk.codegen.risime.data.profile.PhotoKv {
                override fun get(key: String) = photoPrefs.getString(key, null)
                override fun set(key: String, value: String?) = photoPrefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            },
            blocked = { id -> contacts.friendsState.value.blocked.any { it.userId.equals(id, true) } },
            autoDownload = {
                val n = lk.codegen.risime.push.currentNetKind(appContext)
                n == lk.codegen.risime.data.media.NetKind.UNMETERED || n == lk.codegen.risime.data.media.NetKind.METERED
            },
            log = { Log.w("RisiMe", "photos: $it") },
        )
    }
    val avatarLoader by lazy { lk.codegen.risime.ui.common.AvatarLoader(profilePhotos) }

    /** §18.5: the conversations a photo may go to (friends' DMs and groups); the photo code keeps those with an MLS group. */
    private suspend fun photoConversations(): List<String> {
        val me = sessionStore.current()?.user?.id ?: return emptyList()
        val dms = db.contacts().all().first().filter { it.friend && it.userId != null }.map { lk.codegen.risime.net.dmConversationId(me, it.userId!!) }
        val groups = db.groups().allNow().filter { !it.readOnly }.map { it.conversationId }
        return dms + groups
    }

    /** MLS state changed (a commit, a Welcome): the §18.5 triggers and the group icons, after a debounce. */
    private val mlsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** §14.1: advertise `images` only with a groups-capable core whose media API is present (receive and render work). */
    fun imagesSupported(): Boolean = mlsEngine?.groupsSupported == true && mediaCrypto != null

    /** §14.7 Receiving 7: background downloads on unmetered networks (WorkManager), and now if the app is open on one. */
    fun scheduleImageDownloads() {
        lk.codegen.risime.push.MediaDownloadWorker.enqueue(appContext)
        if (foreground.value && lk.codegen.risime.push.currentNetKind(appContext) == lk.codegen.risime.data.media.NetKind.UNMETERED) {
            scope.launch { downloadAllImages() }
        }
    }

    /** Downloads every downloadable image, newest first (the downloader allows 3 at a time). */
    suspend fun downloadAllImages(): List<lk.codegen.risime.data.media.DownloadOutcome> = kotlinx.coroutines.coroutineScope {
        val rows = db.media().downloadable()
        rows.map { r -> async { downloadImage(r.clientMsgId) } }.map { it.await() }.also { images.evict() }
    }

    /** One image now (tap, or visible in an open chat when the network allows it); a failed check gets one re-download. */
    suspend fun downloadImage(clientMsgId: String): lk.codegen.risime.data.media.DownloadOutcome {
        var o = imageDownloader.download(clientMsgId)
        if (o == lk.codegen.risime.data.media.DownloadOutcome.Retry(0)) o = imageDownloader.download(clientMsgId)
        return o
    }

    /** A bubble is on screen: download it if the network policy allows (tap-only on Data Saver / roaming). */
    fun onImageVisible(clientMsgId: String) {
        val net = lk.codegen.risime.push.currentNetKind(appContext)
        if (lk.codegen.risime.data.media.autoDownload(net, visibleInOpenChat = true)) {
            scope.launch { downloadImage(clientMsgId) }
        } else {
            lk.codegen.risime.push.MediaDownloadWorker.enqueue(appContext) // the next unmetered network
        }
    }

    /** After an upload: the outbox sends the envelope (a short background connection if the app isn't open). */
    suspend fun flushOutboxAfterUpload() {
        if (realtime.state.value == ConnectionState.Live) {
            engine.flushOutbox()
            return
        }
        if (foreground.value) return // connecting: onLive flushes the outbox
        if (sessionStore.current() == null) return
        backgroundSync.value = true
        try {
            withTimeoutOrNull(25_000) { realtime.state.first { it == ConnectionState.Live } }
            delay(2_000) // onLive flushes the outbox
        } finally {
            backgroundSync.value = false
        }
    }

    val groupOps by lazy {
        lk.codegen.risime.data.groups.GroupOpsExecutor(
            { mlsEngine }, groupApi, db.groupOps(), db.groups(), groupStore, dbTx,
            me = { sessionStore.current()?.user?.id }, deviceId = { sessionStore.deviceId() },
            catchUp = { conv -> catchUpCommits(conv) },
            log = { Log.i("RisiMe", it) },
            openIcon = { conv, sealed -> openGroupIcon(conv, sealed) },
        )
    }

    /** §18.7: a pending group-photo op's icon object (its key inside) is sealed with the database key until it is committed. */
    fun sealGroupIcon(conversationId: String, icon: kotlinx.serialization.json.JsonElement): String =
        java.util.Base64.getEncoder().encodeToString(
            KvSealer(mlsDbKey.get()).seal("group-icon-op", conversationId.lowercase().toByteArray(), lk.codegen.risime.net.ProtocolJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), icon).toByteArray()),
        )

    private fun openGroupIcon(conversationId: String, sealed: ByteArray): kotlinx.serialization.json.JsonElement? = runCatching {
        lk.codegen.risime.net.ProtocolJson.parseToJsonElement(KvSealer(mlsDbKey.get()).open("group-icon-op", conversationId.lowercase().toByteArray(), sealed).toString(Charsets.UTF_8))
    }.getOrNull()

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
        // §12.8: groups this device holds no MLS state for (a sign-in after a logout keeps the device
        // id but starts a new MLS state): rejoin them, in the background, idempotently.
        val engine = mlsEngine ?: return
        val queued = groupStore.queueRejoins(r.value.groups, me, { conv -> engine.group(conv)?.generation })
        if (queued.isNotEmpty()) Log.i("RisiMe", "rejoining ${queued.size} group(s) on this device")
        kickGroupOps()
    }

    /** §12.7 S4: "Kamal added you to <name>" (the name from the Welcome's group_meta), unless that chat is open. */
    private suspend fun notifyAddedToGroup(conversationId: String, actor: String) {
        if (foreground.value) return
        val name = db.groups().get(conversationId)?.name ?: mlsEngine?.groupMeta(conversationId)?.name
        val who = db.groups().members(conversationId).firstOrNull { it.userId.equals(actor, true) }?.displayName
            ?: contacts.contacts.first().firstOrNull { it.userId.equals(actor, true) }?.displayName ?: "Someone"
        notifier.postAddedToGroup(conversationId, lk.codegen.risime.push.addedToGroupText(who, name))
    }

    /** GET /groups/{id}: server truth for members, roles and owed ops; 404 keeps the local snapshot read-only (S3). */
    /** `GET /groups/{id}` applied locally; @return the server's group (null when unavailable). */
    suspend fun refreshGroup(conversationId: String): lk.codegen.risime.net.Group? {
        val me = sessionStore.current()?.user?.id ?: return null
        return when (val r = api.group(conversationId)) {
            is ApiResult.Ok -> r.value.group.also { db.withTransaction { groupStore.applyServerGroup(it, me) } }
            is ApiResult.Error -> null.also { if (r.httpStatus == 404) db.withTransaction { groupStore.markGone(conversationId) } }
            is ApiResult.NetworkError -> null
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
            imagesSupported = { imagesSupported() },
            historySupported = { BuildConfig.HISTORY_SHARE_ENABLED && history.supported() },
            callsSupported = { runCatching { calls.canAdvertise() }.getOrDefault(false) },
            videoSupported = { runCatching { calls.canAdvertiseVideo() }.getOrDefault(false) },
            groupCallsSupported = { runCatching { calls.canAdvertiseGroupCalls() }.getOrDefault(false) },
            groupsReplacedFor = { sessionStore.groupsKeyPackagesFor() },
            setGroupsReplacedFor = { sessionStore.setGroupsKeyPackagesFor(it) },
        )
    }

    /**
     * Load the MLS core if this build has it and the server offers attestation keys (E2EE on), then
     * register with the MLS key (→ attestation → key packages). Returns null when MLS doesn't apply
     * (no core, E2EE off: register for push instead), else whether the device is registered.
     */
    suspend fun activateMls(): Boolean? {
        if (mlsEngine != null) return true
        if (!BuildConfig.CRYPTO_AVAILABLE) return null
        val session = sessionStore.current()?.takeIf { it.user.phoneVerified } ?: return false
        val factory = MlsEngineFactory.get() ?: return null
        val keys = api.attestationKeys()
        if (keys !is ApiResult.Ok) return if (keys is ApiResult.Error && keys.httpStatus in 400..499) null else false
        val served = keys.value.keys
        if (served.isEmpty()) return null // mls_unavailable / no key: E2EE is off
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
            return null
        }
        mlsEngine = lk.codegen.risime.data.mls.ObservedMlsEngine(engine) { conv ->
            mlsChanged.tryEmit(Unit)
            // §20.6 K3: a merged commit rekeys a running group call at once.
            runCatching { calls.onGroupChanged(conv) }
        }
        mlsChanged.tryEmit(Unit)
        return when (val r = deviceRegistrar.register(runCatching { push.currentToken() }.getOrNull())) {
            is Registration.Mls -> {
                Log.i("RisiMe", "MLS device registered, ${r.keyPackages} key packages")
                // Key packages are up with `groups`: now the server can re-add this device (§12.8).
                scope.launch { runCatching { syncGroups() } }
                true
            }
            Registration.MlsUnavailable -> {
                mlsEngine = null // the server turned it down: stay a v1.6 client (pushed-only registration done)
                null
            }
            else -> {
                Log.i("RisiMe", "MLS registration failed, will retry: $r")
                mlsEngine = null
                false
            }
        }
    }

    /**
     * The device's capabilities can change after registration (notifications allowed later, Telecom
     * registered): re-advertise them when they did (nightly.16: a fresh install registered before the
     * notification prompt was answered and never advertised `calls`). Called on every app start into
     * the foreground and after a permission answer.
     */
    fun refreshCapabilities() {
        scope.launch {
            runCatching {
                if (mlsEngine == null) return@runCatching
                val r = deviceRegistrar.refreshCapabilities(runCatching { push.currentToken() }.getOrNull()) ?: return@runCatching
                Log.i("RisiMe", "capabilities changed: re-registered ($r)")
            }
        }
    }

    /** One registration attempt: MLS when it applies, else push-only. True when nothing is left to retry. */
    private suspend fun registerDeviceOnce(): Boolean = when (activateMls()) {
        true -> true
        false -> false
        null -> push.register().settled()
    }

    val push by lazy {
        PushManager(context, api, sessionStore, deviceRegistrar, { mlsEngine != null }, canAuthenticate = { canAuthenticate() })
    }

    /** A bearer exists: a dev token, or an unlocked OIDC session (a locked one sends no token → 401). */
    private suspend fun canAuthenticate(): Boolean =
        sessionStore.current()?.let { it.kind == AuthKind.DEV || auth.unlocked.value } ?: false

    init {
        // E2EE: try once per signed-in, verified session that can authenticate. An OIDC session is
        // locked (no bearer) until the fingerprint unlock, so registering before it got 401 and left
        // E2EE and groups off for the whole process (P0 nightly.10 finding): wait for the unlock.
        scope.launch {
            combine(sessionStore.session, auth.unlocked) { s, unlocked ->
                s?.takeIf { it.user.phoneVerified && (it.kind == AuthKind.DEV || unlocked) }?.user?.id
            }.distinctUntilChanged().collectLatest { id ->
                if (id == null) {
                    mlsEngine = null
                    return@collectLatest
                }
                // Device registration (MLS, else push) until it succeeds, with backoff; again after
                // every unlock / sign-in (this restarts). A 401 is retried after a token refresh by
                // ApiClient; it never signs out or wipes.
                RegistrationRetry.untilDone { registerDeviceOnce() }
            }
        }
    }

    /** A short background connection for a push wake-up (the socket is otherwise foreground-only). */
    private val backgroundSync = MutableStateFlow(false)

    /** Notification tap → open this chat (MainActivity sets it, MainNav consumes it). */
    val openChatRequest = MutableStateFlow<String?>(null)

    /** Release-only self-updater (decision 016); disabled in debug builds. */
    val updater = Updater(context, http, scope)

    /** The conversation on screen (`dm:`/`grp:`), if any: its notifications are suppressed, a DM peer is watched. */
    val openConversation = MutableStateFlow<String?>(null)

    /** §15.7 server-clock offset (in memory, persisted for a cold start). */
    val serverClock = lk.codegen.risime.data.deletes.ServerClock(persist = { sessionStore.setServerOffset(it) })

    /**
     * §15.6 after a delete committed: posted notifications are rebuilt silently (foreground too), a
     * best-effort WAL checkpoint runs off the UI thread, purged image bitmaps leave memory.
     */
    fun onDeletesApplied() {
        scope.launch { runCatching { refreshPostedNotifications() }.onFailure { Log.w("RisiMe", "notification refresh: ${it.message}") } }
        checkpointSoon()
    }

    private val checkpointLock = kotlinx.coroutines.sync.Mutex()

    /** `PRAGMA wal_checkpoint(TRUNCATE)`, best effort (busy while readers are active: retried by the next delete). */
    fun checkpointSoon() {
        scope.launch(Dispatchers.IO) {
            if (!checkpointLock.tryLock()) return@launch
            try {
                delay(500)
                runCatching { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
            } finally {
                checkpointLock.unlock()
            }
        }
    }

    /** §15.6 (android R7): every chat with a posted notification, re-planned from all its unread rows, reposted silently. */
    suspend fun refreshPostedNotifications() {
        if (notifier.activeChatIds().isEmpty()) return
        val me = sessionStore.current()?.user?.id ?: return
        val open = openConversation.value.takeIf { foreground.value }
        val contactList = contacts.contacts.first()
        val names = contactList.filter { it.userId != null }.associate { it.userId!!.lowercase() to it.displayName }
        val reactionAdds = db.reactions().addsSince(System.currentTimeMillis() - 24 * 3600_000L)
        val targets = reactionAdds.map { it.targetMessageId }.distinct().associateWith { db.messages().byMessageId(it) }
        val plan = lk.codegen.risime.push.mergeReactionNotifications(
            planChatNotifications(
                db.messages().unreadIncoming(), contactList, Long.MIN_VALUE, open,
                groupNames = db.groups().allNow().associate { it.conversationId to lk.codegen.risime.data.groups.groupDisplayName(it.name) },
                memberNames = db.groups().observeAllMembers().first().groupBy { it.conversationId }
                    .mapValues { (_, ms) -> ms.associate { it.userId.lowercase() to it.displayName } },
            ),
            reactionAdds, { targets[it] }, { id -> names[id.lowercase()] ?: "Someone" }, me, open,
        )
        notifier.refreshChats(plan)
    }

    // ---- 1:1 voice calls (contract v1.13 §16, decisions 051, 052) ----
    private val callPort = object : lk.codegen.risime.calls.CallAppPort {
        override val scope get() = this@AppContainer.scope
        override val api get() = this@AppContainer.api
        override val connection get() = realtime.state
        override val callMarkDao get() = db.callMarks()
        override suspend fun me() = sessionStore.current()?.user?.id
        override suspend fun deviceId() = sessionStore.deviceId()
        override suspend fun sessionLocked(): Boolean = !auth.unlocked.value && sessionStore.current()?.kind == AuthKind.OIDC
        override fun serverNow() = serverClock.serverNow()
        override suspend fun displayName(userId: String) =
            contacts.contacts.first().firstOrNull { it.userId.equals(userId, true) }?.displayName
                // §20: group members who aren't my friends are named from the group's member list.
                ?: runCatching { db.groups().observeAllMembers().first().firstOrNull { it.userId.equals(userId, true) }?.displayName }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "Someone"
        override suspend fun sendSignal(conv: String, peer: String, env: lk.codegen.risime.calls.CallEnvelope.Env, media: String) = engine.sendCallSignal(conv, peer, env, media)
        override suspend fun localMissedCall(conv: String, peer: String, callId: String, video: Boolean) = engine.insertLocalMissedCall(conv, peer, callId, video)
        override suspend fun queueCallEnd(conv: String, peer: String, env: lk.codegen.risime.calls.CallEnvelope.End, rangUnanswered: Boolean) {
            engine.queueCallEnd(conv, peer, env, rangUnanswered)
        }
        override fun foreground() = foreground.value

        // ---- §20 group calls ----
        override val callKeysSupported: Boolean get() = mlsEngine?.callKeysSupported == true
        override suspend fun groupCatchUp(conv: String) { engine.catchUpGroup(conv) }
        override suspend fun groupEpoch(conv: String): Long? = withContext(Dispatchers.IO) { mlsEngine?.group(conv)?.epoch }
        override suspend fun groupFrameKeys(conv: String, callId: String): lk.codegen.risime.data.mls.CallKeys = withContext(Dispatchers.IO) {
            val m = mlsEngine ?: throw lk.codegen.risime.data.mls.CallKeysException(lk.codegen.risime.data.mls.CallKeysException.Kind.UnknownGroup, "no MLS")
            m.callFrameKeys(conv, callId)
        }
        override suspend fun sendGroupSignal(conv: String, env: lk.codegen.risime.calls.CallEnvelope.Env, media: String) = engine.sendGroupCallSignal(conv, env, media)
        override suspend fun groupCallStarted(conv: String, env: lk.codegen.risime.calls.GroupCallEnvelope) { engine.queueGroupCallStarted(conv, env) }
        override suspend fun groupCallEnded(conv: String, env: lk.codegen.risime.calls.GroupCallEnvelope) { engine.sendGroupCallEnded(conv, env) }
        override suspend fun groupCallOver(conv: String, callId: String) { engine.markGroupCallOver(conv, callId) }
        override suspend fun groupName(conv: String): String =
            runCatching { db.groups().get(conv)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Group"
    }

    val calls: lk.codegen.risime.calls.CallManager by lazy {
        lk.codegen.risime.calls.CallManager(appContext, callPort).also { m -> m.openConversation = { openConversation.value } }
    }

    /** The pipeline's call hooks (the manager is created on first use). */
    private val callHooks = object : lk.codegen.risime.calls.CallHooks {
        override suspend fun rangUnanswered(callId: String) = calls.hooks.rangUnanswered(callId)
        override suspend fun onSignal(s: lk.codegen.risime.calls.InboundCall) = calls.hooks.onSignal(s)
        override suspend fun onCallEnd(conversationId: String, fromUser: String, fromDevice: String?, end: lk.codegen.risime.calls.CallEnvelope.End) =
            calls.hooks.onCallEnd(conversationId, fromUser, fromDevice, end)
        override suspend fun onPageEnd() = calls.hooks.onPageEnd()
        override fun onMissedCall(conversationId: String, from: String, video: Boolean) = calls.hooks.onMissedCall(conversationId, from, video)
        override suspend fun onGroupCallLine(conversationId: String, from: String, env: lk.codegen.risime.calls.GroupCallEnvelope) =
            calls.hooks.onGroupCallLine(conversationId, from, env)
    }

    // ---- History sharing (contract v1.15 §17). Behind BuildConfig.HISTORY_SHARE_ENABLED until green. ----
    private val historyCrypto: lk.codegen.risime.data.mls.HistoryCrypto? by lazy {
        if (BuildConfig.CRYPTO_AVAILABLE) lk.codegen.risime.data.mls.HistoryCrypto.get() else null
    }

    /** The export worker holds the realtime connection (like a push wake) while it runs. */
    private val historyConnection = MutableStateFlow(0)

    /** Only while unlocked with a verified session (a locked app has no bearer, §17.8). */
    suspend fun canExportHistory(): Boolean =
        BuildConfig.HISTORY_SHARE_ENABLED && sessionStore.current()?.user?.phoneVerified == true && canAuthenticateNow()

    private suspend fun canAuthenticateNow(): Boolean =
        sessionStore.current()?.let { it.kind == AuthKind.DEV || auth.unlocked.value } ?: false

    suspend fun <T> withHistoryConnection(block: suspend () -> T): T {
        historyConnection.value++
        try {
            withTimeoutOrNull(25_000) { realtime.state.first { it == ConnectionState.Live } }
            return block()
        } finally {
            historyConnection.value--
        }
    }

    val history: lk.codegen.risime.data.history.HistoryManager by lazy {
        lk.codegen.risime.data.history.HistoryManager(
            db.history(), db.messages(), db.deletes(), db.media(), mediaSealer, db.reactions(),
            images.takeIf { BuildConfig.CRYPTO_AVAILABLE }, dbTx, { mlsEngine }, { historyCrypto }, { realtime },
            object : lk.codegen.risime.data.history.HistoryBlobApi {
                override suspend fun upload(conversationId: String, requestId: String, clientBlobId: String, file: File) =
                    api.uploadHistoryBlob(conversationId, requestId, clientBlobId, file, sessionStore.deviceId())

                override suspend fun download(blobId: String, into: File): ApiResult<Long> {
                    into.delete()
                    return api.downloadBlobTo(blobId, into, 0, null)
                }
            },
            { engine }, { conv -> catchUpCommits(conv) },
            me = { sessionStore.current()?.user?.id }, deviceId = { sessionStore.deviceId() },
            membersAsk = { sessionStore.historyMembers.first() }, ownAllowed = { sessionStore.historyOwn.first() },
            workDir = File(appContext.noBackupFilesDir, "history"), scope = scope,
            ports = object : lk.codegen.risime.data.history.HistoryPorts {
                override fun startExport(requestId: String) = lk.codegen.risime.push.HistoryExportWorker.enqueue(appContext, requestId)
                override fun cancelExport(requestId: String) = lk.codegen.risime.push.HistoryExportWorker.cancel(appContext, requestId)
                override fun promptsChanged(asks: List<lk.codegen.risime.data.db.HistoryProvideEntity>) {
                    if (asks.isEmpty()) {
                        notifier.cancelHistoryPrompt()
                        return
                    }
                    scope.launch {
                        val a = asks.first()
                        notifier.postHistoryPrompt(lk.codegen.risime.ui.history.historyPromptText(a, historyName(a.requesterUser)), a.conversationId.takeIf { !a.own })
                    }
                }
                override fun sharedWithOwnDevice() = notifier.postHistoryShared()
                override fun imagesImported() = scheduleImageDownloads()
                override suspend fun name(userId: String) = historyName(userId)
            },
            log = { Log.w("RisiMe", "history: $it") },
        )
    }

    /** A display name for history prompts and labels (contacts, then group members). */
    suspend fun historyName(userId: String): String =
        contacts.contacts.first().firstOrNull { it.userId.equals(userId, true) }?.displayName
            ?: db.groups().observeAllMembers().first().firstOrNull { it.userId.equals(userId, true) }?.displayName
            ?: "Someone"

    val engine: ChatEngine = ChatEngine(
        messages = db.messages(),
        sync = db.sync(),
        tx = dbTx,
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
        onDmNeedsRepair = { conv -> repairDm(conv) },
        // Off the main thread: the MLS core's storage callbacks run Room transactions synchronously.
        io = Dispatchers.IO,
        // §13.3 R7: a fresh install never notifies for replayed events (messages, reactions).
        onFreshReplayDone = { sessionStore.setNotifiedUpTo(maxOf(System.currentTimeMillis(), sessionStore.notifiedUpTo())) },
        images = images.takeIf { BuildConfig.CRYPTO_AVAILABLE },
        deletes = db.deletes(),
        onDeletesApplied = { onDeletesApplied() },
        serverClock = serverClock,
        log = { Log.w("RisiMe", "deletes: $it") },
        calls = callHooks,
        historyDao = db.history(),
        history = history.takeIf { BuildConfig.HISTORY_SHARE_ENABLED },
        profilePhotos = profilePhotos.takeIf { BuildConfig.CRYPTO_AVAILABLE },
    )


    val realtime: RealtimeClient = PhoenixRealtimeClient(
        http, scope, engine, signals = presence, refusals = { onSocketRefused() },
    )

    /** Decision 055: each time the connection goes live, missing history is asked for once per chat. */
    private val historyAutoRequest = if (BuildConfig.HISTORY_SHARE_ENABLED) scope.launch {
        realtime.state.collectLatest { st ->
            if (st == lk.codegen.risime.realtime.ConnectionState.Live) {
                kotlinx.coroutines.delay(20_000) // groups joined and gaps recorded first
                runCatching { history.autoRequestAll() }.onFailure { Log.w("RisiMe", "history auto-request: ${it.message}") }
            }
        }
    } else null

    private val foreground = MutableStateFlow(false)

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground.value = true
                refreshCapabilities()
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
            // android R6: a ringing, connecting or active call keeps the socket up regardless of foreground.
            combine(combine(foreground, backgroundSync, calls.keepConnected, historyConnection) { f, b, c, h -> f || b || c || h > 0 }, sessionStore.session, auth.unlocked, blocked) { fg, s, unlocked, b ->
                if (shouldConnect(fg, s, unlocked, b)) s!!.serverUrl to s.user.id else null
            }
                .distinctUntilChanged()
                .collect { s ->
                    realtime.stop()
                    if (s != null) {
                        // History recovery before the join reads the cursor (P0 nightly.10).
                        runCatching {
                            localAccount.recoverHistoryIfNeeded({ db.messages().countAll() }, { db.sync().cursor() }, { db.wipe().syncState() })
                        }
                        realtime.start(
                            RealtimeSession(s.first, s.second, sessionStore.deviceId(), BuildConfig.VERSION_NAME) { force -> bearer(force) },
                        )
                    }
                }
        }
        // §18.5: the photo triggers after MLS changes (debounced: the change's transaction has committed by then).
        if (BuildConfig.CRYPTO_AVAILABLE) {
            scope.launch {
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                mlsChanged.debounce(1_500).collect { runCatching { profilePhotos.reconcile() }.onFailure { Log.w("RisiMe", "photos: ${it.message}") } }
            }
            scope.launch {
                realtime.state.collect { st ->
                    if (st == ConnectionState.Live) {
                        mlsChanged.tryEmit(Unit)
                        runCatching { profilePhotos.downloadAll() }
                    }
                }
            }
            scope.launch { openConversation.filterNotNull().collect { conv -> runCatching { profilePhotos.onChatOpened(conv) } } }
            scope.launch {
                runCatching { profilePhotos.startup(db.profilePhotos().observeAll().first()) }
            }
        }
        // Updater (P0-1): a check on every foreground (onStart, 15 min throttle), and every 15 min while there.
        scope.launch {
            foreground.collectLatest { fg ->
                while (fg) {
                    delay(lk.codegen.risime.update.UPDATE_CHECK_INTERVAL_MS)
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
                    localAccount.signOutKeepData()
                    signInNotice.value = "Your RisiCloud session ended. Sign in again — your chats are kept."
                    auth.acknowledgeSignInNeeded()
                }
            }
        }
        // An OIDC session from a previous process with nothing to unlock (memory-only device).
        scope.launch {
            val s = sessionStore.current()
            if (s?.kind == AuthKind.OIDC && !auth.canUnlock() && !auth.unlocked.value) {
                localAccount.signOutKeepData()
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
        // §8.1: push registration is part of the device registration loop above (after unlock).
        // §9.3: refetch GET /friends after every (re)join and on `friend` signals, debounced.
        scope.launch {
            realtime.state.collect { if (it == ConnectionState.Live) requestFriendsRefresh() }
        }
        // §15.7: the server-clock offset from the last run, until the next join reply.
        scope.launch { serverClock.restore(runCatching { sessionStore.serverOffset() }.getOrNull()) }
        // §15.6: purged images leave the in-memory bitmap caches.
        scope.launch { images.purgedIds.collect { id -> imageLoader.forget(id) } }
        // §15.6: hidden tombstones are kept 30 days.
        scope.launch { runCatching { db.deletes().pruneDeletedIds(System.currentTimeMillis() - 30L * 24 * 3600_000) } }
        // §17.3: the start-up sweep (keys of closed or 48-h-old requests), then owed history work, once live.
        if (BuildConfig.HISTORY_SHARE_ENABLED) scope.launch {
            realtime.state.first { it == ConnectionState.Live }
            runCatching { history.startup() }.onFailure { Log.w("RisiMe", "history startup: ${it.message}") }
        }
        // §17.2: gap rows go at server_ts + 30 days (the inbox TTL).
        scope.launch { runCatching { lk.codegen.risime.data.history.HistoryGaps.prune(db.history(), System.currentTimeMillis()) } }
        // §14.7: temp plaintext and orphans go, owed uploads/downloads resume, the cache is trimmed.
        scope.launch { runCatching { images.startup() }.onFailure { Log.w("RisiMe", "image startup: ${it.message}") } }
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

    /**
     * A sign-in by a different account than the one whose chats are on this phone: the UI asks
     * "delete them?" ([accountSwitch]); no answer = keep.
     */
    data class AccountSwitch(val previousName: String, val newName: String, val answer: CompletableDeferred<Boolean>)

    val accountSwitch = MutableStateFlow<AccountSwitch?>(null)

    private suspend fun askAccountSwitch(previousName: String?, newName: String): Boolean {
        val q = AccountSwitch(previousName?.takeIf { it.isNotBlank() } ?: "another account", newName, CompletableDeferred())
        accountSwitch.value = q
        return try {
            q.answer.await()
        } finally {
            accountSwitch.value = null
        }
    }

    /** Dev OTP login (DEV_LOCAL_AUTH servers). False when the user kept another account's chats (not signed in). */
    suspend fun onLoggedIn(token: String, user: User): Boolean {
        if (localAccount.beforeSignIn(user, ::askAccountSwitch) == lk.codegen.risime.data.SignInDecision.CANCELLED) {
            signInNotice.value = SWITCH_CANCELLED
            return false
        }
        signInNotice.value = null
        sessionStore.saveLogin(token, user)
        return true
    }

    /**
     * Browser sign-in finished (code exchanged): verify with GET /me, then keep the tokens.
     * Returns an error message for the sign-in screen, or null on success / a blocking screen.
     */
    suspend fun completeOidcSignIn(issuer: String, clientId: String, tokens: OidcTokens): String? {
        auth.adopt(issuer, clientId, tokens)
        return when (val o = meOutcome(api.me())) {
            is MeOutcome.Ok -> if (adoptOidcUser(o.user)) null else SWITCH_CANCELLED
            is MeOutcome.NeedsPhone -> {
                // Signed in; the gate shows "Confirm your phone" before anything connects.
                val user = o.user ?: (api.me() as? ApiResult.Ok)?.value?.user?.copy(phoneVerified = false)
                if (user == null) {
                    auth.signOut()
                    "Can't reach the RisiMe server. Try again."
                } else if (adoptOidcUser(user)) {
                    null
                } else {
                    SWITCH_CANCELLED
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

    /** §21.8: the display name to prefill on "Create your RisiMe account" (the token's `name`). */
    fun signupNamePrefill(): String? = lk.codegen.risime.data.auth.nameClaim(auth.idToken())

    /**
     * §21.3 `POST /auth/signup` for the signed-in (blocked: signup_required) identity. On success the
     * new user is adopted like any OIDC sign-in. A new account: nothing local is touched (rule 9).
     */
    suspend fun signUp(phoneE164: String, displayName: String): lk.codegen.risime.data.auth.SignupOutcome {
        val o = lk.codegen.risime.data.auth.signupOutcome(api.signup(lk.codegen.risime.net.SignupRequest(phoneE164, displayName.trim())))
        return when (o) {
            is lk.codegen.risime.data.auth.SignupOutcome.Created ->
                if (adoptOidcUser(o.user)) o else lk.codegen.risime.data.auth.SignupOutcome.Failed(SWITCH_CANCELLED)
            is lk.codegen.risime.data.auth.SignupOutcome.Closed -> {
                blocked.value = lk.codegen.risime.data.auth.Blocked(lk.codegen.risime.data.auth.BlockKind.NOT_ALLOWLISTED, o.message)
                o
            }
            lk.codegen.risime.data.auth.SignupOutcome.Unauthorized -> when (val m = meOutcome(api.me())) {
                is MeOutcome.Ok -> if (adoptOidcUser(m.user)) lk.codegen.risime.data.auth.SignupOutcome.Created(m.user) else lk.codegen.risime.data.auth.SignupOutcome.Failed(SWITCH_CANCELLED)
                is MeOutcome.NeedsPhone -> m.user?.let { u ->
                    if (adoptOidcUser(u)) lk.codegen.risime.data.auth.SignupOutcome.Created(u) else lk.codegen.risime.data.auth.SignupOutcome.Failed(SWITCH_CANCELLED)
                } ?: lk.codegen.risime.data.auth.SignupOutcome.Failed("Can't reach the RisiMe server. Try again.")
                is MeOutcome.Refused -> {
                    blocked.value = m.blocked
                    o
                }
                MeOutcome.Unauthorized -> lk.codegen.risime.data.auth.SignupOutcome.Failed("Your RisiCloud sign-in expired. Use \"Sign in with another account\" to sign in again.")
                is MeOutcome.Transient -> lk.codegen.risime.data.auth.SignupOutcome.Failed("Can't reach the RisiMe server. Try again.")
            }
            else -> o
        }
    }

    /** False when the user kept another account's chats: this sign-in is dropped. */
    private suspend fun adoptOidcUser(user: User): Boolean {
        if (localAccount.beforeSignIn(user, ::askAccountSwitch) == lk.codegen.risime.data.SignInDecision.CANCELLED) {
            withTimeoutOrNull(LOGOUT_NETWORK_MS) { runCatching { auth.signOut() } }
            runCatching { auth.forgetLocally() }
            return false
        }
        blocked.value = null
        signInNotice.value = null
        sessionStore.saveOidcLogin(user)
        return true
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
     * Logout (decisions 014, 050): revoke the refresh token, end the Keycloak session in the
     * browser, delete the key pair. Dev tokens: POST /auth/logout (the server keeps the device).
     * A plain logout unregisters push only and keeps the chats, the MLS state and the device id;
     * [confirmed].deleteChats ("Log out and delete chats from this phone") removes the device on
     * the server (it leaves its groups) and wipes local chat data.
     */
    suspend fun logout(confirmed: lk.codegen.risime.data.UserConfirmation) {
        notifier.cancelAll()
        val end = auth.issuerAndClient()?.let { (issuer, _) -> auth.idToken()?.let { EndSession(issuer, it) } }
        try {
            // Server side is best effort and bounded: a dead network or a Keycloak error never
            // blocks or reverts the local logout.
            // §15.7 (android S-f): deletes and clears done just before logout reach the server (best effort, bounded).
            withTimeoutOrNull(LOGOUT_NETWORK_MS) { runCatching { engine.flushDeletes() } }
            withTimeoutOrNull(LOGOUT_NETWORK_MS) {
                // While the token still works: DELETE /me/devices (leave the groups) or push only.
                runCatching { if (confirmed.deleteChats) push.unregister() else push.unregisterPushOnly() }
            }
            if (sessionStore.current()?.kind == AuthKind.DEV) withTimeoutOrNull(LOGOUT_NETWORK_MS) { runCatching { api.logout() } }
            withTimeoutOrNull(LOGOUT_NETWORK_MS) { runCatching { auth.signOut() } }
        } finally {
            withContext(NonCancellable) {
                runCatching { auth.forgetLocally() }
                blocked.value = null
                signInNotice.value = null
                if (confirmed.deleteChats) {
                    clearFriendsMemory()
                    clearLocal()
                } else {
                    realtime.stop()
                    localAccount.logoutKeepChats()
                }
            }
        }
        // Keycloak end_session in the browser, after the local logout: its failure (e.g. an
        // unregistered post-logout redirect) changes nothing here.
        end?.let { endSessionRequests.tryEmit(it) }
    }

    /**
     * Every escape-screen "Sign out" (blocked, identity conflict, locked, required update, confirm
     * phone): tokens and session go, the chats stay. Only Settings / the chats menu "Log out" wipes.
     */
    suspend fun signOutKeepChats(notice: String? = "Signed out — your chats are kept.") {
        realtime.stop()
        withTimeoutOrNull(LOGOUT_NETWORK_MS) { runCatching { auth.signOut() } }
        runCatching { auth.forgetLocally() }
        blocked.value = null
        localAccount.signOutKeepData()
        signInNotice.value = notice
    }

    /** §6: token rejected (refresh already tried): back to sign-in, keeping local chats. */
    suspend fun signOutKeepData(notice: String) {
        realtime.stop()
        if (auth.unlocked.value) auth.signOut()
        localAccount.signOutKeepData()
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
        localAccount.switchServer(url)
    }

    /** Explicit logout only: forget the account and delete local chats. */
    private suspend fun clearLocal() {
        realtime.stop()
        localAccount.logoutAndDeleteChats()
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
                // §12.4a wake push: a group op naming this device is committed before the worker ends.
                withTimeoutOrNull(20_000) { runCatching { groupOps.runDue() } }
            } finally {
                backgroundSync.value = false
            }
        }
        notifyFromLocal()
    }

    private suspend fun notifyFromLocal() {
        if (engine.replayingFresh) return // §13.3: the replay isn't live yet; onFreshReplayDone moves notifiedUpTo
        val open = openConversation.value.takeIf { foreground.value }
        val since = sessionStore.notifiedUpTo()
        val contactList = contacts.contacts.first()
        val me = sessionStore.current()?.user?.id ?: return
        val names = contactList.filter { it.userId != null }.associate { it.userId!!.lowercase() to it.displayName }
        val reactionAdds = db.reactions().addsSince(since)
        val targets = reactionAdds.map { it.targetMessageId }.distinct().associateWith { db.messages().byMessageId(it) }
        val plan = lk.codegen.risime.push.mergeReactionNotifications(
            planChatNotifications(
                db.messages().unreadIncoming(), contactList, since, open,
                groupNames = db.groups().allNow().associate { it.conversationId to lk.codegen.risime.data.groups.groupDisplayName(it.name) },
                memberNames = db.groups().observeAllMembers().first().groupBy { it.conversationId }
                    .mapValues { (_, ms) -> ms.associate { it.userId.lowercase() to it.displayName } },
            ),
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

    private suspend fun wipeDb(reason: WipeReason) = withContext(Dispatchers.IO) {
        Log.w("RisiMe", "wiping local chats: $reason")
        db.wipe().allChatData()
        runCatching { androidx.work.WorkManager.getInstance(appContext).cancelAllWork() }
        mediaFiles.wipe()
        File(appContext.noBackupFilesDir, "avatars").deleteRecursively()
        photoPrefs.edit().clear().apply()
        mlsEngine = null
        mlsDbKey.destroy()
    }
}

private inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(f(value))
    is ApiResult.Error -> this
    is ApiResult.NetworkError -> this
}
