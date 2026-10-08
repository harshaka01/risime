package lk.codegen.risime.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * P0-1 updater state machine, pure and JVM-tested: what a check, an installer status, a cold start
 * or an HTTP Range answer does to the update. The Android side is [Updater] / [UpdateWorker].
 */

/** PackageInstaller.STATUS_PENDING_USER_ACTION / STATUS_SUCCESS (literal so this stays JVM-pure). */
const val INSTALL_PENDING_USER_ACTION = -1
const val INSTALL_SUCCESS = 0

/** How far the last attempt got (persisted in [UpdateAttempt.step]). */
enum class UpdateStep { DOWNLOAD, VERIFY, INSTALL, FAILED, INSTALLED }

/**
 * The last update attempt, kept in DataStore so a failure survives the process (and a late
 * installer status still finds its release). [error] is the plain-words text shown on screen;
 * [status]/[statusMessage] are the installer's raw result (EXTRA_STATUS / EXTRA_STATUS_MESSAGE).
 */
@Serializable
data class UpdateAttempt(
    val versionCode: Long,
    val versionName: String,
    val step: UpdateStep,
    val status: Int? = null,
    val statusMessage: String? = null,
    val error: String? = null,
    val sessionId: Int? = null,
    val atMs: Long = 0,
    /** The app was in the foreground at commit: after the update it may reopen itself. */
    val wasForeground: Boolean = false,
) {
    fun encode(): String = attemptJson.encodeToString(serializer(), this)

    companion object {
        fun decode(s: String?): UpdateAttempt? = s?.let { runCatching { attemptJson.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

private val attemptJson = Json { ignoreUnknownKeys = true }

fun UpdateState.infoOrNull(): UpdateInfo? = when (this) {
    UpdateState.Idle -> null
    is UpdateState.Available -> info
    is UpdateState.Working -> info
    is UpdateState.NeedsPermission -> info
    is UpdateState.Failed -> info
}

/**
 * A finished check. A newer offer replaces whatever was shown; the same release keeps its running
 * download, its permission wait or its failure (a check must never hide an error or a progress bar).
 */
fun stateAfterCheck(current: UpdateState, decision: UpdateDecision): UpdateState {
    val offered = when (decision) {
        is UpdateDecision.Available -> decision.info
        is UpdateDecision.Required -> decision.info
        UpdateDecision.UpToDate -> return if (current is UpdateState.Working) current else UpdateState.Idle
        is UpdateDecision.Rejected -> return current
    }
    val same = current.infoOrNull()?.versionCode == offered.versionCode
    return when {
        same && current is UpdateState.Working -> current.copy(info = offered)
        same && current is UpdateState.NeedsPermission -> UpdateState.NeedsPermission(offered)
        same && current is UpdateState.Failed -> current.copy(info = offered)
        current is UpdateState.Working -> current // never interrupt a running update with another offer
        else -> UpdateState.Available(offered)
    }
}

/**
 * The release a (re)started download should fetch: the server's current offer when it is the same
 * release (republished: new hash/URL) or a newer one, else the one the user tapped.
 */
fun refreshTarget(pending: UpdateInfo, fresh: UpdateDecision?): UpdateInfo {
    val offered = when (fresh) {
        is UpdateDecision.Available -> fresh.info
        is UpdateDecision.Required -> fresh.info
        else -> return pending
    }
    return if (offered.versionCode >= pending.versionCode) offered else pending
}

/** An installer status, and whether it was ignored (a stale session's late answer). */
data class InstallStatusOutcome(val state: UpdateState, val ignored: Boolean = false)

/**
 * The installer's answer, whatever the UI state is now (P0-1 D: statuses that arrive after the
 * process restarted, or after the user dismissed the banner, are no longer dropped). [target] is the
 * persisted release of the attempt; a status for an older session while a newer one runs is ignored.
 */
fun stateAfterInstallStatus(
    current: UpdateState,
    target: UpdateInfo?,
    currentSessionId: Int?,
    statusSessionId: Int?,
    status: Int,
    message: String?,
): InstallStatusOutcome {
    if (current is UpdateState.Working && currentSessionId != null && statusSessionId != null && statusSessionId != currentSessionId) {
        return InstallStatusOutcome(current, ignored = true)
    }
    val info = current.infoOrNull()?.takeIf { target == null || it.versionCode == target.versionCode } ?: target
        ?: return InstallStatusOutcome(current, ignored = true)
    return when (status) {
        INSTALL_PENDING_USER_ACTION -> InstallStatusOutcome(UpdateState.Working(info, "Confirm the update…"))
        // The system replaces and restarts the app; until then nothing is left to do.
        INSTALL_SUCCESS -> InstallStatusOutcome(UpdateState.Idle)
        else -> InstallStatusOutcome(UpdateState.Failed(info, installFailureMessage(status, message)))
    }
}

/**
 * Cold start: a persisted failure for a release still newer than the app shows again (with its real
 * error); an attempt for a release that is now installed is finished. An attempt cut off mid-way is
 * shown by its worker when that resumes (or offered again by the next check).
 */
fun stateOnStart(attempt: UpdateAttempt?, pending: UpdateInfo?, installedVersionCode: Long): UpdateState {
    if (attempt == null || pending == null || attempt.versionCode <= installedVersionCode) return UpdateState.Idle
    if (pending.versionCode != attempt.versionCode) return UpdateState.Idle
    return when (attempt.step) {
        UpdateStep.FAILED -> UpdateState.Failed(pending, attempt.error ?: "The last update attempt failed.")
        else -> UpdateState.Idle
    }
}

/** What to do with an HTTP answer to a (possibly ranged) download request. */
sealed interface RangeOutcome {
    /** 206 for exactly the bytes asked for: append to the partial file. */
    data class Append(val from: Long, val total: Long?) : RangeOutcome

    /** A full body (200): write the file from the start. */
    data class Restart(val total: Long?) : RangeOutcome

    /** 416: the partial file already holds every byte (the hash then decides). */
    data object Complete : RangeOutcome

    /** A 206 for other bytes than asked for: drop the partial file and download it whole. */
    data object Mismatch : RangeOutcome

    data class Fail(val code: Int) : RangeOutcome
}

/** "bytes 100-199/1000" → (100, 1000); total null when "*". */
fun parseContentRange(header: String?): Pair<Long, Long?>? {
    val m = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""").matchEntire(header ?: return null) ?: return null
    return m.groupValues[1].toLong() to m.groupValues[3].toLongOrNull()
}

/**
 * [requestedFrom]: the partial file's length sent as `Range: bytes=N-` (0 = no Range header).
 * [contentLength]: the body's length (-1 unknown).
 */
fun rangeOutcome(requestedFrom: Long, code: Int, contentRange: String?, contentLength: Long): RangeOutcome {
    val body = contentLength.takeIf { it >= 0 }
    return when {
        code == 206 && requestedFrom > 0 -> {
            val (start, total) = parseContentRange(contentRange) ?: return RangeOutcome.Mismatch
            if (start == requestedFrom) RangeOutcome.Append(start, total ?: body?.let { it + start }) else RangeOutcome.Mismatch
        }
        code == 416 && requestedFrom > 0 -> RangeOutcome.Complete
        code in 200..299 && code != 206 -> RangeOutcome.Restart(body)
        else -> RangeOutcome.Fail(code)
    }
}
