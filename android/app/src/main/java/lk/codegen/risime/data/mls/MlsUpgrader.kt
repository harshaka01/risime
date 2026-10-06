package lk.codegen.risime.data.mls

import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.KeyPackagesClaimReply
import lk.codegen.risime.net.MlsCommitReply
import lk.codegen.risime.net.MlsCommitRequest
import lk.codegen.risime.net.MlsDeviceRef
import lk.codegen.risime.net.MlsGroup
import lk.codegen.risime.net.MlsMissing
import java.util.Base64

/** The REST calls the upgrade needs (ApiClient in the app, a fake in tests). */
interface MlsApi {
    suspend fun group(conversationId: String): ApiResult<MlsGroup>

    suspend fun claim(userIds: List<String>): ApiResult<KeyPackagesClaimReply>

    suspend fun commit(conversationId: String, body: MlsCommitRequest): ApiResult<MlsCommitReply>
}

/** The chat's encryption state, for the lock / "not end-to-end encrypted yet" strip (§10.4). */
sealed interface E2eeState {
    data class Encrypted(val epoch: Long) : E2eeState

    /** Someone's app can't do MLS yet (or the server said so). */
    data class NotReady(val missing: List<MlsMissing>) : E2eeState

    /** The group exists on the server (or we lost a creation race): our Welcome is on its way. */
    data object WaitingForWelcome : E2eeState

    /** No MLS core in this app, or E2EE is off on the server: plaintext, exactly as before. */
    data object Unavailable : E2eeState

    data class Failed(val reason: String) : E2eeState
}

/** Text for the strip under the chat header; null when there's nothing to say. */
fun e2eeStripText(state: E2eeState, nameOf: (String) -> String): String? = when (state) {
    is E2eeState.Encrypted -> null
    E2eeState.Unavailable -> null
    E2eeState.WaitingForWelcome -> "Setting up end-to-end encryption…"
    is E2eeState.Failed -> null
    is E2eeState.NotReady -> {
        val m = state.missing.firstOrNull()
        when {
            m == null -> "Not end-to-end encrypted yet"
            m.reason == MlsMissing.LEGACY_APP || m.reason == MlsMissing.NO_MLS ->
                "Not end-to-end encrypted yet: ${nameOf(m.userId)} needs to update"
            else -> "Not end-to-end encrypted yet: waiting for ${nameOf(m.userId)}'s phone"
        }
    }
}

/**
 * Creates the conversation's group when it's ready (claim → epoch-0 commit with a Welcome →
 * merge only on 200). A creation race is lost gracefully: drop the local group, wait for the Welcome.
 */
class MlsUpgrader(private val engine: () -> MlsEngine?, private val api: MlsApi) {
    private val b64 = Base64.getEncoder()
    private val dec = Base64.getDecoder()

    suspend fun ensure(conversationId: String, myUserId: String, peerId: String): E2eeState {
        val mls = engine() ?: return E2eeState.Unavailable
        mls.group(conversationId)?.let { return E2eeState.Encrypted(it.epoch) }
        val g = when (val r = api.group(conversationId)) {
            is ApiResult.Ok -> r.value
            is ApiResult.Error -> return if (r.code == AuthErrors.MLS_UNAVAILABLE) E2eeState.Unavailable else E2eeState.Failed(r.code)
            is ApiResult.NetworkError -> return E2eeState.Failed("network")
        }
        if (g.e2ee) return E2eeState.WaitingForWelcome
        if (!g.ready) return E2eeState.NotReady(g.missing)
        val claimed = when (val r = api.claim(listOf(peerId, myUserId).distinct())) {
            is ApiResult.Ok -> r.value.devices
            is ApiResult.Error -> return E2eeState.Failed(r.code)
            is ApiResult.NetworkError -> return E2eeState.Failed("network")
        }
        val notReady = claimed.filter { !it.mls || it.deviceId == null || it.keyPackage == null }
        if (notReady.isNotEmpty()) {
            return E2eeState.NotReady(notReady.map { MlsMissing(it.userId, it.deviceId, if (!it.mls) MlsMissing.NO_MLS else "no_key_package") })
        }
        val members = claimed.map { ClaimedKeyPackage(DeviceRef(it.userId, it.deviceId!!), dec.decode(it.keyPackage)) }
        val pc = mls.createGroup(conversationId, g.generation, members)
        val req = MlsCommitRequest(
            generation = pc.generation, epoch = pc.epoch, commit = b64.encodeToString(pc.commit),
            welcome = pc.welcome?.let(b64::encodeToString),
            added = pc.added.map { MlsDeviceRef(it.userId, it.deviceId) },
            removed = pc.removed.map { MlsDeviceRef(it.userId, it.deviceId) },
        )
        return when (val r = api.commit(conversationId, req)) {
            is ApiResult.Ok -> {
                mls.commitAccepted(conversationId) // never merge before the 200
                E2eeState.Encrypted(r.value.epoch)
            }
            is ApiResult.Error -> {
                mls.commitRejected(conversationId)
                when (r.code) {
                    AuthErrors.EPOCH_CONFLICT -> E2eeState.WaitingForWelcome // the other creator won
                    AuthErrors.NOT_READY -> E2eeState.NotReady(r.missing.orEmpty())
                    else -> E2eeState.Failed(r.code)
                }
            }
            is ApiResult.NetworkError -> {
                mls.commitRejected(conversationId)
                E2eeState.Failed("network")
            }
        }
    }
}
