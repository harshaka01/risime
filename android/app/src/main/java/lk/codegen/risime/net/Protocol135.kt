package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * v1.35 §34 Risi P0: `confirm_update` (superseded cards), `error.next_actions`, the Source
 * `risi_item`, `answer.clarify` (the name check) and Risi's items (`/api/v1/risi/items`).
 * Phase 2 (this app) advertises the device capability `risi_items`. Additive only.
 */

/** §34 Phase 2: the app renders `confirm_update`, `clarify` and has Settings → Risi skills → Calendar → "Risi's items". */
const val CAPABILITY_RISI_ITEMS = "risi_items"

object RisiKinds135 {
    /** §34.2: a card (by `write_id`) was closed by the server; `state` says why. */
    const val CONFIRM_UPDATE = "confirm_update"

    /** §34.2 `confirm_update.state`; any other state reads "Closed". */
    const val STATE_SUPERSEDED = "superseded"

    /** §34.2 the `error` code of a tap on a superseded card. */
    const val ERROR_SUPERSEDED = "superseded"

    /** §34.4 the `Source` type naming one of Risi's items. */
    const val SOURCE_RISI_ITEM = "risi_item"

    /** §34.1 the `calendar_source` source of Risi's items. */
    const val CALENDAR_SOURCE_RISI_ITEMS = "risi_items"
}

/** §34.5 `answer.clarify`: the name question before a write. */
@Serializable
data class RisiNameClarify(
    val about: String = "",
    @SerialName("write_id") val writeId: String? = null,
    val said: String = "",
    val options: List<RisiNameOption> = emptyList(),
    val keep: Boolean = false,
) {
    companion object {
        const val ABOUT_NAME = "name"
    }
}

@Serializable
data class RisiNameOption(val name: String = "", @SerialName("user_id") val userId: String? = null)

/** §34.4 `RisiItem`. */
@Serializable
data class RisiItem(
    val id: String,
    val kind: String,
    val title: String? = null,
    val start: String? = null,
    val end: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val state: String = "active",
    val calendar: RisiCalendarRef? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    val ref: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String? = null,
    val actions: List<String> = emptyList(),
) {
    private fun refString(key: String): String? = (ref[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** `phone_event_added` / `risi_calendar_event`: the event id (opaque provider id, or the Risi event's uuid). */
    val eventId: String? get() = refString("event_id")
    val writeId: String? get() = refString("write_id")
    /** `phone_event_added` / `scheduled_message`: the phone that owns the row. */
    val deviceId: String? get() = refString("device_id")
    val reminderId: String? get() = refString("reminder_id")
    val scheduleId: String? get() = refString("schedule_id")
    val targetConversationId: String? get() = refString("target_conversation_id")
    val repeat: String? get() = refString("repeat")
    val itemId: String? get() = refString("item_id")

    fun can(action: String): Boolean = action in actions

    companion object {
        const val PHONE_EVENT_ADDED = "phone_event_added"
        const val RISI_CALENDAR_EVENT = "risi_calendar_event"
        const val REMINDER = "reminder"
        const val SCHEDULED_MESSAGE = "scheduled_message"
        const val FOLLOW_UP = "follow_up"
        const val PROMISE = "promise"
        val KINDS = listOf(PHONE_EVENT_ADDED, RISI_CALENDAR_EVENT, REMINDER, SCHEDULED_MESSAGE, FOLLOW_UP, PROMISE)

        const val OPEN = "open"
        const val EDIT = "edit"
        const val DELETE = "delete"
    }
}

/** `GET /api/v1/risi/items` reply. */
@Serializable
data class RisiItemsReply(val items: List<RisiItem> = emptyList())

/** `PATCH /api/v1/risi/items/{id}` (reply). */
@Serializable
data class RisiItemReply(val item: RisiItem)

/** `PATCH /api/v1/risi/items/{id}` body: `reminder` only (`at` and/or `text`, 1–500 chars). */
@Serializable
data class RisiItemPatch(
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val at: String? = null,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val text: String? = null,
) {
    companion object {
        const val MAX_TEXT = 500

        /** A valid PATCH or null: at least one field, text trimmed to 1–500 chars. */
        fun of(at: String?, text: String?): RisiItemPatch? {
            val t = text?.trim()
            if (t != null && (t.isEmpty() || t.length > MAX_TEXT)) return null
            if (at == null && t == null) return null
            return RisiItemPatch(at, t)
        }
    }
}
