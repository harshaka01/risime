package lk.codegen.risime.data.media

import java.io.File

/** What [MediaCrypto.encryptFile] produced: the envelope's `enc` and `blob` size/digest (§14.3). */
class SealedBlob(val key: ByteArray, val alg: String, val plainSize: Long, val cipherSize: Long, val sha256: ByteArray)

/** Any media failure (integrity, format, unsupported, too large, I/O): "Couldn't open this photo". */
class MediaCryptoException(val kind: String, message: String?) : Exception(message)

/**
 * The `A256GCM-S64K` blob format, implemented only by the Rust core (risime-mls `media`, through
 * UniFFI). Keys are generated inside [encryptFile]; nothing here accepts a caller-chosen key.
 */
interface MediaCrypto {
    /** Streams [src] into the blob file [dst] (atomic) under a fresh random key. */
    fun encryptFile(src: File, dst: File): SealedBlob

    /** Decrypt-on-display: returns only after size, SHA-256, every segment, the final flag and the padding verified. */
    fun decryptFile(src: File, key: ByteArray, alg: String, plainSize: Long, cipherSize: Long, sha256: ByteArray): ByteArray

    /** As [decryptFile], into [dst] with constant memory ([dst] appears only after every check). */
    fun decryptFileToFile(src: File, dst: File, key: ByteArray, alg: String, plainSize: Long, cipherSize: Long, sha256: ByteArray)

    /** For a `Range` resume: the bytes of leading whole segments of [src] that verify in order. */
    fun verifiedPrefix(src: File, key: ByteArray, alg: String, cipherSize: Long): Long

    companion object {
        private const val IMPL = "lk.codegen.risime.crypto.UniffiMediaCrypto"

        /** The core's implementation, or null in builds without the Rust toolchain (then no `images` capability). */
        fun get(): MediaCrypto? = runCatching { Class.forName(IMPL).getDeclaredConstructor().newInstance() as MediaCrypto }.getOrNull()
    }
}
