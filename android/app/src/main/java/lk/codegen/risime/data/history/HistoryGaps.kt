package lk.codegen.risime.data.history

import lk.codegen.risime.data.HistoryMarkers
import lk.codegen.risime.data.db.HistoryDao
import lk.codegen.risime.data.db.HistoryGapEntity

/** §17.2 the gap index: the range of a request and the prune at `server_ts + 30 days` (the inbox TTL). */
object HistoryGaps {
    const val TTL_MS = 30L * 24 * 3600_000

    /** `[min(server_ts), max(server_ts)]` over the rows (compared as instants), or null without rows. */
    fun range(rows: List<HistoryGapEntity>): Pair<String, String>? {
        val timed = rows.mapNotNull { r -> HistoryMarkers.epochMs(r.serverTs)?.let { it to r.serverTs } }
        if (timed.isEmpty()) return null
        return timed.minBy { it.first }.second to timed.maxBy { it.first }.second
    }

    /** Deletes rows whose `server_ts + 30 days` has passed; returns how many. */
    suspend fun prune(dao: HistoryDao, now: Long): Int {
        val old = dao.allGaps().filter { g -> (HistoryMarkers.epochMs(g.serverTs) ?: Long.MIN_VALUE) + TTL_MS < now }.map { it.messageId }
        if (old.isEmpty()) return 0
        return old.chunked(500).sumOf { dao.deleteGaps(it) }
    }
}
