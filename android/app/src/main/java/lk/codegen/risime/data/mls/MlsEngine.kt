package lk.codegen.risime.data.mls

import lk.codegen.risime.net.GroupMeta

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

    /** True while an own commit is built but neither accepted nor rejected (it blocks building another, and encrypting). */
    fun hasPendingCommit(conversationId: String): Boolean = false

    /** Apply someone else's commit (PublicMessage) at the group's current epoch. */
    fun processCommit(conversationId: String, generation: Long, commit: ByteArray): CommitOutcome

    /** Join from a Welcome addressed to this device. */
    fun joinFromWelcome(conversationId: String, generation: Long, welcome: ByteArray): GroupRef

    fun encrypt(conversationId: String, plaintext: ByteArray): ByteArray

    /**
     * Throws [MlsDecryptException] for anything undecryptable. v1.12: the result carries the
     * PrivateMessage's `authenticated_data` and `sender_is_admin` at the message's epoch (§15.4,
     * the core's `processDetailed`); a delete control from an epoch with no admin record throws
     * [MlsMalformedException] (rolled back, nothing consumed).
     */
    fun decrypt(conversationId: String, generation: Long, ciphertext: ByteArray): Decrypted

    /** §15.3: `encrypt` with the PrivateMessage `authenticated_data` (a delete control's canonical AAD). */
    fun encryptWithAad(conversationId: String, plaintext: ByteArray, aad: ByteArray): ByteArray =
        throw UnsupportedOperationException("delete controls not supported by this MLS core")

    /** True once the core answers `authenticated_data` and `sender_is_admin` (v1.12): only then is `deletes` advertised. */
    val deletesSupported: Boolean get() = false

    fun deleteGroup(conversationId: String)

    /** The group's current leaves (for the membership executor). */
    fun members(conversationId: String): List<DeviceRef>

    // ---- §12 groups. Defaults = a core without group support (the v1.8 FFI): the app then never
    // advertises the `groups` capability, so the server never sends it group traffic. ----

    /**
     * True once this core creates and joins groups with `group_meta` (GroupContext extension
     * 0xFA01), uses PrivateMessage handshakes for `grp:`, enforces the admin policy, and its key
     * packages carry capability 0xFA01. Only then does the app advertise `groups` (§12.1).
     */
    val groupsSupported: Boolean get() = false

    /**
     * The device capabilities (§12.1) whose rules this core enforces itself (FFI
     * `core_capabilities()`), e.g. `member_devices` (v1.14 §12.4a). The app advertises such a
     * capability only when it is listed here, never on its own.
     */
    val coreCapabilities: Set<String> get() = emptySet()

    /** Epoch 0 of a `grp:` group (or the rebuild after a reset) with every claimed device and [meta]; my user must be in its admins. */
    fun createGroupWithMeta(conversationId: String, generation: Long, members: List<ClaimedKeyPackage>, meta: GroupMeta): PendingCommit =
        unsupported()

    /** One commit for an `add`, `remove` or `devices` op; a device in both lists is re-added (rejoin). */
    fun changeGroupMembers(conversationId: String, add: List<ClaimedKeyPackage>, removeDevices: List<DeviceRef>): PendingCommit = unsupported()

    /** Removes every leaf of [userIds] (a removal, or an admin committing someone's leave). */
    fun removeGroupUsers(conversationId: String, userIds: List<String>): PendingCommit = unsupported()

    /** A GroupContextExtensions commit: rename, or a `role` op's admin list. */
    fun updateGroupMeta(conversationId: String, meta: GroupMeta): PendingCommit = unsupported()

    /** The group's current `group_meta`, or null (no group, or a DM). v1.24: with `tab`, `chat_id` and `agents`. */
    fun groupMeta(conversationId: String): GroupMeta? = null

    // ---- §24 two tabs (v1.24). Defaults = a core without the §24.1 rules: `tabs` is never advertised. ----

    /** True once the bundled core enforces §24.1 (tab/chat_id immutable, no agent in Private or a DM, admin-only agent adds). */
    val tabsSupported: Boolean get() = false

    /** §24.1/§24.11: the users holding a leaf whose attestation says `kind: "agent"` (from MLS, never server JSON). */
    fun agentUsers(conversationId: String): Set<String> = emptySet()

    // ---- §17 history sharing (v1.15). Defaults = a core without the §17.3 functions: the app then
    // never advertises `history_share`. `rsk` and `K` never cross this interface (crypto R7). ----

    /** True once the core has `history_keygen/open/forget/sender` (and [HistoryCrypto] has `history_seal`). */
    val historySupported: Boolean get() = false

    /** A fresh per-request HPKE key pair; `rsk` stored in the sealed store **in the caller's transaction**; returns `rpk` (32 bytes). */
    fun historyKeygen(requestId: String): ByteArray = throw HistoryException(HistoryException.Kind.Storage, "history not supported")

    /** The stored `rpk` (for a refreshed `history_request`), or null when no key is stored. */
    fun historyPublicKey(requestId: String): ByteArray? = null

    /** Opens one part with the stored `rsk` (HPKE, then the blob's SHA-256 and every segment). */
    fun historyOpen(requestId: String, ctx: HistoryCtx, hpkeEnc: ByteArray, sealedKey: ByteArray, blob: java.io.File): ByteArray =
        throw HistoryException(HistoryException.Kind.Storage, "history not supported")

    /** Deletes a request's `rsk` (idempotent). */
    fun historyForget(requestId: String) = Unit

    /** Request ids that still have a stored `rsk` (the start-up sweep). */
    fun historyOpenRequests(): List<String> = emptyList()

    /** Own versus member for a decrypted history envelope's MLS sender, plus its leaf signature key (§17.7, crypto R4). */
    fun historySender(conversationId: String, sender: DeviceRef): HistorySenderInfo =
        throw HistoryException(HistoryException.Kind.Malformed, "history not supported")

    /** Small app state in the sealed store (namespace "app"), in the caller's transaction: the §17.8 approvals. */
    // ---- §20.6 group call frame keys (crypto README "Group call keys"). The exporter output never
    // crosses this interface; only the per-identity frame keys do (memory only, never logged). ----

    /** The bundled core exports `risime-call-v1` frame keys (`callFrameKeys`). Only then is `group_calls` advertised. */
    val callKeysSupported: Boolean get() = false

    /**
     * §20.6: every leaf's frame key for [callId] at the group's current epoch, in leaf order (= the
     * MLS roster). Throws [CallKeysException]: Malformed (a DM, a non-UUID call id), UnknownGroup,
     * RemovedFromGroup. Catch the group's commits up first (K4).
     */
    fun callFrameKeys(conversationId: String, callId: String): CallKeys = throw CallKeysException(CallKeysException.Kind.Unsupported, "call keys not supported by this MLS core")

    /** §22 the core's backup keys and file streams for this device (null: a core without the backup API). */
    val backupKeys: lk.codegen.risime.data.backup.BackupKeys? get() = null

    fun appStateGet(key: String): ByteArray? = null

    fun appStatePut(key: String, value: ByteArray?) = Unit
}

/** §17.3 the one record behind the HPKE `info` and `aad`; identities are `"<user_id>/<device_id>"` from MLS credentials. */
class HistoryCtx(
    val requestId: String,
    val conversationId: String,
    val requester: String,
    val provider: String,
    val part: Int,
    val parts: Int,
    /** The share's `blob.sha256` (raw) when opening; empty when sealing. */
    val sha256: ByteArray = ByteArray(0),
    val plainSize: Long = 0,
)

/** §17.7 the MLS sender of a history envelope: own (same user) or member, and its leaf signature key (the option-A key). */
/** §20.6 one member's frame key (the leaf identity `<user_id>/<device_id>` = the LiveKit participant identity). */
class CallKey(val identity: String, val key: ByteArray) {
    override fun toString() = "CallKey($identity)" // K10: never the key
}

/** §20.6 the frame keys of one call at [epoch]; [keyIndex] = epoch mod 16. */
class CallKeys(val epoch: Long, val keyIndex: Int, val keys: List<CallKey>) {
    val identities: List<String> get() = keys.map { it.identity }

    /** K10: overwrite the key bytes (on leaving and at call end). */
    fun wipe() = keys.forEach { it.key.fill(0) }

    override fun toString() = "CallKeys(epoch=$epoch, index=$keyIndex, ${keys.size} keys)"
}

class CallKeysException(val kind: Kind, message: String?) : Exception(message) {
    enum class Kind { Malformed, UnknownGroup, RemovedFromGroup, Unsupported, Other }
}

class HistorySenderInfo(val device: DeviceRef, val own: Boolean, val signatureKey: ByteArray)

/** What `history_seal` produced (no key material). */
class SealedPartInfo(val hpkeEnc: ByteArray, val sealedKey: ByteArray, val plainSize: Long, val size: Long, val sha256: ByteArray)

/** A §17.3 failure: [Kind.OpenFailed] rejects the part; [Kind.UnknownRequest] closes the request locally. */
class HistoryException(val kind: Kind, message: String?) : Exception(message) {
    enum class Kind { Malformed, SealRefused, OpenFailed, UnknownRequest, Io, Storage }
}

/**
 * §17.3 the core's free history functions (no `Client` state): `history_seal` and the `'H'` AAD
 * helpers. Found by name (only builds with the Rust toolchain have it).
 */
interface HistoryCrypto {
    /** Seals one part for [rpk]: a fresh `K` inside the core, the blob written to [out] atomically. */
    fun seal(rpk: ByteArray, ctx: HistoryCtx, plaintext: ByteArray, out: java.io.File): SealedPartInfo

    fun aadEncode(requestId: String): ByteArray

    /** The request id of an exact `'H'` AAD, or null. */
    fun aadDecode(aad: ByteArray): String?

    /** Test support: every case of `history_vectors.json`; returns the number checked. */
    fun vectorsCheck(vectorsJson: String, workDir: java.io.File): Int

    companion object {
        private const val IMPL = "lk.codegen.risime.crypto.UniffiHistoryCrypto"

        fun get(): HistoryCrypto? = runCatching { Class.forName(IMPL).getDeclaredConstructor().newInstance() as HistoryCrypto }.getOrNull()
    }
}

private fun unsupported(): Nothing = throw UnsupportedOperationException("groups not supported by this MLS core")

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
    /** §12.4 `meta_changed` (a rename or role commit). */
    val metaChanged: Boolean = false,
)

sealed interface CommitOutcome {
    /** [discardedOwnPending]: our own pending change lost to this commit; redo it if still needed. */
    data class Applied(val epoch: Long, val discardedOwnPending: Boolean = false) : CommitOutcome

    /** This device was removed: the group is gone locally. */
    data object RemovedSelf : CommitOutcome

    /** Verification failed (bad attestation, proposals not matching): dropped and logged. */
    data class Rejected(val reason: String) : CommitOutcome
}

/**
 * The sender as authenticated by MLS (credential identity "<user_id>/<device_id>"). v1.12:
 * [authenticatedData] (empty for everything but a delete control) and [senderIsAdmin] (admin at
 * the message's epoch; null in DMs and for pre-v1.12 epochs of a group).
 */
class Decrypted(
    val sender: DeviceRef,
    val epoch: Long,
    val plaintext: ByteArray,
    val authenticatedData: ByteArray = ByteArray(0),
    val senderIsAdmin: Boolean? = null,
)

open class MlsDecryptException(message: String) : Exception(message)

/** The core's `Malformed` on decrypt: e.g. a delete control from an epoch with no admin record (more than 3 epochs back). */
class MlsMalformedException(message: String) : MlsDecryptException(message)

/** The core refused to build a commit under the group policy (§12.4/§12.4a): never retried as is. */
class MlsPolicyException(message: String) : Exception(message)

fun groupId(conversationId: String, generation: Long) = "$conversationId#$generation"

/** Creates the real engine (found by name: only builds with the Rust toolchain have it). */
interface MlsEngineFactory {
    /**
     * Opens this device's MLS state. [store] must route into the app's Room transaction;
     * [trustedKeysJwks] = pinned keys + the server's /mls/attestation_keys.
     */
    fun open(sql: KvSql, sealer: KvSealer, inTransaction: (() -> Any?) -> Any?, userId: String, deviceId: String, trustedKeysJwks: List<String>): MlsEngine

    companion object {
        private const val IMPL = "lk.codegen.risime.crypto.UniffiMlsEngineFactory"

        fun get(): MlsEngineFactory? = runCatching { Class.forName(IMPL).getDeclaredConstructor().newInstance() as MlsEngineFactory }.getOrNull()
    }
}
