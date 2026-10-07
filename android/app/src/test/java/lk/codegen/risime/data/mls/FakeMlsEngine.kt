package lk.codegen.risime.data.mls

/**
 * Deterministic stand-in for the MLS core (phase A). "Ciphertext" is readable on purpose:
 * `gen|epoch|user/device|plaintext`. Commits are `commit|<note>`; welcomes `welcome|<epoch>`.
 */
class FakeMlsEngine(override val userId: String, override val deviceId: String) : MlsEngine {
    val groups = mutableMapOf<String, GroupRef>()
    private val pendingCommits = mutableMapOf<String, PendingCommit>()
    val processed = mutableListOf<String>()
    var lastAttestation: String? = null

    override fun signatureKey() = ByteArray(32) { 7 }
    override fun setAttestation(jws: String) { lastAttestation = jws }
    override fun group(conversationId: String) = groups[conversationId]
    override fun createKeyPackages(count: Int) = List(count) { "kp$it".toByteArray() }
    override fun lastResortKeyPackage() = "kp-last".toByteArray()

    override fun createGroup(conversationId: String, generation: Long, members: List<ClaimedKeyPackage>): PendingCommit =
        PendingCommit(generation, 0, "commit|create".toByteArray(), "welcome|1".toByteArray(), members.map { it.device }, emptyList())
            .also { pendingCommits[conversationId] = it }

    override fun changeMembers(conversationId: String, add: List<ClaimedKeyPackage>, removeDevices: List<DeviceRef>): PendingCommit {
        val g = groups.getValue(conversationId)
        return PendingCommit(g.generation, g.epoch, "commit|change".toByteArray(), if (add.isEmpty()) null else "welcome|${g.epoch + 1}".toByteArray(),
            add.map { it.device }, removeDevices).also { pendingCommits[conversationId] = it }
    }

    override fun commitAccepted(conversationId: String) {
        pendingMeta.remove(conversationId)?.let { metas[conversationId] = it }
        val pc = pendingCommits.remove(conversationId) ?: if (lk.codegen.risime.net.isGroupConversation(conversationId)) return else error("nothing pending")
        val list = memberLists.getOrPut(conversationId) { mutableListOf() }
        list += pc.added
        list -= pc.removed.toSet()
        groups[conversationId] = GroupRef(conversationId, pc.generation, pc.epoch + 1)
    }

    override fun commitRejected(conversationId: String) {
        pendingMeta.remove(conversationId)
        val pc = pendingCommits.remove(conversationId)
        if (pc != null && pc.epoch == 0L && groups[conversationId] == null) memberLists.remove(conversationId)
    }

    val hasPending: Boolean get() = pendingCommits.isNotEmpty()

    override fun processCommit(conversationId: String, generation: Long, commit: ByteArray): CommitOutcome {
        val note = commit.decodeToString().substringAfter('|')
        processed += note
        val g = groups.getValue(conversationId)
        return when (note) {
            "remove-me" -> CommitOutcome.RemovedSelf
            "bad" -> CommitOutcome.Rejected("attestation")
            else -> CommitOutcome.Applied(g.epoch + 1).also { groups[conversationId] = g.copy(epoch = g.epoch + 1) }
        }
    }

    override fun joinFromWelcome(conversationId: String, generation: Long, welcome: ByteArray): GroupRef {
        val epoch = welcome.decodeToString().substringAfter('|').toLong()
        return GroupRef(conversationId, generation, epoch).also { groups[conversationId] = it }
    }

    override fun encrypt(conversationId: String, plaintext: ByteArray): ByteArray {
        val g = groups.getValue(conversationId)
        return "${g.generation}|${g.epoch}|$userId/$deviceId|${plaintext.decodeToString()}".toByteArray()
    }

    // ---- v1.12 delete controls: "AAD:<b64 aad>#" + the normal fake ciphertext ----
    var deletesOn = false
    override val deletesSupported: Boolean get() = deletesOn

    /** Admin list per (conversation, epoch); default: the current group_meta admins. A null answer in a group = no record. */
    var adminsAt: (String, Long) -> List<String>? = { conv, _ -> metas[conv]?.admins }

    override fun encryptWithAad(conversationId: String, plaintext: ByteArray, aad: ByteArray): ByteArray =
        "AAD:${java.util.Base64.getEncoder().encodeToString(aad)}#".toByteArray() + encrypt(conversationId, plaintext)

    override fun decrypt(conversationId: String, generation: Long, ciphertext: ByteArray): Decrypted {
        val text = ciphertext.decodeToString()
        if (text.startsWith("AAD:")) {
            val aad = java.util.Base64.getDecoder().decode(text.substringAfter("AAD:").substringBefore('#'))
            val inner = decryptPlain(conversationId, text.substringAfter('#').toByteArray())
            val admin = if (lk.codegen.risime.net.isGroupConversation(conversationId)) {
                val list = adminsAt(conversationId, inner.epoch) ?: throw MlsMalformedException("no admin record for epoch ${inner.epoch}")
                list.any { it.equals(inner.sender.userId, true) }
            } else null
            return Decrypted(inner.sender, inner.epoch, inner.plaintext, aad, admin)
        }
        return decryptPlain(conversationId, ciphertext)
    }

    private fun decryptPlain(conversationId: String, ciphertext: ByteArray): Decrypted {
        val parts = ciphertext.decodeToString().split('|', limit = 4)
        if (parts.size != 4) throw MlsDecryptException("garbage")
        val g = groups[conversationId] ?: throw MlsDecryptException("no group")
        val epoch = parts[1].toLong()
        if (epoch < g.epoch - 3) throw MlsDecryptException("epoch too old")
        val (u, d) = parts[2].split('/')
        return Decrypted(DeviceRef(u, d), epoch, parts[3].toByteArray())
    }

    override fun deleteGroup(conversationId: String) {
        groups.remove(conversationId)
    }

    val memberLists = mutableMapOf<String, MutableList<DeviceRef>>()
    override fun members(conversationId: String): List<DeviceRef> = memberLists[conversationId].orEmpty()

    // ---- §12 groups (stand-in for the FFI group calls) ----
    var groupsOn = true
    override val groupsSupported: Boolean get() = groupsOn
    var coreCaps: Set<String> = emptySet()
    override val coreCapabilities: Set<String> get() = coreCaps

    /** When set, the core refuses group-member commits under the policy (§12.4a). */
    var policyRefusal: String? = null
    val metas = mutableMapOf<String, lk.codegen.risime.net.GroupMeta>()
    private val pendingMeta = mutableMapOf<String, lk.codegen.risime.net.GroupMeta?>()
    val groupCommits = mutableListOf<Pair<String, PendingCommit>>()
    var failNextGroupCommit: Exception? = null
    var bigCommit = false

    private fun groupCommit(conv: String, note: String, add: List<DeviceRef>, remove: List<DeviceRef>, meta: lk.codegen.risime.net.GroupMeta?): PendingCommit {
        failNextGroupCommit?.let { failNextGroupCommit = null; throw it }
        val g = groups.getValue(conv)
        return PendingCommit(g.generation, g.epoch, if (bigCommit) ByteArray(70_000) { 1 } else "commit|$note".toByteArray(), if (add.isEmpty()) null else "welcome|${g.epoch + 1}".toByteArray(),
            add, remove, metaChanged = meta != null).also { pendingCommits[conv] = it; pendingMeta[conv] = meta; groupCommits += conv to it }
    }

    override fun createGroupWithMeta(conversationId: String, generation: Long, members: List<ClaimedKeyPackage>, meta: lk.codegen.risime.net.GroupMeta): PendingCommit {
        failNextGroupCommit?.let { failNextGroupCommit = null; throw it }
        return PendingCommit(generation, 0, "commit|create".toByteArray(), if (members.isEmpty()) null else "welcome|1".toByteArray(), members.map { it.device }, emptyList(), metaChanged = true)
            .also { pendingCommits[conversationId] = it; pendingMeta[conversationId] = meta; groupCommits += conversationId to it }
    }

    override fun changeGroupMembers(conversationId: String, add: List<ClaimedKeyPackage>, removeDevices: List<DeviceRef>) =
        policyRefusal?.let { throw MlsPolicyException(it) } ?: groupCommit(conversationId, "change", add.map { it.device }, removeDevices, null)

    override fun removeGroupUsers(conversationId: String, userIds: List<String>) =
        groupCommit(conversationId, "remove-users", emptyList(), members(conversationId).filter { m -> userIds.any { it.equals(m.userId, true) } }, null)

    override fun updateGroupMeta(conversationId: String, meta: lk.codegen.risime.net.GroupMeta) =
        groupCommit(conversationId, "meta", emptyList(), emptyList(), meta)

    override fun groupMeta(conversationId: String) = metas[conversationId]

    companion object {
        fun ciphertext(gen: Long, epoch: Long, user: String, device: String, text: String) =
            java.util.Base64.getEncoder().encodeToString("$gen|$epoch|$user/$device|$text".toByteArray())

        fun b64(s: String): String = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

        /** A delete control's fake ciphertext (base64) with its AAD. */
        fun deleteCiphertext(gen: Long, epoch: Long, user: String, device: String, envelope: ByteArray, aad: ByteArray) =
            java.util.Base64.getEncoder().encodeToString("AAD:${java.util.Base64.getEncoder().encodeToString(aad)}#$gen|$epoch|$user/$device|".toByteArray() + envelope)
    }
}

class FakeMlsPendingDao : lk.codegen.risime.data.db.MlsPendingDao {
    val rows = mutableListOf<lk.codegen.risime.data.db.MlsPendingEntity>()
    override suspend fun add(e: lk.codegen.risime.data.db.MlsPendingEntity) {
        if (rows.none { it.eventId == e.eventId }) rows += e
    }
    override suspend fun forConversation(conversationId: String) = rows.filter { it.conversationId == conversationId }.sortedBy { it.seq }
    override suspend fun remove(eventId: String) { rows.removeAll { it.eventId == eventId } }
    override suspend fun olderGenerations(conversationId: String, generation: Long) =
        rows.filter { it.conversationId == conversationId && it.generation < generation }.sortedBy { it.seq }
    override suspend fun dropOlderGenerations(conversationId: String, generation: Long) {
        rows.removeAll { it.conversationId == conversationId && it.generation < generation }
    }
}
