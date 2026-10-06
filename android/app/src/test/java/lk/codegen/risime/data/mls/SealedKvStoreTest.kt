package lk.codegen.risime.data.mls

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import lk.codegen.risime.data.db.Migration2To3
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** The sealed MLS store on real SQLite: savepoints nest inside the caller's transaction. */
class SealedKvStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val conn: SQLiteConnection = BundledSQLiteDriver().open(":memory:").also { c ->
        Migration2To3.SQL.take(1).forEach { c.execSQL(it) } // mls_kv
        c.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY)")
    }

    @After fun close() = conn.close()

    private val sql = object : KvSql {
        override fun exec(sql: String, args: Array<Any?>) {
            conn.prepare(sql).use { st ->
                args.forEachIndexed { i, a ->
                    when (a) {
                        is String -> st.bindText(i + 1, a)
                        is ByteArray -> st.bindBlob(i + 1, a)
                        is Long -> st.bindLong(i + 1, a)
                        null -> st.bindNull(i + 1)
                        else -> error("bind $a")
                    }
                }
                while (st.step()) Unit
            }
        }

        override fun queryBlob(sql: String, args: Array<Any?>): ByteArray? = conn.prepare(sql).use { st ->
            args.forEachIndexed { i, a -> if (a is String) st.bindText(i + 1, a) else st.bindBlob(i + 1, a as ByteArray) }
            if (st.step()) st.getBlob(0) else null
        }
    }

    private val key = ByteArray(32) { it.toByte() }
    private val kv = SealedKvStore(sql, KvSealer(key))
    private val k = "group".toByteArray()

    private fun rawValue(): ByteArray? = sql.queryBlob("SELECT value FROM mls_kv WHERE namespace = ? AND key = ?", arrayOf("ns", k))

    @Test fun valuesAreSealedAndBoundToTheirKey() {
        kv.put("ns", k, "ratchet-secret".toByteArray())
        assertArrayEquals("ratchet-secret".toByteArray(), kv.get("ns", k))
        assertFalse(String(rawValue()!!, Charsets.ISO_8859_1).contains("ratchet-secret"))
        // A value copied under another key fails authentication.
        sql.exec("INSERT INTO mls_kv (namespace, key, value) VALUES (?, ?, ?)", arrayOf("ns", "other".toByteArray(), rawValue()))
        assertTrue(runCatching { kv.get("ns", "other".toByteArray()) }.isFailure)
        // The wrong DB key can't open it.
        assertTrue(runCatching { SealedKvStore(sql, KvSealer(ByteArray(32))).get("ns", k) }.isFailure)
        kv.delete("ns", k)
        assertNull(kv.get("ns", k))
    }

    @Test fun nestedSavepointsInsideTheCallersTransaction() {
        conn.execSQL("BEGIN")
        conn.execSQL("INSERT INTO messages (id) VALUES ('m1')") // the decrypted message, same transaction
        kv.begin()
        kv.put("ns", k, byteArrayOf(1))
        kv.begin()
        kv.put("ns", k, byteArrayOf(2))
        kv.rollback() // inner change undone
        assertArrayEquals(byteArrayOf(1), kv.get("ns", k))
        kv.commit()
        assertEquals(0, kv.openSavepoints)
        conn.execSQL("ROLLBACK") // e.g. a crash before the cursor write: MLS state and message vanish together
        assertNull(kv.get("ns", k))
        conn.prepare("SELECT COUNT(*) FROM messages").use { st -> st.step(); assertEquals(0L, st.getLong(0)) }

        conn.execSQL("BEGIN")
        kv.begin(); kv.put("ns", k, byteArrayOf(3)); kv.commit()
        conn.execSQL("INSERT INTO messages (id) VALUES ('m2')")
        conn.execSQL("COMMIT")
        assertArrayEquals(byteArrayOf(3), kv.get("ns", k))
        assertTrue(runCatching { kv.commit() }.isFailure) // unbalanced
    }

    @Test fun dbKeyIsCreatedOnceWrappedAndDestroyed() {
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val wrapper = object : DbKeyWrapper {
            override fun wrap(plain: ByteArray): ByteArray {
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes) }
                return c.iv + c.doFinal(plain)
            }
            override fun unwrap(wrapped: ByteArray): ByteArray {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, wrapped, 0, 12))
                return c.doFinal(wrapped, 12, wrapped.size - 12)
            }
        }
        val f = File(tmp.root, "mls_dbkey.bin")
        val k1 = MlsDbKey(f, wrapper).get()
        assertEquals(32, k1.size)
        assertFalse(f.readBytes().contentEquals(k1)) // stored wrapped
        assertArrayEquals(k1, MlsDbKey(f, wrapper).get()) // a new process unwraps the same key
        MlsDbKey(f, wrapper).destroy()
        assertFalse(f.exists())
        assertFalse(MlsDbKey(f, wrapper).get().contentEquals(k1))
    }
}
