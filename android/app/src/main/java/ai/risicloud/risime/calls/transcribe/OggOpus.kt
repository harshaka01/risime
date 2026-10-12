package ai.risicloud.risime.calls.transcribe

import java.io.ByteArrayOutputStream

/**
 * §36.4 `codec: "ogg_opus"`: Ogg/Opus (RFC 7845), mono. Pure Kotlin muxing of encoded Opus packets into
 * an in-memory Ogg stream (MediaMuxer's OGG output needs a file; §36.0 forbids audio on disk).
 */
object OggOpus {
    /** RFC 7845 §4.2 default pre-skip for libopus encoders (6.5 ms at 48 kHz). */
    const val PRE_SKIP = 312

    /** Packets per audio page (50 × 20 ms = 1 s; well under the 255-segment limit for ≤ 32 kbit/s). */
    private const val PACKETS_PER_PAGE = 50

    private const val HEADER_BOS = 0x02
    private const val HEADER_EOS = 0x04

    private val CRC_TABLE = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            t[i] = r
        }
    }

    /** The Ogg CRC-32 (poly 0x04C11DB7, no reflection, init 0) over [data]. */
    fun crc(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var c = 0
        for (i in from until to) c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF]
        return c
    }

    /** Samples at 48 kHz in one Opus packet, from its TOC byte (RFC 6716 §3.1); 0 for an empty or bad packet. */
    fun packetSamples48k(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        // Frame size in units of 0.5 ms (so 2.5 ms = 5).
        val halfMs = when {
            config < 12 -> intArrayOf(20, 40, 80, 120)[config % 4]
            config < 16 -> intArrayOf(20, 40)[config % 2]
            else -> intArrayOf(5, 10, 20, 40)[config % 4]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size < 2) return 0 else packet[1].toInt() and 0x3F
        }
        return frames * halfMs * 24 // 0.5 ms at 48 kHz = 24 samples
    }

    fun opusHead(inputRate: Int, channels: Int = 1, preSkip: Int = PRE_SKIP): ByteArray {
        val b = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        b[8] = 1
        b[9] = channels.toByte()
        le16(b, 10, preSkip)
        le32(b, 12, inputRate)
        le16(b, 16, 0) // output gain
        b[18] = 0 // mapping family 0 (mono/stereo)
        return b
    }

    fun opusTags(vendor: String = "RisiMe"): ByteArray {
        val v = vendor.toByteArray(Charsets.UTF_8)
        val b = ByteArray(8 + 4 + v.size + 4)
        "OpusTags".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        le32(b, 8, v.size)
        v.copyInto(b, 12)
        le32(b, 12 + v.size, 0)
        return b
    }

    /**
     * One Ogg/Opus stream of [packets] (each a full Opus packet; config/CSD buffers excluded).
     * [inputRate] goes in OpusHead; granule positions count 48 kHz samples including the pre-skip.
     */
    fun mux(packets: List<ByteArray>, inputRate: Int, serial: Int, preSkip: Int = PRE_SKIP): ByteArray {
        val out = ByteArrayOutputStream(packets.sumOf { it.size } + 1024)
        var seq = 0
        out.write(page(listOf(opusHead(inputRate, 1, preSkip)), HEADER_BOS, 0, serial, seq++))
        out.write(page(listOf(opusTags()), 0, 0, serial, seq++))
        var granule = preSkip.toLong()
        if (packets.isEmpty()) {
            out.write(page(emptyList(), HEADER_EOS, granule, serial, seq))
            return out.toByteArray()
        }
        val pages = packets.chunked(PACKETS_PER_PAGE)
        pages.forEachIndexed { i, group ->
            granule += group.sumOf { packetSamples48k(it).toLong() }
            out.write(page(group, if (i == pages.lastIndex) HEADER_EOS else 0, granule, serial, seq++))
        }
        return out.toByteArray()
    }

    /** One Ogg page holding whole [packets] (lacing values per RFC 3533). */
    fun page(packets: List<ByteArray>, headerType: Int, granule: Long, serial: Int, seq: Int): ByteArray {
        val lacing = ArrayList<Int>()
        for (p in packets) {
            var n = p.size
            while (n >= 255) {
                lacing += 255
                n -= 255
            }
            lacing += n
        }
        require(lacing.size <= 255) { "too many segments for one page" }
        val bodyLen = packets.sumOf { it.size }
        val b = ByteArray(27 + lacing.size + bodyLen)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        b[4] = 0
        b[5] = headerType.toByte()
        le64(b, 6, granule)
        le32(b, 14, serial)
        le32(b, 18, seq)
        le32(b, 22, 0) // CRC, filled below
        b[26] = lacing.size.toByte()
        lacing.forEachIndexed { i, v -> b[27 + i] = v.toByte() }
        var off = 27 + lacing.size
        for (p in packets) {
            p.copyInto(b, off)
            off += p.size
        }
        le32(b, 22, crc(b))
        return b
    }

    private fun le16(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte()
        b[at + 1] = (v shr 8).toByte()
    }

    private fun le32(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = (v shr (8 * i)).toByte()
    }

    private fun le64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v shr (8 * i)).toByte()
    }
}

/** PCM helpers for the transcription tap: everything to 16 kHz mono 16-bit, in memory. */
object Pcm {
    const val RATE = 16_000

    /** Little-endian 16-bit PCM bytes ([channels] interleaved at [rate]) to 16 kHz mono samples. */
    fun toMono16k(data: ByteArray, length: Int, rate: Int, channels: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = length / (2 * ch)
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until ch) {
                val i = (f * ch + c) * 2
                sum += ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            }
            mono[f] = (sum / ch).toShort()
        }
        return resample(mono, frames, rate, RATE)
    }

    /** Resamples [n] samples from [from] Hz to [to] Hz: a box average for integer down-ratios (48k→16k), else linear. */
    fun resample(src: ShortArray, n: Int, from: Int, to: Int): ShortArray {
        if (from == to) return if (n == src.size) src else src.copyOf(n)
        if (from > to && from % to == 0) {
            val k = from / to
            val out = ShortArray(n / k)
            for (i in out.indices) {
                var s = 0
                for (j in 0 until k) s += src[i * k + j]
                out[i] = (s / k).toShort()
            }
            return out
        }
        val outN = ((n.toLong() * to) / from).toInt()
        val out = ShortArray(outN)
        for (i in 0 until outN) {
            val pos = i.toDouble() * from / to
            val i0 = pos.toInt().coerceAtMost(n - 1)
            val i1 = (i0 + 1).coerceAtMost(n - 1)
            val t = pos - i0
            out[i] = (src[i0] * (1 - t) + src[i1] * t).toInt().toShort()
        }
        return out
    }
}
