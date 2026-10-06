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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

    class Device(val ref: DeviceRef, val conn: SQLiteConnection, val engine: MlsEngine, private val connLock: ReentrantLock) {
        /** The app's outer transaction around one unit of work (holds the connection's lock throughout). */
        fun <T> transaction(block: () -> T): T = connLock.withLock {
            conn.execSQL("BEGIN")
            try {
                val r = block()
                conn.execSQL("COMMIT")
                r
            } catch (t: Throwable) {
                conn.execSQL("ROLLBACK")
                throw t
            }
        }

        fun keyPackage(): ClaimedKeyPackage = ClaimedKeyPackage(ref, engine.createKeyPackages(1).single())

        fun close() = conn.close()
    }

    /** [attest] = sign the attestation with the test attestor (false: the live server attests). */
    fun device(userId: String, deviceId: String, trusted: List<String> = listOf(attestor.publicJwk()), attest: Boolean = true): Device {
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
        // A bundled SQLiteConnection is not thread-safe: confine it with a per-device lock held for
        // the whole transaction (the role Room's transaction lock plays in the app). Join the
        // caller's transaction (same thread, re-entrant), or open one.
        val connLock = ReentrantLock()
        val runInTx: (() -> Any?) -> Any? = { block ->
            connLock.withLock {
                if (conn.inTransaction()) {
                    block()
                } else {
                    conn.execSQL("BEGIN")
                    val r = try { block() } catch (t: Throwable) { conn.execSQL("ROLLBACK"); throw t }
                    conn.execSQL("COMMIT")
                    r
                }
            }
        }
        val engine = UniffiMlsEngineFactory().open(sql, KvSealer(ByteArray(32) { 9 }), runInTx, userId, deviceId, trusted)
        if (attest) engine.setAttestation(attestor.attest(userId, deviceId, engine.signatureKey(), (System.currentTimeMillis() / 1000).toULong()))
        return Device(DeviceRef(userId, deviceId), conn, engine, connLock)
    }
}
