package lk.codegen.risime.data.media

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.EnvelopeExtras
import lk.codegen.risime.net.ProtocolJson
import java.text.Normalizer
import java.util.Base64

/**
 * v1.34 §33.13 the minimal `file` MLS application payload, after strict validation. [blob]/[enc] are
 * null only for a `parts` file (reserved for A): a placeholder bubble "Update RisiMe to open this file".
 * [mime] is already the sanitised hint (`application/octet-stream` when malformed); it is never trusted
 * for in-app rendering: received files are only ever opened by an external app.
 */
data class FileEnvelope(
    val blob: BlobRef?,
    val enc: ImageEnc?,
    val name: String,
    val mime: String,
    val thumb: ImageThumb?,
    val pages: Int?,
    val caption: String?,
    val extras: EnvelopeExtras = EnvelopeExtras.NONE,
) {
    /** A multi-part file this build can't open (`parts` without `blob`). */
    val partsOnly: Boolean get() = blob == null

    /** §33.13: receivers strip leading dots and cut at 120 graphemes for display and saving. */
    val displayName: String get() = displayName(name)

    /** §33.13: an app installer has no Open, only Save with a warning. */
    val isApk: Boolean get() = isApk(mime, name)

    fun encode(): ByteArray {
        val full = encodeRaw(thumb)
        if (full.size <= ImageEnvelope.MAX_ENVELOPE_BYTES || thumb == null) return full
        return encodeRaw(null)
    }

    private fun encodeRaw(t: ImageThumb?): ByteArray {
        val b = requireNotNull(blob) { "a parts file is never sent by v1.34" }
        val e = requireNotNull(enc)
        val b64 = Base64.getEncoder()
        return ProtocolJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("v", 1)
                put("type", TYPE)
                put("blob", buildJsonObject { put("blob_id", b.blobId); put("size", b.size); put("sha256", b.sha256) })
                put("enc", buildJsonObject { put("alg", e.alg); put("key", b64.encodeToString(e.key)); put("plain_size", e.plainSize) })
                put("name", name)
                put("mime", mime)
                if (t == null) {
                    put("thumb", JsonNull)
                } else {
                    put("thumb", buildJsonObject { put("mime", t.mime); put("w", t.w); put("h", t.h); put("data", b64.encodeToString(t.data)) })
                }
                pages?.let { put("pages", it) }
                caption?.takeIf { it.isNotEmpty() }?.let { put("caption", it) }
                extras.putInto(this)
            },
        ).toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val TYPE = "file"
        const val OCTET_STREAM = "application/octet-stream"
        const val MIME_PDF = "application/pdf"
        const val MIME_APK = "application/vnd.android.package-archive"
        const val MAX_NAME_BYTES = 255
        const val MAX_DISPLAY_GRAPHEMES = 120
        const val MAX_PAGES = 10_000

        /** Auto-download only up to this many plaintext bytes on unmetered networks (§33.13). */
        const val AUTO_DOWNLOAD_MAX = 2L * 1024 * 1024
        private val MIME_RE = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$")

        fun validate(obj: JsonObject): FileEnvelope? = runCatching { validateOrThrow(obj) }.getOrNull()

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.num(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        private fun b64(s: String?): ByteArray? = s?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

        /** A forbidden code point in a file name (§33.13): C0/C1 controls, bidi overrides/isolates, `/`, `\`. */
        fun forbidden(cp: Int): Boolean =
            cp < 0x20 || cp in 0x7F..0x9F || cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == '/'.code || cp == '\\'.code

        /** §33.13 receiver rule: 1–255 bytes, NFC, no forbidden characters. */
        fun validName(n: String): Boolean {
            val bytes = n.toByteArray(Charsets.UTF_8).size
            if (bytes !in 1..MAX_NAME_BYTES) return false
            if (!Normalizer.isNormalized(n, Normalizer.Form.NFC)) return false
            return n.codePoints().noneMatch { forbidden(it) }
        }

        /** §33.13 sender rule: forbidden characters → `-`, NFC, trimmed, cut to 255 bytes on a grapheme boundary. */
        fun sanitizeName(raw: String): String {
            val sb = StringBuilder()
            Normalizer.normalize(raw, Normalizer.Form.NFC).codePoints().forEach { cp -> if (forbidden(cp)) sb.append('-') else sb.appendCodePoint(cp) }
            val trimmed = sb.toString().trim()
            val cut = cutBytes(trimmed, MAX_NAME_BYTES)
            return cut.ifEmpty { "file" }
        }

        /** Cuts [s] to at most [maxBytes] UTF-8 bytes on a grapheme boundary. */
        fun cutBytes(s: String, maxBytes: Int): String {
            if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
            val bi = java.text.BreakIterator.getCharacterInstance()
            bi.setText(s)
            var end = 0
            while (true) {
                val n = bi.next()
                if (n == java.text.BreakIterator.DONE) break
                if (s.substring(0, n).toByteArray(Charsets.UTF_8).size > maxBytes) break
                end = n
            }
            return s.substring(0, end)
        }

        /** Cuts [s] at [max] graphemes. */
        fun cutGraphemes(s: String, max: Int): String {
            val bi = java.text.BreakIterator.getCharacterInstance()
            bi.setText(s)
            var end = 0
            repeat(max) { val n = bi.next(); if (n == java.text.BreakIterator.DONE) return s; end = n }
            return s.substring(0, end)
        }

        fun displayName(name: String): String = cutGraphemes(name.trimStart('.'), MAX_DISPLAY_GRAPHEMES).ifBlank { "file" }

        /** The sanitised mime hint: lowercase `type/subtype` per §33.13, else octet-stream. */
        fun sanitizeMime(m: String?): String = m?.takeIf { MIME_RE.matches(it) } ?: OCTET_STREAM

        fun isApk(mime: String, name: String): Boolean = mime == MIME_APK || name.trimEnd().endsWith(".apk", ignoreCase = true)

        /** The type icon shown on the card (PDF, DOC, XLS, ZIP, generic). */
        fun iconKind(mime: String, name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                mime == MIME_PDF || ext == "pdf" -> "PDF"
                ext in setOf("doc", "docx", "odt", "rtf") || mime.contains("word") -> "DOC"
                ext in setOf("xls", "xlsx", "ods", "csv") || mime.contains("sheet") || mime.contains("excel") -> "XLS"
                ext in setOf("zip", "7z", "rar", "tar", "gz") || mime.contains("zip") -> "ZIP"
                else -> "FILE"
            }
        }

        private fun validateOrThrow(o: JsonObject): FileEnvelope? {
            val name = o.str("name")?.takeIf(::validName) ?: return null
            val mime = sanitizeMime(o.str("mime"))
            val pages = when (val p = o["pages"]) {
                null, JsonNull -> null
                else -> (p as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 1..MAX_PAGES.toLong() }?.toInt() ?: return null
            }
            val thumb = when (val t = o["thumb"]) {
                null, JsonNull -> null
                is JsonObject -> {
                    val tm = t.str("mime")?.takeIf { it in ImageEnvelope.THUMB_MIMES } ?: return null
                    val tw = t.num("w")?.takeIf { it in 1..ImageEnvelope.MAX_THUMB_SIDE.toLong() } ?: return null
                    val th = t.num("h")?.takeIf { it in 1..ImageEnvelope.MAX_THUMB_SIDE.toLong() } ?: return null
                    val data = b64(t.str("data"))?.takeIf { it.isNotEmpty() && it.size <= ImageEnvelope.MAX_THUMB_BYTES } ?: return null
                    ImageThumb(tm, tw.toInt(), th.toInt(), data)
                }
                else -> return null
            }
            val caption = when (val c = o["caption"]) {
                null, JsonNull -> null
                is JsonPrimitive -> if (c.isString) c.content.takeIf { it.isNotBlank() }?.let { cutGraphemes(it, 4096) } else return null
                else -> return null
            }
            val extras = EnvelopeExtras.of(o)
            val blobObj = o["blob"] as? JsonObject
            if (blobObj == null) {
                // §33.13: `parts` (A) without `blob`: a placeholder, never dropped.
                if (o["parts"] !is kotlinx.serialization.json.JsonArray) return null
                return FileEnvelope(null, null, name, mime, thumb, pages, caption, extras)
            }
            val enc = o["enc"] as? JsonObject ?: return null
            val blobId = blobObj.str("blob_id")?.takeIf { it.isNotBlank() } ?: return null
            val size = blobObj.num("size") ?: return null
            val sha = blobObj.str("sha256") ?: return null
            if (b64(sha)?.size != 32) return null
            if (size < 17 || size > MediaFormat.MAX_MEDIA_CIPHER) return null
            if (enc.str("alg") != MediaFormat.ALG) return null
            val key = b64(enc.str("key"))?.takeIf { it.size == 32 } ?: return null
            val plain = enc.num("plain_size")?.takeIf { it >= 1 } ?: return null
            if (MediaFormat.cipherSize(plain) != size) return null
            return FileEnvelope(BlobRef(blobId, size, sha), ImageEnc(MediaFormat.ALG, key, plain), name, mime, thumb, pages, caption, extras)
        }
    }
}
