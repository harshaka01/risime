package lk.codegen.risime.data.mls

import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.MlsCommitRequest
import lk.codegen.risime.net.MlsDeviceRef
import lk.codegen.risime.net.MlsDmOpEvent
import java.util.Base64

sealed interface MembershipOutcome {
    data object Done : MembershipOutcome

    /** Nothing to do: already applied (by us or the named committer), or no group here. */
    data object NotNeeded : MembershipOutcome

    data class Failed(val reason: String) : MembershipOutcome
}

/**
 * §10.3 `mls_membership` after its delay: add a new device (claim its key package, commit with a
 * Welcome) or remove a gone one, only if the group still needs it. 409 → catch up and re-check
 * once. Our commit merges only on the server's 200.
 */
class MembershipExecutor(
    private val engine: () -> MlsEngine?,
    private val api: MlsApi,
    private val catchUp: suspend (String) -> Unit,
) {
    private val enc = Base64.getEncoder()
    private val dec = Base64.getDecoder()

    suspend fun execute(a: MembershipAction, retried: Boolean = false): MembershipOutcome {
        val mls = engine() ?: return MembershipOutcome.NotNeeded
        val e = a.event
        val conv = e.conversationId
        mls.group(conv) ?: return MembershipOutcome.NotNeeded
        val target = DeviceRef(e.userId, e.deviceId)
        val present = mls.members(conv).any { it.userId.equals(target.userId, true) && it.deviceId.equals(target.deviceId, true) }
        val pc = when (e.change) {
            "added" -> {
                if (present) return MembershipOutcome.NotNeeded
                val claimed = (api.claim(listOf(e.userId)) as? ApiResult.Ok)?.value?.devices
                    ?.firstOrNull { it.deviceId.equals(e.deviceId, true) && it.mls && it.keyPackage != null }
                    ?: return MembershipOutcome.Failed("no key package for ${e.deviceId}")
                runCatching { mls.changeMembers(conv, listOf(ClaimedKeyPackage(target, dec.decode(claimed.keyPackage))), emptyList()) }
                    .getOrElse { return MembershipOutcome.Failed("add: ${it.message}") }
            }
            "removed" -> {
                if (!present) return MembershipOutcome.NotNeeded
                runCatching { mls.changeMembers(conv, emptyList(), listOf(target)) }.getOrElse { return MembershipOutcome.Failed("remove: ${it.message}") }
            }
            else -> return MembershipOutcome.NotNeeded
        }
        val req = MlsCommitRequest(
            pc.generation, pc.epoch, enc.encodeToString(pc.commit), pc.welcome?.let(enc::encodeToString),
            pc.added.map { MlsDeviceRef(it.userId, it.deviceId) }, pc.removed.map { MlsDeviceRef(it.userId, it.deviceId) },
        )
        return when (val r = api.commit(conv, req)) {
            is ApiResult.Ok -> {
                mls.commitAccepted(conv)
                MembershipOutcome.Done
            }
            is ApiResult.Error -> {
                mls.commitRejected(conv)
                if (r.code == AuthErrors.EPOCH_CONFLICT && !retried) {
                    catchUp(conv) // someone else's commit may already have done it
                    execute(a, retried = true)
                } else {
                    MembershipOutcome.Failed(r.code)
                }
            }
            is ApiResult.NetworkError -> {
                mls.commitRejected(conv)
                MembershipOutcome.Failed("network")
            }
        }
    }

    /**
     * v1.16 (proposal 2026-10-07-dm-device-readd §2.2, §5): this device was named for a DM `devices`
     * op. The DM core builds one add **or** one removal per commit, so the op takes up to three
     * commits, each with its `op_id`: (a) removals of devices the op re-adds (a lost MLS state),
     * (b) the adds with a Welcome, (c) the superseded removals. 409 → catch up and re-derive once.
     */
    suspend fun executeOp(e: MlsDmOpEvent, retried: Boolean = false): MembershipOutcome {
        val mls = engine() ?: return MembershipOutcome.NotNeeded
        val op = e.op
        val conv = e.conversationId
        val committer = op.committer ?: return MembershipOutcome.NotNeeded
        if (!committer.deviceId.equals(mls.deviceId, true) || !committer.userId.equals(mls.userId, true)) return MembershipOutcome.NotNeeded
        val g = mls.group(conv) ?: return MembershipOutcome.NotNeeded
        if (g.generation != e.generation) return MembershipOutcome.NotNeeded
        fun same(a: MlsDeviceRef, b: DeviceRef) = a.userId.equals(b.userId, true) && a.deviceId.equals(b.deviceId, true)
        fun MlsDeviceRef.ref() = DeviceRef(userId, deviceId)
        val readd = op.added.filter { a -> op.removed.any { it.userId.equals(a.userId, true) && it.deviceId.equals(a.deviceId, true) } }
        var did = false
        // (a) a device that lost its state: its old leaf goes first.
        run {
            val present = mls.members(conv)
            val gone = readd.filter { r -> present.any { same(r, it) } }.map { it.ref() }
            if (gone.isNotEmpty()) {
                val pc = runCatching { mls.changeMembers(conv, emptyList(), gone) }.getOrElse { return MembershipOutcome.Failed("remove: ${it.message}") }
                when (val o = submit(conv, pc, op.opId)) { null -> did = true; else -> return retryOr(e, retried, o) }
            }
        }
        // (b) the adds (new devices, and re-adds whose old leaf is gone now).
        run {
            val present = mls.members(conv)
            val missing = op.added.filter { a -> present.none { same(a, it) } }
            if (missing.isNotEmpty()) {
                val users = missing.map { it.userId.lowercase() }.distinct()
                val claimed = (api.claim(users) as? ApiResult.Ok)?.value?.devices ?: return MembershipOutcome.Failed("claim")
                val kps = missing.map { m ->
                    val c = claimed.firstOrNull { it.deviceId.equals(m.deviceId, true) && it.mls && it.keyPackage != null }
                        ?: return MembershipOutcome.Failed("no key package for ${m.deviceId}")
                    ClaimedKeyPackage(m.ref(), dec.decode(c.keyPackage))
                }
                val pc = runCatching { mls.changeMembers(conv, kps, emptyList()) }.getOrElse { return MembershipOutcome.Failed("add: ${it.message}") }
                when (val o = submit(conv, pc, op.opId)) { null -> did = true; else -> return retryOr(e, retried, o) }
            }
        }
        // (c) the user's superseded leaves.
        run {
            val present = mls.members(conv)
            val stale = op.removed.filter { r -> readd.none { it == r } && present.any { same(r, it) } }.map { it.ref() }
            if (stale.isNotEmpty()) {
                val pc = runCatching { mls.changeMembers(conv, emptyList(), stale) }.getOrElse { return MembershipOutcome.Failed("remove: ${it.message}") }
                when (val o = submit(conv, pc, op.opId)) { null -> did = true; else -> return retryOr(e, retried, o) }
            }
        }
        return if (did) MembershipOutcome.Done else MembershipOutcome.NotNeeded
    }

    /** Commits [pc] with [opId]; null on 200 (merged), else the error code. */
    private suspend fun submit(conv: String, pc: PendingCommit, opId: String): String? {
        val req = MlsCommitRequest(
            pc.generation, pc.epoch, enc.encodeToString(pc.commit), pc.welcome?.let(enc::encodeToString),
            pc.added.map { MlsDeviceRef(it.userId, it.deviceId) }, pc.removed.map { MlsDeviceRef(it.userId, it.deviceId) },
            opId = opId,
        )
        return when (val r = api.commit(conv, req)) {
            is ApiResult.Ok -> { mls(conv)?.commitAccepted(conv); null }
            is ApiResult.Error -> { mls(conv)?.commitRejected(conv); r.code }
            is ApiResult.NetworkError -> { mls(conv)?.commitRejected(conv); "network" }
        }
    }

    private fun mls(@Suppress("UNUSED_PARAMETER") conv: String) = engine()

    private suspend fun retryOr(e: MlsDmOpEvent, retried: Boolean, code: String): MembershipOutcome =
        if (code == AuthErrors.EPOCH_CONFLICT && !retried) {
            catchUp(e.conversationId) // someone else's commit may already have done part of it
            executeOp(e, retried = true)
        } else {
            MembershipOutcome.Failed(code)
        }
}
