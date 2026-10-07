package lk.codegen.risime.data.groups

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.GroupDao
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.db.GroupOpDao
import lk.codegen.risime.data.db.GroupOpEntity
import lk.codegen.risime.data.mls.ClaimedKeyPackage
import lk.codegen.risime.data.mls.DeviceRef
import lk.codegen.risime.data.mls.MlsEngine
import lk.codegen.risime.data.mls.MlsPolicyException
import lk.codegen.risime.data.mls.PendingCommit
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.ClaimedDevice
import lk.codegen.risime.net.Group
import lk.codegen.risime.net.GroupCommitRequest
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.net.MlsDeviceRef
import lk.codegen.risime.net.MlsMissing
import lk.codegen.risime.net.PendingOp
import lk.codegen.risime.net.ProtocolJson
import java.util.Base64

/** The group REST the op executor needs (ApiClient in the app with X-Device-Id; a fake in tests). */
interface GroupApi {
    suspend fun create(clientGroupId: String, memberIds: List<String>): ApiResult<Group>
    suspend fun group(id: String): ApiResult<Group>
    suspend fun addMembers(id: String, userIds: List<String>): ApiResult<Group>
    suspend fun removeMember(id: String, userId: String): ApiResult<Unit>
    suspend fun leave(id: String): ApiResult<Unit>
    suspend fun setRole(id: String, userId: String, role: String): ApiResult<Group>
    suspend fun rejoin(id: String): ApiResult<Group>
    suspend fun reset(id: String, generation: Long): ApiResult<Long>

    /** §12.5: [conversationId] null = friends and yourself (§10.2), else co-members of that group. */
    suspend fun claim(userIds: List<String>, conversationId: String?): ApiResult<List<ClaimedDevice>>
    suspend fun commit(id: String, body: GroupCommitRequest): ApiResult<Long>
    suspend fun uploadBlob(conversationId: String, bytes: ByteArray): ApiResult<BlobRef>
}

@Serializable
data class CreatePayload(val name: String, @SerialName("member_ids") val memberIds: List<String>)

@Serializable
data class UsersPayload(@SerialName("user_ids") val userIds: List<String>)

@Serializable
data class RolePayload(@SerialName("user_id") val userId: String, val role: String)

@Serializable
data class RenamePayload(val name: String)

/** What one attempt at an op came to. */
sealed interface OpOutcome {
    data object Done : OpOutcome

    /** Try again after [afterMs] (network, 5xx, rate limit, lost race). */
    data class Retry(val reason: String, val afterMs: Long) : OpOutcome

    /** Final: shown to the user where it was their action (e.g. last_admin, not_ready). */
    data class Failed(val reason: String, val missing: List<MlsMissing> = emptyList()) : OpOutcome
}

/** User-facing text for a failed group action. */
fun groupOpErrorText(reason: String?): String = when (reason) {
    AuthErrors.LAST_ADMIN -> "Make another member an admin before you leave."
    AuthErrors.NOT_ADMIN -> "Only group admins can do that."
    AuthErrors.NOT_READY -> "Someone needs to update RisiMe first."
    AuthErrors.TOO_MANY_MEMBERS -> "A group can have at most 256 members."
    AuthErrors.TOO_MANY_DEVICES -> "Too many devices for one group."
    AuthErrors.NOT_FRIENDS -> "You can only add your friends."
    AuthErrors.INVALID_ROLE -> "That member can't be an admin."
    null -> "Something went wrong."
    else -> "Couldn't do that ($reason)."
}

/**
 * The group-op outbox (review chunk 6). Every user action and every server op naming this device is
 * a persisted [GroupOpEntity], so it survives process death. Commits are never replayed blindly:
 * a server op is re-derived from `GET /groups/{id}` on every attempt (R5), and after `409
 * epoch_conflict` the device catches up and builds the commit again. A commit merges only on
 * the server's `200` (§10.4).
 */
class GroupOpsExecutor(
    private val engine: () -> MlsEngine?,
    private val api: GroupApi,
    private val ops: GroupOpDao,
    private val groups: GroupDao,
    private val store: GroupStore,
    private val tx: TransactionRunner,
    private val me: suspend () -> String?,
    private val deviceId: suspend () -> String,
    private val catchUp: suspend (conversationId: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = 8,
    private val log: (String) -> Unit = {},
    /** Commits/Welcomes larger than this go by blob reference (§12.6); live interop lowers it. */
    private val inlineMaxBytes: Int = INLINE_MAX_BYTES,
) {
    private val lock = Mutex()
    private val enc = Base64.getEncoder()
    private val dec = Base64.getDecoder()

    /**
     * Runs every due op once, in order. @return the earliest time a queued op is due again (to
     * schedule the next run), or null when nothing is waiting.
     */
    suspend fun runDue(): Long? = lock.withLock {
        for (op in ops.due(clock())) {
            val outcome = runCatching { execute(op) }.getOrElse { t ->
                log("group op ${op.type} threw: ${t.javaClass.simpleName}: ${t.message}")
                OpOutcome.Retry(t.javaClass.simpleName, 30_000)
            }
            val fresh = ops.get(op.id) ?: op
            val next = when (outcome) {
                OpOutcome.Done -> fresh.copy(state = GroupOpType.DONE, lastError = null)
                is OpOutcome.Failed -> fresh.copy(state = GroupOpType.FAILED, lastError = outcome.reason)
                is OpOutcome.Retry -> {
                    val attempts = fresh.attempts + 1
                    if (attempts >= maxAttempts) {
                        fresh.copy(state = GroupOpType.FAILED, attempts = attempts, lastError = outcome.reason)
                    } else {
                        fresh.copy(attempts = attempts, nextAt = clock() + outcome.afterMs * (1L shl (attempts - 1).coerceAtMost(6)), lastError = outcome.reason)
                    }
                }
            }
            ops.update(next)
            if (next.state == GroupOpType.FAILED) onFailed(next)
        }
        ops.queued().minOfOrNull { it.nextAt }
    }

    /** A failed local action is rolled back where the UI already showed it (leave is immediate locally). */
    private suspend fun onFailed(op: GroupOpEntity) {
        log("group op ${op.type} failed: ${op.lastError}")
        val conv = op.conversationId ?: return
        when (op.type) {
            GroupOpType.LEAVE -> groups.get(conv)?.takeIf { it.state == GroupEntity.STATE_LEFT }?.let { groups.upsert(it.copy(state = GroupEntity.STATE_ACTIVE)) }
            GroupOpType.CREATE -> groups.get(conv)?.takeIf { it.state == GroupEntity.STATE_CREATING }?.let { groups.delete(conv) }
        }
    }

    private inline fun <T> net(r: ApiResult<T>, onOk: (T) -> OpOutcome): OpOutcome = when (r) {
        is ApiResult.Ok -> onOk(r.value)
        is ApiResult.Error -> errorOutcome(r)
        is ApiResult.NetworkError -> OpOutcome.Retry("network", 5_000)
    }

    private fun errorOutcome(r: ApiResult.Error): OpOutcome = when {
        r.httpStatus == 429 -> OpOutcome.Retry(AuthErrors.RATE_LIMITED, (r.retryAfterSec ?: 30) * 1000)
        r.httpStatus >= 500 -> OpOutcome.Retry(r.code, 10_000)
        r.httpStatus == 401 -> OpOutcome.Retry(r.code, 30_000)
        else -> OpOutcome.Failed(r.code, r.missing.orEmpty())
    }

    suspend fun execute(op: GroupOpEntity): OpOutcome {
        val myId = me() ?: return OpOutcome.Retry("signed out", 60_000)
        return when (op.type) {
            GroupOpType.CREATE -> create(op, myId)
            GroupOpType.ADD -> {
                val p = ProtocolJson.decodeFromString(UsersPayload.serializer(), op.payloadJson)
                net(api.addMembers(op.conversationId!!, p.userIds)) { g -> afterReply(g, myId) }
            }
            GroupOpType.REMOVE -> {
                val p = ProtocolJson.decodeFromString(UsersPayload.serializer(), op.payloadJson)
                when (val r = api.removeMember(op.conversationId!!, p.userIds.single())) {
                    is ApiResult.Ok -> refreshThenDone(op.conversationId, myId)
                    else -> net(r) { OpOutcome.Done }
                }
            }
            GroupOpType.LEAVE -> net(api.leave(op.conversationId!!)) { OpOutcome.Done }
            GroupOpType.ROLE -> {
                val p = ProtocolJson.decodeFromString(RolePayload.serializer(), op.payloadJson)
                net(api.setRole(op.conversationId!!, p.userId, p.role)) { g -> afterReply(g, myId) }
            }
            GroupOpType.RENAME -> rename(op, myId)
            GroupOpType.COMMIT -> commitServerOp(op, myId)
            GroupOpType.REJOIN -> {
                if (alreadyJoined(op)) return OpOutcome.Done
                net(api.rejoin(op.conversationId!!)) { OpOutcome.Done }
            }
            GroupOpType.RESET -> {
                if (alreadyJoined(op)) return OpOutcome.Done
                val g = groups.get(op.conversationId!!) ?: return OpOutcome.Done
                when (val r = api.reset(op.conversationId, g.generation)) {
                    is ApiResult.Error -> if (r.code == AuthErrors.GENERATION_CONFLICT) OpOutcome.Done else errorOutcome(r)
                    else -> net(r) { OpOutcome.Done } // the rebuild op reaches us as group_op
                }
            }
            else -> OpOutcome.Failed("unknown op type ${op.type}")
        }
    }

    /**
     * An automatic rejoin/reset ([RejoinPayload.ifMissing]) that's no longer needed: this device
     * holds the group's current generation (its Welcome arrived during the grace period).
     */
    private suspend fun alreadyJoined(op: GroupOpEntity): Boolean {
        val p = runCatching { ProtocolJson.decodeFromString(RejoinPayload.serializer(), op.payloadJson) }.getOrNull() ?: return false
        if (!p.ifMissing) return false
        val local = engine()?.group(op.conversationId ?: return false) ?: return false
        return local.generation >= (groups.get(op.conversationId)?.generation ?: 0)
    }

    /** A REST reply with the group: apply it (this queues any op naming this device as committer). */
    private suspend fun afterReply(g: Group, myId: String): OpOutcome {
        tx.run { store.applyServerGroup(g, myId) }
        return OpOutcome.Done
    }

    private suspend fun refreshThenDone(conv: String, myId: String): OpOutcome {
        (api.group(conv) as? ApiResult.Ok)?.value?.let { tx.run { store.applyServerGroup(it, myId) } }
        return OpOutcome.Done
    }

    // ---- create: POST /groups (idempotent by client_group_id) → claim → epoch-0 commit with group_meta ----

    private suspend fun create(op: GroupOpEntity, myId: String): OpOutcome {
        val mls = engine() ?: return OpOutcome.Retry("no MLS core", 60_000)
        val p = ProtocolJson.decodeFromString(CreatePayload.serializer(), op.payloadJson)
        val group = when (val r = api.create(op.clientGroupId!!, p.memberIds)) {
            is ApiResult.Ok -> r.value
            is ApiResult.Error -> return errorOutcome(r)
            is ApiResult.NetworkError -> return OpOutcome.Retry("network", 5_000)
        }
        val conv = group.id
        if (op.conversationId != conv) ops.update((ops.get(op.id) ?: op).copy(conversationId = conv))
        tx.run {
            store.applyServerGroup(group, myId)
            // Until the commit lands the creator sees it as "creating"; the name is known locally.
            groups.get(conv)?.let { if (it.name == null) groups.upsert(it.copy(name = p.name)) }
        }
        if (group.state != Group.STATE_CREATING) {
            // Already committed by an earlier attempt: done if we have the group, else rejoin.
            if (mls.group(conv) == null) store.queueLocal(conv, GroupOpType.REJOIN)
            return OpOutcome.Done
        }
        val claimed = when (val r = api.claim((p.memberIds + myId).distinct(), null)) {
            is ApiResult.Ok -> r.value
            is ApiResult.Error -> return errorOutcome(r)
            is ApiResult.NetworkError -> return OpOutcome.Retry("network", 5_000)
        }
        val kps = keyPackages(claimed) ?: return OpOutcome.Retry("no_key_package", 30_000)
        val meta = GroupMeta(name = p.name, admins = listOf(myId))
        val pc = tx.run {
            if (mls.group(conv) != null) mls.deleteGroup(conv) // a stale local epoch 0 from a crashed attempt
            mls.createGroupWithMeta(conv, group.generation, kps, meta)
        }
        return submit(conv, pc, opId = null, metaChanged = false, myId = myId, onConflict = {
            // epoch 0 taken: an earlier attempt of ours landed. Re-read; rejoin if we lost the state.
            OpOutcome.Retry(AuthErrors.EPOCH_CONFLICT, 1_000)
        })
    }

    /** Claimed devices → key packages; null if any device came back without one (try later). */
    private fun keyPackages(devices: List<ClaimedDevice>): List<ClaimedKeyPackage>? {
        val usable = devices.filter { it.mls && it.deviceId != null }
        if (usable.any { it.keyPackage == null }) return null
        return usable.map { ClaimedKeyPackage(DeviceRef(it.userId, it.deviceId!!), dec.decode(it.keyPackage)) }
    }

    // ---- rename: an admin's GroupContextExtensions commit with meta_changed (no server op) ----

    private suspend fun rename(op: GroupOpEntity, myId: String): OpOutcome {
        val conv = op.conversationId!!
        val mls = engine() ?: return OpOutcome.Retry("no MLS core", 60_000)
        val p = ProtocolJson.decodeFromString(RenamePayload.serializer(), op.payloadJson)
        val current = tx.run { mls.groupMeta(conv) } ?: return OpOutcome.Retry("no group yet", 10_000)
        if (current.name == p.name) return OpOutcome.Done
        val pc = runCatching { tx.run { mls.updateGroupMeta(conv, current.copy(name = p.name)) } }
            .getOrElse { return OpOutcome.Failed("policy: ${it.message}") }
        return submit(conv, pc, opId = null, metaChanged = true, myId = myId)
    }

    // ---- server ops naming this device (R4/R5): re-derived from GET /groups/{id} every time ----

    private suspend fun commitServerOp(op: GroupOpEntity, myId: String): OpOutcome {
        val conv = op.conversationId!!
        val mls = engine() ?: return OpOutcome.Retry("no MLS core", 60_000)
        val g = when (val r = api.group(conv)) {
            is ApiResult.Ok -> r.value
            is ApiResult.Error -> return if (r.httpStatus == 404) OpOutcome.Done else errorOutcome(r)
            is ApiResult.NetworkError -> return OpOutcome.Retry("network", 5_000)
        }
        tx.run { store.applyServerGroup(g, myId) }
        val pending = g.pending.firstOrNull { it.opId == op.opId } ?: return OpOutcome.Done // completed elsewhere or expired
        if (pending.type != PendingOp.REBUILD && mls.group(conv) == null) return OpOutcome.Retry("no group yet", 10_000)
        val pc: PendingCommit = try {
            when (pending.type) {
                PendingOp.ADD -> {
                    val claimed = when (val r = api.claim(pending.userIds, conv)) {
                        is ApiResult.Ok -> r.value
                        is ApiResult.Error -> return errorOutcome(r)
                        is ApiResult.NetworkError -> return OpOutcome.Retry("network", 5_000)
                    }
                    val have = mls.members(conv).map { it.deviceId.lowercase() }.toSet()
                    val kps = keyPackages(claimed.filter { it.deviceId?.lowercase() !in have }) ?: return OpOutcome.Retry("no_key_package", 30_000)
                    if (kps.isEmpty()) return OpOutcome.Retry("nothing to add yet", 30_000)
                    tx.run { mls.changeGroupMembers(conv, kps, emptyList()) }
                }
                PendingOp.REMOVE -> tx.run { mls.removeGroupUsers(conv, pending.userIds) }
                PendingOp.ROLE -> {
                    val meta = tx.run { mls.groupMeta(conv) } ?: return OpOutcome.Retry("no meta", 10_000)
                    tx.run { mls.updateGroupMeta(conv, meta.copy(admins = adminsAfterRole(g, pending))) }
                }
                PendingOp.DEVICES -> {
                    val leaves = mls.members(conv)
                    // v1.14 §12.4a: a non-admin named for another user's devices op (the member path).
                    val memberPath = g.myRole != GroupMember.ROLE_ADMIN &&
                        (pending.added + pending.removed).any { !it.userId.equals(myId, true) }
                    if (memberPath) {
                        memberPathRefusal(pending, leaves, myId)?.let { why ->
                            log("group op ${pending.opId}: not member-committable ($why)")
                            return OpOutcome.Failed("policy: $why") // reported, never retried: the server names someone else
                        }
                    }
                    val addUsers = pending.added.map { it.userId }.distinct()
                    val claimed = if (addUsers.isEmpty()) emptyList() else when (val r = api.claim(addUsers, conv)) {
                        is ApiResult.Ok -> r.value
                        is ApiResult.Error -> return errorOutcome(r)
                        is ApiResult.NetworkError -> return OpOutcome.Retry("network", 5_000)
                    }
                    val wanted = pending.added.map { it.deviceId.lowercase() }.toSet()
                    val kps = keyPackages(claimed.filter { it.deviceId?.lowercase() in wanted }) ?: return OpOutcome.Retry("no_key_package", 30_000)
                    // §12.4a: the member path must match the op's `added` exactly (the server rejects a subset).
                    if (memberPath && kps.map { it.device.deviceId.lowercase() }.toSet() != wanted) return OpOutcome.Retry("no_key_package", 30_000)
                    val present = leaves.map { it.deviceId.lowercase() }.toSet()
                    val remove = pending.removed.filter { it.deviceId.lowercase() in present }.map { DeviceRef(it.userId, it.deviceId) }
                    if (kps.isEmpty() && remove.isEmpty()) return OpOutcome.Done
                    tx.run { mls.changeGroupMembers(conv, kps, remove) }
                }
                PendingOp.REBUILD -> {
                    val members = g.members.filter { it.state == GroupMember.STATE_ACTIVE }.map { it.userId }
                    val claimed = when (val r = api.claim((members + myId).distinct(), conv)) {
                        is ApiResult.Ok -> r.value
                        is ApiResult.Error -> return errorOutcome(r)
                        is ApiResult.NetworkError -> return OpOutcome.Retry("network", 5_000)
                    }
                    // Members without a usable device are added later by a `devices` op (§12.8).
                    val kps = claimed.filter { it.mls && it.deviceId != null && it.keyPackage != null }
                        .map { ClaimedKeyPackage(DeviceRef(it.userId, it.deviceId!!), dec.decode(it.keyPackage)) }
                    val name = groups.get(conv)?.name ?: "Group"
                    val admins = g.members.filter { it.admin }.map { it.userId }
                    tx.run {
                        mls.group(conv)?.takeIf { it.generation < g.generation }?.let { mls.deleteGroup(conv) }
                        mls.createGroupWithMeta(conv, g.generation, kps, GroupMeta(name = name, admins = admins))
                    }
                }
                else -> return OpOutcome.Failed("unknown op ${pending.type}")
            }
        } catch (e: UnsupportedOperationException) {
            return OpOutcome.Failed("unsupported")
        } catch (e: MlsPolicyException) {
            // §12.4a: the core refuses it under the group policy. Retrying can't change that: report
            // and stop (silently: a server op isn't the user's action); the server names someone else.
            log("group op ${pending.opId} refused by the core: ${e.message}")
            return OpOutcome.Failed("policy: ${e.message}")
        } catch (e: Exception) {
            // The core refused to build it (policy, unknown member …): a peer or the server is out of step.
            return OpOutcome.Retry("build: ${e.message}", 30_000)
        }
        return submit(conv, pc, opId = pending.opId, metaChanged = pc.metaChanged, myId = myId)
    }

    /**
     * §12.4a, checked before claiming anything: a non-admin may add devices only of users who
     * already hold a leaf (or its own), and may remove another user's leaf only when the same op
     * re-adds that same device. Null = member-committable; else why not (the core would refuse it).
     */
    internal fun memberPathRefusal(op: PendingOp, leaves: List<DeviceRef>, myId: String): String? {
        val leafUsers = leaves.map { it.userId.lowercase() }.toSet()
        val added = op.added.map { it.userId.lowercase() to it.deviceId.lowercase() }.toSet()
        op.added.firstOrNull { !it.userId.equals(myId, true) && it.userId.lowercase() !in leafUsers }
            ?.let { return "adds a user with no leaf" }
        op.removed.firstOrNull { !it.userId.equals(myId, true) && (it.userId.lowercase() to it.deviceId.lowercase()) !in added }
            ?.let { return "removes another user's device without re-adding it" }
        return null
    }

    /** §12.4 `role`: the admin list after the op, from the server's roles (S6). */
    private fun adminsAfterRole(g: Group, op: PendingOp): List<String> {
        val admins = g.members.filter { it.admin }.map { it.userId }.toMutableList()
        if (op.role == GroupMember.ROLE_ADMIN) {
            op.userIds.forEach { u -> if (admins.none { it.equals(u, true) }) admins += u }
        } else {
            admins.removeAll { a -> op.userIds.any { it.equals(a, true) } }
        }
        return admins
    }

    /**
     * POST the commit (blob refs over 64 KiB), merge on 200, drop on anything else. 409
     * epoch_conflict → catch up, then the op is built again from server state on the retry.
     */
    private suspend fun submit(
        conv: String,
        pc: PendingCommit,
        opId: String?,
        metaChanged: Boolean,
        myId: String,
        onConflict: (suspend () -> OpOutcome)? = null,
    ): OpOutcome {
        val mls = engine() ?: return OpOutcome.Retry("no MLS core", 60_000)
        suspend fun reject(o: OpOutcome): OpOutcome {
            tx.run { mls.commitRejected(conv) }
            return o
        }
        val commitRef = if (pc.commit.size > inlineMaxBytes) {
            when (val r = api.uploadBlob(conv, pc.commit)) {
                is ApiResult.Ok -> r.value
                is ApiResult.Error -> return reject(errorOutcome(r).let { if (it is OpOutcome.Failed) OpOutcome.Retry(it.reason, 30_000) else it })
                is ApiResult.NetworkError -> return reject(OpOutcome.Retry("network", 5_000))
            }
        } else {
            null
        }
        val welcome = pc.welcome
        val welcomeRef = if (welcome != null && welcome.size > inlineMaxBytes) {
            when (val r = api.uploadBlob(conv, welcome)) {
                is ApiResult.Ok -> r.value
                is ApiResult.Error -> return reject(OpOutcome.Retry(r.code, 30_000))
                is ApiResult.NetworkError -> return reject(OpOutcome.Retry("network", 5_000))
            }
        } else {
            null
        }
        val body = GroupCommitRequest(
            generation = pc.generation, epoch = pc.epoch,
            commit = if (commitRef == null) enc.encodeToString(pc.commit) else null, commitRef = commitRef,
            welcome = if (welcome != null && welcomeRef == null) enc.encodeToString(welcome) else null, welcomeRef = welcomeRef,
            added = pc.added.map { MlsDeviceRef(it.userId, it.deviceId) }, removed = pc.removed.map { MlsDeviceRef(it.userId, it.deviceId) },
            opId = opId, metaChanged = metaChanged,
        )
        return when (val r = api.commit(conv, body)) {
            is ApiResult.Ok -> {
                tx.run {
                    mls.commitAccepted(conv) // never before the 200
                    store.onGroupStateChanged(conv, removedSelf = false)
                    groups.get(conv)?.takeIf { it.state == GroupEntity.STATE_CREATING }?.let { groups.upsert(it.copy(state = GroupEntity.STATE_ACTIVE)) }
                }
                OpOutcome.Done
            }
            is ApiResult.Error -> if (r.code == AuthErrors.EPOCH_CONFLICT) {
                reject(OpOutcome.Done) // drop our pending commit first
                catchUp(conv)
                onConflict?.invoke() ?: OpOutcome.Retry(AuthErrors.EPOCH_CONFLICT, 500)
            } else {
                reject(errorOutcome(r))
            }
            // Rare: if the server did take it, our own commit then fails to process and this device rejoins.
            is ApiResult.NetworkError -> reject(OpOutcome.Retry("network", 5_000))
        }
    }
}
