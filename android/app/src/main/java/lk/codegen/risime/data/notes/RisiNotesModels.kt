package lk.codegen.risime.data.notes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.LedgerItemView
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.RisiNote
import lk.codegen.risime.net.RisiNoteSummary
import lk.codegen.risime.net.RisiNotesRest
import java.time.Instant

/*
 * v1.29 §30.6 the Notes list, the note screen and sharing as plain models (a fake [RisiNotesRest] and fake
 * rows in tests; the Android view models wrap them). Nothing here is persisted: the server keeps notes, the
 * phone keeps the note cards as messages.
 */

/** Names on this phone for a note's title (the viewer's own name, everyone else's from contacts and members). */
class NoteNames(val me: String, val myName: String, val nameOf: (String) -> String) {
    fun title(withUsers: List<String>, topic: String, endedAt: String?): String = RisiNotes.title(me, myName, withUsers, nameOf, topic, endedAt)

    fun title(n: RisiNote): String = title(n.withUsers, n.topic, n.endedAt)

    fun title(s: RisiNoteSummary): String = title(s.withUsers, s.topic, s.endedAt)
}

const val NOTES_OFFLINE = "Couldn't reach the server. Showing the notes on this phone."
const val NOTES_EMPTY = "No notes yet. Risi saves notes of your Official discussions here."
const val NOTES_NO_MATCH = "No notes match."
const val NOTE_GONE = "This note was deleted or isn't available."
const val NOTE_NOT_HERE = "This note isn't on this phone."

private fun failure(r: ApiResult<*>?): String = when {
    r is ApiResult.Error && r.httpStatus >= 500 -> "The server had a problem — try again."
    r is ApiResult.Error -> "Something went wrong (${r.httpStatus}). Try again."
    else -> "Couldn't reach the server. Try again."
}

data class NotesListUi(
    val loading: Boolean = true,
    val query: String = "",
    val notes: List<RisiNoteSummary> = emptyList(),
    val hasMore: Boolean = false,
    /** The server couldn't be reached: [notes] are this phone's own note cards (search is local). */
    val local: Boolean = false,
    val error: String? = null,
    val confirmAll: Boolean = false,
)

/**
 * §30.6 the Notes list: newest first, search via the server (`q`), more pages (`before`), delete one (from
 * my list only) and delete all (confirmed). Offline: the phone's own note cards ([local]), searched locally.
 */
class NotesListModel(
    private val rest: RisiNotesRest,
    private val scope: CoroutineScope,
    /** This phone's note cards (newest first) and their list rows. */
    private val local: suspend () -> List<Pair<RisiNote, RisiNoteSummary>> = { emptyList() },
    private val nameOf: (String) -> String = { it },
) {
    val state: StateFlow<NotesListUi> get() = _state
    private val _state = MutableStateFlow(NotesListUi())
    private var generation = 0

    fun load() = search(_state.value.query)

    /** A new query (blank: everything); an older reply that comes back later is dropped. */
    fun search(q: String) {
        val gen = ++generation
        _state.update { it.copy(loading = true, query = q, error = null) }
        scope.launch {
            val query = q.trim().take(100).takeIf { it.isNotEmpty() }
            val r = runCatching { rest.notes(query, null, RisiNotes.PAGE) }.getOrNull()
            if (gen != generation) return@launch
            when (r) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, notes = r.value.notes, hasMore = r.value.hasMore, local = false) }
                else -> {
                    val mine = runCatching { local() }.getOrDefault(emptyList()).filter { (n, _) -> query == null || RisiNotes.matches(n, query, nameOf) }.map { it.second }
                    val offline = r !is ApiResult.Error
                    _state.update {
                        it.copy(loading = false, notes = if (offline) mine else emptyList(), hasMore = false, local = offline, error = if (offline) null else failure(r))
                    }
                }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasMore || s.loading || s.local) return
        val before = s.notes.lastOrNull()?.noteId ?: return
        val gen = generation
        _state.update { it.copy(loading = true) }
        scope.launch {
            val r = runCatching { rest.notes(s.query.trim().take(100).takeIf { it.isNotEmpty() }, before, RisiNotes.PAGE) }.getOrNull()
            if (gen != generation) return@launch
            when (r) {
                is ApiResult.Ok -> _state.update { st ->
                    val known = st.notes.map { it.noteId.lowercase() }.toSet()
                    st.copy(loading = false, notes = st.notes + r.value.notes.filter { it.noteId.lowercase() !in known }, hasMore = r.value.hasMore)
                }
                else -> _state.update { it.copy(loading = false, error = failure(r)) }
            }
        }
    }

    /** Removes it from my list only (items, promises and events stay); 404 = already gone. */
    fun delete(noteId: String) {
        scope.launch {
            when (val r = runCatching { rest.deleteNote(noteId) }.getOrNull()) {
                is ApiResult.Ok -> drop(noteId)
                is ApiResult.Error -> if (r.httpStatus == 404) drop(noteId) else _state.update { it.copy(error = failure(r)) }
                else -> _state.update { it.copy(error = failure(r)) }
            }
        }
    }

    private fun drop(noteId: String) = _state.update { s -> s.copy(notes = s.notes.filterNot { it.noteId.equals(noteId, true) }, error = null) }

    fun askDeleteAll() = _state.update { it.copy(confirmAll = true) }

    fun cancelDeleteAll() = _state.update { it.copy(confirmAll = false) }

    fun confirmDeleteAll() {
        _state.update { it.copy(confirmAll = false) }
        scope.launch {
            when (val r = runCatching { rest.deleteAllNotes() }.getOrNull()) {
                is ApiResult.Ok -> _state.update { it.copy(notes = emptyList(), hasMore = false, error = null) }
                else -> _state.update { it.copy(error = failure(r)) }
            }
        }
    }
}

/** What the note screen shows: the note, its items in their live state, and my actions on their way. */
data class NoteView(
    val note: RisiNote,
    val items: List<LedgerItemView>,
    /** item id (lowercase) → my action on its way. */
    val pending: Map<String, String>,
    /** The card's copy is past `expires_at`: proposed items are "Not tracked". */
    val expired: Boolean,
)

data class NoteScreenUi(
    val loading: Boolean = true,
    val note: RisiNote? = null,
    /** [note] came from `GET /risi/notes/{id}` (live states then); else it is the local card's copy. */
    val fromServer: Boolean = false,
    /** The rows already on the phone when [note] was fetched (null: card copy). */
    val reflected: Set<String>? = null,
    val error: String? = null,
    val notice: String? = null,
    /** Deleted from my list: the screen closes. */
    val deleted: Boolean = false,
)

/**
 * §30.6 the note screen: the local `note_card` at once, then `GET /risi/notes/{id}` (live) when this is a
 * notes device ([rest] non-null). Ticks and the other item actions go out through [act] (the Risi chat);
 * their `item_update`s change the items here and on the card alike ([view]).
 */
class NoteScreenModel(
    private val noteId: String,
    private val rest: RisiNotesRest?,
    private val scope: CoroutineScope,
    private val act: suspend (itemId: String, action: String, text: String?, due: String?, allDay: Boolean) -> Boolean,
    private val accept: suspend (eventId: String) -> Boolean = { false },
) {
    val state: StateFlow<NoteScreenUi> get() = _state
    private val _state = MutableStateFlow(NoteScreenUi())

    /** [card]: this phone's `note_card` of it (null: not here); [rows]: the Risi chat's rows right now. */
    fun load(card: RisiNote?, rows: List<MessageEntity>) {
        if (card != null && !_state.value.fromServer) _state.update { it.copy(note = card, loading = rest != null) }
        val api = rest
        if (api == null) {
            _state.update { it.copy(loading = false, error = if (card == null) NOTE_NOT_HERE else null) }
            return
        }
        val seen = RisiNotes.reflectedIds(rows)
        scope.launch {
            val r = runCatching { api.note(noteId) }.getOrNull()
            when {
                r is ApiResult.Ok -> _state.update { it.copy(loading = false, note = r.value.note, fromServer = true, reflected = seen, error = null) }
                r is ApiResult.Error && r.httpStatus == 404 -> _state.update { it.copy(loading = false, error = if (it.note == null) NOTE_GONE else null) }
                else -> _state.update { it.copy(loading = false, error = if (it.note == null) failure(r) else null) }
            }
        }
    }

    /** The screen's content for the Risi chat's [rows] now. */
    fun view(rows: List<MessageEntity>, me: String, nowMs: Long): NoteView? {
        val s = _state.value
        val n = s.note ?: return null
        val items = RisiNotes.liveItems(n.items, n.noteId, rows, if (s.fromServer) s.reflected else null)
        val exp = n.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        return NoteView(n, items, RisiNotes.pending(rows, me, nowMs), exp != null && nowMs >= exp)
    }

    /** A tick-box changed: §27.5 `done` / §30.5 `item_reopen`. */
    fun tick(itemId: String, checked: Boolean) = send(itemId, RisiNotes.tickAction(checked))

    /** ✓ ✗ on my proposed item; ✎ with text and due. */
    fun send(itemId: String, action: String, text: String? = null, due: String? = null, allDay: Boolean = false) {
        scope.launch {
            val ok = runCatching { act(itemId, action, text, due, allDay) }.getOrDefault(false)
            if (!ok) _state.update { it.copy(notice = "Couldn't send that. Open your Risi chat and try again.") }
        }
    }

    /** [Accept] on a proposed meeting: the Risi Calendar answer, then the note again (its `my_status`). */
    fun acceptEvent(eventId: String, rows: List<MessageEntity>) {
        scope.launch {
            val ok = runCatching { accept(eventId) }.getOrDefault(false)
            if (!ok) _state.update { it.copy(notice = "Couldn't accept the meeting. Try again.") }
            else load(null, rows)
        }
    }

    fun delete() {
        val api = rest ?: return
        scope.launch {
            when (val r = runCatching { api.deleteNote(noteId) }.getOrNull()) {
                is ApiResult.Ok -> _state.update { it.copy(deleted = true) }
                is ApiResult.Error -> if (r.httpStatus == 404) _state.update { it.copy(deleted = true) } else _state.update { it.copy(notice = failure(r)) }
                else -> _state.update { it.copy(notice = failure(r)) }
            }
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }
}

data class ShareUi(
    /** The text waiting for a chat to be picked (null: no picker). */
    val text: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
)

/**
 * §30.6 share into a chat (phone only): the note rendered as text ([render]), the user picks a chat, and
 * the phone sends it as an ordinary message of its own through the normal send path ([send]). Risi is
 * not involved.
 */
class NoteShareModel(
    private val scope: CoroutineScope,
    private val render: suspend (noteId: String) -> String?,
    private val send: suspend (conversationId: String, text: String) -> Boolean,
) {
    val state: StateFlow<ShareUi> get() = _state
    private val _state = MutableStateFlow(ShareUi())

    fun start(noteId: String) {
        _state.value = ShareUi(busy = true)
        scope.launch {
            val t = runCatching { render(noteId) }.getOrNull()
            _state.value = if (t == null) ShareUi(notice = NOTE_GONE) else ShareUi(text = t)
        }
    }

    /** Share text already rendered (the note screen). */
    fun startWith(text: String) {
        _state.value = ShareUi(text = text)
    }

    fun pick(conversationId: String, chatName: String) {
        val t = _state.value.text ?: return
        _state.value = ShareUi(busy = true)
        scope.launch {
            val ok = runCatching { send(conversationId, t) }.getOrDefault(false)
            _state.value = ShareUi(notice = if (ok) "Shared with $chatName" else "Couldn't share. Try again.")
        }
    }

    fun cancel() {
        _state.value = ShareUi()
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }
}
