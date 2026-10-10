package lk.codegen.risime.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Contract v1.25 (§25): Risi with tools. Wire models; every model tolerates unknown fields and
 * defaults what a v1.24 server omits.
 */

/** Capability string a v1.25 client advertises (with `groups` and `tabs`) once `/auth/config` says `risi_tools: on`. */
const val CAPABILITY_RISI_TOOLS = "risi_tools"

/** `Chat.kind` and `Group.chat_kind` of a user's Risi chat (§25.2). */
const val CHAT_KIND_RISI = "risi"

/** A JSON string that may be `null` on the wire; null reads as "" (writes stay strings). */
object NullAsEmptyString : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("NullAsEmptyString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        if (decoder is JsonDecoder) {
            val e = decoder.decodeJsonElement()
            return if (e is JsonNull) "" else (e as JsonPrimitive).content
        }
        return decoder.decodeString()
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** One tool step of a turn (`answer.steps`, `risi_progress.step`); `n` only on progress. */
@Serializable
data class RisiStep(val tool: String, val status: String, val n: Int? = null)

/** `StepStatus` (§25.4). */
object RisiStepStatus {
    const val RUNNING = "running"
    const val OK = "ok"
    const val FAILED = "failed"
    const val TIMEOUT = "timeout"
    const val NO_PERMISSION = "no_permission"
    const val DECLINED = "declined"
    const val DENIED = "denied"
    const val SKIPPED = "skipped"
}

/** `answer.sources[]`: one flat model; `type` is "message" | "calendar" | "note" | "link" (others are shown as nothing). */
@Serializable
data class RisiSource(
    val type: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    val start: String? = null,
    val end: String? = null,
    val busy: Boolean? = null,
    @SerialName("all_day") val allDay: Boolean? = null,
    @SerialName("fact_id") val factId: String? = null,
    val url: String? = null,
    val title: String? = null,
    // `calendar_source` (§29.7, v1.31 §31.5): the source read, whether it was, why not; `count`/`refs` for `google_api`
    val source: String? = null,
    val names: List<String> = emptyList(),
    @SerialName("read_ok") val readOk: Boolean? = null,
    val reason: String? = null,
    val count: Int? = null,
    val refs: List<String> = emptyList(),
) {
    /** Whether the app can show it: a known type with its fields (a link only over https). */
    val showable: Boolean get() = when (type) {
        "message" -> conversationId != null && messageId != null
        "calendar" -> start != null && end != null
        "note" -> factId != null
        "link" -> url != null && url.startsWith("https://", ignoreCase = true)
        else -> false
    }
}

/** `answer.local_search`: a search the app runs over its own Private chats; nothing about it leaves the phone. */
@Serializable
data class RisiLocalSearch(val text: String, val since: String? = null)

/** `confirm.when`. */
@Serializable
data class RisiWhen(val start: String, val end: String? = null, @SerialName("all_day") val allDay: Boolean = false)

/** The v1.25 `risi_action` values (target = `write_id` or `reminder_id`). */
object RisiActions {
    const val CONFIRM_WRITE = "confirm_write"
    const val CANCEL_WRITE = "cancel_write"
    const val ME_TOO = "me_too"
    const val NOT_ME = "not_me"
}

/** v1.25 Risi `error` codes (in a `risi` `error` object). */
object RisiErrorCodes {
    const val QUEUE_OVERFLOW = "queue_overflow"
    const val TOOL_TIMEOUT = "tool_timeout"
}

/** §25.8 REST error codes. */
object RisiToolsErrors {
    const val RISI_CHAT = "risi_chat"
    const val TOOL_CALL_EXPIRED = "tool_call_expired"
    const val WRITE_NOT_CONFIRMED = "write_not_confirmed"
}

/** The `risi_tool_call` event data (§25.3). `args` is the tool's (decode with [calendarCheckArgs] / [calendarAddArgs]). */
@Serializable
data class RisiToolCall(
    @SerialName("tool_call_id") val toolCallId: String,
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    /** Null for a §26.4 undo call. */
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    val tool: String,
    val args: JsonObject = JsonObject(emptyMap()),
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("to_devices") val toDevices: List<String> = emptyList(),
    @SerialName("server_ts") val serverTs: String? = null,
    /** §26.4 (v1.26): set on an undo call (no `write_id`, no confirm card). */
    @SerialName("undo_entry_id") val undoEntryId: String? = null,
) {
    fun setAlarmArgs(): SetAlarmArgs? = argsAs(SetAlarmArgs.serializer())

    fun scheduleMessageArgs(): ScheduleMessageArgs? = argsAs(ScheduleMessageArgs.serializer())

    fun cancelScheduledArgs(): CancelScheduledArgs? = argsAs(CancelScheduledArgs.serializer())

    fun calendarRemoveArgs(): CalendarRemoveArgs? = argsAs(CalendarRemoveArgs.serializer())

    /** The call's `write_id` (null for reads and undo calls). */
    val writeId: String? get() = (args["write_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun <T> argsAs(ser: KSerializer<T>): T? =
        runCatching { ProtocolJson.decodeFromJsonElement(ser, args) }.getOrNull()

    fun calendarCheckArgs(): CalendarCheckArgs? =
        runCatching { ProtocolJson.decodeFromJsonElement(CalendarCheckArgs.serializer(), args) }.getOrNull()

    fun calendarAddArgs(): CalendarAddArgs? =
        runCatching { ProtocolJson.decodeFromJsonElement(CalendarAddArgs.serializer(), args) }.getOrNull()

    companion object {
        const val TOOL_CALENDAR_CHECK = "calendar_check"
        const val TOOL_CALENDAR_ADD = "calendar_add"

        // v1.26 §26.6
        const val TOOL_SET_ALARM = "set_alarm"
        const val TOOL_SCHEDULE_MESSAGE = "schedule_message"
        const val TOOL_CANCEL_SCHEDULED = "cancel_scheduled"
        const val TOOL_CALENDAR_REMOVE = "calendar_remove"
    }
}

@Serializable
data class CalendarCheckArgs(
    val from: String,
    val to: String,
    /** §31.4 (v1.31): the sources to read (`phone_provider`, `google_api`); absent = everything the phone reads (v1.29). */
    val sources: List<String>? = null,
)

@Serializable
data class CalendarAddArgs(
    @SerialName("write_id") val writeId: String,
    val title: String,
    val start: String,
    val end: String,
    @SerialName("all_day") val allDay: Boolean = false,
)

/** One free/busy block: never a title, place, attendee or id (§25.3). */
@Serializable
data class CalendarBlock(val start: String, val end: String, val busy: Boolean, @SerialName("all_day") val allDay: Boolean)

@Serializable
data class CalendarCheckResult(
    val blocks: List<CalendarBlock>,
    /**
     * P0 2026-10-09 (honesty, docs/status/android.md "Contract asks"): what was actually read, per source.
     * Null only on an old phone (the server then treats the read as unknown: never "clear").
     */
    val sources: List<CalendarSourceReport>? = null,
    /** The sources read successfully (`read_ok`): `["phone_provider"]` or empty. */
    @SerialName("connected_sources") val connectedSources: List<String>? = null,
)

/** One calendar a check read: its name, raw account type (`com.google`, `LOCAL`…) and instances in the window. */
@Serializable
data class CalendarSourceCalendar(val name: String, @SerialName("account_type") val accountType: String, val events: Int)

/**
 * One source of a `calendar_check` (v1.29 §29.2 as aligned with root): `phone_provider` (Android's
 * CalendarContract) or `google_api` (not connected yet). `read_ok` false with a `reason` (`not_connected`,
 * `no_permission`, `reauth_needed`, `no_play_services`, `network`, `timeout`, `api_error`, `no_calendars`)
 * when nothing trustworthy was read. Names only: an email-named calendar is sent as "Primary calendar".
 */
@Serializable
data class CalendarSourceReport(
    val source: String,
    val calendars: List<CalendarSourceCalendar>,
    @SerialName("read_ok") val readOk: Boolean,
    val reason: String? = null,
)

@Serializable
data class CalendarAddResult(
    @SerialName("event_id") val eventId: String,
    /** Proposal 2026-10-09-risi-action-loop §3: the calendar it went to (omitted when unknown). */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val calendar: RisiCalendarRef? = null,
)

/** `POST /risi/tool_calls/{id}/result` body; `result` is the tool's result for `ok`, `{"code"}` for `error`, else null. */
@Serializable
data class RisiToolResult(val status: String, val result: JsonObject? = null) {
    companion object {
        const val OK = "ok"
        const val NO_PERMISSION = "no_permission"
        const val DECLINED = "declined"
        const val ERROR = "error"

        /** Body cap (§25.3). */
        const val MAX_BYTES = 16 * 1024

        fun declined() = RisiToolResult(DECLINED, null)

        fun error(code: String) = RisiToolResult(ERROR, JsonObject(mapOf("code" to JsonPrimitive(code))))
    }
}

/** `result.code` of an `error` result. */
object RisiToolErrorCodes {
    const val UNKNOWN_TOOL = "unknown_tool"
    const val BAD_ARGS = "bad_args"
    const val CALENDAR_UNAVAILABLE = "calendar_unavailable"
}

/** The `risi_progress` signal data (§25.4). */
@Serializable
data class RisiProgress(
    @SerialName("request_id") val requestId: String,
    @SerialName("conversation_id") val conversationId: String,
    val state: String,
    val position: Int? = null,
    val seq: Int = 0,
    val step: RisiStep? = null,
    @SerialName("server_ts") val serverTs: String? = null,
) {
    companion object {
        const val QUEUED = "queued"
        const val WORKING = "working"
        const val STEP = "step"
        const val WAITING_CONFIRM = "waiting_confirm"
        const val DONE = "done"
    }
}

/** `POST /api/v1/risi/chat` → 201/200 `{"chat", "group"}` (§25.2). */
@Serializable
data class RisiChatReply(val chat: Chat, val group: Group)
