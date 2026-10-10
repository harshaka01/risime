package lk.codegen.risime.data.media

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.ProtocolJson
import java.util.Base64

/** The inline thumbnail of an image envelope (§14.4): JPEG or WebP, ≤ 128 px, ≤ 4096 bytes. */
class ImageThumb(val mime: String, val w: Int, val h: Int, val data: ByteArray) {
    override fun equals(other: Any?) = other is ImageThumb && mime == other.mime && w == other.w && h == other.h && data.contentEquals(other.data)

    override fun hashCode() = data.contentHashCode()
}

/** The `enc` object: the content key K (32 bytes) and the plaintext size. */
class ImageEnc(val alg: String, val key: ByteArray, val plainSize: Long) {
    override fun equals(other: Any?) = other is ImageEnc && alg == other.alg && key.contentEquals(other.key) && plainSize == other.plainSize

    override fun hashCode() = key.contentHashCode()
}

/** The §14.4 `image` MLS application payload, after strict validation. */
data class ImageEnvelope(
    val blob: BlobRef,
    val enc: ImageEnc,
    val mime: String,
    val w: Int,
    val h: Int,
    val thumb: ImageThumb?,
    val caption: String?,
    /** v1.34 §33: `forwarded`, `reply_to` and the reserved `view_once`. */
    val extras: lk.codegen.risime.net.EnvelopeExtras = lk.codegen.risime.net.EnvelopeExtras.NONE,
) {
    /** UTF-8 JSON; `thumb` is set to null when a long caption would push it over [MAX_ENVELOPE_BYTES]. */
    fun encode(): ByteArray {
        val full = encodeRaw(thumb)
        if (full.size <= MAX_ENVELOPE_BYTES || thumb == null) return full
        return encodeRaw(null)
    }

    private fun encodeRaw(t: ImageThumb?): ByteArray = ProtocolJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("v", 1)
            put("type", TYPE)
            put(
                "blob",
                buildJsonObject {
                    put("blob_id", blob.blobId)
                    put("size", blob.size)
                    put("sha256", blob.sha256)
                },
            )
            put(
                "enc",
                buildJsonObject {
                    put("alg", enc.alg)
                    put("key", b64e.encodeToString(enc.key))
                    put("plain_size", enc.plainSize)
                },
            )
            put("mime", mime)
            put("w", w)
            put("h", h)
            if (t == null) {
                put("thumb", JsonNull)
            } else {
                put(
                    "thumb",
                    buildJsonObject {
                        put("mime", t.mime)
                        put("w", t.w)
                        put("h", t.h)
                        put("data", b64e.encodeToString(t.data))
                    },
                )
            }
            caption?.takeIf { it.isNotEmpty() }?.let { put("caption", it) }
            extras.putInto(this)
        },
    ).toByteArray(Charsets.UTF_8)

    companion object {
        const val TYPE = "image"
        const val MAX_ENVELOPE_BYTES = 22_528
        const val MAX_THUMB_BYTES = 4096
        const val MAX_SIDE = 2048
        const val MAX_THUMB_SIDE = 128
        const val MIME_JPEG = "image/jpeg"
        const val MIME_PNG = "image/png"
        const val MIME_WEBP = "image/webp"
        val IMAGE_MIMES = setOf(MIME_JPEG, MIME_PNG, MIME_WEBP)
        val THUMB_MIMES = setOf(MIME_JPEG, MIME_WEBP)
        private val b64e = Base64.getEncoder()

        /**
         * Strict validation (§14.4, crypto R4, android R3) of a decoded JSON object whose `type` is
         * "image". Null = malformed: drop and log, never store, fetch or decode.
         */
        fun validate(obj: JsonObject): ImageEnvelope? = runCatching { validateOrThrow(obj) }.getOrNull()

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.num(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject

        private fun b64(s: String?): ByteArray? = s?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

        private fun validateOrThrow(o: JsonObject): ImageEnvelope? {
            val blob = o.obj("blob") ?: return null
            val enc = o.obj("enc") ?: return null
            val blobId = blob.str("blob_id")?.takeIf { it.isNotBlank() } ?: return null
            val size = blob.num("size") ?: return null
            val sha = blob.str("sha256") ?: return null
            if (b64(sha)?.size != 32) return null
            if (size < 17 || size > MediaFormat.MAX_MEDIA_CIPHER) return null
            if (enc.str("alg") != MediaFormat.ALG) return null
            val key = b64(enc.str("key"))?.takeIf { it.size == 32 } ?: return null
            val plain = enc.num("plain_size")?.takeIf { it >= 1 } ?: return null
            if (MediaFormat.cipherSize(plain) != size) return null
            val mime = o.str("mime")?.takeIf { it in IMAGE_MIMES } ?: return null
            val w = o.num("w")?.takeIf { it in 1..MAX_SIDE.toLong() } ?: return null
            val h = o.num("h")?.takeIf { it in 1..MAX_SIDE.toLong() } ?: return null
            val thumb = when (val t = o["thumb"]) {
                null, JsonNull -> null
                is JsonObject -> {
                    val tm = t.str("mime")?.takeIf { it in THUMB_MIMES } ?: return null
                    val tw = t.num("w")?.takeIf { it in 1..MAX_THUMB_SIDE.toLong() } ?: return null
                    val th = t.num("h")?.takeIf { it in 1..MAX_THUMB_SIDE.toLong() } ?: return null
                    val data = b64(t.str("data"))?.takeIf { it.isNotEmpty() && it.size <= MAX_THUMB_BYTES } ?: return null
                    ImageThumb(tm, tw.toInt(), th.toInt(), data)
                }
                else -> return null
            }
            val caption = when (val c = o["caption"]) {
                null, JsonNull -> null
                is JsonPrimitive -> if (c.isString) c.content.takeIf { it.isNotBlank() }?.let(::cutCaption) else return null
                else -> return null
            }
            return ImageEnvelope(BlobRef(blobId, size, sha), ImageEnc(MediaFormat.ALG, key, plain), mime, w.toInt(), h.toInt(), thumb, caption, lk.codegen.risime.net.EnvelopeExtras.of(o))
        }

        /** Receivers cut a caption at 4096 graphemes (§14.4, §11.1). */
        private fun cutCaption(s: String): String {
            if (s.length <= 4096) return s
            val bi = java.text.BreakIterator.getCharacterInstance()
            bi.setText(s)
            var end = 0
            repeat(4096) { val n = bi.next(); if (n == java.text.BreakIterator.DONE) return s; end = n }
            return s.substring(0, end)
        }
    }
}
