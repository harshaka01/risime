package lk.codegen.risime.data.mls

import org.junit.Assert.assertEquals
import org.junit.Test

class MlsPayloadTest {
    @Test fun roundTrip() {
        val bytes = MlsPayload.text("héllo \"quoted\" 👋")
        assertEquals("""{"v":1,"type":"text","body":"héllo \"quoted\" 👋"}""", bytes.toString(Charsets.UTF_8))
        assertEquals(MlsPayload.Decoded.Text("héllo \"quoted\" 👋"), MlsPayload.decode(bytes))
    }

    @Test fun legacyAndMalformedAreText() {
        assertEquals(MlsPayload.Decoded.Text("plain hello"), MlsPayload.decode("plain hello".toByteArray()))
        assertEquals(MlsPayload.Decoded.Text("{not json"), MlsPayload.decode("{not json".toByteArray()))
        assertEquals(MlsPayload.Decoded.Text("[1,2]"), MlsPayload.decode("[1,2]".toByteArray())) // not an object
        assertEquals(MlsPayload.Decoded.Text("42"), MlsPayload.decode("42".toByteArray()))
        // An object without a string "type" is someone's literal JSON text.
        assertEquals(MlsPayload.Decoded.Text("""{"a":1}"""), MlsPayload.decode("""{"a":1}""".toByteArray()))
        assertEquals(MlsPayload.Decoded.Text("""{"type":7}"""), MlsPayload.decode("""{"type":7}""".toByteArray()))
    }

    @Test fun unknownTypesAreIgnored() {
        assertEquals(MlsPayload.Decoded.Ignored("reaction"), MlsPayload.decode("""{"v":1,"type":"reaction","emoji":"+1","target":"m1"}""".toByteArray()))
        assertEquals(MlsPayload.Decoded.Ignored("image"), MlsPayload.decode("""{"v":2,"type":"image"}""".toByteArray()))
        // Forward compatible: extra fields and other versions of "text" still read the body.
        assertEquals(MlsPayload.Decoded.Text("hi"), MlsPayload.decode("""{"v":2,"type":"text","body":"hi","fmt":"md"}""".toByteArray()))
    }
}
