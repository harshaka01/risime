package lk.codegen.risime.data.auth

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import lk.codegen.risime.net.ProtocolJson
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The token vault of decision 064: the token set (refresh + ID token) sealed with AES-256-GCM under
 * a non-exportable Keystore key with **no user-authentication requirement** (StrongBox when the
 * phone has one, else the TEE). The background (push, workers) can always open it; nothing is
 * stored in plaintext, and the key can't leave the phone's secure hardware.
 *
 * Blob: "RMV2" | 12-byte IV | AES-GCM ciphertext+tag; AAD = "<alias>|v2" (binds the blob to its key
 * and format version).
 */
class SessionVault(private val file: File, private val key: VaultKey) {
    sealed interface Load {
        data class Tokens(val tokens: StoredTokens) : Load

        /** Nothing stored (never signed in under 064, or signed out). */
        data object Empty : Load

        /** A blob that can't be opened (key gone, corrupt): treated as signed out, chats kept. */
        data class Unreadable(val reason: String) : Load
    }

    fun exists(): Boolean = file.isFile

    /** Seals and stores [tokens] (no prompt). False when the phone can't create the key at all. */
    fun store(tokens: StoredTokens): Boolean {
        val k = key.getOrCreate() ?: return false
        return try {
            val plain = ProtocolJson.encodeToString(StoredTokens.serializer(), tokens).toByteArray()
            val blob = seal(plain, k, key.alias)
            plain.fill(0)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(blob)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            true
        } catch (e: Exception) {
            Log.w("RisiMe", "RisiMe auth: vault store failed: ${e.javaClass.simpleName}")
            false
        }
    }

    fun load(): Load {
        if (!file.isFile) return Load.Empty
        val k = key.get() ?: return Load.Unreadable("key missing")
        return try {
            val plain = open(file.readBytes(), k, key.alias)
            Load.Tokens(ProtocolJson.decodeFromString(StoredTokens.serializer(), plain.decodeToString()))
        } catch (e: Exception) {
            Load.Unreadable(e.javaClass.simpleName)
        }
    }

    fun clear() {
        file.delete()
        key.delete()
    }

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte())
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun aad(alias: String): ByteArray = "$alias|v2".toByteArray()

        /** The cipher picks a fresh random IV (Keystore keys refuse caller-chosen IVs). */
        fun seal(plain: ByteArray, k: SecretKey, alias: String): ByteArray {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.ENCRYPT_MODE, k)
            c.updateAAD(aad(alias))
            val ct = c.doFinal(plain)
            val iv = c.iv
            require(iv.size == IV_LEN)
            return ByteBuffer.allocate(MAGIC.size + IV_LEN + ct.size).put(MAGIC).put(iv).put(ct).array()
        }

        fun open(blob: ByteArray, k: SecretKey, alias: String): ByteArray {
            require(blob.size > MAGIC.size + IV_LEN) { "blob too short" }
            require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "unknown blob format" }
            val iv = blob.copyOfRange(MAGIC.size, MAGIC.size + IV_LEN)
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            c.updateAAD(aad(alias))
            return c.doFinal(blob, MAGIC.size + IV_LEN, blob.size - MAGIC.size - IV_LEN)
        }
    }
}

/** The vault's AES key, behind an interface so the vault is JVM-testable. */
interface VaultKey {
    val alias: String

    fun get(): SecretKey?

    fun getOrCreate(): SecretKey?

    fun delete()
}

/**
 * AES-256-GCM in the Android Keystore: StrongBox when available (API 28+), else the TEE. No user
 * authentication, not invalidated by a biometric enrolment, usable while the phone is locked.
 */
class KeystoreVaultKey(override val alias: String = "risime_session_aes") : VaultKey {
    private val ks: KeyStore get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun get(): SecretKey? = runCatching { ks.getKey(alias, null) as? SecretKey }.getOrNull()

    override fun getOrCreate(): SecretKey? {
        get()?.let { return it }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return generate(strongBox = true).also { Log.i("RisiMe", "RisiMe auth: vault key created (StrongBox)") }
            } catch (e: Exception) {
                // StrongBoxUnavailableException (no StrongBox), or a StrongBox that refuses AES-256.
                Log.i("RisiMe", "RisiMe auth: StrongBox unavailable (${e.javaClass.simpleName}): TEE key")
                runCatching { ks.deleteEntry(alias) }
            }
        }
        return try {
            generate(strongBox = false).also { Log.i("RisiMe", "RisiMe auth: vault key created (TEE)") }
        } catch (e: Exception) {
            Log.w("RisiMe", "RisiMe auth: vault key unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
        if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(b.build())
            generateKey()
        }
    }

    override fun delete() {
        runCatching { ks.deleteEntry(alias) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
