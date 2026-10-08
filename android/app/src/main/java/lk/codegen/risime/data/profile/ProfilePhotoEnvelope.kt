package lk.codegen.risime.data.profile

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lk.codegen.risime.data.media.ImageEnc
import lk.codegen.risime.data.media.ImageEnvelope
import lk.codegen.risime.data.media.MediaFormat
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.ProtocolJson
import java.util.Base64

/**
 * The §14.4 icon object: a blob reference with its key, the mime and the square size. Used by the
 * §18.1 `profile_photo` envelope (JPEG only, 64–512 px, under the 512 KiB `avatar` cap) and by
 * `group_meta.icon` (§14.4/§18.7: JPEG/PNG/WebP, ≤ 512 px, under the 512 KiB `icon` cap).
 */
class PhotoRef(val blob: BlobRef, val enc: ImageEnc, val mime: String, val w: Int, val h: Int) {
    fun toJson(): JsonObject = buildJsonObject {
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
                put("key", Base64.getEncoder().encodeToString(enc.key))
                put("plain_size", enc.plainSize)
            },
        )
        put("mime", mime)
        put("w", w)
        put("h", h)
    }

    override fun equals(other: Any?) = other is PhotoRef && blob == other.blob && enc == other.enc && mime == other.mime && w == other.w && h == other.h

    override fun hashCode() = blob.hashCode()

    companion object {
        /** §18.3 / §14.5: the `avatar` and `icon` caps on the ciphertext. */
        const val MAX_CIPHER = 512L * 1024
        const val MIN_SIDE = 64
        const val MAX_SIDE = 512

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.num(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        private fun b64(s: String?): ByteArray? = s?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

        /**
         * Strict validation of an icon object (§18.1 for a profile photo, §14.4 for a group icon).
         * [profile]: `mime` exactly JPEG and `64 ≤ w == h ≤ 512`; otherwise any of the §14.4 image
         * types with `1 ≤ w, h ≤ 512`. Null = malformed.
         */
        fun validate(o: JsonObject, profile: Boolean): PhotoRef? = runCatching {
            val blob = o["blob"] as? JsonObject ?: return null
            val enc = o["enc"] as? JsonObject ?: return null
            val blobId = blob.str("blob_id")?.takeIf { it.isNotBlank() } ?: return null
            val size = blob.num("size") ?: return null
            val sha = blob.str("sha256") ?: return null
            if (b64(sha)?.size != 32) return null
            if (size < 17 || size > MAX_CIPHER) return null
            if (enc.str("alg") != MediaFormat.ALG) return null
            val key = b64(enc.str("key"))?.takeIf { it.size == 32 } ?: return null
            val plain = enc.num("plain_size")?.takeIf { it >= 1 } ?: return null
            if (MediaFormat.cipherSize(plain) != size) return null
            val mime = o.str("mime") ?: return null
            val w = o.num("w") ?: return null
            val h = o.num("h") ?: return null
            if (profile) {
                if (mime != ImageEnvelope.MIME_JPEG) return null
                if (w != h || w !in MIN_SIDE..MAX_SIDE) return null
            } else {
                if (mime !in ImageEnvelope.IMAGE_MIMES) return null
                if (w !in 1..MAX_SIDE || h !in 1..MAX_SIDE) return null
            }
            PhotoRef(BlobRef(blobId, size, sha), ImageEnc(MediaFormat.ALG, key, plain), mime, w.toInt(), h.toInt())
        }.getOrNull()

        /** `group_meta.icon` as kept verbatim in [lk.codegen.risime.net.GroupMeta]; null = none or malformed (initials). */
        fun groupIcon(e: JsonElement?): PhotoRef? = (e as? JsonObject)?.let { validate(it, profile = false) }
    }
}

/** The §18.1 `profile_photo` MLS application payload, after strict validation. [photo] null = "no photo". */
class ProfilePhotoEnvelope(val ver: Long, val photo: PhotoRef?) {
    fun encode(): ByteArray = ProtocolJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("v", 1)
            put("type", TYPE)
            put("ver", ver)
            put("photo", photo?.toJson() ?: JsonNull)
        },
    ).toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?) = other is ProfilePhotoEnvelope && ver == other.ver && photo == other.photo

    override fun hashCode() = ver.hashCode()

    companion object {
        const val TYPE = "profile_photo"

        /** §18.1 crypto K2: `ver` at most the receiver's server-corrected now + 24 h. */
        const val FUTURE_MS = 24 * 60 * 60 * 1000L

        /**
         * Strict validation of the structure (§18.1); the `ver ≤ now + 24 h` window needs the
         * receiver's clock and is checked by [inWindow] when applying. Unknown fields (a `user_id`
         * among them, crypto K3) are ignored: the subject is always the MLS sender.
         */
        fun validate(o: JsonObject): ProfilePhotoEnvelope? {
            val v = (o["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull()
            if (v != 1) return null
            val ver = (o["ver"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toLongOrNull() ?: return null
            if (ver < 1) return null
            val photo = when (val p = o["photo"]) {
                JsonNull -> null
                is JsonObject -> PhotoRef.validate(p, profile = true) ?: return null
                else -> return null // absent or not an object
            }
            return ProfilePhotoEnvelope(ver, photo)
        }

        fun inWindow(ver: Long, serverNowMs: Long): Boolean = ver in 1..(serverNowMs + FUTURE_MS)

        /**
         * §18.1 crypto K1, per subject on every device: the higher `ver`; on an equal `ver` a `null`
         * photo; otherwise the greater `blob.sha256` compared as base64 strings. True when [new]
         * beats the stored ([curVer], [curSha]; [curSha] null = a stored "no photo").
         */
        fun wins(newVer: Long, newSha: String?, curVer: Long?, curSha: String?): Boolean {
            if (curVer == null) return true
            if (newVer != curVer) return newVer > curVer
            if (curSha == null) return false // a stored null already wins every tie
            if (newSha == null) return true
            return newSha > curSha
        }
    }
}
