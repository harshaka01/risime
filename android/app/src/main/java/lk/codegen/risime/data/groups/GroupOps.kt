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
import lk.codegen.risime.data.mls.MlsCommitGate
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
    /** §12.12.2 (v1.21): the reply carries the op re-adding this device, its candidates and `exhausted`. */
    suspend fun rejoin(id: String): ApiResult<lk.codegen.risime.net.GroupRejoinReply>
    suspend fun reset(id: String, generation: Long): ApiResult<Long>

    /** §12.5: [conversationId] null = friends and yourself (§10.2), else co-members of that group. */
    suspend fun claim(userIds: List<String>, conversationId: String?): ApiResult<List<ClaimedDevice>>
    suspend fun commit(id: String, body: GroupCommitRequest): ApiResult<Long>
    suspend fun uploadBlob(conversationId: String, bytes: ByteArray): ApiResult<BlobRef>

    /** §24.2 `POST /chats/{chat_id}/official`: the chat's Official group (`creating` when new; idempotent). */
    suspend fun createOfficial(chatId: String): ApiResult<Group> = ApiResult.Error(404, AuthErrors.NOT_FOUND, "")
}

/** §24.2 the Official conversation of [chatId] (lazy for 1:1s and migrated chats; right after a new group). */
@Serializable
data class OfficialPayload(@SerialName("chat_id") val chatId: String)

/**
 * §24.2 epoch 0 of an Official group: `group_meta` `{"tab": "official", "chat_id", "agents", "admins": the
 * Private group's admins (both users for a 1:1), "name": the Private name (none for a 1:1, "" over the FFI)}`.
 * [privateMeta] is the Private group's MLS meta (null for a 1:1); never the server's word.
 */
fun officialEpoch0Meta(chatId: String, group: Group, privateMeta: GroupMeta?, privateName: String?): GroupMeta {
    val dm = chatId.startsWith("dm:")
    val humans = group.members.filter { it.kind != GroupMember.KIND_AGENT }.map { it.userId }
    val agents = (group.agents + group.members.filter { it.kind == GroupMember.KIND_AGENT }.map { it.userId }).distinct()
    val admins = if (dm) humans else privateMeta?.admins?.takeIf { it.isNotEmpty() } ?: group.members.filter { it.admin && it.kind != GroupMember.KIND_AGENT }.map { it.userId }
    return GroupMeta(
        name = if (dm) "" else (privateMeta?.name ?: privateName ?: ""),
        icon = if (dm) null else privateMeta?.icon,
        admins = admins.filterNot { a -> agents.any { it.equals(a, true) } },
        tab = GroupMeta.TAB_OFFICIAL,
        chatId = chatId,
        agents = agents,
    )
}

@Serializable
data class CreatePayload(val name: String, @SerialName("member_ids") val memberIds: List<String>)

@Serializable
data class UsersPayload(@SerialName("user_ids") val userIds: List<String>)

@Serializable
data class RolePayload(@SerialName("user_id") val userId: String, val role: String)

@Serializable
data class RenamePayload(val name: String)

/** §18.7 a group photo change: the §14.4 icon object sealed with the database key (base64), or null to remove. */
@Serializable
data class IconPayload(val sealed: String? = null)

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
    AuthErrors.REJOIN_PENDING -> GroupOpType.RESET_REJOIN_PENDING_TEXT
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
    /** §18.7: opens an [IconPayload]'s sealed icon object (null = can't: the op fails). */
    private val openIcon: (conversationId: String, sealed: ByteArray) -> kotlinx.serialization.json.JsonElement? = { _, _ -> null },
    /** One own commit per conversation at a time, shared with the DM executors (no staged commit outlives its attempt). */
    private val gate: MlsCommitGate = MlsCommitGate(log),
    /** §24.1: the chat id of [conversationId] when this device recorded it as Official from MLS (null = Private or unknown). */
    private val officialTab: suspend (conversationId: String) -> String? = { null },
) {
    private val lock = Mutex()

    /**
     * Key packages claimed for a server op whose commit hasn't landed yet, by op id: a retry reuses
     * them instead of claiming (consuming) more of the target's (memory only; dropped when the op
     * ends or the core refuses to build with them).
     */
    private val claimed = java.util.concurrent.ConcurrentHashMap<String, List<ClaimedDevice>>()
    private val enc = Base64.getEncoder()
    private val dec = Base64.getDecoder()

    /**
     * Runs every due op once, in order. @return the earliest time a queued op is due again (to
     * schedule the next run), or null when nothing is waiting.
     */
    suspend fun runDue(): Long? = lock.withLock {
        for (op in ops.due(clock())) {
            val outcome = try {
                execute(op)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c // the gate has dropped any staged commit; the op stays due
            } catch (t: Exception) {
                OpOutcome.Retry("${t.javaClass.simpleName}: ${t.message}", 30_000)
            }
            val fresh = ops.get(op.id) ?: op
            val attempt = fresh.attempts + 1
            when (outcome) {
                OpOutcome.Done -> if (fresh.attempts > 0) log("group op ${op.type} ${tag(op)} done on attempt $attempt")
                is OpOutcome.Failed -> log("group op ${op.type} ${tag(op)} attempt $attempt failed: ${outcome.reason}")
                is OpOutcome.Retry -> log("group op ${op.type} ${tag(op)} attempt $attempt/$maxAttempts: ${outcome.reason}")
            }
            // A retried op keeps its first error (the cause; later ones are often its consequence).
            val firstError = fresh.lastError
            val next = when (outcome) {
                OpOutcome.Done -> fresh.copy(state = GroupOpType.DONE, lastError = null)
                is OpOutcome.Failed -> fresh.copy(state = GroupOpType.FAILED, lastError = outcome.reason)
                is OpOutcome.Retry -> {
                    val attempts = attempt
                    if (attempts >= maxAttempts) {
                        val last = if (firstError == null || firstError == outcome.reason) outcome.reason else "$firstError (last: ${outcome.reason})"
                        fresh.copy(state = GroupOpType.FAILED, attempts = attempts, lastError = last)
                    } else {
                        fresh.copy(attempts = attempts, nextAt = clock() + outcome.afterMs * (1L shl (attempts - 1).coerceAtMost(6)), lastError = firstError ?: outcome.reason)
                    }
                }
            }
            if (next.state != GroupOpType.QUEUED) op.opId?.let { claimed.remove(it) }
            ops.update(next)
            if (next.state == GroupOpType.FAILED) onFailed(next)
        }
        ops.queued().minOfOrNull { it.nextAt }
    }

    private fun tag(op: GroupOpEntity) = op.opId?.take(8) ?: "#${op.id}"

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
            GroupOpType.CREATE_OFFICIAL -> createOfficial(op, myId)
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
            GroupOpType.RENAME -> committing(op) { rename(op, myId) }
            GroupOpType.ICON -> committing(op) { icon(op, myId) }
            GroupOpType.COMMIT -> committing(op) { commitServerOp(op, myId) }
            GroupOpType.REJOIN -> rejoin(op, myId)
            // v1.21: an automatic reset queued by an older app version (the only admin's) is a rejoin now.
            GroupOpType.RESET -> if (automatic(op)) rejoin(op, myId) else manualReset(op)
            else -> OpOutcome.Failed("unknown op type ${op.type}")
        }
    }

    /** An op that builds a commit for its conversation: under the conversation's commit gate. */
    private suspend fun committing(op: GroupOpEntity, block: suspend () -> OpOutcome): OpOutcome {
        val mls = engine() ?: return OpOutcome.Retry("no MLS core", 60_000)
        return gate.withCommit(op.conversationId!!, mls, block)
    }

    /**
     * §12.8/§12.12: ask to be re-added, then decide from the reply. Waiting is the normal outcome
     * (the op row is done; the next rejoin comes from a sync, the open chat or its 15-minute
     * refresh). Only an admin device resets, and only as the last resort ([RejoinRules.group]).
     */
    private suspend fun rejoin(op: GroupOpEntity, myId: String): OpOutcome {
        val conv = op.conversationId!!
        if (alreadyJoined(op)) {
            store.clearRejoinWait(conv)
            return OpOutcome.Done
        }
        val reply = when (val r = api.rejoin(conv)) {
            is ApiResult.Ok -> r.value
            is ApiResult.Error -> return errorOutcome(r)
            is ApiResult.NetworkError -> return OpOutcome.Retry("network", 5_000)
        }
        tx.run { store.applyServerGroup(reply.group, myId) }
        // An automatic rejoin whose Welcome landed while we asked.
        if (alreadyJoined(op)) {
            store.clearRejoinWait(conv)
            return OpOutcome.Done
        }
        store.noteRejoinWait(conv, reply)
        val admin = reply.group.myRole == GroupMember.ROLE_ADMIN
        val decision = lk.codegen.risime.data.mls.RejoinRules.group(
            admin, reply.candidates, reply.exhausted, lk.codegen.risime.data.mls.RejoinRules.epochMs(reply.op?.createdAt), clock(),
        )
        if (decision == lk.codegen.risime.data.mls.RejoinDecision.WAIT) return OpOutcome.Done
        log("group $conv: nobody can re-add this device (candidates ${reply.candidates}, exhausted ${reply.exhausted}): last-resort reset")
        return when (val r = api.reset(conv, reply.group.generation)) {
            is ApiResult.Ok -> {
                store.clearRejoinWait(conv)
                OpOutcome.Done // the rebuild op reaches us as group_op
            }
            is ApiResult.Error -> when (r.code) {
                AuthErrors.GENERATION_CONFLICT -> OpOutcome.Done // someone else reset
                AuthErrors.REJOIN_PENDING -> OpOutcome.Done // §12.12.3: the server still sees a viable re-add: keep waiting
                else -> errorOutcome(r)
            }
            is ApiResult.NetworkError -> OpOutcome.Retry("network", 5_000)
        }
    }

    /**
     * §12.12.3 an admin's confirmed "Reset encryption". `409 rejoin_pending`: this device is still
     * being re-added, so it isn't retried; a rejoin is queued instead and the user is told.
     */
    private suspend fun manualReset(op: GroupOpEntity): OpOutcome {
        val conv = op.conversationId!!
        val g = groups.get(conv) ?: return OpOutcome.Done
        return when (val r = api.reset(conv, g.generation)) {
            is ApiResult.Ok -> {
                store.clearRejoinWait(conv)
                OpOutcome.Done // the rebuild op reaches us as group_op
            }
            is ApiResult.Error -> when (r.code) {
                AuthErrors.GENERATION_CONFLICT -> OpOutcome.Done
                AuthErrors.REJOIN_PENDING -> {
                    store.requestRejoin(conv, except = op.id)
                    OpOutcome.Failed(AuthErrors.REJOIN_PENDING)
                }
                else -> errorOutcome(r)
            }
            is ApiResult.NetworkError -> OpOutcome.Retry("network", 5_000)
        }
    }

    private fun automatic(op: GroupOpEntity): Boolean =
        runCatching { ProtocolJson.decodeFromString(RejoinPayload.serializer(), op.payloadJson) }.getOrNull()?.ifMissing == true

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
        return gate.withCommit(conv, mls) {
            val claimed = when (val r = api.claim((p.memberIds + myId).distinct(), null)) {
                is ApiResult.Ok -> r.value
                is ApiResult.Error -> return@withCommit errorOutcome(r)
                is ApiResult.NetworkError -> return@withCommit OpOutcome.Retry("network", 5_000)
            }
            val kps = keyPackages(claimed) ?: return@withCommit OpOutcome.Retry("no_key_package", 30_000)
            val meta = GroupMeta(name = p.name, admins = listOf(myId))
            val pc = tx.run {
                if (mls.group(conv) != null) mls.deleteGroup(conv) // a stale local epoch 0 from a crashed attempt
                mls.createGroupWithMeta(conv, group.generation, kps, meta)
            }
            submit(conv, pc, opId = null, metaChanged = false, myId = myId, onConflict = {
                // epoch 0 taken: an earlier attempt of ours landed. Re-read; rejoin if we lost the state.
                OpOutcome.Retry(AuthErrors.EPOCH_CONFLICT, 1_000)
            })
        }
    }

    /**
     * §24.2: `POST /chats/{chat_id}/official` (idempotent: a retry gets the same group), then the epoch-0
     * commit adding every device the claim returns (the members' `tabs` devices and Risi's) with the
     * Official `group_meta`. An Official group that is already active is done (rejoined if this device
     * has no state for it).
     */
    private suspend fun createOfficial(op: GroupOpEntity, myId: String): OpOutcome {
        val mls = engine() ?: return OpOutcome.Retry("no MLS core", 60_000)
        val p = ProtocolJson.decodeFromString(OfficialPayload.serializer(), op.payloadJson)
        val group = when (val r = api.createOfficial(p.chatId)) {
            is ApiResult.Ok -> r.value
            is ApiResult.Error -> return errorOutcome(r)
            is ApiResult.NetworkError -> return OpOutcome.Retry("network", 5_000)
        }
        val conv = group.id
        if (op.conversationId != conv) ops.update((ops.get(op.id) ?: op).copy(conversationId = conv))
        tx.run { store.applyServerGroup(group, myId) }
        if (group.state != Group.STATE_CREATING) {
            if (mls.group(conv) == null) store.queueLocal(conv, GroupOpType.REJOIN)
            return OpOutcome.Done
        }
        return gate.withCommit(conv, mls) {
            val claimed = when (val r = api.claim((group.members.map { it.userId } + myId).distinct(), conv)) {
                is ApiResult.Ok -> r.value
                is ApiResult.Error -> return@withCommit errorOutcome(r)
                is ApiResult.NetworkError -> return@withCommit OpOutcome.Retry("network", 5_000)
            }
            val kps = keyPackages(claimed) ?: return@withCommit OpOutcome.Retry("no_key_package", 30_000)
            val privateMeta = if (p.chatId.startsWith("dm:")) null else tx.run { mls.groupMeta(p.chatId) }
            val meta = officialEpoch0Meta(p.chatId, group, privateMeta, groups.get(p.chatId)?.name)
            val pc = try {
                tx.run {
                    if (mls.group(conv) != null) mls.deleteGroup(conv)
                    mls.createGroupWithMeta(conv, group.generation, kps, meta)
                }
            } catch (e: MlsPolicyException) {
                return@withCommit OpOutcome.Failed("policy: ${e.message}")
            }
            submit(
                conv, pc, opId = null, metaChanged = false, myId = myId, onConflict = { OpOutcome.Retry(AuthErrors.EPOCH_CONFLICT, 1_000) },
                // §24.2: a member was added/removed while Official was `creating` (the server re-synced its rows):
                // refetch the group now; the retry claims key packages again and rebuilds epoch 0 (bounded by the op's attempts).
                onMembersChanged = {
                    (api.group(conv) as? ApiResult.Ok)?.value?.let { g -> tx.run { store.applyServerGroup(g, myId) } }
                    log("official $conv: members changed while creating: rebuilding epoch 0")
                    OpOutcome.Retry(lk.codegen.risime.net.TabsErrors.MEMBERS_CHANGED, 1_000)
                },
            )
        }
    }

    /**
     * §12.5 claim for a server op, reusing what an earlier attempt of the same op claimed (its
     * commit never landed, so those key packages are unused) when it covers [userIds].
     */
    private suspend fun claimFor(opId: String, userIds: List<String>, conv: String): ApiResult<List<ClaimedDevice>> {
        claimed[opId]?.let { c -> if (userIds.all { u -> c.any { it.userId.equals(u, true) } }) return ApiResult.Ok(c) }
        val r = api.claim(userIds, conv)
        if (r is ApiResult.Ok) claimed[opId] = r.value
        return r
    }

    /** A device came back without a key package: claim afresh on the retry (it may have uploaded some). */
    private fun noKeyPackage(opId: String): OpOutcome {
        claimed.remove(opId)
        return OpOutcome.Retry("no_key_package", 30_000)
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

    /** §18.7 set/change/remove the group photo: the same `meta_changed` commit with the new `group_meta.icon`. */
    private suspend fun icon(op: GroupOpEntity, myId: String): OpOutcome {
        val conv = op.conversationId!!
        val mls = engine() ?: return OpOutcome.Retry("no MLS core", 60_000)
        val p = ProtocolJson.decodeFromString(IconPayload.serializer(), op.payloadJson)
        val icon = p.sealed?.let { s -> openIcon(conv, dec.decode(s)) ?: return OpOutcome.Failed("icon: can't open") }
        val current = tx.run { mls.groupMeta(conv) } ?: return OpOutcome.Retry("no group yet", 10_000)
        if (current.icon == icon || (icon == null && current.icon is kotlinx.serialization.json.JsonNull)) return OpOutcome.Done
        val pc = runCatching { tx.run { mls.updateGroupMeta(conv, current.copy(icon = icon)) } }
            .getOrElse { return OpOutcome.Failed("policy: ${it.message}") }
        return submit(conv, pc, opId = null, metaChanged = true, myId = myId)
    }

    // ---- server ops naming this device (R4/R5): re-derived from GET /groups/{id} every time ----

    private suspend fun commitServerOp(op: GroupOpEntity, myId: String): OpOutcome {
        val conv = op.conversationId!!
        val mls = engine() ?: return OpOutcome.Retry("no MLS core", 60_000)
        val g = when (val r = api.group(conv)) {
            is ApiResult.Ok -> r.value
            is ApiResult.Error -> return if (r.httpStatus == 404) {
                // §24.7: a 404 on an Official group is not "gone" (only a group_event removes us): retry.
                if (officialTab(conv) != null) OpOutcome.Retry("official group 404", 30_000) else OpOutcome.Done
            } else errorOutcome(r)
            is ApiResult.NetworkError -> return OpOutcome.Retry("network", 5_000)
        }
        tx.run { store.applyServerGroup(g, myId) }
        val pending = g.pending.firstOrNull { it.opId == op.opId } ?: return OpOutcome.Done // completed elsewhere or expired
        if (pending.type != PendingOp.REBUILD && mls.group(conv) == null) return OpOutcome.Retry("no group yet", 10_000)
        val pc: PendingCommit = try {
            when (pending.type) {
                PendingOp.ADD -> {
                    val claimed = when (val r = claimFor(pending.opId, pending.userIds, conv)) {
                        is ApiResult.Ok -> r.value
                        is ApiResult.Error -> return errorOutcome(r)
                        is ApiResult.NetworkError -> return OpOutcome.Retry("network", 5_000)
                    }
                    val have = mls.members(conv).map { it.deviceId.lowercase() }.toSet()
                    val kps = keyPackages(claimed.filter { it.deviceId?.lowercase() !in have }) ?: return noKeyPackage(pending.opId)
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
                    // §12.12.6 a cleanup op (`added: []`) never removes this device's own leaf.
                    val myDevice = deviceId()
                    if (pending.added.isEmpty() && pending.removed.any { it.userId.equals(myId, true) && it.deviceId.equals(myDevice, true) }) {
                        log("group op ${pending.opId}: a cleanup op lists this device; not committing")
                        return OpOutcome.Failed("policy: removes this device")
                    }
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
                    val claimed = if (addUsers.isEmpty()) emptyList() else when (val r = claimFor(pending.opId, addUsers, conv)) {
                        is ApiResult.Ok -> r.value
                        is ApiResult.Error -> return errorOutcome(r)
                        is ApiResult.NetworkError -> return OpOutcome.Retry("network", 5_000)
                    }
                    val wanted = pending.added.map { it.deviceId.lowercase() }.toSet()
                    val kps = keyPackages(claimed.filter { it.deviceId?.lowercase() in wanted }) ?: return noKeyPackage(pending.opId)
                    // §12.4a: the member path must match the op's `added` exactly (the server rejects a subset).
                    if (memberPath && kps.map { it.device.deviceId.lowercase() }.toSet() != wanted) return noKeyPackage(pending.opId)
                    val present = leaves.map { it.deviceId.lowercase() }.toSet()
                    val remove = pending.removed.filter { it.deviceId.lowercase() in present }.map { DeviceRef(it.userId, it.deviceId) }
                    if (kps.isEmpty() && remove.isEmpty()) return OpOutcome.Done
                    tx.run { mls.changeGroupMembers(conv, kps, remove) }
                }
                PendingOp.REBUILD -> {
                    val members = g.members.filter { it.state == GroupMember.STATE_ACTIVE }.map { it.userId }
                    val claimed = when (val r = claimFor(pending.opId, (members + myId).distinct(), conv)) {
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
                        // §24.1: a rebuilt Official group stays Official of the same chat (from this device's MLS state, never the server's word).
                        val old = mls.groupMeta(conv)?.takeIf { it.official } ?: officialTab(conv)?.let { GroupMeta(name = name, tab = GroupMeta.TAB_OFFICIAL, chatId = it) }
                        mls.group(conv)?.takeIf { it.generation < g.generation }?.let { mls.deleteGroup(conv) }
                        val meta = if (old == null) {
                            GroupMeta(name = name, admins = admins)
                        } else {
                            val agents = (g.agents + g.members.filter { it.kind == GroupMember.KIND_AGENT }.map { it.userId }).distinct()
                            GroupMeta(name = if ((old.chatId ?: conv).startsWith("dm:")) "" else old.name, icon = old.icon, admins = admins.filterNot { a -> agents.any { it.equals(a, true) } }, tab = GroupMeta.TAB_OFFICIAL, chatId = old.chatId ?: conv, agents = agents)
                        }
                        mls.createGroupWithMeta(conv, g.generation, kps, meta)
                    }
                }
                else -> return OpOutcome.Failed("unknown op ${pending.type}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: UnsupportedOperationException) {
            return OpOutcome.Failed("unsupported")
        } catch (e: MlsPolicyException) {
            // §12.4a: the core refuses it under the group policy. Retrying can't change that: report
            // and stop (silently: a server op isn't the user's action); the server names someone else.
            log("group op ${pending.opId} refused by the core: ${e.message}")
            return OpOutcome.Failed("policy: ${e.message}")
        } catch (e: Exception) {
            // The core refused to build it (policy, unknown member, a bad key package …): a peer or the
            // server is out of step. Claim afresh next time.
            claimed.remove(pending.opId)
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
        /** §24.2 `409 members_changed` (an Official epoch 0 only); null: a permanent failure like any other 409. */
        onMembersChanged: (suspend () -> OpOutcome)? = null,
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
            } else if (r.code == lk.codegen.risime.net.TabsErrors.MEMBERS_CHANGED && onMembersChanged != null) {
                reject(OpOutcome.Done) // drop the stale epoch 0 first
                onMembersChanged()
            } else {
                reject(errorOutcome(r))
            }
            // Rare: if the server did take it, our own commit then fails to process and this device rejoins.
            is ApiResult.NetworkError -> reject(OpOutcome.Retry("network", 5_000))
        }
    }
}
