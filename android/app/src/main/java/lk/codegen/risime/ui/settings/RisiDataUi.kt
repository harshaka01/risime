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

/** One item: text, owner · due (all-day aware) · status ("Needs a date"); a tap opens its source chat (server item 9). */
@Composable
private fun PromiseRow(c: RisiCommitment, me: String, nameOf: (String) -> String, tag: String, onOpen: ((RisiCommitment) -> Unit)?) {
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

/** "My promises": `GET /risi/commitments?state=open`. */
class RisiPromisesModel(private val rest: RisiRest, private val scope: CoroutineScope, private val opener: RisiPromiseOpener? = null) {
    val state: StateFlow<PromisesUi> get() = _state
    private val _state = MutableStateFlow(PromisesUi())

    /** Rows are tappable only with an opener. */
    val canOpen: Boolean get() = opener != null

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            when (val r = runCatching { rest.commitments("open") }.getOrNull()) {
                is ApiResult.Ok -> _state.value = PromisesUi(false, r.value.commitments, totals = r.value.totals)
                else -> _state.update { it.copy(loading = false, error = risiErrorMessage(r)) }
            }
        }
    }

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

class RisiPromisesViewModel(rest: RisiRest, opener: RisiPromiseOpener? = null) : ViewModel() {
    val model = RisiPromisesModel(rest, viewModelScope, opener).also { it.load() }
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
                s.items.isEmpty() && s.error == null -> Text(PROMISES_EMPTY, modifier = Modifier.testTag("risi_promises_empty"))
                else -> LazyColumn(Modifier.testTag("risi_promises_list")) {
                    // Server item 9: "I promised" / "Promised to me" / "Others" by `direction`, counts from `totals`.
                    val onOpen: ((RisiCommitment) -> Unit)? = if (model.canOpen) model::open else null
                    s.sections.forEach { sec ->
                        item(key = "h:" + sec.direction) {
                            Text(
                                "${sec.title} (${sec.count})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs).testTag("risi_promises_section_" + sec.direction),
                            )
                        }
                        items(sec.items, key = { sec.direction + ":" + it.commitmentId }) { c -> PromiseRow(c, me, nameOf, PROMISE_TAGS[sec.direction] ?: "risi_promise_other", onOpen) }
                    }
                }
            }
        }
    }
}
