package lk.codegen.risime.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import lk.codegen.risime.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState

    data class Available(val info: UpdateInfo) : UpdateState

    /** [percent]: download progress while known. */
    data class Working(val info: UpdateInfo, val step: String, val percent: Int? = null) : UpdateState

    /** "Install unknown apps" isn't granted yet; Settings was opened. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState

    /** [message]: the real error in plain words (installer message, HTTP code, check that failed). */
    data class Failed(val info: UpdateInfo, val message: String) : UpdateState
}

/** Every state carrying a `required` release blocks the app (decision 016). */
fun UpdateState.blocking(): UpdateInfo? = when (this) {
    UpdateState.Idle -> null
    is UpdateState.Available -> info.takeIf { it.required }
    is UpdateState.Working -> info.takeIf { it.required }
    is UpdateState.NeedsPermission -> info.takeIf { it.required }
    is UpdateState.Failed -> info.takeIf { it.required }
}

/** The last check, for Settings → About ("Check for updates"). */
data class CheckStatus(val atWallMs: Long, val result: String)

/**
 * P0-2 seam: called right before the verified APK is handed to PackageInstaller (a silent update
 * closes the app moments later). The coming local chat backup plugs a synchronous backup in here;
 * the install waits until it returns. A failure is logged and the install continues: an in-place
 * update keeps the chats by itself (decision 055), so a broken backup must not block updates.
 */
fun interface BeforeUpdateInstall {
    suspend fun onBeforeUpdateInstall(target: UpdateInfo)

    companion object {
        val None = BeforeUpdateInstall { }
    }
}

private val Context.updateDataStore by preferencesDataStore(name = "risime_update")

/** The updater's own small store: the release being installed, the last attempt, the last check. */
class UpdateStore(private val prefs: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun pending(): UpdateInfo? =
        prefs.data.first()[PENDING]?.let { runCatching { json.decodeFromString(UpdateInfo.serializer(), it) }.getOrNull() }

    suspend fun setPending(info: UpdateInfo?) {
        prefs.edit { if (info == null) it.remove(PENDING) else it[PENDING] = json.encodeToString(UpdateInfo.serializer(), info) }
    }

    suspend fun attempt(): UpdateAttempt? = UpdateAttempt.decode(prefs.data.first()[ATTEMPT])

    suspend fun setAttempt(a: UpdateAttempt) {
        prefs.edit { it[ATTEMPT] = a.encode() }
    }

    suspend fun lastCheck(): CheckStatus? = prefs.data.first().let { p ->
        val at = p[CHECK_AT] ?: return@let null
        CheckStatus(at, p[CHECK_RESULT] ?: "")
    }

    suspend fun setLastCheck(c: CheckStatus) {
        prefs.edit {
            it[CHECK_AT] = c.atWallMs
            it[CHECK_RESULT] = c.result
        }
    }

    private companion object {
        val PENDING = stringPreferencesKey("pending")
        val ATTEMPT = stringPreferencesKey("attempt")
        val CHECK_AT = longPreferencesKey("check_at")
        val CHECK_RESULT = stringPreferencesKey("check_result")
    }
}

/**
 * Release-only self-updater (decisions 016, 027; P0-1). Checks `version.json` on every foreground
 * (15 min throttle, ETag). Download, verification and install run in [UpdateWorker] (a foreground
 * WorkManager job), never in the UI: leaving the app or rotating doesn't stop it, a partial download
 * resumes with HTTP Range, and every failure is kept and shown in plain words.
 */
class Updater(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val baseUrl: String = BuildConfig.UPDATE_BASE_URL,
    val enabled: Boolean = BuildConfig.UPDATER_ENABLED,
) {
    private val store by lazy { UpdateStore(context.updateDataStore) }
    val notifications = UpdateNotifications(context)

    /** P0-2 plugs the local backup in here. */
    @Volatile var beforeInstall: BeforeUpdateInstall = BeforeUpdateInstall.None

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _lastCheck = MutableStateFlow<CheckStatus?>(null)
    val lastCheck: StateFlow<CheckStatus?> = _lastCheck.asStateFlow()

    @Volatile private var lastCheckElapsed: Long? = null
    @Volatile private var etag: String? = null
    @Volatile private var lastBody: String? = null

    /** The PackageInstaller session in flight (a late status from an older one is ignored). */
    @Volatile private var sessionId: Int? = null

    /** A system confirmation to show (STATUS_PENDING_USER_ACTION); the activity launches it. */
    private val _confirm = MutableStateFlow<Intent?>(null)
    val confirm: StateFlow<Intent?> = _confirm.asStateFlow()

    init {
        if (enabled) scope.launch { runCatching { restore() }.onFailure { Log.w(TAG, "update restore: ${it.message}") } }
    }

    private suspend fun restore() {
        val attempt = store.attempt()
        _lastCheck.value = store.lastCheck()
        val installed = installedVersionCode()
        if (attempt != null && attempt.versionCode <= installed && attempt.step != UpdateStep.INSTALLED) {
            store.setAttempt(attempt.copy(step = UpdateStep.INSTALLED, atMs = System.currentTimeMillis()))
            store.setPending(null)
            clearDownloads(keep = null)
        }
        val s = stateOnStart(attempt, store.pending(), installed)
        if (_state.value == UpdateState.Idle) _state.value = s
    }

    fun confirmShown() {
        _confirm.value = null
    }

    private fun appInForeground(): Boolean =
        runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)

    /** Back in the foreground: continue an update that was waiting for "install unknown apps". */
    suspend fun onForeground() {
        if (!enabled) return
        notifications.cancelStatus() // the banner shows the same thing now
        val s = _state.value
        if (s is UpdateState.NeedsPermission && context.packageManager.canRequestPackageInstalls()) start(s.info)
    }

    /**
     * Called on every foreground (and every 15 min while there): at most once per
     * [UPDATE_CHECK_INTERVAL_MS] unless [force] ("Check for updates"). Returns null when throttled.
     */
    suspend fun maybeCheck(nowElapsedMs: Long, force: Boolean = false): CheckStatus? {
        if (!enabled) return CheckStatus(System.currentTimeMillis(), "In-app updates are off in debug builds.")
        if (!shouldCheck(lastCheckElapsed, nowElapsedMs, force = force)) return null
        val result = runCatching { fetchVersionJson() }
        // Only an answer counts for the throttle: offline, the next foreground tries again.
        if (result.isSuccess) lastCheckElapsed = nowElapsedMs
        val status = result.fold(
            onSuccess = { text ->
                val d = decide(VersionJson.parse(text), installedVersionCode(), baseUrl)
                _state.value = stateAfterCheck(_state.value, d)
                when (d) {
                    UpdateDecision.UpToDate -> "Up to date"
                    is UpdateDecision.Available -> "RisiMe ${d.info.versionName} is available"
                    is UpdateDecision.Required -> "RisiMe ${d.info.versionName} is available (required)"
                    is UpdateDecision.Rejected -> "Update ignored: ${d.reason}".also { Log.w(TAG, "update ignored: ${d.reason}") }
                }
            },
            onFailure = { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "update check failed: ${e.message}")
                "Couldn't check: " + when (e) {
                    is UpdateHttpException -> "the update server answered HTTP ${e.code}"
                    is java.io.IOException -> "no connection to the update server"
                    else -> e.message ?: e.javaClass.simpleName
                }
            },
        )
        return CheckStatus(System.currentTimeMillis(), status).also {
            _lastCheck.value = it
            runCatching { store.setLastCheck(it) }
        }
    }

    /** GET version.json with If-None-Match; a 304 reuses the body we already have. */
    private suspend fun fetchVersionJson(): String {
        val known = lastBody
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/version.json")
            .apply { val e = etag; if (e != null && known != null) header("If-None-Match", e) }
            .build()
        return http.newCall(req).awaitCancellable { r ->
            when {
                r.code == 304 && known != null -> known
                r.isSuccessful -> r.body.string().also {
                    etag = r.header("ETag")
                    lastBody = it
                }
                else -> throw UpdateHttpException(r.code)
            }
        }
    }

    fun dismiss() {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.NeedsPermission -> s.info
            is UpdateState.Failed -> s.info
            else -> null
        }
        if (info != null && !info.required) _state.value = UpdateState.Idle
    }

    private fun installedVersionCode(): Long {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    /**
     * "Update" / "Retry": asks for "install unknown apps" if needed (the UI is on screen), else hands
     * the release to [UpdateWorker]. Returns at once; the state and the notification show progress.
     */
    fun start(info: UpdateInfo) {
        if (!enabled) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info)
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                fail(info, UpdateStep.DOWNLOAD, "This device won't let RisiMe install updates. Use \"$DOWNLOAD_FROM_WEBSITE\".")
            }
            return
        }
        _state.value = UpdateState.Working(info, "Starting the download…")
        scope.launch {
            store.setPending(info)
            UpdateWorker.enqueue(context)
        }
    }

    private fun apkFiles(info: UpdateInfo): Pair<File, File> {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        return File(dir, "risime-${info.versionCode}.apk.part") to File(dir, "risime-${info.versionCode}.apk")
    }

    /** Removes downloads of other releases (and everything when [keep] is null). */
    private fun clearDownloads(keep: UpdateInfo?) {
        val names = keep?.let { apkFiles(it).toList().map(File::getName).toSet() } ?: emptySet()
        File(context.filesDir, "updates").listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    private fun persist(info: UpdateInfo, step: UpdateStep, error: String? = null, status: Int? = null, statusMessage: String? = null, session: Int? = null) {
        val a = UpdateAttempt(
            info.versionCode, info.versionName, step, status, statusMessage, error, session,
            System.currentTimeMillis(), wasForeground = appInForeground(),
        )
        scope.launch { runCatching { store.setAttempt(a) } }
    }

    private fun fail(info: UpdateInfo, step: UpdateStep, error: String, status: Int? = null, statusMessage: String? = null) {
        Log.w(TAG, "RisiMe update: status=${status ?: "-"} msg=${statusMessage ?: "-"} step=$step error=$error")
        _state.value = UpdateState.Failed(info, error)
        persist(info, UpdateStep.FAILED, error, status, statusMessage)
        if (!appInForeground()) notifications.failed(info, error)
    }

    /**
     * The worker's job: download (resuming a partial file) → verify → [beforeInstall] → install.
     * [onProgress] updates the foreground notification. Cancellation (the user's Cancel, or the
     * system stopping the job) cancels the HTTP call at once and keeps the partial file.
     */
    suspend fun runUpdate(onProgress: (UpdateInfo, String, Int?) -> Unit) {
        if (!enabled) return
        val stored = store.pending() ?: return
        // Retry after a republish (or a newer nightly): take what the server offers now.
        val fresh = runCatching { decide(VersionJson.parse(fetchVersionJson()), installedVersionCode(), baseUrl) }
            .onFailure { if (it is CancellationException) throw it }.getOrNull()
        val info = refreshTarget(stored, fresh)
        if (info != stored) {
            store.setPending(info)
            if (info.versionCode == stored.versionCode && (info.sha256 != stored.sha256 || info.url != stored.url)) {
                apkFiles(info).toList().forEach { it.delete() } // a different file now: don't resume the old bytes
            }
        }
        if (info.versionCode <= installedVersionCode()) {
            store.setPending(null)
            clearDownloads(keep = null)
            if (_state.value.infoOrNull()?.versionCode == info.versionCode) _state.value = UpdateState.Idle
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            fail(info, UpdateStep.DOWNLOAD, "RisiMe isn't allowed to install updates. Tap Retry and allow it.")
            return
        }
        clearDownloads(keep = info)
        val (part, apk) = apkFiles(info)
        fun working(step: String, percent: Int? = null) {
            _state.value = UpdateState.Working(info, step, percent)
            onProgress(info, step, percent)
        }
        var step = UpdateStep.DOWNLOAD
        try {
            persist(info, step)
            working(downloadStep(null))
            if (!urlAllowed(info.url, baseUrl)) error("download URL outside the update server")
            ResumableDownload(http).fetch(info.url, part, apk) { pct -> working(downloadStep(pct), pct) }
            step = UpdateStep.VERIFY
            persist(info, step)
            working("Verifying…")
            val facts = withContext(Dispatchers.IO) { facts(apk, sha256Hex(apk)) }
            when (val v = verifyApk(info, facts, context.packageName)) {
                is VerifyResult.Failed -> {
                    apk.delete()
                    fail(info, step, verifyFailureMessage(v.reason))
                    return
                }
                VerifyResult.Ok -> Unit
            }
            step = UpdateStep.INSTALL
            working("Installing…")
            runCatching { beforeInstall.onBeforeUpdateInstall(info) }
                .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "before-install hook failed; installing anyway", it) }
            notifications.installing(info)
            val silent = requestSilentUpdate(Build.VERSION.SDK_INT, archiveTargetSdk(apk))
            val id = withContext(Dispatchers.IO) { install(apk, silent) }
            sessionId = id
            persist(info, step, session = id)
            Log.i(TAG, "RisiMe update: committed session=$id version=${info.versionCode} silent=$silent")
        } catch (e: CancellationException) {
            Log.i(TAG, "RisiMe update: stopped during $step (partial download kept)")
            if (_state.value is UpdateState.Working) _state.value = UpdateState.Available(info)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update failed during $step", e)
            if (step == UpdateStep.INSTALL) notifications.cancelStatus()
            fail(info, step, if (step == UpdateStep.INSTALL) "App not installed: ${e.message ?: e.javaClass.simpleName}." else downloadFailureMessage(e))
        }
    }

    /** Installer status from [InstallResultReceiver], whatever the state is now (late statuses included). */
    fun onInstallStatus(status: Int, message: String?, statusSession: Int?) {
        Log.i(TAG, "RisiMe update: status=$status msg=${message ?: "-"} session=${statusSession ?: "-"}")
        scope.launch {
            val target = runCatching { store.pending() }.getOrNull()
            val out = stateAfterInstallStatus(_state.value, target, sessionId, statusSession, status, message)
            if (out.ignored) return@launch
            val info = out.state.infoOrNull() ?: target
            when (val s = out.state) {
                is UpdateState.Failed -> {
                    notifications.cancelStatus()
                    fail(s.info, UpdateStep.INSTALL, s.message, status, message)
                }
                else -> {
                    _state.value = s
                    if (status == INSTALL_SUCCESS && info != null) persist(info, UpdateStep.INSTALLED, status = status, statusMessage = message)
                }
            }
        }
    }

    /** STATUS_PENDING_USER_ACTION: the activity shows the system dialog (or a notification asks for a tap). */
    fun onPendingUserAction(intent: Intent, statusSession: Int?) {
        Log.i(TAG, "RisiMe update: status=$INSTALL_PENDING_USER_ACTION msg=confirmation needed session=${statusSession ?: "-"}")
        _confirm.value = intent
        scope.launch {
            val target = runCatching { store.pending() }.getOrNull()
            val out = stateAfterInstallStatus(_state.value, target, sessionId, statusSession, INSTALL_PENDING_USER_ACTION, null)
            if (out.ignored) return@launch
            _state.value = out.state
            if (!appInForeground()) out.state.infoOrNull()?.let { notifications.confirm(it) }
        }
    }

    /** ACTION_MY_PACKAGE_REPLACED, in the new version's process. */
    suspend fun onPackageReplaced() {
        if (!enabled) return
        val attempt = runCatching { store.attempt() }.getOrNull()
        Log.i(TAG, "RisiMe update: replaced, now ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); attempt=${attempt?.versionCode}")
        if (attempt != null && attempt.versionCode <= installedVersionCode()) {
            store.setAttempt(attempt.copy(step = UpdateStep.INSTALLED, atMs = System.currentTimeMillis()))
            store.setPending(null)
        }
        clearDownloads(keep = null)
        // A check may already have offered a newer release in this process: keep that.
        if ((_state.value.infoOrNull()?.versionCode ?: 0) <= installedVersionCode()) _state.value = UpdateState.Idle
        notifications.updated(BuildConfig.VERSION_NAME)
        // The user was in the app when it closed for the update: try to bring it back (Android may
        // block a start from the background; the notification stays either way).
        if (attempt?.wasForeground == true) {
            runCatching {
                context.startActivity(
                    Intent(context, lk.codegen.risime.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.i(TAG, "relaunch after update not allowed: ${it.message}") }
        }
    }

    private fun facts(file: File, sha: String): ApkFacts {
        val pm = context.packageManager
        val info: PackageInfo? = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
        }
        val certs: List<ByteArray> = when {
            info == null -> emptyList()
            Build.VERSION.SDK_INT >= 28 -> info.signingInfo?.let { si ->
                if (si.hasMultipleSigners()) si.apkContentsSigners.map { it.toByteArray() }
                else si.signingCertificateHistory.lastOrNull()?.let { listOf(it.toByteArray()) } ?: emptyList()
            } ?: emptyList()
            else -> @Suppress("DEPRECATION") info.signatures?.map { it.toByteArray() } ?: emptyList()
        }
        val sha256 = { b: ByteArray -> MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } }
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
        return ApkFacts(sha, info?.packageName, code, certs.map(sha256))
    }

    private fun archiveTargetSdk(file: File): Int =
        context.packageManager.getPackageArchiveInfo(file.path, 0)?.applicationInfo?.targetSdkVersion ?: 0

    /**
     * [silent]: ask Android 12+ to skip the confirmation (decision 027): we update ourselves, hold
     * REQUEST_INSTALL_PACKAGES + UPDATE_PACKAGES_WITHOUT_USER_ACTION, and the APK's targetSdk is
     * recent enough. The system may still ask (STATUS_PENDING_USER_ACTION → confirm dialog).
     * Returns the session id.
     */
    private fun install(file: File, silent: Boolean): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(
                    if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED else PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                )
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("risime.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(
                    context, id, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), flags,
                )
                sessionId = id
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
        return id
    }

    companion object {
        const val TAG = "RisiMe"
    }
}

/** PackageInstaller callbacks (declared in the release-only manifest). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1).takeIf { it >= 0 }
        val app = context.applicationContext as? lk.codegen.risime.RisiMeApp ?: return
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Shown by the activity (background activity starts are blocked on Android 10+).
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            app.container.updater.onPendingUserAction(confirm, session)
            return
        }
        app.container.updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), session)
    }
}

/** ACTION_MY_PACKAGE_REPLACED (release-only manifest): "RisiMe updated to X — tap to open". */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? lk.codegen.risime.RisiMeApp ?: return
        val pending = goAsync()
        app.container.scope.launch {
            try {
                app.container.updater.onPackageReplaced()
            } finally {
                pending.finish()
            }
        }
    }
}
