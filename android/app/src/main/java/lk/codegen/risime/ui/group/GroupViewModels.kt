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
    val current = members.filter { it.current }
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

    fun refreshGroupCalls() {
        viewModelScope.launch {
            val r = c.api.mlsGroup(conversationId)
            if (r is lk.codegen.risime.net.ApiResult.Ok) {
                _groupCallsReady.value = r.value.e2ee && r.value.groupCallsReady
                missingGroupCalls.value = r.value.missingGroupCalls.map { it.userId }.distinct()
            }
        }
    }

    /** Null = the call buttons work; otherwise what a tap explains (§20.1 UI). */
    fun groupCallBlockedText(encrypted: Boolean, ready: Boolean, video: Boolean): String? = when {
        !runCatching { c.calls.canAdvertiseGroupCalls() }.getOrDefault(false) -> lk.codegen.risime.calls.CallTexts.GROUP_UPDATE_TEXT
        !encrypted -> "Calls need an end-to-end encrypted chat."
        !ready -> lk.codegen.risime.calls.CallTexts.GROUP_NOT_READY_TEXT
        else -> null
    }

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
        else -> members.value.firstOrNull { it.userId.equals(userId, true) }?.displayName ?: "Former member"
    }

    fun onDraftChanged(text: String) {
        if (group.value?.readOnly != true) typingSender.onInput(text)
    }

    fun send(text: String) {
        typingSender.stop()
        viewModelScope.launch { c.engine.sendText(conversationId, text) }
    }

    fun react(targetMessageId: String, emoji: String, op: String) {
        c.scope.launch { c.engine.react(conversationId, targetMessageId, emoji, op) }
    }

    private fun isImage(id: String) = messages.value.firstOrNull { it.clientMsgId == id }?.image == true

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
        _readBy.value = messageId to ReadByState.Loading
        viewModelScope.launch {
            _readBy.value = messageId to when (val r = c.api.groupReceipts(conversationId, messageId)) {
                is ApiResult.Ok -> ReadByState.Loaded(r.value)
                is ApiResult.Error -> ReadByState.Error(if (r.httpStatus == 404) "No longer available" else "Couldn't load (${r.code})")
                is ApiResult.NetworkError -> ReadByState.Error("Offline — try again")
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
