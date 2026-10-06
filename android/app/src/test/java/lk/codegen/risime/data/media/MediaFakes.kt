package lk.codegen.risime.data.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.db.CachedMedia
import lk.codegen.risime.data.db.MediaDao
import lk.codegen.risime.data.db.MediaEntity
import java.io.File

/** In-memory MediaDao with the Room queries' semantics (joins read [messages]). */
class FakeMediaDao(private val messages: FakeMessageDao) : MediaDao {
    val rows = MutableStateFlow<Map<String, MediaEntity>>(emptyMap())

    init {
        messages.mediaState = { rows.value[it]?.state }
    }

    override suspend fun insert(m: MediaEntity): Long {
        if (m.clientMsgId in rows.value) return -1
        rows.value = rows.value + (m.clientMsgId to m)
        return 1
    }

    override suspend fun update(m: MediaEntity) {
        if (m.clientMsgId in rows.value) rows.value = rows.value + (m.clientMsgId to m)
    }

    override suspend fun get(clientMsgId: String) = rows.value[clientMsgId]

    override fun observe(clientMsgId: String): Flow<MediaEntity?> = rows.map { it[clientMsgId] }

    override fun forConversation(conversationId: String): Flow<List<MediaEntity>> = rows.map { m -> m.values.filter { it.conversationId == conversationId } }

    override suspend fun owedUploads() = rows.value.values.filter { it.outgoing && it.state in setOf("ENCRYPTED", "UPLOADING") }

    override suspend fun downloadable() = rows.value.values
        .filter { it.state in setOf("NONE", "DOWNLOADING") && it.blobId != null && messages.rows[it.clientMsgId] != null }
        .sortedByDescending { messages.rows[it.clientMsgId]!!.localTs }

    override suspend fun cached() = rows.value.values.filter { it.fileName != null && messages.rows[it.clientMsgId] != null }.map {
        CachedMedia(it.clientMsgId, it.fileName!!, it.blobSize, it.lastAccess, it.expiresAtEst, it.outgoing, it.state, messages.rows[it.clientMsgId]!!.status)
    }

    override suspend fun fileNames() = rows.value.values.mapNotNull { it.fileName }

    override suspend fun delete(clientMsgId: String): Int {
        if (clientMsgId !in rows.value) return 0
        rows.value = rows.value - clientMsgId
        return 1
    }
}

/**
 * A stand-in for the core in JVM unit tests: "verifies" a file against the blob it was made from
 * (whole 65 552-byte segments that match, in order). The real core runs in testCrypto.
 */
class FakeMediaCrypto(private val blobs: MutableMap<String, ByteArray> = mutableMapOf()) : MediaCrypto {
    var verifyCalls = 0

    fun register(key: ByteArray, blob: ByteArray) {
        blobs[key.contentToString()] = blob
    }

    override fun encryptFile(src: File, dst: File): SealedBlob {
        val plain = src.readBytes()
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val size = MediaFormat.cipherSize(plain.size.toLong())!!
        val blob = ByteArray(size.toInt()).also { java.util.Random(plain.size.toLong()).nextBytes(it) }
        register(key, blob)
        dst.writeBytes(blob)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
        return SealedBlob(key, MediaFormat.ALG, plain.size.toLong(), size, sha)
    }

    override fun decryptFile(src: File, key: ByteArray, alg: String, plainSize: Long, cipherSize: Long, sha256: ByteArray): ByteArray {
        if (verifiedPrefix(src, key, alg, cipherSize) != cipherSize) throw MediaCryptoException("Integrity", "fake")
        return ByteArray(plainSize.toInt())
    }

    override fun decryptFileToFile(src: File, dst: File, key: ByteArray, alg: String, plainSize: Long, cipherSize: Long, sha256: ByteArray) {
        dst.writeBytes(decryptFile(src, key, alg, plainSize, cipherSize, sha256))
    }

    override fun verifiedPrefix(src: File, key: ByteArray, alg: String, cipherSize: Long): Long {
        verifyCalls++
        val ref = blobs[key.contentToString()] ?: return 0
        val got = if (src.isFile) src.readBytes() else return 0
        if (got.contentEquals(ref)) return ref.size.toLong()
        val seg = 65_552
        var ok = 0
        while (ok + seg <= got.size && ok + seg < ref.size && got.copyOfRange(ok, ok + seg).contentEquals(ref.copyOfRange(ok, ok + seg))) ok += seg
        return ok.toLong()
    }
}
