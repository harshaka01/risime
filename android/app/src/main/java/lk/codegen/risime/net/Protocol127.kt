package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Contract v1.27 (§27): model transparency (`made_by`), the Commitment Ledger follow-ups and call
 * transcription. Wire models; every model tolerates unknown fields and defaults what a v1.26 server omits.
 */

/** Capability a v1.27 client advertises (only with `risi_tools`) while `/auth/config` says `risi_ledger: on`. */
const val CAPABILITY_RISI_LEDGER = "risi_ledger"

/** §27.1 another model the content rests on (e.g. the speech model of a call summary). */
@Serializable
data class RisiMadeByAlso(val model: String, val provider: String = PROVIDER_RISIME, val task: String = "") {
    companion object {
        const val PROVIDER_RISIME = "risime"
        const val TASK_TRANSCRIBE = "transcribe"
    }
}

/** §27.1 `risi.made_by`: what made a Risi message (`model` null = RisiMe's own rules, no model). */
@Serializable
data class RisiMadeBy(
    val model: String? = null,
    val provider: String = RisiMadeByAlso.PROVIDER_RISIME,
    val at: String? = null,
    val also: List<RisiMadeByAlso> = emptyList(),
)

/**
 * One item: the §27.3 `Item` of a `discussion_summary` (`item_id`) and the §24.11 digest item
 * (`commitment_id`); a ledger item's `commitment_id` is its `item_id` (§27.9). Read the id with [id].
 */
@Serializable
data class RisiDigestItem(
    @SerialName("commitment_id") val commitmentId: String? = null,
    @SerialName("item_id") val itemId: String? = null,
    val text: String,
    val owner: String? = null,
    val counterpart: List<String> = emptyList(),
    val due: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    @SerialName("due_text") val dueText: String? = null,
    val state: String? = null,
    /** Server item 9 (personal digest): "i_promised" | "promised_to_me" | "others"; absent from older servers. */
    val direction: String? = null,
) {
    val id: String get() = itemId ?: commitmentId ?: ""
}

/** §27.3 item states. */
object RisiItemStates {
    const val PROPOSED = "proposed"
    const val CONFIRMED = "confirmed"
    const val EDITED = "edited"
    const val DECLINED = "declined"
    const val DONE = "done"
    const val CANCELLED = "cancelled"
    const val EXPIRED = "expired"

    /** Confirmed or edited: tracked, one of the owner's promises (§27.5). */
    fun tracked(state: String?): Boolean = state == CONFIRMED || state == EDITED
}

/** §27 `risi.kind`s. */
object RisiKinds127 {
    const val DISCUSSION_SUMMARY = "discussion_summary"
    const val DISCUSSION_CARD = "discussion_card"
    const val ITEM_UPDATE = "item_update"
    const val ITEM_DUE = "item_due"
    const val ITEM_OVERDUE = "item_overdue"
    const val ITEM_NUDGE = "item_nudge"
    const val CALL_LISTEN = "call_listen"
    const val DIGEST = "digest"

    /** Server item 10 (2026-10-09): the one "when is it due?" question of a vague item ([New date] = `item_edit`). */
    const val ITEM_CLARIFY = "item_clarify"

    val ALL = setOf(DISCUSSION_SUMMARY, DISCUSSION_CARD, ITEM_UPDATE, ITEM_DUE, ITEM_OVERDUE, ITEM_NUDGE, CALL_LISTEN)
}

/** §27.5 the item actions (`risi_action`, sent in the actor's own Risi chat, `target` = `item_id`). */
object RisiActions127 {
    const val ITEM_CONFIRM = "item_confirm"
    const val ITEM_DECLINE = "item_decline"
    const val ITEM_EDIT = "item_edit"

    /** §24.11 `done`: the owner or a counterpart. */
    const val DONE = "done"

    /** `item_edit.edit`: `{"text", "due", "all_day"}` (`due` null = no date). */
    fun editObject(text: String, due: String?, allDay: Boolean): JsonObject = buildJsonObject {
        put("text", text)
        put("due", due?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        put("all_day", allDay)
    }
}

/** §27.6 `item_due.moment`, `role` and the reminder buttons. */
object RisiItemDue {
    const val BEFORE = "before"
    const val AT = "at"
    const val TODAY = "today"
    const val ROLE_OWNER = "owner"
    const val ROLE_COUNTERPART = "counterpart"
    const val BUTTON_DONE = "done"
    const val BUTTON_NEW_DATE = "new_date"
}

/** §27.7 the `risi` object of the rooms replies (`start`, `status`, `risi_stop`). */
@Serializable
data class CallRisiState(val state: String, val reason: String? = null) {
    companion object {
        const val REQUESTED = "requested"
        const val LISTENING = "listening"
        const val STOPPED = "stopped"
        const val UNAVAILABLE = "unavailable"
        const val OFF = "off"
    }
}

/** §27.7 `POST /calls/rooms` `risi_stop` reply. */
@Serializable
data class CallsRoomRisiStopReply(val risi: CallRisiState)

/** §27.8 the ephemeral signal `call_risi`. */
@Serializable
data class CallRisiSignal(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("call_id") val callId: String,
    val state: String,
    val reason: String? = null,
    val by: String? = null,
    @SerialName("server_ts") val serverTs: String? = null,
)

/** §27.7 `call_offer` (`sfu`) / `group_call` `started` `risi` value. */
const val CALL_RISI_LISTEN = "listen"

/** A list of strings that also accepts a single string (v1.27 `discussion_summary.for` is one uuid; `confirm.for` a list). */
object StringOrListSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when {
        element is JsonPrimitive && element.isString -> JsonArray(listOf(element))
        element is JsonNull -> JsonArray(emptyList())
        else -> element
    }
}
