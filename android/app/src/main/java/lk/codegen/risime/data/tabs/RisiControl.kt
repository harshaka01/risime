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
    val ACTIONS = setOf("confirm", "decline", "edit", "done", "offer_yes", "offer_not_now") + V125_ACTIONS + V126_ACTIONS + V127_ACTIONS

    /** §25.4 (v1.25): on a confirm card (`write_id`) or a group reminder (`reminder_id`). */
    val V125_ACTIONS: Set<String> get() = setOf("confirm_write", "cancel_write", "me_too", "not_me")

    /** §26.7 (v1.26): on a `calendar_offer` (`offer_id`). */
    val V126_ACTIONS: Set<String> get() = setOf("calendar_accept", "calendar_decline")

    /** §27.5 (v1.27): on a ledger item (`item_id`), sent in the actor's own Risi chat. */
    val V127_ACTIONS: Set<String> get() = setOf("item_confirm", "item_decline", "item_edit")

    /** §27.5 `item_edit.edit.text`: 1-200 characters. */
    const val MAX_ITEM_TEXT = 200

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

    /** Proposal 2026-10-09-risi-30day-summaries: `summarise` with a `scope` object (`{"period"}` or `{"from","to"}`). */
    fun request(requestId: String, action: String, scope: JsonObject): JsonObject = buildJsonObject {
        put("v", 1)
        put("type", TYPE_REQUEST)
        put("request_id", requestId)
        put("action", action)
        put("text", JsonNull)
        put("scope", scope)
    }

    /** "today" | "7d" | "30d" (an older server reads no `since` and answers its 24-h default). */
    val PERIODS = listOf("today", "7d", "30d")

    /** A date range is at most 31 days back (else `out_of_window`). */
    const val RANGE_MAX_DAYS = 31L

    fun action(target: String, action: String, editText: String? = null, editDue: String? = null, reminder: Boolean? = null, editAllDay: Boolean? = null, writeEdit: JsonObject? = null): JsonObject = buildJsonObject {
        put("v", 1)
        put("type", TYPE_ACTION)
        put("target", target)
        put("action", action)
        if (action == "item_edit") {
            // §27.5 {"text", "due", "all_day"}
            put("edit", lk.codegen.risime.net.RisiActions127.editObject((editText ?: "").take(MAX_ITEM_TEXT), editDue, editAllDay == true))
        } else if (action == "edit") {
            put("edit", buildJsonObject {
                put("text", editText ?: "")
                if (editDue == null) put("due", JsonNull) else put("due", editDue)
            })
        } else if (action == "confirm_write" && writeEdit != null) {
            // Proposal 2026-10-09-risi-action-loop §4: [Edit] on a calendar_add card (title/start/end/all_day).
            put("edit", writeEdit)
        } else {
            put("edit", JsonNull)
        }
        if (action == "calendar_accept") put("options", buildJsonObject { put("reminder", reminder == true) })
        else if (action == "calendar_decline") put("options", JsonNull)
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
            "calendar_accept" -> "Added to calendar"
            "calendar_decline" -> "Declined the meeting card"
            "item_confirm" -> "Confirmed an item"
            "item_decline" -> "Declined an item"
            "item_edit" -> "Edited an item"
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
                "calendar_accept" -> "$who added it to their calendar"
                "calendar_decline" -> "$who declined"
                "item_confirm" -> "$who confirmed an item"
                "item_decline" -> "$who declined an item"
                "item_edit" -> "$who edited an item"
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
    /** A `risi_action`'s `edit` object (null when absent or `null`). */
    fun editOf(json: String?): JsonObject? = parse(json)?.takeIf { str(it, "type") == TYPE_ACTION }?.get("edit") as? JsonObject

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

    /** Summarise a period ("today" | "7d" | "30d"): `scope {"period"}`. */
    suspend fun summarisePeriod(period: String): Boolean {
        if (period !in RisiControl.PERIODS || !isOfficial()) return false
        send(RisiControl.request(newId(), "summarise", buildJsonObject { put("period", period) }))
        return true
    }

    /** Summarise a date range: `scope {"from", "to"}` ([fromMs] at most 31 days back). */
    suspend fun summariseRange(fromMs: Long, toMs: Long): Boolean {
        if (toMs <= fromMs || now() - fromMs > RisiControl.RANGE_MAX_DAYS * 86_400_000L || !isOfficial()) return false
        send(RisiControl.request(newId(), "summarise", buildJsonObject { put("from", RisiControl.iso(fromMs)); put("to", RisiControl.iso(minOf(toMs, now()))) }))
        return true
    }

    suspend fun report(): Boolean {
        if (!isOfficial()) return false
        send(RisiControl.request(newId(), "report", null, now() - RisiControl.SUMMARY_WINDOW_MS))
        return true
    }

    /** [Add] after [Edit] on a calendar_add card: `confirm_write` with the corrected args. */
    suspend fun confirmEdited(writeId: String, edit: JsonObject): Boolean {
        if (!isOfficial()) return false
        send(RisiControl.action(writeId, "confirm_write", writeEdit = edit))
        return true
    }

    suspend fun act(target: String, action: String, editText: String? = null, editDue: String? = null, editAllDay: Boolean? = null): Boolean {
        if (action !in RisiControl.ACTIONS || !isOfficial()) return false
        if (action == "item_edit" && editText.isNullOrBlank()) return false
        send(RisiControl.action(target, action, editText, editDue, editAllDay = editAllDay))
        return true
    }
}
