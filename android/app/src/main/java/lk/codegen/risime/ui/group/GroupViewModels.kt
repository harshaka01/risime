package lk.codegen.risime.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.IcuGraphemes
import lk.codegen.risime.data.TypingSender
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.db.GroupMemberEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.groups.CreatePayload
import lk.codegen.risime.data.groups.GroupOpType
import lk.codegen.risime.data.groups.RenamePayload
import lk.codegen.risime.data.groups.RolePayload
import lk.codegen.risime.data.groups.UsersPayload
import lk.codegen.risime.data.groups.groupDisplayName
import lk.codegen.risime.data.groups.groupOpErrorText
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.ProtocolJson
import java.util.UUID

fun pickFriends(contacts: List<ContactEntity>, exclude: Set<String> = emptySet()): List<PickFriend> =
    contacts.filter { it.friend && it.userId != null && it.userId.lowercase() !in exclude }
        .map { PickFriend(it.userId!!, it.displayName, it.company, it.groupReady) }

class CreateGroupViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(CreateGroupUi())
    val ui: StateFlow<CreateGroupUi> = combine(_ui, c.contacts.contacts) { u, contacts -> u.copy(friends = pickFriends(contacts)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CreateGroupUi())

    /** The new conversation id once its epoch-0 commit landed (the screen opens the chat). */
    val created = MutableStateFlow<String?>(null)

    fun onQuery(q: String) = _ui.update { it.copy(query = q) }

    fun onToggle(id: String) = _ui.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id, error = null) }

    fun onNext() = _ui.update { it.copy(step = 1, nameError = groupNameError(it.name, IcuGraphemes)) }

    /** Back from the name step returns to the picker; from the picker, leaves (false). */
    fun onBackStep(): Boolean {
        if (_ui.value.step == 1 && !_ui.value.busy) {
            _ui.update { it.copy(step = 0) }
            return true
        }
        return false
    }

    fun onName(n: String) = _ui.update { it.copy(name = n, nameError = groupNameError(n, IcuGraphemes), error = null) }

    /** Queues the create op (persisted: survives process death) and waits for it. */
    fun onCreate() {
        val u = _ui.value
        if (!u.canCreate) return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val payload = ProtocolJson.encodeToString(CreatePayload.serializer(), CreatePayload(u.name.trim(), u.selected.toList()))
            val id = c.groupStore.queueLocal(null, GroupOpType.CREATE, payload, clientGroupId = UUID.randomUUID().toString())
            val done = c.db.groupOps().observe(id).filterNotNull().first { it.state != GroupOpType.QUEUED || it.conversationId != null && it.attempts > 2 }
            when (done.state) {
                GroupOpType.DONE -> {
                    // §24.2: a new group gets its Official conversation at once (tabs on only).
                    done.conversationId?.let { c.startOfficialForNewGroup(it) }
                    created.value = done.conversationId
                }
                GroupOpType.FAILED -> _ui.update { it.copy(busy = false, error = groupOpErrorText(done.lastError)) }
                // Still retrying in the background (offline): open it; it shows "Creating…" until it lands.
                else -> created.value = done.conversationId
            }
        }
    }
}

/** A member as the info screen shows it. */
data class MemberUi(
    val userId: String,
    val name: String,
    val admin: Boolean,
    /** active | pending_add | pending_remove */
    val state: String,
    val me: Boolean,
    val joinedAt: String?,
    /** §12.4a: a `devices` op is still adding this member's new phone ("<name>'s new phone is being added"). */
    val newPhone: Boolean = false,
)

/**
 * §12.4a: the other members whose new phone (a device the group doesn't have yet) waits in a
 * pending `devices` op. A same-device re-add (rejoin) isn't a new phone; my own waiting device
 * shows "Rejoining group…" on its own group screen instead.
 */
fun newPhoneUsers(g: lk.codegen.risime.net.Group, me: String): Set<String> =
    g.pending.filter { it.type == lk.codegen.risime.net.PendingOp.DEVICES }.flatMap { op ->
        val removed = op.removed.map { it.deviceId.lowercase() }.toSet()
        op.added.filter { it.deviceId.lowercase() !in removed && !it.userId.equals(me, true) }.map { it.userId.lowercase() }
    }.toSet()

data class GroupInfoUi(
    val name: String = "",
    val members: List<MemberUi> = emptyList(),
    val iAmAdmin: Boolean = false,
    val readOnly: Boolean = false,
    val stateLine: String? = null,
    val addCandidates: List<PickFriend> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
    /** §14.1 `missing_images`: members whose app can't show photos yet. */
    val photosNeedUpdate: List<String> = emptyList(),
    /** §20.1: members (or my other phone) who need to update to join group calls. */
    val callsNeedUpdate: List<String> = emptyList(),
    /** §18.7 the group photo's key (the conversation id) and whether one is set. */
    val conversationId: String? = null,
    val hasPhoto: Boolean = false,
    /** §18.7 "Setting the group photo…" while it is encoded and uploaded. */
    val photoBusy: String? = null,
    /** §12.12.3 (A6): this phone waits to be re-added, so the manual reset is held back (the server would say 409). */
    val resetBlocked: Boolean = false,
) {
    val memberCount: Int get() = members.count { it.state != GroupMember.STATE_PENDING_ADD }

    /** §12.3 last_admin: the UI suggests the longest-standing other member. */
    val suggestedAdmin: MemberUi?
        get() = members.filter { !it.me && !it.admin && it.state == GroupMember.STATE_ACTIVE }.minByOrNull { it.joinedAt ?: "9999" }
}

fun groupInfoUi(meId: String, g: GroupEntity?, members: List<GroupMemberEntity>, contacts: List<ContactEntity>): GroupInfoUi {
    // §24.0: the chat's (Private) member list never shows an agent; Risi is listed only under Official members.
    val current = members.filter { it.current && it.kind != GroupMember.KIND_AGENT }
    val ms = current.map { MemberUi(it.userId, it.displayName, it.role == GroupMember.ROLE_ADMIN, it.state, it.userId.equals(meId, true), it.joinedAt) }
        .sortedWith(compareByDescending<MemberUi> { it.me }.thenByDescending { it.admin }.thenBy { it.name.lowercase() })
    val readOnly = g?.readOnly == true
    return GroupInfoUi(
        name = groupDisplayName(g?.name),
        members = if (readOnly) ms.filter { !it.me } else ms,
        iAmAdmin = g?.myRole == GroupMember.ROLE_ADMIN && !readOnly,
        readOnly = readOnly,
        stateLine = when (g?.state) {
            GroupEntity.STATE_LEFT -> "You left this group"
            GroupEntity.STATE_REMOVED -> "You were removed from this group"
            GroupEntity.STATE_CREATING -> "Creating…"
            else -> null
        },
        addCandidates = pickFriends(contacts, current.map { it.userId.lowercase() }.toSet()),
    )
}

private data class PhotoState(val error: String?, val missing: List<String>, val phones: Set<String>, val photo: Boolean, val busy: String?)

class GroupInfoViewModel(private val c: AppContainer, private val meId: String, val conversationId: String) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)
    private val missingImages = MutableStateFlow<List<String>>(emptyList())
    private val missingGroupCalls = MutableStateFlow<List<String>>(emptyList())
    private val newPhones = MutableStateFlow<Set<String>>(emptySet())
    private val photoBusy = MutableStateFlow<String?>(null)
    private val hasPhoto = c.db.profilePhotos().observeAll().map { l -> l.any { it.userId == conversationId.lowercase() && it.blobId != null } }

    val ui: StateFlow<GroupInfoUi> = combine(
        uiBase(),
        c.groupStore.rejoinWaits,
    ) { u, waits -> u.copy(resetBlocked = conversationId in waits) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupInfoUi())

    private fun uiBase() = combine(
        c.db.groups().observe(conversationId),
        c.db.groups().observeMembers(conversationId),
        c.contacts.contacts,
        combine(error, missingImages, newPhones, combine(hasPhoto, photoBusy, ::Pair), missingGroupCalls) { e, m, n, p, gc -> PhotoState(e, m, n, p.first, p.second) to gc },
    ) { g, members, contacts, (photoState, missingCalls) ->
        val (err, missing, phones, photo, busyText) = photoState
        val names = missing.distinctBy { it.lowercase() }.map { id ->
            if (id.equals(meId, true)) "Your other phone" else members.firstOrNull { it.userId.equals(id, true) }?.displayName ?: "Someone"
        }
        val base = groupInfoUi(meId, g, members, contacts)
        val callNames = missingCalls.distinctBy { it.lowercase() }.map { id ->
            if (id.equals(meId, true)) "Your other phone" else members.firstOrNull { it.userId.equals(id, true) }?.displayName ?: "Someone"
        }
        base.copy(
            callsNeedUpdate = callNames.takeIf { lk.codegen.risime.BuildConfig.GROUP_CALLS_ENABLED }.orEmpty(),
            error = err, photosNeedUpdate = names, conversationId = conversationId, hasPhoto = photo, photoBusy = busyText,
            members = base.members.map { m -> if (m.userId.lowercase() in phones && m.state == GroupMember.STATE_ACTIVE) m.copy(newPhone = true) else m },
        )
    }

    init {
        // §12.4a: device-only commits emit no group_event, so re-read while a new phone is waiting.
        viewModelScope.launch {
            while (true) {
                val g = c.refreshGroup(conversationId) ?: break
                newPhones.value = newPhoneUsers(g, meId)
                if (newPhones.value.isEmpty()) break
                kotlinx.coroutines.delay(15_000)
            }
        }
        if (c.mediaCrypto != null) viewModelScope.launch {
            (c.api.mlsGroup(conversationId) as? ApiResult.Ok)?.let { r ->
                missingGroupCalls.value = r.value.missingGroupCalls.map { it.userId }
                missingImages.value = r.value.missingImages.map { it.userId }
            }
        }
    }

    /** Queue an op and report its failure here (success shows through the group's own state). */
    private fun queue(type: String, payload: String = "{}") {
        error.value = null
        viewModelScope.launch {
            val id = c.groupStore.queueLocal(conversationId, type, payload)
            val done = c.db.groupOps().observe(id).filterNotNull().first { it.state != GroupOpType.QUEUED }
            if (done.state == GroupOpType.FAILED) {
                error.value = if (done.lastError == AuthErrors.LAST_ADMIN) {
                    val s = ui.value.suggestedAdmin
                    groupOpErrorText(done.lastError) + (s?.let { " Make ${it.name} an admin, then leave." } ?: "")
                } else {
                    groupOpErrorText(done.lastError)
                }
            }
        }
    }

    fun add(userIds: Collection<String>) = queue(GroupOpType.ADD, ProtocolJson.encodeToString(UsersPayload.serializer(), UsersPayload(userIds.toList())))

    fun remove(userId: String) = queue(GroupOpType.REMOVE, ProtocolJson.encodeToString(UsersPayload.serializer(), UsersPayload(listOf(userId))))

    fun setRole(userId: String, admin: Boolean) =
        queue(GroupOpType.ROLE, ProtocolJson.encodeToString(RolePayload.serializer(), RolePayload(userId, if (admin) GroupMember.ROLE_ADMIN else GroupMember.ROLE_MEMBER)))

    fun rename(name: String) {
        groupNameError(name, IcuGraphemes)?.let { error.value = it; return }
        queue(GroupOpType.RENAME, ProtocolJson.encodeToString(RenamePayload.serializer(), RenamePayload(name.trim())))
    }

    /**
     * §18.7 an admin sets or changes the group photo: re-encode the crop (JPEG, no metadata, ≤ 512 px),
     * encrypt under a fresh key, upload as `purpose=icon`, then the `meta_changed` commit (an op).
     */
    fun setPhoto(source: ByteArray, crop: lk.codegen.risime.data.profile.CropSquare) {
        error.value = null
        photoBusy.value = "Setting the group photo…"
        viewModelScope.launch {
            try {
                val (ref, why) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    try {
                        val jpeg = lk.codegen.risime.data.profile.AvatarEncoder(lk.codegen.risime.data.media.AndroidBitmapOps).encode(source, crop)
                        c.profilePhotos.prepareGroupIcon(conversationId, jpeg.bytes, jpeg.side)
                    } catch (e: lk.codegen.risime.data.media.ImageRejected) {
                        null to (e.message ?: "Couldn't use this photo")
                    }
                }
                if (ref == null) {
                    error.value = why
                    return@launch
                }
                queue(GroupOpType.ICON, ProtocolJson.encodeToString(lk.codegen.risime.data.groups.IconPayload.serializer(), lk.codegen.risime.data.groups.IconPayload(c.sealGroupIcon(conversationId, ref.toJson()))))
            } finally {
                photoBusy.value = null
            }
        }
    }

    /** §18.7 remove = `icon: null` in the same commit. */
    fun removePhoto() = queue(GroupOpType.ICON, ProtocolJson.encodeToString(lk.codegen.risime.data.groups.IconPayload.serializer(), lk.codegen.risime.data.groups.IconPayload(null)))

    fun photoError(text: String) {
        error.value = text
    }

    /** Leaving is immediate locally (read-only chat); undone if the server says last_admin. */
    fun leave() {
        viewModelScope.launch {
            c.db.groups().get(conversationId)?.let { c.db.groups().upsert(it.copy(state = GroupEntity.STATE_LEFT)) }
            queue(GroupOpType.LEAVE)
        }
    }

    /**
     * §12.8/§12.12.3 an admin's confirmed "Reset encryption": rebuilds the whole group's encryption.
     * Refused (`409 rejoin_pending`) while this phone is still being re-added.
     */
    fun reset() {
        if (ui.value.resetBlocked) {
            error.value = GroupOpType.RESET_REJOIN_PENDING_TEXT
            return
        }
        queue(GroupOpType.RESET)
    }

    fun dismissError() {
        error.value = null
    }
}

/** The group chat's composer: on, or off with the reason shown in its place (never a silent failure). */
sealed interface GroupComposer {
    data object Enabled : GroupComposer

    data class Disabled(val reason: String) : GroupComposer
}

/**
 * [encrypted] = this device holds the group's MLS state (null = not checked yet). v1.21 §12.12.3:
 * an active group without it is waiting for its rejoin Welcome; the composer stays on and sends
 * stay pending in the outbox until the Welcome is applied ([groupE2eeStrip] says why). A group
 * still being created keeps the composer too (its sends wait for the epoch-0 commit).
 */
fun groupComposer(g: GroupEntity?, @Suppress("UNUSED_PARAMETER") encrypted: Boolean?): GroupComposer = when {
    g == null -> GroupComposer.Enabled
    g.readOnly -> GroupComposer.Disabled(if (g.state == GroupEntity.STATE_LEFT) "You left this group." else "You were removed from this group.")
    else -> GroupComposer.Enabled
}

/**
 * The strip under a group chat's header while this device has no MLS state for it (null otherwise):
 * a group being created is "Not end-to-end encrypted yet: setting up…"; an active group this phone
 * is being re-added to says "Setting up encryption on this phone…", and names the wait once the
 * rejoin reply has a candidate that hasn't committed (§12.12.3). Never a failure, never a reset.
 */
fun groupE2eeStrip(g: GroupEntity?, encrypted: Boolean?, wait: lk.codegen.risime.data.groups.RejoinWait?, now: Long): String? = when {
    g == null || g.readOnly || encrypted != false -> null
    g.state == GroupEntity.STATE_CREATING -> "${lk.codegen.risime.data.mls.NOT_E2EE_PREFIX}: setting up end-to-end encryption…"
    wait?.waitingForOthers(now) == true -> lk.codegen.risime.data.mls.GROUP_WAITING_TEXT
    else -> lk.codegen.risime.data.mls.REPAIRING_TEXT
}

class GroupChatViewModel(private val c: AppContainer, private val meId: String, val conversationId: String) : ViewModel() {
    /** The app container (the §33 picker and file card read it). */
    val container: AppContainer get() = c

    /** v1.34 §33: stars, reply, forward, Info, share (this conversation, this tab). */
    val messaging = lk.codegen.risime.ui.chat.MessagingController(c, viewModelScope, meId, conversationId)

    /** §33.13 open and save files. */
    val files = lk.codegen.risime.ui.chat.FileActions(c, viewModelScope)
    val group: StateFlow<GroupEntity?> = c.db.groups().observe(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val members: StateFlow<List<GroupMemberEntity>> = c.db.groups().observeMembers(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** §17.12 the gap marker's state (null with the feature off). */
    val historyMarker: StateFlow<lk.codegen.risime.data.history.HistoryMarkerState?> =
        if (lk.codegen.risime.BuildConfig.HISTORY_SHARE_ENABLED) {
            c.history.markerState(conversationId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        } else {
            kotlinx.coroutines.flow.MutableStateFlow(null)
        }

    fun requestHistory(sources: String) {
        c.scope.launch { c.history.request(conversationId, sources) }
    }

    fun escalateHistory() {
        c.scope.launch { c.history.escalate(conversationId) }
    }

    val messages: StateFlow<List<MessageEntity>> = c.db.messages().conversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connection = c.realtime.state

    val typingNames: StateFlow<List<String>> = combine(c.presence.groupTyping, members) { t, ms ->
        val names = ms.associate { it.userId.lowercase() to it.displayName }
        t[conversationId].orEmpty().filter { !it.equals(meId, true) }.map { names[it] ?: "Someone" }.sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val reactions: StateFlow<Map<String, List<lk.codegen.risime.data.ReactionChip>>> =
        c.db.reactions().forConversation(conversationId)
            .map { rows -> rows.groupBy { it.targetMessageId }.mapValues { (_, rs) -> lk.codegen.risime.data.chipsFor(rs, meId) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // ---- §20 group calls ----

    private val _groupCallsReady = MutableStateFlow(false)

    /** §20.1 `group_calls_ready` (refetched on open and before a call). */
    val groupCallsReady: StateFlow<Boolean> = _groupCallsReady

    /** §20.1: who still needs to update for group calls (group info; information only). */
    val missingGroupCalls = MutableStateFlow<List<String>>(emptyList())

    private val _groupCallsUnavailable = MutableStateFlow<String?>(null)

    /** v1.33 §20.1 `group_calls_unavailable` (`"server"`: no LiveKit on this server). */
    val groupCallsUnavailable: StateFlow<String?> = _groupCallsUnavailable

    fun refreshGroupCalls() {
        viewModelScope.launch {
            val r = c.api.mlsGroup(conversationId)
            if (r is lk.codegen.risime.net.ApiResult.Ok) {
                _groupCallsReady.value = r.value.e2ee && r.value.groupCallsReady
                _groupCallsUnavailable.value = r.value.groupCallsUnavailable
                missingGroupCalls.value = r.value.missingGroupCalls.map { it.userId }.distinct()
            }
        }
    }

    /** Null = the call buttons work; otherwise what a tap explains (§20.1 UI). */
    fun groupCallBlockedText(encrypted: Boolean, ready: Boolean, video: Boolean): String? = groupCallBlockedText(
        thisPhone = runCatching { c.calls.canAdvertiseGroupCalls() }.getOrDefault(false),
        unavailable = _groupCallsUnavailable.value, encrypted = encrypted, ready = ready,
    )

    fun startGroupCall(video: Boolean, camera: Boolean) {
        c.calls.placeCall(conversationId, video = video, camera = camera)
        c.calls.openCallScreen()
    }

    /** §20.4 Join on a running call's line. */
    fun joinGroupCall(env: lk.codegen.risime.calls.GroupCallEnvelope, starter: String, camera: Boolean) {
        c.calls.joinGroupCall(conversationId, env.callId, env.media, starter, camera)
        c.calls.openCallScreen()
    }

    /** §20.4: on chat open, `status` for every `started` line under 4 h old with no `ended`. */
    private fun checkRunningLines() {
        viewModelScope.launch {
            val rows = c.db.messages().conversation(conversationId).first()
            val now = System.currentTimeMillis()
            for (m in rows) {
                if (!m.call) continue
                val env = lk.codegen.risime.calls.GroupCallEnvelope.decode(m.systemJson) ?: continue
                if (env.state != lk.codegen.risime.calls.GroupCallEnvelope.STARTED || lk.codegen.risime.calls.GroupCallLines.over(m.systemJson)) continue
                if (now - m.localTs > lk.codegen.risime.calls.GroupCallLines.RUNNING_CHECK_MS) {
                    c.engine.markGroupCallOver(conversationId, env.callId)
                    continue
                }
                runCatching { c.calls.groupCallRunning(conversationId, env.callId, env.media) }
            }
        }
    }

    init {
        refreshGroupCalls()
        checkRunningLines()
    }

    /** The local MLS group exists (null until first checked; false: rejoining / setting up until the Welcome). */
    private val _encrypted = MutableStateFlow<Boolean?>(null)
    val encrypted: StateFlow<Boolean?> = _encrypted

    /** The composer, or why it's off (left/removed). */
    val composer: StateFlow<GroupComposer> = combine(group, _encrypted) { g, e -> groupComposer(g, e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupComposer.Enabled)

    /** Re-evaluates the strip's time-based wording while waiting. */
    private val tick = MutableStateFlow(0L)

    /** §12.12.3 the waiting strip ([groupE2eeStrip]). */
    val e2eeStrip: StateFlow<String?> = combine(group, _encrypted, c.groupStore.rejoinWaits, tick) { g, e, w, _ ->
        groupE2eeStrip(g, e, w[conversationId], System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val typingSender = TypingSender(viewModelScope, System::currentTimeMillis, { typing ->
        c.scope.launch { c.realtime.typing(conversationId, typing) }
    })

    /** §15.7 deletes (message actions behind DeleteFeature.sendEnabled; admins delete any message, §15.4). */
    val del = lk.codegen.risime.ui.chat.DeleteController(c, viewModelScope, meId, conversationId, iAmAdmin = { group.value?.myRole == lk.codegen.risime.net.GroupMember.ROLE_ADMIN })
        .also { it.refreshReady() }

    /** §14: photos in this group. */
    val imgs = lk.codegen.risime.ui.chat.ImageActions(c, viewModelScope, meId, conversationId)

    init {
        c.openConversation.value = conversationId
        c.notifier.cancelChat(conversationId)
        viewModelScope.launch { c.refreshGroup(conversationId) }
        imgs.refreshImagesReady()
        viewModelScope.launch {
            // Re-checked on new rows, on group row changes and whenever a Welcome/commit changed the MLS state.
            combine(messages, group, c.groupStore.stateChanges) { _, _, _ -> }.collect {
                _encrypted.value = withContext(Dispatchers.IO) { c.mlsEngine?.group(conversationId) != null }
            }
        }
        viewModelScope.launch {
            // §12.12.2: while this phone waits to be re-added, rejoin on open and every 15 minutes while
            // the chat is open (sync rejoins on reconnect); the reply decides the strip's wording.
            _encrypted.filterNotNull().first()
            var lastAsk: Long? = null
            while (true) {
                val g = c.db.groups().get(conversationId)
                val now = System.currentTimeMillis()
                if (_encrypted.value == false && g?.state == GroupEntity.STATE_ACTIVE) {
                    if (lastAsk == null || now - lastAsk >= lk.codegen.risime.data.mls.RejoinRules.REFRESH_MS) {
                        lastAsk = now
                        c.groupStore.requestRejoin(conversationId)
                    }
                } else {
                    lastAsk = null
                }
                tick.value += 1
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    fun nameOf(userId: String): String = when {
        userId.equals(meId, true) -> "You"
        else -> members.value.firstOrNull { it.userId.equals(userId, true) }?.displayName
            ?: knownPeople.value[userId.lowercase()] ?: "Former member"
    }

    /**
     * §27.3 names of people outside this conversation from the phone's own data (friends, members of other
     * chats): the Risi chat's summaries name the other participants ("…with Shenika").
     */
    private val knownPeople: StateFlow<Map<String, String>> = combine(c.db.contacts().all(), c.db.groups().observeAllMembers()) { ct, ms ->
        val out = HashMap<String, String>()
        ms.forEach { m -> if (m.displayName.isNotBlank()) out[m.userId.lowercase()] = m.displayName }
        ct.forEach { x -> x.userId?.let { out[it.lowercase()] = x.displayName } }
        out as Map<String, String>
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun onDraftChanged(text: String) {
        if (group.value?.readOnly != true) typingSender.onInput(text)
    }

    /**
     * §24.11 Risi in this conversation: requests/actions go out only while it is an Official conversation
     * (checked at the source, so a Private chat can't send one); feedback is private REST.
     */
    val risi: lk.codegen.risime.ui.tabs.RisiHost = object : lk.codegen.risime.ui.tabs.RisiHost {
        private val requests = lk.codegen.risime.data.tabs.RisiRequests(
            isOfficial = { c.db.chatTabs().get(conversationId)?.official == true },
            send = { c.engine.sendRisiControl(conversationId, it) },
        )

        override val me: String get() = meId

        override fun ask(text: String) { viewModelScope.launch { requests.ask(text) } }

        // v1.34 §33.15 the `pdf` chip: the PDF is made on this phone (a source not on it: "This isn't on this phone").
        override fun exportPdf(source: kotlinx.serialization.json.JsonObject) = c.requestPdf(source)

        override suspend fun localEvents(fromMs: Long, toMs: Long): lk.codegen.risime.data.tabs.LocalEventsResult =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                lk.codegen.risime.data.tabs.LocalEvents.build(fromMs, toMs, phone = { c.phoneCalendar.localEvents(fromMs, toMs) }, risi = { c.risiCalendar.eventsNow() })
            }

        override fun summarise() { viewModelScope.launch { requests.summarise() } }

        override fun report() { viewModelScope.launch { requests.report() } }

        override fun summarisePeriod(period: String) { viewModelScope.launch { requests.summarisePeriod(period) } }

        override fun summariseRange(fromMs: Long, toMs: Long) { viewModelScope.launch { requests.summariseRange(fromMs, toMs) } }

        override fun act(target: String, action: String, editText: String?, editDue: String?) {
            viewModelScope.launch { requests.act(target, action, editText, editDue) }
        }

        override fun feedback(callRef: String, rating: String, reason: String?) {
            c.scope.launch { runCatching { c.risiRest.feedback(callRef, rating, reason) } }
        }

        override val undone: Set<String> get() = undoneEntries.value

        override fun undo(skillId: String, entryId: String, token: String) {
            undoneEntries.value = undoneEntries.value + entryId.lowercase()
            c.scope.launch {
                val r = c.api.undoRisiSkillEntry(skillId, entryId, token)
                imgs.toast.value = lk.codegen.risime.ui.settings.undoResultText(r)
                if (r !is ApiResult.Ok) undoneEntries.value = undoneEntries.value - entryId.lowercase()
            }
        }

        override fun openSkills(skillId: String?) = c.risiUi.openSkills(skillId)

        override fun openNotificationSettings() {
            c.startExternal(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, c.packageNameForLinks))
        }

        override fun useDraft(conversationId: String, text: String) = c.risiUi.useDraft(conversationId, text)

        override fun hasConversation(conversationId: String): Boolean = conversationNames.value.containsKey(conversationId.lowercase())

        override fun conversationName(conversationId: String): String? = conversationNames.value[conversationId.lowercase()]

        override fun scheduleMayBeLate(): Boolean = !c.backgroundSkillPermissions.exactAlarmsAllowed()

        override val calendar: lk.codegen.risime.data.tabs.RisiCalendarPort get() = c.calendarPort

        // §29: only on a risi_events device (else the cards render from their own data, without buttons).
        override val risiCalendar: lk.codegen.risime.data.calendar.RisiCalendarCardsPort? get() = if (c.risiEventsOn()) c.risiCalendarCards else null

        override fun confirmEdited(writeId: String, edit: kotlinx.serialization.json.JsonObject) {
            viewModelScope.launch { requests.confirmEdited(writeId, edit) }
        }

        override fun actItem(itemId: String, action: String, text: String?, due: String?, allDay: Boolean) {
            viewModelScope.launch { requests.act(itemId, action, text, due, allDay) }
        }

        override fun openChat(conversationId: String, atIso: String?) =
            c.risiUi.openChat(conversationId, atIso?.let { lk.codegen.risime.data.tabs.RisiUiBus.Focus(at = it) })

        override fun openRisiChat(summaryId: String) {
            val conv = risiChatId() ?: return
            c.risiUi.openChat(conv, lk.codegen.risime.data.tabs.RisiUiBus.Focus(summaryId = summaryId))
        }

        override fun risiChatAvailable(): Boolean = c.risiLedgerOn() && risiChatId() != null

        override val notesOn: Boolean get() = c.risiNotesOn()

        override val myName: String get() = myDisplayName.value ?: "You"

        override fun openNote(noteId: String) = c.risiUi.openNote(noteId)

        // §31.5 / §31.7 (the Google phone shows real names and Google busy blocks; elsewhere the counts and a caption).
        override val gcalNames: Map<String, String> get() = if (c.googleCalendarOn()) c.gcalNames.value else emptyMap()

        override suspend fun googleTimeline(startMs: Long, zone: java.time.ZoneId) =
            if (!c.googleCalendarOn()) null else c.gcal.timelineFor(
                startMs, zone, c.risiSkillsStore.localState(lk.codegen.risime.net.RisiSkillIds.CALENDAR) == lk.codegen.risime.net.RisiSkillStates.OFF,
                runCatching { c.sessionStore.deviceId() }.getOrNull(),
            )

        override fun openNotes() = c.risiUi.openNotes()

        private fun risiChatId(): String? = c.chatTabs.rows.value?.values?.firstOrNull { it.risi }?.conversationId
    }

    /** §30.3 my own display name for note titles (from the session). */
    private val myDisplayName = MutableStateFlow<String?>(null).also { f ->
        viewModelScope.launch { f.value = runCatching { c.sessionStore.current()?.user?.displayName }.getOrNull()?.takeIf { it.isNotBlank() } }
    }

    /** §30 the Notes entries (Risi chat ⋮ → Notes) follow the switch and the advertisement. */
    val notesActive: StateFlow<Boolean> get() = c.risiNotesActive

    /** §27.4 a usable Risi chat with the Ledger on ([Open Risi chat] on the short card). */
    val risiChatReady: StateFlow<Boolean> = combine(c.chatTabs.rows, c.risiLedger.on, c.risiTools.on) { rows, ledger, tools ->
        ledger && tools && rows?.values?.any { it.risi } == true
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** §27.3/§27.4 where this chat should scroll when it opens from a Risi card (taken once). */
    fun takeFocus(): lk.codegen.risime.data.tabs.RisiUiBus.Focus? = c.risiUi.takeFocus(conversationId)

    /** Entries undone from this screen (their [Undo] goes; a failure brings it back). */
    private val undoneEntries = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val undoneState: StateFlow<Set<String>> = undoneEntries

    /** Local conversation names for draft targets and scheduled-message recipients. */
    val conversationNames: StateFlow<Map<String, String>> = combine(c.db.groups().all(), c.db.contacts().all(), c.chatTabs.rows) { g, ct, rows ->
        lk.codegen.risime.data.tabs.ConversationDirectory.names(meId, g, ct, rows)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** §25.4 Risi's progress bubbles for requests made in this conversation. */
    val risiProgress: StateFlow<List<lk.codegen.risime.data.tabs.RisiProgressStore.Shown>> = combine(c.risiProgress.byRequest, messages) { m, rows ->
        lk.codegen.risime.data.tabs.RisiProgressStore.visible(m.values, rows, conversationId)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** §25.4 a draft's [Use] opened this chat: the composer starts with it (taken once). */
    fun takeDraft(): String? = c.risiUi.takeDraft(conversationId)

    /** §26.6 this chat's scheduled messages (the sender's phone only). */
    val scheduledCtl = lk.codegen.risime.ui.chat.ScheduledControls(
        c.scheduled, c.db.scheduled().open(), c.db.scheduled().sends(), viewModelScope, conversationId,
        afterSend = { c.flushOutboxAfterUpload() },
    )

    /** "Scheduled messages" in the chat ⋮: on a risi_skills phone, or while this chat has any. */
    fun scheduledMenu(): Boolean = c.risiSkillsOn()

    fun send(text: String) {
        typingSender.stop()
        // §33.9 the composer's quote bar (if any) goes with this message.
        val reply = messaging.takeReply()
        viewModelScope.launch { c.engine.sendText(conversationId, text, replyTo = reply) }
    }

    fun react(targetMessageId: String, emoji: String, op: String) {
        c.scope.launch { c.engine.react(conversationId, targetMessageId, emoji, op) }
    }

    private fun isImage(id: String) = messages.value.firstOrNull { it.clientMsgId == id }?.media == true

    fun retry(clientMsgId: String) {
        if (isImage(clientMsgId)) return imgs.retry(clientMsgId)
        c.scope.launch { c.engine.retry(clientMsgId) }
    }

    fun delete(clientMsgId: String) {
        if (isImage(clientMsgId)) return imgs.delete(clientMsgId)
        c.scope.launch { c.engine.deleteFailed(clientMsgId) }
    }

    fun markRead() {
        c.scope.launch { c.engine.markConversationRead(conversationId) }
    }

    /** "Read by" for my message: fetched on open, never stored (R10). */
    private val _readBy = MutableStateFlow<Pair<String, ReadByState>?>(null)
    val readBy: StateFlow<Pair<String, ReadByState>?> = _readBy

    fun openReadBy(messageId: String) {
        val last = (_readBy.value?.takeIf { it.first == messageId }?.second as? ReadByState.Loaded)?.reply
        _readBy.value = messageId to (last?.let { ReadByState.Loaded(it) } ?: ReadByState.Loading)
        viewModelScope.launch {
            _readBy.value = messageId to when (val r = c.api.groupReceipts(conversationId, messageId)) {
                is ApiResult.Ok -> ReadByState.Loaded(r.value)
                // §33.12: expired from retention.
                is ApiResult.Error -> ReadByState.Error(if (r.httpStatus == 404) lk.codegen.risime.ui.chat.MessagingStrings.RECEIPTS_GONE else "Couldn't load (${r.code})")
                // Offline: the last loaded list with "Couldn't refresh · Retry".
                is ApiResult.NetworkError -> last?.let { ReadByState.Stale(it) } ?: ReadByState.Error("Offline — try again")
            }
        }
    }

    /** §33.12 agent members are never listed in Info. */
    fun isAgent(userId: String): Boolean = members.value.any { it.userId.equals(userId, true) && it.kind == lk.codegen.risime.net.GroupMember.KIND_AGENT }

    init {
        // §33.12 `group_receipt` events refresh an open Info live.
        viewModelScope.launch {
            messages.collect { rows ->
                val open = _readBy.value ?: return@collect
                if (open.second is ReadByState.Loaded && rows.any { it.messageId == open.first }) {
                    val r = c.api.groupReceipts(conversationId, open.first) as? ApiResult.Ok ?: return@collect
                    if (_readBy.value?.first == open.first) _readBy.value = open.first to ReadByState.Loaded(r.value)
                }
            }
        }
    }

    fun closeReadBy() {
        _readBy.value = null
    }

    override fun onCleared() {
        typingSender.stop()
        c.openConversation.compareAndSet(conversationId, null)
    }
}

/**
 * §20.1 the group call buttons' rule order: this phone first, then (v1.33) the server without LiveKit
 * (`group_calls_unavailable: "server"`), then e2ee, then the members' readiness. Null = the buttons work.
 */
fun groupCallBlockedText(thisPhone: Boolean, unavailable: String?, encrypted: Boolean, ready: Boolean): String? = when {
    !thisPhone -> lk.codegen.risime.calls.CallTexts.GROUP_UPDATE_TEXT
    unavailable != null -> lk.codegen.risime.calls.CallTexts.GROUP_SERVER_UNAVAILABLE_TEXT
    !encrypted -> "Calls need an end-to-end encrypted chat."
    !ready -> lk.codegen.risime.calls.CallTexts.GROUP_NOT_READY_TEXT
    else -> null
}
