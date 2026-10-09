package lk.codegen.risime.data.groups

import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.GroupDao
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.db.GroupMemberEntity
import lk.codegen.risime.data.db.GroupOpDao
import lk.codegen.risime.data.db.GroupOpEntity
import lk.codegen.risime.data.db.MessageDao
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.Group
import lk.codegen.risime.net.GroupEvent
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.net.GroupOpEvent
import lk.codegen.risime.net.GroupReceiptEvent
import lk.codegen.risime.net.ProtocolJson

/** What a system line says (stored as `messages.system_json`; rendered with live names). */
@Serializable
data class SystemLine(
    val action: String,
    val actor: String,
    val targets: List<String> = emptyList(),
    val role: String? = null,
    /** metadata_changed / created: the group name at that time (from the local group_meta). */
    val name: String? = null,
) {
    fun encode(): String = ProtocolJson.encodeToString(serializer(), this)

    companion object {
        /** §13.3 local lines (never on the wire). */
        const val HISTORY_GAP = "history_gap"
        const val UNDECRYPTABLE = "undecryptable"
        const val HISTORY_GAP_TEXT = "Earlier messages aren't available on this device"
        const val UNDECRYPTABLE_TEXT = "Some messages couldn't be decrypted"

        /** §17.12 local lines: the block header of imported history; the gap marker after a partial import. */
        const val HISTORY_SHARED = "history_shared"
        const val HISTORY_GAP_SOME_TEXT = "Some earlier messages aren't available on this device"
        const val HISTORY_GAP_RESIDUAL_TEXT = "Some earlier messages couldn't be restored"
        const val HISTORY_RESTORED_TEXT = "History restored from your other device"

        fun historySharedText(name: String) = "History shared by $name"

        /** §18.7 local lines (never sent, never unread or notified). */
        const val PHOTO_CHANGED = "photo_changed"
        const val PHOTO_REMOVED = "photo_removed"

        /** Local lines whose stored text is authoritative (never re-rendered from the line). */
        val LOCAL_ACTIONS = setOf(HISTORY_GAP, UNDECRYPTABLE, HISTORY_SHARED)

        fun decode(json: String?): SystemLine? = json?.let { runCatching { ProtocolJson.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * The text of a system line. [nameOf] gives a member's display name; "You"/"you" for me.
 * E.g. "Kamal added Nimal and 2 others", "You left", "Kamal made you an admin".
 */
fun systemText(line: SystemLine, me: String, nameOf: (String) -> String): String {
    fun n(id: String, start: Boolean) = if (id.equals(me, true)) (if (start) "You" else "you") else nameOf(id)
    fun list(ids: List<String>): String = when (ids.size) {
        0 -> "nobody"
        1 -> n(ids[0], false)
        2 -> "${n(ids[0], false)} and ${n(ids[1], false)}"
        else -> "${n(ids[0], false)} and ${ids.size - 1} others"
    }
    val actor = n(line.actor, true)
    return when (line.action) {
        GroupEvent.CREATED -> if (line.name != null) "$actor created the group “${line.name}”" else "$actor created the group"
        GroupEvent.ADDED -> "$actor added ${list(line.targets)}"
        GroupEvent.REMOVED -> "$actor removed ${list(line.targets)}"
        GroupEvent.LEFT -> "${n(line.targets.firstOrNull() ?: line.actor, true)} left"
        GroupEvent.ROLE_CHANGED -> if (line.role == GroupMember.ROLE_ADMIN) {
            "$actor made ${list(line.targets)} an admin"
        } else {
            "$actor dismissed ${list(line.targets)} as admin"
        }
        SystemLine.PHOTO_CHANGED -> "$actor changed the group photo"
        SystemLine.PHOTO_REMOVED -> "$actor removed the group photo"
        GroupEvent.METADATA_CHANGED -> if (line.name != null) "$actor changed the group name to “${line.name}”" else "$actor changed the group name"
        GroupEvent.ADD_EXPIRED -> "Couldn't add ${list(line.targets)}"
        GroupEvent.RESET -> "Encryption was reset; some messages may be missing"
        lk.codegen.risime.net.ChatEventActions.OFFICIAL_OFF -> "$actor turned Official off"
        lk.codegen.risime.net.ChatEventActions.OFFICIAL_ON -> "$actor turned Official on"
        SystemLine.HISTORY_GAP -> SystemLine.HISTORY_GAP_TEXT
        SystemLine.UNDECRYPTABLE -> SystemLine.UNDECRYPTABLE_TEXT
        else -> "The group changed"
    }
}

/** §18.7 the identity of a group's icon for its lines: the blob's SHA-256, "" for none. */
fun iconSha(meta: GroupMeta?): String = lk.codegen.risime.data.profile.PhotoRef.groupIcon(meta?.icon)?.blob?.sha256 ?: ""

/** A `rejoin`/`reset` outbox row's payload: [ifMissing] = skip it if this device has the group by then. */
@Serializable
data class RejoinPayload(@kotlinx.serialization.SerialName("if_missing") val ifMissing: Boolean = false)

/**
 * §12.8 for a group the server lists: what this device must do when it holds no MLS state for the
 * group's current generation (a reinstall, or a sign-in after a logout that starts a new MLS state):
 * always [GroupOpType.REJOIN]. The named committer (the user's other device, an admin, or under
 * §12.4a any member) re-adds this device, and it joins from the Welcome.
 * v1.21 (§12.12.1, decision 060): admin is a user role, so a reinstalled admin, the only admin
 * included, is re-added like anyone else; it never resets here. A reset is decided only after the
 * rejoin reply ([lk.codegen.risime.data.mls.RejoinRules.group]).
 * Null when nothing is owed: the device holds the generation, the group is still creating or
 * waiting for a rebuild (epoch null), or I'm not an active member.
 */
fun rejoinPlan(g: Group, me: String, localGeneration: Long?): String? {
    if (g.state != Group.STATE_ACTIVE || g.epoch == null) return null
    val mine = g.members.firstOrNull { it.userId.equals(me, true) } ?: return null
    if (mine.state != GroupMember.STATE_ACTIVE) return null
    if (localGeneration != null && localGeneration >= g.generation) return null
    return GroupOpType.REJOIN
}

/**
 * §12.12.3 what this device knows of its own pending re-add (from the last rejoin reply), for the
 * waiting strip and to hold back the manual reset. In memory only: a restart asks again.
 */
data class RejoinWait(
    val candidates: Int?,
    val exhausted: Boolean,
    val committerNamed: Boolean,
    val opCreatedAtMs: Long?,
    /** When this device first got a reply while waiting. */
    val since: Long,
) {
    fun waitingForOthers(now: Long): Boolean =
        lk.codegen.risime.data.mls.RejoinRules.waitingForOthers(candidates, committerNamed, now - since)
}

/** The kinds of group-op outbox rows (GroupOpEntity.type). */
object GroupOpType {
    const val CREATE = "create"

    /** §24.2 create (or finish) a chat's Official conversation. */
    const val CREATE_OFFICIAL = "create_official"

    /** §25.2 create (or finish) the user's Risi chat. */
    const val CREATE_RISI_CHAT = "create_risi_chat"
    const val ADD = "add"
    const val REMOVE = "remove"
    const val LEAVE = "leave"
    const val ROLE = "role"
    const val RENAME = "rename"

    /** §18.7 the group photo (an admin's `meta_changed` commit). */
    const val ICON = "icon"

    /** A server PendingOp naming this device as committer (group_op, or found by GET /groups/{id}). */
    const val COMMIT = "commit"
    const val REJOIN = "rejoin"
    const val RESET = "reset"

    /** A manual reset the server refused (§12.12.3 `409 rejoin_pending`): this phone is still being re-added. */
    const val RESET_REJOIN_PENDING_TEXT = "This phone is still being re-added to the group. Try again later."

    /** §12.8 [rejoinPlan]: wait this long before calling rejoin, so a Welcome already on its way can land first. */
    const val REJOIN_GRACE_MS = 8_000L

    const val QUEUED = "queued"
    const val DONE = "done"
    const val FAILED = "failed"
}

/**
 * Applies the group side of the inbox (§12.7) to Room: `group_event` → groups/group_members plus
 * system lines; `group_op` naming this device → the op outbox; `group_receipt` → ticks. Runs inside
 * ChatEngine's per-event transaction. Server roles are shown (S6); the name comes from the local
 * `group_meta` ([metaOf]).
 */
class GroupStore(
    private val groups: GroupDao,
    private val ops: GroupOpDao,
    private val messages: MessageDao,
    private val myDeviceId: suspend () -> String,
    private val metaOf: (conversationId: String) -> GroupMeta? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Members are known only by id (added, or a Welcome before any event): refresh with GET /groups/{id}. */
    private val needsRefresh: (conversationId: String) -> Unit = {},
    /** S4: "<actor> added you to <name>" (notification + already woken by push). */
    private val onAddedMe: (conversationId: String, actor: String) -> Unit = { _, _ -> },
    /** A new op in the outbox (kick the executor). */
    private val onOpQueued: () -> Unit = {},
    /** §12.8 reset: drop the old-generation MLS group (and parked events). */
    private val onReset: suspend (conversationId: String, generation: Long) -> Unit = { _, _ -> },
    /** §24.1: the group's MLS `group_meta` was read (null = not known yet): record its chat and tab. */
    private val onMlsMeta: suspend (conversationId: String, meta: GroupMeta?) -> Unit = { _, _ -> },
    /** §24.1: the server's word on the tab (a hint only, never trusted for Private-ness). */
    private val onServerTab: (conversationId: String, tab: String?, chatId: String?) -> Unit = { _, _, _ -> },
) {
    fun observeGroups() = groups.all()

    private val _stateChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Bumped when this device's MLS state of a group changed (Welcome joined, commit applied, removed). */
    val stateChanges: kotlinx.coroutines.flow.StateFlow<Long> = _stateChanges

    private val _rejoinWaits = kotlinx.coroutines.flow.MutableStateFlow<Map<String, RejoinWait>>(emptyMap())

    /** §12.12.3 groups this device is waiting to be re-added to (from the last rejoin reply). */
    val rejoinWaits: kotlinx.coroutines.flow.StateFlow<Map<String, RejoinWait>> = _rejoinWaits

    /** A rejoin reply for [conv]: this device waits for its re-add (the first reply's time is kept). */
    fun noteRejoinWait(conv: String, r: lk.codegen.risime.net.GroupRejoinReply) {
        _rejoinWaits.update { m ->
            m + (conv to RejoinWait(
                r.candidates, r.exhausted, r.op?.committer != null,
                lk.codegen.risime.data.mls.RejoinRules.epochMs(r.op?.createdAt), m[conv]?.since ?: clock(),
            ))
        }
    }

    fun clearRejoinWait(conv: String) {
        if (conv in _rejoinWaits.value) _rejoinWaits.update { it - conv }
    }

    /**
     * §12.8 automatic rejoin (after sign-in, on every sync): for each listed group this device holds
     * no state for, queue a rejoin once, after [delayMs] (v1.21: never a reset). The op skips
     * itself if the Welcome arrived meanwhile; the server's rejoin is idempotent. Never blocks.
     * @return the conversations queued now.
     */
    suspend fun queueRejoins(server: List<Group>, me: String, localGeneration: (String) -> Long?, delayMs: Long = GroupOpType.REJOIN_GRACE_MS): List<String> {
        val owed = ops.queued().filter { it.type == GroupOpType.REJOIN || it.type == GroupOpType.RESET }.mapNotNull { it.conversationId }.toSet()
        val now = clock()
        val payload = ProtocolJson.encodeToString(RejoinPayload.serializer(), RejoinPayload(ifMissing = true))
        val queued = server.mapNotNull { g ->
            val plan = rejoinPlan(g, me, localGeneration(g.id)) ?: return@mapNotNull null
            if (g.id in owed) return@mapNotNull null
            ops.insert(GroupOpEntity(conversationId = g.id, type = plan, payloadJson = payload, state = GroupOpType.QUEUED, createdAt = now, nextAt = now + delayMs))
            g.id
        }
        if (queued.isNotEmpty()) onOpQueued()
        return queued
    }

    /**
     * §12.12.2 refresh a waiting group's rejoin reply (chat open, and every 15 min while it's open):
     * one automatic rejoin, unless one is already queued ([except]: the op asking, still queued). @return true if queued now.
     */
    suspend fun requestRejoin(conv: String, except: Long? = null): Boolean {
        if (ops.queued().any { it.id != except && it.conversationId == conv && (it.type == GroupOpType.REJOIN || it.type == GroupOpType.RESET) }) return false
        val payload = ProtocolJson.encodeToString(RejoinPayload.serializer(), RejoinPayload(ifMissing = true))
        val now = clock()
        ops.insert(GroupOpEntity(conversationId = conv, type = GroupOpType.REJOIN, payloadJson = payload, state = GroupOpType.QUEUED, createdAt = now, nextAt = now))
        onOpQueued()
        return true
    }

    /** @return true if the event changed anything visible. */
    suspend fun applyEvent(eventId: String, e: GroupEvent, me: String, restoredLocalTs: Long? = null, suppressLine: Boolean = false): Boolean {
        val conv = e.groupId
        val now = clock()
        val existing = groups.get(conv)
        val meta = metaOf(conv)
        onServerTab(conv, e.tab, e.chatId)
        onMlsMeta(conv, meta)
        val mine = e.targets.any { it.equals(me, true) }
        var line: SystemLine? = SystemLine(e.action, e.actor, e.targets, e.role)
        var photoLine: SystemLine? = null
        val base = existing ?: GroupEntity(
            conv, meta?.name, GroupMember.ROLE_MEMBER, GroupEntity.STATE_ACTIVE, null, null, e.generation,
            null, null, null, now,
        )
        var g = base.copy(epochSeen = e.epoch ?: base.epochSeen, name = meta?.name ?: base.name)
        when (e.action) {
            GroupEvent.CREATED -> {
                val members = e.members.orEmpty()
                upsertMembers(conv, members)
                val myRole = members.firstOrNull { it.userId.equals(me, true) }?.role ?: g.myRole
                g = g.copy(state = GroupEntity.STATE_ACTIVE, myRole = myRole, createdBy = e.actor, generation = e.generation)
                line = line?.copy(name = meta?.name)
                if (meta != null) g = g.copy(iconSha = iconSha(meta), announcedName = meta.name)
                if (!e.actor.equals(me, true) && existing == null) onAddedMe(conv, e.actor)
            }
            GroupEvent.ADDED -> {
                ensureMembers(conv, e.targets, GroupMember.STATE_ACTIVE)
                if (mine) {
                    g = g.copy(state = GroupEntity.STATE_ACTIVE)
                    if (existing == null || existing.readOnly) onAddedMe(conv, e.actor)
                }
                needsRefresh(conv)
            }
            GroupEvent.REMOVED, GroupEvent.LEFT -> {
                val state = if (e.action == GroupEvent.LEFT) GroupEntity.STATE_LEFT else GroupEntity.STATE_REMOVED
                ensureMembers(conv, e.targets, state)
                if (mine) g = g.copy(state = state, myRole = GroupMember.ROLE_MEMBER)
            }
            GroupEvent.ROLE_CHANGED -> {
                val role = e.role ?: GroupMember.ROLE_MEMBER
                ensureMembers(conv, e.targets, null)
                groups.setMemberRole(conv, e.targets, role)
                if (mine) g = g.copy(myRole = role)
            }
            GroupEvent.METADATA_CHANGED -> {
                // §18.7: compare the previous and new group_meta: a rename, a photo change, or both.
                val newIcon = iconSha(meta)
                val photoChanged = meta != null && newIcon != (existing?.iconSha ?: "")
                val renamed = when {
                    meta == null -> true
                    existing?.announcedName != null -> meta.name != existing.announcedName
                    else -> !photoChanged // before v10 nothing was recorded: a rename unless the photo changed
                }
                line = if (renamed) line?.copy(name = meta?.name) else null
                if (photoChanged) {
                    photoLine = SystemLine(if (newIcon.isEmpty()) SystemLine.PHOTO_REMOVED else SystemLine.PHOTO_CHANGED, e.actor)
                }
                g = g.copy(metaUpdatedAt = now, iconSha = if (meta != null) newIcon else g.iconSha, announcedName = meta?.name ?: g.announcedName)
            }
            GroupEvent.ADD_EXPIRED -> {
                val pending = groups.members(conv).filter { m -> e.targets.any { it.equals(m.userId, true) } && m.state == GroupMember.STATE_PENDING_ADD }
                groups.setMemberState(conv, pending.map { it.userId }, GroupEntity.STATE_REMOVED)
                if (pending.isEmpty()) line = null
            }
            GroupEvent.RESET -> {
                e.members?.let { upsertMembers(conv, it) }
                val myRole = e.members?.firstOrNull { it.userId.equals(me, true) }?.role ?: g.myRole
                g = g.copy(generation = e.generation, epochSeen = null, myRole = myRole)
                clearRejoinWait(conv) // the rebuild's Welcome replaces the re-add
                onReset(conv, e.generation)
                // The `rebuild` op reaches its rebuilder as group_op (and via GET /groups after a restart).
                needsRefresh(conv)
            }
            else -> line = null // unknown action: ignore (§10.0)
        }
        groups.upsert(g)
        // §13.3 R5: a replayed line sits at its server time, not under "Today".
        // §15.7: no line at or before a Clear chat watermark ([suppressLine]); the state above still applies.
        if (!suppressLine) line?.let { insertSystemLine(eventId, conv, it, me, restoredLocalTs ?: now) }
        if (!suppressLine) photoLine?.let { insertSystemLine("$eventId:photo", conv, it, me, restoredLocalTs ?: now) }
        return true
    }

    /** §12.4: only the device named in `op.committer` acts; others ignore the naming. */
    suspend fun applyOp(e: GroupOpEvent, me: String): Boolean {
        val c = e.op.committer ?: return false
        if (!c.userId.equals(me, true) || !c.deviceId.equals(myDeviceId(), true)) return false
        return queueCommit(e.groupId, e.op)
    }

    /** Queue a server op for this device to commit (deduped by op_id). */
    suspend fun queueCommit(conv: String, op: lk.codegen.risime.net.PendingOp): Boolean {
        val row = GroupOpEntity(
            conversationId = conv, type = GroupOpType.COMMIT, payloadJson = ProtocolJson.encodeToString(lk.codegen.risime.net.PendingOp.serializer(), op),
            state = GroupOpType.QUEUED, createdAt = clock(), opId = op.opId,
        )
        val queued = ops.insert(row) > 0
        if (queued) onOpQueued()
        return queued
    }

    /** A local intent (create/add/remove/leave/role/rename/rejoin/reset) for the op outbox. */
    suspend fun queueLocal(conv: String?, type: String, payloadJson: String = "{}", clientGroupId: String? = null): Long {
        val id = ops.insert(GroupOpEntity(conversationId = conv, type = type, payloadJson = payloadJson, state = GroupOpType.QUEUED, createdAt = clock(), clientGroupId = clientGroupId))
        onOpQueued()
        return id
    }

    /** §12.7 aggregated receipt on my own message: ✓✓ when all delivered, read when all read. */
    suspend fun applyReceipt(r: GroupReceiptEvent) {
        val row = messages.byMessageId(r.messageId) ?: r.clientMsgId?.let { messages.byClientMsgId(it) } ?: return
        if (!row.outgoing) return
        val current = MessageStatus.valueOf(row.status)
        val target = when {
            r.allRead -> MessageStatus.READ
            r.allDelivered -> MessageStatus.DELIVERED
            else -> MessageStatus.SENT
        }
        // P0-4: the receipt can beat the msg:send reply (members ack within ~100 ms), so the row may
        // have no message_id yet; matching on it alone dropped the receipt for good. Fill it in first.
        if (row.messageId == null) {
            messages.updateStatus(row.clientMsgId, row.status, r.messageId, null, row.failReason)
        }
        messages.setGroupReceipt(row.messageId ?: r.messageId, r.delivered, r.read, r.of, current.advance(target).name)
    }

    /** A Welcome joined or a commit changed the group: make sure the row exists and the name is current. */
    suspend fun onGroupStateChanged(conv: String, removedSelf: Boolean) {
        if (!removedSelf) clearRejoinWait(conv)
        _stateChanges.value += 1
        val existing = groups.get(conv)
        val meta = metaOf(conv)
        if (!removedSelf) onMlsMeta(conv, meta)
        if (existing == null) {
            if (removedSelf) return
            groups.upsert(GroupEntity(conv, meta?.name, GroupMember.ROLE_MEMBER, GroupEntity.STATE_ACTIVE, null, null, 1, null, null, null, clock()))
            needsRefresh(conv)
            return
        }
        var g = existing
        if (meta != null && meta.name != existing.name) g = g.copy(name = meta.name, metaUpdatedAt = clock())
        if (removedSelf && !existing.readOnly) g = g.copy(state = GroupEntity.STATE_REMOVED)
        // §12.8: a rejoin removes this device's old leaf and re-adds it; the Welcome brings it back.
        if (!removedSelf && existing.state == GroupEntity.STATE_REMOVED) g = g.copy(state = GroupEntity.STATE_ACTIVE)
        if (g != existing) groups.upsert(g)
        // After a rejoin: members and roles again from the server.
        if (!removedSelf && existing.name == null) needsRefresh(conv)
    }

    /**
     * `GET /groups/{id}` (or a create/add reply): server truth for state, roles and members; pending
     * ops naming this device are queued (R5: owed work found after a restart).
     */
    suspend fun applyServerGroup(group: Group, me: String) {
        val conv = group.id
        val existing = groups.get(conv)
        val meta = metaOf(conv)
        onServerTab(conv, group.tab, group.chatId)
        onMlsMeta(conv, meta)
        val state = when {
            group.state == Group.STATE_CREATING -> GroupEntity.STATE_CREATING
            existing?.state == GroupEntity.STATE_LEFT -> GroupEntity.STATE_LEFT // leave is immediate locally
            else -> GroupEntity.STATE_ACTIVE
        }
        groups.upsert(
            (existing ?: GroupEntity(conv, meta?.name, group.myRole, state, group.createdBy, group.createdAt, group.generation, group.epoch, null, null, clock()))
                .copy(
                    name = meta?.name ?: existing?.name,
                    myRole = group.myRole,
                    state = state,
                    createdBy = group.createdBy,
                    createdAt = group.createdAt,
                    generation = group.generation,
                    lastRefreshedAt = clock(),
                ),
        )
        // Members the server no longer lists have left or been removed (their rows stay for old bubbles).
        val listed = group.members.map { it.userId.lowercase() }.toSet()
        val gone = groups.members(conv).filter { it.current && it.userId.lowercase() !in listed }
        if (gone.isNotEmpty()) groups.setMemberState(conv, gone.map { it.userId }, GroupEntity.STATE_REMOVED)
        upsertMembers(conv, group.members)
        val device = myDeviceId()
        group.pending.filter { op -> op.committer?.let { it.userId.equals(me, true) && it.deviceId.equals(device, true) } == true }
            .forEach { queueCommit(conv, it) }
    }

    /** S3: `404` for a group I was in: keep the local snapshot, read-only. */
    suspend fun markGone(conv: String) {
        val g = groups.get(conv) ?: return
        if (!g.readOnly && g.state != GroupEntity.STATE_CREATING) groups.upsert(g.copy(state = GroupEntity.STATE_REMOVED))
        if (g.state == GroupEntity.STATE_CREATING) groups.delete(conv)
    }

    private suspend fun upsertMembers(conv: String, members: List<GroupMember>) {
        if (members.isEmpty()) return
        groups.upsertMembers(
            members.map { GroupMemberEntity(conv, it.userId, it.displayName, it.phone, it.role, it.kind, it.state, it.joinedAt) },
        )
    }

    /** Targets known only by id: keep a known name, else a placeholder until the refresh. */
    private suspend fun ensureMembers(conv: String, ids: List<String>, state: String?) {
        if (ids.isEmpty()) return
        val known = groups.members(conv).associateBy { it.userId.lowercase() }
        groups.upsertMembers(
            ids.map { id ->
                val k = known[id.lowercase()]
                k?.copy(state = state ?: k.state)
                    ?: GroupMemberEntity(conv, id, "Member", null, GroupMember.ROLE_MEMBER, GroupMember.KIND_USER, state ?: GroupMember.STATE_ACTIVE, null)
            },
        )
    }

    private suspend fun insertSystemLine(eventId: String, conv: String, line: SystemLine, me: String, now: Long) {
        val names = groups.members(conv).associate { it.userId.lowercase() to it.displayName }
        messages.insert(
            MessageEntity(
                clientMsgId = "sys:$eventId",
                messageId = null,
                conversationId = conv,
                from = line.actor,
                to = conv,
                body = systemText(line, me) { names[it.lowercase()] ?: "Someone" },
                serverTs = null,
                localTs = now,
                status = MessageStatus.READ.name, // never unread, never acked, never notified
                outgoing = false,
                kind = MessageEntity.KIND_SYSTEM,
                systemJson = line.encode(),
            ),
        )
    }
}

/** Shown for a group whose name this device doesn't know yet (its Welcome hasn't been processed). */
const val GROUP_NAME_PENDING = "Rejoining group…"

/**
 * A group's display name: the local `group_meta` name (kept across sign-outs that keep the chats),
 * else [GROUP_NAME_PENDING] until the Welcome is processed. Never a made-up name.
 */
fun groupDisplayName(name: String?): String = name?.takeIf { it.isNotBlank() } ?: GROUP_NAME_PENDING

/** `messages.system_json` → line; null for normal messages. */
fun MessageEntity.systemLine(): SystemLine? = if (system) SystemLine.decode(systemJson) else null
