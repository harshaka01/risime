package lk.codegen.risime.data.auth

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * RSA-OAEP key pair in the Android Keystore. The private key requires BIOMETRIC_STRONG for every
 * use (plus DEVICE_CREDENTIAL on API 30+) and is invalidated by a new biometric enrolment.
 */
class KeystoreWrappingKey(private val alias: String = "risime_token_wrap") : WrappingKey {
    private val ks: KeyStore get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun exists(): Boolean = runCatching { ks.containsAlias(alias) }.getOrDefault(false)

    override fun ensure(): Boolean {
        if (exists()) return true
        return try {
            val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(3072, RSAKeyGenParameterSpec.F4))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= 30) {
                b.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).run {
                initialize(b.build())
                generateKeyPair()
            }
            true
        } catch (e: Exception) {
            // Typically: no secure lock screen / no enrolled biometric. Tokens then stay in memory only.
            Log.w("RisiMe", "auth-bound key unavailable: ${e.javaClass.simpleName}")
            false
        }
    }

    override fun wrap(dataKey: ByteArray): ByteArray {
        // Public-key operations through a software copy of the public key: no authentication needed.
        val pub = ks.getCertificate(alias).publicKey
        val soft = KeyFactory.getInstance(pub.algorithm).generatePublic(X509EncodedKeySpec(pub.encoded))
        val c = Cipher.getInstance(Envelope.RSA_TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, soft, Envelope.OAEP_SPEC)
        return c.doFinal(dataKey)
    }

    override fun unwrapCipher(): Cipher? = try {
        val key = ks.getKey(alias, null) as? PrivateKey ?: return null
        Cipher.getInstance(Envelope.RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, Envelope.OAEP_SPEC) }
    } catch (_: KeyPermanentlyInvalidatedException) {
        delete()
        null
    } catch (e: Exception) {
        Log.w("RisiMe", "unwrap cipher unavailable: ${e.javaClass.simpleName}")
        null
    }

    override fun delete() {
        runCatching { ks.deleteEntry(alias) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
