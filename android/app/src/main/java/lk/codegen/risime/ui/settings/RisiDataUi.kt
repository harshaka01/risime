package lk.codegen.risime.ui.settings

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
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.RisiCommitment
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
const val RISI_OFFLINE = "Couldn't reach the server. Try again."

/** §24.11 the order and titles of the fact kinds. */
val FACT_KINDS = listOf("commitment" to "Commitments", "date" to "Dates", "person" to "People", "preference" to "Preferences", "topic" to "Topics")

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
                else -> _state.update { it.copy(loading = false, error = RISI_OFFLINE) }
            }
        }
    }

    /** Hard delete on the server; the row leaves the list only once the server said so. */
    fun delete(factId: String) {
        scope.launch {
            when (runCatching { rest.deleteFact(factId) }.getOrNull()) {
                is ApiResult.Ok -> _state.update { s -> s.copy(facts = s.facts.filter { it.factId != factId }, error = null) }
                is ApiResult.Error -> _state.update { s -> s.copy(facts = s.facts.filter { it.factId != factId }) } // 404: already gone
                else -> _state.update { it.copy(error = RISI_OFFLINE) }
            }
        }
    }

    fun askDeleteAll() = _state.update { it.copy(confirmAll = true) }

    fun cancelDeleteAll() = _state.update { it.copy(confirmAll = false) }

    fun confirmDeleteAll() {
        _state.update { it.copy(confirmAll = false) }
        scope.launch {
            when (runCatching { rest.deleteAllFacts() }.getOrNull()) {
                is ApiResult.Ok -> _state.update { it.copy(facts = emptyList(), error = null) }
                else -> _state.update { it.copy(error = RISI_OFFLINE) }
            }
        }
    }
}

data class PromisesUi(val loading: Boolean = true, val items: List<RisiCommitment> = emptyList(), val error: String? = null)

/** "My promises": `GET /risi/commitments?state=open`. */
class RisiPromisesModel(private val rest: RisiRest, private val scope: CoroutineScope) {
    val state: StateFlow<PromisesUi> get() = _state
    private val _state = MutableStateFlow(PromisesUi())

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            when (val r = runCatching { rest.commitments("open") }.getOrNull()) {
                is ApiResult.Ok -> _state.value = PromisesUi(false, r.value.commitments)
                else -> _state.update { it.copy(loading = false, error = RISI_OFFLINE) }
            }
        }
    }
}

class RisiFactsViewModel(rest: RisiRest) : ViewModel() {
    val model = RisiFactsModel(rest, viewModelScope).also { it.load() }
}

class RisiPromisesViewModel(rest: RisiRest) : ViewModel() {
    val model = RisiPromisesModel(rest, viewModelScope).also { it.load() }
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
                                    Text(f.text, Modifier.weight(1f).padding(vertical = Spacing.sm))
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
            when {
                s.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                s.items.isEmpty() && s.error == null -> Text(PROMISES_EMPTY, modifier = Modifier.testTag("risi_promises_empty"))
                else -> LazyColumn(Modifier.testTag("risi_promises_list")) {
                    items(s.items, key = { it.commitmentId }) { c ->
                        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm).testTag("risi_promise")) {
                            Text(c.text, style = MaterialTheme.typography.bodyLarge)
                            val who = c.owner?.let { if (it.equals(me, true)) "You" else nameOf(it) }
                            val due = formatDue(c.due) ?: c.dueText
                            Text(listOfNotNull(who, due?.let { "due $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
