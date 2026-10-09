package lk.codegen.risime.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab as M3Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Search
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.tabs.Tab
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.common.UnreadBadge
import lk.codegen.risime.ui.theme.Spacing

/** Official's subtly different accent (§24.9). */
internal val OfficialAccent = Color(0xFF2E7D6B)

/** One chat's tabs in the app: the controller over the app's tab store and REST. */
class ChatTabsViewModel(c: AppContainer, meId: String, chatId: String, initial: Tab?) : ViewModel() {
    val controller = ChatTabsController(
        chatId, c.chatTabs, { id -> c.startOfficial(id) }, viewModelScope, initial,
        c.db.messages().unreadCounts().map { list -> list.associate { it.conversationId.lowercase() to it.unread } },
        toggler = { id, on -> c.setOfficial(id, on) },
        canToggle = c.db.groups().observe(chatId).map { g ->
            canToggleOfficial(chatId, g?.myRole == lk.codegen.risime.net.GroupMember.ROLE_ADMIN && g.readOnly.not(), null)
        },
    )

    /** The chat's name: the peer's for a 1:1 (also its Official, whose name is empty, §24.1), else the Private group's. */
    val title: kotlinx.coroutines.flow.StateFlow<String> = kotlinx.coroutines.flow.combine(c.contacts.contacts, c.db.groups().all()) { contacts, groups ->
        val peer = lk.codegen.risime.net.dmPeer(chatId, meId)
        if (peer != null) contacts.firstOrNull { it.userId.equals(peer, true) }?.displayName ?: "Chat"
        else lk.codegen.risime.data.groups.groupDisplayName(groups.firstOrNull { it.conversationId.equals(chatId, true) }?.name)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "")

    init {
        // The server's Official state for this chat (on / off / none), once per open.
        viewModelScope.launch { runCatching { c.refreshChat(chatId) } }
    }
}

/**
 * §24.9 "🔒 Private" | "● Official" directly under the chat header, each with its own unread badge.
 * While Official is off only Private shows (plus a link to Official's read-only history if this device has it).
 */
@Composable
fun ChatTabBar(
    state: TabBarState,
    onSelect: (Tab) -> Unit,
    onHistory: () -> Unit,
    onSearch: (() -> Unit)? = null,
    /** §24.4 "Start Official" while Official is off (null: not offered). */
    onStartOfficial: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().testTag("chat_tabs")) {
        if (state.showOfficial) {
            val official = state.selected == Tab.OFFICIAL
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabRow(
                    selectedTabIndex = if (official) 1 else 0,
                    contentColor = if (official) OfficialAccent else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                ) {
                    M3Tab(selected = !official, onClick = { onSelect(Tab.PRIVATE) }, modifier = Modifier.testTag("tab_private")) {
                        TabLabel(PRIVATE_TAB_LABEL, state.unread.private)
                    }
                    M3Tab(selected = official, onClick = { onSelect(Tab.OFFICIAL) }, modifier = Modifier.testTag("tab_official")) {
                        TabLabel(OFFICIAL_TAB_LABEL, state.unread.official)
                    }
                }
                // §24.9: search inside a chat searches the tab on screen.
                onSearch?.let { s -> TabSearchButton(state.selected, s) }
            }
            if (official) {
                Text(
                    RISI_LISTENING,
                    Modifier.fillMaxWidth().background(OfficialAccent.copy(alpha = 0.12f)).padding(horizontal = Spacing.lg, vertical = Spacing.xxs),
                    style = MaterialTheme.typography.labelMedium, color = OfficialAccent,
                )
            }
        } else {
            // §24.4 Official is off: only the Private label, plus "Start Official" (toggle rights) and the read-only history.
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = Spacing.sm)) { TabLabel(PRIVATE_TAB_LABEL, state.unread.private) }
                onStartOfficial?.let { start ->
                    TextButton(onClick = start, enabled = state.canStartOfficial && !state.starting, modifier = Modifier.testTag("tab_start_official")) {
                        Text(START_OFFICIAL_LABEL)
                    }
                }
                onSearch?.let { s -> TabSearchButton(Tab.PRIVATE, s) }
            }
            if (onStartOfficial != null && !state.canStartOfficial) {
                Text(
                    START_OFFICIAL_ADMINS_ONLY,
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.historyAvailable) {
                TextButton(onClick = onHistory, modifier = Modifier.padding(horizontal = Spacing.sm)) { Text(OFFICIAL_HISTORY_LABEL) }
            }
        }
    }
}

@Composable
private fun TabSearchButton(tab: Tab, onSearch: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onSearch, modifier = Modifier.testTag("tab_search")) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, tabSearchPlaceholder(tab))
    }
}

/** §24.9 the Official banner (first open of the Official tab). */
@Composable
fun OfficialBanner(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(OfficialAccent.copy(alpha = 0.08f)).padding(start = Spacing.lg, end = Spacing.xs).testTag("official_banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(OFFICIAL_BANNER_TEXT, Modifier.weight(1f).padding(vertical = Spacing.sm), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("official_banner_ok")) { Text("OK") }
    }
}

@Composable
private fun TabLabel(text: String, unread: Int) {
    Row(Modifier.padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall)
        if (unread > 0) UnreadBadge(unread)
    }
}

/** §24.2 the intro card of a chat without an Official conversation: [Start Official] creates it. */
@Composable
fun OfficialIntroCard(content: TabContent, onStart: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(OFFICIAL_INTRO_TEXT, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                when (content) {
                    is TabContent.Intro -> {
                        content.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.testTag("official_error")) }
                        Button(onClick = onStart, enabled = !content.busy, modifier = Modifier.testTag("start_official")) { Text(START_OFFICIAL_LABEL) }
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        CircularProgressIndicator(Modifier.padding(2.dp))
                        Text(OFFICIAL_STARTING_TEXT)
                    }
                }
            }
        }
    }
}

/**
 * One chat with two tabs. [tabsOn] false: exactly the screen as before ([privateScreen] with no tab
 * bar). Otherwise the selected tab: the Private screen, the Official conversation's screen, or the
 * intro card (no Official conversation yet), each with the tab bar under its header.
 */
@Composable
fun TabbedChat(
    tabsOn: Boolean,
    vm: @Composable () -> ChatTabsViewModel,
    onBack: () -> Unit,
    privateScreen: @Composable (tabBar: (@Composable () -> Unit)?) -> Unit,
    officialScreen: @Composable (conversationId: String, readOnly: Boolean, tabBar: @Composable () -> Unit) -> Unit,
    /** §24.9 search in the tab on screen (its conversation, its tab). */
    onSearch: ((conversationId: String, tab: Tab) -> Unit)? = null,
    /** The intro screen's title opens chat info (Lock chat is there, not in a ⋮). */
    onInfo: (() -> Unit)? = null,
) {
    if (!tabsOn) return privateScreen(null)
    val model = vm()
    val title by model.title.collectAsStateWithLifecycle()
    TabbedChatContent(model.controller, title, onBack, privateScreen, officialScreen, onSearch, onInfo)
}

@Composable
fun TabbedChatContent(
    controller: ChatTabsController,
    title: String,
    onBack: () -> Unit,
    privateScreen: @Composable (tabBar: (@Composable () -> Unit)?) -> Unit,
    officialScreen: @Composable (conversationId: String, readOnly: Boolean, tabBar: @Composable () -> Unit) -> Unit,
    onSearch: ((conversationId: String, tab: Tab) -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
) {
    val content by controller.content.collectAsStateWithLifecycle()
    val bar by controller.bar.collectAsStateWithLifecycle()
    val notice by controller.notice.collectAsStateWithLifecycle()
    val banner by controller.banner.collectAsStateWithLifecycle()
    val tabBar: @Composable () -> Unit = {
        val scope = searchScopeOf(controller.chatId, content)
        ChatTabBar(
            bar, controller::select, controller::openHistory,
            onSearch?.let { s -> scope?.let { conv -> { s(conv, if (content is TabContent.Official) Tab.OFFICIAL else Tab.PRIVATE) } } },
            onStartOfficial = controller::turnOfficialOn,
        )
        if (banner && bar.selected == Tab.OFFICIAL) OfficialBanner(controller::dismissBanner)
        notice?.let { n ->
            Text(
                n,
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = Spacing.lg, vertical = Spacing.xs).testTag("tabs_notice"),
                style = MaterialTheme.typography.bodySmall,
            )
            LaunchedEffect(n) {
                kotlinx.coroutines.delay(6_000)
                controller.clearNotice()
            }
        }
    }
    when (val c = content) {
        TabContent.Private -> privateScreen(tabBar)
        is TabContent.Official -> officialScreen(c.conversationId, c.readOnly, tabBar)
        is TabContent.Intro, TabContent.Starting -> Scaffold(
            topBar = {
                RisiTopBar(
                    title = title, onBack = onBack,
                    onTitleClick = onInfo, titleClickLabel = "Chat info",
                )
            },
            contentWindowInsets = WindowInsets(0),
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                tabBar()
                OfficialIntroCard(c, controller::startOfficial, Modifier.weight(1f))
            }
        }
    }
}
