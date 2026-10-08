package lk.codegen.risime.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.Backup
import lk.codegen.risime.net.BackupCreateRequest
import lk.codegen.risime.net.BackupErrors
import lk.codegen.risime.net.BackupPartRef
import lk.codegen.risime.net.BackupsReply
import lk.codegen.risime.net.ProtocolJson
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Small persisted backup settings and state (app-private; wiped with the chats). */
interface BackupPrefs {
    fun get(key: String): String?

    fun set(key: String, value: String?)

    fun clear()
}

/** The §22.3 server calls (ApiClient in the app, MockWebServer in tests). Writing calls carry X-Device-Id. */
interface BackupServer {
    /** `GET /auth/config` says `backup: on`. */
    suspend fun switchOn(): Boolean

    suspend fun backups(): ApiResult<BackupsReply>

    /** The record JSON, or ApiResult.Error 404 no_backup_key. */
    suspend fun backupKey(): ApiResult<String>

    suspend fun putBackupKey(recordJson: String): ApiResult<String>

    suspend fun uploadPart(backupId: String, clientBlobId: String, file: File, offset: Long, length: Long): ApiResult<lk.codegen.risime.net.BlobUploadReply>

    suspend fun commit(body: BackupCreateRequest): ApiResult<Backup>

    /** Writes the blob into [into] (truncating it). */
    suspend fun download(blobId: String, into: File): ApiResult<Long>

    suspend fun deleteAll(): ApiResult<Unit>
}

/** Why a backup is made (the file name; the pre-update one is kept apart, §22.7). */
enum class BackupReason(val tag: String) { DAILY("daily"), MANUAL("manual"), PRE_UPDATE("preupdate"), PRE_WIPE("prewipe"), EXPORT("export") }

@Serializable
data class BackupRecord(val at: Long, val size: Long, val id: String, val file: String? = null)

/** What the Settings → Backups screen shows. */
data class BackupStatus(
    val available: Boolean = false,
    val lastLocal: BackupRecord? = null,
    val lastServer: BackupRecord? = null,
    val serverOn: Boolean = false,
    val mobileData: Boolean = false,
    val keySetUp: Boolean = false,
    val running: String? = null,
    val message: String? = null,
)

/** The §22.7 first-sign-in restore gate. */
sealed interface RestoreGate {
    /** Not a fresh install, or already decided: server backups may be made. */
    data object Open : RestoreGate

    /** A fresh install that hasn't looked at the server yet (or couldn't). No server backup meanwhile. */
    data object Checking : RestoreGate

    /** The server has backups: offer "Restore your chats". */
    data class Offer(val backups: List<Backup>) : RestoreGate
}

sealed interface RestoreSource {
    data class LocalFile(val file: File) : RestoreSource

    data class Server(val backup: Backup) : RestoreSource
}

sealed interface RestoreOutcome {
    data class Done(val result: RestoreResult) : RestoreOutcome

    /** The file's key isn't on this phone: ask for the recovery key (or passphrase). */
    data object NeedsSecret : RestoreOutcome

    data class Failed(val message: String) : RestoreOutcome
}

sealed interface UploadOutcome {
    data class Done(val backup: Backup) : UploadOutcome

    /** §22.3 another phone is the backup device: ask "Back up this phone instead of <name>?". */
    data class OtherDevice(val deviceName: String) : UploadOutcome

    /** The account's key record has another `bk_id`: unlock it with the recovery key or passphrase first. */
    data object NeedsUnlock : UploadOutcome

    data class Skipped(val why: String) : UploadOutcome

    data class Failed(val message: String) : UploadOutcome
}

sealed interface ServerSetup {
    /** A new key: show the recovery key once (then confirm and PUT). */
    data class ShowKey(val recoveryKey: String) : ServerSetup

    /** A record exists on the server with another key: unlock it. */
    data object Unlock : ServerSetup

    data class Failed(val message: String) : ServerSetup
}

/**
 * §22 on this phone: local backups (daily, before every in-app update, before a confirmed wipe),
 * export and restore of files, server backups (parts, commit, the key record) and the
 * first-sign-in restore gate. One operation at a time ([lock]); every core call off the main thread.
 */
class BackupManager(
    private val dir: File,
    private val work: File,
    private val prefs: BackupPrefs,
    private val keys: () -> BackupKeys?,
    private val tools: () -> BackupTools?,
    private val exporter: (me: String) -> BundleExporter,
    private val importer: (me: String, progress: RestoreProgress) -> BundleImporter,
    private val server: BackupServer?,
    private val me: suspend () -> String?,
    private val appVersion: String,
    /** Messages on this phone (the gate: a fresh install has none). */
    private val localMessages: suspend () -> Int = { 0 },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val partSize: Long = PART_SIZE,
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()
    private val _gate = MutableStateFlow<RestoreGate>(if (prefs.get(K_GATE) == GATE_PENDING) RestoreGate.Checking else RestoreGate.Open)
    val gate: StateFlow<RestoreGate> = _gate.asStateFlow()

    val serverOn: Boolean get() = prefs.get(K_SERVER_ON) == "1"
    val mobileData: Boolean get() = prefs.get(K_MOBILE) == "1"

    private fun record(k: String): BackupRecord? = prefs.get(k)?.let { runCatching { ProtocolJson.decodeFromString(BackupRecord.serializer(), it) }.getOrNull() }

    private fun putRecord(k: String, r: BackupRecord?) = prefs.set(k, r?.let { ProtocolJson.encodeToString(BackupRecord.serializer(), it) })

    /** Re-reads the persisted state into [status]. */
    fun refresh(message: String? = _status.value.message) {
        _status.value = _status.value.copy(
            available = keys() != null,
            lastLocal = record(K_LAST_LOCAL),
            lastServer = record(K_LAST_SERVER),
            serverOn = serverOn,
            mobileData = mobileData,
            keySetUp = prefs.get(K_KEY_SHOWN) == "1",
            message = message,
        )
    }

    private inline fun <T> running(text: String, block: () -> T): T {
        _status.value = _status.value.copy(running = text, message = null)
        try {
            return block()
        } finally {
            _status.value = _status.value.copy(running = null)
        }
    }

    // ---- files ----

    /** The local backups, newest first (`<ms>-<reason>.risimebk`). */
    fun localFiles(): List<File> = (dir.listFiles { f -> f.name.endsWith(EXT) } ?: emptyArray()).sortedByDescending { it.name.substringBefore('-').toLongOrNull() ?: 0 }

    /**
     * §22.7 retention: the newest 2 plus the latest pre-update one (never the file just written).
     * Older local keys no file needs any more are dropped.
     */
    private fun prune(keep: File) {
        val files = localFiles()
        val preUpdate = files.firstOrNull { it.name.contains("-${BackupReason.PRE_UPDATE.tag}") }
        val keepSet = (files.filter { it.name.endsWith(EXT) }.take(2) + listOfNotNull(preUpdate, keep)).toSet()
        files.filter { it !in keepSet }.forEach { it.delete() }
        val k = keys() ?: return
        val t = tools() ?: return
        val used = localFiles().mapNotNull { runCatching { t.fileInfo(it).bkId }.getOrNull() }.toSet()
        runCatching { k.keyIds().old.filter { it !in used }.forEach { k.dropKey(it) } }
    }

    /**
     * Writes one backup file (§22.4) into the local backup directory: the bundle streamed through
     * DEFLATE into the core writer. Without a `BK` the core makes the silent local pair first.
     * A failure leaves no file (a retry is a new backup id).
     */
    suspend fun writeLocal(reason: BackupReason, keyRecord: String? = null): File = withContext(Dispatchers.IO) {
        val k = keys() ?: throw BackupException(BackupException.Kind.Storage, "backups need the encryption core")
        val user = me() ?: throw BackupException(BackupException.Kind.Storage, "not signed in")
        if (k.keyIds().current == null) k.setup(user) // §22.7: the silent local pair (the key is shown later)
        dir.mkdirs()
        val backupId = UUID.randomUUID().toString()
        val now = clock()
        val createdAt = BackupTime.iso(now)
        val ex = exporter(user)
        val counts = ex.write { } // pass 1: the header's counts (nothing kept)
        val out = File(dir, "$now-${reason.tag}$EXT")
        val w = k.writer(user, backupId, createdAt, appVersion, keyRecord, out)
        val sink = DeflatingSink { w.write(it) }
        try {
            sink.write(encodeLine(BackupBundleHeader.serializer(), BackupBundleHeader(backupId = backupId, userId = user.lowercase(), createdAt = createdAt, appVersion = appVersion, counts = counts)))
            ex.write { sink.write(it) }
            sink.finish()
            val info = w.finish()
            putRecord(K_LAST_LOCAL, BackupRecord(now, info.size, info.backupId, out.name))
            log("backup ${reason.tag}: ${counts.conversations} chats, ${counts.messages} messages, ${counts.tombstones} tombstones, ${info.size} bytes")
            prune(out)
            out
        } catch (t: Throwable) {
            sink.abort()
            out.delete()
            throw t
        }
    }

    /** "Back up now" / the daily job: a local backup, then the upload when server backup is on. */
    suspend fun backupNow(reason: BackupReason = BackupReason.MANUAL, upload: Boolean = true, replaceDevice: Boolean = false): UploadOutcome = lock.withLock {
        running("Backing up…") {
            val f = try {
                writeLocal(reason)
            } catch (e: Exception) {
                log("backup failed: ${e.message}")
                refresh("Backup failed: ${describe(e)}")
                return UploadOutcome.Failed("Backup failed: ${describe(e)}")
            }
            val r = if (upload) uploadLocked(f, replaceDevice) else UploadOutcome.Skipped("local only")
            refresh(
                when (r) {
                    is UploadOutcome.Done -> "Backed up"
                    is UploadOutcome.Failed -> r.message
                    else -> "Backed up on this phone"
                },
            )
            r
        }
    }

    /**
     * §22.7 before an in-app update: a local backup must finish, and with server backup on the
     * upload (waiting up to 2 minutes). Throws when the local backup failed (the caller says
     * "Backup failed — don't uninstall" and still updates).
     */
    suspend fun beforeUpdate() {
        val r = kotlinx.coroutines.withTimeoutOrNull(UPDATE_UPLOAD_WAIT_MS + 60_000) {
            lock.withLock {
                val f = writeLocal(BackupReason.PRE_UPDATE)
                if (serverAllowed()) kotlinx.coroutines.withTimeoutOrNull(UPDATE_UPLOAD_WAIT_MS) { uploadLocked(f, false) } ?: UploadOutcome.Skipped("upload timed out")
                else UploadOutcome.Skipped("local only")
            }
        }
        log("before update: ${r ?: "timed out"}")
        refresh()
    }

    /** §22.7 before a confirmed wipe: a backup, uploaded when server backup is on. Returns whether it reached the server. */
    suspend fun beforeWipe(): Boolean = lock.withLock {
        val f = runCatching { writeLocal(BackupReason.PRE_WIPE) }.onFailure { log("pre-wipe backup failed: ${it.message}") }.getOrNull() ?: return false
        if (!serverAllowed()) return false
        val r = kotlinx.coroutines.withTimeoutOrNull(UPDATE_UPLOAD_WAIT_MS) { uploadLocked(f, false) }
        log("before wipe: $r")
        r is UploadOutcome.Done
    }

    /** A confirmed wipe: the app-private backups and every backup setting go with the chats (`BK` goes with `mls_kv`). */
    fun wipeLocal() {
        dir.deleteRecursively()
        work.deleteRecursively()
        prefs.clear()
        _gate.value = RestoreGate.Open
        refresh(null)
    }

    /** "Export backup file": a fresh backup, copied to [out] (MediaStore Downloads or the picked document). */
    suspend fun export(out: OutputStream): Boolean = lock.withLock {
        running("Preparing the backup file…") {
            val f = runCatching { writeLocal(BackupReason.EXPORT) }.getOrElse {
                refresh("Export failed: ${describe(it)}")
                return false
            }
            withContext(Dispatchers.IO) { out.use { o -> f.inputStream().use { it.copyTo(o) } } }
            refresh("Backup file saved")
            true
        }
    }

    // ---- server ----

    private suspend fun serverAllowed(): Boolean =
        server != null && serverOn && _gate.value == RestoreGate.Open && runCatching { server!!.switchOn() }.getOrDefault(false)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun sha256(file: File, offset: Long, length: Long): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                md.update(buf, 0, n)
                left -= n
            }
        }
        return md.digest()
    }

    private fun bkIdOf(record: String): String? = runCatching { (ProtocolJson.parseToJsonElement(record) as JsonObject).str("bk_id") }.getOrNull()

    /**
     * The account's key record for uploads: published from this phone when the server has none;
     * null when the server's record has another `bk_id` (unlock it first).
     */
    private suspend fun accountRecord(k: BackupKeys, user: String): String? {
        val s = server ?: return null
        val current = k.keyIds().current ?: run { k.setup(user); k.keyIds().current }
        return when (val r = s.backupKey()) {
            is ApiResult.Ok -> if (bkIdOf(r.value) == current) r.value else null
            is ApiResult.Error -> if (r.httpStatus == 404) {
                val mine = k.keyRecord() ?: k.setup(user).keyRecord
                when (val p = s.putBackupKey(mine)) {
                    is ApiResult.Ok -> p.value
                    else -> null
                }
            } else {
                throw BackupException(BackupException.Kind.Io, "backup key: ${r.code}")
            }
            is ApiResult.NetworkError -> throw BackupException(BackupException.Kind.Io, "offline")
        }
    }

    /** Uploads [file] (a local backup) as 1–16 parts and commits it (§22.3). */
    suspend fun upload(file: File, replaceDevice: Boolean = false): UploadOutcome = lock.withLock { uploadLocked(file, replaceDevice) }

    private suspend fun uploadLocked(file0: File, replaceDevice: Boolean): UploadOutcome = withContext(Dispatchers.IO) {
        val s = server ?: return@withContext UploadOutcome.Skipped("no server")
        if (!serverOn) return@withContext UploadOutcome.Skipped("server backup off")
        if (_gate.value != RestoreGate.Open) return@withContext UploadOutcome.Skipped("restore or skip first")
        if (!runCatching { s.switchOn() }.getOrDefault(false)) return@withContext UploadOutcome.Skipped("server backups are turned off")
        val k = keys() ?: return@withContext UploadOutcome.Skipped("no encryption core")
        val t = tools() ?: return@withContext UploadOutcome.Skipped("no encryption core")
        val user = me() ?: return@withContext UploadOutcome.Skipped("not signed in")
        try {
            val record = accountRecord(k, user) ?: return@withContext UploadOutcome.NeedsUnlock
            // The file must be under the account key with the server's record in its header.
            var file = file0
            if (t.fileInfo(file).bkId != bkIdOf(record)) file = writeLocal(BackupReason.MANUAL, record)
            val info = t.fileInfo(file)
            val size = file.length()
            if (size > MAX_BACKUP) return@withContext UploadOutcome.Failed("Your backup is larger than 512 MB, the server's limit")
            val parts = mutableListOf<BackupPartRef>()
            var off = 0L
            while (off < size) {
                val len = minOf(partSize, size - off)
                val sha = b64(sha256(file, off, len))
                val r = s.uploadPart(info.backupId, UUID.randomUUID().toString(), file, off, len)
                when (r) {
                    is ApiResult.Ok -> if (r.value.size != len || r.value.sha256 != sha) return@withContext UploadOutcome.Failed("The server stored a different part")
                    else -> return@withContext UploadOutcome.Failed(serverError(r))
                }
                parts += BackupPartRef(r.value.blobId, len, sha)
                off += len
            }
            val body = BackupCreateRequest(
                backupId = info.backupId, createdAt = info.createdAt, size = size, sha256 = b64(sha256(file, 0, size)), schema = BUNDLE_SCHEMA,
                appVersion = info.appVersion, bkId = info.bkId, parts = parts,
                replaceDevice = replaceDevice || prefs.get(K_REPLACE_OK) == "1",
            )
            when (val c = s.commit(body)) {
                is ApiResult.Ok -> {
                    prefs.set(K_REPLACE_OK, null)
                    putRecord(K_LAST_SERVER, BackupRecord(clock(), size, info.backupId))
                    UploadOutcome.Done(c.value)
                }
                is ApiResult.Error -> when (c.code) {
                    BackupErrors.DEVICE_MISMATCH -> UploadOutcome.OtherDevice(c.detail?.deviceName ?: "another phone")
                    BackupErrors.KEY_CONFLICT -> UploadOutcome.NeedsUnlock
                    else -> UploadOutcome.Failed(serverError(c))
                }
                is ApiResult.NetworkError -> UploadOutcome.Failed("Couldn't reach the server")
            }
        } catch (e: BackupException) {
            UploadOutcome.Failed(describe(e))
        }
    }

    private fun serverError(r: ApiResult<*>): String = when (r) {
        is ApiResult.Error -> when {
            r.code == BackupErrors.UNAVAILABLE -> "Server backups are turned off"
            r.code == "quota_exceeded" -> "Not enough backup space on the server"
            r.code == "storage_full" -> "The server is out of space"
            r.httpStatus == 429 -> "Too many backups today — try again later"
            r.httpStatus == 413 -> "This backup is too large for the server"
            else -> "The server refused the backup (${r.code})"
        }
        is ApiResult.NetworkError -> "Couldn't reach the server"
        else -> "Unexpected answer"
    }

    /**
     * Turning server backup on (§22.7): with no record on the server, the recovery key to show (the
     * stored pair, or a new one); with a record of another key, [ServerSetup.Unlock].
     */
    suspend fun startServerSetup(): ServerSetup = withContext(Dispatchers.IO) {
        val s = server ?: return@withContext ServerSetup.Failed("No server")
        val k = keys() ?: return@withContext ServerSetup.Failed("Setting up encryption on this phone… try again in a moment.")
        val user = me() ?: return@withContext ServerSetup.Failed("Sign in first")
        if (!runCatching { s.switchOn() }.getOrDefault(false)) return@withContext ServerSetup.Failed("Server backups are turned off on this server")
        when (val r = s.backupKey()) {
            is ApiResult.Ok -> {
                if (bkIdOf(r.value) == k.keyIds().current) {
                    ServerSetup.ShowKey(k.recoveryKey() ?: k.setup(user).recoveryKey)
                } else {
                    ServerSetup.Unlock
                }
            }
            is ApiResult.Error -> if (r.httpStatus == 404) ServerSetup.ShowKey(k.setup(user).recoveryKey) else ServerSetup.Failed(serverError(r))
            is ApiResult.NetworkError -> ServerSetup.Failed("Couldn't reach the server")
        }
    }

    /** After "I saved it": publish the record (with the optional passphrase), turn on, first backup. */
    suspend fun finishServerSetup(passphrase: String?): UploadOutcome {
        val s = server ?: return UploadOutcome.Skipped("no server")
        val k = keys() ?: return UploadOutcome.Failed("No encryption core")
        val user = me() ?: return UploadOutcome.Failed("Sign in first")
        val record = withContext(Dispatchers.IO) {
            try {
                if (!passphrase.isNullOrEmpty()) k.addPassphrase(user, passphrase) else k.keyRecord() ?: k.setup(user).keyRecord
            } catch (e: BackupException) {
                return@withContext null.also { refresh(describe(e)) }
            }
        } ?: return UploadOutcome.Failed(_status.value.message ?: "Couldn't set up the key")
        when (val p = s.putBackupKey(record)) {
            is ApiResult.Ok -> Unit
            is ApiResult.Error -> return if (p.code == BackupErrors.KEY_CONFLICT) UploadOutcome.NeedsUnlock else UploadOutcome.Failed(serverError(p))
            is ApiResult.NetworkError -> return UploadOutcome.Failed("Couldn't reach the server")
        }
        prefs.set(K_SERVER_ON, "1")
        prefs.set(K_KEY_SHOWN, "1")
        refresh()
        return backupNow(BackupReason.MANUAL)
    }

    /** The account's record exists with another key: unlock it (Argon2id, off the main thread) and make it current. */
    suspend fun unlockAccountKey(secret: String, kind: SecretKind): String? = withContext(Dispatchers.IO) {
        val s = server ?: return@withContext "No server"
        val k = keys() ?: return@withContext "No encryption core"
        val user = me() ?: return@withContext "Sign in first"
        val record = when (val r = s.backupKey()) {
            is ApiResult.Ok -> r.value
            else -> return@withContext serverError(r)
        }
        try {
            k.unlock(user, record, secret, kind, makeCurrent = true)
            prefs.set(K_SERVER_ON, "1")
            prefs.set(K_KEY_SHOWN, "1")
            refresh()
            null
        } catch (e: BackupException) {
            describe(e)
        }
    }

    /** "Change recovery key": a new `R`, the same `BK`, `PUT`. Older files still open with the old key. */
    suspend fun changeRecoveryKey(): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val k = keys() ?: return@withContext null to "No encryption core"
        val user = me() ?: return@withContext null to "Sign in first"
        try {
            val r = k.rotateRecoveryKey(user)
            if (serverOn) server?.putBackupKey(r.keyRecord)?.let { if (it !is ApiResult.Ok) return@withContext r.recoveryKey to serverError(it) }
            r.recoveryKey to null
        } catch (e: BackupException) {
            null to describe(e)
        }
    }

    suspend fun recoveryKey(): String? = withContext(Dispatchers.IO) {
        val k = keys() ?: return@withContext null
        val user = me() ?: return@withContext null
        runCatching { k.recoveryKey() ?: k.setup(user).recoveryKey }.getOrNull()
    }

    fun markKeyShown() {
        prefs.set(K_KEY_SHOWN, "1")
        refresh()
    }

    fun setMobileData(on: Boolean) {
        prefs.set(K_MOBILE, if (on) "1" else null)
        refresh()
    }

    /** §22.3 "Back up this phone instead of <device name>?" → yes. */
    suspend fun replaceDevice(): UploadOutcome {
        prefs.set(K_REPLACE_OK, "1")
        return backupNow(BackupReason.MANUAL)
    }

    /** "Turn off server backup" (optionally deleting the server's backups and key record). */
    suspend fun turnOffServer(delete: Boolean): String? {
        prefs.set(K_SERVER_ON, null)
        refresh()
        if (!delete) return null
        val r = server?.deleteAll() ?: return null
        if (r !is ApiResult.Ok) return serverError(r)
        putRecord(K_LAST_SERVER, null)
        refresh("Server backups deleted")
        return null
    }

    /** "Reset backup key" (forgotten recovery key): `DELETE /backups`, forget every local key; turn on again afterwards. */
    suspend fun resetBackupKey(): String? = withContext(Dispatchers.IO) {
        server?.deleteAll()?.let { if (it !is ApiResult.Ok) return@withContext serverError(it) }
        runCatching { keys()?.forget() }
        prefs.set(K_SERVER_ON, null)
        prefs.set(K_KEY_SHOWN, null)
        putRecord(K_LAST_SERVER, null)
        refresh("Backup key reset. Local backup files made before need the old key.")
        null
    }

    // ---- restore ----

    /** A picked file is copied into app-private storage before the verify pass (§22.7). */
    suspend fun stageFile(input: InputStream): File = withContext(Dispatchers.IO) {
        work.mkdirs()
        val f = File(work, "picked-${clock()}$EXT")
        input.use { i -> f.outputStream().use { i.copyTo(it) } }
        f
    }

    /** Downloads a server backup's parts in order into one file and checks every size and digest. */
    private suspend fun download(b: Backup): File {
        val s = server ?: throw BackupException(BackupException.Kind.Io, "no server")
        work.mkdirs()
        val out = File(work, "${b.backupId}$EXT")
        if (out.isFile && out.length() == b.size && b64(sha256(out, 0, out.length())) == b.sha256) return out
        out.delete()
        val part = File(work, "${b.backupId}.part")
        for (p in b.parts) {
            when (val r = s.download(p.blobId, part)) {
                is ApiResult.Ok -> Unit
                is ApiResult.Error -> throw BackupException(BackupException.Kind.Io, if (r.httpStatus == 404) "That backup is no longer on the server" else "download: ${r.code}")
                is ApiResult.NetworkError -> throw BackupException(BackupException.Kind.Io, "Couldn't reach the server")
            }
            if (part.length() != p.size || b64(sha256(part, 0, part.length())) != p.sha256) throw BackupException(BackupException.Kind.Integrity, "a part changed on the server")
            java.io.FileOutputStream(out, true).use { o -> part.inputStream().use { it.copyTo(o) } }
        }
        part.delete()
        if (out.length() != b.size || b64(sha256(out, 0, out.length())) != b.sha256) {
            out.delete()
            throw BackupException(BackupException.Kind.Integrity, "the backup changed on the server")
        }
        return out
    }

    /**
     * §22.4/§22.6 restore: open (the local `BK`, else the server's record or the file header's,
     * unlocked with [secret]), verify every chunk, then import (insert-only, resumable).
     */
    suspend fun restore(source: RestoreSource, secret: String? = null, kind: SecretKind = SecretKind.RecoveryKey, afterBatch: (Int) -> Unit = {}): RestoreOutcome = lock.withLock {
        running("Restoring your chats…") {
            withContext(Dispatchers.IO) {
                val k = keys() ?: return@withContext RestoreOutcome.Failed("Setting up encryption on this phone… try again in a moment.")
                val t = tools() ?: return@withContext RestoreOutcome.Failed("This build can't open backups")
                val user = me() ?: return@withContext RestoreOutcome.Failed("Sign in first")
                try {
                    val (file, expectedId, expectedBk) = when (source) {
                        is RestoreSource.LocalFile -> Triple(source.file, null, null)
                        is RestoreSource.Server -> Triple(download(source.backup), source.backup.backupId, source.backup.bkId)
                    }
                    val reader = try {
                        k.reader(user, file, expectedId, expectedBk)
                    } catch (e: BackupException) {
                        if (e.kind != BackupException.Kind.NoKey) throw e
                        if (secret.isNullOrBlank()) return@withContext RestoreOutcome.NeedsSecret
                        val fileBk = t.fileInfo(file).bkId
                        val serverRecord = (server?.let { runCatching { it.backupKey() }.getOrNull() } as? ApiResult.Ok)?.value
                        val serverBk = serverRecord?.let(::bkIdOf)
                        val record = if (serverBk == fileBk) serverRecord else t.fileInfo(file).keyRecord
                        if (record == null) return@withContext RestoreOutcome.Failed("This backup has no key record: it can only be opened on the phone that made it")
                        // The account key when it is the server's (or there is none yet); else kept as an older key.
                        val makeCurrent = serverBk == fileBk || (serverBk == null && k.keyIds().current == null)
                        k.unlock(user, record, secret.trim(), kind, makeCurrent)
                        k.reader(user, file, expectedId, expectedBk)
                    }
                    reader.verify()
                    val result = importer(user, progressStore()).import(bundleLines(PiecesInputStream { reader.read() }), afterBatch)
                    if (source is RestoreSource.Server) {
                        prefs.set(K_REPLACE_OK, "1") // "after restoring on this phone" (§22.3)
                        // The account's key is this phone's now: server backup stays on, as it was on the old phone.
                        if (k.keyIds().current == source.backup.bkId) {
                            prefs.set(K_SERVER_ON, "1")
                            prefs.set(K_KEY_SHOWN, "1")
                        }
                        file.delete()
                    } else if (file.parentFile == work) {
                        file.delete()
                    }
                    openGate()
                    refresh("Restored ${result.imported} messages")
                    RestoreOutcome.Done(result)
                } catch (e: BackupException) {
                    log("restore failed: ${e.kind} ${e.message}")
                    RestoreOutcome.Failed(describe(e))
                } catch (e: BundleRejected) {
                    RestoreOutcome.Failed(if (e.reason == "another account") "This backup belongs to another account" else "This backup file can't be read (${e.reason})")
                } catch (e: java.util.zip.ZipException) {
                    RestoreOutcome.Failed("This backup file is damaged")
                }
            }
        }
    }

    private fun progressStore() = object : RestoreProgress {
        override fun done(backupId: String): Int = prefs.get(K_PROGRESS)?.split(':')?.takeIf { it.size == 2 && it[0] == backupId }?.get(1)?.toIntOrNull() ?: 0

        override fun save(backupId: String, lines: Int) = prefs.set(K_PROGRESS, "$backupId:$lines")

        override fun clear() = prefs.set(K_PROGRESS, null)
    }

    // ---- the first-sign-in gate (§22.7) ----

    /** A sign-in is being saved: a phone with no chats is a fresh install and gets the restore gate. */
    suspend fun onSignIn() {
        val fresh = runCatching { localMessages() }.getOrDefault(1) == 0
        prefs.set(K_GATE, if (fresh) GATE_PENDING else GATE_DONE)
        _gate.value = if (fresh) RestoreGate.Checking else RestoreGate.Open
    }

    /** Looks at the server once the app can (fresh install only). A network failure keeps the gate closed. */
    suspend fun checkGate() {
        if (prefs.get(K_GATE) != GATE_PENDING) {
            _gate.value = RestoreGate.Open
            return
        }
        val s = server ?: return openGate()
        when (val r = s.backups()) {
            is ApiResult.Ok -> if (r.value.backups.isEmpty()) openGate() else _gate.value = RestoreGate.Offer(r.value.backups)
            is ApiResult.Error -> if (r.httpStatus == 404 || r.httpStatus == 403) openGate() // a pre-v1.22 server: nothing to restore
            is ApiResult.NetworkError -> Unit
        }
    }

    /** Restored, or "Skip" confirmed: server backups may be made from now on. */
    fun openGate() {
        prefs.set(K_GATE, GATE_DONE)
        _gate.value = RestoreGate.Open
    }

    suspend fun serverBackups(): ApiResult<BackupsReply> = server?.backups() ?: ApiResult.Error(404, "no_server", "")

    fun describe(e: Throwable): String = when (e) {
        is BackupException -> when (e.kind) {
            BackupException.Kind.Typo -> "Check the recovery key for a typo"
            BackupException.Kind.WrongKey -> "That recovery key or passphrase doesn't match"
            BackupException.Kind.Malformed -> "That isn't a recovery key (28 letters and digits)"
            BackupException.Kind.WrongAccount -> "This backup belongs to another account"
            BackupException.Kind.Unsupported -> "Update RisiMe to restore this backup"
            BackupException.Kind.Integrity, BackupException.Kind.Format -> "This backup file is damaged or was changed"
            BackupException.Kind.WeakPassphrase -> "That passphrase is too weak"
            BackupException.Kind.NoKey -> "This backup's key isn't on this phone"
            BackupException.Kind.Io -> e.message ?: "Couldn't read or write the backup"
            BackupException.Kind.Storage -> e.message ?: "Storage error"
        }
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        const val EXT = ".risimebk"

        /** §22.3: clients use 33 562 624-byte parts (512 chunks). */
        const val PART_SIZE = 33_562_624L
        const val MAX_BACKUP = 536_870_912L
        const val UPDATE_UPLOAD_WAIT_MS = 120_000L

        const val K_SERVER_ON = "server_on"
        const val K_MOBILE = "mobile_data"
        const val K_GATE = "gate"
        const val K_LAST_LOCAL = "last_local"
        const val K_LAST_SERVER = "last_server"
        const val K_PROGRESS = "restore_progress"
        const val K_REPLACE_OK = "replace_device_ok"
        const val K_KEY_SHOWN = "key_shown"
        const val K_CARD = "card_dismissed"
        const val GATE_PENDING = "pending"
        const val GATE_DONE = "done"

        /** §22.4 the export file name. */
        fun exportName(nowMs: Long): String = "risime-backup-${java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}$EXT"
    }
}
