package lk.codegen.risime.data.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import javax.crypto.Cipher

/** The envelope with a plain JVM RSA-OAEP pair standing in for the Keystore key. */
class EnvelopeTest {
    @get:Rule val tmp = TemporaryFolder()

    private class JvmWrappingKey : WrappingKey {
        var pair: KeyPair? = null
        var canCreate = true
        var unwrapCalls = 0

        override fun ensure(): Boolean {
            if (pair == null && canCreate) pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            return pair != null
        }
        override fun exists() = pair != null
        override fun wrap(dataKey: ByteArray): ByteArray =
            Cipher.getInstance(Envelope.RSA_TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, pair!!.public, Envelope.OAEP_SPEC)
                doFinal(dataKey)
            }
        override fun unwrapCipher(): Cipher? = pair?.let {
            unwrapCalls++
            Cipher.getInstance(Envelope.RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, it.private, Envelope.OAEP_SPEC) }
        }
        override fun delete() { pair = null }
    }

    private val tokens = StoredTokens("refresh-" + "x".repeat(900), "id.token.sig", "https://risicloud.ai/realms/aoa", "risime")

    @Test fun sealOpenRoundTripWithFreshKeysAndIvs() {
        val k = JvmWrappingKey().also { it.ensure() }
        val plain = "secret".toByteArray()
        val a = Envelope.seal(plain, k::wrap)
        val b = Envelope.seal(plain, k::wrap)
        assertFalse(a.contentEquals(b)) // new data key + IV each time
        assertArrayEquals(plain, Envelope.open(a) { k.unwrapCipher()!!.doFinal(it) })
        assertEquals(256, Envelope.wrappedKeyOf(a).size) // RSA-2048 block
    }

    @Test fun tamperingAndGarbageAreRejected() {
        val k = JvmWrappingKey().also { it.ensure() }
        val blob = Envelope.seal("secret".toByteArray(), k::wrap)
        val flipped = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertTrue(runCatching { Envelope.open(flipped) { k.unwrapCipher()!!.doFinal(it) } }.isFailure)
        assertTrue(runCatching { Envelope.open(byteArrayOf(1, 2, 3)) { it } }.isFailure)
        // A different key pair can't open it.
        val other = JvmWrappingKey().also { it.ensure() }
        assertTrue(runCatching { Envelope.open(blob) { other.unwrapCipher()!!.doFinal(it) } }.isFailure)
    }

    @Test fun vaultStoresRotatedTokensWithoutUnlockAndOpensWithAuthenticatedCipher() {
        val k = JvmWrappingKey()
        val vault = TokenVault(File(tmp.root, "tokens.bin"), k)
        assertFalse(vault.hasTokens())
        assertTrue(vault.store(tokens))
        assertTrue(vault.store(tokens.copy(refreshToken = "rotated"))) // public-key wrap: no prompt
        assertEquals(0, k.unwrapCalls)
        assertFalse(File(tmp.root, "tokens.bin").readText(Charsets.ISO_8859_1).contains("rotated"))
        val cipher = vault.unlockCipher()!!
        assertEquals("rotated", vault.open(cipher)!!.refreshToken)
        assertFalse(tokens.toString().contains("refresh-"))
    }

    @Test fun noSecureKeyMeansMemoryOnlyAndClearForgetsEverything() {
        val k = JvmWrappingKey().apply { canCreate = false }
        val vault = TokenVault(File(tmp.root, "t.bin"), k)
        assertFalse(vault.store(tokens))
        assertNull(vault.unlockCipher())

        val k2 = JvmWrappingKey()
        val v2 = TokenVault(File(tmp.root, "t2.bin"), k2)
        v2.store(tokens)
        val c = v2.unlockCipher()!!
        v2.clear()
        assertFalse(v2.hasTokens())
        assertNull(v2.unlockCipher())
        assertNull(v2.open(c)) // file gone
        // A key invalidated after storing (new enrolment): open fails cleanly.
        val k3 = JvmWrappingKey()
        val v3 = TokenVault(File(tmp.root, "t3.bin"), k3)
        v3.store(tokens)
        k3.delete(); k3.ensure()
        assertNull(v3.open(k3.unwrapCipher()!!))
        assertNotEquals(null, v3.unlockCipher())
    }
}
