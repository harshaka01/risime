package lk.codegen.risime.crypto

import lk.codegen.risime.data.mls.ClaimedKeyPackage
import lk.codegen.risime.data.mls.CommitOutcome
import lk.codegen.risime.data.mls.Decrypted
import lk.codegen.risime.data.mls.DeviceRef
import lk.codegen.risime.data.mls.GroupRef
import lk.codegen.risime.data.mls.KvSealer
import lk.codegen.risime.data.mls.KvSql
import lk.codegen.risime.data.mls.MlsDecryptException
import lk.codegen.risime.data.mls.MlsEngine
import lk.codegen.risime.data.mls.MlsEngineFactory
import lk.codegen.risime.data.mls.SealedKvStore
import lk.codegen.risime.data.mls.groupId
import lk.codegen.risime.data.mls.PendingCommit as AppPendingCommit

/** The core's KvStore over the sealed `mls_kv` table (namespace "mls"); savepoints nest in Room's transaction. */
class SealedFfiKvStore(private val kv: SealedKvStore) : KvStore {
    private fun <T> wrap(f: () -> T): T = try {
        f()
    } catch (e: KvStoreException) {
        throw e
    } catch (e: Exception) {
        throw KvStoreException.Failed(e.toString())
    }

    override fun get(key: ByteArray): ByteArray? = wrap { kv.get(NS, key) }
    override fun put(key: ByteArray, value: ByteArray) = wrap { kv.put(NS, key, value) }
    override fun delete(key: ByteArray) = wrap { kv.delete(NS, key) }
    override fun begin() = wrap { kv.begin() }
    override fun commit() = wrap { kv.commit() }
    override fun rollback() = wrap { kv.rollback() }

    private companion object {
        const val NS = "mls"
    }
}

/**
 * [MlsEngine] over risime-mls-ffi (decision 036). Every call runs inside a Room transaction
 * ([inTx] joins the caller's or opens one) so the core's savepoints nest in it. The conversation →
 * generation map lives in the same sealed table (namespace "app"), so it rolls back with MLS state.
 */
class UniffiMlsEngine(
    private val client: MlsClient,
    private val kv: SealedKvStore,
    private val inTx: (() -> Any?) -> Any?,
    override val userId: String,
    override val deviceId: String,
) : MlsEngine {
    /**
     * Serialises every engine and KvStore access of this device (interop SIGSEGV, nightly.8: the
     * store must never be touched from two threads at once). Lock order is always
     * transaction → lock: [inTx] first joins/opens the Room transaction (which itself serialises
     * writers), then the lock; nested calls on the same thread re-enter.
     */
    private val lock = java.util.concurrent.locks.ReentrantLock()

    @Suppress("UNCHECKED_CAST")
    private fun <T> tx(block: () -> T): T = inTx {
        lock.lock()
        try {
            block()
        } finally {
            lock.unlock()
        }
    } as T

    private fun genKey(conv: String) = "gen/$conv".toByteArray()

    private fun generationOf(conv: String): Long? =
        kv.get(APP, genKey(conv))?.let { java.nio.ByteBuffer.wrap(it).long }

    private fun setGeneration(conv: String, gen: Long?) {
        if (gen == null) kv.delete(APP, genKey(conv)) else kv.put(APP, genKey(conv), SealedKvStore.longKey(gen))
    }

    private fun gid(conv: String, gen: Long) = groupId(conv, gen).toByteArray()

    private fun current(conv: String): Pair<Long, ByteArray>? {
        val gen = generationOf(conv) ?: return null
        val g = gid(conv, gen)
        return if (client.hasGroup(g)) gen to g else null
    }

    override fun signatureKey(): ByteArray = tx { client.signaturePublicKey() }

    override fun setAttestation(jws: String) = tx { client.setAttestation(jws) }

    override fun group(conversationId: String): GroupRef? = tx {
        val (gen, g) = current(conversationId) ?: return@tx null
        if (!client.isActive(g)) return@tx null
        GroupRef(conversationId, gen, client.epoch(g).toLong())
    }

    override fun createKeyPackages(count: Int): List<ByteArray> = tx { client.generateKeyPackages(count.coerceIn(1, 100).toUShort()) }

    override fun lastResortKeyPackage(): ByteArray = tx { client.generateLastResortKeyPackage() }

    private fun PendingCommit.toApp(gen: Long) = AppPendingCommit(
        gen, epoch.toLong(), commit, welcome,
        added.map { DeviceRef(it.userId, it.deviceId) }, removed.map { DeviceRef(it.userId, it.deviceId) },
    )

    override fun createGroup(conversationId: String, generation: Long, members: List<ClaimedKeyPackage>): AppPendingCommit = tx {
        val pc = client.createGroup(gid(conversationId, generation), members.map { it.keyPackage })
        setGeneration(conversationId, generation)
        pc.toApp(generation)
    }

    override fun changeMembers(conversationId: String, add: List<ClaimedKeyPackage>, removeDevices: List<DeviceRef>): AppPendingCommit = tx {
        val (gen, g) = current(conversationId) ?: throw IllegalStateException("no group for $conversationId")
        val pc = when {
            add.isNotEmpty() && removeDevices.isEmpty() -> client.addMembers(g, add.map { it.keyPackage })
            removeDevices.isNotEmpty() && add.isEmpty() -> client.removeMembers(g, removeDevices.map { DeviceId(it.userId, it.deviceId) })
            else -> throw IllegalArgumentException("add or remove, one at a time")
        }
        pc.toApp(gen)
    }

    override fun commitAccepted(conversationId: String) = tx {
        val (_, g) = current(conversationId) ?: return@tx
        // A group commit may already be merged from the log (processCommits) when the 200 arrives.
        if (client.hasPendingCommit(g)) client.commitAccepted(g)
        Unit
    }

    override fun commitRejected(conversationId: String) = tx {
        val (_, g) = current(conversationId) ?: return@tx
        if (client.hasPendingCommit(g)) client.commitRejected(g)
        // At epoch 0 the core deletes the group (creation-race loser).
        if (!client.hasGroup(g)) setGeneration(conversationId, null)
    }

    override fun hasPendingCommit(conversationId: String): Boolean = tx {
        val (_, g) = current(conversationId) ?: return@tx false
        client.hasPendingCommit(g)
    }

    override fun processCommit(conversationId: String, generation: Long, commit: ByteArray): CommitOutcome = tx {
        val g = gid(conversationId, generation)
        try {
            if (lk.codegen.risime.net.isGroupConversation(conversationId)) {
                // All-or-nothing catch-up path: also merges our own pending commit found in the log.
                val u = client.processCommits(g, listOf(commit))
                return@tx when {
                    u.removedSelf -> CommitOutcome.RemovedSelf
                    else -> CommitOutcome.Applied(u.epoch.toLong(), u.applied.any { (it as? IncomingMessage.Commit)?.discardedOwnPending == true })
                }
            }
            when (val r = client.process(g, commit)) {
                is IncomingMessage.Commit -> when {
                    r.removedSelf -> CommitOutcome.RemovedSelf
                    else -> CommitOutcome.Applied(r.epoch.toLong(), r.discardedOwnPending)
                }
                IncomingMessage.OwnEcho -> CommitOutcome.Applied(client.epoch(g).toLong())
                is IncomingMessage.Application -> CommitOutcome.Rejected("application message in a commit event")
            }
        } catch (e: RisiMlsException) {
            CommitOutcome.Rejected("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun joinFromWelcome(conversationId: String, generation: Long, welcome: ByteArray): GroupRef = tx {
        val j = client.joinFromWelcome(welcome)
        val expected = gid(conversationId, generation)
        if (!j.groupId.contentEquals(expected)) {
            client.deleteGroup(j.groupId)
            throw IllegalStateException("welcome for another group")
        }
        setGeneration(conversationId, generation)
        GroupRef(conversationId, generation, j.epoch.toLong())
    }

    override fun encrypt(conversationId: String, plaintext: ByteArray): ByteArray = tx {
        val (_, g) = current(conversationId) ?: throw IllegalStateException("no group for $conversationId")
        client.encrypt(g, plaintext)
    }

    override fun decrypt(conversationId: String, generation: Long, ciphertext: ByteArray): Decrypted = tx {
        try {
            // v1.12: processDetailed (same transaction as process) adds the AAD and sender_is_admin (§15.4).
            val p = client.processDetailed(gid(conversationId, generation), ciphertext)
            when (val r = p.incoming) {
                is IncomingMessage.Application -> Decrypted(
                    DeviceRef(r.sender.userId, r.sender.deviceId), r.epoch.toLong(), r.plaintext,
                    p.application?.authenticatedData ?: ByteArray(0), p.application?.senderIsAdmin,
                )
                else -> throw MlsDecryptException("not an application message")
            }
        } catch (e: RisiMlsException.Malformed) {
            throw lk.codegen.risime.data.mls.MlsMalformedException("Malformed: ${e.message}")
        } catch (e: RisiMlsException) {
            throw MlsDecryptException("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun encryptWithAad(conversationId: String, plaintext: ByteArray, aad: ByteArray): ByteArray = tx {
        val (_, g) = current(conversationId) ?: throw IllegalStateException("no group for $conversationId")
        client.encryptWithAad(g, plaintext, aad)
    }

    // §15.1 `deletes`: the app applies delete events (tombstones, hidden tombstones, notification refresh).
    override val deletesSupported: Boolean get() = true

    override fun deleteGroup(conversationId: String) = tx {
        current(conversationId)?.let { (_, g) -> client.deleteGroup(g) }
        setGeneration(conversationId, null)
    }

    // §20.6 (crypto README "Group call keys"): only frame keys cross the FFI; never logged or persisted.
    override val callKeysSupported: Boolean by lazy { runCatching { "risime-call-v1" in lk.codegen.risime.crypto.exporterLabels() }.getOrDefault(false) }

    override fun callFrameKeys(conversationId: String, callId: String): lk.codegen.risime.data.mls.CallKeys = tx {
        val (_, g) = current(conversationId)
            ?: throw lk.codegen.risime.data.mls.CallKeysException(lk.codegen.risime.data.mls.CallKeysException.Kind.UnknownGroup, "no group for $conversationId")
        val k = try {
            client.callFrameKeys(g, callId)
        } catch (e: RisiMlsException.Malformed) {
            throw lk.codegen.risime.data.mls.CallKeysException(lk.codegen.risime.data.mls.CallKeysException.Kind.Malformed, e.message)
        } catch (e: RisiMlsException.UnknownGroup) {
            throw lk.codegen.risime.data.mls.CallKeysException(lk.codegen.risime.data.mls.CallKeysException.Kind.UnknownGroup, e.message)
        } catch (e: RisiMlsException.RemovedFromGroup) {
            throw lk.codegen.risime.data.mls.CallKeysException(lk.codegen.risime.data.mls.CallKeysException.Kind.RemovedFromGroup, e.message)
        } catch (e: RisiMlsException) {
            throw lk.codegen.risime.data.mls.CallKeysException(lk.codegen.risime.data.mls.CallKeysException.Kind.Other, "${e.javaClass.simpleName}: ${e.message}")
        }
        lk.codegen.risime.data.mls.CallKeys(k.epoch.toLong(), k.keyIndex.toInt(), k.keys.map { lk.codegen.risime.data.mls.CallKey(it.identity, it.key) })
    }

    override fun members(conversationId: String): List<DeviceRef> = tx {
        val (_, g) = current(conversationId) ?: return@tx emptyList()
        client.members(g).map { DeviceRef(it.userId, it.deviceId) }
    }

    // ---- §12 groups (risime-mls-ffi group API, crypto/README "Group API") ----

    override val groupsSupported: Boolean get() = true

    // v1.14 §12.1: what the bundled core enforces (`member_devices` from the §12.4a core).
    override val coreCapabilities: Set<String> by lazy { runCatching { lk.codegen.risime.crypto.coreCapabilities().toSet() }.getOrDefault(emptySet()) }

    /** A core policy refusal becomes [lk.codegen.risime.data.mls.MlsPolicyException] (reported, not retried). */
    private inline fun <T> policy(block: () -> T): T = try {
        block()
    } catch (e: RisiMlsException.PolicyViolation) {
        throw lk.codegen.risime.data.mls.MlsPolicyException(e.message ?: "policy violation")
    }

    private fun GroupCommit.toApp(gen: Long) = AppPendingCommit(
        gen, epoch.toLong(), commit, welcome,
        added.map { DeviceRef(it.userId, it.deviceId) }, removed.map { DeviceRef(it.userId, it.deviceId) }, metaChanged,
    )

    // v1.24 §24.1: tab/chat_id/agents pass through as given (null = carried over by the core on updates).
    private fun lk.codegen.risime.net.GroupMeta.toFfi() = GroupMeta(name, icon?.toString(), admins, tab, chatId, agents, chatKind)

    private fun currentOrThrow(conv: String) = current(conv) ?: throw IllegalStateException("no group for $conv")

    override fun createGroupWithMeta(
        conversationId: String,
        generation: Long,
        members: List<ClaimedKeyPackage>,
        meta: lk.codegen.risime.net.GroupMeta,
    ): AppPendingCommit = tx {
        val pc = client.createGroupWithMeta(gid(conversationId, generation), members.map { it.keyPackage }, meta.toFfi())
        setGeneration(conversationId, generation)
        pc.toApp(generation)
    }

    override fun changeGroupMembers(conversationId: String, add: List<ClaimedKeyPackage>, removeDevices: List<DeviceRef>): AppPendingCommit = tx {
        val (gen, g) = currentOrThrow(conversationId)
        policy { client.changeMembers(g, add.map { it.keyPackage }, removeDevices.map { DeviceId(it.userId, it.deviceId) }) }.toApp(gen)
    }

    override fun removeGroupUsers(conversationId: String, userIds: List<String>): AppPendingCommit = tx {
        val (gen, g) = currentOrThrow(conversationId)
        policy { client.removeUsers(g, userIds) }.toApp(gen)
    }

    override fun updateGroupMeta(conversationId: String, meta: lk.codegen.risime.net.GroupMeta): AppPendingCommit = tx {
        val (gen, g) = currentOrThrow(conversationId)
        policy { client.updateGroupMeta(g, meta.toFfi()) }.toApp(gen)
    }

    override fun groupMeta(conversationId: String): lk.codegen.risime.net.GroupMeta? = tx {
        val (_, g) = current(conversationId) ?: return@tx null
        client.groupMeta(g)?.let { lk.codegen.risime.net.GroupMeta(name = it.name, icon = null, admins = it.admins, tab = it.tab, chatId = it.chatId, agents = it.agents, chatKind = it.chatKind) }
    }

    // v1.24: this core (risime-mls with policy::check_tab_policy) enforces §24.1 itself.
    override val tabsSupported: Boolean get() = true

    override val risiChatSupported: Boolean get() = true

    override fun agentUsers(conversationId: String): Set<String> = tx {
        val (_, g) = current(conversationId) ?: return@tx emptySet()
        client.members(g).filter { it.kind == "agent" }.map { it.userId.lowercase() }.toSet()
    }

    // ---- §17 history sharing (risime-mls-ffi history API): rsk and K stay in the core ----

    override val historySupported: Boolean by lazy { runCatching { historyLimits().maxParts > 0u }.getOrDefault(false) }

    private inline fun <T> hist(block: () -> T): T = try {
        block()
    } catch (e: RisiHistoryException) {
        throw e.toApp()
    }

    override fun historyKeygen(requestId: String): ByteArray = tx { hist { client.historyKeygen(requestId) } }

    override fun historyPublicKey(requestId: String): ByteArray? = tx { hist { client.historyPublicKey(requestId) } }

    override fun historyOpen(
        requestId: String,
        ctx: lk.codegen.risime.data.mls.HistoryCtx,
        hpkeEnc: ByteArray,
        sealedKey: ByteArray,
        blob: java.io.File,
    ): ByteArray = tx { hist { client.historyOpen(requestId, ctx.toFfi(), hpkeEnc, sealedKey, blob.absolutePath) } }

    override fun historyForget(requestId: String) = tx { hist { client.historyForget(requestId) } }

    override fun historyOpenRequests(): List<String> = tx { hist { client.historyOpenRequests() } }

    override fun historySender(conversationId: String, sender: DeviceRef): lk.codegen.risime.data.mls.HistorySenderInfo = tx {
        val (_, g) = current(conversationId) ?: throw lk.codegen.risime.data.mls.HistoryException(lk.codegen.risime.data.mls.HistoryException.Kind.Malformed, "no group")
        try {
            val s = client.historySender(g, DeviceId(sender.userId, sender.deviceId))
            lk.codegen.risime.data.mls.HistorySenderInfo(DeviceRef(s.device.userId, s.device.deviceId), s.own, s.signatureKey)
        } catch (e: RisiMlsException) {
            throw lk.codegen.risime.data.mls.HistoryException(lk.codegen.risime.data.mls.HistoryException.Kind.Malformed, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // §22 backups: the same transaction + lock as every other core call (BK lives in mls_kv).
    override val backupKeys: lk.codegen.risime.data.backup.BackupKeys by lazy {
        UniffiBackupKeys(client) { block -> tx { block() } }
    }

    override fun appStateGet(key: String): ByteArray? = tx { kv.get(APP, "state/$key".toByteArray()) }

    override fun appStatePut(key: String, value: ByteArray?) = tx {
        if (value == null) kv.delete(APP, "state/$key".toByteArray()) else kv.put(APP, "state/$key".toByteArray(), value)
    }

    private companion object {
        const val APP = "app"
    }
}

/** Found by name from main ([MlsEngineFactory.get]); absent in builds without the toolchain. */
class UniffiMlsEngineFactory : MlsEngineFactory {
    override fun open(sql: KvSql, sealer: KvSealer, inTransaction: (() -> Any?) -> Any?, userId: String, deviceId: String, trustedKeysJwks: List<String>): MlsEngine {
        val kv = SealedKvStore(sql, sealer)
        val client = inTransaction { MlsClient.open(SealedFfiKvStore(kv), userId, deviceId, trustedKeysJwks) } as MlsClient
        return UniffiMlsEngine(client, kv, inTransaction, userId, deviceId)
    }
}
