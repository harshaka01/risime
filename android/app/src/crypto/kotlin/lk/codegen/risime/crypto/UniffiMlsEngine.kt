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
            when (val r = client.process(gid(conversationId, generation), ciphertext)) {
                is IncomingMessage.Application -> Decrypted(DeviceRef(r.sender.userId, r.sender.deviceId), r.epoch.toLong(), r.plaintext)
                else -> throw MlsDecryptException("not an application message")
            }
        } catch (e: RisiMlsException) {
            throw MlsDecryptException("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun deleteGroup(conversationId: String) = tx {
        current(conversationId)?.let { (_, g) -> client.deleteGroup(g) }
        setGeneration(conversationId, null)
    }

    override fun members(conversationId: String): List<DeviceRef> = tx {
        val (_, g) = current(conversationId) ?: return@tx emptyList()
        client.members(g).map { DeviceRef(it.userId, it.deviceId) }
    }

    // ---- §12 groups (risime-mls-ffi group API, crypto/README "Group API") ----

    override val groupsSupported: Boolean get() = true

    private fun GroupCommit.toApp(gen: Long) = AppPendingCommit(
        gen, epoch.toLong(), commit, welcome,
        added.map { DeviceRef(it.userId, it.deviceId) }, removed.map { DeviceRef(it.userId, it.deviceId) }, metaChanged,
    )

    private fun lk.codegen.risime.net.GroupMeta.toFfi() = GroupMeta(name, icon?.toString(), admins)

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
        client.changeMembers(g, add.map { it.keyPackage }, removeDevices.map { DeviceId(it.userId, it.deviceId) }).toApp(gen)
    }

    override fun removeGroupUsers(conversationId: String, userIds: List<String>): AppPendingCommit = tx {
        val (gen, g) = currentOrThrow(conversationId)
        client.removeUsers(g, userIds).toApp(gen)
    }

    override fun updateGroupMeta(conversationId: String, meta: lk.codegen.risime.net.GroupMeta): AppPendingCommit = tx {
        val (gen, g) = currentOrThrow(conversationId)
        client.updateGroupMeta(g, meta.toFfi()).toApp(gen)
    }

    override fun groupMeta(conversationId: String): lk.codegen.risime.net.GroupMeta? = tx {
        val (_, g) = current(conversationId) ?: return@tx null
        client.groupMeta(g)?.let { lk.codegen.risime.net.GroupMeta(name = it.name, icon = null, admins = it.admins) }
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
