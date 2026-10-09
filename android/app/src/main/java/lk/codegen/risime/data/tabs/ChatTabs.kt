package lk.codegen.risime.data.tabs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.ChatPrefEntity
import lk.codegen.risime.data.db.ChatTabDao
import lk.codegen.risime.data.db.ChatTabEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.net.ChatEventActions
import lk.codegen.risime.net.ChatEventData
import lk.codegen.risime.net.GroupMeta

/*
 * Contract v1.24 §24: two tabs per chat (Private | Official). A chat's id is its Private (anchor)
 * conversation id; its Official conversation is a `grp:` whose MLS `group_meta` says `tab: official`
 * with that `chat_id`. Which tab a conversation is in comes from the MLS state only (§24.1): server
 * JSON (Group.tab, group_event tab fields) is never trusted for it.
 */

/** The two tabs. */
enum class Tab(val wire: String) {
    PRIVATE(ChatTabEntity.TAB_PRIVATE),
    OFFICIAL(ChatTabEntity.TAB_OFFICIAL),
    ;

    companion object {
        fun of(s: String?): Tab? = entries.firstOrNull { it.wire == s }
    }
}

/** `chat_prefs.official_state` values (the `Chat.official.state` of §24.1). */
object OfficialState {
    const val ON = "on"
    const val OFF = "off"
    const val NONE = "none"
}

/**
 * §24.1: the chat and tab of [conversationId] as its MLS state says. A `dm:` is always its own
 * chat's Private tab. A `grp:` is Official only when its `group_meta.tab` is `official`; absent or
 * anything else is Private with its own id as the chat id (a Private group never belongs to another
 * chat). Null: the MLS state isn't known yet (nothing is recorded; the conversation counts as
 * Private, so its plaintext never reaches an agent path).
 */
fun tabFromMls(conversationId: String, meta: GroupMeta?): ChatTabEntity? {
    if (conversationId.startsWith("dm:")) return ChatTabEntity(conversationId, conversationId, ChatTabEntity.TAB_PRIVATE, ChatTabEntity.KIND_DM)
    meta ?: return null
    if (!meta.official) return ChatTabEntity(conversationId, conversationId, ChatTabEntity.TAB_PRIVATE, ChatTabEntity.KIND_GROUP)
    val chatId = meta.chatId?.takeIf { it.isNotBlank() } ?: conversationId
    val kind = if (chatId.startsWith("dm:")) ChatTabEntity.KIND_DM else ChatTabEntity.KIND_GROUP
    return ChatTabEntity(conversationId, chatId, ChatTabEntity.TAB_OFFICIAL, kind)
}

/** §24.1: Private unless the MLS-derived row says Official (unknown = Private). */
fun isPrivate(conversationId: String, rows: Map<String, ChatTabEntity>?): Boolean = rows?.get(conversationId)?.official != true

/** The chat a conversation belongs to (a conversation without an MLS-derived row is its own chat). */
fun chatIdOf(conversationId: String, rows: Map<String, ChatTabEntity>?): String = rows?.get(conversationId)?.chatId ?: conversationId

/** The chat's Official conversation, if this device holds one (MLS-derived). */
fun officialConversationOf(chatId: String, rows: Map<String, ChatTabEntity>?): String? =
    rows?.values?.firstOrNull { it.official && it.chatId.equals(chatId, true) }?.conversationId

/**
 * §24.9 the tab a chat opens on: the last one used ([ChatPrefEntity.lastTab]); a chat with no
 * choice yet opens on Private (a new group's creation records Official, §24.2). Official is offered
 * only while it isn't off (the tab bar shows Private only while off, §24.4).
 */
fun defaultTab(pref: ChatPrefEntity?): Tab {
    val last = Tab.of(pref?.lastTab) ?: return Tab.PRIVATE
    if (last == Tab.OFFICIAL && pref?.officialState == OfficialState.OFF) return Tab.PRIVATE
    return last
}

/** §24.9 per-tab unread badges for one chat. */
data class TabUnread(val private: Int, val official: Int) {
    val total: Int get() = private + official
}

fun tabUnread(chatId: String, officialConversation: String?, unreadByConversation: Map<String, Int>): TabUnread =
    TabUnread(unreadByConversation[chatId] ?: 0, officialConversation?.let { unreadByConversation[it] } ?: 0)

/** §24.8 the system line text of an Official toggle ("Kamal turned Official off"). */
fun officialToggleText(action: String, actorName: String): String? = when (action) {
    ChatEventActions.OFFICIAL_OFF -> "$actorName turned Official off"
    ChatEventActions.OFFICIAL_ON -> "$actorName turned Official on"
    else -> null
}

/**
 * The tab state of this device: the MLS-derived rows (cached for synchronous readers: the locked
 * chats check, notifications), the per-chat prefs, the server switch (`/auth/config` `tabs`) and
 * whether this device advertises `tabs` now. The UI shows tabs only while [uiOn].
 */
class ChatTabs(
    private val dao: ChatTabDao,
    scope: CoroutineScope,
    /** The last `/auth/config` `tabs` value, kept across restarts (no tab bar flicker at start). */
    private val persistedServerOn: Boolean = false,
    private val persistServerOn: (Boolean) -> Unit = {},
    private val log: (String) -> Unit = {},
    /** Chats whose Official banner was already shown (§24.9 "a banner" on the first open), kept across restarts. */
    persistedBannerSeen: Set<String> = emptySet(),
    private val persistBannerSeen: (Set<String>) -> Unit = {},
) {
    private val _rows = MutableStateFlow<Map<String, ChatTabEntity>?>(null)

    /** MLS-derived rows by conversation id (lowercase keys); null until read from Room. */
    val rows: StateFlow<Map<String, ChatTabEntity>?> = _rows.asStateFlow()

    private val _prefs = MutableStateFlow<Map<String, ChatPrefEntity>>(emptyMap())
    val prefs: StateFlow<Map<String, ChatPrefEntity>> = _prefs.asStateFlow()

    private val _serverOn = MutableStateFlow(persistedServerOn)

    /** `/auth/config` says `tabs: on` (absent = off, §24.15). */
    val serverOn: StateFlow<Boolean> = _serverOn.asStateFlow()

    private val _advertised = MutableStateFlow(false)

    /** This device's last MLS registration advertised `tabs`. */
    val advertised: StateFlow<Boolean> = _advertised.asStateFlow()

    /** §24.9: the tab bar and every tab UI only while the server says on and this device advertises `tabs`. */
    val uiOn: StateFlow<Boolean> = combine(_serverOn, _advertised) { s, a -> s && a }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _hints = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Groups the server lists as Official (conversation → chat id) whose MLS state this device doesn't
     * hold yet: hidden from the chat list until the Welcome tells (never shown as a separate chat, and
     * never shown as Official on the server's word alone).
     */
    val pendingOfficial: StateFlow<Map<String, String>> = _hints.asStateFlow()

    private val _starting = MutableStateFlow<Set<String>>(emptySet())

    /** Chats whose `POST …/official` was accepted here and whose Official conversation isn't readable yet ("Starting Official…"). */
    val starting: StateFlow<Set<String>> = _starting.asStateFlow()

    fun markStarting(chatId: String) = _starting.update { it + chatId.lowercase() }

    private val _notice = MutableStateFlow<Pair<String, String>?>(null)

    /** A one-off line for a chat's screen (chat id → text), e.g. a new group's Official refused with `not_ready`. */
    val notice: StateFlow<Pair<String, String>?> = _notice.asStateFlow()

    fun setNotice(chatId: String, text: String) {
        _notice.value = chatId.lowercase() to text
    }

    fun clearNotice() {
        _notice.value = null
    }

    private val _bannerSeen = MutableStateFlow(persistedBannerSeen.map { it.lowercase() }.toSet())

    /** §24.9: chats whose Official banner was shown once already (it shows on the first open of Official only). */
    val bannerSeen: StateFlow<Set<String>> = _bannerSeen.asStateFlow()

    fun markBannerSeen(chatId: String) {
        val key = chatId.lowercase()
        if (key in _bannerSeen.value) return
        _bannerSeen.update { it + key }
        persistBannerSeen(_bannerSeen.value)
    }

    init {
        scope.launch { dao.all().collect { list -> _rows.value = list.associateBy { it.conversationId.lowercase() } } }
        scope.launch { dao.prefs().collect { list -> _prefs.value = list.associateBy { it.chatId.lowercase() } } }
    }

    fun setServerOn(on: Boolean) {
        if (_serverOn.value != on) {
            log("tabs: server switch ${if (on) "on" else "off"}")
            _serverOn.value = on
            persistServerOn(on)
        }
    }

    /** A registration answered in this process (it wins over [restoreAdvertised]). */
    @Volatile private var advertisedKnown = false

    fun setAdvertised(on: Boolean) {
        advertisedKnown = true
        _advertised.value = on
    }

    /**
     * Process start: this device's last successful MLS registration (same device id) advertised `tabs`,
     * and the server still holds it until the next `PUT`, so the tab bar shows at once instead of
     * waiting for the new registration (which may be slow or fail: no tabs at all until it answered —
     * the real-phone "no tab row" report). A registration in this process always wins.
     */
    fun restoreAdvertised(on: Boolean) {
        if (advertisedKnown) return
        if (on) log("tabs: restored the last advertisement (tabs)")
        _advertised.value = on
    }

    /** Synchronous: the chat of [conversationId]; null while the rows aren't read yet. */
    fun chatIdOrNull(conversationId: String): String? = _rows.value?.let { chatIdOf(conversationId.lowercase(), it).lowercase() }

    fun chatId(conversationId: String): String = chatIdOf(conversationId.lowercase(), _rows.value)

    fun officialOf(chatId: String): String? = officialConversationOf(chatId, _rows.value)

    /** §24.1: Private unless MLS says Official. */
    fun private(conversationId: String): Boolean = isPrivate(conversationId.lowercase(), _rows.value)

    /** After this device read a conversation's MLS `group_meta` (Welcome, commit, event, refresh): record its chat and tab. */
    suspend fun recordMls(conversationId: String, meta: GroupMeta?) {
        val t = tabFromMls(conversationId, meta) ?: return
        val existing = dao.get(conversationId)
        if (existing == t) return
        // §24.1 rule 1: tab and chat id never change after epoch 0; an Official row never turns Private.
        if (existing != null && existing.official && !t.official) {
            log("tabs: $conversationId MLS state reads Private after Official: kept Official")
            return
        }
        dao.upsert(t)
        _hints.update { it - conversationId.lowercase() }
        if (t.official) _starting.update { it - t.chatId.lowercase() }
        _rows.update { m -> m?.plus(conversationId.lowercase() to t) }
    }

    /** Server JSON says Official (Group.tab, group_event): only a hint to hide it until MLS tells. */
    fun noteServerTab(conversationId: String, tab: String?, chatId: String?) {
        val key = conversationId.lowercase()
        if (tab != ChatTabEntity.TAB_OFFICIAL || chatId == null) return
        if (_rows.value?.containsKey(key) == true) return
        _hints.update { it + (key to chatId) }
    }

    suspend fun setLastTab(chatId: String, tab: Tab) {
        dao.setLastTab(chatId, tab.wire)
        _prefs.update { m -> m + (chatId.lowercase() to ((m[chatId.lowercase()] ?: ChatPrefEntity(chatId, null, null)).copy(lastTab = tab.wire))) }
    }

    suspend fun setOfficialState(chatId: String, state: String) {
        dao.setOfficialState(chatId, state)
        _prefs.update { m -> m + (chatId.lowercase() to ((m[chatId.lowercase()] ?: ChatPrefEntity(chatId, null, null)).copy(officialState = state))) }
    }

    /**
     * §24.8 a `chat_event`, inside the event's transaction: the Official state, and for off/on a system
     * line in both tabs (the Official one only if this device holds that conversation).
     */
    suspend fun applyChatEvent(eventId: String, e: ChatEventData, me: String, nameOf: suspend (String) -> String, insert: suspend (MessageEntity) -> Unit, now: Long) {
        val state = when (e.action) {
            ChatEventActions.OFFICIAL_CREATED, ChatEventActions.OFFICIAL_ON -> OfficialState.ON
            ChatEventActions.OFFICIAL_OFF -> OfficialState.OFF
            else -> return // unknown action: ignored (§0)
        }
        setOfficialState(e.chatId, state)
        val actor = e.actor ?: return
        val text = officialToggleText(e.action, if (actor.equals(me, true)) "You" else nameOf(actor)) ?: return
        val official = e.officialConversationId?.takeIf { c -> _rows.value?.get(c.lowercase())?.let { it.official && it.chatId.equals(e.chatId, true) } == true }
        listOfNotNull(e.chatId, official).forEach { conv ->
            insert(
                MessageEntity(
                    clientMsgId = "sys:$eventId:$conv",
                    messageId = null,
                    conversationId = conv,
                    from = actor,
                    to = conv,
                    body = text,
                    serverTs = e.serverTs,
                    localTs = now,
                    status = MessageStatus.READ.name, // never unread, acked or notified
                    outgoing = false,
                    kind = MessageEntity.KIND_SYSTEM,
                    systemJson = SystemLine(e.action, actor).encode(),
                ),
            )
        }
    }
}
