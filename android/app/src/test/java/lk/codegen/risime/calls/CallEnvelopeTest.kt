package lk.codegen.risime.calls

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §16.2 envelopes and §16.10 SDP rules against the real contract examples. */
class CallEnvelopeTest {
    private fun read(name: String): String =
        javaClass.classLoader!!.getResource("contract/v1/examples/$name")?.readText() ?: error("missing $name")

    private fun obj(name: String) = ProtocolJson.parseToJsonElement(read(name)) as JsonObject

    private fun decode(name: String): CallEnvelope.Env? = CallEnvelope.decode(read(name).toByteArray())

    @Test
    fun everyCallExampleDecodesAndReEncodesToTheSameJson() {
        val names = listOf(
            "call_offer_payload.json", "call_ringing_payload.json", "call_answer_payload.json", "call_accepted_payload.json",
            "call_ice_payload.json", "call_busy_payload.json", "call_cancel_payload.json", "call_end_payload.json", "call_end_missed_payload.json",
        )
        for (n in names) {
            val env = decode(n) ?: throw AssertionError("$n dropped")
            assertEquals(n, obj(n), CallEnvelope.toJson(env))
        }
        val offer = decode("call_offer_payload.json") as CallEnvelope.Offer
        assertEquals("4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10", offer.callId)
        assertTrue(CallEnvelope.ringFor(offer))
        val ice = decode("call_ice_payload.json") as CallEnvelope.Ice
        assertEquals(2, ice.candidates.size)
        assertNull(ice.toDevice)
        val end = decode("call_end_payload.json") as CallEnvelope.End
        assertEquals(192L, end.durationS)
        val missed = decode("call_end_missed_payload.json") as CallEnvelope.End
        assertEquals(CallEnvelope.R_TIMEOUT, missed.reason)
        assertNull(missed.connectedAt)
    }

    @Test
    fun theBadOfferWithTheAudioLevelExtensionIsDropped() {
        var why = ""
        assertNull(CallEnvelope.decode(read("call_offer_payload_bad.json").toByteArray()) { why = it })
        assertTrue(why, why.contains("audio-level"))
    }

    private fun offerWith(sdp: String, extra: Map<String, Any?> = emptyMap()): ByteArray {
        val base = obj("call_offer_payload.json").toMutableMap()
        base["sdp"] = JsonPrimitive(sdp)
        extra.forEach { (k, v) -> base[k] = when (v) { null -> kotlinx.serialization.json.JsonNull; is Boolean -> JsonPrimitive(v); is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) } }
        return ProtocolJson.encodeToString(JsonObject.serializer(), JsonObject(base)).toByteArray()
    }

    private val goodSdp: String get() = (CallEnvelope.decode(read("call_offer_payload.json").toByteArray()) as CallEnvelope.Offer).sdp

    @Test
    fun strictDropsOnTheEnvelope() {
        assertNotNull(CallEnvelope.decode(offerWith(goodSdp)))
        assertNull("uppercase call_id", CallEnvelope.decode(offerWith(goodSdp, mapOf("call_id" to "4B7E1C1E-3C0E-4B55-9F43-0B8F8A1F2D10"))))
        assertNull("not a uuid", CallEnvelope.decode(offerWith(goodSdp, mapOf("call_id" to "call-1"))))
        assertNull("video", CallEnvelope.decode(offerWith(goodSdp, mapOf("media" to "video"))))
        assertNull("sent_at", CallEnvelope.decode(offerWith(goodSdp, mapOf("sent_at" to "yesterday"))))
        assertNull("no sent_at", CallEnvelope.decode(offerWith(goodSdp, mapOf("sent_at" to null))))
        assertNull("v=2", CallEnvelope.decode(offerWith(goodSdp, mapOf("v" to 2))))
        assertNull("restart without to_device", CallEnvelope.decode(offerWith(goodSdp, mapOf("restart" to true))))
        assertNotNull(CallEnvelope.decode(offerWith(goodSdp, mapOf("restart" to true, "to_device" to "c0a80101-0000-4000-8000-000000000004"))))
        val big = offerWith(goodSdp + "a=x-pad:" + "x".repeat(25_000) + "\r\n")
        assertNull("over 20 480 bytes", CallEnvelope.decode(big))
        // ICE limits.
        fun ice(n: Int, line: String = "candidate:1 1 udp 2122260223 192.0.2.10 49203 typ host"): ByteArray =
            CallEnvelope.encode(CallEnvelope.Ice("4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10", null, List(n) { CallEnvelope.Candidate(line, "0", 0) }, false))
        assertNotNull(CallEnvelope.decode(ice(20)))
        assertNull(CallEnvelope.decode(ice(21)))
        assertNull(CallEnvelope.decode(ice(1, "a=candidate:1")))
        assertNull(CallEnvelope.decode(ice(1, "candidate:" + "9".repeat(510))))
        assertNull("empty batch without done", CallEnvelope.decode(ice(0)))
        assertNull(CallEnvelope.decode(CallEnvelope.encode(CallEnvelope.Accepted("4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10", "not-a-device"))))
        // An unknown call_end reason still decodes (rendered as "Voice call").
        assertEquals("exploded", (CallEnvelope.decode(CallEnvelope.encode(CallEnvelope.End("4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10", "exploded"))) as CallEnvelope.End).reason)
    }

    @Test
    fun sdpRules() {
        val offer = goodSdp
        assertNull(SdpRules.validate(offer, SdpRules.Role.OFFER))
        assertNotNull("actpass is not an answer", SdpRules.validate(offer, SdpRules.Role.ANSWER))
        val answer = (CallEnvelope.decode(read("call_answer_payload.json").toByteArray()) as CallEnvelope.Answer).sdp
        assertNull(SdpRules.validate(answer, SdpRules.Role.ANSWER))
        assertNotNull(SdpRules.validate(answer, SdpRules.Role.OFFER))
        fun bad(s: String, why: String) = assertNotNull(why, SdpRules.validate(s, SdpRules.Role.OFFER))
        bad(offer.replace("a=fingerprint:sha-256", "a=fingerprint:sha-1"), "sha-1")
        bad(offer.replace("a=fingerprint:sha-256 0B:30", "a=fingerprint:sha-256 30"), "31 bytes")
        val fpLine = SdpRules.lines(offer).first { it.startsWith("a=fingerprint") }
        bad(offer.replace(fpLine, "$fpLine\r\n$fpLine"), "second fingerprint")
        bad(offer.replace(fpLine + "\r\n", ""), "no fingerprint")
        bad(offer.replace("a=rtcp-mux\r\n", "a=rtcp-mux\r\na=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:abc\r\n"), "SDES")
        bad(offer.replace("a=rtcp-mux\r\n", ""), "rtcp-mux")
        bad(offer.replace(Regex("a=ice-ufrag:[^\r]*\r\n"), ""), "ufrag")
        bad(offer.replace(Regex("a=ice-pwd:[^\r]*\r\n"), ""), "pwd")
        bad(offer + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n", "video m-line")
        bad(offer.replace("UDP/TLS/RTP/SAVPF", "RTP/AVP"), "plain RTP")
        bad(offer.replace("opus/48000/2", "PCMU/8000"), "no opus")
        bad(offer.replace("a=setup:actpass", "a=setup:active"), "setup")
        bad(offer + "a=x:" + "y".repeat(17_000) + "\r\n", "16 KiB")
        // Stripping and Opus tuning: what the app produces always passes.
        val raw = fakeSdp(true, fingerprintOf(3), "u3", audioLevel = true)
        assertNotNull(SdpRules.validate(raw, SdpRules.Role.OFFER))
        val prepared = SdpRules.prepareLocal(raw)
        assertNull(SdpRules.validate(prepared, SdpRules.Role.OFFER))
        assertFalse(prepared.contains(SdpRules.AUDIO_LEVEL))
        val fmtp = SdpRules.lines(prepared).single { it.startsWith("a=fmtp:111 ") }
        for (p in listOf("useinbandfec=1", "usedtx=1", "cbr=1", "stereo=0", "maxaveragebitrate=32000")) assertTrue(fmtp, fmtp.contains(p))
        assertTrue(prepared.contains("a=ptime:20"))
        // Fingerprints: normalised compare, and the tamper hook changes exactly one byte.
        assertEquals(SdpRules.normalize(fingerprintOf(3)), SdpRules.fingerprint(prepared))
        assertTrue(SdpRules.sameFingerprint("0b:30:55", "0B30:55"))
        val tampered = SdpRules.tamperFingerprint(prepared)
        assertNull(SdpRules.validate(tampered, SdpRules.Role.OFFER))
        assertFalse(SdpRules.sameFingerprint(SdpRules.fingerprint(tampered), SdpRules.fingerprint(prepared)))
    }
}
