package lk.codegen.risime.data.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lk.codegen.risime.net.ProtocolJson
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Envelope encryption for the stored token set (decision 014):
 * a fresh random AES-256-GCM data key per write, wrapped by an RSA-OAEP key whose private half
 * lives in the Android Keystore behind BiometricPrompt. Wrapping uses only the public key, so
 * rotated refresh tokens are stored without a prompt; unwrapping needs an authenticated Cipher.
 *
 * Blob: "RME1" | u16 wrappedKeyLen | wrappedKey | 12-byte IV | AES-GCM ciphertext+tag.
 */
object Envelope {
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'M'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    /** RSA/ECB/OAEPWithSHA-256AndMGF1Padding with MGF1-SHA1 (what the Keystore supports everywhere). */
    const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    val OAEP_SPEC = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    fun seal(plain: ByteArray, wrap: (ByteArray) -> ByteArray): ByteArray {
        val dataKey = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        aes.updateAAD(MAGIC)
        val ct = aes.doFinal(plain)
        val wrapped = wrap(dataKey)
        dataKey.fill(0)
        require(wrapped.size <= 0xFFFF)
        return ByteBuffer.allocate(MAGIC.size + 2 + wrapped.size + IV_LEN + ct.size)
            .put(MAGIC).putShort(wrapped.size.toShort()).put(wrapped).put(iv).put(ct).array()
    }

    /** @throws IllegalArgumentException for a malformed blob; javax.crypto exceptions if tampered. */
    fun open(blob: ByteArray, unwrap: (ByteArray) -> ByteArray): ByteArray {
        val b = ByteBuffer.wrap(blob)
        require(blob.size > MAGIC.size + 2 + IV_LEN) { "blob too short" }
        val magic = ByteArray(MAGIC.size).also { b.get(it) }
        require(magic.contentEquals(MAGIC)) { "unknown blob format" }
        val wrappedLen = b.short.toInt() and 0xFFFF
        require(b.remaining() > wrappedLen + IV_LEN) { "blob truncated" }
        val wrapped = ByteArray(wrappedLen).also { b.get(it) }
        val iv = ByteArray(IV_LEN).also { b.get(it) }
        val ct = ByteArray(b.remaining()).also { b.get(it) }
        val dataKey = unwrap(wrapped)
        try {
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
            aes.updateAAD(MAGIC)
            return aes.doFinal(ct)
        } finally {
            dataKey.fill(0)
        }
    }

    /** The wrapped data key inside a blob (what the authenticated Cipher must decrypt). */
    fun wrappedKeyOf(blob: ByteArray): ByteArray {
        val b = ByteBuffer.wrap(blob)
        b.position(MAGIC.size)
        val len = b.short.toInt() and 0xFFFF
        return ByteArray(len).also { b.get(it) }
    }
}

/** The long-lived secrets: offline refresh token and ID token (for end_session). Never logged. */
@Serializable
data class StoredTokens(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("id_token") val idToken: String? = null,
    val issuer: String,
    @SerialName("client_id") val clientId: String,
) {
    override fun toString() = "StoredTokens(issuer=$issuer, clientId=$clientId, <secrets hidden>)"
}

/**
 * The Keystore key pair, behind an interface so the envelope logic is JVM-testable.
 * Implementations: [KeystoreWrappingKey] (device), a plain JVM RSA pair in tests.
 */
interface WrappingKey {
    /** Creates the key pair if missing. False if the device can't (no secure lock screen). */
    fun ensure(): Boolean

    fun exists(): Boolean

    /** Encrypt a data key with the public key (no user authentication). */
    fun wrap(dataKey: ByteArray): ByteArray

    /**
     * A Cipher initialised for unwrapping, to be authenticated through BiometricPrompt's
     * CryptoObject. Null when the key is gone or permanently invalidated (new enrolment).
     */
    fun unwrapCipher(): Cipher?

    fun delete()
}

/** Sealed token storage in one app-private file. */
class TokenVault(private val file: File, private val key: WrappingKey) {
    fun hasTokens(): Boolean = file.isFile && key.exists()

    /** Seals and stores [tokens]. False when the device can't hold an auth-bound key. */
    fun store(tokens: StoredTokens): Boolean {
        if (!key.ensure()) return false
        val plain = ProtocolJson.encodeToString(StoredTokens.serializer(), tokens).toByteArray()
        val blob = Envelope.seal(plain, key::wrap)
        plain.fill(0)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        return true
    }

    /** The Cipher to authenticate, or null if there's nothing usable (then: browser sign-in). */
    fun unlockCipher(): Cipher? = if (file.isFile) key.unwrapCipher() else null

    /** Opens the stored set with a Cipher that BiometricPrompt has authenticated. */
    fun open(authenticated: Cipher): StoredTokens? = runCatching {
        val plain = Envelope.open(file.readBytes()) { authenticated.doFinal(it) }
        ProtocolJson.decodeFromString(StoredTokens.serializer(), plain.decodeToString())
    }.getOrNull()

    /** Logout or invalidated key: forget everything. */
    fun clear() {
        file.delete()
        key.delete()
    }
}
