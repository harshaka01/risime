package lk.codegen.risime.crypto

import lk.codegen.risime.data.media.MediaCrypto
import lk.codegen.risime.data.media.MediaCryptoException
import lk.codegen.risime.data.media.SealedBlob
import java.io.File

/** [MediaCrypto] over risime-mls-ffi's media API (crypto/README "Media API"). Found by name from main. */
class UniffiMediaCrypto : MediaCrypto {
    private inline fun <T> wrap(f: () -> T): T = try {
        f()
    } catch (e: RisiMediaException) {
        throw MediaCryptoException(e.javaClass.simpleName, e.message)
    }

    override fun encryptFile(src: File, dst: File): SealedBlob = wrap {
        val s = mediaEncryptFile(src.absolutePath, dst.absolutePath)
        SealedBlob(s.key, s.alg, s.plainSize.toLong(), s.cipherSize.toLong(), s.sha256)
    }

    override fun decryptFile(src: File, key: ByteArray, alg: String, plainSize: Long, cipherSize: Long, sha256: ByteArray): ByteArray = wrap {
        mediaDecryptFile(src.absolutePath, key, alg, plainSize.toULong(), cipherSize.toULong(), sha256)
    }

    override fun decryptFileToFile(src: File, dst: File, key: ByteArray, alg: String, plainSize: Long, cipherSize: Long, sha256: ByteArray) = wrap {
        mediaDecryptFileToFile(src.absolutePath, dst.absolutePath, key, alg, plainSize.toULong(), cipherSize.toULong(), sha256)
    }

    override fun verifiedPrefix(src: File, key: ByteArray, alg: String, cipherSize: Long): Long = wrap {
        mediaVerifiedPrefix(src.absolutePath, key, alg, cipherSize.toULong()).toLong()
    }
}
