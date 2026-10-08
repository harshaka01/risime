package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
    @SerialName("private") val privateSide: ChatPrivate,
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
)

@Serializable
data class RisiDigestItem(
    @SerialName("commitment_id") val commitmentId: String,
    val text: String,
    val owner: String? = null,
    val due: String? = null,
    val state: String? = null,
)

@Serializable
data class RisiPeriod(val from: String, val to: String)

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
)

@Serializable
data class RisiCommitmentsReply(val commitments: List<RisiCommitment>)

@Serializable
data class RisiFact(
    @SerialName("fact_id") val factId: String,
    val kind: String,
    val text: String,
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class RisiFactsReply(val facts: List<RisiFact>)

/** Plain `{"reason": ...}` error detail (e.g. `official_off`). */
@Serializable
data class ReasonBody(val reason: String)

/** §24 error codes. */
object TabsErrors {
    const val PRIVATE_TAB = "private_tab"
}

/** Capability string a v1.24 client advertises in the MLS device capabilities. */
const val CAPABILITY_TABS = "tabs"
