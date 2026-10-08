package lk.codegen.risime.data.profile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.ProfilePhotoConvEntity
import lk.codegen.risime.data.db.ProfilePhotoDao
import lk.codegen.risime.data.db.ProfilePhotoEntity
import lk.codegen.risime.data.media.ImageEnc
import lk.codegen.risime.data.media.MediaCrypto
import lk.codegen.risime.data.media.MediaFormat
import lk.codegen.risime.data.media.b64ToHex
import lk.codegen.risime.data.media.sha256B64
import lk.codegen.risime.data.mls.KvSealer
import lk.codegen.risime.data.mls.MlsEngine
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.BlobUploadReply
import lk.codegen.risime.net.MsgSendReply
import lk.codegen.risime.net.isGroupConversation
import lk.codegen.risime.realtime.PushResult
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** What the chat engine needs from the profile-photo store (null in builds/tests without photos). */
interface ProfilePhotoHooks {
    /** §18.2, inside the event's transaction: the winner rule, the sealed key, the old key and cache dropped. */
    suspend fun applyInTx(subject: String, conversationId: String, env: ProfilePhotoEnvelope, me: String)

    /** After the batch's transactions: unlink replaced ciphertext, schedule downloads, refresh the UI. */
    fun afterCommit()

    /** §18.5 (android A2): before an own message in a group, send a dirty group's photo first. */
    suspend fun beforeOwnMessage(conversationId: String)
}

/** The blob REST calls the photos need (§14.2, §18.3). */
interface AvatarApi {
    suspend fun uploadAvatar(clientBlobId: String, file: File): ApiResult<BlobUploadReply>

    suspend fun uploadIcon(conversationId: String, clientBlobId: String, file: File): ApiResult<BlobUploadReply>

    /** Downloads the whole blob into [into] (overwriting it). */
    suspend fun download(blobId: String, into: File, etagHex: String): ApiResult<Long>

    suspend fun delete(blobId: String): ApiResult<Unit>
}

/** Small local state (the change limit, a blob to delete after "Remove photo"). */
interface PhotoKv {
    fun get(key: String): String?

    fun set(key: String, value: String?)
}

/** The avatar ciphertext cache (app-private, no backup): one file per subject and blob, never plaintext. */
class AvatarFiles(val dir: File) {
    val tmp: File get() = File(dir, "tmp").also { it.mkdirs() }

    fun file(subject: String, blobId: String): File = File(dir.also { it.mkdirs() }, name(subject, blobId))

    fun part(subject: String, blobId: String): File = File(dir.also { it.mkdirs() }, name(subject, blobId) + ".part")

    private fun name(subject: String, blobId: String): String =
        MessageDigest.getInstance("SHA-256").digest("${subject.lowercase()}\u0000$blobId".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(40) + ".enc"

    /** Files of rows that no longer exist (crash between commit and unlink) and temp files. */
    fun cleanup(live: Set<File>) {
        File(dir, "tmp").listFiles()?.forEach { it.delete() }
        dir.listFiles()?.filter { it.isFile && it !in live }?.forEach { it.delete() }
    }

    fun wipe() {
        dir.deleteRecursively()
    }
}

/** The outcome of a photo change, for the UI. */
sealed interface PhotoChange {
    data object Ok : PhotoChange

    data class Refused(val text: String) : PhotoChange
}

/**
 * Contract v1.17 §18 on the device: receiving (`profile_photo` in the event's transaction, the
 * winner rule, sealed keys, the ciphertext cache), downloads and decrypt-on-display, setting and
 * removing the own photo (the `avatar` blob, paced sends to every e2ee conversation), the §18.5
 * triggers with the one-sender-per-user wait and dirty groups, and the §18.7 group icons.
 */
class ProfilePhotos(
    private val dao: ProfilePhotoDao,
    private val tx: TransactionRunner,
    private val sealer: () -> KvSealer,
    val files: AvatarFiles,
    private val crypto: () -> MediaCrypto?,
    private val api: AvatarApi?,
    private val sendSilent: suspend (conversationId: String, plaintext: ByteArray) -> PushResult<MsgSendReply>,
    private val mls: () -> MlsEngine?,
    /** Candidate conversations (friends' DMs and groups); only those this device holds an MLS group for count. */
    private val conversations: suspend () -> List<String>,
    private val me: suspend () -> String?,
    private val serverNow: () -> Long,
    private val scope: CoroutineScope,
    private val kv: PhotoKv,
    /** §18.2 (android A9): blocked users show initials on this device. */
    private val blocked: (userId: String) -> Boolean = { false },
    /** §18.2: download at once except with Data Saver or roaming (then when first shown). */
    private val autoDownload: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: (IntRange) -> Int = { it.random() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = {},
) : ProfilePhotoHooks {
    private val _revision = MutableStateFlow(0L)

    /** Bumped whenever a photo row or its cache changed (the UI reloads its avatars). */
    val revision: StateFlow<Long> = _revision

    /** Files of replaced photos, unlinked after the commit (android A5: the key went in the transaction). */
    private val stale = java.util.concurrent.ConcurrentLinkedQueue<File>()
    @Volatile private var touched = false

    // ---- receiving (§18.2) ----

    override suspend fun applyInTx(subject: String, conversationId: String, env: ProfilePhotoEnvelope, me: String) {
        if (!ProfilePhotoEnvelope.inWindow(env.ver, serverNow())) {
            log("profile_photo ver ${env.ver} outside the window: dropped")
            return
        }
        val key = subject.lowercase()
        // §18.5 (android A1): a sibling's send with a ver ≥ mine settles what this device owed there.
        if (key == me.lowercase()) {
            val ownVer = dao.get(key)?.ver
            if (ownVer == null || env.ver >= ownVer) dao.clearSent(conversationId, env.ver)
        }
        val cur = dao.get(key)
        if (!ProfilePhotoEnvelope.wins(env.ver, env.photo?.blob?.sha256, cur?.ver, cur?.sha256)) return
        cur?.blobId?.let { b ->
            if (b != env.photo?.blob?.blobId) {
                stale += files.file(key, b)
                stale += files.part(key, b)
            }
        }
        dao.put(entity(key, env.ver, env.photo, ProfilePhotoEntity.FETCH_NONE))
        touched = true
    }

    private fun entity(key: String, ver: Long, p: PhotoRef?, fetched: Int): ProfilePhotoEntity = ProfilePhotoEntity(
        userId = key, ver = ver, blobId = p?.blob?.blobId, size = p?.blob?.size ?: 0, sha256 = p?.blob?.sha256,
        keySealed = p?.let { seal(key, it.blob.blobId, it.enc.key) }, plainSize = p?.enc?.plainSize ?: 0,
        w = p?.w ?: 0, h = p?.h ?: 0, fetched = fetched, mime = p?.mime ?: "image/jpeg",
    )

    private fun seal(subject: String, blobId: String, key: ByteArray): ByteArray = sealer().seal(NS, "$subject|$blobId".toByteArray(), key)

    private fun open(row: ProfilePhotoEntity): ByteArray = sealer().open(NS, "${row.userId}|${row.blobId}".toByteArray(), row.keySealed!!)

    override fun afterCommit() {
        while (true) stale.poll()?.delete() ?: break
        if (!touched) return
        touched = false
        _revision.value += 1
        scope.launch { runCatching { downloadAll() }.onFailure { log("avatar downloads: ${it.message}") } }
    }

    private val downloadLock = Mutex()

    /** Every unfetched current photo (and, once a day, those that were gone). */
    suspend fun downloadAll(force: Boolean = false) = downloadLock.withLock {
        if (!force && !autoDownload()) return@withLock
        for (row in dao.unfetched() + dao.goneBefore(clock() - DAY_MS)) {
            if (blocked(row.userId)) continue
            download(row)
        }
    }

    /** §18.2 Download: size → SHA-256 → every segment and the final flag (the core's verified prefix). */
    private suspend fun download(row: ProfilePhotoEntity): Boolean {
        val core = crypto() ?: return false
        val a = api ?: return false
        val blobId = row.blobId ?: return false
        val sha = row.sha256 ?: return false
        val dst = files.file(row.userId, blobId)
        if (dst.isFile && dst.length() == row.size) return mark(row, ProfilePhotoEntity.FETCH_CACHED)
        val part = files.part(row.userId, blobId)
        part.delete()
        when (val r = a.download(blobId, part, b64ToHex(sha))) {
            is ApiResult.Error -> {
                part.delete()
                if (r.httpStatus == 404) mark(row, ProfilePhotoEntity.FETCH_GONE)
                return false
            }
            is ApiResult.NetworkError -> {
                part.delete()
                return false
            }
            is ApiResult.Ok -> Unit
        }
        val ok = runCatching {
            part.length() == row.size && sha256B64(part) == sha &&
                core.verifiedPrefix(part, open(row), MediaFormat.ALG, row.size) == row.size
        }.getOrDefault(false)
        if (!ok) {
            part.delete()
            log("avatar ${row.userId.take(8)}: verify failed")
            mark(row, ProfilePhotoEntity.FETCH_FAILED)
            return false
        }
        if (!part.renameTo(dst)) {
            part.delete()
            return false
        }
        return mark(row, ProfilePhotoEntity.FETCH_CACHED).also { if (!it) dst.delete() }
    }

    /** Only while [row] is still the current photo (a newer one may have replaced it meanwhile). */
    private suspend fun mark(row: ProfilePhotoEntity, fetched: Int): Boolean {
        val n = dao.setFetched(row.userId, row.blobId ?: return false, fetched, clock())
        if (n > 0) _revision.value += 1
        return n > 0
    }

    /** The decoded-image source: verified plaintext and its declared metadata, or null (initials). */
    class Plain(val bytes: ByteArray, val mime: String, val w: Int, val h: Int, val ver: Long, val blobId: String)

    /**
     * Decrypt-on-display (§18.2): the cached ciphertext, opened in the core after every check. Null =
     * initials: no photo, blocked, not fetched yet (a download starts), gone or failed.
     */
    suspend fun plaintext(subject: String): Plain? {
        val key = subject.lowercase()
        val row = dao.get(key) ?: return null
        val blobId = row.blobId ?: return null
        if (blocked(key)) return null
        when (row.fetched) {
            ProfilePhotoEntity.FETCH_NONE -> {
                scope.launch { downloadLock.withLock { download(row) } }
                return null
            }
            ProfilePhotoEntity.FETCH_CACHED -> Unit
            else -> return null
        }
        val core = crypto() ?: return null
        val f = files.file(key, blobId)
        if (!f.isFile) {
            mark(row, ProfilePhotoEntity.FETCH_NONE)
            scope.launch { downloadLock.withLock { download(row.copy(fetched = ProfilePhotoEntity.FETCH_NONE)) } }
            return null
        }
        return try {
            val bytes = core.decryptFile(f, open(row), MediaFormat.ALG, row.plainSize, row.size, Base64.getDecoder().decode(row.sha256))
            Plain(bytes, row.mime, row.w, row.h, row.ver, blobId)
        } catch (e: Exception) {
            log("avatar ${key.take(8)}: decrypt failed (${e.javaClass.simpleName})")
            failed(key, blobId)
            null
        }
    }

    /** A decrypt or decode failure: initials, logged without bytes; never a visible error. */
    suspend fun failed(subject: String, blobId: String) {
        val row = dao.get(subject.lowercase())?.takeIf { it.blobId == blobId } ?: return
        files.file(row.userId, blobId).delete()
        mark(row, ProfilePhotoEntity.FETCH_FAILED)
    }

    /** The blob shown for [subject] right now (cached and not blocked), without decrypting; null = initials or not yet. */
    suspend fun shownBlob(subject: String): String? {
        val row = dao.get(subject.lowercase())?.takeIf { it.fetched == ProfilePhotoEntity.FETCH_CACHED } ?: return null
        if (blocked(row.userId)) return null
        return row.blobId
    }

    /** Whether a subject currently has a photo row with a blob (Settings: Change / Remove). */
    suspend fun hasPhoto(subject: String): Boolean = dao.get(subject.lowercase())?.blobId != null

    // ---- the own photo (§18.5 change, §18.6 UI) ----

    private val changeLock = Mutex()

    /**
     * Set or change: [jpeg] is the cropped, re-encoded, metadata-free square (§18.6). One fresh
     * encrypt call (crypto K4), the `avatar` upload, then the own row and a paced send to every e2ee
     * conversation. The file is moved into the cache, so this device never downloads its own photo.
     */
    suspend fun setOwnPhoto(jpeg: ByteArray, side: Int): PhotoChange = changeLock.withLock {
        val me = me()?.lowercase() ?: return@withLock PhotoChange.Refused(GENERIC)
        if (!changeAllowed()) return@withLock PhotoChange.Refused(TRY_LATER)
        val core = crypto() ?: return@withLock PhotoChange.Refused("Profile photos need end-to-end encryption on this phone")
        val a = api ?: return@withLock PhotoChange.Refused(GENERIC)
        val id = newId()
        val plain = File(files.tmp, "$id.plain")
        val cipher = File(files.tmp, "$id.enc")
        try {
            plain.writeBytes(jpeg)
            val sealed = core.encryptFile(plain, cipher)
            plain.delete()
            if (sealed.cipherSize > PhotoRef.MAX_CIPHER) return@withLock PhotoChange.Refused("This photo is too large")
            val sha = Base64.getEncoder().encodeToString(sealed.sha256)
            val reply = when (val r = a.uploadAvatar(newId(), cipher)) {
                is ApiResult.Ok -> r.value
                is ApiResult.Error -> return@withLock PhotoChange.Refused(if (r.httpStatus == 429) TRY_LATER else GENERIC)
                is ApiResult.NetworkError -> return@withLock PhotoChange.Refused("No connection. Try again.")
            }
            if (reply.size != sealed.cipherSize || reply.sha256 != sha) return@withLock PhotoChange.Refused(GENERIC)
            val ref = PhotoRef(BlobRef(reply.blobId, reply.size, reply.sha256), ImageEnc(sealed.alg, sealed.key, sealed.plainSize), "image/jpeg", side, side)
            val dst = files.file(me, reply.blobId)
            if (!cipher.renameTo(dst)) cipher.copyTo(dst, overwrite = true)
            commitChange(me, ref)
            sealed.key.fill(0)
            PhotoChange.Ok
        } finally {
            plain.delete()
            cipher.delete()
        }
    }

    /** "Remove photo": `photo: null` with a new `ver`; the blob is deleted once every queued send has gone (android A5). */
    suspend fun removeOwnPhoto(): PhotoChange = changeLock.withLock {
        val me = me()?.lowercase() ?: return@withLock PhotoChange.Refused(GENERIC)
        val cur = dao.get(me)
        val blobId = cur?.blobId ?: return@withLock PhotoChange.Ok
        if (!changeAllowed()) return@withLock PhotoChange.Refused(TRY_LATER)
        kv.set(KV_DELETE_BLOB, blobId)
        commitChange(me, null)
        files.file(me, blobId).delete()
        PhotoChange.Ok
    }

    private suspend fun commitChange(me: String, ref: PhotoRef?) {
        val old = dao.get(me)
        val ver = maxOf(serverNow(), (old?.ver ?: 0) + 1)
        val convs = e2eeConversations()
        tx.run {
            dao.put(entity(me, ver, ref, if (ref != null) ProfilePhotoEntity.FETCH_CACHED else ProfilePhotoEntity.FETCH_NONE))
            for (c in convs) {
                val row = dao.conv(c) ?: ProfilePhotoConvEntity(c, null, 0, false, leaves(c))
                dao.putConv(row.copy(pendingVer = ver, dueAt = 0, dirty = false))
            }
        }
        old?.blobId?.takeIf { it != ref?.blob?.blobId }?.let { files.file(me, it).delete() }
        recordChange()
        _revision.value += 1
        pumpSoon()
    }

    /** §18.5 Limits: at most 3 changes per hour per user ("Try again later"). */
    private fun changeAllowed(): Boolean = recentChanges().size < MAX_CHANGES_PER_HOUR

    private fun recentChanges(): List<Long> {
        val now = clock()
        return kv.get(KV_CHANGES).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < HOUR_MS }
    }

    private fun recordChange() = kv.set(KV_CHANGES, (recentChanges() + clock()).joinToString(","))

    private suspend fun e2eeConversations(): List<String> {
        val engine = mls() ?: return emptyList()
        return conversations().map { it.lowercase() }.distinct().filter { engine.group(it) != null }
    }

    private fun leaves(conv: String): String =
        mls()?.members(conv).orEmpty().map { "${it.userId.lowercase()}/${it.deviceId.lowercase()}" }.sorted().joinToString("\n")

    /** The own envelope to send: the current row's `ver`, blob and key (a re-send keeps all three). */
    private fun ownEnvelope(own: ProfilePhotoEntity): ProfilePhotoEnvelope {
        val blobId = own.blobId ?: return ProfilePhotoEnvelope(own.ver, null)
        val ref = PhotoRef(BlobRef(blobId, own.size, own.sha256!!), ImageEnc(MediaFormat.ALG, open(own), own.plainSize), own.mime, own.w, own.h)
        return ProfilePhotoEnvelope(own.ver, ref)
    }

    // ---- sending: pacing and triggers (§18.5) ----

    private val pumpLock = Mutex()
    private var timer: Job? = null

    fun pumpSoon(afterMs: Long = 0) {
        scope.launch {
            if (afterMs > 0) sleep(afterMs)
            runCatching { pump() }.onFailure { log("profile photo sends: ${it.message}") }
        }
    }

    /**
     * Sends what is due, at most one `profile_photo` per second in all; a send that can't go now is
     * retried later. Then deletes a removed photo's blob once nothing is queued any more.
     */
    suspend fun pump(): Unit = pumpLock.withLock {
        val me = me()?.lowercase() ?: return@withLock
        while (true) {
            val rows = dao.pendingSends()
            if (rows.isEmpty()) {
                finishRemoval()
                return@withLock
            }
            val own = dao.get(me)
            if (own == null) {
                rows.forEach { dao.clearSent(it.conversationId, Long.MAX_VALUE) }
                return@withLock
            }
            val now = clock()
            val due = rows.firstOrNull { it.dueAt <= now }
            if (due == null) {
                schedule(rows.minOf { it.dueAt } - now)
                return@withLock
            }
            when (val r = sendTo(due.conversationId, own)) {
                is PushResult.Ok -> dao.clearSent(due.conversationId, own.ver)
                is PushResult.Rejected -> {
                    log("profile_photo to ${due.conversationId.take(12)}: ${r.reason}, later")
                    dao.putConv(due.copy(dueAt = now + RETRY_MS))
                }
                PushResult.Unavailable -> {
                    schedule(RETRY_MS)
                    return@withLock
                }
            }
            sleep(PACE_MS)
        }
    }

    private fun schedule(inMs: Long) {
        timer?.cancel()
        timer = scope.launch {
            sleep(inMs.coerceAtLeast(0))
            runCatching { pump() }
        }
    }

    private suspend fun sendTo(conv: String, own: ProfilePhotoEntity): PushResult<MsgSendReply> {
        if (mls()?.group(conv) == null) return PushResult.Rejected("no_group")
        return sendSilent(conv, ownEnvelope(own).encode())
    }

    private suspend fun finishRemoval() {
        val blobId = kv.get(KV_DELETE_BLOB) ?: return
        val a = api ?: return
        when (val r = a.delete(blobId)) {
            is ApiResult.Ok -> kv.set(KV_DELETE_BLOB, null)
            is ApiResult.Error -> if (r.httpStatus == 404 || r.httpStatus == 403) kv.set(KV_DELETE_BLOB, null)
            is ApiResult.NetworkError -> Unit
        }
    }

    override suspend fun beforeOwnMessage(conversationId: String) {
        val conv = conversationId.lowercase()
        val row = dao.conv(conv)?.takeIf { it.dirty } ?: return
        val me = me()?.lowercase() ?: return
        val own = dao.get(me)
        if (own?.blobId == null) {
            dao.putConv(row.copy(dirty = false))
            return
        }
        if (sendTo(conv, own) is PushResult.Ok) dao.clearSent(conv, own.ver)
    }

    /** §18.5 (android A2): a dirty group sends when the user opens it, after the 5–30 s sibling wait. */
    suspend fun onChatOpened(conversationId: String) {
        val conv = conversationId.lowercase()
        val row = dao.conv(conv)?.takeIf { it.dirty && it.pendingVer == null } ?: return
        val own = dao.get(me()?.lowercase() ?: return)?.takeIf { it.blobId != null } ?: return
        dao.putConv(row.copy(pendingVer = own.ver, dueAt = clock() + random(WAIT_MIN_MS..WAIT_MAX_MS)))
        pumpSoon()
    }

    private val reconcileLock = Mutex()

    /**
     * §18.5 triggers 2 and 3, from the MLS state: a conversation this device holds a group for but
     * has no row (created, added, re-added, rejoined) or one with a leaf not seen before (an added
     * device, the own user's included). Only while a photo is set: DMs send after the random 5–30 s
     * wait, groups go dirty. Also keeps the group icons in step with `group_meta.icon` (§18.7).
     */
    suspend fun reconcile(): Unit = reconcileLock.withLock {
        val engine = mls() ?: return@withLock
        val me = me()?.lowercase() ?: return@withLock
        val own = dao.get(me)?.takeIf { it.blobId != null }
        var changed = false
        for (conv in conversations().map { it.lowercase() }.distinct()) {
            if (engine.group(conv) == null) continue
            if (isGroupConversation(conv)) changed = syncGroupIcon(conv, engine) || changed
            val now = leaves(conv)
            val row = dao.conv(conv)
            val trigger = row == null || (now.split('\n').toSet() - row.leaves.split('\n').toSet()).any { it.isNotEmpty() }
            var next = (row ?: ProfilePhotoConvEntity(conv, null, 0, false, now)).copy(leaves = now)
            if (trigger && own != null) {
                next = if (isGroupConversation(conv)) {
                    next.copy(dirty = true)
                } else {
                    val due = clock() + random(WAIT_MIN_MS..WAIT_MAX_MS)
                    next.copy(pendingVer = own.ver, dueAt = if (next.pendingVer != null) minOf(next.dueAt, due) else due)
                }
            }
            if (next != row) dao.putConv(next)
        }
        if (changed) {
            _revision.value += 1
            scope.launch { runCatching { downloadAll() } }
        }
        pumpSoon()
    }

    // ---- group icons (§18.7; §14.4 `group_meta.icon`) ----

    /** The local row follows the group's current `group_meta.icon`; @return true if it changed. */
    private suspend fun syncGroupIcon(conv: String, engine: MlsEngine): Boolean {
        val ref = runCatching { PhotoRef.groupIcon(engine.groupMeta(conv)?.icon) }.getOrNull()
        val cur = dao.get(conv)
        if (ref == null) {
            if (cur == null) return false
            dao.delete(conv)
            cur.blobId?.let { files.file(conv, it).delete() }
            return true
        }
        if (cur != null && cur.blobId == ref.blob.blobId && cur.sha256 == ref.blob.sha256) return false
        val cached = files.file(conv, ref.blob.blobId).let { it.isFile && it.length() == ref.blob.size }
        dao.put(entity(conv, 0, ref, if (cached) ProfilePhotoEntity.FETCH_CACHED else ProfilePhotoEntity.FETCH_NONE))
        cur?.blobId?.takeIf { it != ref.blob.blobId }?.let { files.file(conv, it).delete() }
        return true
    }

    /**
     * §18.7 an admin's new group photo: encrypted under a fresh key, uploaded as `purpose=icon`, the
     * ciphertext kept in the cache (so it is never downloaded again here). Returns the icon object
     * for the `meta_changed` commit, or why it can't be set.
     */
    suspend fun prepareGroupIcon(conv: String, jpeg: ByteArray, side: Int): Pair<PhotoRef?, String?> {
        val core = crypto() ?: return null to "Group photos need end-to-end encryption on this phone"
        val a = api ?: return null to GENERIC
        val id = newId()
        val plain = File(files.tmp, "$id.plain")
        val cipher = File(files.tmp, "$id.enc")
        try {
            plain.writeBytes(jpeg)
            val sealed = core.encryptFile(plain, cipher)
            plain.delete()
            if (sealed.cipherSize > PhotoRef.MAX_CIPHER) return null to "This photo is too large"
            val sha = Base64.getEncoder().encodeToString(sealed.sha256)
            val reply = when (val r = a.uploadIcon(conv, newId(), cipher)) {
                is ApiResult.Ok -> r.value
                is ApiResult.Error -> return null to when {
                    r.httpStatus == 429 -> TRY_LATER
                    r.httpStatus == 403 -> "Only admins can change the group photo"
                    else -> GENERIC
                }
                is ApiResult.NetworkError -> return null to "No connection. Try again."
            }
            if (reply.size != sealed.cipherSize || reply.sha256 != sha) return null to GENERIC
            val dst = files.file(conv.lowercase(), reply.blobId)
            if (!cipher.renameTo(dst)) cipher.copyTo(dst, overwrite = true)
            return PhotoRef(BlobRef(reply.blobId, reply.size, reply.sha256), ImageEnc(sealed.alg, sealed.key, sealed.plainSize), "image/jpeg", side, side) to null
        } finally {
            plain.delete()
            cipher.delete()
        }
    }

    /** On start: temp files and ciphertext of rows that are gone. */
    suspend fun startup(subjects: List<ProfilePhotoEntity>) {
        files.cleanup(subjects.mapNotNull { r -> r.blobId?.let { files.file(r.userId, it) } }.toSet())
    }

    companion object {
        const val NS = "avatar"
        const val KV_CHANGES = "profile_photo_changes"
        const val KV_DELETE_BLOB = "profile_photo_delete_blob"
        const val MAX_CHANGES_PER_HOUR = 3
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        const val PACE_MS = 1_000L
        const val RETRY_MS = 60_000L
        const val WAIT_MIN_MS = 5_000
        const val WAIT_MAX_MS = 30_000
        const val TRY_LATER = "Try again later"
        const val GENERIC = "Couldn't change your photo. Try again."
    }
}
