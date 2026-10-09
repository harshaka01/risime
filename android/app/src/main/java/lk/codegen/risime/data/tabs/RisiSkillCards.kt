package lk.codegen.risime.data.tabs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.RisiSkillIds
import lk.codegen.risime.net.RisiToolCall
import lk.codegen.risime.net.RisiUndo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Contract v1.26 §26.5: the card rules of the skill kinds (confirm with `skill_id`/`args`,
 * `skill_done`, `skill_needed`). Pure functions; the cards in ui/tabs draw what these return.
 */
object RisiSkillCards {
    /** `confirm.tool` values this app can confirm; an unknown tool shows its summary without buttons (§26.9). */
    val CONFIRM_TOOLS = setOf(
        "set_reminder", RisiToolCall.TOOL_CALENDAR_ADD, "ask_risiwork",
        RisiToolCall.TOOL_SET_ALARM, RisiToolCall.TOOL_SCHEDULE_MESSAGE, RisiToolCall.TOOL_CANCEL_SCHEDULED,
    )

    fun knownConfirmTool(r: RisiMeta): Boolean = r.tool in CONFIRM_TOOLS

    private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val AT_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** "Mon, Wed" for ISO weekdays; "Every day" for all seven; null for a one-off. */
    fun daysText(days: List<Int>?): String? {
        if (days.isNullOrEmpty()) return null
        val d = days.filter { it in 1..7 }.distinct().sorted()
        if (d.size == 7) return "Every day"
        return d.joinToString(", ") { DAY_NAMES[it - 1] }
    }

    /** A timestamp in the phone's zone ("Sat 10 Oct, 06:00"); the raw value if it doesn't parse. */
    fun atText(ts: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
        ts?.let { runCatching { AT_FORMAT.format(Instant.parse(it).atZone(zone)) }.getOrDefault(it) }

    /**
     * §26.5 the card's exact `args`, one labelled line each, so the user sees precisely what the phone
     * will do (known tools in plain words; any other key as `key: value`). Empty without `args`.
     */
    fun argLines(r: RisiMeta, nameOfConversation: (String) -> String? = { null }, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, String>> {
        val a = r.args ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        val shown = HashSet<String>()
        fun add(label: String, key: String, value: String?) { shown += key; if (value != null) out += label to value }
        when (r.tool) {
            RisiToolCall.TOOL_SET_ALARM -> {
                add("Time", "time", str(a, "time"))
                add("Label", "label", str(a, "label")?.let { if (it.isEmpty()) "(none)" else "“$it”" })
                val days = (a["days"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                add("Repeats", "days", daysText(days) ?: "Once (next ${str(a, "time") ?: "time"})")
            }
            RisiToolCall.TOOL_SCHEDULE_MESSAGE -> {
                val conv = str(a, "conversation_id")
                add("To", "conversation_id", conv?.let { nameOfConversation(it) ?: "a chat not on this phone" })
                add("Message", "text", str(a, "text")?.let { "“$it”" })
                add("First send", "at", atText(str(a, "at"), zone))
                add("Repeats", "repeat", if (str(a, "repeat") == "daily") "Every day" else "Once")
            }
            RisiToolCall.TOOL_CANCEL_SCHEDULED -> add("Scheduled message", "schedule_id", str(a, "schedule_id"))
        }
        for ((k, v) in a) {
            if (k in shown || k == "write_id") continue
            val text = when (v) {
                is JsonNull -> "—"
                is JsonPrimitive -> v.content
                else -> v.toString()
            }
            out += k to text
        }
        return out
    }

    /** §26.5 [Undo] on a `skill_done`: a server/client undo with a token, before `until`, not undone here. */
    fun canUndo(r: RisiMeta, nowMs: Long, undoneHere: Set<String>): Boolean {
        val u = r.undo ?: return false
        if (u.kind != RisiUndo.UNDO_SERVER && u.kind != RisiUndo.UNDO_CLIENT) return false
        if (r.undoToken.isNullOrEmpty() || r.skillId == null || r.entryId == null) return false
        if (u.state != null && u.state != RisiUndo.AVAILABLE && u.state != RisiUndo.FAILED) return false
        if (r.entryId.lowercase() in undoneHere) return false
        val until = u.until?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        return until == null || nowMs < until
    }

    /** A `manual` undo: the hint (and "Open Clock" for an alarm). */
    fun manualHint(r: RisiMeta): String? = r.undo?.takeIf { it.kind == RisiUndo.UNDO_MANUAL }?.let { it.hint ?: "Remove it yourself" }

    fun opensClock(r: RisiMeta): Boolean = manualHint(r) != null && r.skillId == RisiSkillIds.ALARM

    /** §26.5 the `skill_needed` line (the server's `body` stays the fallback). */
    fun neededText(r: RisiMeta, body: String): String {
        val what = skillNoun(r.skillId) ?: return body
        return when (r.reason) {
            "off" -> if (r.wasOn == true) "I no longer have access to your $what. Turn it on in Settings → Risi skills."
            else "I don't have access to your $what yet. Turn it on in Settings → Risi skills."
            "no_permission" -> "${skillTitle(r.skillId)} permission is off on this phone."
            "unavailable" -> "${skillTitle(r.skillId)} is coming later."
            else -> body
        }
    }

    fun skillNoun(id: String?): String? = when (id) {
        RisiSkillIds.ALARM -> "alarms"
        RisiSkillIds.REMINDERS -> "reminders"
        RisiSkillIds.CALENDAR -> "calendar"
        RisiSkillIds.SCHEDULED_MESSAGES -> "scheduled messages"
        RisiSkillIds.EMAIL -> "email"
        else -> null
    }

    fun skillTitle(id: String?): String = when (id) {
        RisiSkillIds.ALARM -> "Alarm"
        RisiSkillIds.REMINDERS -> "Reminders"
        RisiSkillIds.CALENDAR -> "Calendar"
        RisiSkillIds.SCHEDULED_MESSAGES -> "Scheduled messages"
        RisiSkillIds.EMAIL -> "Email"
        else -> id ?: "This skill"
    }

    /** §25.4 an answer's steps as lines ("✓ Checked your calendar"). */
    fun stepLines(r: RisiMeta): List<String> = r.steps.map { s ->
        val mark = when (s.status) {
            "ok" -> "✓"
            "running" -> "…"
            "skipped" -> "–"
            else -> "✗"
        }
        val what = RisiStepLabels.done(s.tool)
        val why = when (s.status) {
            "timeout" -> " (no answer from your phone)"
            "no_permission" -> " (no permission)"
            "declined" -> " (declined)"
            "denied" -> " (not allowed)"
            "failed" -> " (failed)"
            else -> ""
        }
        "$mark $what$why"
    }

    /**
     * At most 3 chips (§25.4); blank ones dropped. A chip only FILLS the composer, so a chip that is a
     * question ("What is…?") or a confirm/answer phrase ("Confirm…", "Yes…", "Cancel") is hidden: sent
     * as a request it means nothing to Risi (P0: "Confirm to add the event" → "I don't see any event
     * details"). Confirming is the card's [Add].
     */
    fun nextSteps(r: RisiMeta): List<String> = r.nextSteps.map { it.trim() }.filter { it.isNotEmpty() && chipUsable(it) }.take(3)

    private val QUESTION_START = Regex(
        "^(what|what's|whats|which|when|where|who|whom|whose|why|how|is|are|am|was|were|do you|does|did|can you|could you|would you|should|shall|will you|may i|want me)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CONFIRM_START = Regex(
        "^(confirm|yes|yeah|yep|ok|okay|sure|go ahead|proceed|approve|accept|add it|do it|please confirm|tap|no|nope|cancel|don't|do not)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** A chip that reads as a request of its own (not a question, not an answer to a card). */
    fun chipUsable(s: String): Boolean {
        val t = s.trim().trimStart('“', '"', '\'')
        if (t.isEmpty() || t.trimEnd('”', '"', '\'', '.', ' ').endsWith("?")) return false
        return !QUESTION_START.containsMatchIn(t) && !CONFIRM_START.containsMatchIn(t)
    }
}
