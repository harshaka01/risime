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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.channels.awaitClose
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
import lk.codegen.risime.push.DIRECT_PUSH_SYNC_MS
import lk.codegen.risime.push.SOCKET_NOTIFY_DEBOUNCE_MS
import kotlinx.coroutines.flow.update
import lk.codegen.risime.push.planChatNotifications
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.edit
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

/** §22.7: the backup before a confirmed wipe (local file, then the upload when server backup is on). */
private const val PRE_WIPE_BACKUP_MS = 150_000L

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
    /** Sign-in/sign-out facts for the log and the health screen (decision 064). */
    val authDiagnostics = lk.codegen.risime.data.auth.AuthDiagnosticsStore(context)
    val auth = AuthManager(
        gateway = oidc,
        // Decision 064: hardware AES key, no user authentication: a push-started process has a bearer.
        vault = lk.codegen.risime.data.auth.SessionVault(File(context.noBackupFilesDir, "session.bin"), lk.codegen.risime.data.auth.KeystoreVaultKey()),
        // The pre-064 fingerprint-bound vault: read once more to migrate, then deleted.
        legacy = TokenVault(File(context.noBackupFilesDir, "tokens.bin"), KeystoreWrappingKey()),
        elapsed = SystemClock::elapsedRealtime,
        diagnostics = authDiagnostics,
        transientStarts = object : lk.codegen.risime.data.auth.TransientStartCounter {
            private val p = context.getSharedPreferences("risime_auth_vault", Context.MODE_PRIVATE)
            override fun get() = p.getInt("transient_starts", 0)
            override fun set(n: Int) = p.edit().putInt("transient_starts", n).apply()
        },
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

    /** The bearer for REST and the socket: the OIDC token (restored from the vault, no UI), else the stored dev token. */
    suspend fun bearer(forceRefresh: Boolean = false): String? =
        if (auth.hasSession()) auth.bearer(forceRefresh) else sessionStore.current()?.takeIf { it.kind == AuthKind.DEV }?.token

    val api = ApiClient(
        http,
        { sessionStore.currentServerUrl() },
        { bearer() },
        onUnauthorized = { auth.hasSession() && auth.bearer(forceRefresh = true) != null },
        deviceId = { sessionStore.deviceId() },
    )

    /** An OIDC session is in memory (restored at process start without any prompt, decision 064). */
    private fun oidcReady(): kotlinx.coroutines.flow.Flow<Boolean> = auth.state.map { it == lk.codegen.risime.data.auth.SessionState.READY }
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
        onRisiProgress = { p -> if (risiTools.on.value) risiProgress.apply(p) },
    )

    // ---- §25 (v1.25) Risi with tools ----

    /** §25.8 the `risi_tools` server switch (kept across restarts) and this device's advertisement. */
    val risiTools = lk.codegen.risime.data.tabs.RisiToolsSwitch(
        persistedServerOn = context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getBoolean("risi_tools_on", false),
        persistServerOn = { on -> context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).edit().putBoolean("risi_tools_on", on).apply() },
        log = { Log.i("RisiMe", it) },
    )

    /** §25.4 Risi's progress bubbles (in memory). */
    val risiProgress = lk.codegen.risime.data.tabs.RisiProgressStore()

    /** §25.4/§26.5 a draft's [Use] and "Open Risi skills" from a card. */
    val risiUi = lk.codegen.risime.data.tabs.RisiUiBus()

    // ---- §26 (v1.26) Risi skills ----

    /** §26.9 the `risi_skills` server switch (kept across restarts) and this device's advertisement. */
    val risiSkills = lk.codegen.risime.data.tabs.RisiToolsSwitch(
        persistedServerOn = context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getBoolean("risi_skills_on", false),
        persistServerOn = { on -> context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).edit().putBoolean("risi_skills_on", on).apply() },
        log = { Log.i("RisiMe", it.replace("risi_tools", "risi_skills")) },
    )

    /** Skills on this device: the server switch, the advertisement, and `risi_tools`. */
    fun risiSkillsOn(): Boolean = risiSkills.on.value && risiTools.on.value

    // ---- §27 (v1.27) the Commitment Ledger follow-ups ----

    /** §27.10 the `risi_ledger` server switch (kept across restarts) and this device's advertisement. */
    val risiLedger = lk.codegen.risime.data.tabs.RisiToolsSwitch(
        persistedServerOn = context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getBoolean("risi_ledger_on", false),
        persistServerOn = { on -> context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).edit().putBoolean("risi_ledger_on", on).apply() },
        log = { Log.i("RisiMe", it.replace("risi_tools", "risi_ledger")) },
    )

    /** The Ledger on this device: the server switch, the advertisement, and `risi_tools`. */
    fun risiLedgerOn(): Boolean = risiLedger.on.value && risiTools.on.value

    // ---- §29 (v1.29) Risi Calendar ----

    /** §29.1 the `risi_events` server switch (kept across restarts) and this device's advertisement. */
    val risiEvents = lk.codegen.risime.data.tabs.RisiToolsSwitch(
        persistedServerOn = context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getBoolean("risi_events_on", false),
        persistServerOn = { on -> context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).edit().putBoolean("risi_events_on", on).apply() },
        log = { Log.i("RisiMe", it.replace("risi_tools", "risi_events")) },
    )

    /** Risi Calendar on this device: the switch and advertisement with `risi_tools`, `risi_skills` and `risi_ledger`. */
    fun risiEventsOn(): Boolean = risiEvents.on.value && risiTools.on.value && risiSkills.on.value && risiLedger.on.value

    /** The same as a flow (the Calendar tab shows only while it is true; v1.28 exactly otherwise). */
    val risiEventsActive: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy {
        kotlinx.coroutines.flow.combine(risiEvents.on, risiTools.on, risiSkills.on, risiLedger.on) { a, b, c2, d -> a && b && c2 && d }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    }

    // ---- §30 (v1.29) Risi Notes ----

    /** §30.1 the `risi_notes` server switch (kept across restarts) and this device's advertisement. */
    val risiNotes = lk.codegen.risime.data.tabs.RisiToolsSwitch(
        persistedServerOn = context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getBoolean("risi_notes_on", false),
        persistServerOn = { on -> context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).edit().putBoolean("risi_notes_on", on).apply() },
        log = { Log.i("RisiMe", it.replace("risi_tools", "risi_notes")) },
    )

    /** Risi Notes on this device: its switch and advertisement with everything `risi_events` needs. */
    fun risiNotesOn(): Boolean = risiNotes.on.value && risiEventsOn()

    /** The same as a flow (the Notes entries show only while it is true; v1.28/§29 exactly otherwise). */
    val risiNotesActive: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy {
        kotlinx.coroutines.flow.combine(risiNotes.on, risiEventsActive) { a, b -> a && b }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    }

    /** §30.6 the Notes REST. */
    val risiNotesRest: lk.codegen.risime.net.RisiNotesRest by lazy { lk.codegen.risime.net.RisiNotesApi(api) }

    /** §30.6 share a note into a chat: an ordinary message of the user's own (Risi isn't involved). */
    suspend fun shareText(conversationId: String, text: String): Boolean = engine.sendText(conversationId, text) != null

    /** The user's Risi chat (null: none on this phone yet). */
    fun risiChatConversation(): String? = chatTabs.rows.value?.values?.firstOrNull { it.risi }?.conversationId

    /** §27.5/§30.5 an item action (`done`, `item_reopen`, …) from outside a chat screen: sent in the user's Risi chat. */
    suspend fun sendItemAction(itemId: String, action: String, text: String? = null, due: String? = null, allDay: Boolean = false): Boolean {
        val conv = risiChatConversation() ?: return false
        return lk.codegen.risime.data.tabs.RisiRequests(
            isOfficial = { db.chatTabs().get(conv)?.official == true },
            send = { engine.sendRisiControl(conv, it) },
        ).act(itemId, action, text, due, allDay)
    }

    /** §29.3/§29.6 the calendar: REST, the Room cache and the cursor feed. */
    val risiCalendar: lk.codegen.risime.data.calendar.RisiCalendar by lazy {
        val prefs = context.getSharedPreferences("risime_calendar", Context.MODE_PRIVATE)
        lk.codegen.risime.data.calendar.RisiCalendar(
            lk.codegen.risime.data.calendar.ApiCalendarRemote(api),
            lk.codegen.risime.data.calendar.RoomCalendarStore(db.risiCalendar()) { block -> dbTx.run { block() } },
            enabled = { risiEventsOn() },
            me = { sessionStore.current()?.user?.id },
            log = { Log.i("RisiMe", it) },
            loadShowDeclined = { prefs.getBoolean("show_declined", false) },
            saveShowDeclined = { on -> prefs.edit().putBoolean("show_declined", on).apply() },
        )
    }

    /** The Risi cards' view of the calendar (cache for the timeline; REST answers; [Open] → the Calendar tab). */
    val risiCalendarCards: lk.codegen.risime.data.calendar.RisiCalendarCardsPort by lazy {
        val cal = risiCalendar
        val cached = cal.events.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
        object : lk.codegen.risime.data.calendar.RisiCalendarCardsPort {
            override val events = cached
            override suspend fun respond(eventId: String, response: String, version: Int?, expectStart: String?, expectEnd: String?, suggest: Triple<String, String, Boolean>?) =
                cal.respond(eventId, response, version, expectStart, expectEnd, suggest)
            override suspend fun resolve(suggestionId: String, action: String) = cal.resolve(suggestionId, action)
            override suspend fun delete(eventId: String): lk.codegen.risime.data.calendar.CalendarResult {
                val e = cached.value.firstOrNull { it.eventId.equals(eventId, true) }
                    ?: return lk.codegen.risime.data.calendar.CalendarResult.Failed("not_found")
                return cal.delete(e)
            }
            override fun open(eventId: String) {
                cal.requestFocus(eventId)
                risiUi.openCalendar()
            }
        }
    }

    /** A sync off the event path (inbox event, app start, the tab opening); failures only log. */
    fun syncRisiCalendar(cursor: String? = null) {
        if (!risiEventsOn()) return
        scope.launch(Dispatchers.IO) { runCatching { risiCalendar.onChanged(cursor) }.onFailure { Log.w("RisiMe", "risi_calendar: sync ${it.javaClass.simpleName}") } }
    }

    /** §29.10 an `event_update` card updates the cache silently (then a sync fetches the full view). */
    private fun onRisiObject(json: String) {
        if (!json.contains(lk.codegen.risime.net.RisiKinds129.EVENT_UPDATE) && !json.contains(lk.codegen.risime.net.RisiKinds129.CALENDAR_INVITE)) return
        val card = lk.codegen.risime.net.RisiCalendarCard.parse(json) ?: return
        if (card.kind != lk.codegen.risime.net.RisiKinds129.EVENT_UPDATE && card.kind != lk.codegen.risime.net.RisiKinds129.CALENDAR_INVITE) return
        if (!risiEventsOn()) return
        scope.launch(Dispatchers.IO) {
            runCatching { risiCalendar.applyUpdate(card) }
            runCatching { risiCalendar.sync() }
        }
    }

    /** §26.2 the skills, this user's state and the phone's own record of it (the allowed path trusts only that). */
    val risiSkillsStore: lk.codegen.risime.data.tabs.RisiSkillsStore by lazy {
        val p = context.getSharedPreferences("risime_skills", Context.MODE_PRIVATE)
        lk.codegen.risime.data.tabs.RisiSkillsStore(
            get = { api.risiSkills() },
            patch = { api.patchRisiSkills(it) },
            loadStates = { lk.codegen.risime.data.tabs.RisiSkillsStore.decodeStates(p.getString("states", null)) },
            saveStates = { m -> p.edit().putString("states", lk.codegen.risime.data.tabs.RisiSkillsStore.encodeStates(m)).apply() },
            cancelLocal = { id -> cancelLocalSkillItems(id) },
            log = { Log.i("RisiMe", it) },
        )
    }

    /** What Android says about each skill's permissions (no prompt: background reporting). */
    val backgroundSkillPermissions by lazy { lk.codegen.risime.data.tabs.AndroidSkillPermissions(appContext, { null }, { null }) }

    /** The Settings screen's permissions: the prompt goes through its launchers. */
    fun skillPermissions(asker: lk.codegen.risime.ui.settings.ScreenPermissionAsker): lk.codegen.risime.data.tabs.SkillPermissions =
        lk.codegen.risime.data.tabs.AndroidSkillPermissions(appContext, { asker.runtime }, { asker.exactAlarm })

    /** "Also cancel N pending": this phone's own pending items of a skill (null: none kept on the phone). */
    suspend fun pendingSkillItems(skillId: String): Int? =
        if (skillId == lk.codegen.risime.net.RisiSkillIds.SCHEDULED_MESSAGES) runCatching { db.scheduled().pendingCount() }.getOrNull() else null

    /** §26.5 revoke with "Also cancel N pending": the phone cancels its own items. */
    suspend fun cancelLocalSkillItems(skillId: String) {
        if (skillId == lk.codegen.risime.net.RisiSkillIds.SCHEDULED_MESSAGES) scheduled.cancelAll()
    }

    /** §26.6 scheduled messages: stored and sent by this phone (exact alarm or WorkManager). */
    val scheduled: lk.codegen.risime.data.tabs.ScheduledMessages by lazy {
        lk.codegen.risime.data.tabs.ScheduledMessages(
            db.scheduled(), lk.codegen.risime.push.AndroidScheduleArmer(appContext),
            send = { conv, text, id -> engine.sendText(conv, text, id) != null },
            isMember = { conv -> scheduleTargetOk(conv) },
            graphemes = { lk.codegen.risime.data.IcuGraphemes.count(it) },
            log = { Log.i("RisiMe", it) },
        )
    }

    /** §26.6 `not_member` otherwise: a conversation this phone holds, the user active in it, never a Risi chat. */
    private suspend fun scheduleTargetOk(conv: String): Boolean {
        val rows = chatTabs.rows.value
        if (lk.codegen.risime.data.tabs.isRisiChat(conv, rows)) return false
        val me = sessionStore.current()?.user?.id ?: return false
        return if (conv.startsWith("dm:")) {
            val peer = lk.codegen.risime.net.dmPeer(conv, me) ?: return false
            db.contacts().byUserId(peer)?.let { it.friend && it.registered } == true
        } else {
            db.groups().get(conv)?.state == lk.codegen.risime.data.db.GroupEntity.STATE_ACTIVE
        }
    }

    /**
     * P0 §25.3/§26.6 the phone's calendar: a synced Google calendar (or the user's explicit pick), the
     * remembered pick and the local write_id → event record in DataStore (loaded off the main thread).
     */
    val phoneCalendar: lk.codegen.risime.data.tabs.PhoneCalendar by lazy {
        val chosenKey = androidx.datastore.preferences.core.longPreferencesKey("risi_calendar_id")
        val writesKey = androidx.datastore.preferences.core.stringPreferencesKey("risi_calendar_writes")
        val loaded = CompletableDeferred<Unit>()
        val chosen = MutableStateFlow<Long?>(null)
        val choice = object : lk.codegen.risime.data.tabs.CalendarChoiceStore {
            override val chosen = chosen
            override suspend fun set(id: Long?) {
                loaded.await()
                chosen.value = id
                prefs.edit { p -> if (id == null) p.remove(chosenKey) else p[chosenKey] = id }
            }
        }
        val writes = lk.codegen.risime.data.tabs.CalendarWriteLog(null) { json -> prefs.edit { it[writesKey] = json } }
        scope.launch(Dispatchers.IO) {
            try {
                val p = prefs.data.first()
                chosen.value = p[chosenKey]
                writes.restore(p[writesKey])
            } finally {
                loaded.complete(Unit)
            }
        }
        lk.codegen.risime.data.tabs.PhoneCalendar(
            lk.codegen.risime.data.tabs.AndroidCalendarBackend(appContext), choice, writes,
            log = { Log.i("RisiMe", it) }, ready = { loaded.await() },
        )
    }

    val calendarPort: lk.codegen.risime.data.tabs.RisiCalendarPort by lazy { lk.codegen.risime.data.tabs.AndroidCalendarPort(appContext, phoneCalendar) { ref -> risiSkillsStore.reportCalendar(ref) } }

    /** §25.3/§26.3/§26.6 the phone's executor for Risi's client tools (only on a `risi_skills` device). */
    val risiToolExecutor: lk.codegen.risime.data.tabs.RisiToolExecutor by lazy {
        lk.codegen.risime.data.tabs.RisiToolExecutor(
            me = { sessionStore.current()?.user?.id },
            history = { conv -> db.messages().conversation(conv).first() },
            risiChats = { chatTabs.rows.value?.values?.filter { it.risi }?.map { it.conversationId }.orEmpty() },
            skillState = { risiSkillsStore.localState(it) },
            dao = db.scheduled(),
            scheduled = scheduled,
            setAlarm = { a -> withContext(Dispatchers.Main) { lk.codegen.risime.push.fireSetAlarm(appContext, a) } },
            graphemes = { lk.codegen.risime.data.IcuGraphemes.count(it) },
            log = { Log.i("RisiMe", it) },
            calendar = phoneCalendar,
        )
    }

    /** §25.3 client tools: answered over TLS, never stored. */
    val risiToolCalls = lk.codegen.risime.data.tabs.RisiToolCallHandler(
        deviceId = { runCatching { sessionStore.deviceId() }.getOrNull() },
        post = { id, result, device -> api.postRisiToolResult(id, result, device) },
        enabled = { risiTools.on.value },
        // §26.6: the new tools only on a risi_skills device (else unknown_tool, §26.9); a v1.25 device runs the calendar under the card rule.
        execute = { call -> if (risiSkillsOn()) risiToolExecutor.execute(call) else risiToolExecutor.executeV125(call) },
        log = { Log.i("RisiMe", it) },
    )

    // ---- The optional fingerprint lock (decision 064): a UI gate only ----
    val appLock = lk.codegen.risime.data.lock.AppLock(
        store = lk.codegen.risime.data.lock.DataStoreAppLockStore(prefs),
        stamp = object : lk.codegen.risime.data.lock.LockStamp {
            private val p = context.getSharedPreferences("risime_lock", Context.MODE_PRIVATE)
            override fun get(): Long? = p.getLong("bg_elapsed", -1L).takeIf { it >= 0 }
            override fun set(v: Long?) = p.edit().putLong("bg_elapsed", v ?: -1L).apply()
        },
        biometric = { lk.codegen.risime.ui.lock.strongBiometricStatus(context) },
        canUnlock = { lk.codegen.risime.ui.lock.lockUnlockable(context) },
        elapsed = SystemClock::elapsedRealtime,
        log = { Log.w("RisiMe", it) },
    )

    // ---- Locked chats (WhatsApp "Lock chat"): per device, local, never sent anywhere ----
    val lockedChats = lk.codegen.risime.data.lock.LockedChats(
        lk.codegen.risime.data.lock.FileLockedChatsStore(File(context.noBackupFilesDir, "locked_chats.bin"), lk.codegen.risime.data.auth.KeystoreVaultKey("risime_locked_chats_aes")),
        log = { Log.w("RisiMe", it) },
        // §24: by chat id (both tabs); unknown until the chat-tab rows are read (redacted meanwhile).
        chatOf = { conv -> chatTabsOrNull?.chatIdOrNull(conv) },
    )
    /** Set right after [chatTabs] is built (the locked list is created first). */
    @Volatile private var chatTabsOrNull: lk.codegen.risime.data.tabs.ChatTabs? = null

    // ---- Two tabs per chat (contract v1.24 §24): tab of each conversation from MLS, the server switch ----
    val chatTabs = lk.codegen.risime.data.tabs.ChatTabs(
        db.chatTabs(), scope,
        persistedServerOn = context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getBoolean("server_on", false),
        persistServerOn = { on -> context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).edit().putBoolean("server_on", on).apply() },
        log = { Log.i("RisiMe", it) },
        persistedBannerSeen = context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getStringSet("banner_seen", null).orEmpty(),
        persistBannerSeen = { s -> context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).edit().putStringSet("banner_seen", HashSet(s)).apply() },
    ).also { chatTabsOrNull = it }

    /** §24.11 the private Risi REST calls (feedback, facts, commitments). */
    val risiRest: lk.codegen.risime.net.RisiRest = lk.codegen.risime.net.RisiRestApi(api)

    /** §24.11 `PATCH /me {"tz"}` at sign-in and when the phone's zone changes (tabs on only). */
    val timezoneSync = lk.codegen.risime.data.tabs.TimezoneSync(
        zone = { java.util.TimeZone.getDefault().id },
        lastSent = { u -> context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getString("tz_sent_" + u.lowercase(), null) },
        remember = { u, tz -> context.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).edit().putString("tz_sent_" + u.lowercase(), tz).apply() },
        patch = { tz -> api.patchTimezone(tz) },
    )

    suspend fun syncTimezone() {
        if (chatTabs.serverOn.value) timezoneSync.sync(sessionStore.current()?.user?.id)
    }

    /** §24.15: read `/auth/config` `tabs`; a change re-advertises the capabilities. */
    suspend fun refreshTabsSwitch() {
        val cfg = (api.authConfig() as? ApiResult.Ok)?.value ?: return
        val before = chatTabs.serverOn.value
        chatTabs.setServerOn(cfg.tabsOn)
        val risiBefore = risiTools.serverOn.value
        risiTools.setServerOn(cfg.risiToolsOn)
        val skillsBefore = risiSkills.serverOn.value
        risiSkills.setServerOn(cfg.risiSkillsOn)
        val ledgerBefore = risiLedger.serverOn.value
        risiLedger.setServerOn(cfg.risiLedgerOn)
        val eventsBefore = risiEvents.serverOn.value
        val notesBefore = risiNotes.serverOn.value
        risiNotes.setServerOn(cfg.risiNotesOn)
        risiEvents.setServerOn(cfg.risiEventsOn)
        if (before != cfg.tabsOn || risiBefore != cfg.risiToolsOn || skillsBefore != cfg.risiSkillsOn || ledgerBefore != cfg.risiLedgerOn || eventsBefore != cfg.risiEventsOn || notesBefore != cfg.risiNotesOn) refreshCapabilities()
    }

    /** §25.2 the Risi chat's first open (only on a `risi_tools` device). */
    private val risiChatOpener by lazy {
        lk.codegen.risime.data.tabs.RisiChatOpener(
            enabled = { risiTools.on.value && chatTabs.uiOn.value },
            create = { api.createRisiChat(sessionStore.deviceId()) },
            applyGroup = { g -> sessionStore.current()?.user?.id?.let { me -> dbTx.run { groupStore.applyServerGroup(g, me) } } },
            hasMlsGroup = { conv -> mlsEngine?.let { e -> dbTx.run { e.group(conv) } } != null },
            queueEpoch0 = { conv -> groupStore.queueLocal(conv, lk.codegen.risime.data.groups.GroupOpType.CREATE_RISI_CHAT) },
            log = { Log.i("RisiMe", it) },
        )
    }

    suspend fun openRisiChat(): lk.codegen.risime.data.tabs.RisiChatOpen = risiChatOpener.open()

    private val risiChatEnsured = java.util.concurrent.atomic.AtomicBoolean(false)

    /** §27.10 once per process: create the Risi chat when this phone has none (POST /risi/chat is idempotent). */
    private suspend fun ensureRisiChat() {
        if (risiChatEnsured.get()) return
        val rows = chatTabs.rows.first { it != null } ?: return
        if (rows.values.any { it.risi }) { risiChatEnsured.set(true); return }
        when (val r = runCatching { openRisiChat() }.getOrNull()) {
            is lk.codegen.risime.data.tabs.RisiChatOpen.Ready -> { risiChatEnsured.set(true); Log.i("RisiMe", "risi_ledger: Risi chat created at start") }
            is lk.codegen.risime.data.tabs.RisiChatOpen.Failed -> Log.w("RisiMe", "risi_ledger: Risi chat at start failed: ${r.text}")
            null -> Log.w("RisiMe", "risi_ledger: Risi chat at start failed")
        }
    }

    /**
     * §24.2 [Start Official] / a new group's Official: `POST /chats/{chat_id}/official` now (its refusal is
     * the user's answer), then the epoch-0 commit through the group-op outbox (survives process death).
     */
    suspend fun startOfficial(chatId: String): ApiResult<lk.codegen.risime.net.Group> {
        val r = api.createOfficial(chatId, sessionStore.deviceId())
        if (r is ApiResult.Ok) {
            chatTabs.setOfficialState(chatId, lk.codegen.risime.data.tabs.OfficialState.ON)
            chatTabs.markStarting(chatId)
            groupStore.queueLocal(r.value.group.id, lk.codegen.risime.data.groups.GroupOpType.CREATE_OFFICIAL,
                lk.codegen.risime.net.ProtocolJson.encodeToString(lk.codegen.risime.data.groups.OfficialPayload.serializer(), lk.codegen.risime.data.groups.OfficialPayload(chatId)))
        }
        return when (r) {
            is ApiResult.Ok -> ApiResult.Ok(r.value.group)
            is ApiResult.Error -> r
            is ApiResult.NetworkError -> r
        }
    }

    /**
     * §24.2 a new group (its Private group is active): `POST …/official` straight away and open on
     * Official; `409 not_ready` (or any refusal) keeps it on Private with the reason shown in the chat.
     */
    fun startOfficialForNewGroup(conversationId: String) {
        if (!chatTabs.uiOn.value) return
        scope.launch {
            when (val r = startOfficial(conversationId)) {
                is ApiResult.Ok -> chatTabs.setLastTab(conversationId, lk.codegen.risime.data.tabs.Tab.OFFICIAL)
                is ApiResult.Error -> chatTabs.setNotice(conversationId, lk.codegen.risime.ui.tabs.officialErrorText(r.code))
                is ApiResult.NetworkError -> chatTabs.setNotice(conversationId, lk.codegen.risime.ui.tabs.officialErrorText(null))
            }
        }
    }

    /** §24.4 `PATCH /chats/{chat_id}` `official: on | off`. */
    suspend fun setOfficial(chatId: String, on: Boolean): ApiResult<lk.codegen.risime.net.Chat> {
        val r = api.patchChat(chatId, if (on) "on" else "off", sessionStore.deviceId())
        if (r is ApiResult.Ok) chatTabs.setOfficialState(chatId, r.value.chat.official.state)
        return when (r) {
            is ApiResult.Ok -> ApiResult.Ok(r.value.chat)
            is ApiResult.Error -> r
            is ApiResult.NetworkError -> r
        }
    }

    /** §24.8 `GET /chats/{chat_id}`: the server's Official state for the tab bar (tabs devices only). */
    suspend fun refreshChat(chatId: String): lk.codegen.risime.net.Chat? {
        val chat = (api.chat(chatId, sessionStore.deviceId()) as? ApiResult.Ok)?.value?.chat ?: return null
        chatTabs.setOfficialState(chatId, chat.official.state)
        return chat
    }

    // ---- Push (contract v1.5, decision 026) ----
    // §23.5: while the screen is shared, RisiMe's own notifications carry no sender or content and make no sound.
    val notifier = Notifier(
        context,
        hideContent = { lk.codegen.risime.calls.ScreenSharing.active || appLock.hideNotificationContentBlocking() },
        isLockedChat = { lockedChats.redactBlocking(it) },
        quiet = { lk.codegen.risime.calls.ScreenSharing.active },
    )
    // ---- E2EE (contract v1.7, decisions 035, 037). The engine loads only once the server offers
    // attestation keys; until then (pilot: mls_unavailable) the app behaves exactly like v1.6.
    /** Core open vs registered, tracked apart (P0 background delivery, rule 9). */
    private val mlsCore = lk.codegen.risime.data.mls.MlsCoreState(BuildConfig.CRYPTO_AVAILABLE, object : lk.codegen.risime.data.mls.MlsCoreState.Persist {
        private fun p(): android.content.SharedPreferences = context.getSharedPreferences("risime_mls", android.content.Context.MODE_PRIVATE)
        override fun openedBefore() = p().getBoolean("core_opened", false)
        override fun setOpenedBefore(v: Boolean) = p().edit().putBoolean("core_opened", v).apply()
        override fun cachedKeys(): List<String> = p().getString("attestation_keys", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        override fun setCachedKeys(keys: List<String>) = p().edit().putString("attestation_keys", keys.joinToString("\n")).apply()
    })
    val mlsEngineState: MutableStateFlow<MlsEngine?> get() = mlsCore.engine
    val mlsEngine: MlsEngine?
        get() = mlsCore.engine.value

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
    /** One own MLS commit per conversation at a time across the group outbox and the DM executors (no staged commit outlives its attempt). */
    private val commitGate = lk.codegen.risime.data.mls.MlsCommitGate { Log.w("RisiMe", it) }
    val membershipExecutor by lazy { MembershipExecutor({ mlsEngine }, mlsApi, commitGate) { conv -> catchUpCommits(conv) } }
    val mlsPipeline by lazy {
        MlsPipeline(
            { mlsEngine }, db.mlsPending(),
            coreExpected = { mlsCore.coreExpected() },
            deviceId = { runCatching { sessionStore.deviceId() }.getOrNull() },
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
            onRisiObject = { json -> onRisiObject(json) },
        )
    }
    val mlsUpgrader by lazy { MlsUpgrader({ mlsEngine }, mlsApi, gate = commitGate) }

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
            onMlsMeta = { conv, meta -> chatTabs.recordMls(conv, meta) },
            onServerTab = { conv, tab, chatId -> chatTabs.noteServerTab(conv, tab, chatId) },
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
        override suspend fun createOfficial(chatId: String) = api.createOfficial(chatId, dev()).map { it.group }
        override suspend fun createRisiChat() = api.createRisiChat(dev()).map { it.group }
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
        backgroundSync.update { it + 1 }
        try {
            withTimeoutOrNull(25_000) { realtime.state.first { it == ConnectionState.Live } }
            delay(2_000) // onLive flushes the outbox
        } finally {
            backgroundSync.update { it - 1 }
        }
    }

    val groupOps by lazy {
        lk.codegen.risime.data.groups.GroupOpsExecutor(
            { mlsEngine }, groupApi, db.groupOps(), db.groups(), groupStore, dbTx,
            me = { sessionStore.current()?.user?.id }, deviceId = { sessionStore.deviceId() },
            catchUp = { conv -> catchUpCommits(conv) },
            log = { Log.i("RisiMe", it) },
            openIcon = { conv, sealed -> openGroupIcon(conv, sealed) },
            gate = commitGate,
            officialTab = { conv -> db.chatTabs().get(conv)?.takeIf { it.official }?.chatId },
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
            db.groups().allNow().filter { it.conversationId !in listed && !it.readOnly }.forEach {
                // §24.7: a list that omits an Official group is not a removal; only a group_event says so.
                if (chatTabs.private(it.conversationId)) groupStore.markGone(it.conversationId)
                else Log.w("RisiMe", "GET /groups omits Official group ${it.conversationId}: kept (no group_event removal)")
            }
        }
        // §12.8: groups this device holds no MLS state for (a sign-in after a logout keeps the device
        // id but starts a new MLS state): rejoin them, in the background, idempotently.
        val engine = mlsEngine ?: return
        val queued = groupStore.queueRejoins(r.value.groups, me, { conv -> engine.group(conv)?.generation })
        if (queued.isNotEmpty()) Log.i("RisiMe", "rejoining ${queued.size} group(s) on this device")
        kickGroupOps()
    }

    /** §12.7 S4: "Kamal added you to <name>" (the name from the Welcome's group_meta), unless that chat is open. */
    /** A user's display name for a local line: a contact, else any group member row, else "Someone". */
    private suspend fun chatMemberName(userId: String): String =
        contacts.contacts.first().firstOrNull { it.userId.equals(userId, true) }?.displayName
            ?: db.groups().observeAllMembers().first().firstOrNull { it.userId.equals(userId, true) }?.displayName
            ?: "Someone"

    private suspend fun notifyAddedToGroup(conversationId: String, actor: String) {
        if (foreground.value) return
        // §24: an Official conversation is a tab of an existing chat, never a new group to announce.
        if (!chatTabs.private(conversationId) || conversationId.lowercase() in chatTabs.pendingOfficial.value) return
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
            is ApiResult.Error -> null.also {
                if (r.httpStatus == 404) {
                    if (chatTabs.private(conversationId)) db.withTransaction { groupStore.markGone(conversationId) }
                    else Log.w("RisiMe", "GET /groups/$conversationId 404 on an Official group: kept, will retry (no group_event removal)")
                }
            }
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
            callSwitchSupported = { runCatching { calls.canAdvertiseSwitch() }.getOrDefault(false) },
            screenShareSupported = { runCatching { calls.canAdvertiseScreenShare() }.getOrDefault(false) },
            groupsReplacedFor = { sessionStore.groupsKeyPackagesFor() },
            setGroupsReplacedFor = { sessionStore.setGroupsKeyPackagesFor(it) },
            onPushTokenRegistered = { t ->
                lk.codegen.risime.push.PushRegistrationStore(appContext).markRegistered(t)
                Log.i("RisiMe", "RisiMe push: device registered, push token ${if (t == null) "none" else "sent"}")
            },
            tabsSupported = { chatTabs.serverOn.value },
            risiToolsSupported = { risiTools.serverOn.value },
            risiSkillsSupported = { risiSkills.serverOn.value },
            risiLedgerSupported = { risiLedger.serverOn.value },
            risiEventsSupported = { risiEvents.serverOn.value },
            risiNotesSupported = { risiNotes.serverOn.value },
            onAdvertised = { caps ->
                val tabs = lk.codegen.risime.net.DeviceMls.CAP_TABS in caps
                chatTabs.setAdvertised(tabs)
                val risi = lk.codegen.risime.net.DeviceMls.CAP_RISI_TOOLS in caps
                risiTools.setAdvertised(risi)
                if (!risi) risiProgress.clear()
                val skills = lk.codegen.risime.net.DeviceMls.CAP_RISI_SKILLS in caps
                risiSkills.setAdvertised(skills)
                val ledger = lk.codegen.risime.net.DeviceMls.CAP_RISI_LEDGER in caps
                risiLedger.setAdvertised(ledger)
                val events = lk.codegen.risime.net.DeviceMls.CAP_RISI_EVENTS in caps
                risiEvents.setAdvertised(events)
                risiNotes.setAdvertised(lk.codegen.risime.net.DeviceMls.CAP_RISI_NOTES in caps)
                // §29.6: a calendar device syncs on start (lists when it has no cursor yet).
                if (events) scope.launch { runCatching { risiCalendar.sync(); risiCalendar.loadSettings() } }
                // §27.10: a risi_ledger app creates its Risi chat at start when it has none (follow-ups land there).
                if (ledger && risi) scope.launch { ensureRisiChat() }
                // §26.2: report the Android permission state on start (and the phone's record of each skill).
                if (skills) scope.launch { runCatching { risiSkillsStore.refresh(backgroundSkillPermissions) } }
                // Remembered per device id: the next process start shows the tab bar at once (ChatTabs.restoreAdvertised).
                scope.launch {
                    val id = runCatching { sessionStore.deviceId() }.getOrNull()
                    appContext.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).edit()
                        .putString("advertised_tabs_device", if (tabs) id else null)
                        .putString("advertised_risi_tools_device", if (risi) id else null)
                        .putString("advertised_risi_skills_device", if (skills) id else null)
                        .putString("advertised_risi_ledger_device", if (ledger) id else null)
                        .putString("advertised_risi_events_device", if (events) id else null)
                        .putString("advertised_risi_notes_device", if (lk.codegen.risime.net.DeviceMls.CAP_RISI_NOTES in caps) id else null).apply()
                }
            },
        )
    }

    /**
     * Load the MLS core if this build has it and the server offers attestation keys (E2EE on), then
     * register with the MLS key (→ attestation → key packages). Returns null when MLS doesn't apply
     * (no core, E2EE off: register for push instead), else whether the device is registered.
     *
     * P0 background delivery: a failed registration keeps the core open (decrypting the inbox doesn't
     * need it; the registration retries on its own). Without the network, a core opened before on this
     * install opens with the last attestation keys served.
     */
    suspend fun activateMls(): Boolean? {
        if (mlsEngine != null && mlsCore.registered) return true
        if (!BuildConfig.CRYPTO_AVAILABLE) return null
        val session = sessionStore.current()?.takeIf { it.user.phoneVerified } ?: return false
        if (mlsEngine == null) {
            val factory = MlsEngineFactory.get() ?: return null
            val served: List<String>? = when (val keys = api.attestationKeys()) {
                // mls_unavailable / no key: E2EE is off
                is ApiResult.Ok -> keys.value.keys.map { it.toString() }.also { if (it.isEmpty()) return null }
                is ApiResult.Error -> if (keys.httpStatus in 400..499) return null else null
                is ApiResult.NetworkError -> null
            }
            val trustedServed = served ?: mlsCore.offlineKeys() ?: return false
            if (served == null) Log.i("RisiMe", "mls: attestation keys not fetched: opening the core with the last served keys")
            val pinned = BuildConfig.MLS_PINNED_KEYS.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            val trusted = pinned + trustedServed
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
            mlsCore.opened(
                lk.codegen.risime.data.mls.ObservedMlsEngine(engine) { conv ->
                    mlsChanged.tryEmit(Unit)
                    // §20.6 K3: a merged commit rekeys a running group call at once.
                    runCatching { calls.onGroupChanged(conv) }
                },
                served,
            )
            Log.i("RisiMe", "mls: core open")
            mlsChanged.tryEmit(Unit)
            // Recovery: a commit staged by an earlier run that never reached the server blocks every
            // later commit (and sends) in that chat; nothing of ours is in flight yet.
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val dropped = commitGate.sweep(engine, photoConversations())
                    if (dropped.isNotEmpty()) Log.w("RisiMe", "mls: dropped ${dropped.size} stale staged commit(s) at start-up")
                }.onFailure { Log.w("RisiMe", "mls: start-up staged-commit check failed: ${it.javaClass.simpleName}") }
            }
        }
        val r = deviceRegistrar.register(runCatching { push.currentToken() }.getOrNull())
        return mlsCore.registration(r).also { ok ->
            when (ok) {
                true -> {
                    Log.i("RisiMe", "MLS device registered, ${(r as Registration.Mls).keyPackages} key packages")
                    // A server switch read while this registration was in flight (refreshTabsSwitch →
                    // refreshCapabilities found nothing registered yet and returned) was advertised with
                    // the old value: compare again now (a no-op when nothing changed). Seen on redroid:
                    // RISI_EVENTS turned on, the app restarted, no `risi_events` until the next foreground.
                    refreshCapabilities()
                    // Key packages are up with `groups`: now the server can re-add this device (§12.8).
                    scope.launch { runCatching { syncGroups() } }
                }
                null -> Log.i("RisiMe", "MLS turned down by the server: push-only registration")
                false -> Log.i("RisiMe", "MLS registration failed, will retry (the core stays open): $r")
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
                if (mlsEngine == null || !mlsCore.registered) return@runCatching
                val r = deviceRegistrar.refreshCapabilities(runCatching { push.currentToken() }.getOrNull()) ?: return@runCatching
                Log.i("RisiMe", "capabilities changed: re-registered ($r)")
            }
            // P0 background delivery: the server must hold this phone's current push token.
            runCatching { if ((mlsCore.registered || mlsCore.notApplicable) && canAuthenticate()) push.ensureRegistered() }
        }
    }

    /** One registration attempt: MLS when it applies, else push-only. True when nothing is left to retry. */
    private suspend fun registerDeviceOnce(): Boolean = when (activateMls()) {
        true -> true
        false -> false
        null -> {
            mlsCore.notApplicable = true
            push.register().settled()
        }
    }

    /**
     * [ChatEngine]'s gate: the MLS core is open, or MLS doesn't apply; waits up to 15 s for it to open
     * (a push-started process). Opening no longer waits for the registration.
     */
    private suspend fun mlsReadyForEvents(): Boolean {
        if (!mlsCore.coreExpected()) return true
        return withTimeoutOrNull(15_000) {
            while (mlsCore.coreExpected()) delay(100)
            true
        } ?: false
    }

    val push by lazy {
        PushManager(context, api, sessionStore, deviceRegistrar, { mlsEngine != null }, canAuthenticate = { canAuthenticate() })
    }

    /** A bearer exists: a dev token, or an OIDC session in memory (only an unmigrated pre-064 vault has none). */
    private suspend fun canAuthenticate(): Boolean =
        sessionStore.current()?.let { it.kind == AuthKind.DEV || auth.hasSession() } ?: false

    init {
        // Decision 064: read the token vault at process start, with no UI (a push-started process too).
        // A Keystore that doesn't answer yet (busy after boot) keeps RESTORING: read again with backoff.
        scope.launch(Dispatchers.IO) {
            while (runCatching { auth.restore() }.onFailure { Log.w("RisiMe", "RisiMe auth: restore failed: ${it.message}") }.getOrNull() == lk.codegen.risime.data.auth.SessionState.RESTORING) {
                kotlinx.coroutines.delay(auth.restoreRetryInMs().coerceAtLeast(500))
            }
        }
        scope.launch(Dispatchers.IO) { runCatching { appLock.load() }.onFailure { Log.w("RisiMe", "RisiMe lock: settings: ${it.message}") } }
        // Locked chats: read at process start so the list and the notifications know them; a busy Keystore is retried.
        scope.launch(Dispatchers.IO) {
            var wait = 500L
            while (!runCatching { lockedChats.load() }.getOrDefault(false)) {
                kotlinx.coroutines.delay(wait)
                wait = (wait * 2).coerceAtMost(30_000L)
            }
            // v1.24: the stored set by chat id (a no-op for every pre-v1.24 id: chat_id = conversation_id).
            chatTabs.rows.first { it != null }
            runCatching { lockedChats.migrateToChatIds() }.onFailure { Log.w("RisiMe", "RisiMe lock: chat-id migration: ${it.message}") }
        }
        // §24.9: the last registration of this device id advertised `tabs` (and the server switch is on): tabs at once.
        scope.launch(Dispatchers.IO) {
            val saved = appContext.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getString("advertised_tabs_device", null) ?: return@launch
            val id = runCatching { sessionStore.deviceId() }.getOrNull()
            if (id != null && saved.equals(id, true) && sessionStore.current() != null) chatTabs.restoreAdvertised(true)
            val risi = appContext.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getString("advertised_risi_tools_device", null)
            if (id != null && risi != null && risi.equals(id, true) && sessionStore.current() != null) risiTools.restoreAdvertised(true)
            val skills = appContext.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getString("advertised_risi_skills_device", null)
            if (id != null && skills != null && skills.equals(id, true) && sessionStore.current() != null) risiSkills.restoreAdvertised(true)
            val ledger = appContext.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getString("advertised_risi_ledger_device", null)
            if (id != null && ledger != null && ledger.equals(id, true) && sessionStore.current() != null) risiLedger.restoreAdvertised(true)
            val events = appContext.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getString("advertised_risi_events_device", null)
            if (id != null && events != null && events.equals(id, true) && sessionStore.current() != null) risiEvents.restoreAdvertised(true)
            val notes = appContext.getSharedPreferences("risime_tabs", Context.MODE_PRIVATE).getString("advertised_risi_notes_device", null)
            if (id != null && notes != null && notes.equals(id, true) && sessionStore.current() != null) risiNotes.restoreAdvertised(true)
            // §26.6: every process start re-arms the pending schedules and sends what is overdue.
            if (sessionStore.current() != null) runCatching { scheduled.rearmAll() }.onFailure { Log.w("RisiMe", "scheduled message: re-arm failed: ${it.javaClass.simpleName}") }
        }
        if (BuildConfig.DEBUG) registerDebugOidcSignIn(context)
        if (BuildConfig.DEBUG) registerDebugLockChat(context)
        if (BuildConfig.DEBUG) registerDebugAppLock(context)
        // E2EE: try once per signed-in, verified session that can authenticate. An OIDC session has
        // a bearer once restored (an unmigrated pre-064 vault only after its one migration prompt):
        // registering before that got 401 and left E2EE off for the process (P0 nightly.10).
        scope.launch {
            combine(sessionStore.session, oidcReady()) { s, ready ->
                s?.takeIf { it.user.phoneVerified && (it.kind == AuthKind.DEV || ready) }?.user?.id
            }.distinctUntilChanged().collectLatest { id ->
                mlsCore.notApplicable = false
                if (id == null) {
                    mlsCore.closed()
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
    private val backgroundSync = MutableStateFlow(0)

    /** Live socket events while not in the foreground → notifications (debounced). */
    private val socketIncoming = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /** Notification tap → open this chat (MainActivity sets it, MainNav consumes it). */
    val openChatRequest = MutableStateFlow<String?>(null)

    /** Missed-call notification "Call back" → (conversation id, video), consumed by MainNav once the chat is open. */
    val callBackRequest = MutableStateFlow<Pair<String, Boolean>?>(null)

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
                me = me, tabsOn = chatTabs.uiOn.value, tabRows = chatTabs.rows.value,
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
        // Decision 064: a bearer is restored without UI; only an unmigrated pre-064 vault (or no vault) has none.
        override suspend fun sessionLocked(): Boolean = sessionStore.current()?.kind == AuthKind.OIDC && !auth.hasSession()
        override fun serverNow() = serverClock.serverNow()
        override suspend fun displayName(userId: String) =
            contacts.contacts.first().firstOrNull { it.userId.equals(userId, true) }?.displayName
                // §20: group members who aren't my friends are named from the group's member list.
                ?: runCatching { db.groups().observeAllMembers().first().firstOrNull { it.userId.equals(userId, true) }?.displayName }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "Someone"
        override suspend fun sendSignal(conv: String, peer: String, env: lk.codegen.risime.calls.CallEnvelope.Env, media: String) = engine.sendCallSignal(conv, peer, env, media)
        override fun hideNotificationContent() = appLock.hideNotificationContentBlocking()
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

    /** Only with a bearer and a verified session (§17.8; since decision 064 also in the background). */
    suspend fun canExportHistory(): Boolean =
        BuildConfig.HISTORY_SHARE_ENABLED && sessionStore.current()?.user?.phoneVerified == true && canAuthenticate()

    /** The MLS core for a background job (a push- or WorkManager-started process opens it after the restore). */
    suspend fun awaitMlsCore(timeoutMs: Long = 15_000): MlsEngine? =
        mlsEngine ?: if (!canAuthenticate()) null else withTimeoutOrNull(timeoutMs) { mlsCore.engine.filterNotNull().first() }

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

    // ---- §22 encrypted backups (v1.22, decision 059) ----

    /** The §22.3 calls over ApiClient (writing calls carry X-Device-Id). */
    val backupServer: lk.codegen.risime.data.backup.BackupServer = lk.codegen.risime.data.backup.ApiBackupServer(api) { sessionStore.deviceId() }

    private val backupPrefs by lazy { appContext.getSharedPreferences("risime_backup", Context.MODE_PRIVATE) }

    val backups: lk.codegen.risime.data.backup.BackupManager by lazy {
        lk.codegen.risime.data.backup.BackupManager(
            dir = File(appContext.noBackupFilesDir, "backups"),
            work = File(appContext.noBackupFilesDir, "backup-restore"),
            prefs = object : lk.codegen.risime.data.backup.BackupPrefs {
                override fun get(key: String): String? = backupPrefs.getString(key, null)
                override fun set(key: String, value: String?) = backupPrefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
                override fun clear() = backupPrefs.edit().clear().apply()
            },
            keys = { mlsEngine?.backupKeys },
            tools = { backupTools() },
            exporter = { me ->
                lk.codegen.risime.data.backup.BundleExporter(
                    db.backup(), db.groups(), db.deletes(), db.media(), mediaSealer, me,
                    dmE2ee = { conv -> runCatching { mlsEngine?.group(conv) != null }.getOrDefault(false) },
                    tabOf = { conv -> db.chatTabs().get(conv) },
                )
            },
            importer = { me, progress ->
                lk.codegen.risime.data.backup.BundleImporter(
                    db.messages(), db.deletes(), db.groups(), db.contacts(), db.backup(), db.history(),
                    lk.codegen.risime.data.ReactionStore(db.reactions()), images.takeIf { BuildConfig.CRYPTO_AVAILABLE }, dbTx, progress, me,
                    log = { Log.i("RisiMe", it) },
                    // §24.10: a restored Official conversation keeps its tab (never over a row this phone already has).
                    restoreTab = { t -> if (db.chatTabs().insertIfMissing(t) != -1L) Log.i("RisiMe", "restore: ${t.conversationId} is Official of ${t.chatId}") },
                )
            },
            server = backupServer,
            me = { sessionStore.current()?.user?.id },
            appVersion = BuildConfig.VERSION_NAME,
            localMessages = { db.messages().countAll() },
            log = { Log.i("RisiMe", it) },
        ).also { it.refresh() }
    }

    private val backupToolsImpl: lk.codegen.risime.data.backup.BackupTools? by lazy { lk.codegen.risime.data.backup.BackupTools.get() }

    /** §22.2 the bundled list of the 10 000 most common passwords (case-insensitive), for the passphrase floor. */
    val commonPasswords: Set<String> by lazy {
        runCatching { appContext.assets.open("common-passwords-10k.txt").bufferedReader().useLines { l -> l.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet() } }.getOrDefault(emptySet())
    }

    fun backupTools(): lk.codegen.risime.data.backup.BackupTools? = if (BuildConfig.CRYPTO_AVAILABLE) backupToolsImpl else null

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
        onIncomingFrom = { from ->
            presence.onMessageFrom(from)
            // P0 background delivery: a message over the socket while not in the foreground notifies like a push.
            if (!foreground.value) socketIncoming.tryEmit(Unit)
        },
        mls = mlsPipeline,
        mlsEngine = { mlsEngine },
        catchUp = { conv -> catchUpCommits(conv) },
        reactionsDao = db.reactions(),
        groupsEnabled = { mlsEngine?.groupsSupported == true },
        groups = groupStore,
        chatEvents = { eventId, e, me ->
            chatTabs.applyChatEvent(eventId, e, me, nameOf = { id -> chatMemberName(id) }, insert = { db.messages().insert(it) }, now = System.currentTimeMillis())
        },
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
        mlsReady = { mlsReadyForEvents() },
        log = { Log.w("RisiMe", "deletes: $it") },
        calls = callHooks,
        historyDao = db.history(),
        history = history.takeIf { BuildConfig.HISTORY_SHARE_ENABLED },
        profilePhotos = profilePhotos.takeIf { BuildConfig.CRYPTO_AVAILABLE },
        // §25.3: answered off the event path (a network call); ignored unless this device advertises risi_tools.
        risiToolCalls = { call -> scope.launch { risiToolCalls.handle(call) } },
        // §29.6: content-free; the calendar syncs off the event path (no-op unless this device advertises risi_events).
        onRisiCalendarChanged = { cursor -> syncRisiCalendar(cursor) },
    )


    val realtime: RealtimeClient = PhoenixRealtimeClient(
        http, scope, engine, signals = presence, refusals = { onSocketRefused() },
    )

    /** Decision 055: each time the connection goes live, missing history is asked for once per chat. */
    private val historyAutoRequest = if (BuildConfig.HISTORY_SHARE_ENABLED) scope.launch {
        realtime.state.collectLatest { st ->
            if (st == lk.codegen.risime.realtime.ConnectionState.Live) {
                kotlinx.coroutines.delay(20_000) // groups joined and gaps recorded first
                // §22.7: a fresh install's restore (or skip) comes first; restored rows need no history request.
                backups.gate.first { it == lk.codegen.risime.data.backup.RestoreGate.Open }
                runCatching { history.autoRequestAll() }.onFailure { Log.w("RisiMe", "history auto-request: ${it.message}") }
            }
        }
    } else null

    private val foreground = MutableStateFlow(false)

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground.value = true
                appLock.checkOnReturn()
                scope.launch { appLock.onForeground() }
                refreshCapabilities()
                scope.launch {
                    updater.onForeground()
                    updater.maybeCheck(SystemClock.elapsedRealtime())
                }
                scope.launch { behaviour.appOpen() }
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground.value = false
                appLock.onBackground()
                lockedChats.closeFolder() // leaving the app re-locks the Locked chats folder
                // Everything that arrived while the app was open has been seen in the app.
                scope.launch { sessionStore.setNotifiedUpTo(System.currentTimeMillis()) }
            }
        })
        // Connected only while in the foreground (+ a 5-s grace) and signed in with a bearer. In the
        // background the socket is closed so the server pushes (P0 background delivery).
        scope.launch {
            // android R6: a ringing, connecting or active call keeps the socket up regardless of foreground.
            combine(
                combine(
                    lk.codegen.risime.push.foregroundHoldFlow(foreground, screenInteractive()),
                    backgroundSync, calls.keepConnected, historyConnection,
                ) { f, b, c, h ->
                    lk.codegen.risime.push.socketWanted(f, b > 0, c, h > 0)
                },
                sessionStore.session, oidcReady(), blocked,
            ) { fg, s, ready, b ->
                if (shouldConnect(fg, s, ready, b)) s!!.serverUrl to s.user.id else null
            }
                .distinctUntilChanged()
                .collect { s ->
                    Log.i("RisiMe", "RisiMe push: socket ${if (s != null) "up" else "closed"} (foreground=${foreground.value} sync=${backgroundSync.value} call=${calls.keepConnected.value})")
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
        // P0 background delivery: socket events while not in the foreground (grace, a call, a push
        // sync, a history export) → the same local notifications as a push-woken sync.
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            socketIncoming.debounce(SOCKET_NOTIFY_DEBOUNCE_MS).collect {
                if (lk.codegen.risime.push.shouldNotifyFromSocket(foreground.value, engine.replayingFresh)) {
                    Log.i("RisiMe", "RisiMe push: socket message while in the background")
                    runCatching { notifyFromLocal() }.onFailure { Log.w("RisiMe", "socket notify: ${it.message}") }
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
        // §22.7 (P0-2): a local backup (and the upload, ≤ 2 min) before every in-app update; a failure
        // says "don't uninstall" and the update still goes ahead.
        updater.beforeInstall = lk.codegen.risime.update.BeforeUpdateInstall {
            try {
                backups.beforeUpdate()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("RisiMe", "pre-update backup failed: ${e.message}")
                notifier.postBackupFailed()
            }
        }
        // §22.7 the daily backup job (local always; the upload when server backup is on).
        scope.launch {
            sessionStore.session.map { it != null }.distinctUntilChanged().collect { signedIn ->
                if (signedIn) runCatching { lk.codegen.risime.push.BackupWorker.schedule(appContext) }
            }
        }
        // §22.7 catch-up: the daily backup when the job couldn't run (a locked app has no core in the background).
        scope.launch {
            realtime.state.collectLatest { st ->
                if (st != ConnectionState.Live) return@collectLatest
                delay(60_000)
                val b = backups
                val last = b.status.value.lastLocal?.at ?: 0L
                if (mlsEngine?.backupKeys == null || System.currentTimeMillis() - last < 24 * 3_600_000L) return@collectLatest
                val net = lk.codegen.risime.push.currentNetKind(appContext)
                val upload = b.serverOn && (net == lk.codegen.risime.data.media.NetKind.UNMETERED || (b.mobileData && net == lk.codegen.risime.data.media.NetKind.METERED))
                runCatching { b.backupNow(lk.codegen.risime.data.backup.BackupReason.DAILY, upload = upload) }
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
            combine(foreground, oidcReady()) { fg, u -> fg && u }.distinctUntilChanged().collectLatest { active ->
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
                    authDiagnostics.signedOut(lk.codegen.risime.data.auth.SignOutTrigger.INVALID_GRANT)
                    realtime.stop()
                    localAccount.signOutKeepData()
                    signInNotice.value = "Your RisiCloud session ended. Sign in again — your chats are kept."
                    auth.acknowledgeSignInNeeded()
                }
            }
        }
        // An OIDC session from a previous process with no token set to restore (a pre-064 memory-only
        // install, or an unreadable vault): sign in once more, chats kept; from then on it stays.
        scope.launch {
            val st = auth.state.first { it != lk.codegen.risime.data.auth.SessionState.RESTORING }
            val s = sessionStore.current()
            // The user's own "Sign in again" on the stuck screen signs out itself (giveUpSavedSignIn).
            val userChose = auth.restoreProblem == lk.codegen.risime.data.auth.SignOutTrigger.VAULT_UNREADABLE_USER
            if (s?.kind == AuthKind.OIDC && st == lk.codegen.risime.data.auth.SessionState.NONE && !userChose) {
                authDiagnostics.signedOut(auth.restoreProblem ?: lk.codegen.risime.data.auth.SignOutTrigger.NO_STORED_SESSION)
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
        // §24.15: the server's `tabs` switch, read on every (re)join (a change re-advertises `tabs`).
        scope.launch {
            realtime.state.collect { if (it == ConnectionState.Live) runCatching { refreshTabsSwitch() } }
        }
        // §24.11: the phone's timezone to the server on every (re)join (a no-op when unchanged) and when it changes.
        scope.launch {
            realtime.state.collect { if (it == ConnectionState.Live) runCatching { syncTimezone() } }
        }
        run {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    scope.launch { runCatching { syncTimezone() } }
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(
                appContext, r, android.content.IntentFilter(android.content.Intent.ACTION_TIMEZONE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        // The group-op outbox: one runner; a queued retry re-arms the timer.
        scope.launch {
            groupOpsRun.collectLatest {
                // A new kick cancels only the wait: a pass that is building or submitting a commit runs to
                // its end (cancelling it there used to leave a staged commit behind).
                var nextAt = withContext(NonCancellable) { runCatching { groupOps.runDue() }.getOrNull() }
                while (nextAt != null) {
                    delay((nextAt - System.currentTimeMillis()).coerceAtLeast(250))
                    nextAt = withContext(NonCancellable) { runCatching { groupOps.runDue() }.getOrNull() }
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
        runCatching { backups.onSignIn() }
        sessionStore.saveLogin(token, user)
        return true
    }

    /**
     * Debug builds only (the device gate, decision 064): Redroid has no browser for the RisiCloud
     * sign-in, so `adb shell am broadcast -a lk.codegen.risime.debug.OIDC_SIGN_IN` hands the app a
     * token set from the gate's stand-in issuer; from there it is the real path ([completeOidcSignIn]:
     * the vault, GET /me, the session). Only the shell (android.permission.DUMP) can send it.
     */
    private fun registerDebugOidcSignIn(context: Context) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: android.content.Intent) {
                val issuer = i.getStringExtra("issuer") ?: return
                val access = i.getStringExtra("access_token") ?: return
                val refresh = i.getStringExtra("refresh_token") ?: return
                val clientId = i.getStringExtra("client_id") ?: "risime"
                val expires = i.getStringExtra("expires_in")?.toLongOrNull() ?: 300L
                val pending = goAsync()
                scope.launch {
                    try {
                        val err = completeOidcSignIn(issuer, clientId, OidcTokens(access, expires, refresh, null))
                        Log.i("RisiMe", "RisiMe debug: oidc sign-in ${err ?: "ok"}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context, r, android.content.IntentFilter("lk.codegen.risime.debug.OIDC_SIGN_IN"),
                android.Manifest.permission.DUMP, null, androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w("RisiMe", "RisiMe debug: oidc receiver: ${it.message}") }
    }

    /**
     * Debug builds only, guarded exactly like the OIDC receiver (runtime-registered, shell-only through
     * android.permission.DUMP): the device gates (redroid has no fingerprint or screen lock) lock or
     * unlock a chat. Extras: `conversation_id`, or `first_dm` (the most recent 1:1 chat), or `unlock_all`.
     */
    private fun registerDebugLockChat(context: Context) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: android.content.Intent) {
                val pending = goAsync()
                scope.launch {
                    try {
                        val ok = when {
                            i.getBooleanExtra("unlock_all", false) -> lockedChats.resetAll()
                            else -> {
                                val conv = i.getStringExtra("conversation_id")
                                    ?: if (i.getBooleanExtra("first_dm", false)) {
                                        db.messages().lastMessages().first().filter { it.conversationId.startsWith("dm:") }.maxByOrNull { it.localTs }?.conversationId
                                    } else null
                                conv != null && lockedChats.lock(conv)
                            }
                        }
                        Log.i("RisiMe", "RisiMe debug: lock chat ${if (ok) "ok" else "failed"}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context, r, android.content.IntentFilter("lk.codegen.risime.debug.LOCK_CHAT"),
                android.Manifest.permission.DUMP, null, androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w("RisiMe", "RisiMe debug: lock receiver: ${it.message}") }
    }

    /**
     * Debug builds only (scripts/applock-device-test): Redroid has no fingerprint sensor, so the
     * Settings row never shows there; `adb shell am broadcast -a lk.codegen.risime.debug.APP_LOCK
     * --ez enabled true --el auto_lock_ms 0` sets the lock directly. Only the shell
     * (android.permission.DUMP) can send it.
     */
    private fun registerDebugAppLock(context: Context) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: android.content.Intent) {
                val enabled = i.getBooleanExtra("enabled", false)
                val auto = lk.codegen.risime.data.lock.AutoLock.ofMs(i.getLongExtra("auto_lock_ms", 0L))
                val pending = goAsync()
                scope.launch {
                    try {
                        appLock.debugSet(enabled, auto)
                        Log.i("RisiMe", "RisiMe debug: app lock enabled=$enabled auto=$auto")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context, r, android.content.IntentFilter("lk.codegen.risime.debug.APP_LOCK"),
                android.Manifest.permission.DUMP, null, androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w("RisiMe", "RisiMe debug: app lock receiver: ${it.message}") }
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
                    authDiagnostics.signedOut(lk.codegen.risime.data.auth.SignOutTrigger.SIGN_IN_FAILED)
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
                authDiagnostics.signedOut(lk.codegen.risime.data.auth.SignOutTrigger.SIGN_IN_FAILED)
                auth.signOut()
                "The RisiMe server didn't accept this RisiCloud sign-in."
            }
            is MeOutcome.Transient -> {
                authDiagnostics.signedOut(lk.codegen.risime.data.auth.SignOutTrigger.SIGN_IN_FAILED)
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
            authDiagnostics.signedOut(lk.codegen.risime.data.auth.SignOutTrigger.ANOTHER_ACCOUNT)
            withTimeoutOrNull(LOGOUT_NETWORK_MS) { runCatching { auth.signOut() } }
            runCatching { auth.forgetLocally() }
            return false
        }
        blocked.value = null
        signInNotice.value = null
        runCatching { backups.onSignIn() }
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
        authDiagnostics.signedOut(lk.codegen.risime.data.auth.SignOutTrigger.ANOTHER_ACCOUNT)
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
        authDiagnostics.signedOut(if (confirmed.deleteChats) lk.codegen.risime.data.auth.SignOutTrigger.USER_LOGOUT_DELETE else lk.codegen.risime.data.auth.SignOutTrigger.USER_LOGOUT)
        notifier.cancelAll()
        val end = auth.issuerAndClient()?.let { (issuer, _) -> auth.idToken()?.let { EndSession(issuer, it) } }
        try {
            // Server side is best effort and bounded: a dead network or a Keycloak error never
            // blocks or reverts the local logout.
            // §15.7 (android S-f): deletes and clears done just before logout reach the server (best effort, bounded).
            withTimeoutOrNull(LOGOUT_NETWORK_MS) { runCatching { engine.flushDeletes() } }
            // §22.7: a backup before the confirmed wipe (uploaded when server backup is on).
            if (confirmed.deleteChats) withTimeoutOrNull(PRE_WIPE_BACKUP_MS) { runCatching { backups.beforeWipe() } }
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
    suspend fun signOutKeepChats(
        notice: String? = "Signed out — your chats are kept.",
        trigger: lk.codegen.risime.data.auth.SignOutTrigger = lk.codegen.risime.data.auth.SignOutTrigger.ESCAPE_SCREEN,
    ) {
        authDiagnostics.signedOut(trigger)
        realtime.stop()
        withTimeoutOrNull(LOGOUT_NETWORK_MS) { runCatching { auth.signOut() } }
        runCatching { auth.forgetLocally() }
        blocked.value = null
        localAccount.signOutKeepData()
        signInNotice.value = notice
    }

    /**
     * The loading screen's way out when the Keystore won't open the saved sign-in ([AuthManager.vaultStuck]):
     * forget only the token vault and go to the sign-in, keeping every chat (rule 9).
     */
    suspend fun giveUpSavedSignIn() {
        auth.giveUpVault()
        signOutKeepData("Sign in again — your chats are kept.", lk.codegen.risime.data.auth.SignOutTrigger.VAULT_UNREADABLE_USER)
    }

    /** §6: token rejected (refresh already tried): back to sign-in, keeping local chats. */
    suspend fun signOutKeepData(
        notice: String,
        trigger: lk.codegen.risime.data.auth.SignOutTrigger = lk.codegen.risime.data.auth.SignOutTrigger.UNAUTHORIZED,
    ) {
        // Decision 064: Keycloak unreachable (refresh failing, not refused) is no reason to end an
        // offline session; the next refresh recovers it. Only invalid_grant or a server refusal with
        // a fresh token signs out.
        if (trigger == lk.codegen.risime.data.auth.SignOutTrigger.UNAUTHORIZED && auth.refreshFailingTransiently()) {
            Log.w("RisiMe", "RisiMe auth: 401 while the token refresh is failing: session kept")
            return
        }
        authDiagnostics.signedOut(trigger)
        realtime.stop()
        if (auth.hasSession()) auth.signOut()
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
        // Keycloak unreachable (refresh failing): retry later, never sign out for it (decision 064).
        MeOutcome.Unauthorized -> if (auth.refreshFailingTransiently()) {
            true
        } else {
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
        // §22.7: a backup (to the current server) before the confirmed switch wipes this phone.
        withTimeoutOrNull(PRE_WIPE_BACKUP_MS) { runCatching { backups.beforeWipe() } }
        if (sessionStore.current()?.kind == AuthKind.DEV) api.logout()
        authDiagnostics.signedOut(lk.codegen.risime.data.auth.SignOutTrigger.SWITCH_SERVER)
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
    /** Inbox wake-ups vs direct syncs (the worker skips its socket session when a direct sync covered it). */
    val pushWakes = lk.codegen.risime.push.PushWakeTracker()

    /**
     * [quick] = the direct sync of a push wake-up (bounded by the caller). [workerEnqueuedAt] = the
     * worker's wake-up stamp: when a direct sync already went live since, the worker skips the socket.
     */
    suspend fun syncAndNotify(quick: Boolean = false, workerEnqueuedAt: Long = 0L) {
        val s = sessionStore.current() ?: return
        if (!s.user.phoneVerified) return
        // Decision 064: the session is restored from the vault with no UI. Only a pre-064 vault that
        // still waits for its one migration prompt has no bearer here: a content-free notice.
        if (s.kind == AuthKind.OIDC && !auth.hasSession()) {
            Log.i("RisiMe", "RisiMe push: sync skipped (no session: ${auth.state.value}): content-free notice")
            notifier.postLocked()
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        if (!quick && !foreground.value && pushWakes.workerCanSkipSocket(workerEnqueuedAt)) {
            Log.i("RisiMe", "RisiMe push: sync start (worker, socket skipped: the direct sync covered this wake-up)")
            runCatching { contacts.refresh() }
            withTimeoutOrNull(20_000) { runCatching { groupOps.runDue() } }
            notifyFromLocal()
            Log.i("RisiMe", "RisiMe push: sync end after ${SystemClock.elapsedRealtime() - t0} ms")
            return
        }
        Log.i("RisiMe", "RisiMe push: sync start (${if (quick) "direct" else "worker"}, foreground=${foreground.value}, socket=${realtime.state.value})")
        if (!foreground.value) {
            backgroundSync.update { it + 1 }
            try {
                // Live = the join's catch-up pages are applied: notify at once (P0: within FCM's ~10-s window).
                val live = withTimeoutOrNull(if (quick) DIRECT_PUSH_SYNC_MS - 1_000 else 25_000) { realtime.state.first { it == ConnectionState.Live } } != null
                Log.i("RisiMe", "RisiMe push: sync ${if (live) "live" else "not live"} after ${SystemClock.elapsedRealtime() - t0} ms")
                if (live) {
                    notifyFromLocal()
                    if (quick) pushWakes.directLive()
                }
                if (!quick) {
                    delay(1_500) // let live events and the friends refetch land
                    contacts.refresh()
                    // §12.4a wake push: a group op naming this device is committed before the worker ends.
                    withTimeoutOrNull(20_000) { runCatching { groupOps.runDue() } }
                }
            } finally {
                backgroundSync.update { it - 1 }
            }
        }
        notifyFromLocal()
        Log.i("RisiMe", "RisiMe push: sync end after ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /** Screen on/off (SCREEN_ON/SCREEN_OFF broadcasts; the initial value from PowerManager). */
    private fun screenInteractive(): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.callbackFlow {
        val pm = appContext.getSystemService(android.os.PowerManager::class.java)
        trySend(pm?.isInteractive ?: true)
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                trySend(i?.action == android.content.Intent.ACTION_SCREEN_ON)
            }
        }
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
        }
        androidx.core.content.ContextCompat.registerReceiver(appContext, r, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { runCatching { appContext.unregisterReceiver(r) } }
    }.distinctUntilChanged()

    private val notifyLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun notifyFromLocal() = notifyLock.withLock { notifyFromLocalLocked() }

    private suspend fun notifyFromLocalLocked() {
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
                me = me, tabsOn = chatTabs.uiOn.value, tabRows = chatTabs.rows.value,
            ),
            reactionAdds, { targets[it] }, { id -> names[id.lowercase()] ?: "Someone" }, me, open,
        )
        if (!foreground.value && plan.isNotEmpty()) {
            val posted = notifier.postChats(plan)
            Log.i("RisiMe", "RisiMe push: notification ${if (posted) "posted" else "NOT posted (notifications off)"} chats=${plan.size}")
        }
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

    init {
        // §23.5: a share that starts redacts the message notifications already in the shade (and restores them after).
        lk.codegen.risime.calls.ScreenSharing.watch(scope) { refreshPostedNotifications() }
    }

    private suspend fun wipeDb(reason: WipeReason) = withContext(Dispatchers.IO) {
        Log.w("RisiMe", "wiping local chats: $reason")
        db.wipe().allChatData()
        runCatching { backups.wipeLocal() } // §22.7: the app-private backups go too (BK went with mls_kv)
        runCatching { androidx.work.WorkManager.getInstance(appContext).cancelAllWork() }
        mediaFiles.wipe()
        File(appContext.noBackupFilesDir, "avatars").deleteRecursively()
        photoPrefs.edit().clear().apply()
        runCatching { lockedChats.wipe() } // only "delete chats from this phone": the locked list goes with them
        mlsCore.wiped()
        mlsDbKey.destroy()
    }
}

private inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(f(value))
    is ApiResult.Error -> this
    is ApiResult.NetworkError -> this
}
