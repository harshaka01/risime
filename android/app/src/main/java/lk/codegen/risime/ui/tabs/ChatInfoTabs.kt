package lk.codegen.risime.ui.tabs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lk.codegen.risime.data.db.ChatPrefEntity
import lk.codegen.risime.data.db.GroupMemberEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.ChatTabs
import lk.codegen.risime.data.tabs.OfficialState
import lk.codegen.risime.data.tabs.officialConversationOf
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.TabsErrors

/*
 * §24.4/§24.9 chat info for a chat with two tabs: the per-chat Official switch, the read-only Official
 * history entry, the Official member list (Risi with an "AI agent" badge, never offered actions) and
 * the media of both tabs in separate sections. Nothing here ever shows in the Private member list.
 */

const val OFFICIAL_SWITCH_LABEL = "Official"
const val OFFICIAL_SWITCH_ON_TEXT = "Risi is in Official and helps with follow-ups."
const val OFFICIAL_SWITCH_OFF_TEXT = "Official is off. Its history stays read-only."
const val OFFICIAL_OFF_CONFIRM_TITLE = "Turn Official off?"
const val OFFICIAL_OFF_CONFIRM_TEXT = "Risi will leave and delete what it learned in this chat. The Official history stays read-only."
const val OFFICIAL_OFF_CONFIRM_BUTTON = "Turn off"
const val ONLY_ADMINS_OFF_TEXT = "Only admins can turn Official off"
const val ONLY_ADMINS_ON_TEXT = "Only admins can turn Official on"
const val OFFICIAL_MEMBERS_HEADER = "Official members"
const val AI_AGENT_BADGE = "AI agent"
const val PRIVATE_MEDIA_HEADER = "🔒 Private media"
const val OFFICIAL_MEDIA_HEADER = "● Official media"
const val NO_MEDIA_TEXT = "No photos"

/** `PATCH /chats/{chat_id}` (the app: [lk.codegen.risime.AppContainer.setOfficial]; a fake in tests). */
fun interface OfficialToggler {
    suspend fun set(chatId: String, on: Boolean): ApiResult<lk.codegen.risime.net.Chat>
}

/**
 * §24.4 who may toggle: in a 1:1 either person, in a group admins only. The server's `can_toggle`
 * (from `GET /chats/{id}`) wins when known; until then the local role decides.
 */
fun canToggleOfficial(chatId: String, iAmAdmin: Boolean, serverCanToggle: Boolean?): Boolean =
    serverCanToggle ?: (chatId.startsWith("dm:") || iAmAdmin)

/** The switch position: on unless the chat's Official is off (unknown and `none` read as on, §24.6). */
fun officialSwitchOn(pref: ChatPrefEntity?): Boolean = pref?.officialState != OfficialState.OFF

/** The user-facing text for a refused `PATCH /chats/{id}` (null code: offline). */
fun officialToggleErrorText(code: String?, turningOn: Boolean): String = when (code) {
    AuthErrors.NOT_ADMIN -> if (turningOn) ONLY_ADMINS_ON_TEXT else ONLY_ADMINS_OFF_TEXT
    AuthErrors.RATE_LIMITED -> "Official was changed too often today. Try again tomorrow."
    TabsErrors.AGENT_UNAVAILABLE -> "Risi isn't available right now. Try again later."
    AuthErrors.NOT_FRIENDS -> "You can only use Official with a friend."
    null -> "You're offline. Try again when you're connected."
    else -> "Couldn't change Official ($code)."
}

/** One row of the Official member list. [agent]: Risi (`kind: "agent"` from the server or the attested leaf): a badge, never actions. */
data class OfficialMemberUi(val userId: String, val name: String, val agent: Boolean, val me: Boolean)

/**
 * §24.0/§24.9 the Official member list: the humans, then the agent(s) with the "AI agent" badge.
 * [agentUsers] is the MLS view (attested kinds); a server `kind: "agent"` counts as well.
 */
fun officialMembersUi(members: List<GroupMemberEntity>, agentUsers: Set<String>, me: String): List<OfficialMemberUi> {
    val agents = agentUsers.map { it.lowercase() }.toSet()
    return members.filter { it.current }.map { m ->
        val agent = m.kind == GroupMember.KIND_AGENT || m.userId.lowercase() in agents
        OfficialMemberUi(m.userId, if (agent && m.displayName.isBlank()) "Risi" else m.displayName, agent, m.userId.equals(me, true))
    }.sortedWith(compareBy<OfficialMemberUi> { it.agent }.thenByDescending { it.me }.thenBy { it.name.lowercase() })
}

/** What the Official part of chat info shows. */
data class OfficialInfoUi(
    /** The switch position. */
    val on: Boolean = true,
    val canToggle: Boolean = false,
    /** Shown under a disabled switch ("Only admins can turn Official off"). */
    val disabledReason: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** "Turn Official off?" is asked. */
    val confirmingOff: Boolean = false,
    /** The chat's Official conversation on this phone (null: never created or not readable yet). */
    val officialConversation: String? = null,
    /** "Official history (read-only)": Official is off and this phone holds its conversation. */
    val historyAvailable: Boolean = false,
    val officialMembers: List<OfficialMemberUi> = emptyList(),
)

/** Both tabs' photos for chat info (§24.9: separate sections), newest first. */
data class TabMedia(val private: List<MessageEntity> = emptyList(), val official: List<MessageEntity> = emptyList())

/**
 * §24.4 the per-chat Official switch. Turning off asks first ([OFFICIAL_OFF_CONFIRM_TEXT]); turning
 * on doesn't. Off never deletes anything on this phone (hard rule 9): the Official conversation
 * stays, read-only, behind "Official history (read-only)".
 */
class OfficialSwitchController(
    val chatId: String,
    private val tabs: ChatTabs,
    private val toggler: OfficialToggler,
    private val scope: CoroutineScope,
    /** I'm an admin of the chat's Private group (always false for a 1:1, where it doesn't matter). */
    iAmAdmin: Flow<Boolean> = flowOf(false),
    /** The Official conversation's members (Room, from the server's member list). */
    officialMembers: (String) -> Flow<List<GroupMemberEntity>> = { flowOf(emptyList()) },
    /** The MLS view of the agent users of a conversation (attested kinds). */
    private val agentUsers: (String) -> Set<String> = { emptySet() },
    private val me: String = "",
) {
    private val key = chatId.lowercase()
    private val serverCanToggle = MutableStateFlow<Boolean?>(null)
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val confirming = MutableStateFlow(false)

    private val officialConv: StateFlow<String?> = tabs.rows.map { rows -> officialConversationOf(chatId, rows) }
        .stateIn(scope, SharingStarted.Eagerly, officialConversationOf(chatId, tabs.rows.value))

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val members: Flow<List<GroupMemberEntity>> = officialConv.flatMapLatest { conv -> if (conv == null) flowOf(emptyList()) else officialMembers(conv) }

    val ui: StateFlow<OfficialInfoUi> = combine(
        combine(tabs.prefs, officialConv, ::Pair),
        combine(iAmAdmin, serverCanToggle, ::Pair),
        combine(busy, error, confirming, ::Triple),
        members,
    ) { (prefs, conv), (admin, server), (b, e, conf), ms ->
        val pref = prefs[key]
        val on = officialSwitchOn(pref)
        val can = canToggleOfficial(chatId, admin, server)
        OfficialInfoUi(
            on = on,
            canToggle = can,
            disabledReason = if (can) null else if (on) ONLY_ADMINS_OFF_TEXT else ONLY_ADMINS_ON_TEXT,
            busy = b,
            error = e,
            confirmingOff = conf,
            officialConversation = conv,
            historyAvailable = !on && conv != null,
            officialMembers = if (conv == null) emptyList() else officialMembersUi(ms, agentUsers(conv), me),
        )
    }.stateIn(scope, SharingStarted.Eagerly, OfficialInfoUi())

    /** `GET /chats/{id}`'s `can_toggle` (null: not known). */
    fun setServerCanToggle(can: Boolean?) {
        serverCanToggle.value = can
    }

    /** The switch was flipped to [on]. Off asks first; a disabled switch does nothing. */
    fun request(on: Boolean) {
        val u = ui.value
        if (!u.canToggle || u.busy || on == u.on) return
        if (!on) confirming.value = true else send(true)
    }

    fun confirmOff() {
        confirming.value = false
        send(false)
    }

    fun cancelOff() {
        confirming.value = false
    }

    fun dismissError() {
        error.value = null
    }

    private fun send(on: Boolean) {
        busy.value = true
        error.value = null
        scope.launch {
            val r = runCatching { toggler.set(chatId, on) }.getOrNull()
            when (r) {
                is ApiResult.Ok -> {
                    tabs.setOfficialState(chatId, r.value.official.state)
                    serverCanToggle.value = r.value.canToggle
                }
                is ApiResult.Error -> error.value = officialToggleErrorText(r.code, on)
                else -> error.value = officialToggleErrorText(null, on)
            }
            busy.value = false
        }
    }
}

