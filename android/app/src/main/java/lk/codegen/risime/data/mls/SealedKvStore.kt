package lk.codegen.risime.data.mls

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The SQL the key-value store needs, over Room's SupportSQLiteDatabase in the app and a bundled
 * SQLite connection in JVM tests. Calls must happen on the connection that holds the caller's
 * Room transaction (decision 033).
 */
interface KvSql {
    fun exec(sql: String, args: Array<Any?> = emptyArray())

    fun queryBlob(sql: String, args: Array<Any?>): ByteArray?
}

/** AES-256-GCM per value; AAD binds the value to its (namespace, key) so rows can't be swapped. */
class KvSealer(dbKey: ByteArray) {
    private val key = SecretKeySpec(dbKey.copyOf().also { require(it.size == 32) { "db key must be 32 bytes" } }, "AES")
    private val random = SecureRandom()

    private fun aad(ns: String, k: ByteArray) = ns.toByteArray() + 0 + k

    fun seal(ns: String, k: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad(ns, k))
        return iv + c.doFinal(plain)
    }

    fun open(ns: String, k: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size > 12 + 16) { "sealed value too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
        c.updateAAD(aad(ns, k))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

/**
 * The MLS core's storage (the `KvStore` callback in phase B). `begin`/`commit`/`rollback` nest as
 * SQLite SAVEPOINTs **inside** the caller's transaction, so the MLS state change commits or rolls
 * back together with the decrypted message, the seen event and the cursor (contract §10.4).
 */
class SealedKvStore(private val sql: KvSql, private val sealer: KvSealer, private val chunkBytes: Int = CHUNK_BYTES) {
    private var depth = 0

    private fun raw(namespace: String, key: ByteArray): ByteArray? =
        sql.queryBlob("SELECT value FROM mls_kv WHERE namespace = ? AND key = ?", arrayOf(namespace, key))

    private fun putRaw(namespace: String, key: ByteArray, plain: ByteArray) =
        sql.exec("INSERT OR REPLACE INTO mls_kv (namespace, key, value) VALUES (?, ?, ?)", arrayOf(namespace, key, sealer.seal(namespace, key, plain)))

    private fun deleteRaw(namespace: String, key: ByteArray) =
        sql.exec("DELETE FROM mls_kv WHERE namespace = ? AND key = ?", arrayOf(namespace, key))

    /** The chunk count if the stored row is a chunk header (sealed headers are tiny), else null. */
    private fun chunkCount(namespace: String, key: ByteArray): Int? {
        val r = raw(namespace, key) ?: return null
        if (r.size > HEADER_SEALED_MAX) return null
        return headerCount(sealer.open(namespace, key, r))
    }

    private fun deleteChunks(namespace: String, key: ByteArray, count: Int) {
        for (i in 0 until count) deleteRaw(chunkNs(namespace), chunkKey(key, i))
    }

    fun get(namespace: String, key: ByteArray): ByteArray? {
        val plain = raw(namespace, key)?.let { sealer.open(namespace, key, it) } ?: return null
        val n = headerCount(plain) ?: return plain
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until n) {
            val ck = chunkKey(key, i)
            val part = raw(chunkNs(namespace), ck) ?: error("mls_kv chunk $i of $n missing")
            out.write(sealer.open(chunkNs(namespace), ck, part))
        }
        return out.toByteArray()
    }

    /**
     * Values over [chunkBytes] (512 KB) are split into sealed chunk rows (namespace `<ns>#c`, key
     * `<key> 00 <index>`) under a small header row, so no row comes near Android's 2 MB
     * `CursorWindow` (a 768-leaf ratchet tree is about 1.8 MB).
     */
    fun put(namespace: String, key: ByteArray, value: ByteArray) {
        chunkCount(namespace, key)?.let { deleteChunks(namespace, key, it) }
        if (value.size <= chunkBytes) return putRaw(namespace, key, value)
        val n = (value.size + chunkBytes - 1) / chunkBytes
        for (i in 0 until n) {
            putRaw(chunkNs(namespace), chunkKey(key, i), value.copyOfRange(i * chunkBytes, minOf(value.size, (i + 1) * chunkBytes)))
        }
        putRaw(namespace, key, header(n))
    }

    fun delete(namespace: String, key: ByteArray) {
        chunkCount(namespace, key)?.let { deleteChunks(namespace, key, it) }
        deleteRaw(namespace, key)
    }

    fun begin() {
        depth++
        sql.exec("SAVEPOINT ${name()}")
    }

    fun commit() {
        check(depth > 0) { "commit without begin" }
        sql.exec("RELEASE ${name()}")
        depth--
    }

    fun rollback() {
        check(depth > 0) { "rollback without begin" }
        sql.exec("ROLLBACK TO ${name()}")
        sql.exec("RELEASE ${name()}")
        depth--
    }

    val openSavepoints: Int get() = depth

    private fun name() = "risime_kv_$depth"

    companion object {
        const val CHUNK_BYTES = 512 * 1024

        /** Header plaintext: a 16-byte marker + the chunk count (core values never start with it in practice). */
        private val MAGIC = "risime-kv-chunks".toByteArray()
        private const val HEADER_SEALED_MAX = 12 + 16 + 16 + 4 + 8

        private fun header(n: Int): ByteArray = MAGIC + ByteBuffer.allocate(4).putInt(n).array()

        private fun headerCount(plain: ByteArray): Int? =
            if (plain.size == MAGIC.size + 4 && plain.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) ByteBuffer.wrap(plain, MAGIC.size, 4).int else null

        private fun chunkNs(ns: String) = "$ns#c"

        private fun chunkKey(key: ByteArray, i: Int) = key + 0 + ByteBuffer.allocate(4).putInt(i).array()

        /** Big-endian helper for callers that key by numbers (epochs, generations). */
        fun longKey(v: Long): ByteArray = ByteBuffer.allocate(8).putLong(v).array()
    }
}
