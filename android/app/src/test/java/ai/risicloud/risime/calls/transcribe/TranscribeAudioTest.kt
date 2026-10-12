package ai.risicloud.risime.calls.transcribe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** §36.4 the chunker (durations, seq, offsets, silence), the Ogg/Opus muxer, and "no audio on disk". */
class TranscribeAudioTest {

    private fun chunker(target: Long = 12_000L): Pair<TranscribeChunker, MutableList<PcmChunk>> {
        val out = mutableListOf<PcmChunk>()
        return TranscribeChunker(target) { out += it } to out
    }

    @Test
    fun chunksAreTwelveSecondsWithIncreasingSeqAndOffsets() {
        val (c, out) = chunker()
        c.start(baseOffsetMs = 0)
        val frame = ShortArray(320) { 1000 } // 20 ms at 16 kHz
        repeat(50 * 40) { c.feed(frame) } // 40 s
        assertEquals(3, out.size)
        assertEquals(listOf(0, 1, 2), out.map { it.seq })
        assertEquals(listOf(0L, 12_000L, 24_000L), out.map { it.offsetMs })
        out.forEach {
            assertEquals(12_000L, it.durationMs)
            assertTrue(it.durationMs in TranscribeChunker.MIN_MS..TranscribeChunker.MAX_MS)
            assertEquals(16_000 * 12, it.pcm.size)
        }
        assertEquals(40_000L, c.fedMs)
    }

    @Test
    fun aLateStartOffsetsFromStartedAt() {
        val (c, out) = chunker(10_000)
        c.start(baseOffsetMs = 3_250)
        c.padSilence(21_000)
        assertEquals(listOf(3_250L, 13_250L), out.map { it.offsetMs })
        assertEquals(listOf(10_000L, 10_000L), out.map { it.durationMs })
    }

    @Test
    fun mutedAudioAndNoMicrophoneStillMakeSilentChunks() {
        val (c, out) = chunker()
        c.start(0)
        val loud = ShortArray(16_000) { 12_000 }
        repeat(6) { c.feed(loud, muted = true) } // 6 s muted
        c.padSilence(6_000) // the microphone delivered nothing
        assertEquals(1, out.size)
        assertTrue("muted = silence (keep-alive)", out[0].pcm.all { it.toInt() == 0 })
    }

    @Test
    fun nothingBeforeStartAndClearDropsAndZeroesThePartialChunk() {
        val (c, out) = chunker()
        c.feed(ShortArray(16_000 * 13) { 5 })
        assertTrue(out.isEmpty())
        c.start(0)
        c.feed(ShortArray(16_000 * 5) { 5 })
        c.clear()
        c.feed(ShortArray(16_000 * 13) { 5 }) // after clear: not started any more
        assertTrue(out.isEmpty())
    }

    @Test
    fun chunkWipeZeroes() {
        val ch = PcmChunk(0, 0, 10, ShortArray(10) { 7 })
        ch.wipe()
        assertTrue(ch.pcm.all { it.toInt() == 0 })
        val e = EncodedChunk("s", 0, 0, 10, ByteArray(10) { 7 })
        e.wipe()
        assertTrue(e.audio.all { it.toInt() == 0 })
    }

    @Test
    fun resampleFrom48kStereoTo16kMono() {
        // 480 frames of 48 kHz stereo (10 ms), left 300 / right 100 → mono 200, 160 samples.
        val bytes = ByteArray(480 * 2 * 2)
        for (f in 0 until 480) {
            bytes[f * 4] = 44; bytes[f * 4 + 1] = 1 // 300
            bytes[f * 4 + 2] = 100; bytes[f * 4 + 3] = 0 // 100
        }
        val mono = Pcm.toMono16k(bytes, bytes.size, 48_000, 2)
        assertEquals(160, mono.size)
        assertTrue(mono.all { it.toInt() == 200 })
        assertEquals(320, Pcm.resample(ShortArray(441), 441, 22_050, 16_000).size)
    }

    // ---- Ogg/Opus ----

    private fun le32(b: ByteArray, at: Int) = (0 until 4).sumOf { (b[at + it].toInt() and 0xFF) shl (8 * it) }
    private fun le64(b: ByteArray, at: Int) = (0 until 8).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }

    private data class Page(val type: Int, val granule: Long, val seq: Int, val body: ByteArray)

    private fun pages(stream: ByteArray): List<Page> {
        val out = mutableListOf<Page>()
        var at = 0
        while (at < stream.size) {
            assertEquals("OggS", String(stream, at, 4, Charsets.US_ASCII))
            val n = stream[at + 26].toInt() and 0xFF
            val body = (0 until n).sumOf { stream[at + 27 + it].toInt() and 0xFF }
            val len = 27 + n + body
            val page = stream.copyOfRange(at, at + len)
            val stored = le32(page, 22)
            for (i in 22 until 26) page[i] = 0
            assertEquals("CRC of page ${out.size}", stored, OggOpus.crc(page))
            out += Page(page[5].toInt(), le64(page, 6), le32(page, 18), page.copyOfRange(27 + n, len))
            at += len
        }
        return out
    }

    @Test
    fun opusPacketDurationsFromTheToc() {
        assertEquals(960, OggOpus.packetSamples48k(byteArrayOf((31 shl 3).toByte()))) // CELT FB 20 ms, one frame
        assertEquals(960, OggOpus.packetSamples48k(byteArrayOf((1 shl 3).toByte()))) // SILK NB 20 ms
        assertEquals(1920, OggOpus.packetSamples48k(byteArrayOf(((1 shl 3) or 1).toByte()))) // two frames
        assertEquals(480 * 3, OggOpus.packetSamples48k(byteArrayOf(((30 shl 3) or 3).toByte(), 3))) // CELT 10 ms × 3
        assertEquals(120, OggOpus.packetSamples48k(byteArrayOf((16 shl 3).toByte()))) // 2.5 ms
        assertEquals(0, OggOpus.packetSamples48k(ByteArray(0)))
    }

    @Test
    fun oggStreamHasHeadTagsAndAudioPagesWithValidCrcAndGranules() {
        val pkt = ByteArray(60) { (31 shl 3).toByte() } // 20 ms each
        val packets = List(120) { pkt.copyOf() } // 2.4 s
        val ogg = OggOpus.mux(packets, inputRate = 16_000, serial = 0x1234)
        val ps = pages(ogg)
        assertEquals(2 + 3, ps.size) // head, tags, 50 + 50 + 20 packets
        assertEquals(0x02, ps[0].type) // BOS
        assertEquals("OpusHead", String(ps[0].body, 0, 8, Charsets.US_ASCII))
        assertEquals(1, ps[0].body[9].toInt()) // mono
        assertEquals(16_000, le32(ps[0].body, 12))
        assertEquals("OpusTags", String(ps[1].body, 0, 8, Charsets.US_ASCII))
        assertEquals(listOf(0, 1, 2, 3, 4), ps.map { it.seq })
        assertEquals(0x04, ps.last().type) // EOS
        assertEquals(OggOpus.PRE_SKIP + 120L * 960, ps.last().granule)
        assertEquals(OggOpus.PRE_SKIP + 50L * 960, ps[2].granule)
        assertArrayEquals(pkt, ps[2].body.copyOfRange(0, 60))
    }

    @Test
    fun longPacketsUseLacingContinuation() {
        val big = ByteArray(600) { 0x08 }
        val ps = pages(OggOpus.mux(listOf(big), 48_000, 1))
        assertEquals(600, ps[2].body.size)
    }

    /**
     * §36.0/§36.4 "raw audio is never stored": the transcription path keeps audio in memory only. Guard: no
     * file, cache or muxer API anywhere in the package (the encoder, chunker, uploader, tap and manager).
     */
    @Test
    fun theTranscriptionPathNeverWritesAudioToDisk() {
        val dir = File("src/main/java/ai/risicloud/risime/calls/transcribe")
        val files = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("the transcribe package exists", files.size >= 4)
        val banned = listOf(
            "java.io.File", "File(", "FileOutputStream", "FileWriter", "RandomAccessFile", "openFileOutput", "createTempFile",
            "filesDir", "cacheDir", "externalCacheDir", "getExternalFilesDir", "MediaMuxer(", "Files.write", "Files.newOutputStream",
            "MediaStore", "SharedPreferences", "Log.", "println(",
        )
        for (f in files) {
            val code = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
                .joinToString("\n") { it.substringBefore("//") }
            for (b in banned) assertTrue("${f.name} uses $b (audio must stay in memory, never logged)", b !in code)
        }
    }
}
