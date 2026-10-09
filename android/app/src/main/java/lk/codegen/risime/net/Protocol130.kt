package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * v1.29 §30 Risi Notes (proposal 2026-10-09-risi-calendar-notes, decision 073): the record of an Official
 * discussion (title, key points, agreed items = the §27 ledger items, proposed Risi Calendar events) in the
 * person's own Risi chat (`note_card`), one short `notes_saved` card in Official, and the Notes REST.
 * Additive only: an app without `risi_notes` sees the §27.3/§27.4 summaries exactly. Every field optional
 * where the proposal allows it; unknown fields and kinds are ignored.
 */

/** Capability a v1.29 client advertises (only with `risi_events`, i.e. with tools, skills, ledger) while `/auth/config` says `risi_notes: on`. */
const val CAPABILITY_RISI_NOTES = "risi_notes"

/** §30.4 the two Risi kinds of Notes. */
object RisiKinds130 {
    /** In the recipient's own Risi chat (replaces `discussion_summary` for a notes user). */
    const val NOTE_CARD = "note_card"

    /** In the Official conversation (replaces `discussion_card`). */
    const val NOTES_SAVED = "notes_saved"

    val ALL = setOf(NOTE_CARD, NOTES_SAVED)
}

/** §30.5 the new `risi_action`: un-ticking a done item (owner or counterpart, within 7 days of done). */
object RisiActions130 {
    const val ITEM_REOPEN = "item_reopen"
    val ALL = setOf(ITEM_REOPEN)
}

/** §30.3 `events[]`: a meeting the discussion produced (a proposed Risi Calendar event). */
@Serializable
data class RisiNoteEvent(
    @SerialName("event_id") val eventId: String,
    val title: String = "",
    val start: String? = null,
    val end: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    /** Null from a view the caller isn't in (shown without [Accept]). */
    @SerialName("my_status") val myStatus: String? = null,
)

/**
 * §30.3 a Note: the `risi` object of a `note_card` (with `kind`, `for`, `expires_at`, `notify`) and
 * `GET /risi/notes/{id}`'s `note` alike. `items` are §27.3 Items with their live states.
 */
@Serializable
data class RisiNote(
    @SerialName("note_id") val noteId: String,
    val kind: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("chat_id") val chatId: String? = null,
    val source: String = SOURCE_CHAT,
    @SerialName("call_id") val callId: String? = null,
    val media: String? = null,
    @SerialName("duration_s") val durationS: Long? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    /** All participants (ids; `{"user_id"}` objects are read as their ids). */
    @Serializable(with = LenientIdListSerializer::class)
    @SerialName("with") val withUsers: List<String> = emptyList(),
    val topic: String = "",
    val language: String? = null,
    @SerialName("key_points") val keyPoints: List<String> = emptyList(),
    val items: List<RisiDigestItem> = emptyList(),
    val events: List<RisiNoteEvent> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("call_ref") val callRef: String? = null,
    @SerialName("made_by") val madeBy: RisiMadeBy? = null,
    // note_card only
    @Serializable(with = StringOrListSerializer::class)
    @SerialName("for") val forUsers: List<String> = emptyList(),
    @SerialName("expires_at") val expiresAt: String? = null,
) {
    companion object {
        const val SOURCE_CHAT = "chat"
        const val SOURCE_CALL = "call"
        const val SOURCE_REQUEST = "request"

        /** A stored row's honoured `risi` object as a note (null: not a note or malformed). */
        fun parse(json: String?): RisiNote? =
            json?.let { runCatching { ProtocolJson.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** `GET /risi/notes/{id}` → `{"note": Note}`. */
@Serializable
data class RisiNoteReply(val note: RisiNote)

/** §30.6 one row of the Notes list. */
@Serializable
data class RisiNoteSummary(
    @SerialName("note_id") val noteId: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    @Serializable(with = LenientIdListSerializer::class)
    @SerialName("with") val withUsers: List<String> = emptyList(),
    val topic: String = "",
    @SerialName("ended_at") val endedAt: String? = null,
    val source: String = RisiNote.SOURCE_CHAT,
    @SerialName("items_count") val itemsCount: Int = 0,
    @SerialName("open_items_count") val openItemsCount: Int = 0,
    @SerialName("events_count") val eventsCount: Int = 0,
)

/** `GET /risi/notes?q=&before=&limit=` → newest first. */
@Serializable
data class RisiNotesReply(
    val notes: List<RisiNoteSummary> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** §30.6 the Notes REST ([ApiClient] in the app; a fake in tests). */
interface RisiNotesRest {
    suspend fun notes(q: String?, before: String?, limit: Int): ApiResult<RisiNotesReply>

    suspend fun note(id: String): ApiResult<RisiNoteReply>

    suspend fun deleteNote(id: String): ApiResult<Unit>

    suspend fun deleteAllNotes(): ApiResult<Unit>
}

/** The app's [RisiNotesRest]: [ApiClient] with this device's id (a `risi_notes` device). */
class RisiNotesApi(private val api: ApiClient) : RisiNotesRest {
    override suspend fun notes(q: String?, before: String?, limit: Int) = api.risiNotes(q, before, limit)

    override suspend fun note(id: String) = api.risiNote(id)

    override suspend fun deleteNote(id: String) = api.deleteRisiNote(id)

    override suspend fun deleteAllNotes() = api.deleteAllRisiNotes()
}
