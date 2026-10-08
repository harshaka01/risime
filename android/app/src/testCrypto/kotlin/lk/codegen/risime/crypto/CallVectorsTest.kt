package lk.codegen.risime.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import lk.codegen.risime.calls.FrameKeyRing
import lk.codegen.risime.data.mls.CallKeysException
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * §20.6 contract/v1/call_vectors.json: every case through the real core (host build via JNA), the
 * app's own key text and key index against the vectors, an independent HKDF-Expand of every frame
 * key, and the frame keys of a real 3-device group (agreement, rekey on add and remove).
 */
class CallVectorsTest {
    @Before
    fun host() = RealMls.assumeHostLibrary()

    private val text: String by lazy { File(System.getProperty("risime.contract"), "call_vectors.json").readText() }
    private val vectors: JsonObject by lazy { ProtocolJson.parseToJsonElement(text).jsonObject }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    /** RFC 5869 HKDF-Expand-SHA256 with L = 32 (one block). */
    private fun hkdfExpand32(prk: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1)
        return mac.doFinal()
    }

    @Test
    fun everyVectorCaseRunsInTheCore() {
        val n = callVectorsCheck(text).toInt()
        val expected = vectors["frame_keys"]!!.jsonArray.size + vectors["exporter_cases"]!!.jsonObject["cases"]!!.jsonArray.size + vectors["negative"]!!.jsonArray.size
        assertEquals(29, n)
        assertEquals(expected, n)
        assertEquals(listOf("risime-call-v1"), exporterLabels())
    }

    @Test
    fun frameKeysKeyTextAndIndexAgreeWithTheApp() {
        var checked = 0
        for (c in vectors["frame_keys"]!!.jsonArray.map { it.jsonObject }) {
            val secret = hex(c.s("call_secret"))
            val epoch = c["epoch"]!!.jsonPrimitive.content.toULong().toLong()
            assertEquals(c.s("call_id"), c["key_index"]!!.jsonPrimitive.long.toInt(), FrameKeyRing.keyIndex(epoch))
            for (k in c["keys"]!!.jsonArray.map { it.jsonObject }) {
                val info = "risime-call-v1 frame".toByteArray() + byteArrayOf(0) + k.s("identity").toByteArray(Charsets.UTF_8)
                assertArrayEquals(k.s("identity"), hex(k.s("info")), info)
                val key = hkdfExpand32(secret, info)
                assertArrayEquals(k.s("identity"), hex(k.s("frame_key")), key)
                // livekit-android 2.29 takes the key's standard base64 text as the key material.
                assertEquals(k.s("frame_key_base64"), FrameKeyRing.keyText(key))
                checked++
            }
        }
        assertTrue(checked >= 8)
    }

    @Test
    fun aRealGroupDerivesTheSameKeysAndRekeysOnAddAndRemove() {
        val conv = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
        val callId = "3c8e1f4a-9b2d-4e7f-8a61-5d0c2b9e7f13"
        val a = RealMls.device("u-a", "a-phone")
        val b = RealMls.device("u-b", "b-phone")
        val c = RealMls.device("u-c", "c-phone")
        val d = RealMls.device("u-d", "d-phone")
        try {
            assertTrue(a.engine.callKeysSupported)
            val meta = lk.codegen.risime.net.GroupMeta(name = "Pilot", admins = listOf("u-a"))
            val create = a.transaction { a.engine.createGroupWithMeta(conv, 1, listOf(b.keyPackage(), c.keyPackage()), meta) }
            a.transaction { a.engine.commitAccepted(conv) }
            b.transaction { b.engine.joinFromWelcome(conv, 1, create.welcome!!) }
            c.transaction { c.engine.joinFromWelcome(conv, 1, create.welcome!!) }
            val ka = a.engine.callFrameKeys(conv, callId)
            val kb = b.engine.callFrameKeys(conv, callId)
            val kc = c.engine.callFrameKeys(conv, callId)
            assertEquals(1L, ka.epoch)
            assertEquals(1, ka.keyIndex)
            assertEquals(listOf("u-a/a-phone", "u-b/b-phone", "u-c/c-phone"), ka.identities)
            for (i in ka.keys.indices) {
                assertArrayEquals(ka.keys[i].key, kb.keys[i].key)
                assertArrayEquals(ka.keys[i].key, kc.keys[i].key)
            }
            // Another call id gives other keys; keys never show in toString (K10).
            assertNotEquals(FrameKeyRing.keyText(ka.keys[0].key), FrameKeyRing.keyText(a.engine.callFrameKeys(conv, "3c8e1f4a-9b2d-4e7f-8a61-5d0c2b9e7f14").keys[0].key))
            assertTrue(!ka.toString().contains(FrameKeyRing.keyText(ka.keys[0].key)))

            // Add D: a new epoch, new keys for everyone, D derives the same.
            val add = a.transaction { a.engine.changeGroupMembers(conv, listOf(d.keyPackage()), emptyList()) }
            a.transaction { a.engine.commitAccepted(conv) }
            assertTrue(b.transaction { b.engine.processCommit(conv, 1, add.commit) } is lk.codegen.risime.data.mls.CommitOutcome.Applied)
            assertTrue(c.transaction { c.engine.processCommit(conv, 1, add.commit) } is lk.codegen.risime.data.mls.CommitOutcome.Applied)
            d.transaction { d.engine.joinFromWelcome(conv, 1, add.welcome!!) }
            val a2 = a.engine.callFrameKeys(conv, callId)
            val d2 = d.engine.callFrameKeys(conv, callId)
            assertEquals(2L, a2.epoch)
            assertEquals(2, a2.keyIndex)
            assertEquals(4, a2.keys.size)
            assertTrue(a2.keys.zip(d2.keys).all { (x, y) -> x.identity == y.identity && x.key.contentEquals(y.key) })
            assertTrue(!a2.keys[0].key.contentEquals(ka.keys[0].key))

            // Remove C: C gets nothing for the new epoch; the others agree without C.
            val rm = a.transaction { a.engine.removeGroupUsers(conv, listOf("u-c")) }
            a.transaction { a.engine.commitAccepted(conv) }
            b.transaction { b.engine.processCommit(conv, 1, rm.commit) }
            c.transaction { c.engine.processCommit(conv, 1, rm.commit) }
            val a3 = a.engine.callFrameKeys(conv, callId)
            assertEquals(listOf("u-a/a-phone", "u-b/b-phone", "u-d/d-phone"), a3.identities.sorted())
            assertTrue(a3.keys.zip(b.engine.callFrameKeys(conv, callId).keys).all { (x, y) -> x.key.contentEquals(y.key) })
            try {
                c.engine.callFrameKeys(conv, callId)
                fail("a removed member derived keys")
            } catch (e: CallKeysException) {
                assertTrue(e.kind.toString(), e.kind == CallKeysException.Kind.UnknownGroup || e.kind == CallKeysException.Kind.RemovedFromGroup)
            }
            // Bad input.
            try {
                a.engine.callFrameKeys(conv, "not-a-uuid")
                fail("non-UUID call id")
            } catch (e: CallKeysException) {
                assertEquals(CallKeysException.Kind.Malformed, e.kind)
            }
            a3.wipe()
            assertTrue(a3.keys.all { k -> k.key.all { it == 0.toByte() } })
            assertEquals(Base64.getEncoder().encodeToString(ByteArray(32)), FrameKeyRing.keyText(ByteArray(32)))
        } finally {
            listOf(a, b, c, d).forEach { it.close() }
        }
    }
}
