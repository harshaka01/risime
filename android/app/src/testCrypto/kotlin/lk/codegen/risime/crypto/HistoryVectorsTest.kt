package lk.codegen.risime.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.data.history.HistoryAad
import lk.codegen.risime.data.history.HistoryLimits
import lk.codegen.risime.data.media.MediaFormat
import lk.codegen.risime.data.mls.HistoryCtx
import lk.codegen.risime.data.mls.HistoryException
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** §17.3 contract/v1/history_vectors.json through the real core (host build via JNA), plus the app's own AAD parser and limits. */
class HistoryVectorsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val crypto = UniffiHistoryCrypto()

    @Before
    fun host() = RealMls.assumeHostLibrary()

    private val text: String by lazy { File(System.getProperty("risime.contract"), "history_vectors.json").readText() }
    private val vectors: JsonObject by lazy { ProtocolJson.parseToJsonElement(text).jsonObject }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun everyVectorCaseRunsInTheCore() {
        val n = crypto.vectorsCheck(text, tmp.newFolder())
        val expected = listOf("aad_h", "positive", "negative").sumOf { vectors[it]!!.jsonArray.size }
        assertTrue("checked $n of $expected cases", n >= expected - vectors["aad_h"]!!.jsonArray.size && n > 0)
    }

    @Test
    fun aadCasesAgreeBetweenTheAppAndTheCore() {
        for (c in vectors["aad_h"]!!.jsonArray.map { it.jsonObject }) {
            val bytes = hex(c.s("bytes"))
            val ok = c.s("expect") == "ok"
            assertEquals(c.s("name"), if (ok) c.s("request_id") else null, HistoryAad.decode(bytes))
            assertEquals(c.s("name"), if (ok) c.s("request_id") else null, crypto.aadDecode(bytes))
            if (ok) assertArrayEquals(c.s("name"), bytes, HistoryAad.encode(c.s("request_id")))
            if (ok) assertArrayEquals(c.s("name"), bytes, crypto.aadEncode(c.s("request_id")))
        }
    }

    @Test
    fun appLimitsEqualTheCore() {
        val l = historyLimits()
        assertEquals(HistoryLimits.LABEL, l.label)
        assertEquals(HistoryLimits.ALG, l.alg)
        assertEquals(HistoryLimits.RPK_LEN, l.rpkLen.toInt())
        assertEquals(HistoryLimits.HPKE_ENC_LEN, l.hpkeEncLen.toInt())
        assertEquals(HistoryLimits.SEALED_KEY_LEN, l.sealedKeyLen.toInt())
        assertEquals(HistoryLimits.MAX_PARTS, l.maxParts.toInt())
        assertEquals(HistoryLimits.MAX_PLAIN, l.maxPlainSize.toLong())
        // The positive vectors' blob sizes follow the app's Padmé math.
        for (v in vectors["positive"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(v.s("name"), hex(v.s("cipher")).size.toLong(), MediaFormat.cipherSize(v.s("plain_size").toLong()))
        }
    }

    @Test
    fun sealOpenRoundTripThroughTheEngineAndForget() {
        val requester = RealMls.device("u-alice", "d-new")
        try {
            val rid = UUID.randomUUID().toString()
            val rpk = requester.transaction { requester.engine.historyKeygen(rid) }
            assertEquals(32, rpk.size)
            assertArrayEquals(rpk, requester.engine.historyPublicKey(rid))
            assertTrue(rid in requester.engine.historyOpenRequests())
            val plain = "{\"v\":1}\n".repeat(1000).toByteArray()
            val ctx = HistoryCtx(rid, "dm:a_b", "u-alice/d-new", "u-alice/d-old", 1, 1)
            val out = File(tmp.root, "part1")
            val sealed = crypto.seal(rpk, ctx, plain, out)
            assertEquals(32, sealed.hpkeEnc.size)
            assertEquals(48, sealed.sealedKey.size)
            assertEquals(out.length(), sealed.size)
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(out.readBytes()), sealed.sha256)
            val openCtx = HistoryCtx(rid, "dm:a_b", "u-alice/d-new", "u-alice/d-old", 1, 1, sealed.sha256, sealed.plainSize)
            assertArrayEquals(plain, requester.engine.historyOpen(rid, openCtx, sealed.hpkeEnc, sealed.sealedKey, out))
            // A wrong provider identity (info) fails.
            try {
                requester.engine.historyOpen(rid, HistoryCtx(rid, "dm:a_b", "u-alice/d-new", "u-mallory/d-x", 1, 1, sealed.sha256, sealed.plainSize), sealed.hpkeEnc, sealed.sealedKey, out)
                fail("opened with the wrong provider")
            } catch (e: HistoryException) {
                assertEquals(HistoryException.Kind.OpenFailed, e.kind)
            }
            requester.engine.historyForget(rid)
            assertNull(requester.engine.historyPublicKey(rid))
            try {
                requester.engine.historyOpen(rid, openCtx, sealed.hpkeEnc, sealed.sealedKey, out)
                fail("opened after forget")
            } catch (e: HistoryException) {
                assertEquals(HistoryException.Kind.UnknownRequest, e.kind)
            }
        } finally {
            requester.close()
        }
    }

    @Test
    fun keygenRolledBackWithTheTransactionLeavesNoKey() {
        val d = RealMls.device("u-bob", "d-1")
        try {
            val rid = UUID.randomUUID().toString()
            runCatching { d.transaction { d.engine.historyKeygen(rid); error("the request row write failed") } }
            assertNull(d.engine.historyPublicKey(rid))
        } finally {
            d.close()
        }
    }
}
