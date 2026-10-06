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
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val date: String?,
    val notes: String?,
    val url: String,
    val sha256: String,
    val certSha256: String,
    val required: Boolean,
)

/**
 * `version.json` parsing, with every field name in this one place. The format is a draft until
 * RisiWork's example arrives (decision 016); rename the constants here when it does.
 * Current publisher (scripts/publish-release):
 * {"versionCode","versionName","date","notes","url","sha256","certSha256","required"}.
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
        )
    }.getOrNull()
}

/** "DA:7B:…" / "da7b…" → "da7b…". */
fun normalizeHex(s: String): String = s.trim().replace(":", "").lowercase()

/** Only https URLs under the update base (same host, path prefix) are ever downloaded. */
fun urlAllowed(url: String, baseUrl: String): Boolean {
    val u = url.toHttpUrlOrNull() ?: return false
    val b = baseUrl.toHttpUrlOrNull() ?: return false
    if (!u.isHttps || u.host != b.host || u.port != b.port) return false
    if (u.username.isNotEmpty() || u.password.isNotEmpty()) return false
    val prefix = b.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
    val path = u.encodedPath
    return path.startsWith(prefix) && path.length > prefix.length && !path.contains("/../") && !path.contains("%2e%2e", ignoreCase = true)
}

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

/** At start, then at most every [intervalMs] while in the foreground. */
fun shouldCheck(lastCheckElapsedMs: Long?, nowElapsedMs: Long, intervalMs: Long = 6 * 60 * 60 * 1000L): Boolean =
    lastCheckElapsedMs == null || nowElapsedMs - lastCheckElapsedMs >= intervalMs
