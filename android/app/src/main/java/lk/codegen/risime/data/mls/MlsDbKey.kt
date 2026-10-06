package lk.codegen.risime.data.mls

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps the 32-byte MLS database key (Keystore in the app, a plain AES key in tests). */
interface DbKeyWrapper {
    fun wrap(plain: ByteArray): ByteArray

    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * The database key that seals `mls_kv` values (decision 012 §6 as amended by 033): random, wrapped
 * by a Keystore AES-GCM key that needs **no user authentication** (background sync must decrypt),
 * stored in an app-private, backup-excluded file.
 */
class MlsDbKey(private val file: File, private val wrapper: DbKeyWrapper) {
    @Volatile private var cached: ByteArray? = null

    @Synchronized
    fun get(): ByteArray {
        cached?.let { return it }
        val key = if (file.isFile) {
            wrapper.unwrap(file.readBytes())
        } else {
            ByteArray(32).also(SecureRandom()::nextBytes).also { k ->
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(wrapper.wrap(k))
                tmp.renameTo(file)
            }
        }
        cached = key
        return key
    }

    /** Logout: the MLS state is wiped, so is its key. */
    @Synchronized
    fun destroy() {
        cached?.fill(0)
        cached = null
        file.delete()
    }
}

class KeystoreDbKeyWrapper(private val alias: String = "risime_mls_db") : DbKeyWrapper {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, wrapped, 0, 12))
        return c.doFinal(wrapped, 12, wrapped.size - 12)
    }
}

/** Room's SupportSQLiteDatabase as [KvSql] (use only inside the caller's withTransaction block). */
class SupportKvSql(private val db: androidx.sqlite.db.SupportSQLiteDatabase) : KvSql {
    override fun exec(sql: String, args: Array<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args)
    }

    override fun queryBlob(sql: String, args: Array<Any?>): ByteArray? =
        db.query(sql, args).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
}
