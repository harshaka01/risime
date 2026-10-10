package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * v1.34 §33 basic messaging (NEXT-PHASE E): `forwarded`, `reply_to`, the reserved `view_once`, the
 * `files` / `pdf_export` capabilities, PdfSource and the Risi `export_pdf` client tool. Additive only.
 */

/** §33.1: advertised once the app can receive, validate and open a `file` envelope. */
const val CAPABILITY_FILES = "files"

/** §33.1: the Risi `export_pdf` tool and the `pdf` chip (kept by the server only with `risi_tools`). */
const val CAPABILITY_PDF_EXPORT = "pdf_export"

/**
 * The §33 envelope extras shared by `text`, `image`, `file` (and A's types): the validated
 * `forwarded.hops` (null = absent or invalid, §33.4), the validated `reply_to` (§33.9) and whether
 * the envelope carries the reserved `view_once` key (§33.0, any value).
 */
data class EnvelopeExtras(val forwardHops: Int? = null, val replyTo: ReplyRef? = null, val viewOnce: Boolean = false) {
    val forwardedMany: Boolean get() = (forwardHops ?: 0) >= Forwarding.MANY_TIMES

    /** Adds `forwarded` / `reply_to` to an envelope being built (never `view_once`: v1.34 never sends one). */
    fun putInto(b: kotlinx.serialization.json.JsonObjectBuilder) {
        forwardHops?.let { h -> b.put("forwarded", buildJsonObject { put("hops", h) }) }
        replyTo?.let { r -> b.put("reply_to", buildJsonObject { put("message_id", r.messageId); put("from", r.from) }) }
    }

    companion object {
        val NONE = EnvelopeExtras()

        /** Reads the extras of a decoded envelope object; a bad `forwarded` / `reply_to` is ignored, never fatal. */
        fun of(obj: JsonObject): EnvelopeExtras {
            val e = EnvelopeExtras(Forwarding.hops(obj["forwarded"]), ReplyRef.parse(obj["reply_to"]), ViewOnce.carries(obj))
            return if (e == NONE) NONE else e
        }
    }
}

/** §33.4 the `forwarded` field. */
object Forwarding {
    const val MAX_HOPS = 255

    /** "Forwarded many times" from this many hops (and then one target only). */
    const val MANY_TIMES = 5

    /** Valid only as an object whose `hops` is an integer 1..255; anything else is ignored (null). */
    fun hops(el: JsonElement?): Int? {
        val o = el as? JsonObject ?: return null
        val p = o["hops"] as? JsonPrimitive ?: return null
        if (p.isString) return null
        if (p.content.contains('.') || p.content.contains('e', true)) return null
        val n = p.longOrNull ?: return null
        return n.takeIf { it in 1..MAX_HOPS }?.toInt()
    }

    /**
     * The `hops` of a forward of [source] (§33.4): +1 when the source was forwarded, 1 for someone
     * else's unforwarded message, null (no field) for the user's own unforwarded message; ≤ 255.
     */
    fun nextHops(sourceHops: Int?, sourceIsOwn: Boolean): Int? = when {
        sourceHops != null -> (sourceHops + 1).coerceAtMost(MAX_HOPS)
        sourceIsOwn -> null
        else -> 1
    }
}

/** §33.9 `reply_to`: `{"message_id": "<timeuuid>", "from": "<user uuid>"}`. No snippet travels. */
data class ReplyRef(val messageId: String, val from: String) {
    companion object {
        /** Null (ignored, the message is still shown) when malformed. */
        fun parse(el: JsonElement?): ReplyRef? {
            val o = el as? JsonObject ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val mid = str("message_id")?.let(::uuid)?.takeIf { it.version() == 1 } ?: return null
            val from = str("from")?.let(::uuid) ?: return null
            return ReplyRef(mid.toString(), from.toString())
        }

        private fun uuid(s: String): java.util.UUID? =
            runCatching { java.util.UUID.fromString(s) }.getOrNull()?.takeIf { it.toString().equals(s, true) }
    }
}

/** §33.0 the reserved `view_once` key: never copied, forwarded, shared, starred, quoted, exported or indexed. */
object ViewOnce {
    const val FIELD = "view_once"

    fun carries(obj: JsonObject): Boolean = obj.containsKey(FIELD)
}

/** §33.14 `PdfSource` (also on the wire, §33.15). Kept as its JSON object; [type] says which. */
object PdfSources {
    const val MESSAGE = "message"
    const val NOTE = "note"
    const val CALENDAR = "calendar"
    const val MY_RISI = "my_risi"
    val VIEWS = setOf("day", "week", "agenda")

    fun type(o: JsonObject?): String? = (o?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Structurally valid for this build (my_risi is reserved for B: not exportable yet). */
    fun valid(o: JsonObject?): Boolean {
        o ?: return false
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        return when (type(o)) {
            MESSAGE -> str("conversation_id") != null && str("message_id") != null
            NOTE -> str("note_id") != null
            CALENDAR -> str("view") in VIEWS && str("from") != null && str("to") != null
            else -> false
        }
    }

    fun note(noteId: String): JsonObject = buildJsonObject { put("type", NOTE); put("note_id", noteId) }

    fun message(conversationId: String, messageId: String): JsonObject =
        buildJsonObject { put("type", MESSAGE); put("conversation_id", conversationId); put("message_id", messageId) }

    fun calendar(view: String, from: String, to: String): JsonObject =
        buildJsonObject { put("type", CALENDAR); put("view", view); put("from", from); put("to", to) }
}

/** §33.15 a `confirm` card's `export`: what the phone renders and where it goes. */
@Serializable
data class RisiExport(
    val source: JsonObject,
    @SerialName("conversation_id") val conversationId: String,
)

/** §33.15 `risi_tool_call` `export_pdf` args. */
@Serializable
data class ExportPdfArgs(
    @SerialName("write_id") val writeId: String,
    val source: JsonObject,
    @SerialName("conversation_id") val conversationId: String,
)

/** §33.15 `ok` result: `{"state": "sent" | "queued", "pages": n}`. */
@Serializable
data class ExportPdfResult(val state: String, val pages: Int) {
    companion object {
        const val SENT = "sent"
        const val QUEUED = "queued"
    }
}

/** §33.15 `error` codes (in `result.code`, `risi_tool_result_export_pdf_error.json`). */
object ExportPdfErrors {
    const val SOURCE_UNAVAILABLE = "source_unavailable"
    const val NOT_MEMBER = "not_member"
    const val FILES_NOT_READY = "files_not_ready"
    const val TOO_LARGE = "too_large"
    const val PDF_FAILED = "pdf_failed"
    val ALL = setOf(SOURCE_UNAVAILABLE, NOT_MEMBER, FILES_NOT_READY, TOO_LARGE, PDF_FAILED)
}

object RisiTools134 {
    const val TOOL_EXPORT_PDF = "export_pdf"

    /** §33.15 the export confirm's buttons. */
    const val BUTTON_SEND = "send"
    const val BUTTON_CANCEL = "cancel"

    /** §33.15 the `next_actions` action that renders a PDF locally. */
    const val ACTION_PDF = "pdf"
}

fun RisiToolCall.exportPdfArgs(): ExportPdfArgs? =
    runCatching { ProtocolJson.decodeFromJsonElement(ExportPdfArgs.serializer(), args) }.getOrNull()
