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
    /** Proposal 2026-10-14 (v1.47 candidate): the sender phone's own on-device transcript; null = absent (v1.46 bytes). */
    val phoneTranscript: PhoneTranscript? = null,
    /**
     * Decision 092 (proposal 2026-10-14-private-transcribe §4) `allow_transcribe`: `false` = the sender doesn't let
     * readers send this note's audio (or text derived from it) off their phone; on-device recognition stays allowed.
     * Only `false` is ever written (absent = allowed: v1.47 bytes).
     */
    val allowTranscribe: Boolean? = null,
) {
    /** False when the sender opted out of off-phone transcription for this note. */
    val othersMayTranscribe: Boolean get() = allowTranscribe != false

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
                phoneTranscript?.let { put(PhoneTranscript.FIELD, it.toJson()) }
                if (allowTranscribe == false) put(FIELD_ALLOW_TRANSCRIBE, false)
            },
        ).toByteArray(Charsets.UTF_8)
    }

    override fun equals(other: Any?) = other is VoiceEnvelope && blob == other.blob && enc == other.enc && mime == other.mime &&
        durationMs == other.durationMs && waveform.contentEquals(other.waveform) && transcribe == other.transcribe &&
        requestId == other.requestId && extras == other.extras && phoneTranscript == other.phoneTranscript &&
        allowTranscribe == other.allowTranscribe

    override fun hashCode() = blob.hashCode() * 31 + waveform.contentHashCode()

    companion object {
        const val TYPE = "voice"
        const val FIELD_ALLOW_TRANSCRIBE = "allow_transcribe"
        const val MIME_OGG_OPUS = "audio/ogg; codecs=opus"
        const val MIME_MP4 = "audio/mp4"
        val MIMES = setOf(MIME_OGG_OPUS, MIME_MP4)
        const val MIN_DURATION_MS = 500L
        const val MAX_DURATION_MS = 1_800_000L
        const val WAVEFORM_LEVELS = 64

        /** §37.6: Risi transcribes notes up to 5 min. */
        const val MAX_TRANSCRIBE_MS = 300_000L

        /**
         * Decision 092: absent / null / `true` = allowed (null); anything else (`false`, or a malformed value) is read
         * as the opt-out `false`: the privacy-safe reading. The note itself is always kept.
         */
        fun allowTranscribeOf(el: kotlinx.serialization.json.JsonElement?): Boolean? = when {
            el == null || el == JsonNull -> null
            el is JsonPrimitive && !el.isString && el.content == "true" -> null
            else -> false
        }

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
                // Lenient (proposal 2026-10-14): a malformed `phone_transcript` is ignored, the note is kept.
                phoneTranscript = PhoneTranscript.parse(o[PhoneTranscript.FIELD]),
                allowTranscribe = allowTranscribeOf(o[FIELD_ALLOW_TRANSCRIBE]),
            )
        }
    }
}

/**
 * What a `voice` row keeps besides its media row (in `messages.system_json`): the mime, duration, waveform (base64)
 * and the sender-side fields that travel again on a resend ([transcribe] `false` only, [requestId] in the Risi chat).
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@kotlinx.serialization.Serializable
data class VoiceMeta(
    val mime: String,
    @SerialName("duration_ms") val durationMs: Long,
    val waveform: String,
    val transcribe: Boolean? = null,
    @SerialName("request_id") val requestId: String? = null,
    /** Never written when absent, so a note without it stores exactly the v1.46 JSON. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("phone_transcript") val phoneTranscript: PhoneTranscript? = null,
    /** Decision 092: the sender's `allow_transcribe: false` (never written when absent: the v1.47 JSON). */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("allow_transcribe") val allowTranscribe: Boolean? = null,
) {
    /** False when the sender opted out of off-phone transcription for this note (decision 092). */
    val othersMayTranscribe: Boolean get() = allowTranscribe != false

    fun encode(): String = ProtocolJson.encodeToString(serializer(), this)

    /** The 64 levels (all zero when the stored value is damaged). */
    val levels: ByteArray get() = runCatching { Base64.getDecoder().decode(waveform) }.getOrNull()?.takeIf { it.size == VoiceEnvelope.WAVEFORM_LEVELS }
        ?: ByteArray(VoiceEnvelope.WAVEFORM_LEVELS)

    companion object {
        fun of(e: VoiceEnvelope) = VoiceMeta(e.mime, e.durationMs, Base64.getEncoder().encodeToString(e.waveform), e.transcribe?.takeIf { !it }, e.requestId, e.phoneTranscript, e.allowTranscribe?.takeIf { !it })

        fun decode(json: String?): VoiceMeta? = json?.let { runCatching { ProtocolJson.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * Proposal 2026-10-14 (v1.47 candidate) `voice.phone_transcript`: what the sender's phone heard, recognised **on the
 * device** before encryption. It travels only inside the E2EE envelope. [lang] is the BCP-47 locale the recogniser
 * ran with; [engine] is `android-on-device` from this app.
 */
@kotlinx.serialization.Serializable
data class PhoneTranscript(val text: String, val lang: String, val engine: String = ENGINE_ANDROID) {
    fun toJson(): JsonObject = buildJsonObject { put("text", text); put("lang", lang); put("engine", engine) }

    companion object {
        const val FIELD = "phone_transcript"
        const val ENGINE_ANDROID = "android-on-device"
        const val MAX_CHARS = 4000
        const val MAX_BYTES = 12_000

        /** A sender's transcript, trimmed and capped (null when nothing usable was heard). */
        fun of(text: String?, lang: String): PhoneTranscript? {
            var t = text?.trim()?.replace(Regex("\\s+"), " ") ?: return null
            if (t.isEmpty() || lang.length !in 2..35) return null
            if (t.length > MAX_CHARS) t = t.take(MAX_CHARS).trimEnd()
            while (t.toByteArray(Charsets.UTF_8).size > MAX_BYTES) t = t.dropLast(1)
            return PhoneTranscript(t, lang)
        }

        /** Lenient receive-side check: anything off → null (ignored), never fatal for the note. */
        fun parse(el: kotlinx.serialization.json.JsonElement?): PhoneTranscript? {
            val o = el as? JsonObject ?: return null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val text = s("text")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_CHARS && it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES } ?: return null
            val lang = s("lang")?.takeIf { it.length in 2..35 && it.all { c -> c.isLetterOrDigit() || c == '-' } } ?: return null
            val engine = s("engine")?.takeIf { it.length in 1..64 } ?: return null
            return PhoneTranscript(text, lang, engine)
        }
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
