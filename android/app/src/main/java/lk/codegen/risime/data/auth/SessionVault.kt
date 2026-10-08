package lk.codegen.risime.data.auth

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import lk.codegen.risime.net.ProtocolJson
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import javax.crypto.AEADBadTagException
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

        /** A blob that can never be opened (key gone, corrupt, invalidated): signed out, chats kept. */
        data class Unreadable(val reason: String) : Load

        /**
         * The Keystore or the file system failed this time (keystore2/StrongBox busy right after
         * boot, a push-started process): nothing is deleted; read again later.
         */
        data class Transient(val reason: String) : Load
    }

    fun exists(): Boolean = file.isFile

    /**
     * Seals and stores [tokens] (no prompt). True only once the blob is durable: written, fsynced,
     * atomically renamed over the old one (and the directory synced). False when the key can't be
     * read or created now, or the write failed; the previous blob is then untouched.
     */
    fun store(tokens: StoredTokens): Boolean = try {
        val k = key.getOrCreate()
        if (k == null) {
            false
        } else {
            val plain = ProtocolJson.encodeToString(StoredTokens.serializer(), tokens).toByteArray()
            val blob = seal(plain, k, key.alias)
            plain.fill(0)
            writeDurably(blob)
            true
        }
    } catch (e: Exception) {
        Log.w("RisiMe", "RisiMe auth: vault store failed: ${e.javaClass.simpleName}")
        false
    }

    private fun writeDurably(blob: ByteArray) {
        val dir = file.absoluteFile.parentFile
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(blob)
            out.flush()
            out.fd.sync()
        }
        // rename(2): an atomic replace on the same file system; there is never a moment without a blob.
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // The rename is durable once the directory entry is synced (best effort: not every FS allows it).
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    fun load(): Load {
        if (!file.isFile) return Load.Empty
        val k = try {
            key.get()
        } catch (e: Exception) {
            // Not "key missing": the Keystore didn't answer this time. Never cleared for this.
            return Load.Transient("key: ${e.javaClass.simpleName}")
        } ?: return Load.Unreadable("key missing")
        val bytes = try {
            file.readBytes()
        } catch (e: java.io.IOException) {
            return Load.Transient("read: ${e.javaClass.simpleName}")
        }
        return try {
            val plain = open(bytes, k, key.alias)
            Load.Tokens(ProtocolJson.decodeFromString(StoredTokens.serializer(), plain.decodeToString()))
        } catch (e: Exception) {
            classify(e)
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

        /**
         * Only a blob that can never open again is [Load.Unreadable]: a failed tag (corrupt, or sealed
         * under another key), an invalidated key, an unknown format or broken contents. Anything
         * else (a Keystore/provider error, I/O) is [Load.Transient]: kept and read again later.
         */
        fun classify(e: Exception): Load = when (e) {
            is AEADBadTagException,
            is KeyPermanentlyInvalidatedException,
            is IllegalArgumentException, // too short / another format; kotlinx SerializationException
            -> Load.Unreadable(e.javaClass.simpleName)
            else -> Load.Transient(e.javaClass.simpleName)
        }

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

    /** Null only when no key exists under [alias]; throws when the Keystore can't answer now. */
    fun get(): SecretKey?

    /** The key, created when none exists. Throws (never replaces it) when one exists but can't be read now. */
    fun getOrCreate(): SecretKey?

    fun delete()
}

/**
 * AES-256-GCM in the Android Keystore: StrongBox when available (API 28+), else the TEE. No user
 * authentication, not invalidated by a biometric enrolment, usable while the phone is locked.
 */
class KeystoreVaultKey(override val alias: String = "risime_session_aes") : VaultKey {
    private val ks: KeyStore get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun get(): SecretKey? {
        val store = ks // a KeyStoreException / IOException here: the Keystore isn't answering (thrown on)
        if (!store.containsAlias(alias)) return null
        // A key that exists but can't be loaded now (keystore2 busy, UnrecoverableKeyException) throws:
        // the caller keeps the blob and retries. It is never reported as missing.
        return store.getKey(alias, null) as? SecretKey ?: throw java.security.KeyStoreException("not a secret key")
    }

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
