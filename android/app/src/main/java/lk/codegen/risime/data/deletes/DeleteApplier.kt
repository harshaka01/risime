package lk.codegen.risime.data.deletes

import lk.codegen.risime.data.db.DeleteDao
import lk.codegen.risime.data.db.DeletedIdEntity
import lk.codegen.risime.data.db.MessageDao
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.net.DeleteTarget
import lk.codegen.risime.net.MsgDelete
import lk.codegen.risime.net.isGroupConversation

/**
 * §15.4–§15.6 on this device, always inside the caller's transaction (the event's, with the cursor).
 * Files can't be in a transaction (android R10): [purged] collects the image rows whose cached files
 * must go after the commit ([takePurged]).
 */
class DeleteApplier(
    private val dao: DeleteDao,
    private val messages: MessageDao,
    private val images: lk.codegen.risime.data.media.ImageHooks?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val purged = mutableListOf<PurgedMedia>()

    /** The image rows purged since the last call (unlink their files after the commit). */
    @Synchronized
    fun takePurged(): List<PurgedMedia> = purged.toList().also { purged.clear() }

    /** §15.7 the Clear chat watermark (TimeUUID ticks), or null. */
    suspend fun clearedUpto(conversationId: String): Long? = dao.chatState(conversationId)?.clearedUpto

    /** At or before the conversation's watermark: its visible effect is ignored (§15.7). */
    suspend fun cleared(conversationId: String, ticks: Long?): Boolean {
        val upto = clearedUpto(conversationId) ?: return false
        return ticks != null && ticks <= upto
    }

    /**
     * §15.6 the purge of one stored row: it becomes a tombstone in place (or, for "Delete for me",
     * [tombstone] = false, disappears), with its reactions and image key; files after the commit.
     */
    suspend fun purge(row: MessageEntity, by: String, byAdmin: Boolean, tombstone: Boolean = true) {
        row.messageId?.let { dao.deleteReactions(listOf(it)); dao.deleteStars(listOf(it)) }
        if (row.media || row.blobId != null) {
            val files = images?.purgeRow(row.clientMsgId).orEmpty()
            synchronized(this) { purged += PurgedMedia(row.clientMsgId, files) }
        }
        if (tombstone) dao.tombstone(row.clientMsgId, by, byAdmin, clock()) else messages.delete(row.clientMsgId)
    }

    /**
     * Applies an authenticated (E2EE) or server-trusted (plaintext) delete for everyone.
     * [deleter] is the attested user id; [senderIsAdmin] is the core's answer at the control's
     * epoch (groups only). Returns true if anything visible changed.
     */
    suspend fun applyEveryone(
        conversationId: String,
        deleter: String,
        deleteServerTs: String,
        targets: List<DeleteTarget>,
        senderIsAdmin: Boolean?,
        e2ee: Boolean,
        historyBefore: java.time.Instant?,
        me: String,
    ): Boolean {
        val group = isGroupConversation(conversationId)
        val deleteMs = TimeUuid.isoMs(deleteServerTs)
        var changed = false
        for (t in targets) {
            val id = t.messageId.lowercase()
            val local = messages.byMessageId(id) ?: messages.byMessageId(t.messageId)
            if (local != null && !local.clientMsgId.startsWith(PLACEHOLDER)) {
                // §15.4 step 3: a row of another conversation is ignored; system rows and tombstones too.
                if (!local.conversationId.equals(conversationId, true)) { log("delete target $id is of another conversation"); continue }
                if (local.system || local.deleted) continue
                val targetMs = TimeUuid.isoMs(local.serverTs) ?: TimeUuid.epochMs(id)
                if (!DeleteRules.allowed(deleter, local.from, deleteMs, targetMs, group, senderIsAdmin)) {
                    log("delete_unauthorised $id by $deleter")
                    continue
                }
                purge(local, deleter, byAdmin = !deleter.equals(local.from, true))
                changed = true
                continue
            }
            if (local != null) continue // a positioned tombstone already
            if (dao.deletedId(id) != null) continue // already a hidden tombstone (repeats are no-ops)
            if (cleared(conversationId, TimeUuid.ticksOfIso(t.serverTs) ?: TimeUuid.ticks(id))) continue
            val from = t.from
            val ts = t.serverTs
            if (from != null && ts != null) {
                // §15.5: e2ee history before this install is covered by the §13.3 marker.
                val tsMs = TimeUuid.isoMs(ts)
                if (e2ee && historyBefore != null && tsMs != null && tsMs < historyBefore.toEpochMilli()) continue
                if (!DeleteRules.allowed(deleter, from, deleteMs, tsMs, group, senderIsAdmin)) {
                    log("delete_unauthorised $id (placed) by $deleter")
                    continue
                }
                val outgoing = from.equals(me, true)
                messages.insert(
                    MessageEntity(
                        clientMsgId = MessageEntity.placeholderId(id), messageId = id, conversationId = conversationId,
                        from = from, to = conversationId, body = "", serverTs = ts, localTs = tsMs ?: clock(),
                        status = MessageStatus.READ.name, outgoing = outgoing, ackedStatus = MessageStatus.READ.name,
                        kind = MessageEntity.KIND_DELETED, deletedBy = deleter, deletedByAdmin = !deleter.equals(from, true), deletedAt = clock(),
                    ),
                )
                changed = true
            }
            // Hidden tombstone (also behind a placed one: the real message, if it ever comes, is re-judged).
            dao.putDeletedId(DeletedIdEntity(id, conversationId, deleter, group && senderIsAdmin == true, deleteServerTs, MsgDelete.SCOPE_EVERYONE, clock()))
        }
        return changed
    }

    /** What to do with a message arriving for an id with a (hidden) tombstone (§15.6, crypto R3). */
    sealed interface Arrival {
        /** No tombstone (or an unauthorised one, now dropped): store it normally. */
        data object Store : Arrival

        /** Authorised: store as a tombstone (never notified, never unread). */
        data class Tombstone(val by: String, val byAdmin: Boolean) : Arrival

        /** Deleted for me: never stored. */
        data object Drop : Arrival
    }

    /** Called after decrypting a newly arrived message, before it is stored. */
    suspend fun judgeArrival(conversationId: String, messageId: String, from: String, serverTs: String?): Arrival {
        val h = dao.deletedId(messageId.lowercase()) ?: dao.deletedId(messageId) ?: return Arrival.Store
        if (h.scope == MsgDelete.SCOPE_ME) return Arrival.Drop
        val group = isGroupConversation(conversationId)
        val ok = h.conversationId.equals(conversationId, true) &&
            DeleteRules.allowed(h.deletedBy, from, TimeUuid.isoMs(h.deleteServerTs), TimeUuid.isoMs(serverTs) ?: TimeUuid.epochMs(messageId), group, h.deleterIsAdmin.takeIf { group })
        if (ok) return Arrival.Tombstone(h.deletedBy, !h.deletedBy.equals(from, true))
        // Unauthorised: show it normally (never blind hiding), drop the hidden and any placed tombstone.
        log("delete_unauthorised $messageId (re-checked on arrival)")
        dao.removeDeletedId(h.messageId)
        messages.delete(MessageEntity.placeholderId(h.messageId))
        return Arrival.Store
    }

    /** §15.4: a delete the core couldn't verify (Malformed): a small note on the stored targets. */
    suspend fun markUnverified(conversationId: String, targets: List<DeleteTarget>): Boolean =
        dao.markUnverified(conversationId, targets.map { it.messageId.lowercase() }) > 0

    companion object {
        const val PLACEHOLDER = "del:"
    }
}

/** An image whose row was purged: its cached ciphertext and temp files go after the commit (R10). */
class PurgedMedia(val clientMsgId: String, val files: List<java.io.File>)
