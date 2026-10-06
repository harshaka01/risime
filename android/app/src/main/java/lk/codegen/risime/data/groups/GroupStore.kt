package lk.codegen.risime.data.groups

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
        GroupEvent.METADATA_CHANGED -> if (line.name != null) "$actor changed the group name to “${line.name}”" else "$actor changed the group name"
        GroupEvent.ADD_EXPIRED -> "Couldn't add ${list(line.targets)}"
        GroupEvent.RESET -> "Encryption was reset; some messages may be missing"
        SystemLine.HISTORY_GAP -> SystemLine.HISTORY_GAP_TEXT
        SystemLine.UNDECRYPTABLE -> SystemLine.UNDECRYPTABLE_TEXT
        else -> "The group changed"
    }
}

/** The kinds of group-op outbox rows (GroupOpEntity.type). */
object GroupOpType {
    const val CREATE = "create"
    const val ADD = "add"
    const val REMOVE = "remove"
    const val LEAVE = "leave"
    const val ROLE = "role"
    const val RENAME = "rename"

    /** A server PendingOp naming this device as committer (group_op, or found by GET /groups/{id}). */
    const val COMMIT = "commit"
    const val REJOIN = "rejoin"
    const val RESET = "reset"

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
) {
    fun observeGroups() = groups.all()

    /** @return true if the event changed anything visible. */
    suspend fun applyEvent(eventId: String, e: GroupEvent, me: String, restoredLocalTs: Long? = null): Boolean {
        val conv = e.groupId
        val now = clock()
        val existing = groups.get(conv)
        val meta = metaOf(conv)
        val mine = e.targets.any { it.equals(me, true) }
        var line: SystemLine? = SystemLine(e.action, e.actor, e.targets, e.role)
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
                line = line?.copy(name = meta?.name)
                g = g.copy(metaUpdatedAt = now)
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
                onReset(conv, e.generation)
                // The `rebuild` op reaches its rebuilder as group_op (and via GET /groups after a restart).
                needsRefresh(conv)
            }
            else -> line = null // unknown action: ignore (§10.0)
        }
        groups.upsert(g)
        // §13.3 R5: a replayed line sits at its server time, not under "Today".
        line?.let { insertSystemLine(eventId, conv, it, me, restoredLocalTs ?: now) }
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
        messages.setGroupReceipt(row.messageId ?: r.messageId, r.delivered, r.read, r.of, current.advance(target).name)
    }

    /** A Welcome joined or a commit changed the group: make sure the row exists and the name is current. */
    suspend fun onGroupStateChanged(conv: String, removedSelf: Boolean) {
        val existing = groups.get(conv)
        val meta = metaOf(conv)
        if (existing == null) {
            if (removedSelf) return
            groups.upsert(GroupEntity(conv, meta?.name, GroupMember.ROLE_MEMBER, GroupEntity.STATE_ACTIVE, null, null, 1, null, null, null, clock()))
            needsRefresh(conv)
            return
        }
        var g = existing
        if (meta != null && meta.name != existing.name) g = g.copy(name = meta.name, metaUpdatedAt = clock())
        if (removedSelf && !existing.readOnly) g = g.copy(state = GroupEntity.STATE_REMOVED)
        if (g != existing) groups.upsert(g)
    }

    /**
     * `GET /groups/{id}` (or a create/add reply): server truth for state, roles and members; pending
     * ops naming this device are queued (R5: owed work found after a restart).
     */
    suspend fun applyServerGroup(group: Group, me: String) {
        val conv = group.id
        val existing = groups.get(conv)
        val meta = metaOf(conv)
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

/** A group's display name: the local `group_meta` name, else "New group" until the Welcome is processed. */
fun groupDisplayName(name: String?): String = name?.takeIf { it.isNotBlank() } ?: "New group"

/** `messages.system_json` → line; null for normal messages. */
fun MessageEntity.systemLine(): SystemLine? = if (system) SystemLine.decode(systemJson) else null
