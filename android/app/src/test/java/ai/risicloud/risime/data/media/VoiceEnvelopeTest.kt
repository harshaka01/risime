package ai.risicloud.risime.data.media

import ai.risicloud.risime.data.mls.MlsPayload
import ai.risicloud.risime.net.BlobRef
import ai.risicloud.risime.net.EnvelopeExtras
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.ReplyRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** v1.41 §37.2 strict validation, encoding, the waveform fold and the preview texts. */
class VoiceEnvelopeTest {
    private val b64 = Base64.getEncoder()
    private val wave = ByteArray(64) { (it * 4).toByte() }

    private fun env(
        plain: Long = 49_152,
        mime: String = VoiceEnvelope.MIME_OGG_OPUS,
        duration: Long = 12_400,
        waveform: ByteArray = wave,
        transcribe: Boolean? = null,
        requestId: String? = null,
        extras: EnvelopeExtras = EnvelopeExtras.NONE,
    ) = VoiceEnvelope(
        BlobRef("5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c", MediaFormat.cipherSize(plain)!!, b64.encodeToString(ByteArray(32) { 7 })),
        ImageEnc(MediaFormat.ALG, ByteArray(32) { 9 }, plain), mime, duration, waveform, transcribe, requestId, extras,
    )

    private fun obj(e: VoiceEnvelope): JsonObject = ProtocolJson.parseToJsonElement(e.encode().toString(Charsets.UTF_8)).jsonObject

    private fun with(o: JsonObject, k: String, v: kotlinx.serialization.json.JsonElement) = JsonObject(o + (k to v))

    @Test fun roundTripsEveryField() {
        val e = env(transcribe = false, extras = EnvelopeExtras(null, ReplyRef("c1a2b3e1-a0b1-11f0-8000-0242ac120002", "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e")))
        val d = MlsPayload.decode(e.encode()) as MlsPayload.Decoded.Voice
        assertEquals(e, d.envelope)
        assertEquals(false, d.envelope.transcribe)
        // Never a caption; `transcribe: true` is never written (absent = allowed).
        assertFalse(obj(env()).containsKey("transcribe"))
        assertFalse(obj(env()).containsKey("caption"))
        assertTrue(e.encode().size < ImageEnvelope.MAX_ENVELOPE_BYTES)
    }

    @Test fun strictValidationDropsMalformedNotes() {
        val good = obj(env())
        assertNotNull(VoiceEnvelope.validate(good))
        // §37.2 bad waveform (40 bytes, 65 bytes, not base64), mime, duration.
        assertNull(VoiceEnvelope.validate(with(good, "waveform", JsonPrimitive(b64.encodeToString(ByteArray(40))))))
        assertNull(VoiceEnvelope.validate(with(good, "waveform", JsonPrimitive(b64.encodeToString(ByteArray(65))))))
        assertNull(VoiceEnvelope.validate(with(good, "waveform", JsonPrimitive("not base64!"))))
        assertNull(VoiceEnvelope.validate(JsonObject(good - "waveform")))
        assertNull(VoiceEnvelope.validate(with(good, "mime", JsonPrimitive("audio/mpeg"))))
        assertNull(VoiceEnvelope.validate(with(good, "mime", JsonPrimitive("audio/ogg"))))
        assertNotNull(VoiceEnvelope.validate(with(good, "mime", JsonPrimitive(VoiceEnvelope.MIME_MP4))))
        assertNull(VoiceEnvelope.validate(with(good, "duration_ms", JsonPrimitive(499))))
        assertNull(VoiceEnvelope.validate(with(good, "duration_ms", JsonPrimitive(1_800_001))))
        assertNull(VoiceEnvelope.validate(with(good, "duration_ms", JsonPrimitive("12400"))))
        assertNull(VoiceEnvelope.validate(with(good, "duration_ms", JsonPrimitive(12.5))))
        assertNotNull(VoiceEnvelope.validate(with(good, "duration_ms", JsonPrimitive(1_800_000))))
        // §14.4: size must equal the cipher size of plain_size; key 32 bytes; alg exact.
        val blob = good["blob"]!!.jsonObject
        assertNull(VoiceEnvelope.validate(with(good, "blob", JsonObject(blob + ("size" to JsonPrimitive(49_248))))))
        val enc = good["enc"]!!.jsonObject
        assertNull(VoiceEnvelope.validate(with(good, "enc", JsonObject(enc + ("key" to JsonPrimitive(b64.encodeToString(ByteArray(16))))))))
        assertNull(VoiceEnvelope.validate(with(good, "enc", JsonObject(enc + ("alg" to JsonPrimitive("A128GCM"))))))
        // A malformed note never becomes a row: the decoder drops it.
        assertEquals(MlsPayload.Decoded.Ignored("voice (malformed)"), MlsPayload.decode(with(good, "waveform", JsonPrimitive("AAAA")).toString().toByteArray()))
    }

    @Test fun optionalFieldsAreLenient() {
        val good = obj(env())
        assertNull(VoiceEnvelope.validate(with(good, "transcribe", JsonPrimitive("false")))!!.transcribe)
        assertEquals(false, VoiceEnvelope.validate(with(good, "transcribe", JsonPrimitive(false)))!!.transcribe)
        assertNull(VoiceEnvelope.validate(with(good, "request_id", JsonPrimitive("nope")))!!.requestId)
        assertEquals(1, VoiceEnvelope.validate(with(good, "forwarded", JsonObject(mapOf("hops" to JsonPrimitive(1)))))!!.extras.forwardHops)
    }

    @Test fun metaKeepsWhatAResendNeeds() {
        val e = env(transcribe = false, requestId = "8c9d0e1f-2a3b-4c4d-9e5f-6a7b8c9d0e1f")
        val m = VoiceMeta.decode(VoiceMeta.of(e).encode())!!
        assertEquals(e.mime, m.mime)
        assertEquals(e.durationMs, m.durationMs)
        assertArrayEquals(e.waveform, m.levels)
        assertEquals(false, m.transcribe)
        assertEquals(e.requestId, m.requestId)
        assertNull(VoiceMeta.of(env(transcribe = true)).transcribe)
        assertArrayEquals(ByteArray(64), VoiceMeta("a", 1, "bad").levels)
    }

    @Test fun waveformFoldsPeaksInto64NormalisedLevels() {
        assertArrayEquals(ByteArray(64), Waveform.of(IntArray(0)))
        assertArrayEquals(ByteArray(64), Waveform.of(IntArray(500)))
        // 640 frames rising: each level is the peak of its 10 frames, normalised to 255 at the end.
        val rising = IntArray(640) { it * 10 }
        val w = Waveform.of(rising)
        assertEquals(64, w.size)
        assertEquals(255, w[63].toInt() and 0xFF)
        for (i in 1 until 64) assertTrue((w[i].toInt() and 0xFF) >= (w[i - 1].toInt() and 0xFF))
        // Fewer frames than levels: every level still has a frame; a single loud frame is the 255.
        val few = Waveform.of(intArrayOf(100, 3200, 1600))
        assertEquals(255, few.maxOf { it.toInt() and 0xFF })
        assertTrue(few.all { (it.toInt() and 0xFF) > 0 })
        assertEquals(32_767, Waveform.peak(shortArrayOf(1, -32_767, 5)))
        assertEquals(1f, Waveform.fractions(w)[63])
    }

    @Test fun previewTexts() {
        assertEquals("0:12", VoiceText.duration(12_400))
        assertEquals("0:01", VoiceText.duration(500))
        assertEquals("12:04", VoiceText.duration(724_000))
        assertEquals("1:00:00", VoiceText.duration(3_600_000))
        assertEquals("🎤 Voice note 0:12", VoiceText.preview(12_400))
        assertEquals("<Voice note 0:12>", VoiceText.copyLine(12_400))
        assertEquals("🎤 Voice note 0:05", VoiceText.previewOf(VoiceMeta.of(env(duration = 5_200)).encode()))
        assertEquals("🎤 Voice note", VoiceText.previewOf(null))
    }
}
