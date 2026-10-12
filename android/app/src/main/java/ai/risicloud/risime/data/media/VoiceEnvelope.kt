package ai.risicloud.risime.data.media

import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import ai.risicloud.risime.net.BlobRef
import ai.risicloud.risime.net.EnvelopeExtras
import ai.risicloud.risime.net.ProtocolJson
import java.util.Base64

/**
 * v1.41 §37.2 the `voice` MLS application payload, after strict validation. A voice note is an E2EE blob exactly
 * like a photo (§14): `blob` + `enc` (`A256GCM-S64K`, a fresh single-use key), plus the audio's mime, duration and
 * 64-level waveform. [transcribe] is the sender's per-note opt-out (§37.6: `false` only; absent = allowed);
 * [requestId] is meaningful only in the user's Risi chat (§37.8).
 */
data class VoiceEnvelope(
    val blob: BlobRef,
    val enc: ImageEnc,
    val mime: String,
    val durationMs: Long,
    val waveform: ByteArray,
    val transcribe: Boolean? = null,
    val requestId: String? = null,
    val extras: EnvelopeExtras = EnvelopeExtras.NONE,
) {
    fun encode(): ByteArray {
        val b64 = Base64.getEncoder()
        return ProtocolJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("v", 1)
                put("type", TYPE)
                put("blob", buildJsonObject { put("blob_id", blob.blobId); put("size", blob.size); put("sha256", blob.sha256) })
                put("enc", buildJsonObject { put("alg", enc.alg); put("key", b64.encodeToString(enc.key)); put("plain_size", enc.plainSize) })
                put("mime", mime)
                put("duration_ms", durationMs)
                put("waveform", b64.encodeToString(waveform))
                if (transcribe == false) put("transcribe", false)
                requestId?.let { put("request_id", it) }
                extras.putInto(this)
            },
        ).toByteArray(Charsets.UTF_8)
    }

    override fun equals(other: Any?) = other is VoiceEnvelope && blob == other.blob && enc == other.enc && mime == other.mime &&
        durationMs == other.durationMs && waveform.contentEquals(other.waveform) && transcribe == other.transcribe &&
        requestId == other.requestId && extras == other.extras

    override fun hashCode() = blob.hashCode() * 31 + waveform.contentHashCode()

    companion object {
        const val TYPE = "voice"
        const val MIME_OGG_OPUS = "audio/ogg; codecs=opus"
        const val MIME_MP4 = "audio/mp4"
        val MIMES = setOf(MIME_OGG_OPUS, MIME_MP4)
        const val MIN_DURATION_MS = 500L
        const val MAX_DURATION_MS = 1_800_000L
        const val WAVEFORM_LEVELS = 64

        /** §37.6: Risi transcribes notes up to 5 min. */
        const val MAX_TRANSCRIBE_MS = 300_000L

        fun validate(obj: JsonObject): VoiceEnvelope? = runCatching { validateOrThrow(obj) }.getOrNull()

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.num(k: String): Long? =
            (this[k] as? JsonPrimitive)?.takeIf { !it.isString && !it.content.contains('.') && !it.content.contains('e', true) }?.longOrNull

        private fun b64(s: String?): ByteArray? = s?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

        /** §37.2 strict validation: the §14.4 `blob`/`enc` checks, `mime`, `duration_ms`, a 64-byte `waveform`. Null = drop and log. */
        private fun validateOrThrow(o: JsonObject): VoiceEnvelope? {
            val blob = o["blob"] as? JsonObject ?: return null
            val enc = o["enc"] as? JsonObject ?: return null
            val blobId = blob.str("blob_id")?.takeIf { it.isNotBlank() } ?: return null
            val size = blob.num("size") ?: return null
            val sha = blob.str("sha256") ?: return null
            if (b64(sha)?.size != 32) return null
            if (size < 17 || size > MediaFormat.MAX_MEDIA_CIPHER) return null
            if (enc.str("alg") != MediaFormat.ALG) return null
            val key = b64(enc.str("key"))?.takeIf { it.size == 32 } ?: return null
            val plain = enc.num("plain_size")?.takeIf { it >= 1 } ?: return null
            if (MediaFormat.cipherSize(plain) != size) return null
            val mime = o.str("mime")?.takeIf { it in MIMES } ?: return null
            val duration = o.num("duration_ms")?.takeIf { it in MIN_DURATION_MS..MAX_DURATION_MS } ?: return null
            val waveform = b64(o.str("waveform"))?.takeIf { it.size == WAVEFORM_LEVELS } ?: return null
            val transcribe = when (val t = o["transcribe"]) {
                null, JsonNull -> null
                is JsonPrimitive -> if (t.isString) null else t.content.toBooleanStrictOrNull()
                else -> null
            }
            val requestId = o.str("request_id")?.let { s -> runCatching { java.util.UUID.fromString(s) }.getOrNull()?.toString()?.takeIf { it.equals(s, true) } }
            return VoiceEnvelope(
                BlobRef(blobId, size, sha), ImageEnc(MediaFormat.ALG, key, plain), mime, duration, waveform,
                transcribe = transcribe, requestId = requestId, extras = EnvelopeExtras.of(o),
            )
        }
    }
}

/**
 * What a `voice` row keeps besides its media row (in `messages.system_json`): the mime, duration, waveform (base64)
 * and the sender-side fields that travel again on a resend ([transcribe] `false` only, [requestId] in the Risi chat).
 */
@kotlinx.serialization.Serializable
data class VoiceMeta(
    val mime: String,
    @SerialName("duration_ms") val durationMs: Long,
    val waveform: String,
    val transcribe: Boolean? = null,
    @SerialName("request_id") val requestId: String? = null,
) {
    fun encode(): String = ProtocolJson.encodeToString(serializer(), this)

    /** The 64 levels (all zero when the stored value is damaged). */
    val levels: ByteArray get() = runCatching { Base64.getDecoder().decode(waveform) }.getOrNull()?.takeIf { it.size == VoiceEnvelope.WAVEFORM_LEVELS }
        ?: ByteArray(VoiceEnvelope.WAVEFORM_LEVELS)

    companion object {
        fun of(e: VoiceEnvelope) = VoiceMeta(e.mime, e.durationMs, Base64.getEncoder().encodeToString(e.waveform), e.transcribe?.takeIf { !it }, e.requestId)

        fun decode(json: String?): VoiceMeta? = json?.let { runCatching { ProtocolJson.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** §37.3 the user-visible texts of a voice note (previews, quotes, copy). */
object VoiceText {
    /** "0:12", "12:04", "1:02:03" (`h:mm:ss` from one hour). */
    fun duration(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 500) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Chat list, notifications and reply quotes: "🎤 Voice note 0:12". */
    fun preview(durationMs: Long?): String = if (durationMs == null) "🎤 Voice note" else "🎤 Voice note ${duration(durationMs)}"

    /** §33.3 copy line: "<Voice note 0:12>". */
    fun copyLine(durationMs: Long?): String = if (durationMs == null) "<Voice note>" else "<Voice note ${duration(durationMs)}>"

    fun previewOf(systemJson: String?): String = preview(VoiceMeta.decode(systemJson)?.durationMs)
}

/**
 * §37.2 the 64-level waveform: the peak amplitude per 1/64 of the note, normalised to the note's own peak
 * (0 = silence, 255 = loudest). The recorder keeps one peak per 20 ms frame and folds them here.
 */
object Waveform {
    /** [framePeaks]: one absolute peak (0..32768) per recorded frame, in order. */
    fun of(framePeaks: IntArray, count: Int = framePeaks.size): ByteArray {
        val out = ByteArray(VoiceEnvelope.WAVEFORM_LEVELS)
        if (count <= 0) return out
        val buckets = IntArray(VoiceEnvelope.WAVEFORM_LEVELS)
        for (b in 0 until VoiceEnvelope.WAVEFORM_LEVELS) {
            val from = (b.toLong() * count / VoiceEnvelope.WAVEFORM_LEVELS).toInt()
            val to = maxOf(from + 1, ((b + 1).toLong() * count / VoiceEnvelope.WAVEFORM_LEVELS).toInt()).coerceAtMost(count)
            var peak = 0
            for (i in from until to) if (framePeaks[i] > peak) peak = framePeaks[i]
            buckets[b] = peak
        }
        val max = buckets.max()
        if (max <= 0) return out
        for (b in buckets.indices) out[b] = ((buckets[b].toLong() * 255 + max / 2) / max).toInt().coerceIn(0, 255).toByte()
        return out
    }

    /** The absolute peak of [n] 16-bit samples. */
    fun peak(pcm: ShortArray, n: Int = pcm.size): Int {
        var p = 0
        for (i in 0 until n) {
            val a = kotlin.math.abs(pcm[i].toInt())
            if (a > p) p = a
        }
        return p
    }

    /** Levels as 0..1 floats for drawing. */
    fun fractions(levels: ByteArray): FloatArray = FloatArray(levels.size) { (levels[it].toInt() and 0xFF) / 255f }
}
