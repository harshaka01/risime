package lk.codegen.risime.crypto

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import lk.codegen.risime.data.db.Migration2To3
import lk.codegen.risime.data.mls.ClaimedKeyPackage
import lk.codegen.risime.data.mls.DeviceRef
import lk.codegen.risime.data.mls.KvSealer
import lk.codegen.risime.data.mls.KvSql
import lk.codegen.risime.data.mls.MlsEngine
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Real MLS devices for JVM tests: the host build of risime-mls-ffi via JNA, each device with its
 * own SQLite (bundled driver) holding the sealed mls_kv table, and a TestAttestor as the server.
 */
object RealMls {
    fun assumeHostLibrary() {
        val dir = System.getProperty("jna.library.path")
        assumeTrue("no host build of risime-mls-ffi", dir != null && File(dir, "libuniffi_risime.so").isFile)
    }

    val attestor: TestAttestor by lazy { TestAttestor.fromSeed(ByteArray(32) { 42 }) }

    class Device(val ref: DeviceRef, val conn: SQLiteConnection, val engine: MlsEngine) {
        /** The app's outer transaction around one unit of work. */
        fun <T> transaction(block: () -> T): T {
            conn.execSQL("BEGIN")
            try {
                val r = block()
                conn.execSQL("COMMIT")
                return r
            } catch (t: Throwable) {
                conn.execSQL("ROLLBACK")
                throw t
            }
        }

        fun keyPackage(): ClaimedKeyPackage = ClaimedKeyPackage(ref, engine.createKeyPackages(1).single())

        fun close() = conn.close()
    }

    fun device(userId: String, deviceId: String, trusted: List<String> = listOf(attestor.publicJwk())): Device {
        val conn = BundledSQLiteDriver().open(":memory:")
        conn.execSQL(Migration2To3.SQL.first()) // mls_kv
        val sql = object : KvSql {
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
        // Join the caller's transaction, or open one (like Room's runInTransaction).
        val runInTx: (() -> Any?) -> Any? = { block ->
            if (conn.inTransaction()) {
                block()
            } else {
                conn.execSQL("BEGIN")
                val r = try { block() } catch (t: Throwable) { conn.execSQL("ROLLBACK"); throw t }
                conn.execSQL("COMMIT")
                r
            }
        }
        val engine = UniffiMlsEngineFactory().open(sql, KvSealer(ByteArray(32) { 9 }), runInTx, userId, deviceId, trusted)
        engine.setAttestation(attestor.attest(userId, deviceId, engine.signatureKey(), (System.currentTimeMillis() / 1000).toULong()))
        return Device(DeviceRef(userId, deviceId), conn, engine)
    }
}
