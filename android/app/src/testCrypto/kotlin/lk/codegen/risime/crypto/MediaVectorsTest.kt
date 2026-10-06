package lk.codegen.risime.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.data.media.MediaCryptoException
import lk.codegen.risime.data.media.MediaFormat
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** contract/v1/media_vectors.json through the real core (host build via JNA), and the Kotlin size math against it. */
class MediaVectorsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val crypto = UniffiMediaCrypto()

    @Before
    fun host() = RealMls.assumeHostLibrary()

    private val vectors: JsonObject by lazy {
        ProtocolJson.parseToJsonElement(File(System.getProperty("risime.contract"), "media_vectors.json").readText()).jsonObject
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun file(bytes: ByteArray) = tmp.newFile().also { it.writeBytes(bytes) }

    @Test
    fun positiveVectorsDecryptAndSizesAgree() {
        val pos = vectors["positive"]!!.jsonArray.map { it.jsonObject }
        assertEquals(5, pos.size)
        for (v in pos) {
            val name = v.s("name")
            val cipher = hex(v.s("cipher"))
            val plainSize = v.s("plain_size").toLong()
            val cipherSize = v.s("cipher_size").toLong()
            assertEquals(name, cipherSize, cipher.size.toLong())
            assertEquals(name, cipherSize, MediaFormat.cipherSize(plainSize))
            assertEquals(name, cipherSize.toULong(), mediaCipherSize(plainSize.toULong()))
            assertEquals(name, v.s("padded_size").toLong(), MediaFormat.padme(plainSize))
            assertArrayEquals(name, hex(v.s("sha256")), MessageDigest.getInstance("SHA-256").digest(cipher))
            val f = file(cipher)
            val out = crypto.decryptFile(f, hex(v.s("key")), MediaFormat.ALG, plainSize, cipherSize, hex(v.s("sha256")))
            assertArrayEquals(name, hex(v.s("plain")), out)
            assertEquals(name, cipherSize, crypto.verifiedPrefix(f, hex(v.s("key")), MediaFormat.ALG, cipherSize))
            val dst = File(tmp.root, "$name.out")
            crypto.decryptFileToFile(f, dst, hex(v.s("key")), MediaFormat.ALG, plainSize, cipherSize, hex(v.s("sha256")))
            assertArrayEquals(name, hex(v.s("plain")), dst.readBytes())
        }
    }

    @Test
    fun negativeVectorsFailWithTheirError() {
        val neg = vectors["negative"]!!.jsonArray.map { it.jsonObject }
        assertEquals(9, neg.size)
        for (v in neg) {
            val name = v.s("name")
            val dst = File(tmp.root, "$name.out")
            try {
                crypto.decryptFileToFile(
                    file(hex(v.s("cipher"))), dst, hex(v.s("key")), MediaFormat.ALG,
                    v.s("plain_size").toLong(), v.s("cipher_size").toLong(), hex(v.s("sha256")),
                )
                fail("$name decrypted")
            } catch (e: MediaCryptoException) {
                assertEquals(name, v.s("expect"), e.kind)
            }
            assertFalse("$name released plaintext", dst.exists())
        }
    }

    @Test
    fun encryptRoundTripFreshKeys() {
        val plain = ByteArray(200_000) { (it * 7).toByte() }
        val src = file(plain)
        val a = crypto.encryptFile(src, File(tmp.root, "a.enc"))
        val b = crypto.encryptFile(src, File(tmp.root, "b.enc"))
        assertFalse(a.key.contentEquals(b.key))
        assertEquals(MediaFormat.ALG, a.alg)
        assertEquals(MediaFormat.cipherSize(plain.size.toLong()), a.cipherSize)
        assertArrayEquals(plain, crypto.decryptFile(File(tmp.root, "a.enc"), a.key, a.alg, a.plainSize, a.cipherSize, a.sha256))
        // A truncated download resumes from its verified prefix (whole segments only).
        val part = File(tmp.root, "a.part")
        part.writeBytes(File(tmp.root, "a.enc").readBytes().copyOf(100_000))
        assertEquals(65_552L, crypto.verifiedPrefix(part, a.key, a.alg, a.cipherSize))
        try {
            crypto.decryptFile(File(tmp.root, "a.enc"), b.key, a.alg, a.plainSize, a.cipherSize, a.sha256)
            fail("wrong key")
        } catch (e: MediaCryptoException) {
            assertTrue(e.kind == "Integrity")
        }
    }
}
