package lk.codegen.risime.data.tabs

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import lk.codegen.risime.net.ProtocolJson

/**
 * §24.11 the two MLS application messages members send to Risi in Official:
 * `risi_request` ("@Risi" chip, menu items; free text is never parsed for intent, decision 066) and
 * `risi_action` (confirm / decline / edit on a card). Both are shown as small system lines (row kind `risi_ctl`).
 */
object RisiControl {
    const val TYPE_REQUEST = "risi_request"
    const val TYPE_ACTION = "risi_action"

    val REQUEST_ACTIONS = setOf("ask", "summarise", "report")
    val ACTIONS = setOf("confirm", "decline", "edit", "done", "offer_yes", "offer_not_now") + V125_ACTIONS

    /** §25.4 (v1.25): on a confirm card (`write_id`) or a group reminder (`reminder_id`). */
    val V125_ACTIONS: Set<String> get() = setOf("confirm_write", "cancel_write", "me_too", "not_me")

    /** `text` of an `ask`: 1-1000 grapheme clusters (§24.11). */
    const val MAX_ASK_GRAPHEMES = 1000

    /** `scope.since` is at most 24 h back (else the `out_of_window` error): stay a little inside it. */
    const val SUMMARY_WINDOW_MS = 24L * 3600_000 - 5 * 60_000

    private val ISO = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

    fun iso(ms: Long): String = ISO.format(java.time.Instant.ofEpochMilli(ms))

    fun request(requestId: String, action: String, text: String?, sinceMs: Long?): JsonObject = buildJsonObject {
        put("v", 1)
        put("type", TYPE_REQUEST)
        put("request_id", requestId)
        put("action", action)
        if (text == null) put("text", JsonNull) else put("text", text)
        if (sinceMs != null) put("scope", buildJsonObject { put("since", iso(sinceMs)) })
    }

    fun action(target: String, action: String, editText: String? = null, editDue: String? = null): JsonObject = buildJsonObject {
        put("v", 1)
        put("type", TYPE_ACTION)
        put("target", target)
        put("action", action)
        if (action == "edit") {
            put("edit", buildJsonObject {
                put("text", editText ?: "")
                if (editDue == null) put("due", JsonNull) else put("due", editDue)
            })
        } else {
            put("edit", JsonNull)
        }
    }

    fun encode(o: JsonObject): ByteArray = ProtocolJson.encodeToString(JsonObject.serializer(), o).toByteArray(Charsets.UTF_8)

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** Shape check of a received control (strict enough to render a line safely); false: dropped. */
    fun valid(o: JsonObject): Boolean {
        val v = (o["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull()
        if (v != 1) return false
        return when (str(o, "type")) {
            TYPE_REQUEST -> str(o, "request_id") != null && str(o, "action") in REQUEST_ACTIONS
            TYPE_ACTION -> str(o, "target") != null && str(o, "action") in ACTIONS
            else -> false
        }
    }

    /** Name-free text of a control (the row's `body`: chat list preview, search). */
    fun body(o: JsonObject): String = when (str(o, "type")) {
        TYPE_REQUEST -> when (str(o, "action")) {
            "summarise" -> "Asked Risi to summarise"
            "report" -> "Asked Risi for a report"
            else -> "Asked Risi" + (str(o, "text")?.let { ": " + it.take(120) } ?: "")
        }
        else -> when (str(o, "action")) {
            "confirm" -> "Confirmed"
            "decline" -> "Declined"
            "edit" -> "Edited a commitment"
            "done" -> "Marked a commitment done"
            "offer_yes" -> "Said yes to Risi's offer"
            "offer_not_now" -> "Said not now to Risi's offer"
            "confirm_write" -> "Tapped Add"
            "cancel_write" -> "Cancelled"
            "me_too" -> "Me too"
            "not_me" -> "Not me"
            else -> "Risi"
        }
    }

    private fun parse(json: String?): JsonObject? =
        runCatching { ProtocolJson.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull()?.takeIf { valid(it) }

    /** The small system line: "Kamal confirmed", "You asked Risi to summarise". [who] = "You" for me. */
    fun line(json: String?, who: String): String? {
        val o = parse(json) ?: return null
        return when (str(o, "type")) {
            TYPE_REQUEST -> when (str(o, "action")) {
                "summarise" -> "$who asked Risi to summarise"
                "report" -> "$who asked Risi for a report"
                else -> "$who asked Risi" + (str(o, "text")?.let { ": " + it.take(120) } ?: "")
            }
            else -> when (str(o, "action")) {
                "confirm" -> "$who confirmed"
                "decline" -> "$who declined"
                "edit" -> "$who edited a commitment"
                "done" -> "$who marked a commitment done"
                "offer_yes" -> "$who said yes to Risi's offer"
                "offer_not_now" -> "$who said not now to Risi's offer"
                "confirm_write" -> "$who tapped Add"
                "cancel_write" -> "$who cancelled"
                "me_too" -> "$who: me too"
                "not_me" -> "$who: not me"
                else -> null
            }
        }
    }

    /** §25.2 the text of a stored `ask` (the Risi chat shows it as the user's own bubble), or null. */
    fun askText(json: String?): String? =
        parse(json)?.takeIf { str(it, "type") == TYPE_REQUEST && str(it, "action") == "ask" }?.let { str(it, "text") }

    /** The `request_id` of a stored `risi_request` row, or null. */
    fun requestIdOf(json: String?): String? = parse(json)?.takeIf { str(it, "type") == TYPE_REQUEST }?.let { str(it, "request_id") }

    /** The action of a stored `risi_action` row, or null. */
    fun actionOf(json: String?): String? = parse(json)?.takeIf { str(it, "type") == TYPE_ACTION }?.let { str(it, "action") }

    /** The target of a stored `risi_action` row, or null. */
    fun targetOf(json: String?): String? = parse(json)?.takeIf { str(it, "type") == TYPE_ACTION }?.let { str(it, "target") }
}

/**
 * What the Official screen sends to Risi. Only in Official ([isOfficial]); in Private every call is a
 * no-op that returns false, so no Risi affordance can reach a Private chat (§24.9).
 */
class RisiRequests(
    private val isOfficial: suspend () -> Boolean,
    private val send: suspend (JsonObject) -> Unit,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val graphemes: (String) -> Int = { it.codePointCount(0, it.length) },
) {
    /** The @Risi chip: a structured `ask`; 1-1000 characters, never inferred from free text. */
    suspend fun ask(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || graphemes(t) > RisiControl.MAX_ASK_GRAPHEMES || !isOfficial()) return false
        send(RisiControl.request(newId(), "ask", t, null))
        return true
    }

    suspend fun summarise(): Boolean {
        if (!isOfficial()) return false
        send(RisiControl.request(newId(), "summarise", null, now() - RisiControl.SUMMARY_WINDOW_MS))
        return true
    }

    suspend fun report(): Boolean {
        if (!isOfficial()) return false
        send(RisiControl.request(newId(), "report", null, now() - RisiControl.SUMMARY_WINDOW_MS))
        return true
    }

    suspend fun act(target: String, action: String, editText: String? = null, editDue: String? = null): Boolean {
        if (action !in RisiControl.ACTIONS || !isOfficial()) return false
        send(RisiControl.action(target, action, editText, editDue))
        return true
    }
}
