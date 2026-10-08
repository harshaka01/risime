package lk.codegen.risime.data.backup

import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.ReactionStore
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.AppDatabase
import lk.codegen.risime.data.db.ChatStateEntity
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.DeletedIdEntity
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.db.GroupMemberEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.deletes.TimeUuid
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.data.history.HistoryFixtures
import lk.codegen.risime.data.media.ImageEnvelope
import lk.codegen.risime.data.media.ImageRepository
import lk.codegen.risime.data.media.MediaFiles
import lk.codegen.risime.data.media.MediaSealer
import lk.codegen.risime.data.mls.KvSealer
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * A stand-in for the core's backup file (JVM tests without the native core): magic, a JSON header
 * (the §22.4 fields the app reads), the DEFLATE bytes and a SHA-256 over everything before it. The
 * key logic mirrors the core's contract: a record names its `bk_id`, unlocking needs the secret,
 * a reader without the file's key is [BackupException.Kind.NoKey].
 */
class FakeBackupKeys(private val rng: java.util.Random = java.util.Random(7)) : BackupKeys {
    private val known = mutableMapOf<String, String>() // bk_id -> recovery key
    var current: String? = null
    private var recovery: String? = null
    private var passphrase: String? = null
    var unlockCalls = 0

    private fun hash(s: String) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.trim().uppercase().toByteArray()))

    private fun record(bk: String, rk: String, pp: String? = passphrase): String = ProtocolJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("v", 1)
            put("bk_id", bk)
            put("rk", hash(rk))
            if (pp != null) put("pp", hash(pp))
        },
    )

    private fun newKey(): String = (1..7).joinToString("-") { (1..4).map { "0123456789ABCDEFGHJKMNPQRSTVWXYZ"[rng.nextInt(32)] }.joinToString("") }

    override fun setup(userId: String): BackupSetupResult {
        val bk = current ?: Base64.getEncoder().encodeToString(ByteArray(8).also(rng::nextBytes)).also { current = it }
        val rk = recovery ?: newKey().also { recovery = it }
        known[bk] = rk
        return BackupSetupResult(rk, record(bk, rk))
    }

    override fun addPassphrase(userId: String, passphrase: String): String {
        if (passphrase.trim().length < 14) throw BackupException(BackupException.Kind.WeakPassphrase)
        this.passphrase = passphrase
        return record(current!!, recovery!!, passphrase)
    }

    override fun unlock(userId: String, keyRecord: String, secret: String, kind: SecretKind, makeCurrent: Boolean): String {
        unlockCalls++
        val o = ProtocolJson.parseToJsonElement(keyRecord) as JsonObject
        val bk = o.str("bk_id") ?: throw BackupException(BackupException.Kind.Malformed)
        val want = if (kind == SecretKind.RecoveryKey) o.str("rk") else o.str("pp")
        if (kind == SecretKind.RecoveryKey && secret.replace("-", "").replace(" ", "").length != 28) throw BackupException(BackupException.Kind.Malformed)
        if (want != hash(secret)) throw BackupException(BackupException.Kind.WrongKey)
        known[bk] = secret.trim().uppercase()
        if (makeCurrent) {
            current = bk
            if (kind == SecretKind.RecoveryKey) recovery = secret.trim().uppercase()
        }
        return bk
    }

    override fun recoveryKey(): String? = recovery

    override fun rotateRecoveryKey(userId: String): BackupSetupResult {
        recovery = newKey()
        known[current!!] = recovery!!
        return BackupSetupResult(recovery!!, record(current!!, recovery!!))
    }

    override fun forget() {
        known.clear()
        current = null
        recovery = null
        passphrase = null
    }

    override fun keyRecord(): String? = current?.let { record(it, recovery ?: return null) }

    override fun keyIds(): BackupKeyIds = BackupKeyIds(current, known.keys.filter { it != current })

    override fun dropKey(bkId: String) {
        known.remove(bkId)
    }

    override fun writer(userId: String, backupId: String, createdAt: String, appVersion: String, keyRecord: String?, out: File): BackupCipherWriter {
        val bk = keyRecord?.let { (ProtocolJson.parseToJsonElement(it) as JsonObject).str("bk_id") } ?: current ?: throw BackupException(BackupException.Kind.NoKey)
        if (bk !in known) throw BackupException(BackupException.Kind.NoKey, bk)
        val header = buildJsonObject {
            put("backup_id", backupId); put("user_id", userId); put("created_at", createdAt); put("app_version", appVersion); put("bk_id", bk)
            put("key", keyRecord ?: keyRecord() ?: "")
        }.toString().toByteArray()
        val data = ByteArrayOutputStream()
        return object : BackupCipherWriter {
            override fun write(bytes: ByteArray) = data.write(bytes)

            override fun finish(): BackupWrittenInfo {
                val body = ByteArrayOutputStream()
                DataOutputStream(body).apply { write(MAGIC); writeInt(header.size); write(header); write(data.toByteArray()) }
                val bytes = body.toByteArray()
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                out.writeBytes(bytes + sha)
                return BackupWrittenInfo(backupId, bk, out.length(), MessageDigest.getInstance("SHA-256").digest(out.readBytes()), data.size().toLong())
            }
        }
    }

    override fun reader(userId: String, file: File, expectedBackupId: String?, expectedBkId: String?): BackupCipherReader {
        val meta = FakeBackupTools.parse(file)
        if (!meta.userId.equals(userId, true)) throw BackupException(BackupException.Kind.WrongAccount)
        if (expectedBackupId != null && expectedBackupId != meta.backupId) throw BackupException(BackupException.Kind.Integrity, "backup_id")
        if (expectedBkId != null && expectedBkId != meta.bkId) throw BackupException(BackupException.Kind.Integrity, "bk_id")
        if (meta.bkId !in known) throw BackupException(BackupException.Kind.NoKey, meta.bkId)
        var verified = false
        var data: ByteArray? = null
        return object : BackupCipherReader {
            override fun info() = meta

            override fun verify(): Long {
                val all = file.readBytes()
                val body = all.copyOf(all.size - 32)
                if (!MessageDigest.getInstance("SHA-256").digest(body).contentEquals(all.copyOfRange(all.size - 32, all.size))) throw BackupException(BackupException.Kind.Integrity, "chunk")
                val hl = DataInputStream(body.inputStream(MAGIC.size, 4)).readInt()
                data = body.copyOfRange(MAGIC.size + 4 + hl, body.size)
                verified = true
                return data!!.size.toLong()
            }

            private var pos = 0

            override fun read(): ByteArray {
                if (!verified) throw BackupException(BackupException.Kind.Malformed, "read before verify")
                val d = data!!
                if (pos >= d.size) return ByteArray(0)
                val n = minOf(1000, d.size - pos)
                return d.copyOfRange(pos, pos + n).also { pos += n }
            }
        }
    }

    companion object {
        val MAGIC = "FAKEBK01".toByteArray()
    }
}

object FakeBackupTools : BackupTools {
    fun parse(file: File): BackupFileMeta {
        val all = file.readBytes()
        if (all.size < 12 || !all.copyOf(8).contentEquals(FakeBackupKeys.MAGIC)) throw BackupException(BackupException.Kind.Format)
        val hl = DataInputStream(all.inputStream(8, 4)).readInt()
        val h = ProtocolJson.parseToJsonElement(String(all, 12, hl)) as JsonObject
        return BackupFileMeta(h.str("backup_id")!!, h.str("user_id")!!, h.str("created_at")!!, h.str("app_version")!!, h.str("bk_id")!!, 1, h.str("key")?.takeIf { it.isNotEmpty() })
    }

    override fun fileInfo(file: File): BackupFileMeta = parse(file)

    override fun normalizeRecoveryKey(input: String): String = input.trim().uppercase()

    override fun passphraseFloor(passphrase: String, phone: String?): PassphraseFloor = if (passphrase.trim().length >= 14) PassphraseFloor.Ok else PassphraseFloor.TooShort

    override fun vectorsCheck(json: String): Int = 0
}

class MapPrefs : BackupPrefs {
    val m = mutableMapOf<String, String>()

    override fun get(key: String) = m[key]

    override fun set(key: String, value: String?) {
        if (value == null) m.remove(key) else m[key] = value
    }

    override fun clear() = m.clear()
}

/** One phone's database (Room in memory, bundled SQLite) with the pieces export and import need. */
class BackupPhone(val me: String = HistoryFixtures.ME, dir: File) {
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .build()
    val tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() }
    val sealer = MediaSealer { KvSealer(ByteArray(32) { 3 }) }
    val images = ImageRepository(db.media(), db.messages(), tx, sealer, MediaFiles(File(dir, "media")), { null }, null)
    val progress = object : RestoreProgress {
        var saved: Pair<String, Int>? = null
        override fun done(backupId: String) = saved?.takeIf { it.first == backupId }?.second ?: 0
        override fun save(backupId: String, lines: Int) { saved = backupId to lines }
        override fun clear() { saved = null }
    }

    fun exporter() = BundleExporter(db.backup(), db.groups(), db.deletes(), db.media(), sealer, me, tabOf = { db.chatTabs().get(it) })

    fun importer(batch: Int = 1_000, progress: RestoreProgress = this.progress) = BundleImporter(
        db.messages(), db.deletes(), db.groups(), db.contacts(), db.backup(), db.history(), ReactionStore(db.reactions()), images, tx, progress, me, batch = batch,
        restoreTab = { db.chatTabs().insertIfMissing(it) },
    )

    /** The upgrade gate's view: per conversation (texts, images, calls, tombstones). */
    suspend fun counts(): Map<String, List<Int>> = db.backup().counts().associate { it.conversationId to listOf(it.texts, it.images, it.calls, it.tombstones) }

    fun close() = db.close()
}

/** The bundle as plain lines (header first), the way the manager writes it. */
suspend fun bundleOf(phone: BackupPhone, backupId: String = UUID.randomUUID().toString()): List<String> {
    val ex = phone.exporter()
    val counts = ex.write { }
    val out = ByteArrayOutputStream()
    out.write(encodeLine(BackupBundleHeader.serializer(), BackupBundleHeader(schema = ex.schema, backupId = backupId, userId = phone.me, createdAt = "2026-10-08T02:00:00.000Z", appVersion = "test", counts = counts)))
    phone.exporter().write { out.write(it) }
    return out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
}

object BackupData {
    const val ME = HistoryFixtures.ME
    const val PEER = HistoryFixtures.PEER
    const val THIRD = HistoryFixtures.THIRD
    const val GRP = HistoryFixtures.GRP
    val DM: String = dmConversationId(ME, PEER)

    fun at(i: Int) = "2026-10-0${1 + i / 1000}T10:%02d:%02d.000Z".format((i / 60) % 60, i % 60)

    fun msg(conv: String, from: String, i: Int, body: String = "m$i", status: String? = null): MessageEntity {
        val ts = at(i)
        val id = HistoryFixtures.tuuid(HistoryFixtures.ms(ts), i)
        val out = from == ME
        return MessageEntity(
            clientMsgId = "cm-$id", messageId = id, conversationId = conv, from = from, to = if (out) (if (conv == DM) PEER else conv) else ME,
            body = body, serverTs = ts, localTs = HistoryFixtures.ms(ts),
            status = status ?: if (out) MessageStatus.DELIVERED.name else MessageStatus.READ.name, outgoing = out,
            ackedStatus = if (out) null else MessageStatus.READ.name,
        )
    }

    val image: ImageEnvelope by lazy {
        val text = javaClass.classLoader!!.getResource("contract/v1/examples/image_payload.json")!!.readText()
        (MlsPayload.decode(text.toByteArray()) as MlsPayload.Decoded.Image).envelope
    }

    /**
     * A phone with 1:1 and group chats: texts both ways, a photo by reference, a call line, a local
     * "Missed call" line, a tombstone, a hidden tombstone, a reaction, group lines, a Clear chat
     * watermark, plus what never goes into a backup (an outbox row, a §13.3 marker, a gap row).
     */
    suspend fun fill(p: BackupPhone) {
        val m = p.db.messages()
        for (i in 0 until 6) m.insert(msg(DM, if (i % 2 == 0) PEER else ME, i))
        val img = msg(DM, PEER, 10, body = "a photo").copy(kind = MessageEntity.KIND_IMAGE, blobId = image.blob.blobId)
        m.insert(img)
        p.images.stored(img, image)
        val call = msg(DM, ME, 11, body = "Voice call · 0:42").copy(
            kind = MessageEntity.KIND_CALL, callId = "0b6c3a50-0000-4000-8000-000000000001",
            systemJson = ProtocolJson.encodeToString(JsonObject.serializer(), lk.codegen.risime.calls.CallEnvelope.toJson(lk.codegen.risime.calls.CallEnvelope.End("0b6c3a50-0000-4000-8000-000000000001", "hangup", durationS = 42))),
        )
        m.insert(call)
        m.insert(
            MessageEntity(
                clientMsgId = "local-missed:0b6c3a50-0000-4000-8000-000000000002", messageId = null, conversationId = DM, from = PEER, to = ME,
                body = "Missed voice call", serverTs = at(12), localTs = HistoryFixtures.ms(at(12)), status = MessageStatus.READ.name, outgoing = false,
                ackedStatus = MessageStatus.READ.name, kind = MessageEntity.KIND_CALL, callId = "0b6c3a50-0000-4000-8000-000000000002",
                systemJson = ProtocolJson.encodeToString(JsonObject.serializer(), lk.codegen.risime.calls.CallEnvelope.toJson(lk.codegen.risime.calls.CallEnvelope.End("0b6c3a50-0000-4000-8000-000000000002", "timeout"))),
            ),
        )
        val deleted = msg(DM, PEER, 13).copy(kind = MessageEntity.KIND_DELETED, body = "", deletedBy = PEER)
        m.insert(deleted)
        val hiddenId = HistoryFixtures.tuuid(HistoryFixtures.ms(at(14)), 14)
        p.db.deletes().putDeletedId(DeletedIdEntity(hiddenId, DM, PEER, false, at(15), "everyone", 1))
        val target = msg(DM, PEER, 1)
        lk.codegen.risime.data.ReactionStore(p.db.reactions()).applyConfirmed(DM, target.messageId!!, ME, "👍", "add", at(16), HistoryFixtures.tuuid(HistoryFixtures.ms(at(16)), 16), "cm-r16")
        // Never in a backup: an outbox row, a marker, a gap row.
        m.insert(msg(DM, ME, 20, status = MessageStatus.PENDING.name).copy(messageId = null, clientMsgId = "pending-20"))
        m.upsertSystemLine(lk.codegen.risime.data.HistoryMarkers.row(DM, SystemLine.HISTORY_GAP, at(0), 0))

        p.db.groups().upsert(GroupEntity(GRP, "Site team", "admin", GroupEntity.STATE_ACTIVE, ME, null, 3, 4, null, null, 1))
        p.db.groups().upsertMembers(
            listOf(
                GroupMemberEntity(GRP, ME, "Me", null, "admin", "user", "active", null),
                GroupMemberEntity(GRP, PEER, "Kamal", "+94771234568", "member", "user", "active", null),
                GroupMemberEntity(GRP, THIRD, "Nimal", "+94771234569", "member", "user", "active", null),
            ),
        )
        m.insert(
            MessageEntity(
                clientMsgId = "sys:c1a2b3eb-a0b1-11f0-8000-0242ac120002", messageId = null, conversationId = GRP, from = ME, to = GRP,
                body = "You added Kamal", serverTs = null, localTs = HistoryFixtures.ms(at(30)), status = MessageStatus.READ.name, outgoing = false,
                kind = MessageEntity.KIND_SYSTEM, systemJson = SystemLine("added", ME, listOf(PEER)).encode(),
            ),
        )
        for (i in 31 until 36) m.insert(msg(GRP, if (i % 2 == 0) ME else THIRD, i))
        // A cleared chat: watermark after its first message.
        val third = dmConversationId(ME, THIRD)
        m.insert(msg(third, THIRD, 41))
        p.db.deletes().putChatState(ChatStateEntity(third, TimeUuid.ticks(msg(third, THIRD, 40).messageId), false))
        p.db.contacts().upsertAll(listOf(ContactEntity("+94771234568", "Kamal", "Co", PEER, true)))
    }

    /** Text rows only for counting what a backup carries ([fill] minus the outbox row). */
    fun json(s: String): JsonObject = ProtocolJson.parseToJsonElement(s) as JsonObject

    fun type(line: String): String = (json(line)["type"] as JsonPrimitive).content
}
