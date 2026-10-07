package lk.codegen.risime.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeReactionDao
import lk.codegen.risime.data.db.HistoryDao
import lk.codegen.risime.data.db.HistoryGapEntity
import lk.codegen.risime.data.db.HistoryPartEntity
import lk.codegen.risime.data.db.HistoryProvideEntity
import lk.codegen.risime.data.db.HistoryRequestEntity
import lk.codegen.risime.data.db.MessageEntity

/** In-memory HistoryDao (same semantics as the Room SQL), over the fake message and reaction tables. */
class FakeHistoryDao(private val messages: FakeMessageDao = FakeMessageDao(), private val reactions: FakeReactionDao? = null) : HistoryDao {
    val gapRows = MutableStateFlow<Map<String, HistoryGapEntity>>(emptyMap())
    val requests = MutableStateFlow<Map<String, HistoryRequestEntity>>(emptyMap())
    val partRows = linkedMapOf<Pair<String, Int>, HistoryPartEntity>()
    val provides = MutableStateFlow<Map<String, HistoryProvideEntity>>(emptyMap())

    override suspend fun insertGap(g: HistoryGapEntity): Long {
        if (g.messageId in gapRows.value) return -1
        gapRows.value = gapRows.value + (g.messageId to g)
        return gapRows.value.size.toLong()
    }

    override suspend fun gaps(conv: String) = gapRows.value.values.filter { it.conversationId == conv }.sortedBy { it.serverTs }
    override suspend fun gap(messageId: String) = gapRows.value[messageId]
    override suspend fun gapCount(conv: String) = gaps(conv).size
    override fun observeGapCount(conv: String): Flow<Int> = gapRows.map { m -> m.values.count { it.conversationId == conv } }
    override fun observeGaps(conv: String): Flow<List<HistoryGapEntity>> = gapRows.map { m -> m.values.filter { it.conversationId == conv } }
    override suspend fun allGaps() = gapRows.value.values.toList()

    override suspend fun deleteGaps(messageIds: List<String>): Int {
        val hit = messageIds.filter { it in gapRows.value }
        gapRows.value = gapRows.value - hit.toSet()
        return hit.size
    }

    override suspend fun deleteConversationGaps(conv: String): Int {
        val hit = gapRows.value.values.filter { it.conversationId == conv }.map { it.messageId }
        return deleteGaps(hit)
    }

    override suspend fun markAsked(conv: String, from: String, to: String, provider: String): Int {
        var n = 0
        gapRows.value = gapRows.value.mapValues { (_, g) ->
            if (g.conversationId == conv && g.serverTs >= from && g.serverTs <= to) { n++; g.copy(askedFrom = provider) } else g
        }
        return n
    }

    override suspend fun upsertRequest(r: HistoryRequestEntity) { requests.value = requests.value + (r.requestId to r) }
    override suspend fun request(requestId: String) = requests.value[requestId]
    override suspend fun openRequest(conv: String) = requests.value.values.filter { it.conversationId == conv && !it.closed }.maxByOrNull { it.createdAt }
    override suspend fun openRequests() = requests.value.values.filter { !it.closed }
    override fun observeLatestRequest(conv: String): Flow<HistoryRequestEntity?> =
        requests.map { m -> m.values.filter { it.conversationId == conv }.maxByOrNull { it.createdAt } }
    override suspend fun latestRequest(conv: String) = requests.value.values.filter { it.conversationId == conv }.maxByOrNull { it.createdAt }

    override suspend fun insertPart(p: HistoryPartEntity): Long {
        val k = p.requestId to p.part
        if (k in partRows) return -1
        partRows[k] = p
        return partRows.size.toLong()
    }

    override suspend fun updatePart(p: HistoryPartEntity) { partRows[p.requestId to p.part] = p }
    override suspend fun part(requestId: String, part: Int) = partRows[requestId to part]
    override suspend fun parts(requestId: String) = partRows.values.filter { it.requestId == requestId }.sortedBy { it.part }
    override suspend fun pendingParts() = partRows.values.filter { it.state == HistoryPartEntity.PENDING }
    override suspend fun deleteParts(requestId: String) { partRows.keys.removeAll { it.first == requestId } }

    override suspend fun upsertProvide(p: HistoryProvideEntity) { provides.value = provides.value + (p.requestId to p) }
    override suspend fun provide(requestId: String) = provides.value[requestId]
    override suspend fun openProvides() = provides.value.values.filter { it.open }.sortedBy { it.createdAt }
    override fun observeOpenProvides(): Flow<List<HistoryProvideEntity>> = provides.map { m -> m.values.filter { it.open }.sortedBy { it.createdAt } }

    override suspend fun provides(state: String) = provides.value.values.filter { it.state == state }.sortedBy { it.createdAt }
    override suspend fun owedAcks() = partRows.values.filter { it.state == HistoryPartEntity.IMPORTED || it.state == HistoryPartEntity.REJECTED }

    override suspend fun exportCandidates(conv: String) = messages.rows.values
        .filter { it.conversationId == conv && it.messageId != null && it.kind in setOf(MessageEntity.KIND_TEXT, MessageEntity.KIND_IMAGE, MessageEntity.KIND_CALL) }
        .sortedByDescending { it.localTs }

    override suspend fun systemRows(conv: String) = messages.rows.values.filter { it.conversationId == conv && it.system }.sortedBy { it.localTs }

    override suspend fun confirmedReactions(conv: String) = reactions?.rows?.values?.filter { it.conversationId == conv && it.confirmedMessageId != null }.orEmpty()

    override suspend fun allRequests() = requests.value.values.toList()
}
