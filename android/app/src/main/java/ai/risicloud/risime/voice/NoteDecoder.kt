package ai.risicloud.risime.voice

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat

/** Mono and 48 kHz from any decoder output (platform-free, unit-tested). */
object PcmShape {
    /** Interleaved [channels] → mono (average). */
    fun mono(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        val out = ShortArray(pcm.size / channels)
        for (i in out.indices) {
            var s = 0
            for (c in 0 until channels) s += pcm[i * channels + c]
            out[i] = (s / channels).toShort()
        }
        return out
    }

    /** Linear resampling of mono [pcm] from [rate] to 48 kHz (Opus decoders already give 48 kHz: returned as is). */
    fun to48k(pcm: ShortArray, rate: Int): ShortArray {
        if (rate == 48_000 || rate <= 0 || pcm.isEmpty()) return pcm
        val n = (pcm.size.toLong() * 48_000 / rate).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val pos = i.toDouble() * rate / 48_000
            val a = pos.toInt().coerceAtMost(pcm.size - 1)
            val b = (a + 1).coerceAtMost(pcm.size - 1)
            val f = pos - a
            out[i] = (pcm[a] * (1 - f) + pcm[b] * f).toInt().toShort()
        }
        return out
    }
}

/**
 * Decodes a voice note (Ogg/Opus or AAC, §37.2) that is already decrypted **in memory** to 48 kHz mono PCM16, with
 * MediaExtractor/MediaCodec reading from a [MediaDataSource] over the bytes: the plaintext never touches disk. Used
 * only by the developer "Phone recogniser test".
 */
object NoteDecoder {
    fun decode(audio: ByteArray): ShortArray? = runCatching { decodeOrThrow(audio) }.getOrNull()

    private fun decodeOrThrow(audio: ByteArray): ShortArray? {
        val ex = MediaExtractor()
        ex.setDataSource(object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= audio.size) return -1
                val n = minOf(size.toLong(), audio.size - position).toInt()
                System.arraycopy(audio, position.toInt(), buffer, offset, n)
                return n
            }

            override fun getSize(): Long = audio.size.toLong()

            override fun close() = Unit
        })
        try {
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            val out = java.io.ByteArrayOutputStream()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inDone = false
                var outDone = false
                val deadline = System.nanoTime() + 120_000_000_000L // a stuck decoder never hangs the test
                while (!outDone && System.nanoTime() < deadline) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        o >= 0 -> {
                            val b = codec.getOutputBuffer(o)!!
                            val chunk = ByteArray(info.size)
                            b.position(info.offset)
                            b.get(chunk, 0, info.size)
                            out.write(chunk)
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            val bytes = out.toByteArray()
            val pcm = ShortArray(bytes.size / 2) { ((bytes[it * 2].toInt() and 0xFF) or (bytes[it * 2 + 1].toInt() shl 8)).toShort() }
            bytes.fill(0)
            return PcmShape.to48k(PcmShape.mono(pcm, channels), rate)
        } finally {
            ex.release()
        }
    }
}
