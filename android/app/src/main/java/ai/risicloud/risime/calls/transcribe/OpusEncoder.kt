package ai.risicloud.risime.calls.transcribe

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteOrder

/** §36.4 PCM (16 kHz mono) → one Ogg/Opus chunk, in memory. Null = this chunk could not be encoded. */
fun interface ChunkEncoder {
    fun encode(pcm: ShortArray, rate: Int): ByteArray?
}

/**
 * The platform Opus encoder (Codec2 `c2.android.opus.encoder`, Android 10+), muxed by [OggOpus] in memory.
 * Mono, 24 kbit/s (§36.4: 16–32 kbit/s); a 12-s chunk is about 36 KB, far under the 256 KiB limit.
 */
class MediaCodecOpusEncoder(private val bitrate: Int = 24_000) : ChunkEncoder {
    companion object {
        private const val TIMEOUT_US = 10_000L

        private fun format(rate: Int, bitrate: Int): MediaFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }

        /** This phone can make `ogg_opus` chunks (the §36 capability depends on it). */
        fun available(): Boolean = Build.VERSION.SDK_INT >= 29 &&
            runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format(Pcm.RATE, 24_000)) != null }.getOrDefault(false)
    }

    override fun encode(pcm: ShortArray, rate: Int): ByteArray? {
        if (Build.VERSION.SDK_INT < 29) return null
        val fmt = format(rate, bitrate)
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(fmt) ?: return null
        val codec = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: return null
        val packets = ArrayList<ByteArray>()
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pos = 0
            var inputDone = false
            var guard = 0
            while (guard++ < 100_000) {
                if (!inputDone) {
                    val ix = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (ix >= 0) {
                        val buf = codec.getInputBuffer(ix)!!
                        buf.clear()
                        buf.order(ByteOrder.nativeOrder())
                        val n = minOf(buf.remaining() / 2, pcm.size - pos, rate / 50 * 10) // ≤ 200 ms per input
                        val pts = pos * 1_000_000L / rate
                        if (n <= 0) {
                            codec.queueInputBuffer(ix, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.asShortBuffer().put(pcm, pos, n)
                            codec.queueInputBuffer(ix, 0, n * 2, pts, 0)
                            pos += n
                        }
                    }
                }
                val ox = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (ox >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val out = codec.getOutputBuffer(ox)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        packets += ByteArray(info.size).also { out.get(it) }
                    }
                    codec.releaseOutputBuffer(ox, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (packets.isEmpty()) return null
            return OggOpus.mux(packets, rate, serial = (System.nanoTime() and 0x7FFFFFFF).toInt())
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            packets.forEach { it.fill(0) }
        }
    }
}

/**
 * §36.6 the tap on this phone's OWN microphone: the WebRTC capture (`JavaAudioDeviceModule`'s record
 * samples, before the network path), never the remote or mixed audio. Nothing flows unless a session is
 * `on` and the recorder installed a [sink]. Debug builds may replace the microphone with an injected
 * source (redroid has no microphone); release builds never accept one.
 */
object CallMicTap {
    /** (16-bit PCM bytes, length, sample rate, channels). */
    fun interface Sink {
        fun onPcm(data: ByteArray, length: Int, rate: Int, channels: Int)
    }

    @Volatile var sink: Sink? = null

    /** Debug only: an injected source feeds the tap instead of the microphone. */
    @Volatile var injecting: Boolean = false
        private set

    /** From the call's audio device module (the local capture). */
    fun onMicrophone(data: ByteArray, rate: Int, channels: Int) {
        if (injecting) return
        sink?.onPcm(data, data.size, rate, channels)
    }

    /** Debug builds only (the caller checks `BuildConfig.DEBUG`): start or stop an injected source. */
    fun setInjecting(on: Boolean) {
        injecting = on
    }

    /** Debug builds only: PCM from the injected source, in place of the microphone. */
    fun onInjected(data: ByteArray, length: Int, rate: Int, channels: Int) {
        if (!injecting) return
        sink?.onPcm(data, length, rate, channels)
    }
}
