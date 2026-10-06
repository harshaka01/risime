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

/** The chat's encryption state, for the header lock / "Not end-to-end encrypted yet" strip (§10.4, decision 048). */
sealed interface E2eeState {
    data class Encrypted(val epoch: Long) : E2eeState

    /** Not known yet (the chat just opened): no lock, no claim either way. */
    data object Checking : E2eeState

    /** Some install can't do MLS yet (or the server said so); `missing[]` says why (§10.2). */
    data class NotReady(val missing: List<MlsMissing>) : E2eeState

    /** The group exists on the server (or we lost a creation race): our Welcome is on its way. */
    data object WaitingForWelcome : E2eeState

    /** No MLS core in this app, or E2EE is off on the server: plaintext, exactly as before. */
    data object Unavailable : E2eeState

    data class Failed(val reason: String) : E2eeState
}

/** Prefix of every "this chat is plaintext" strip (decision 048: a plaintext chat is always labelled). */
const val NOT_E2EE_PREFIX = "Not end-to-end encrypted yet"

/** Chat info line for an E2EE chat (decision 048). */
const val E2EE_INFO_TEXT = "Messages are end-to-end encrypted"

/** A claim reply device with no key package left (client-side reason, not on the wire). */
const val NO_KEY_PACKAGE = "no_key_package"

/**
 * The real reason a chat isn't E2EE yet, most actionable first:
 * - `legacy_app` (an old app still in use) → "<name> needs to update RisiMe";
 * - `no_mls` with a device id, or no key package → "<name>'s phone hasn't finished setting up encryption";
 * - `no_mls` without a device id (no current RisiMe has registered yet) → "waiting for <name>'s phone to come online".
 * [nameOf] names other users; [isMe] marks my own user id (then it's "your other phone"; this
 * phone itself when [myDeviceId] matches, or when I have no MLS device at all).
 */
fun notReadyReason(missing: List<MlsMissing>, nameOf: (String) -> String, isMe: (String) -> Boolean, myDeviceId: String? = null): String? {
    val m = missing.minByOrNull {
        when (it.reason) {
            MlsMissing.LEGACY_APP -> 0
            MlsMissing.NO_MLS -> if (it.deviceId != null) 1 else 2
            else -> 1
        }
    } ?: return null
    val me = isMe(m.userId)
    val thisPhone = me && (m.deviceId == null || m.deviceId.equals(myDeviceId, true))
    val whose = when {
        thisPhone -> "this phone"
        me -> "your other phone"
        else -> "${nameOf(m.userId)}'s phone"
    }
    return when {
        m.reason == MlsMissing.LEGACY_APP ->
            if (me) "your other phone needs to update RisiMe" else "${nameOf(m.userId)} needs to update RisiMe"
        thisPhone -> "this phone hasn't finished setting up encryption"
        m.reason == MlsMissing.NO_MLS && m.deviceId == null -> "waiting for $whose to come online"
        else -> "$whose hasn't finished setting up encryption"
    }
}

/** Text for the strip under the chat header; null only when the chat is E2EE (or still being checked). */
fun e2eeStripText(
    state: E2eeState,
    nameOf: (String) -> String,
    isMe: (String) -> Boolean = { false },
    myDeviceId: String? = null,
): String? = when (state) {
    is E2eeState.Encrypted -> null
    E2eeState.Checking -> null
    E2eeState.Unavailable -> "Not end-to-end encrypted: encryption isn't available on this server or app"
    E2eeState.WaitingForWelcome -> "$NOT_E2EE_PREFIX: setting up end-to-end encryption…"
    is E2eeState.Failed ->
        if (state.reason == "network") "$NOT_E2EE_PREFIX: can't reach the server, retrying"
        else "$NOT_E2EE_PREFIX: encryption setup didn't finish, retrying"
    is E2eeState.NotReady ->
        notReadyReason(state.missing, nameOf, isMe, myDeviceId)?.let { "$NOT_E2EE_PREFIX: $it" } ?: NOT_E2EE_PREFIX
}

/**
 * When to try the upgrade again (P0-1): readiness changes without an event reaching us (the peer
 * updates, registers or comes online), so a chat that isn't E2EE re-checks with backoff: 5 s,
 * 10 s, 20 s … capped at 5 min. A kick (chat open/resume, `mls_membership`, reconnect) resets it.
 * Null = don't poll (encrypted).
 */
class E2eeRetryBackoff(private val firstMs: Long = 5_000, private val maxMs: Long = 300_000) {
    private var next = firstMs

    fun reset() { next = firstMs }

    fun delayAfter(state: E2eeState): Long? {
        if (state is E2eeState.Encrypted) return null
        val d = next
        next = (next * 2).coerceAtMost(maxMs)
        return d
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
            return E2eeState.NotReady(notReady.map { MlsMissing(it.userId, it.deviceId, if (!it.mls) MlsMissing.NO_MLS else NO_KEY_PACKAGE) })
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
