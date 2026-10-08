package lk.codegen.risime.calls

import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §20.3/§20.4 the group call envelopes: strict drops, encode round trips and the history-line table. */
class GroupCallEnvelopeTest {
    private val id = "3c8e1f4a-9b2d-4e7f-8a61-5d0c2b9e7f13"

    private fun call(json: String) = CallEnvelope.decode(json.toByteArray())

    private fun gc(json: String) = GroupCallEnvelope.validate(ProtocolJson.parseToJsonElement(json) as JsonObject)

    @Test fun sfuOfferAndMemberDecodeAndRingBinding() {
        val o = call("""{"v":1,"type":"call_offer","call_id":"$id","mode":"sfu","media":"video","sent_at":"2026-10-08T10:00:00.998Z"}""")
        assertEquals(CallEnvelope.SfuOffer(id, "video", "2026-10-08T10:00:00.998Z"), o)
        assertTrue(CallEnvelope.ringFor(o!!))
        val m = call("""{"v":1,"type":"call_member","call_id":"$id","state":"declined"}""")
        assertEquals(CallEnvelope.Member(id, "declined"), m)
        assertFalse(CallEnvelope.ringFor(m!!))
        // Encode → decode is the identity.
        assertEquals(o, CallEnvelope.decode(CallEnvelope.encode(o)))
        assertEquals(m, CallEnvelope.decode(CallEnvelope.encode(m)))
        assertEquals(CallEnvelope.Cancel(id, "ended"), CallEnvelope.decode(CallEnvelope.encode(CallEnvelope.Cancel(id, CallEnvelope.CANCEL_ENDED))))
    }

    @Test fun strictDrops() {
        val base = """"v":1,"type":"call_offer","call_id":"$id","media":"audio","sent_at":"2026-10-08T10:00:00.998Z""""
        assertNull("mode p2p", call("""{$base,"mode":"p2p"}"""))
        assertNull("mode not a string", call("""{$base,"mode":1}"""))
        assertNull("sdp in an sfu offer", call("""{$base,"mode":"sfu","sdp":"v=0"}"""))
        assertNull("restart in an sfu offer", call("""{$base,"mode":"sfu","restart":true}"""))
        assertNull("media screen", call("""{"v":1,"type":"call_offer","call_id":"$id","mode":"sfu","media":"screen","sent_at":"2026-10-08T10:00:00.998Z"}"""))
        assertNull("member state", call("""{"v":1,"type":"call_member","call_id":"$id","state":"left"}"""))
        assertNull("member no state", call("""{"v":1,"type":"call_member","call_id":"$id"}"""))
        assertNull("upper-case call id", call("""{"v":1,"type":"call_member","call_id":"${id.uppercase()}","state":"joined"}"""))
    }

    @Test fun groupCallValidationAndRoundTrip() {
        val started = gc("""{"v":1,"type":"group_call","call_id":"$id","media":"audio","state":"started"}""")!!
        assertEquals(GroupCallEnvelope.started(id, "audio"), started)
        val ended = gc("""{"v":1,"type":"group_call","call_id":"$id","media":"video","state":"ended","reason":"hangup","connected_at":"2026-10-08T10:00:09.412Z","duration_s":723}""")!!
        assertEquals(723L, ended.durationS)
        assertTrue(ended.video)
        assertEquals(ended, GroupCallEnvelope.validate(ended.toJson()))
        // timeout: connected_at and duration are null whatever was sent.
        val t = gc("""{"v":1,"type":"group_call","call_id":"$id","media":"audio","state":"ended","reason":"timeout","connected_at":"2026-10-08T10:00:09.412Z","duration_s":5}""")!!
        assertNull(t.connectedAt)
        assertNull(t.durationS)
        assertNull("state", gc("""{"v":1,"type":"group_call","call_id":"$id","media":"audio","state":"ringing"}"""))
        assertNull("reason", gc("""{"v":1,"type":"group_call","call_id":"$id","media":"audio","state":"ended","reason":"declined"}"""))
        assertNull("media", gc("""{"v":1,"type":"group_call","call_id":"$id","media":"data","state":"started"}"""))
        assertNull("v", gc("""{"v":2,"type":"group_call","call_id":"$id","media":"audio","state":"started"}"""))
        // Through the payload front door: malformed is Ignored (never a row).
        assertTrue(MlsPayload.decode("""{"v":1,"type":"group_call","call_id":"$id","media":"audio","state":"x"}""".toByteArray()) is MlsPayload.Decoded.Ignored)
        assertTrue(MlsPayload.decode(started.encode()) is MlsPayload.Decoded.GroupCall)
        // A local "over" flag in the stored line never reaches the wire.
        val stored = ProtocolJson.encodeToString(JsonObject.serializer(), JsonObject(started.toJson() + (GroupCallLines.LOCAL_OVER to kotlinx.serialization.json.JsonPrimitive(true))))
        assertTrue(GroupCallLines.over(stored))
        assertEquals(started, GroupCallEnvelope.decode(stored))
        assertFalse(String(GroupCallEnvelope.decode(stored)!!.encode()).contains(GroupCallLines.LOCAL_OVER))
    }

    @Test fun historyLineTable() {
        val s = GroupCallEnvelope.started(id, "audio")
        assertEquals("Kamal started a voice call", GroupCallLines.text(s, "Kamal", starterIsMe = false, running = true))
        assertEquals("You started a voice call", GroupCallLines.text(s, "Kamal", starterIsMe = true, running = true))
        assertEquals("Kamal started a video call", GroupCallLines.text(GroupCallEnvelope.started(id, "video"), "Kamal", false, true))
        assertEquals("Voice call ended", GroupCallLines.text(s, "Kamal", false, running = false))
        val h = GroupCallEnvelope.ended(id, "audio", "hangup", "2026-10-08T10:00:09.412Z", 723)
        assertEquals("Voice call · 12:03", GroupCallLines.text(h, "Kamal", false, true))
        assertEquals("Video call · 12:03", GroupCallLines.text(h.copy(media = "video"), "Kamal", false, true))
        val t = GroupCallEnvelope.ended(id, "audio", "timeout", "x", 9)
        assertNull(t.durationS)
        assertEquals("Voice call · No answer", GroupCallLines.text(t, "Kamal", starterIsMe = true, running = true))
        assertEquals("Missed voice call", GroupCallLines.text(t, "Kamal", starterIsMe = false, running = true))
        assertEquals("Missed video call", GroupCallLines.text(t.copy(media = "video"), "Kamal", false, true))
        assertTrue(GroupCallLines.joinable(s, running = true))
        assertFalse(GroupCallLines.joinable(s, running = false))
        assertFalse(GroupCallLines.joinable(h, running = true))
    }
}
