package lk.codegen.risime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lk.codegen.risime.data.tabs.RisiPromises
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.RisiCommitment
import lk.codegen.risime.net.RisiTotals
import lk.codegen.risime.net.RisiFact
import lk.codegen.risime.net.RisiRest
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.common.SectionHeader
import lk.codegen.risime.ui.tabs.formatDue
import lk.codegen.risime.ui.theme.Spacing

const val RISI_KNOWS_TITLE = "What Risi knows about me"
const val MY_PROMISES_TITLE = "My promises"
const val RISI_KNOWS_BLURB = "What Risi has learned from your Official chats. Delete any item, or everything."
const val DELETE_EVERYTHING_LABEL = "Delete everything"
const val RISI_FACTS_EMPTY = "Risi hasn't stored anything about you."
const val PROMISES_EMPTY = "No open promises."
const val OWED_TO_ME_TITLE = "Owed to me"

/** Row test tags per direction (the v1.27 tags kept for "I promised" and "Promised to me"). */
private val PROMISE_TAGS = mapOf(RisiPromises.I_PROMISED to "risi_promise", RisiPromises.PROMISED_TO_ME to "risi_owed", RisiPromises.OTHERS to "risi_promise_other")

/** §30.5 a row's Notes extras: "From note: …", [Done] / [Reopen]. */
private class PromiseNoteBits(val title: String?, val onNote: (() -> Unit)?, val done: (() -> Unit)?, val reopen: (() -> Unit)?, val busy: Boolean)

/** One item: text, owner · due (all-day aware) · status ("Needs a date"); a tap opens its source chat (server item 9). */
@Composable
private fun PromiseRow(c: RisiCommitment, me: String, nameOf: (String) -> String, tag: String, onOpen: ((RisiCommitment) -> Unit)?, bits: PromiseNoteBits? = null) {
    val open = onOpen?.takeIf { RisiPromises.target(c) != null }
    Column(
        Modifier.fillMaxWidth().then(if (open != null) Modifier.clickable(onClickLabel = "Open chat") { open(c) } else Modifier)
            .padding(vertical = Spacing.sm).testTag(tag),
    ) {
        Text(c.text, style = MaterialTheme.typography.bodyLarge)
        val who = RisiPromises.owner(c, me, nameOf)
        val due = RisiPromises.due(c, timed = { formatDue(it) })
        Text(listOfNotNull(who, due).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_promise_meta"))
        val status = RisiPromises.status(c)
        Text(
            status, style = MaterialTheme.typography.labelMedium,
            color = if (status == "Needs a date") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("risi_promise_status"),
        )
        if (bits != null) {
            bits.onNote?.let { open ->
                TextButton(onClick = open, modifier = Modifier.testTag("risi_promise_from_note")) { Text("From note: " + (bits.title ?: "open"), maxLines = 1) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                bits.done?.let { OutlinedButton(onClick = it, enabled = !bits.busy, modifier = Modifier.testTag("risi_promise_done")) { Text("Done") } }
                bits.reopen?.let { OutlinedButton(onClick = it, enabled = !bits.busy, modifier = Modifier.testTag("risi_promise_reopen")) { Text("Reopen") } }
            }
        }
    }
    HorizontalDivider()
}
const val RISI_OFFLINE = "Couldn't reach the server. Try again."
const val RISI_UNAVAILABLE = "Risi isn't available for this account yet."
const val RISI_SERVER_PROBLEM = "The server had a problem \u2014 try again."

/** Honest message for a failed Risi call: only network trouble says "couldn't reach the server". */
fun risiErrorMessage(r: ApiResult<*>?): String = when {
    r is ApiResult.Error && (r.httpStatus == 404 || r.httpStatus == 403) -> RISI_UNAVAILABLE
    r is ApiResult.Error && r.httpStatus >= 500 -> RISI_SERVER_PROBLEM
    r is ApiResult.Error -> "Something went wrong (${r.httpStatus}). Try again."
    else -> RISI_OFFLINE
}

/** §24.11 the order and titles of the fact kinds. */
val FACT_KINDS = listOf("commitment" to "Commitments", "date" to "Dates", "person" to "People", "preference" to "Preferences", "topic" to "Topics", "note" to "Notes", "summary" to "Summaries")

data class FactsUi(
    val loading: Boolean = true,
    val facts: List<RisiFact> = emptyList(),
    val error: String? = null,
    val confirmAll: Boolean = false,
) {
    /** The facts by kind in [FACT_KINDS] order, then any other kind; empty groups are left out. */
    val grouped: List<Pair<String, List<RisiFact>>>
        get() {
            val known = FACT_KINDS.mapNotNull { (k, title) -> facts.filter { it.kind == k }.takeIf { it.isNotEmpty() }?.let { title to it } }
            val other = facts.filter { f -> FACT_KINDS.none { it.first == f.kind } }
            return if (other.isEmpty()) known else known + ("Other" to other)
        }
}

/** "What Risi knows about me": list, per-item delete, "Delete everything" (confirmed). A fake [RisiRest] in tests. */
class RisiFactsModel(private val rest: RisiRest, private val scope: CoroutineScope) {
    val state: StateFlow<FactsUi> get() = _state
    private val _state = MutableStateFlow(FactsUi())

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            when (val r = runCatching { rest.facts() }.getOrNull()) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, facts = r.value.facts) }
                else -> _state.update { it.copy(loading = false, error = risiErrorMessage(r)) }
            }
        }
    }

    /** Hard delete on the server; the row leaves the list only once the server said so. */
    fun delete(factId: String) {
        scope.launch {
            when (val r = runCatching { rest.deleteFact(factId) }.getOrNull()) {
                is ApiResult.Ok -> _state.update { s -> s.copy(facts = s.facts.filter { it.factId != factId }, error = null) }
                is ApiResult.Error -> _state.update { s -> s.copy(facts = s.facts.filter { it.factId != factId }) } // 404: already gone
                else -> _state.update { it.copy(error = risiErrorMessage(r)) }
            }
        }
    }

    fun askDeleteAll() = _state.update { it.copy(confirmAll = true) }

    fun cancelDeleteAll() = _state.update { it.copy(confirmAll = false) }

    fun confirmDeleteAll() {
        _state.update { it.copy(confirmAll = false) }
        scope.launch {
            when (val r = runCatching { rest.deleteAllFacts() }.getOrNull()) {
                is ApiResult.Ok -> _state.update { it.copy(facts = emptyList(), error = null) }
                else -> _state.update { it.copy(error = risiErrorMessage(r)) }
            }
        }
    }
}

data class PromisesUi(
    val loading: Boolean = true,
    val items: List<RisiCommitment> = emptyList(),
    val error: String? = null,
    /** Server item 9: the reply's per-direction counts (null from older servers: counted from the list). */
    val totals: RisiTotals? = null,
    /** A tap could not open the chat ("That chat isn't on this phone."). */
    val note: String? = null,
    /** v1.29 §30.5 (Notes device): items done in the last 7 days ([Reopen]). */
    val done: List<RisiCommitment> = emptyList(),
    /** note_id (lowercase) → its title for "From note: …". */
    val noteTitles: Map<String, String> = emptyMap(),
    /** Items with a `done`/`item_reopen` of mine on its way (their buttons are off). */
    val acting: Set<String> = emptySet(),
) {
    /** "I promised" / "Promised to me" / "Others" with their counts. */
    val sections: List<RisiPromises.Section> get() = RisiPromises.sections(items, totals)
}

/** Opens an item's source chat for My promises (the app's navigation; absent in tests and previews). */
interface RisiPromiseOpener {
    /** The conversation is on this phone. */
    suspend fun has(conversationId: String): Boolean

    /** Open it, scrolled to [messageId] once that message is here (null: at its end). */
    fun open(conversationId: String, messageId: String?)
}

const val PROMISE_CHAT_MISSING = "That chat isn't on this phone."
const val DONE_RECENTLY_TITLE = "Done (last 7 days)"
const val PROMISE_ACTION_FAILED = "Couldn't send that. Open your Risi chat and try again."

/** §30.5 a done item may be reopened within 7 days of done. */
const val REOPEN_WINDOW_MS = 7L * 86_400_000

/**
 * v1.29 §30.5 My promises on a Risi Notes device: [Done] / [Reopen] are the same actions as ticking in a note
 * (`done` / `item_reopen` in the user's Risi chat), and "From note: <title>" opens the note.
 */
interface RisiPromiseNotes {
    /** The note's title as this phone builds it (null: unknown here). */
    suspend fun title(noteId: String): String?

    fun open(noteId: String)

    /** `done` / `item_reopen` on a ledger item (false: not sent). */
    suspend fun act(itemId: String, action: String): Boolean
}

/** "My promises": `GET /risi/commitments?state=open` (and, on a Notes device, the last 7 days' done items from `state=all`). */
class RisiPromisesModel(
    private val rest: RisiRest,
    private val scope: CoroutineScope,
    private val opener: RisiPromiseOpener? = null,
    private val notes: RisiPromiseNotes? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val state: StateFlow<PromisesUi> get() = _state
    private val _state = MutableStateFlow(PromisesUi())

    /** Rows are tappable only with an opener. */
    val canOpen: Boolean get() = opener != null

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            when (val r = runCatching { rest.commitments("open") }.getOrNull()) {
                is ApiResult.Ok -> {
                    val done = if (notes == null) emptyList() else recentlyDone()
                    _state.value = PromisesUi(false, r.value.commitments, totals = r.value.totals, done = done)
                    titles(r.value.commitments + done)
                }
                else -> _state.update { it.copy(loading = false, error = risiErrorMessage(r)) }
            }
        }
    }

    private suspend fun recentlyDone(): List<RisiCommitment> {
        val all = (runCatching { rest.commitments("all") }.getOrNull() as? ApiResult.Ok)?.value?.commitments ?: return emptyList()
        val since = now() - REOPEN_WINDOW_MS
        return all.filter { c ->
            c.state == lk.codegen.risime.net.RisiItemStates.DONE && canTick(c) &&
                (c.updatedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L) >= since
        }
    }

    private suspend fun titles(items: List<RisiCommitment>) {
        val n = notes ?: return
        val ids = items.mapNotNull { it.noteId?.lowercase() }.distinct()
        if (ids.isEmpty()) return
        val out = HashMap<String, String>()
        for (id in ids) runCatching { n.title(id) }.getOrNull()?.let { out[id] = it }
        _state.update { it.copy(noteTitles = it.noteTitles + out) }
    }

    /** [Done] / [Reopen] show on a ledger item I own or am a counterpart of (Notes device only). */
    fun canTick(c: RisiCommitment): Boolean =
        notes != null && (c.summaryId != null || c.noteId != null) && RisiPromises.direction(c) != RisiPromises.OTHERS

    /** [Done]: the item leaves the open list for "Done (last 7 days)" once the action is sent. */
    fun markDone(c: RisiCommitment) = act(c, lk.codegen.risime.data.tabs.RisiLedger.DONE) { s ->
        s.copy(items = s.items.filterNot { it.commitmentId == c.commitmentId }, done = listOf(c.copy(state = lk.codegen.risime.net.RisiItemStates.DONE, status = null)) + s.done)
    }

    /** [Reopen] (§30.5 `item_reopen`): back to the open list. */
    fun reopen(c: RisiCommitment) = act(c, lk.codegen.risime.net.RisiActions130.ITEM_REOPEN) { s ->
        s.copy(done = s.done.filterNot { it.commitmentId == c.commitmentId }, items = s.items + c.copy(state = lk.codegen.risime.net.RisiItemStates.CONFIRMED, status = null))
    }

    private fun act(c: RisiCommitment, action: String, apply: (PromisesUi) -> PromisesUi) {
        val n = notes ?: return
        val id = c.commitmentId.lowercase()
        if (id in _state.value.acting) return
        _state.update { it.copy(acting = it.acting + id) }
        scope.launch {
            val ok = runCatching { n.act(c.commitmentId, action) }.getOrDefault(false)
            _state.update { s -> (if (ok) apply(s) else s.copy(note = PROMISE_ACTION_FAILED)).copy(acting = s.acting - id) }
        }
    }

    fun openNote(noteId: String) = notes?.open(noteId)

    /** A Risi Notes device (else My promises is exactly as before). */
    val notesOn: Boolean get() = notes != null

    /** A tap: the item's source conversation at its source message; a chat not on this phone says so. */
    fun open(c: RisiCommitment) {
        val o = opener ?: return
        val (conv, mid) = RisiPromises.target(c) ?: return
        scope.launch {
            if (runCatching { o.has(conv) }.getOrDefault(false)) {
                _state.update { it.copy(note = null) }
                o.open(conv, mid)
            } else {
                _state.update { it.copy(note = PROMISE_CHAT_MISSING) }
            }
        }
    }
}

class RisiFactsViewModel(rest: RisiRest) : ViewModel() {
    val model = RisiFactsModel(rest, viewModelScope).also { it.load() }
}

class RisiPromisesViewModel(rest: RisiRest, opener: RisiPromiseOpener? = null, notes: ((CoroutineScope) -> RisiPromiseNotes)? = null) : ViewModel() {
    val model = RisiPromisesModel(rest, viewModelScope, opener, notes?.invoke(viewModelScope)).also { it.load() }
}

@Composable
fun RisiFactsScreen(model: RisiFactsModel, onBack: () -> Unit) {
    val s by model.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { RisiTopBar(title = RISI_KNOWS_TITLE, onBack = onBack) }, contentWindowInsets = WindowInsets(0)) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = Spacing.xl, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(RISI_KNOWS_BLURB, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("risi_facts_error")) }
            when {
                s.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                s.facts.isEmpty() && s.error == null -> Text(RISI_FACTS_EMPTY, modifier = Modifier.testTag("risi_facts_empty"))
                else -> {
                    LazyColumn(Modifier.weight(1f).testTag("risi_facts_list")) {
                        s.grouped.forEach { (title, facts) ->
                            item(key = "h:$title") { SectionHeader(title) }
                            items(facts, key = { it.factId }) { f ->
                                Row(Modifier.fillMaxWidth().testTag("risi_fact"), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(vertical = Spacing.sm)) {
                                        Text(f.text)
                                        // A stored chat summary (proposal 2026-10-09-risi-30day-summaries): its day or week.
                                        lk.codegen.risime.data.tabs.SummaryPeriods.factLabel(f.scope, f.period)?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_fact_period"))
                                        }
                                    }
                                    TextButton(onClick = { model.delete(f.factId) }, modifier = Modifier.testTag("risi_fact_delete")) { Text("Delete") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    OutlinedButton(onClick = model::askDeleteAll, modifier = Modifier.fillMaxWidth().testTag("risi_delete_all")) { Text(DELETE_EVERYTHING_LABEL) }
                }
            }
        }
    }
    if (s.confirmAll) {
        AlertDialog(
            onDismissRequest = model::cancelDeleteAll,
            title = { Text(DELETE_EVERYTHING_LABEL + "?") },
            text = { Text("Risi forgets everything it has stored about you. This can't be undone.") },
            confirmButton = { TextButton(onClick = model::confirmDeleteAll, modifier = Modifier.testTag("risi_delete_all_confirm")) { Text(DELETE_EVERYTHING_LABEL) } },
            dismissButton = { TextButton(onClick = model::cancelDeleteAll) { Text("Cancel") } },
        )
    }
}

@Composable
fun RisiPromisesScreen(model: RisiPromisesModel, me: String, nameOf: (String) -> String, onBack: () -> Unit) {
    val s by model.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { RisiTopBar(title = MY_PROMISES_TITLE, onBack = onBack) }, contentWindowInsets = WindowInsets(0)) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = Spacing.xl, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("risi_promises_note")) }
            when {
                s.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                s.items.isEmpty() && s.done.isEmpty() && s.error == null -> Text(PROMISES_EMPTY, modifier = Modifier.testTag("risi_promises_empty"))
                else -> LazyColumn(Modifier.testTag("risi_promises_list")) {
                    // Server item 9: "I promised" / "Promised to me" / "Others" by `direction`, counts from `totals`.
                    val onOpen: ((RisiCommitment) -> Unit)? = if (model.canOpen) model::open else null
                    // v1.29 §30.5: on a Notes device, [Done] / [Reopen] and "From note: …".
                    fun bits(c: RisiCommitment, done: Boolean): PromiseNoteBits? {
                        val tick = model.canTick(c)
                        val noteId = c.noteId
                        if (!model.notesOn || (!tick && noteId == null)) return null
                        return PromiseNoteBits(
                            title = noteId?.let { s.noteTitles[it.lowercase()] },
                            onNote = noteId?.let { id -> { model.openNote(id) } },
                            done = if (tick && !done) ({ model.markDone(c) }) else null,
                            reopen = if (tick && done) ({ model.reopen(c) }) else null,
                            busy = c.commitmentId.lowercase() in s.acting,
                        )
                    }
                    s.sections.forEach { sec ->
                        item(key = "h:" + sec.direction) {
                            Text(
                                "${sec.title} (${sec.count})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs).testTag("risi_promises_section_" + sec.direction),
                            )
                        }
                        items(sec.items, key = { sec.direction + ":" + it.commitmentId }) { c -> PromiseRow(c, me, nameOf, PROMISE_TAGS[sec.direction] ?: "risi_promise_other", onOpen, bits(c, false)) }
                    }
                    if (s.done.isNotEmpty()) {
                        item(key = "h:done") {
                            Text(
                                "$DONE_RECENTLY_TITLE (${s.done.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs).testTag("risi_promises_section_done"),
                            )
                        }
                        items(s.done, key = { "done:" + it.commitmentId }) { c -> PromiseRow(c, me, nameOf, "risi_promise_done_row", onOpen, bits(c, true)) }
                    }
                }
            }
        }
    }
}
