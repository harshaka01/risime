package lk.codegen.risime.ui.tabs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lk.codegen.risime.data.tabs.ChatTabs
import lk.codegen.risime.data.tabs.OfficialState
import lk.codegen.risime.data.tabs.Tab
import lk.codegen.risime.data.tabs.TabUnread
import lk.codegen.risime.data.tabs.defaultTab
import lk.codegen.risime.data.tabs.officialConversationOf
import lk.codegen.risime.data.tabs.tabUnread
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.TabsErrors

/** §24.2 the intro card's text (verbatim from the contract). */
const val OFFICIAL_INTRO_TEXT = "Risi listens in Official and helps with follow-ups. Use Private for anything Risi shouldn't see."
const val START_OFFICIAL_LABEL = "Start Official"
const val OFFICIAL_NOT_READY_TEXT = "Official needs everyone on the latest app"
const val OFFICIAL_HISTORY_LABEL = "Official history (read-only)"
const val RISI_LISTENING = "Risi is listening"
const val OFFICIAL_STARTING_TEXT = "Starting Official…"
const val PRIVATE_TAB_LABEL = "🔒 Private"
const val OFFICIAL_TAB_LABEL = "● Official"

/** §24.9 the Official banner, shown on the first open of a chat's Official tab. */
const val OFFICIAL_BANNER_TEXT = "Risi listens in Official and helps with follow-ups."

/** The user-facing text for a refused `POST …/official` (null code: offline). */
fun officialErrorText(code: String?): String = when (code) {
    AuthErrors.NOT_READY -> OFFICIAL_NOT_READY_TEXT
    TabsErrors.AGENT_UNAVAILABLE -> "Risi isn't available right now. Try again later."
    TabsErrors.OFFICIAL_OFF -> "Official is turned off in this chat."
    AuthErrors.NOT_E2EE -> "Official needs this chat to be end-to-end encrypted first."
    AuthErrors.NOT_FRIENDS -> "You can only start Official with a friend."
    AuthErrors.RATE_LIMITED -> "Too many tries. Try again later."
    null -> "You're offline. Try again when you're connected."
    else -> "Couldn't start Official ($code)."
}

/** What one chat's screen shows under its header. */
sealed interface TabContent {
    /** The Private conversation (the chat's own id): the existing screen. */
    data object Private : TabContent

    /** The Official conversation; [readOnly] while Official is off (§24.4: its history stays). */
    data class Official(val conversationId: String, val readOnly: Boolean) : TabContent

    /** No Official conversation yet (lazy creation): the intro card with [Start Official]. */
    data class Intro(val busy: Boolean, val error: String?) : TabContent

    /** `POST …/official` accepted; the conversation becomes readable when its Welcome/commit lands. */
    data object Starting : TabContent
}

/** The tab bar's state: which tabs show, the selected one, the per-tab unread badges, and the off-state history link. */
data class TabBarState(
    val selected: Tab,
    val showOfficial: Boolean,
    val unread: TabUnread,
    /** Official is off and this device holds its read-only history. */
    val historyAvailable: Boolean,
    /** "Start Official" in the tab row is offered (Official off): enabled for me (§24.4: 1:1 either person, group admins). */
    val canStartOfficial: Boolean = false,
    /** A "Start Official" request is under way. */
    val starting: Boolean = false,
)

/** Under a disabled "Start Official" in a group where I'm not an admin. */
const val START_OFFICIAL_ADMINS_ONLY = "Only admins can start Official"

/** `POST …/official` (the app: [lk.codegen.risime.AppContainer.startOfficial]; a fake in tests). */
fun interface OfficialStarter {
    suspend fun start(chatId: String): ApiResult<*>
}

/**
 * §24.9 one chat's two tabs. [initial] = the tab the chat was opened on (an Official conversation
 * id opens Official); otherwise the last one used ([defaultTab]). Private-ness comes from
 * [ChatTabs.rows] (MLS only): a conversation is shown in Official only when its MLS state says so.
 */
class ChatTabsController(
    val chatId: String,
    private val tabs: ChatTabs,
    private val starter: OfficialStarter,
    private val scope: CoroutineScope,
    initial: Tab? = null,
    /** Unread per conversation (MessageDao.unreadCounts in the app). */
    unreadByConversation: Flow<Map<String, Int>> = flowOf(emptyMap()),
    /** `PATCH /chats/{id}` `on`: the tab row's "Start Official" while Official is off (null: not offered). */
    private val toggler: OfficialToggler? = null,
    /** §24.4 toggle rights: either person in a 1:1, admins in a group. */
    canToggle: Flow<Boolean> = flowOf(chatId.startsWith("dm:")),
) {
    private val key = chatId.lowercase()
    private val selected = MutableStateFlow(initial)
    private val start = MutableStateFlow(Pair(false, null as String?)) // busy, error

    val official: StateFlow<String?> = tabs.rows.map { officialConversationOf(chatId, it) }
        .stateIn(scope, SharingStarted.Eagerly, officialConversationOf(chatId, tabs.rows.value))

    private val officialState: StateFlow<String?> = tabs.prefs.map { it[key]?.officialState }
        .stateIn(scope, SharingStarted.Eagerly, tabs.prefs.value[key]?.officialState)

    /** The selected tab: the user's choice in this screen, else the last one used. */
    val tab: StateFlow<Tab> = combine(selected, tabs.prefs, official) { sel, prefs, off ->
        val t = sel ?: defaultTab(prefs[key])
        // Off with nothing to read: Private only.
        if (t == Tab.OFFICIAL && prefs[key]?.officialState == OfficialState.OFF && off == null) Tab.PRIVATE else t
    }.stateIn(scope, SharingStarted.Eagerly, selected.value ?: defaultTab(tabs.prefs.value[key]))

    val content: StateFlow<TabContent> = combine(tab, official, officialState, tabs.starting, start) { t, off, state, starting, (busy, error) ->
        when {
            t == Tab.PRIVATE -> TabContent.Private
            off != null -> TabContent.Official(off, readOnly = state == OfficialState.OFF)
            key in starting -> TabContent.Starting
            else -> TabContent.Intro(busy, error)
        }
    }.stateIn(scope, SharingStarted.Eagerly, TabContent.Private)

    val bar: StateFlow<TabBarState> = combine(
        combine(tab, official, officialState, ::Triple), unreadByConversation, canToggle, start,
    ) { (t, off, state), unread, can, (busy, _) ->
        TabBarState(
            selected = t,
            showOfficial = state != OfficialState.OFF,
            unread = tabUnread(key, off?.lowercase(), unread),
            historyAvailable = state == OfficialState.OFF && off != null,
            canStartOfficial = toggler != null && can,
            starting = busy,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TabBarState(tab.value, true, TabUnread(0, 0), false))

    private val bannerShown = MutableStateFlow(false)
    private val bannerDismissed = MutableStateFlow(false)

    /**
     * §24.9 the Official banner: on the first open of this chat's (writable) Official tab on this
     * phone. It is recorded as seen at once, so it stays for this visit only (or until dismissed).
     */
    val banner: StateFlow<Boolean> = combine(content, bannerShown, bannerDismissed) { c, shown, dismissed ->
        c is TabContent.Official && !c.readOnly && shown && !dismissed
    }.stateIn(scope, SharingStarted.Eagerly, false)

    fun dismissBanner() {
        bannerDismissed.value = true
    }

    init {
        scope.launch {
            content.collect { c ->
                if (c is TabContent.Official && !c.readOnly && key !in tabs.bannerSeen.value) {
                    bannerShown.value = true
                    tabs.markBannerSeen(key)
                }
            }
        }
    }

    /** A one-off line for this chat (e.g. a new group's Official refused). */
    val notice: StateFlow<String?> = tabs.notice.map { it?.takeIf { (c, _) -> c == key }?.second }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun clearNotice() = tabs.clearNotice()

    /** §24.5/§24.9 the call this chat places from the tab on screen (Official: §20 on its `grp:`, 1:1 too; null: none yet). */
    fun callTarget(): lk.codegen.risime.data.tabs.CallTarget? = lk.codegen.risime.data.tabs.callTargetFor(chatId, tab.value, official.value)

    fun select(t: Tab) {
        selected.value = t
        scope.launch { tabs.setLastTab(chatId, t) }
    }

    /** §24.4: Official is off: open its read-only history (not remembered as the last tab). */
    fun openHistory() {
        selected.value = Tab.OFFICIAL
    }

    /**
     * §24.4 "Start Official" in the tab row while Official is off: `PATCH … {"official": "on"}`, then
     * the Official tab; with no Official conversation yet, its creation (§24.2) right after. A refusal
     * is shown under the tab row.
     */
    fun turnOfficialOn() {
        val t = toggler ?: return
        if (start.value.first) return
        start.value = true to null
        scope.launch {
            val r = runCatching { t.set(chatId, true) }.getOrNull()
            start.value = false to null
            when (r) {
                is ApiResult.Ok -> {
                    tabs.setOfficialState(chatId, r.value.official.state)
                    select(Tab.OFFICIAL)
                    if (official.value == null) startOfficial()
                }
                is ApiResult.Error -> tabs.setNotice(chatId, officialToggleErrorText(r.code, turningOn = true))
                else -> tabs.setNotice(chatId, officialToggleErrorText(null, turningOn = true))
            }
        }
    }

    /** [Start Official] on the intro card (§24.2 lazy creation). */
    fun startOfficial() {
        if (start.value.first) return
        start.value = true to null
        scope.launch {
            val r = runCatching { starter.start(chatId) }.getOrNull()
            start.update {
                when (r) {
                    is ApiResult.Ok -> false to null
                    is ApiResult.Error -> false to officialErrorText(r.code)
                    else -> false to officialErrorText(null)
                }
            }
        }
    }
}
