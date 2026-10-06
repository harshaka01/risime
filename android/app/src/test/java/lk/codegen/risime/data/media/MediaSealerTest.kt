package lk.codegen.risime.data.media

import lk.codegen.risime.data.mls.KvSealer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSealerTest {
    private val sealer = MediaSealer { KvSealer(ByteArray(32) { 3 }) }
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun keyAndThumbAreSealedAndBoundToTheirRow() {
        val enc = ImageEnc(MediaFormat.ALG, key, 1234)
        val sealed = sealer.sealEnc("c1", enc)
        // The key never appears in the clear in the stored bytes.
        assertFalse(sealed.toList().windowed(32).any { it.toByteArray().contentEquals(key) })
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains(java.util.Base64.getEncoder().encodeToString(key)))
        assertEquals(enc, sealer.openEnc("c1", sealed))
        // Swapped into another row, or read as a thumbnail: refused.
        assertTrue(runCatching { sealer.openEnc("c2", sealed) }.isFailure)
        assertTrue(runCatching { sealer.openThumb("c1", sealed) }.isFailure)
        // Another database key: refused.
        assertTrue(runCatching { MediaSealer { KvSealer(ByteArray(32) { 4 }) }.openEnc("c1", sealed) }.isFailure)

        val t = ImageThumb("image/jpeg", 128, 96, byteArrayOf(1, 2, 3))
        assertEquals(t, sealer.openThumb("c1", sealer.sealThumb("c1", t)))
    }
}
