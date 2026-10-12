package ai.risicloud.risime.data.tabs

import ai.risicloud.risime.calls.CallEnvelope
import ai.risicloud.risime.net.CALL_TAB_OFFICIAL
import ai.risicloud.risime.net.RisiTranscriptKinds
import ai.risicloud.risime.ui.settings.DevTapCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** v1.39 §36.5 the Official chat's transcription lines from the contract examples, and the §36.1 bits around them. */
class RisiTranscriptLinesTest {
    private fun read(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private val names = mapOf(
        "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3" to "Harsha",
        "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e" to "Kamal",
    )
    private val nameOf: (String) -> String = { names[it] ?: "?" }
    private val colombo = ZoneId.of("Asia/Colombo") // UTC+5:30

    @Test
    fun aTranscriptLineUsesThisPhonesNameAndZone() {
        val s = read("envelope_risi_call_transcript.json")
        val t = RisiTranscriptKinds.transcript(s)!!
        assertEquals("Harsha 17:50: Let's send the revised quote by Friday.", RisiTranscriptLines.transcript(t, nameOf, colombo, "body"))
        assertEquals("Harsha 12:20: Let's send the revised quote by Friday.", RisiTranscriptLines.transcript(t, nameOf, ZoneId.of("UTC"), "body"))
    }

    @Test
    fun consentLinesFollowTheTable() {
        val on = RisiTranscriptKinds.consentLine(read("envelope_risi_call_transcribe_on.json"))!!
        assertEquals(
            "Harsha asked Risi to transcribe this call; Kamal allowed (17:50). Audio is sent to Risi for transcription.",
            RisiTranscriptLines.consent(on, nameOf, colombo, "body"),
        )
        val stopped = RisiTranscriptKinds.consentLine(read("envelope_risi_call_transcribe_stopped.json"))!!
        assertEquals("Kamal stopped transcription (18:02).", RisiTranscriptLines.consent(stopped, nameOf, colombo, "body"))
        assertEquals("Kamal stopped transcription and deleted the transcript (18:02).", RisiTranscriptLines.consent(stopped.copy(discard = true), nameOf, colombo, "body"))
        assertEquals("Transcription ended with the call (17:50–18:02).", RisiTranscriptLines.consent(stopped.copy(state = "ended"), nameOf, colombo, "body"))
        assertEquals("No answer to the transcription request (18:02).", RisiTranscriptLines.consent(stopped.copy(state = "expired"), nameOf, colombo, "body"))
        val declined = on.copy(state = "declined", consents = listOf(on.consents[0], on.consents[1].copy(consent = "declined")))
        assertEquals("Kamal declined transcription (17:50).", RisiTranscriptLines.consent(declined, nameOf, colombo, "body"))
        assertEquals("server body", RisiTranscriptLines.consent(on.copy(state = "something_new"), nameOf, colombo, "server body"))
    }

    @Test
    fun compareRowsPutTheRoutedModelFirstAndShowFailures() {
        val c = RisiTranscriptKinds.compare(read("envelope_risi_call_transcript_compare.json"))!!
        val rows = RisiTranscriptLines.compareRows(c)
        assertEquals(listOf("whisper-small-sinhala", "tamil-asr-qwen3", "faster-whisper-large-v3"), rows.map { it.model })
        assertTrue(rows[0].chosen)
        assertEquals(640L, rows[0].elapsedMs)
        assertTrue(rows[1].failed)
        assertEquals("timeout", rows[1].text)
        assertEquals(30_000L, rows[1].elapsedMs)
        assertEquals("Kamal 17:50 · Sinhala detected", RisiTranscriptLines.compareHeader(c, nameOf, colombo))
    }

    @Test
    fun otherKindsAreNotTranscriptKinds() {
        val card = read("envelope_risi_discussion_card_transcribed.json")
        assertNull(RisiTranscriptKinds.transcript(card))
        assertNull(RisiTranscriptKinds.compare(card))
        assertNull(RisiTranscriptKinds.consentLine(card))
    }

    @Test
    fun theOfficialOfferCarriesTabAndAPrivateOneDoesNot() {
        val official = CallEnvelope.decode(read("call_offer_official_payload.json").toByteArray()) as CallEnvelope.Offer
        assertTrue(official.official)
        val private = official.copy(tab = null)
        assertFalse(private.official)
        assertFalse("tab" in CallEnvelope.toJson(private))
        assertEquals(CALL_TAB_OFFICIAL, (CallEnvelope.toJson(official)["tab"] as kotlinx.serialization.json.JsonPrimitive).content)
        // A restart never carries it (first offer only).
        assertFalse("tab" in CallEnvelope.toJson(official.copy(restart = true, toDevice = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e")))
    }

    @Test
    fun sevenTapsOpenTheDeveloperSection() {
        val t = DevTapCounter()
        repeat(6) { assertFalse(t.tap(1_000L + it * 100)) }
        assertTrue(t.tap(1_700))
        // Too slow: the count starts over.
        val slow = DevTapCounter()
        repeat(6) { assertFalse(slow.tap(it * 1_000L)) }
        assertFalse(slow.tap(6_000))
    }
}
