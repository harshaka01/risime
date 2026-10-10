package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Contract v1.24 (§24): two tabs (Private | Official) and Risi stage 1. Wire models only; no behaviour yet.
 * Every model tolerates unknown fields (ProtocolJson ignores them) and defaults what older servers omit.
 */

/** The Private side of a chat. */
@Serializable
data class ChatPrivate(@SerialName("conversation_id") val conversationId: String)

/** The Official side of a chat: `state` is "on" | "off"; the conversation id is null until it was ever created. */
@Serializable
data class ChatOfficial(
    val state: String = "off",
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("changed_by") val changedBy: String? = null,
    @SerialName("changed_at") val changedAt: String? = null,
) {
    val on: Boolean get() = state == "on"
}

/** Why the Official side isn't ready; `user_id`/`device_id` are null for chat-level reasons. */
@Serializable
data class ChatMissing(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    val reason: String,
)

/** `GET /chats` item and `GET /chats/{id}` body. */
@Serializable
data class Chat(
    @SerialName("chat_id") val chatId: String,
    val kind: String,
    /** Null only for a Risi chat (§25.2), which has no Private tab. */
    @SerialName("private") val privateSide: ChatPrivate?,
    val official: ChatOfficial = ChatOfficial(),
    @SerialName("official_ready") val officialReady: Boolean = false,
    val missing: List<ChatMissing> = emptyList(),
    @SerialName("can_toggle") val canToggle: Boolean = false,
)

@Serializable
data class ChatReply(val chat: Chat)

@Serializable
data class ChatsReply(val chats: List<Chat>)

/** `PATCH /chats/{id}` body: `official` is "on" | "off". */
@Serializable
data class ChatPatch(val official: String)

/** `chat_event` data: action is "official_created" | "official_on" | "official_off". */
@Serializable
data class ChatEventData(
    @SerialName("chat_id") val chatId: String,
    val action: String,
    val actor: String? = null,
    @SerialName("official_conversation_id") val officialConversationId: String? = null,
    @SerialName("server_ts") val serverTs: String? = null,
)

@Serializable
data class ChatEvent(
    @SerialName("event_id") val eventId: String,
    val kind: String,
    val data: ChatEventData,
)

/** §24 `risi` object on a text envelope; one flat model for every `kind`, fields unused by a kind stay null/empty. */
@Serializable
data class RisiMeta(
    val v: Int = 1,
    val kind: String,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("call_ref") val callRef: String? = null,
    val notify: List<String> = emptyList(),
    // answer
    val answer: String? = null,
    val refs: List<String> = emptyList(),
    val confidence: Double? = null,
    // commitment / commitment_update / reminder / escalation
    @SerialName("commitment_id") val commitmentId: String? = null,
    val state: String? = null,
    val text: String? = null,
    val owner: String? = null,
    val counterpart: List<String> = emptyList(),
    val due: String? = null,
    @SerialName("due_text") val dueText: String? = null,
    @SerialName("source_message_ids") val sourceMessageIds: List<String> = emptyList(),
    val by: String? = null,
    @SerialName("overdue_by") val overdueBy: Long? = null,
    // digest
    val date: String? = null,
    val items: List<RisiDigestItem> = emptyList(),
    // error
    val code: String? = null,
    // offer
    @SerialName("offer_id") val offerId: String? = null,
    val topic: String? = null,
    val question: String? = null,
    val buttons: List<String> = emptyList(),
    // report
    val title: String? = null,
    val period: RisiPeriod? = null,
    val sections: List<RisiSection> = emptyList(),
    // summary
    val summary: String? = null,
    val decisions: List<String> = emptyList(),
    @SerialName("action_items") val actionItems: List<String> = emptyList(),
    @SerialName("open_questions") val openQuestions: List<String> = emptyList(),
    val partial: Boolean = false,
    // v1.25 §25.4: answer v2 (all optional; absent in v1.24)
    val steps: List<RisiStep> = emptyList(),
    val sources: List<RisiSource> = emptyList(),
    @SerialName("next_steps") val nextSteps: List<String> = emptyList(),
    @SerialName("local_search") val localSearch: RisiLocalSearch? = null,
    @SerialName("turn_ref") val turnRef: String? = null,
    // v1.25 confirm
    @SerialName("write_id") val writeId: String? = null,
    val tool: String? = null,
    /** `confirm`: a [RisiWhen] object; `reminder_set`: a timestamp. Read with [confirmWhen] / [reminderWhen]. */
    @SerialName("when") val whenRaw: JsonElement? = null,
    /** `confirm`: the users who may act (a list); v1.27 `discussion_summary`: the one recipient (a string, read as one element). */
    @Serializable(with = StringOrListSerializer::class)
    @SerialName("for") val forUsers: List<String> = emptyList(),
    @SerialName("expires_at") val expiresAt: String? = null,
    // v1.25 reminder_set (and reminder.reminder_id)
    @SerialName("reminder_id") val reminderId: String? = null,
    /** v1.25 `reminder_set` ids; v1.29 calendar cards' `{"user_id", "status"}` objects read as their ids (see RisiCalendarCard). */
    @Serializable(with = LenientIdListSerializer::class)
    val participants: List<String> = emptyList(),
    @SerialName("me_too") val meToo: Boolean = false,
    // v1.25 draft
    val language: String? = null,
    @SerialName("target_conversation_id") val targetConversationId: String? = null,
    // v1.26 §26.5: confirm `skill_id`/`args`; skill_done; skill_needed
    @SerialName("skill_id") val skillId: String? = null,
    /** `confirm`: the client tool's exact args without `write_id` (compared exactly, §26.5). */
    val args: JsonObject? = null,
    @SerialName("entry_id") val entryId: String? = null,
    val action: String? = null,
    val via: String? = null,
    val undo: RisiUndo? = null,
    @SerialName("undo_token") val undoToken: String? = null,
    val reason: String? = null,
    /** v1.31 §31.8 `google_reconnect`: the Google device. */
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("was_on") val wasOn: Boolean? = null,
    // v1.26 §26.7 calendar_offer
    val start: String? = null,
    val end: String? = null,
    @SerialName("all_day") val allDay: Boolean? = null,
    @SerialName("reminder_before_min") val reminderBeforeMin: Int? = null,
    // v1.27 §27.1: what made this message (absent from older servers: "not recorded")
    @SerialName("made_by") val madeBy: RisiMadeBy? = null,
    // v1.27 §27.3/§27.4 discussion_summary / discussion_card (`items` above holds the summary's items)
    @SerialName("summary_id") val summaryId: String? = null,
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @Serializable(with = LenientIdListSerializer::class)
    @SerialName("with") val withUsers: List<String> = emptyList(),
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    val source: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val media: String? = null,
    /** Proposal 2026-10-09-risi-action-loop §1: a `calendar_add` card's calendar hint (`{name, account}` | null). Read with [calendarHint]. */
    @SerialName("calendar") val calendarRaw: JsonElement? = null,
    @SerialName("duration_s") val durationS: Long? = null,
    @SerialName("key_points") val keyPoints: List<String> = emptyList(),
    @SerialName("items_count") val itemsCount: Int? = null,
    // v1.27 §27.5/§27.6 item_update / item_due / item_overdue / item_nudge (`state`, `by`, `text`, `due`, `all_day`, `owner` above)
    @SerialName("item_id") val itemId: String? = null,
    val moment: String? = null,
    val role: String? = null,
    // v1.27 §27.6 personal digest
    val scope: String? = null,
    // v1.27 §27.7 call_listen
    val since: String? = null,
    // proposal 2026-10-09-risi-30day-summaries: a period summary's stored days (`period` above gains `scope`)
    val days: List<RisiSummaryDay> = emptyList(),
    // Server items 8–10 (2026-10-09): a proactive offer's `confirm` (`origin: "offer"`, `item_id` above; no
    // request behind its `request_id`, `turn_ref` null) and the personal digest's per-direction `totals`.
    val origin: String? = null,
    val totals: RisiTotals? = null,
    // v1.29 §29.9 `event_card` "added" | "official" (the other calendar fields: RisiCalendarCard).
    val mode: String? = null,
    // v1.29 §30.4 note_card / notes_saved (a note's id = its §27 summary_id; `item_update`s may carry either).
    @SerialName("note_id") val noteId: String? = null,
    @SerialName("events_count") val eventsCount: Int? = null,
) {
    /** The §27 summary this row belongs to: `summary_id`, else a note's `note_id` (§30.3: the same id). */
    val summaryKey: String? get() = summaryId ?: noteId

    /** `confirm.calendar` (null if absent, null or malformed). */
    fun calendarHint(): RisiCalendarRef? =
        (calendarRaw as? JsonObject)?.let { runCatching { ProtocolJson.decodeFromJsonElement(RisiCalendarRef.serializer(), it) }.getOrNull() }

    /** `confirm.when` (null if absent or not an object). */
    fun confirmWhen(): RisiWhen? =
        (whenRaw as? JsonObject)?.let { runCatching { ProtocolJson.decodeFromJsonElement(RisiWhen.serializer(), it) }.getOrNull() }

    /** `reminder_set.when` (null if absent or not a string). */
    fun reminderWhen(): String? = (whenRaw as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
data class RisiPeriod(
    val from: String,
    val to: String,
    /** Proposal 2026-10-09-risi-30day-summaries (optional): "today" | "7d" | "30d" | "range"; "day" | "week" on a stored summary. */
    val scope: String? = null,
)

/** Proposal 2026-10-09-risi-30day-summaries: a stored day/week summary a period summary rests on. */
@Serializable
data class RisiSummaryDay(
    val date: String,
    val to: String? = null,
    val scope: String = "day",
    @SerialName("summary_id") val summaryId: String? = null,
)

@Serializable
data class RisiSection(val heading: String, val body: String)

/** Text envelope, optionally carrying a Risi object (old envelopes without `risi` parse unchanged). */
@Serializable
data class RisiTextEnvelope(
    val v: Int = 1,
    val type: String,
    val body: String = "",
    val risi: RisiMeta? = null,
)

/** `risi_request` envelope: a member asks Risi to act (`action` e.g. "summarise"). */
@Serializable
data class RisiRequestEnvelope(
    val v: Int = 1,
    val type: String,
    @SerialName("request_id") val requestId: String,
    val action: String,
    val text: String? = null,
    val scope: JsonObject? = null,
)

/** `risi_action` envelope: a member acts on a commitment (e.g. action "edit" with an `edit` object). */
@Serializable
data class RisiActionEnvelope(
    val v: Int = 1,
    val type: String,
    val target: String,
    val action: String,
    val edit: JsonObject? = null,
    /** v1.26 §26.7: `{"reminder": bool}` on a `calendar_accept`, else null. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val options: JsonObject? = null,
)

/** `POST /risi/feedback` body. */
@Serializable
data class RisiFeedback(
    @SerialName("call_ref") val callRef: String,
    val rating: String,
    val reason: String? = null,
)

@Serializable
data class RisiCommitment(
    @SerialName("commitment_id") val commitmentId: String,
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("official_conversation_id") val officialConversationId: String? = null,
    val state: String,
    val text: String,
    val owner: String? = null,
    val counterpart: List<String> = emptyList(),
    val due: String? = null,
    @SerialName("due_text") val dueText: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    // v1.27 §27.9 ledger items (absent for legacy commitments)
    @SerialName("summary_id") val summaryId: String? = null,
    val source: String? = null,
    @SerialName("all_day") val allDay: Boolean? = null,
    /** "owner" | "counterpart" (§27.9); null for a legacy commitment. */
    val role: String? = null,
    // Server item 9 (2026-10-09), all optional (absent from older servers): the My promises split.
    /** "i_promised" | "promised_to_me" | "others" (any other value is read as "others"). */
    val direction: String? = null,
    /** "You" for the caller, else the owner's display name. */
    @SerialName("owner_name") val ownerName: String? = null,
    /** The item's state, or "needs_clarification" for a live item without a usable due. */
    val status: String? = null,
    @SerialName("needs_clarification") val needsClarification: Boolean? = null,
    @SerialName("source_conversation_id") val sourceConversationId: String? = null,
    @SerialName("source_message_id") val sourceMessageId: String? = null,
    @SerialName("source_message_ids") val sourceMessageIds: List<String> = emptyList(),
    /** v1.29 §30.5 the note this item came from (null: none or an older server). */
    @SerialName("note_id") val noteId: String? = null,
)

/** Server item 9: open items per direction (`GET /risi/commitments` and the personal digest; equal by construction). */
@Serializable
data class RisiTotals(
    @SerialName("i_promised") val iPromised: Int = 0,
    @SerialName("promised_to_me") val promisedToMe: Int = 0,
    val others: Int = 0,
)

@Serializable
data class RisiCommitmentsReply(val commitments: List<RisiCommitment>, val totals: RisiTotals? = null)

@Serializable
data class RisiFact(
    @SerialName("fact_id") val factId: String,
    val kind: String,
    val text: String,
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** Proposal 2026-10-09-risi-30day-summaries: a stored chat summary (`kind: "summary"`): "day" | "week". */
    val scope: String? = null,
    val period: RisiPeriod? = null,
)

@Serializable
data class RisiFactsReply(val facts: List<RisiFact>)

/** Plain `{"reason": ...}` error detail (e.g. `official_off`). */
@Serializable
data class ReasonBody(val reason: String)

/** §24 error codes. */
object TabsErrors {
    const val PRIVATE_TAB = "private_tab"
    const val OFFICIAL_OFF = "official_off"
    const val AGENT_UNAVAILABLE = "agent_unavailable"
    const val RISI_REQUIRED = "risi_required"
    const val DM_CHAT = "dm_chat"
    const val INVALID_MEMBER = "invalid_member"

    /** §24.2/§24.8: an Official epoch 0 built for a stale human member set: refetch, claim again, rebuild (not permanent). */
    const val MEMBERS_CHANGED = "members_changed"
}

/** `chat_event` actions. */
object ChatEventActions {
    const val OFFICIAL_CREATED = "official_created"
    const val OFFICIAL_OFF = "official_off"
    const val OFFICIAL_ON = "official_on"
}

/** Capability string a v1.24 client advertises in the MLS device capabilities. */
const val CAPABILITY_TABS = "tabs"

/** `PATCH /me {"tz": "<IANA zone>"}` (§24.11); a separate body so a rename never carries it and vice versa. */
@Serializable
data class PatchTz(val tz: String)

/**
 * §24.11 the private Risi REST calls (feedback, "What Risi knows about me", "My promises").
 * [ApiClient] in the app; a fake in tests.
 */
interface RisiRest {
    suspend fun feedback(callRef: String, rating: String, reason: String?): ApiResult<Unit>

    suspend fun facts(): ApiResult<RisiFactsReply>

    suspend fun deleteFact(factId: String): ApiResult<Unit>

    suspend fun deleteAllFacts(): ApiResult<Unit>

    suspend fun commitments(state: String): ApiResult<RisiCommitmentsReply>
}
