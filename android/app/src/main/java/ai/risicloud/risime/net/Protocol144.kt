package ai.risicloud.risime.net

import ai.risicloud.risime.data.media.ImageEnvelope
import ai.risicloud.risime.data.media.ImageThumb
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * v1.44 §39 link previews: the optional `link_preview` of a `text` envelope. The SENDER's phone fetches it
 * (data/linkpreview) and sends it inside the E2EE message; receivers render it from the message alone and never
 * fetch. Additive only: no capability, no server logic.
 */

/** §39.1 a validated `link_preview` (the thumb follows the §14.4 thumbnail rules). */
data class LinkPreview(
    val url: String,
    val title: String,
    val description: String? = null,
    val siteName: String? = null,
    val thumb: ImageThumb? = null,
) {
    fun toJson(withThumb: Boolean = true): JsonObject = buildJsonObject {
        put("url", url)
        put("title", title)
        description?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
        siteName?.takeIf { it.isNotEmpty() }?.let { put("site_name", it) }
        val t = thumb?.takeIf { withThumb }
        if (t == null) put("thumb", JsonNull) else put(
            "thumb",
            buildJsonObject {
                put("mime", t.mime); put("w", t.w); put("h", t.h)
                put("data", java.util.Base64.getEncoder().encodeToString(t.data))
            },
        )
    }

    /** The Room column (`messages.link_preview`): the validated object as JSON. */
    fun encode(): String = ProtocolJson.encodeToString(JsonObject.serializer(), toJson())

    companion object {
        const val FIELD = "link_preview"
        const val MAX_URL = 2048
        const val MAX_TITLE = 200
        const val MAX_DESCRIPTION = 300
        const val MAX_SITE_NAME = 100

        /** §39.1 receiver validation: null (ignored; the message is still shown) when any rule is broken. */
        fun validate(el: JsonElement?, body: String): LinkPreview? = runCatching { validateOrNull(el, body) }.getOrNull()

        private fun validateOrNull(el: JsonElement?, body: String): LinkPreview? {
            val o = el as? JsonObject ?: return null
            fun str(k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val url = str("url")?.takeIf { it.length <= MAX_URL && isHttps(it) && body.contains(it) } ?: return null
            val title = str("title")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE } ?: return null
            if (o.containsKey("description") && o["description"] !is JsonNull && str("description") == null) return null
            val description = str("description")?.trim()?.also { if (it.length > MAX_DESCRIPTION) return null }?.takeIf { it.isNotEmpty() }
            if (o.containsKey("site_name") && o["site_name"] !is JsonNull && str("site_name") == null) return null
            val site = str("site_name")?.trim()?.also { if (it.length > MAX_SITE_NAME) return null }?.takeIf { it.isNotEmpty() }
            val thumb = when (val t = o["thumb"]) {
                null, JsonNull -> null
                is JsonObject -> thumb(t) ?: return null
                else -> return null
            }
            return LinkPreview(url, title, description, site, thumb)
        }

        private fun thumb(t: JsonObject): ImageThumb? {
            fun s(k: String) = (t[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun n(k: String) = (t[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            val mime = s("mime")?.takeIf { it in ImageEnvelope.THUMB_MIMES } ?: return null
            val w = n("w")?.takeIf { it in 1..ImageEnvelope.MAX_THUMB_SIDE.toLong() } ?: return null
            val h = n("h")?.takeIf { it in 1..ImageEnvelope.MAX_THUMB_SIDE.toLong() } ?: return null
            val data = s("data")?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() && it.size <= ImageEnvelope.MAX_THUMB_BYTES } ?: return null
            return ImageThumb(mime, w.toInt(), h.toInt(), data)
        }

        /** The Room column back (null when absent or no longer valid against [body]). */
        fun decode(json: String?, body: String): LinkPreview? =
            json?.let { runCatching { validate(ProtocolJson.parseToJsonElement(it), body) }.getOrNull() }

        fun isHttps(u: String): Boolean = runCatching {
            val uri = java.net.URI(u)
            uri.scheme.equals("https", ignoreCase = false) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
        }.getOrDefault(false)

        /** Senders cut long fields at a grapheme boundary with "…" (§39.1). */
        fun cut(s: String?, max: Int): String? {
            val t = s?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (t.length <= max) return t
            val bi = java.text.BreakIterator.getCharacterInstance()
            bi.setText(t)
            val end = bi.preceding(max) // a boundary < max leaves room for the ellipsis
            return t.substring(0, if (end <= 0) max - 1 else end).trimEnd() + "…"
        }
    }
}

/** §39.4 links in a message body: `http(s)://…` and `www.…`, trailing punctuation left out. */
object LinkUrls {
    private val RX = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"'`]+""")
    private const val TRAIL = ".,;:!?…)]}*_~"

    data class Span(val start: Int, val end: Int, val text: String) {
        /** What tapping opens (`www.` gets https). */
        val target: String get() = if (text.startsWith("www.", ignoreCase = true)) "https://$text" else text
    }

    fun find(body: String): List<Span> = RX.findAll(body).mapNotNull { m ->
        var end = m.range.last + 1
        while (end > m.range.first && body[end - 1] in TRAIL) {
            // keep a closing paren that balances one inside the link (Wikipedia-style URLs)
            if (body[end - 1] == ')' && body.substring(m.range.first, end).count { it == '(' } >= body.substring(m.range.first, end).count { it == ')' }) break
            end--
        }
        val text = body.substring(m.range.first, end)
        if (text.length < 8 || text.endsWith("://")) null else Span(m.range.first, end, text)
    }.toList()

    /** §39.3 the URL a sender previews: the first `https://` link of the body. */
    fun firstHttps(body: String): String? = find(body).firstOrNull { it.text.startsWith("https://") && LinkPreview.isHttps(it.text) && it.text.length <= LinkPreview.MAX_URL }?.text
}
