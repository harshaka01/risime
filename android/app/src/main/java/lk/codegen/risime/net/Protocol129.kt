package lk.codegen.risime.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * v1.29 §29 Risi Calendar (proposal 2026-10-09-risi-calendar-notes, decision 073): RisiMe's own calendar on
 * the server (sealed at rest, not end-to-end encrypted). Additive only: an app without `risi_events` sees
 * v1.28 exactly. Unknown fields are ignored; unknown kinds/statuses are tolerated.
 */

/** Capability a v1.29 client advertises (with `risi_tools`, `risi_skills`, `risi_ledger`) while `/auth/config` says `risi_events: on`. */
const val CAPABILITY_RISI_EVENTS = "risi_events"

/** §29.1/§29.6 the stored, content-free inbox event after any change to the user's Risi Calendar. */
const val KIND_RISI_CALENDAR_CHANGED = "risi_calendar_changed"

/** §29.8–§29.10 the Risi-chat / Official kinds of the calendar. */
object RisiKinds129 {
    const val EVENT_CARD = "event_card"
    const val CALENDAR_INVITE = "calendar_invite"
    const val EVENT_UPDATE = "event_update"
    const val CALENDAR_SUGGESTION = "calendar_suggestion"
    const val CALENDAR_REMINDER = "calendar_reminder"

    /** §29.8 the server-side confirm tool (no client tool call, no device permission). */
    const val TOOL_RISI_CALENDAR_ADD = "risi_calendar_add"

    val ALL = setOf(EVENT_CARD, CALENDAR_INVITE, EVENT_UPDATE, CALENDAR_SUGGESTION, CALENDAR_REMINDER)
}

/** §29.11 the new `risi_action` values. */
object RisiActions129 {
    const val EVENT_ACCEPT = "event_accept"
    const val EVENT_DECLINE = "event_decline"
    const val EVENT_SUGGEST = "event_suggest"
    const val SUGGESTION_USE = "suggestion_use"
    const val SUGGESTION_KEEP = "suggestion_keep"
    val ALL = setOf(EVENT_ACCEPT, EVENT_DECLINE, EVENT_SUGGEST, SUGGESTION_USE, SUGGESTION_KEEP)
}

/** Participant statuses (§29.2). */
object RisiEventStatus {
    const val PROPOSED = "proposed"
    const val ACCEPTED = "accepted"
    const val DECLINED = "declined"
}

@Serializable
data class RisiEventParticipant(
    @SerialName("user_id") val userId: String,
    val status: String = RisiEventStatus.PROPOSED,
    @SerialName("responded_at") val respondedAt: String? = null,
)

@Serializable
data class RisiEventSource(
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("message_ids") val messageIds: List<String> = emptyList(),
    @SerialName("item_id") val itemId: String? = null,
    @SerialName("note_id") val noteId: String? = null,
)

/** §29.2 one event as the caller sees it. */
@Serializable
data class RisiEvent(
    @SerialName("event_id") val eventId: String,
    val version: Int = 1,
    val owner: String? = null,
    val title: String = "",
    val notes: String? = null,
    val start: String,
    val end: String,
    @SerialName("all_day") val allDay: Boolean = false,
    val tz: String? = null,
    val participants: List<RisiEventParticipant> = emptyList(),
    @SerialName("my_status") val myStatus: String = RisiEventStatus.PROPOSED,
    @SerialName("my_reminder_min") val myReminderMin: Int? = null,
    val source: RisiEventSource? = null,
    @SerialName("created_by") val createdBy: String? = null,
    val state: String = STATE_ACTIVE,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val cancelled: Boolean get() = state == STATE_CANCELLED

    fun isOwner(me: String?): Boolean = me != null && owner.equals(me, true)

    companion object {
        const val STATE_ACTIVE = "active"
        const val STATE_CANCELLED = "cancelled"
    }
}

@Serializable
data class RisiCalendarEventsReply(val events: List<RisiEvent> = emptyList(), val cursor: String? = null)

/** `{"event": Event}` or `{"event_id", "removed": true}`. */
@Serializable
data class RisiCalendarChange(
    val event: RisiEvent? = null,
    @SerialName("event_id") val eventId: String? = null,
    val removed: Boolean = false,
) {
    /** The event this change is about (lowercase). */
    val id: String? get() = (event?.eventId ?: eventId)?.lowercase()
}

@Serializable
data class RisiCalendarChangesReply(
    val changes: List<RisiCalendarChange> = emptyList(),
    val cursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class RisiEventReply(val event: RisiEvent)

@Serializable
data class RisiCalendarSettings(
    @SerialName("default_reminder_min") val defaultReminderMin: Int? = DEFAULT_REMINDER_MIN,
    @SerialName("default_duration_min") val defaultDurationMin: Int = 60,
    @SerialName("digest_events") val digestEvents: Boolean = true,
) {
    companion object {
        const val DEFAULT_REMINDER_MIN = 30
    }
}

@Serializable
data class RisiCalendarSettingsReply(val settings: RisiCalendarSettings = RisiCalendarSettings())

/** §29.6 `risi_calendar_changed` data (content-free). */
@Serializable
data class RisiCalendarChangedData(val cursor: String? = null, @SerialName("server_ts") val serverTs: String? = null)

fun Event.risiCalendarChanged(): RisiCalendarChangedData? =
    if (kind == KIND_RISI_CALENDAR_CHANGED) runCatching { ProtocolJson.decodeFromJsonElement(RisiCalendarChangedData.serializer(), data) }.getOrNull() else null

/** A participant on a card: `{"user_id", "status"}` (a bare id string is read as `proposed`). */
@Serializable(with = CardParticipantSerializer::class)
data class CardParticipant(val userId: String, val status: String = RisiEventStatus.PROPOSED)

object CardParticipantSerializer : KSerializer<CardParticipant> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): CardParticipant {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        if (el is JsonPrimitive) return CardParticipant(el.content)
        val o = el as JsonObject
        return CardParticipant(
            (o["user_id"] as? JsonPrimitive)?.contentOrNull ?: "",
            (o["status"] as? JsonPrimitive)?.contentOrNull ?: RisiEventStatus.PROPOSED,
        )
    }

    override fun serialize(encoder: Encoder, value: CardParticipant) =
        JsonObject.serializer().serialize(encoder, buildJsonObject { put("user_id", value.userId); put("status", value.status) })
}

/**
 * The calendar fields of a Risi card (§29.8–§29.10), read from the same `risi` object as [RisiMeta]
 * (which keeps only the participants' ids). Every field optional: an older or newer server's card parses.
 */
@Serializable
data class RisiCalendarCard(
    val kind: String,
    val mode: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    val version: Int? = null,
    val title: String? = null,
    val start: String? = null,
    val end: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val tz: String? = null,
    val owner: String? = null,
    val participants: List<CardParticipant> = emptyList(),
    @SerialName("source_conversation_id") val sourceConversationId: String? = null,
    @SerialName("item_id") val itemId: String? = null,
    @SerialName("note_id") val noteId: String? = null,
    val buttons: List<String> = emptyList(),
    val from: String? = null,
    val reason: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val by: String? = null,
    val change: String? = null,
    val state: String? = null,
    @SerialName("suggestion_id") val suggestionId: String? = null,
    @SerialName("reminder_min") val reminderMin: Int? = null,
    val notify: List<String> = emptyList(),
) {
    companion object {
        fun parse(json: String?): RisiCalendarCard? =
            json?.let { runCatching { ProtocolJson.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** Ids from a list of strings or of `{"user_id", …}` objects (v1.29 cards carry objects; older kinds strings). */
object LenientIdListSerializer : kotlinx.serialization.json.JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        val arr = element as? JsonArray ?: return JsonArray(emptyList())
        return JsonArray(arr.mapNotNull { e ->
            when (e) {
                is JsonPrimitive -> e.takeIf { it.isString }
                is JsonObject -> (e["user_id"] as? JsonPrimitive)?.takeIf { it.isString }
                else -> null
            }
        })
    }
}

/** §29.3 request bodies (built as JSON: an absent field and `null` mean different things in a PATCH). */
object RisiCalendarBodies {
    fun create(
        clientEventId: String, title: String, start: String, end: String, allDay: Boolean, tz: String,
        with: List<String> = emptyList(), reminderMin: Int? = null, sendReminder: Boolean = false, notes: String? = null,
    ): JsonObject = buildJsonObject {
        put("client_event_id", clientEventId)
        put("title", title)
        if (notes != null) put("notes", notes)
        put("start", start)
        put("end", end)
        put("all_day", allDay)
        put("tz", tz)
        put("with", JsonArray(with.map { JsonPrimitive(it) }))
        if (sendReminder) put("reminder_min", reminderMin?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /** Only the given fields; [reminderMin] is sent when [sendReminder] (null = no reminder). */
    fun patch(
        version: Int, title: String? = null, start: String? = null, end: String? = null, allDay: Boolean? = null,
        tz: String? = null, notes: String? = null, add: List<String> = emptyList(), remove: List<String> = emptyList(),
        reminderMin: Int? = null, sendReminder: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("version", version)
        title?.let { put("title", it) }
        notes?.let { put("notes", it) }
        start?.let { put("start", it) }
        end?.let { put("end", it) }
        allDay?.let { put("all_day", it) }
        tz?.let { put("tz", it) }
        if (add.isNotEmpty()) put("add", JsonArray(add.map { JsonPrimitive(it) }))
        if (remove.isNotEmpty()) put("remove", JsonArray(remove.map { JsonPrimitive(it) }))
        if (sendReminder) put("reminder_min", reminderMin?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /** `accept` | `decline` | `suggest` (with [suggest] = start, end, all_day). */
    fun respond(response: String, version: Int, suggest: Triple<String, String, Boolean>? = null, reminderMin: Int? = null, sendReminder: Boolean = false): JsonObject =
        buildJsonObject {
            put("response", response)
            put("version", version)
            if (suggest == null) put("suggest", JsonNull) else put("suggest", buildJsonObject {
                put("start", suggest.first); put("end", suggest.second); put("all_day", suggest.third)
            })
            if (sendReminder) put("reminder_min", reminderMin?.let { JsonPrimitive(it) } ?: JsonNull)
        }

    fun resolve(action: String): JsonObject = buildJsonObject { put("action", action) }

    fun settings(s: RisiCalendarSettings): JsonObject = buildJsonObject {
        put("default_reminder_min", s.defaultReminderMin?.let { JsonPrimitive(it) } ?: JsonNull)
        put("default_duration_min", s.defaultDurationMin)
        put("digest_events", s.digestEvents)
    }
}
