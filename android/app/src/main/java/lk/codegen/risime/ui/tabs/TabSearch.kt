package lk.codegen.risime.ui.tabs

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.Tab
import lk.codegen.risime.ui.common.ListRow
import lk.codegen.risime.ui.common.shortStamp
import lk.codegen.risime.ui.search.likePattern

/*
 * §24.9 search inside a chat searches the tab on screen only: its own conversation (the Private
 * anchor, or the Official `grp:`), never the other tab.
 */

/** The conversation a search in this chat covers: the tab on screen; none on the intro card. */
fun searchScopeOf(chatId: String, content: TabContent): String? = when (content) {
    TabContent.Private -> chatId
    is TabContent.Official -> content.conversationId
    is TabContent.Intro, TabContent.Starting -> null
}

fun tabSearchPlaceholder(tab: Tab): String = if (tab == Tab.OFFICIAL) "Search in Official" else "Search in Private"

/** The in-chat search over one conversation ([search] = MessageDao.searchIn in the app). */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatSearchViewModel(
    val conversationId: String,
    val tab: Tab,
    search: (conversationId: String, pattern: String, limit: Int) -> Flow<List<MessageEntity>>,
) : ViewModel() {
    val query = MutableStateFlow("")

    val results: StateFlow<List<MessageEntity>> = query.debounce(150).flatMapLatest { q ->
        if (q.isBlank()) flowOf(emptyList()) else search(conversationId, likePattern(q), 100)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQuery(q: String) {
        query.value = q
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSearchScreen(vm: ChatSearchViewModel, senderName: (MessageEntity) -> String, onBack: () -> Unit) {
    val q by vm.query.collectAsStateWithLifecycle()
    val hits by vm.results.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    TextField(
                        value = q, onValueChange = vm::onQuery, singleLine = true,
                        placeholder = { Text(tabSearchPlaceholder(vm.tab)) },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().testTag("chat_search_field"),
                    )
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(hits, key = { it.clientMsgId }) { m ->
                ListRow(
                    title = if (m.outgoing) "You" else senderName(m),
                    subtitle = lk.codegen.risime.push.bodyPreview(m.kind, m.body),
                    meta = shortStamp(m.localTs),
                    onClick = onBack,
                )
            }
        }
    }
}
