package lk.codegen.risime.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.mls.CallKey
import lk.codegen.risime.data.mls.CallKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §20.6 K3/K4/K7/K10 key installation and rotation against a fake key provider. */
@OptIn(ExperimentalCoroutinesApi::class)
class FrameKeyRingTest {
    private fun keys(epoch: Long, vararg ids: String) = CallKeys(epoch, FrameKeyRing.keyIndex(epoch), ids.map { CallKey(it, ByteArray(32) { b -> (epoch + b + it.length).toByte() }) })

    @Test fun indexWrapsAtSixteen() {
        assertEquals(0, FrameKeyRing.keyIndex(16))
        assertEquals(15, FrameKeyRing.keyIndex(15))
        assertEquals(1, FrameKeyRing.keyIndex(17))
        assertEquals(15, FrameKeyRing.keyIndex((1L shl 32) + 15))
    }

    @Test fun installRotateOverlapWrapAndWipe() = runTest {
        val sink = FakeKeySink()
        val ring = FrameKeyRing(sink, backgroundScope, random = { ByteArray(32) { 7 } })
        val k1 = keys(1, "a/1", "b/1")
        val bytesOfK1 = k1.keys.map { it.key.copyOf() }
        assertTrue(ring.install(k1))
        assertEquals(FrameKeyRing.keyText(bytesOfK1[1]), sink.keys[1]!!["b/1"])
        assertTrue("keys wiped from memory after install", k1.keys.all { k -> k.key.all { it == 0.toByte() } })
        assertEquals(setOf("a/1", "b/1"), ring.roster)
        // K4: an older or equal epoch is never installed.
        assertFalse(ring.install(keys(1, "a/1")))
        assertFalse(ring.install(keys(0, "a/1")))
        assertEquals(listOf(1), sink.ownIndexes.toList())
        // Epoch 2 (b removed): own sending at 2 at once; index 1 kept 10 s, then random.
        assertTrue(ring.install(keys(2, "a/1")))
        assertEquals(listOf(1, 2), sink.ownIndexes.toList())
        assertEquals(setOf("a/1"), ring.roster)
        advanceTimeBy(9_000)
        assertEquals(FrameKeyRing.keyText(bytesOfK1[1]), sink.keys[1]!!["b/1"])
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(FrameKeyRing.keyText(ByteArray(32) { 7 }), sink.keys[1]!!["b/1"])
        assertEquals(setOf(2), ring.installedIndexes())
        // 16 epochs later the same index comes back: identities gone since then lose their key there.
        val k17 = keys(17, "a/1", "c/1")
        val c17 = FrameKeyRing.keyText(k17.keys[1].key)
        ring.install(k17)
        assertEquals(c17, sink.keys[1]!!["c/1"])
        assertTrue(ring.install(keys(33, "a/1")))
        assertEquals(1, ring.index)
        assertEquals(FrameKeyRing.keyText(ByteArray(32) { 7 }), sink.keys[1]!!["c/1"])
        // K10: everything overwritten at the end.
        ring.wipeAll()
        assertTrue(sink.keys.values.all { m -> m.values.all { it == FrameKeyRing.keyText(ByteArray(32) { 7 }) } })
        assertEquals(emptySet<String>(), ring.roster)
    }
}
