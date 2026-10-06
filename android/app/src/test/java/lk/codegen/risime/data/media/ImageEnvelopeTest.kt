package lk.codegen.risime.data.media

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ImageEnvelopeTest {
    private fun example(name: String): String =
        javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private fun obj(s: String) = ProtocolJson.parseToJsonElement(s).jsonObject

    private fun with(o: JsonObject, path: List<String>, v: kotlinx.serialization.json.JsonElement?): JsonObject {
        val k = path.first()
        if (path.size == 1) return JsonObject(if (v == null) o - k else o + (k to v))
        return JsonObject(o + (k to with(o[k]!!.jsonObject, path.drop(1), v)))
    }

    @Test
    fun padmeAndCipherSize() {
        assertEquals(1, MediaFormat.padme(1))
        assertEquals(17L, MediaFormat.cipherSize(1))
        assertEquals(1_311_040L, MediaFormat.cipherSize(1_300_000))
        assertEquals(417_904L, MediaFormat.cipherSize(412_345))
        assertNull(MediaFormat.cipherSize(0))
        assertTrue(MediaFormat.cipherSize(MediaFormat.MAX_MEDIA_PLAIN)!! <= MediaFormat.MAX_MEDIA_CIPHER)
        assertNull(MediaFormat.cipherSize(MediaFormat.MAX_MEDIA_PLAIN + 1))
        // Monotonic and always ≥ L + 16.
        var prev = 0L
        for (l in listOf(2L, 3, 100, 65_535, 65_536, 65_537, 200_000, 4_000_000)) {
            val c = MediaFormat.cipherSize(l)!!
            assertTrue(c >= l + 16 && c >= prev)
            prev = c
        }
    }

    @Test
    fun examplesRoundTripByteForByte() {
        for (name in listOf("image_payload.json", "image_payload_no_thumb.json", "image_payload_png.json")) {
            val env = (MlsPayload.decode(example(name).toByteArray()) as MlsPayload.Decoded.Image).envelope
            assertEquals(name, ProtocolJson.parseToJsonElement(example(name)), ProtocolJson.parseToJsonElement(env.encode().toString(Charsets.UTF_8)))
        }
    }

    @Test
    fun strictDrops() {
        val good = obj(example("image_payload.json"))
        assertNotNull(ImageEnvelope.validate(good))
        val b64 = { n: Int -> JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(n) { 1 })) }
        val bad = mapOf(
            "alg" to with(good, listOf("enc", "alg"), JsonPrimitive("A256GCM")),
            "key 31" to with(good, listOf("enc", "key"), b64(31)),
            "key 33" to with(good, listOf("enc", "key"), b64(33)),
            "plain 0" to with(good, listOf("enc", "plain_size"), JsonPrimitive(0)),
            "plain mismatch" to with(good, listOf("enc", "plain_size"), JsonPrimitive(1_400_000)),
            "size over cap" to with(with(good, listOf("blob", "size"), JsonPrimitive(MediaFormat.MAX_MEDIA_CIPHER + 16)), listOf("enc", "plain_size"), JsonPrimitive(MediaFormat.MAX_MEDIA_PLAIN + 1)),
            "sha 31" to with(good, listOf("blob", "sha256"), b64(31)),
            "mime gif" to with(good, listOf("mime"), JsonPrimitive("image/gif")),
            "w 0" to with(good, listOf("w"), JsonPrimitive(0)),
            "h 2049" to with(good, listOf("h"), JsonPrimitive(2049)),
            "w string" to with(good, listOf("w"), JsonPrimitive("2048")),
            "thumb png" to with(good, listOf("thumb", "mime"), JsonPrimitive("image/png")),
            "thumb 129" to with(good, listOf("thumb", "w"), JsonPrimitive(129)),
            "thumb 4097 bytes" to with(good, listOf("thumb", "data"), b64(4097)),
            "no blob id" to with(good, listOf("blob", "blob_id"), null),
            "caption number" to with(good, listOf("caption"), JsonPrimitive(5)),
        )
        for ((why, o) in bad) {
            assertNull(why, ImageEnvelope.validate(o))
            assertEquals(why, MlsPayload.Decoded.Ignored("image (malformed)"), MlsPayload.decode(o.toString().toByteArray()))
        }
        // Unknown fields are ignored; a thumbnail of exactly 4096 bytes is fine.
        assertNotNull(ImageEnvelope.validate(with(with(good, listOf("x_future"), JsonPrimitive(1)), listOf("thumb", "data"), b64(4096))))
    }

    @Test
    fun longCaptionDropsTheThumbnailToStayUnder22KiB() {
        val env = ImageEnvelope(
            BlobRef("b", 17, Base64.getEncoder().encodeToString(ByteArray(32))), ImageEnc(MediaFormat.ALG, ByteArray(32), 1),
            "image/jpeg", 10, 10, ImageThumb("image/jpeg", 10, 10, ByteArray(4096) { 7 }), "👨‍👩‍👧‍👦".repeat(300) + "\u0001".repeat(1700),
        )
        val bytes = env.encode()
        assertTrue(bytes.size <= ImageEnvelope.MAX_ENVELOPE_BYTES)
        val back = (MlsPayload.decode(bytes) as MlsPayload.Decoded.Image).envelope
        assertNull(back.thumb)
        assertEquals(env.caption, back.caption)
        // Short caption keeps the thumbnail; non-ASCII isn't escaped.
        val short = env.copy(caption = "Site visit ✓").encode()
        assertTrue(short.toString(Charsets.UTF_8).contains("✓"))
        assertNotNull((MlsPayload.decode(short) as MlsPayload.Decoded.Image).envelope.thumb)
    }

    @Test
    fun captionCutAt4096Graphemes() {
        val o = with(obj(example("image_payload.json")), listOf("caption"), JsonPrimitive("a".repeat(5000)))
        assertEquals(4096, ImageEnvelope.validate(o)!!.caption!!.length)
        assertNull(ImageEnvelope.validate(with(o, listOf("caption"), JsonPrimitive("   ")))!!.caption)
    }
}
