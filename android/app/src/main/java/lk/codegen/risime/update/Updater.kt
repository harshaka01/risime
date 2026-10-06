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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import lk.codegen.risime.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState

    data class Available(val info: UpdateInfo) : UpdateState

    data class Working(val info: UpdateInfo, val step: String) : UpdateState

    /** "Install unknown apps" isn't granted yet; Settings was opened. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState

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

/** Release-only self-updater: check version.json, download, verify, install via PackageInstaller. */
class Updater(
    private val context: Context,
    private val http: OkHttpClient,
    private val baseUrl: String = BuildConfig.UPDATE_BASE_URL,
    val enabled: Boolean = BuildConfig.UPDATER_ENABLED,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastCheck: Long? = null

    /** A system confirmation to show (STATUS_PENDING_USER_ACTION); the activity launches it. */
    private val _confirm = MutableStateFlow<Intent?>(null)
    val confirm: StateFlow<Intent?> = _confirm.asStateFlow()

    fun confirmShown() {
        _confirm.value = null
    }

    fun onPendingUserAction(intent: Intent) {
        _confirm.value = intent
        val info = (_state.value as? UpdateState.Working)?.info ?: return
        _state.value = UpdateState.Working(info, "Confirm the update…")
    }

    /** Back in the foreground: continue an update that was waiting for "install unknown apps". */
    suspend fun onForeground() {
        val s = _state.value
        if (enabled && s is UpdateState.NeedsPermission && context.packageManager.canRequestPackageInstalls()) update(s.info)
    }

    /** Called on start and when foregrounded; honours the 6 h interval. */
    suspend fun maybeCheck(nowElapsedMs: Long) {
        if (!enabled || !shouldCheck(lastCheck, nowElapsedMs)) return
        if (_state.value is UpdateState.Working) return
        lastCheck = nowElapsedMs
        val text = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(baseUrl.trimEnd('/') + "/version.json").build()).execute().use { r ->
                    if (r.isSuccessful) r.body.string() else null
                }
            }.getOrNull()
        } ?: return
        when (val d = decide(VersionJson.parse(text), installedVersionCode(), baseUrl)) {
            is UpdateDecision.Available -> _state.value = UpdateState.Available(d.info)
            is UpdateDecision.Required -> _state.value = UpdateState.Available(d.info)
            UpdateDecision.UpToDate -> _state.value = UpdateState.Idle
            is UpdateDecision.Rejected -> Log.w("RisiMe", "update ignored: ${d.reason}")
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

    /** Download → verify → install. Any mismatch deletes the file and installs nothing. */
    suspend fun update(info: UpdateInfo) {
        if (!enabled) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "risime-${info.versionCode}.apk")
        try {
            _state.value = UpdateState.Working(info, "Downloading…")
            val sha = withContext(Dispatchers.IO) { download(info.url, file) }
            _state.value = UpdateState.Working(info, "Verifying…")
            val facts = withContext(Dispatchers.IO) { facts(file, sha) }
            apkTargetSdk = archiveTargetSdk(file)
            when (val v = verifyApk(info, facts, context.packageName)) {
                is VerifyResult.Failed -> {
                    file.delete()
                    _state.value = UpdateState.Failed(info, "Update rejected: ${v.reason}")
                    return
                }
                VerifyResult.Ok -> Unit
            }
            _state.value = UpdateState.Working(info, "Installing…")
            withContext(Dispatchers.IO) { install(file, requestSilentUpdate(Build.VERSION.SDK_INT, apkTargetSdk)) }
        } catch (e: Exception) {
            file.delete()
            _state.value = UpdateState.Failed(info, "Update failed: ${e.javaClass.simpleName}")
        }
    }

    /** Installer status from [InstallResultReceiver]. */
    fun onInstallStatus(status: Int, message: String?) {
        val info = (_state.value as? UpdateState.Working)?.info ?: return
        if (status != PackageInstaller.STATUS_SUCCESS) {
            _state.value = UpdateState.Failed(info, "Install didn't finish (${message ?: status})")
        }
        // On success the app is replaced and restarted by the system.
    }

    private fun download(url: String, out: File): String {
        if (!urlAllowed(url, baseUrl)) error("URL outside the update base")
        val md = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            r.body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        output.write(buf, 0, n)
                    }
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
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

    private var apkTargetSdk = 0

    private fun archiveTargetSdk(file: File): Int =
        context.packageManager.getPackageArchiveInfo(file.path, 0)?.applicationInfo?.targetSdkVersion ?: 0

    /**
     * [silent]: ask Android 12+ to skip the confirmation (decision 027): we update ourselves, hold
     * REQUEST_INSTALL_PACKAGES + UPDATE_PACKAGES_WITHOUT_USER_ACTION, and the APK's targetSdk is
     * recent enough. The system may still ask (STATUS_PENDING_USER_ACTION → confirm dialog).
     */
    private fun install(file: File, silent: Boolean) {
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
        installer.openSession(id).use { session ->
            session.openWrite("risime.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), flags,
            )
            session.commit(pending.intentSender)
        }
    }
}

/** PackageInstaller callbacks (declared in the release-only manifest). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val app = context.applicationContext as? lk.codegen.risime.RisiMeApp ?: return
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Shown by the activity (background activity starts are blocked on Android 10+).
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            app.container.updater.onPendingUserAction(confirm)
            return
        }
        app.container.updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
