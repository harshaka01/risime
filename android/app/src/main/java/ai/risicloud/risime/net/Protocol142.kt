package ai.risicloud.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * v1.42 §38 Risi voice → action items (decision 082): the Official card `action_items`, the owner's `action_confirm`
 * in their Risi chat, three `risi_action`s (`item_calendar`, `item_remind`, `item_not_mine`), the `/auth/config`
 * switch and My promises' `kind` / `source_ref` / `?source=`. Additive only.
 */

object RisiKinds142 {
    /** §38.3 the Official card: what Risi heard for everyone (or "couldn't hear clearly"). */
    const val ACTION_ITEMS = "action_items"

    /** §38.4 one owner's items in their Risi chat, with the buttons. */
    const val ACTION_CONFIRM = "action_confirm"

    const val STATUS_OK = "ok"
    const val STATUS_UNCLEAR = "unclear"

    const val REASON_LOW_CONFIDENCE = "low_confidence"
    const val REASON_SCRIPT_CHECK = "script_check"
    const val REASON_NO_SPEECH = "no_speech"

    const val SOURCE_CALL = "call"
    const val SOURCE_VOICE_NOTE = "voice_note"

    /** §38.5 `source` of a My promises item that came from a chat (the third `?source=` value). */
    const val SOURCE_CHAT = "chat"

    const val PROMISE = "promise"
    const val TASK = "task"
    const val MEETING = "meeting"
    const val DECISION = "decision"
    const val QUESTION = "question"
    const val NUMBER = "number"

    /** The kinds in the card's order. */
    val ITEM_KINDS = listOf(PROMISE, TASK, MEETING, DECISION, QUESTION, NUMBER)

    /** `promise`/`task`/`meeting` with an owner are ledger items (they carry `state`). */
    val LEDGER_KINDS = setOf(PROMISE, TASK, MEETING)

    val SOURCES = setOf(SOURCE_CALL, SOURCE_VOICE_NOTE)
    val STATUSES = setOf(STATUS_OK, STATUS_UNCLEAR)
    val REASONS = setOf(REASON_LOW_CONFIDENCE, REASON_SCRIPT_CHECK, REASON_NO_SPEECH)

    // §38.4 the per-item buttons of `action_confirm`.
    const val BUTTON_ADD_PROMISE = "add_promise"
    const val BUTTON_ADD_CALENDAR = "add_calendar"
    const val BUTTON_REMIND = "remind"
    const val BUTTON_NOT_MINE = "not_mine"
    const val BUTTON_EDIT = "edit"
    val BUTTONS = listOf(BUTTON_ADD_PROMISE, BUTTON_ADD_CALENDAR, BUTTON_REMIND, BUTTON_NOT_MINE, BUTTON_EDIT)
}

/** v1.43 §38.9 "It's mine": `item_claim` (in the claimer's own Risi chat, `target` = `item_id`, `edit: null`). */
object RisiActions143 {
    const val ITEM_CLAIM = "item_claim"

    /** §38.9 point 5: the Reminders skill is off (Remind me still works, in the Risi chat). */
    const val REMINDERS_OFF = "off"
}

/** §38.4 the new `risi_action`s (sent in the owner's own Risi chat, `target` = `item_id`). */
object RisiActions142 {
    const val ITEM_CALENDAR = "item_calendar"
    const val ITEM_REMIND = "item_remind"
    const val ITEM_NOT_MINE = "item_not_mine"
    val ALL = setOf(ITEM_CALENDAR, ITEM_REMIND, ITEM_NOT_MINE)

    /** `item_calendar.edit` (§28.4 shapes): the time the owner saw and confirmed. */
    fun calendarEdit(start: String, end: String?, allDay: Boolean, title: String? = null): JsonObject = buildJsonObject {
        title?.let { put("title", it) }
        put("start", start)
        put("end", end?.let { JsonPrimitive(it) } ?: JsonNull)
        put("all_day", allDay)
    }

    /** `item_remind.edit`: `{"remind_at": ts}` (future). */
    fun remindEdit(remindAt: String): JsonObject = buildJsonObject { put("remind_at", remindAt) }
}

/** §38.3 where an item (or the whole card) came from: a call transcript turn or a voice note, with the offset. */
@Serializable
data class SourceRef(
    val type: String,
    @SerialName("call_id") val callId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    val turn: Int? = null,
    @SerialName("offset_ms") val offsetMs: Long? = null,
    val at: String? = null,
) {
    val isCall: Boolean get() = type == RisiKinds142.SOURCE_CALL
    val isVoiceNote: Boolean get() = type == RisiKinds142.SOURCE_VOICE_NOTE

    /** A line the phone can look for: a call turn (session + turn) or a voice note (its message id). */
    val jumpable: Boolean get() = (isCall && sessionId != null && turn != null) || (isVoiceNote && messageId != null)
}

/** §38.3 one item of `action_items` / `action_confirm` (`buttons` only on `action_confirm`). */
@Serializable
data class ActionItem(
    @SerialName("item_id") val itemId: String,
    val kind: String,
    val text: String,
    val owner: String? = null,
    val counterpart: List<String> = emptyList(),
    val due: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    @SerialName("due_text") val dueText: String? = null,
    val lang: String? = null,
    val state: String? = null,
    @SerialName("source_ref") val sourceRef: SourceRef? = null,
    val buttons: List<String> = emptyList(),
    /** v1.43 §38.9 an unowned promise/task/meeting any member may claim ("It's mine"). */
    val claim: Boolean = false,
) {
    /** A ledger item: promise/task/meeting with an owner. */
    val ledger: Boolean get() = kind in RisiKinds142.LEDGER_KINDS && owner != null
}

/**
 * §38.3/§38.4 the `risi` object of an `action_items` or `action_confirm` row. [parse] is strict about what the phone
 * relies on (kind, source, status, item kinds and text) and drops an item it can't show honestly.
 */
@Serializable
data class RisiActionCard(
    val v: Int = 1,
    val kind: String,
    @SerialName("summary_id") val summaryId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("chat_id") val chatId: String? = null,
    val source: String,
    @SerialName("source_ref") val sourceRef: SourceRef? = null,
    val status: String = RisiKinds142.STATUS_OK,
    val reason: String? = null,
    val summary: String? = null,
    val lang: String? = null,
    val items: List<ActionItem> = emptyList(),
    @Serializable(with = StringOrListSerializer::class)
    @SerialName("for") val forUsers: List<String> = emptyList(),
    @SerialName("expires_at") val expiresAt: String? = null,
    /** v1.43 §38.9 the owner's Reminders skill when posted ("off" | "ask" | "allowed"; null: unknown). */
    @SerialName("reminders_skill") val remindersSkill: String? = null,
    @SerialName("call_ref") val callRef: String? = null,
    @SerialName("made_by") val madeBy: RisiMadeBy? = null,
    val notify: List<String> = emptyList(),
) {
    val unclear: Boolean get() = status == RisiKinds142.STATUS_UNCLEAR

    companion object {
        const val MAX_TEXT = 200

        /** The card in a Risi row's [systemJson]; null when the row is something else or the card is malformed. */
        fun parse(systemJson: String?): RisiActionCard? {
            val j = systemJson ?: return null
            if (!j.contains(RisiKinds142.ACTION_ITEMS) && !j.contains(RisiKinds142.ACTION_CONFIRM)) return null
            val c = runCatching { ProtocolJson.decodeFromString(serializer(), j) }.getOrNull() ?: return null
            return validated(c)
        }

        /** Strict checks; an item of an unknown kind or with no text is left out (never guessed). */
        fun validated(c: RisiActionCard): RisiActionCard? {
            if (c.v != 1) return null
            if (c.kind != RisiKinds142.ACTION_ITEMS && c.kind != RisiKinds142.ACTION_CONFIRM) return null
            if (c.source !in RisiKinds142.SOURCES || c.status !in RisiKinds142.STATUSES) return null
            if (c.reason != null && c.reason !in RisiKinds142.REASONS) return null
            // "Couldn't hear clearly": never any items.
            if (c.unclear) return c.copy(items = emptyList())
            val items = c.items.filter { i ->
                i.kind in RisiKinds142.ITEM_KINDS && i.text.isNotBlank() && i.text.length <= MAX_TEXT && i.itemId.isNotBlank()
            }.map { i ->
                i.copy(
                    buttons = i.buttons.filter { it in RisiKinds142.BUTTONS },
                    // v1.43 only an unowned promise/task/meeting is claimable.
                    claim = i.claim && i.owner == null && i.kind in RisiKinds142.LEDGER_KINDS,
                )
            }
            return c.copy(items = items)
        }
    }
}
