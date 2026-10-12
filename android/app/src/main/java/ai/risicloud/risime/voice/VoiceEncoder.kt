package ai.risicloud.risime.voice

import ai.risicloud.risime.calls.transcribe.OggOpus
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteOrder

/**
 * v1.41 §37.2 the sender's encoder: 48 kHz mono PCM → Opus (the platform Codec2 encoder, Android 10+) at
 * [bitrate] (24–32 kbit/s), packets collected in memory as they come; [finish] muxes them into one Ogg/Opus stream
 * ([OggOpus], RFC 7845). Nothing touches the disk here.
 */
interface VoiceEncoder {
    fun feed(pcm: ShortArray, n: Int)

    /** The Ogg/Opus bytes (null: nothing encoded). The encoder is released either way. */
    fun finish(): ByteArray?

    fun release()
}

class StreamingOpusEncoder(private val bitrate: Int = BITRATE) : VoiceEncoder {
    companion object {
        const val RATE = 48_000
        const val BITRATE = 32_000
        private const val TIMEOUT_US = 5_000L

        private fun format(): MediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }

        /** This phone can record voice notes (the platform Opus encoder exists: Android 10+). */
        fun available(): Boolean = Build.VERSION.SDK_INT >= 29 &&
            runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format()) != null }.getOrDefault(false)
    }

    private val codec: MediaCodec
    private val packets = ArrayList<ByteArray>()
    private val info = MediaCodec.BufferInfo()
    private var samplesIn = 0L
    private var done = false

    init {
        val fmt = format().apply { setInteger(MediaFormat.KEY_BIT_RATE, bitrate) }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(fmt) ?: throw IllegalStateException("no Opus encoder")
        codec = MediaCodec.createByCodecName(name)
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            runCatching { codec.release() }
            throw e
        }
    }

    override fun feed(pcm: ShortArray, n: Int) {
        var pos = 0
        var guard = 0
        while (pos < n && guard++ < 1_000) {
            val ix = codec.dequeueInputBuffer(TIMEOUT_US)
            if (ix >= 0) {
                val buf = codec.getInputBuffer(ix)!!
                buf.clear()
                buf.order(ByteOrder.nativeOrder())
                val k = minOf(buf.remaining() / 2, n - pos)
                buf.asShortBuffer().put(pcm, pos, k)
                codec.queueInputBuffer(ix, 0, k * 2, samplesIn * 1_000_000L / RATE, 0)
                samplesIn += k
                pos += k
            }
            drain(false)
        }
    }

    private fun drain(untilEos: Boolean) {
        var idle = 0
        while (true) {
            val ox = codec.dequeueOutputBuffer(info, if (untilEos) TIMEOUT_US * 2 else 0)
            if (ox == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // Not waiting for the end: come back with the next input. At the end: give up after ~2 s of silence.
                if (!untilEos || ++idle > 200) return
                continue
            }
            if (ox < 0) continue // output format / buffers changed
            idle = 0
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val out = codec.getOutputBuffer(ox)!!
                out.position(info.offset)
                out.limit(info.offset + info.size)
                packets += ByteArray(info.size).also { out.get(it) }
            }
            codec.releaseOutputBuffer(ox, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    override fun finish(): ByteArray? {
        if (done) return null
        try {
            var guard = 0
            while (guard++ < 200) {
                val ix = codec.dequeueInputBuffer(TIMEOUT_US)
                if (ix >= 0) {
                    codec.queueInputBuffer(ix, 0, 0, samplesIn * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(false)
            }
            drain(true)
            if (packets.isEmpty()) return null
            return OggOpus.mux(packets, RATE, serial = (System.nanoTime() and 0x7FFFFFFF).toInt())
        } catch (e: Exception) {
            return null
        } finally {
            release()
        }
    }

    override fun release() {
        if (done) return
        done = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        packets.forEach { it.fill(0) }
        packets.clear()
    }
}
