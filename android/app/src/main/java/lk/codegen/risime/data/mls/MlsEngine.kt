package lk.codegen.risime.data.mls

/**
 * What the app needs from the MLS core (contract §10). Phase B implements it over the new
 * `risime-mls-ffi` API (MlsClient.open(store, userId, deviceId, trustedKeysJwks), PendingCommit,
 * key packages, attestation verified in Rust). Phase A programs against it with a fake.
 *
 * Group ids are `"<conversation_id>#<generation>"` ([groupId]). All calls are synchronous and run
 * inside the caller's Room transaction (the KvStore maps begin/commit to savepoints).
 */
interface MlsEngine {
    val userId: String
    val deviceId: String

    /** The Ed25519 public key for `PUT /me/devices` (`mls.signature_key`). */
    fun signatureKey(): ByteArray

    /** The server's attestation JWS for this device (goes into every key package's leaf). */
    fun setAttestation(jws: String)

    /** The local group for a conversation, if this device is a member. */
    fun group(conversationId: String): GroupRef?

    fun createKeyPackages(count: Int): List<ByteArray>

    fun lastResortKeyPackage(): ByteArray

    /** Epoch 0: a new group with every claimed device. Pending until [commitAccepted]. */
    fun createGroup(conversationId: String, generation: Long, members: List<ClaimedKeyPackage>): PendingCommit

    /** Add/remove devices in an existing group. Pending until [commitAccepted]. */
    fun changeMembers(conversationId: String, add: List<ClaimedKeyPackage>, removeDevices: List<DeviceRef>): PendingCommit

    /** The server answered 200: merge. */
    fun commitAccepted(conversationId: String)

    /** 409 or any failure: drop the pending commit (and, at epoch 0, the local group). */
    fun commitRejected(conversationId: String)

    /** Apply someone else's commit (PublicMessage) at the group's current epoch. */
    fun processCommit(conversationId: String, generation: Long, commit: ByteArray): CommitOutcome

    /** Join from a Welcome addressed to this device. */
    fun joinFromWelcome(conversationId: String, generation: Long, welcome: ByteArray): GroupRef

    fun encrypt(conversationId: String, plaintext: ByteArray): ByteArray

    /** Throws [MlsDecryptException] for anything undecryptable. */
    fun decrypt(conversationId: String, generation: Long, ciphertext: ByteArray): Decrypted

    fun deleteGroup(conversationId: String)
}

data class GroupRef(val conversationId: String, val generation: Long, val epoch: Long)

data class DeviceRef(val userId: String, val deviceId: String)

data class ClaimedKeyPackage(val device: DeviceRef, val keyPackage: ByteArray)

class PendingCommit(
    val generation: Long,
    /** The epoch the commit was built in (what the server compares). */
    val epoch: Long,
    val commit: ByteArray,
    val welcome: ByteArray?,
    val added: List<DeviceRef>,
    val removed: List<DeviceRef>,
)

sealed interface CommitOutcome {
    data class Applied(val epoch: Long) : CommitOutcome

    /** This device was removed: the group is gone locally. */
    data object RemovedSelf : CommitOutcome

    /** Verification failed (bad attestation, proposals not matching): dropped and logged. */
    data class Rejected(val reason: String) : CommitOutcome
}

/** The sender as authenticated by MLS (credential identity "<user_id>/<device_id>"). */
data class Decrypted(val sender: DeviceRef, val epoch: Long, val plaintext: ByteArray)

class MlsDecryptException(message: String) : Exception(message)

fun groupId(conversationId: String, generation: Long) = "$conversationId#$generation"
