package lk.codegen.risime.data.history

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** §17.3 the `'H'` AAD and §17.6 strict envelope validation (anything else is dropped). */
class HistoryEnvelopeTest {
    private val rid = "3f6c2a1e-8b4d-4c7a-9e1f-5a2b3c4d5e6f"

    private fun example(name: String): JsonObject =
        ProtocolJson.parseToJsonElement(javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()).jsonObject

    private fun JsonObject.with(path: List<String>, v: kotlinx.serialization.json.JsonElement?): JsonObject {
        val k = path.first()
        if (path.size == 1) return JsonObject(if (v == null) this - k else this + (k to v))
        return JsonObject(this + (k to (this[k] as JsonObject).with(path.drop(1), v)))
    }

    private fun b64(n: Int) = JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(n) { 1 }))

    @Test fun aadRoundTripAndMalformedForms() {
        val aad = HistoryAad.encode(rid)
        assertEquals(18, aad.size)
        assertEquals(0x01.toByte(), aad[0])
        assertEquals(0x48.toByte(), aad[1])
        assertEquals(rid, HistoryAad.decode(aad))
        assertNull(HistoryAad.decode(aad.copyOf(17)))
        assertNull(HistoryAad.decode(aad + 0))
        assertNull(HistoryAad.decode(aad.copyOf().also { it[1] = 0x44 }))
        assertNull(HistoryAad.decode(aad.copyOf().also { it[0] = 0x02 }))
        assertNull(HistoryAad.decode(ByteArray(0)))
        // Upper-case ids decode to the canonical lowercase form.
        assertEquals(rid, HistoryAad.decode(HistoryAad.encode(rid.uppercase())))
    }

    @Test fun requestEnvelopeStrictValidation() {
        val ok = example("history_request_payload.json")
        val env = HistoryRequestEnvelope.validate(ok)!!
        assertEquals(rid, env.requestId)
        assertArrayEquals(env.rpk, HistoryRequestEnvelope.validate(ProtocolJson.parseToJsonElement(env.encode().decodeToString()).jsonObject)!!.rpk)
        val bad = listOf(
            ok.with(listOf("v"), JsonPrimitive(2)),
            ok.with(listOf("request_id"), JsonPrimitive("not-a-uuid")),
            ok.with(listOf("hpke", "kem"), JsonPrimitive("p256")),
            ok.with(listOf("hpke", "pk"), b64(31)),
            ok.with(listOf("hpke", "pk"), b64(33)),
            ok.with(listOf("range"), null),
            ok.with(listOf("range", "from"), JsonPrimitive("yesterday")),
            ok.with(listOf("gap_count"), JsonPrimitive(-1)),
        )
        bad.forEachIndexed { i, o -> assertNull("case $i", HistoryRequestEnvelope.validate(o)) }
    }

    @Test fun shareEnvelopeStrictValidation() {
        val ok = example("history_share_payload.json")
        assertNotNull(HistoryShareEnvelope.validate(ok))
        val bad = listOf(
            ok.with(listOf("v"), JsonPrimitive(2)),
            ok.with(listOf("part"), JsonPrimitive(0)),
            ok.with(listOf("part"), JsonPrimitive(2)), // part > parts
            ok.with(listOf("parts"), JsonPrimitive(21)).with(listOf("part"), JsonPrimitive(21)),
            ok.with(listOf("enc", "alg"), JsonPrimitive("A128GCM")),
            ok.with(listOf("enc", "label"), JsonPrimitive("risime-media-v1")),
            ok.with(listOf("enc", "hpke_enc"), b64(31)),
            ok.with(listOf("enc", "sealed_key"), b64(32)),
            ok.with(listOf("blob", "sha256"), b64(31)),
            ok.with(listOf("blob", "size"), JsonPrimitive(1_048_833)), // not Padmé-consistent with plain_size
            ok.with(listOf("blob", "size"), JsonPrimitive(16L * 1024 * 1024 + 1)),
            ok.with(listOf("enc", "plain_size"), JsonPrimitive(0)),
            ok.with(listOf("count"), null),
        )
        bad.forEachIndexed { i, o -> assertNull("case $i", HistoryShareEnvelope.validate(o)) }
    }
}
