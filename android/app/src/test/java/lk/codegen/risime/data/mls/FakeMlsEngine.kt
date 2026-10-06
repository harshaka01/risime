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
        val pc = pendingCommits.remove(conversationId) ?: error("nothing pending")
        groups[conversationId] = GroupRef(conversationId, pc.generation, pc.epoch + 1)
    }

    override fun commitRejected(conversationId: String) {
        pendingCommits.remove(conversationId)
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

    override fun decrypt(conversationId: String, generation: Long, ciphertext: ByteArray): Decrypted {
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

    companion object {
        fun ciphertext(gen: Long, epoch: Long, user: String, device: String, text: String) =
            java.util.Base64.getEncoder().encodeToString("$gen|$epoch|$user/$device|$text".toByteArray())

        fun b64(s: String): String = java.util.Base64.getEncoder().encodeToString(s.toByteArray())
    }
}

class FakeMlsPendingDao : lk.codegen.risime.data.db.MlsPendingDao {
    val rows = mutableListOf<lk.codegen.risime.data.db.MlsPendingEntity>()
    override suspend fun add(e: lk.codegen.risime.data.db.MlsPendingEntity) {
        if (rows.none { it.eventId == e.eventId }) rows += e
    }
    override suspend fun forConversation(conversationId: String) = rows.filter { it.conversationId == conversationId }.sortedBy { it.seq }
    override suspend fun remove(eventId: String) { rows.removeAll { it.eventId == eventId } }
    override suspend fun dropOlderGenerations(conversationId: String, generation: Long) {
        rows.removeAll { it.conversationId == conversationId && it.generation < generation }
    }
}
