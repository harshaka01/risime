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
class SealedKvStore(private val sql: KvSql, private val sealer: KvSealer) {
    private var depth = 0

    fun get(namespace: String, key: ByteArray): ByteArray? =
        sql.queryBlob("SELECT value FROM mls_kv WHERE namespace = ? AND key = ?", arrayOf(namespace, key))
            ?.let { sealer.open(namespace, key, it) }

    fun put(namespace: String, key: ByteArray, value: ByteArray) =
        sql.exec("INSERT OR REPLACE INTO mls_kv (namespace, key, value) VALUES (?, ?, ?)", arrayOf(namespace, key, sealer.seal(namespace, key, value)))

    fun delete(namespace: String, key: ByteArray) =
        sql.exec("DELETE FROM mls_kv WHERE namespace = ? AND key = ?", arrayOf(namespace, key))

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
        /** Big-endian helper for callers that key by numbers (epochs, generations). */
        fun longKey(v: Long): ByteArray = ByteBuffer.allocate(8).putLong(v).array()
    }
}
