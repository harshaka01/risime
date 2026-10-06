package lk.codegen.risime.data.mls

import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.MlsCommitRequest
import lk.codegen.risime.net.MlsDeviceRef
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
}
