package lk.codegen.risime.data

import lk.codegen.risime.data.db.ReactionDao
import lk.codegen.risime.data.db.ReactionEntity
import lk.codegen.risime.net.ReactionBody

/** §11.2 ordering: is (ts, id) later than (curTs, curId)? Null current = nothing yet. */
fun isNewer(ts: String, id: String, curTs: String?, curId: String?): Boolean {
    if (curTs == null) return true
    val c = ts.compareTo(curTs)
    return c > 0 || (c == 0 && id > (curId ?: ""))
}

/** One emoji's chip under a bubble. */
data class ReactionChip(val emoji: String, val count: Int, val mine: Boolean, val reactors: List<String>)

/** Effective reactions of one message: users with op "add", grouped by emoji (most used first). */
fun chipsFor(rows: List<ReactionEntity>, meId: String): List<ReactionChip> = rows
    .filter { it.op == ReactionBody.ADD }
    .groupBy { it.emoji }
    .map { (emoji, rs) -> ReactionChip(emoji, rs.map { it.reactorUserId.lowercase() }.distinct().size, rs.any { it.reactorUserId.equals(meId, true) }, rs.map { it.reactorUserId }.distinct()) }
    .sortedWith(compareByDescending<ReactionChip> { it.count }.thenBy { it.emoji })

/**
 * Reaction state (§11.2). [applyConfirmed] folds a server-ordered op in; [tap] records my pending
 * op (the outbox sends the final one after the debounce); [confirmOwn]/[revert] settle a send.
 */
class ReactionStore(private val dao: ReactionDao, private val clock: () -> Long = System::currentTimeMillis) {
    private fun blank(conv: String, target: String, reactor: String, emoji: String) =
        ReactionEntity(conv, target, reactor, emoji, ReactionBody.REMOVE, null, null, null, false, null, 0)

    /** A server-confirmed op (inbox event, decrypted e2ee reaction, or my own send's reply). */
    suspend fun applyConfirmed(conv: String, target: String, reactor: String, emoji: String, op: String, ts: String, messageId: String, clientMsgId: String?): Boolean {
        if (op != ReactionBody.ADD && op != ReactionBody.REMOVE) return false
        if (dao.isReaction(target) > 0) return false // never a reaction to a reaction
        val cur = dao.get(conv, target, reactor, emoji) ?: blank(conv, target, reactor, emoji)
        val newer = isNewer(ts, messageId, cur.confirmedTs, cur.confirmedMessageId)
        val settles = cur.pending && clientMsgId != null && clientMsgId == cur.pendingClientMsgId
        if (!newer && !settles) return false
        val confirmedOp = if (newer) op else cur.confirmedOp
        val next = cur.copy(
            confirmedOp = confirmedOp,
            confirmedTs = if (newer) ts else cur.confirmedTs,
            confirmedMessageId = if (newer) messageId else cur.confirmedMessageId,
            pending = cur.pending && !settles,
            pendingClientMsgId = if (settles) null else cur.pendingClientMsgId,
            op = if (cur.pending && !settles) cur.op else (confirmedOp ?: ReactionBody.REMOVE),
            localTs = clock(),
        )
        dao.upsert(next)
        return newer && next.op == ReactionBody.ADD
    }

    /** My tap: shown at once, sent after the debounce. A new tap = a new client_msg_id. */
    suspend fun tap(conv: String, target: String, me: String, emoji: String, op: String): ReactionEntity {
        val cur = dao.get(conv, target, me, emoji) ?: blank(conv, target, me, emoji)
        val next = cur.copy(op = op, pending = true, pendingClientMsgId = null, localTs = clock())
        dao.upsert(next)
        return next
    }

    /** Before sending: nothing to send if the final op equals what the server already has. */
    fun needsSend(r: ReactionEntity): Boolean = r.op != (r.confirmedOp ?: ReactionBody.REMOVE) || r.pendingClientMsgId != null

    suspend fun assignId(r: ReactionEntity, newId: () -> String): ReactionEntity =
        (r.pendingClientMsgId?.let { r } ?: r.copy(pendingClientMsgId = newId())).also { dao.upsert(it) }

    suspend fun settleNoSend(r: ReactionEntity) = dao.upsert(r.copy(pending = false, op = r.confirmedOp ?: ReactionBody.REMOVE))

    suspend fun confirmOwn(r: ReactionEntity, ts: String, messageId: String) =
        applyConfirmed(r.conversationId, r.targetMessageId, r.reactorUserId, r.emoji, r.op, ts, messageId, r.pendingClientMsgId)

    /** unknown_target / invalid_emoji / …: back to the confirmed state. */
    suspend fun revert(r: ReactionEntity) {
        val cur = dao.get(r.conversationId, r.targetMessageId, r.reactorUserId, r.emoji) ?: return
        if (cur.pendingClientMsgId != r.pendingClientMsgId) return // tapped again meanwhile
        dao.upsert(cur.copy(pending = false, pendingClientMsgId = null, op = cur.confirmedOp ?: ReactionBody.REMOVE, localTs = clock()))
    }
}
