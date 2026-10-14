package ai.risicloud.risime.voice

import ai.risicloud.risime.calls.transcribe.OggOpus
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.media.VoiceEnvelope
import ai.risicloud.risime.data.media.VoiceMeta
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v1.41 §37.3: stamping, auto-play order, speeds, the Ogg framing of a note, and the debug-only microphone seam. */
class VoiceNotesLogicTest {
    private val conv = "grp:g"

    private fun row(id: String, ts: Long, kind: String = MessageEntity.KIND_VOICE, outgoing: Boolean = false, playedAt: Long? = null, systemJson: String? = null) =
        MessageEntity(id, "m-$id", conv, if (outgoing) "me" else "k", conv, "", null, ts, "READ", outgoing, kind = kind, playedAt = playedAt, systemJson = systemJson)

    private fun note(ms: Long = 3_200) = RecordedNote(ByteArray(10) { 1 }, ms, ByteArray(64) { 7 })

    @Test fun stampsTranscribeFalseOnlyInOfficialAndRequestIdOnlyInTheRisiChat() {
        // Private: nothing to opt out of, never a request id.
        val p = VoiceStamp.meta(note(), VoicePlace.PRIVATE, transcribeMine = false)
        assertNull(p.transcribe)
        assertNull(p.requestId)
        assertEquals(VoiceEnvelope.MIME_OGG_OPUS, p.mime)
        assertEquals(3_200L, p.durationMs)
        assertArrayEquals(ByteArray(64) { 7 }, p.levels)
        // Official: `transcribe: false` only while my setting is off.
        assertEquals(false, VoiceStamp.meta(note(), VoicePlace.OFFICIAL, transcribeMine = false).transcribe)
        assertNull(VoiceStamp.meta(note(), VoicePlace.OFFICIAL, transcribeMine = true).transcribe)
        // The Risi chat: a fresh request id per note; `transcribe` is ignored there (never stamped).
        val r = VoiceStamp.meta(note(), VoicePlace.RISI_CHAT, transcribeMine = false) { "8c9d0e1f-2a3b-4c4d-9e5f-6a7b8c9d0e1f" }
        assertEquals("8c9d0e1f-2a3b-4c4d-9e5f-6a7b8c9d0e1f", r.requestId)
        assertNull(r.transcribe)
        // §37.11 (proposal 2026-10-14-spoken-risi): Official with the @Risi chip on: a request id; never in Private.
        assertEquals("rid", VoiceStamp.meta(note(), VoicePlace.OFFICIAL, transcribeMine = true, toRisi = true) { "rid" }.requestId)
        assertNull(VoiceStamp.meta(note(), VoicePlace.OFFICIAL, transcribeMine = true, toRisi = false) { "rid" }.requestId)
        assertNull(VoiceStamp.meta(note(), VoicePlace.PRIVATE, transcribeMine = true, toRisi = true) { "rid" }.requestId)
    }

    @Test fun theOfficialNoteIsShownOnceOnlyInOfficialWhileTheSwitchIsOn() {
        assertTrue(VoiceStamp.officialNoteDue(VoicePlace.OFFICIAL, serverTranscribeOn = true, shown = false))
        assertFalse(VoiceStamp.officialNoteDue(VoicePlace.OFFICIAL, serverTranscribeOn = true, shown = true))
        assertFalse(VoiceStamp.officialNoteDue(VoicePlace.OFFICIAL, serverTranscribeOn = false, shown = false))
        assertFalse("Private never shows Risi text near the mic", VoiceStamp.officialNoteDue(VoicePlace.PRIVATE, serverTranscribeOn = true, shown = false))
    }

    @Test fun autoPlayTakesTheNextUnplayedReceivedNoteOfTheRun() {
        val transcript = row("t", 15, kind = MessageEntity.KIND_TEXT, systemJson = """{"kind":"voice_transcript"}""")
        val rows = listOf(
            row("a", 10),
            transcript,
            row("b", 20, outgoing = true), // mine: passed over
            row("c", 30, playedAt = 5), // played: passed over
            row("d", 40),
            row("e", 50),
            row("x", 60, kind = MessageEntity.KIND_TEXT),
            row("f", 70),
        )
        val isT: (MessageEntity) -> Boolean = { it.clientMsgId == "t" }
        assertEquals("d", VoiceQueue.next(rows, "a", isT)?.clientMsgId)
        assertEquals("e", VoiceQueue.next(rows, "d", isT)?.clientMsgId)
        assertNull("a text message ends the run", VoiceQueue.next(rows, "e", isT))
        assertNull(VoiceQueue.next(rows, "f", isT))
        assertNull(VoiceQueue.next(rows, "nope", isT))
    }

    @Test fun speedsCycleOneToTwo() {
        assertEquals(1.5f, VoiceSpeeds.next(1f))
        assertEquals(2f, VoiceSpeeds.next(1.5f))
        assertEquals(1f, VoiceSpeeds.next(2f))
        assertEquals(1.5f, VoiceSpeeds.next(7f))
        assertEquals("2×", VoiceSpeeds.label(2f))
    }

    /** §37.2 encoder framing: what a note's bytes look like (Ogg/Opus, RFC 7845) for 20 ms packets at 48 kHz. */
    @Test fun aNoteIsOneOggOpusStreamWithHeadTagsAndGranulesAt48k() {
        // 3 s of 20 ms SILK/CELT packets (TOC config 9 = SILK WB 20 ms, code 0: one frame).
        val packets = List(150) { i -> byteArrayOf((9 shl 3).toByte()) + ByteArray(60) { (i + it).toByte() } }
        val ogg = OggOpus.mux(packets, 48_000, serial = 0x1234)
        val pages = pages(ogg)
        assertEquals("OpusHead page, OpusTags page, 3 audio pages", 5, pages.size)
        assertEquals("OpusHead", String(pages[0].body.copyOfRange(0, 8), Charsets.US_ASCII))
        assertEquals(48_000, le32(pages[0].body, 12))
        assertEquals(1, pages[0].body[9].toInt())
        assertEquals(0x02, pages[0].type) // BOS
        assertEquals("OpusTags", String(pages[1].body.copyOfRange(0, 8), Charsets.US_ASCII))
        assertEquals(0x04, pages.last().type) // EOS
        assertEquals(OggOpus.PRE_SKIP + 150L * 960, pages.last().granule)
        assertTrue(pages.all { it.crcOk })
        assertTrue(pages.all { it.serial == 0x1234 })
        assertEquals((0 until 5).toList(), pages.map { it.seq })
        // Duration from the granule: (granule - pre-skip) / 48 = 3000 ms.
        assertEquals(3_000L, (pages.last().granule - OggOpus.PRE_SKIP) / 48)
        // Every note starts with the Ogg magic; the server must never see it (the gate checks the blob).
        assertEquals("OggS", String(ogg.copyOfRange(0, 4), Charsets.US_ASCII))
        assertTrue(VoiceMeta(VoiceEnvelope.MIME_OGG_OPUS, 3_000, "").mime.startsWith("audio/ogg"))
    }

    @Test fun theMicrophoneSeamIsDebugOnly() {
        for ((variant, present) in listOf("debug" to true, "release" to false)) {
            val xml = File(System.getProperty("risime.mergedManifest.$variant") ?: error("no $variant manifest property")).readText()
            assertEquals("$variant: VOICE_INJECT receiver", present, "ai.risicloud.risime.debug.VOICE_INJECT" in xml)
            assertEquals(present, "VoiceInjectReceiver" in xml)
            // The playback service is in both (mediaPlayback only while a note plays).
            assertTrue("VoicePlaybackService" in xml && "FOREGROUND_SERVICE_MEDIA_PLAYBACK" in xml)
        }
        val debugXml = File(System.getProperty("risime.mergedManifest.debug")!!).readText()
        val receiver = debugXml.substringAfter("VoiceInjectReceiver").substringBefore("</receiver>")
        assertTrue("only adb can send it", "android.permission.DUMP" in receiver)
    }

    private class Page(val type: Int, val granule: Long, val serial: Int, val seq: Int, val body: ByteArray, val crcOk: Boolean)

    private fun le32(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun pages(b: ByteArray): List<Page> {
        val out = mutableListOf<Page>()
        var at = 0
        while (at < b.size) {
            assertEquals("OggS", String(b, at, 4, Charsets.US_ASCII))
            val nseg = b[at + 26].toInt() and 0xFF
            val lacing = (0 until nseg).sumOf { b[at + 27 + it].toInt() and 0xFF }
            val len = 27 + nseg + lacing
            val page = b.copyOfRange(at, at + len)
            val crc = le32(page, 22)
            val zeroed = page.copyOf().also { for (i in 22 until 26) it[i] = 0 }
            var g = 0L
            for (i in 0 until 8) g = g or ((page[6 + i].toLong() and 0xFF) shl (8 * i))
            out += Page(page[5].toInt(), g, le32(page, 14), le32(page, 18), page.copyOfRange(27 + nseg, len), OggOpus.crc(zeroed) == crc)
            at += len
        }
        return out
    }
}
