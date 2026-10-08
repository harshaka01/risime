package lk.codegen.risime.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * In-app updater rules (decision 016). Pure and JVM-tested; the Android side is [Updater].
 */

/** The release signing certificate (decision 003), SHA-256 of the DER cert, lowercase hex. */
const val PINNED_CERT_SHA256 = "da7b982415e5d11dd90aee893716c7a04ed0841e21890ffcc8e075f416c99e2e"

/** One published release, as advertised by `version.json`. */
@kotlinx.serialization.Serializable
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val date: String?,
    /** Release notes as published (Markdown). Shown via [displayNotes], never raw. */
    val notes: String?,
    val url: String,
    val sha256: String,
    val certSha256: String,
    val required: Boolean,
    /** Optional short plain-text summary; preferred over [notes] when present. */
    val summary: String? = null,
)

/** What the updater UI shows as "what's new": [UpdateInfo.summary], else the notes as plain text. */
fun UpdateInfo.displayNotes(): String? =
    summary?.trim()?.takeIf { it.isNotEmpty() } ?: notes?.let(::markdownToPlainText)?.takeIf { it.isNotBlank() }

/** Fallback when the in-app download or install can't complete: the public download page. */
const val DOWNLOAD_PAGE_URL = "https://risicloud.ai/app/risime/"

/**
 * `version.json` parsing, with every field name in this one place. The format is a draft until
 * RisiWork's example arrives (decision 016); rename the constants here when it does.
 * Current publisher (scripts/publish-release):
 * {"versionCode","versionName","date","notes","url","sha256","certSha256","required"}, plus the
 * optional "summary" (short plain text; absent in older files).
 */
object VersionJson {
    const val VERSION_CODE = "versionCode"
    const val VERSION_NAME = "versionName"
    const val DATE = "date"
    const val NOTES = "notes"
    const val URL = "url"
    const val SHA256 = "sha256"
    const val CERT_SHA256 = "certSha256"
    const val REQUIRED = "required"
    const val SUMMARY = "summary"

    private val json = Json { ignoreUnknownKeys = true }

    /** Null if malformed or a mandatory field is missing (then: no update offered). */
    fun parse(text: String): UpdateInfo? = runCatching {
        val o: JsonObject = json.parseToJsonElement(text).jsonObject
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        UpdateInfo(
            versionCode = o[VERSION_CODE]?.jsonPrimitive?.longOrNull ?: return null,
            versionName = str(VERSION_NAME) ?: return null,
            date = str(DATE),
            notes = str(NOTES),
            url = str(URL) ?: return null,
            sha256 = normalizeHex(str(SHA256) ?: return null).takeIf { it.length == 64 } ?: return null,
            certSha256 = normalizeHex(str(CERT_SHA256) ?: return null).takeIf { it.length == 64 } ?: return null,
            required = o[REQUIRED]?.jsonPrimitive?.booleanOrNull ?: false,
            summary = str(SUMMARY),
        )
    }.getOrNull()
}

/** "DA:7B:…" / "da7b…" → "da7b…". */
fun normalizeHex(s: String): String = s.trim().replace(":", "").lowercase()

/** Only https URLs under the update base (same host, path prefix) are ever downloaded. */
fun urlAllowed(url: String, baseUrl: String): Boolean {
    val u = url.toHttpUrlOrNull() ?: return false
    val b = baseUrl.toHttpUrlOrNull() ?: return false
    // Release: the base is https, so only https is ever accepted. Only a test build whose base was
    // set (-Prisime.updateBaseUrl) to a plain-http loopback mirror accepts http, and only from there.
    if (u.scheme != b.scheme || (!u.isHttps && !isLoopbackMirror(b))) return false
    if (u.host != b.host || u.port != b.port) return false
    if (u.username.isNotEmpty() || u.password.isNotEmpty()) return false
    val prefix = b.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
    val path = u.encodedPath
    return path.startsWith(prefix) && path.length > prefix.length && !path.contains("/../") && !path.contains("%2e%2e", ignoreCase = true)
}

/** A local test mirror (adb reverse / emulator host): the only place plain http is ever allowed. */
private fun isLoopbackMirror(b: okhttp3.HttpUrl): Boolean = b.host == "127.0.0.1" || b.host == "10.0.2.2"

sealed interface UpdateDecision {
    data object UpToDate : UpdateDecision

    data class Available(val info: UpdateInfo) : UpdateDecision

    /** `required: true`: a blocking screen; chats are unreachable until it's installed. */
    data class Required(val info: UpdateInfo) : UpdateDecision

    data class Rejected(val reason: String) : UpdateDecision
}

fun decide(info: UpdateInfo?, installedVersionCode: Long, baseUrl: String, pinnedCert: String = PINNED_CERT_SHA256): UpdateDecision = when {
    info == null -> UpdateDecision.Rejected("version.json unreadable")
    info.versionCode <= installedVersionCode -> UpdateDecision.UpToDate
    !urlAllowed(info.url, baseUrl) -> UpdateDecision.Rejected("download URL outside ${baseUrl}")
    info.certSha256 != normalizeHex(pinnedCert) -> UpdateDecision.Rejected("advertised certificate isn't RisiMe's")
    info.required -> UpdateDecision.Required(info)
    else -> UpdateDecision.Available(info)
}

/** What the downloaded file turned out to be (PackageManager archive info + our hash). */
data class ApkFacts(
    val sha256: String,
    val packageName: String?,
    val versionCode: Long?,
    /** SHA-256 of each signing certificate (current signers). */
    val certSha256s: List<String>,
)

sealed interface VerifyResult {
    data object Ok : VerifyResult

    data class Failed(val reason: String) : VerifyResult
}

/** All must hold, else the file is deleted and nothing is installed (decision 016). */
fun verifyApk(
    info: UpdateInfo,
    facts: ApkFacts,
    ourPackage: String,
    pinnedCert: String = PINNED_CERT_SHA256,
): VerifyResult {
    val pinned = normalizeHex(pinnedCert)
    val certs = facts.certSha256s.map(::normalizeHex).toSet()
    return when {
        normalizeHex(facts.sha256) != info.sha256 -> VerifyResult.Failed("checksum mismatch")
        certs.isEmpty() -> VerifyResult.Failed("APK is not signed")
        certs != setOf(pinned) -> VerifyResult.Failed("APK is signed by another key")
        info.certSha256 != pinned -> VerifyResult.Failed("advertised certificate isn't RisiMe's")
        facts.packageName != ourPackage -> VerifyResult.Failed("APK is for another app (${facts.packageName})")
        facts.versionCode != info.versionCode -> VerifyResult.Failed("APK version ${facts.versionCode} ≠ advertised ${info.versionCode}")
        else -> VerifyResult.Ok
    }
}

/** Checks run on every foreground, at most every 15 min (P0-1); "Check for updates" forces one. */
const val UPDATE_CHECK_INTERVAL_MS = 15 * 60 * 1000L

fun shouldCheck(lastCheckElapsedMs: Long?, nowElapsedMs: Long, intervalMs: Long = UPDATE_CHECK_INTERVAL_MS, force: Boolean = false): Boolean =
    force || lastCheckElapsedMs == null || nowElapsedMs < lastCheckElapsedMs || nowElapsedMs - lastCheckElapsedMs >= intervalMs

/**
 * Android 12+ silent self-update (decision 027): user action can be skipped only when the APK
 * being installed targets at least this API level on the device's Android version
 * (PackageInstaller.SessionParams#setRequireUserAction docs). Null = not possible (pre-31).
 */
fun minTargetSdkForSilentUpdate(deviceSdk: Int): Int? = when {
    deviceSdk < 31 -> null
    deviceSdk < 33 -> 29 // S, S_V2
    deviceSdk < 34 -> 30 // T
    deviceSdk < 35 -> 31 // U
    deviceSdk < 36 -> 33 // V
    deviceSdk < 37 -> 34 // Baklava
    else -> 35 // 37+, and assumed for newer until the docs say otherwise (the system still decides)
}

/** Ask for USER_ACTION_NOT_REQUIRED? The system makes the final call; we always handle PENDING_USER_ACTION. */
fun requestSilentUpdate(deviceSdk: Int, apkTargetSdk: Int): Boolean =
    minTargetSdkForSilentUpdate(deviceSdk)?.let { apkTargetSdk >= it } ?: false

/** What the update banner shows (null: no banner; `required` uses the blocking screen instead). */
data class UpdateBanner(
    val title: String,
    /** [displayNotes] (summary, or notes as plain text), shown expandable/scrollable. */
    val notes: String?,
    val status: String?,
    /** The single action ("Update", "Retry"); null while working. */
    val action: String?,
    val canDismiss: Boolean,
    val busy: Boolean,
    /** Download progress (0..100) while known. */
    val percent: Int? = null,
    /** [status] is an error (shown in the error colour). */
    val error: Boolean = false,
)

fun updateBanner(state: UpdateState): UpdateBanner? {
    if (state.blocking() != null) return null
    return when (state) {
        UpdateState.Idle -> null
        is UpdateState.Available -> UpdateBanner("RisiMe ${state.info.versionName} is available", state.info.displayNotes(), null, "Update", canDismiss = true, busy = false)
        is UpdateState.Working -> UpdateBanner(
            "Updating to ${state.info.versionName}", state.info.displayNotes(), state.step, null,
            canDismiss = false, busy = true, percent = state.percent,
        )
        is UpdateState.NeedsPermission -> UpdateBanner("RisiMe ${state.info.versionName} is available", state.info.displayNotes(),
            "Allow RisiMe to install updates; the update continues when you come back.", "Update", canDismiss = true, busy = false)
        is UpdateState.Failed -> UpdateBanner(
            "RisiMe ${state.info.versionName} wasn't installed", state.info.displayNotes(), state.message, "Retry",
            canDismiss = true, busy = false, error = true,
        )
    }
}

/**
 * P0-1 (E): testers uninstalled to update and lost their chats. Every update surface (banner, gate,
 * failure, About) says this.
 */
const val NEVER_UNINSTALL_TEXT = "Install over the old app — never uninstall: uninstalling deletes your chats."

/** The fallback on every update surface: opens [DOWNLOAD_PAGE_URL]. */
const val DOWNLOAD_FROM_WEBSITE = "Download from website"

/** "Downloading… 42%" (no percentage while the size is unknown). */
fun downloadStep(percent: Int?): String = if (percent == null) "Downloading…" else "Downloading… $percent%"

/** 0..100, or null while the total is unknown. */
fun downloadPercent(done: Long, total: Long?): Int? =
    if (total == null || total <= 0) null else ((done.coerceIn(0, total) * 100) / total).toInt()

/** A non-2xx answer from the update server; the message keeps the code ("HTTP 404"). */
class UpdateHttpException(val code: Int) : java.io.IOException("HTTP $code")

/** A download/verify failure as the user should read it: the real cause, in plain words. */
fun downloadFailureMessage(e: Throwable): String = when (e) {
    is UpdateHttpException -> "Couldn't download the update: the update server answered HTTP ${e.code}" +
        (if (e.code == 404) " (the file isn't there any more; check for updates again)." else ".")
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException,
    is java.io.InterruptedIOException,
    -> "Couldn't download the update: no connection to the update server. Check your internet and retry."
    is javax.net.ssl.SSLException -> "Couldn't download the update: secure connection failed (certificate problem)."
    is java.io.IOException -> "Couldn't download the update (network error: ${e.message ?: e.javaClass.simpleName})."
    else -> "Update failed (${e.message ?: e.javaClass.simpleName})."
}

/** A rejected download (checksum, signing certificate, package or version mismatch). Nothing was installed. */
fun verifyFailureMessage(reason: String): String =
    "The downloaded update failed its security check ($reason), so nothing was installed."

/**
 * PackageInstaller status → "App not installed: …" with the installer's own message
 * (EXTRA_STATUS_MESSAGE). Codes are PackageInstaller.STATUS_* (literal so this stays JVM-pure):
 * 1 FAILURE, 2 BLOCKED, 3 ABORTED, 4 INVALID, 5 CONFLICT, 6 STORAGE, 7 INCOMPATIBLE, 8 TIMEOUT.
 */
fun installFailureMessage(status: Int, detail: String?): String {
    val what = when (status) {
        3 -> return "Install cancelled. Tap Retry to install the update."
        2 -> "the install was blocked on this device"
        4 -> "the downloaded file isn't a valid app"
        5 -> "it conflicts with the installed app"
        6 -> "not enough storage"
        7 -> "it isn't compatible with this device"
        8 -> "the install timed out"
        else -> "the installer reported a failure (status $status)"
    }
    val extra = detail?.trim()?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""
    return "App not installed: $what$extra."
}
