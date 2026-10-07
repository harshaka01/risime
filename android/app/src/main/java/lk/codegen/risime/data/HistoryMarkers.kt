package lk.codegen.risime.data

import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.groups.SystemLine
import java.time.Instant

/**
 * §13.3 (decision 043): local system lines that make lost history visible. One row per chat per
 * kind (deterministic `client_msg_id`), forward-only position, never sent, acked, unread or notified.
 */
object HistoryMarkers {
    fun historyId(conversationId: String) = "sys:history:$conversationId"

    fun undecryptableId(conversationId: String) = "sys:undecryptable:$conversationId"

    /** §17.12 the one-per-conversation block header of imported history. */
    fun sharedId(conversationId: String) = "sys:history-shared:$conversationId"

    /** A local system row with explicit text and position (the §17.12 marker exception). */
    fun line(id: String, conversationId: String, action: String, text: String, localTs: Long): MessageEntity = MessageEntity(
        clientMsgId = id,
        messageId = null,
        conversationId = conversationId,
        from = "",
        to = conversationId,
        body = text,
        serverTs = null,
        localTs = localTs,
        status = MessageStatus.READ.name,
        outgoing = false,
        ackedStatus = MessageStatus.READ.name,
        kind = MessageEntity.KIND_SYSTEM,
        systemJson = SystemLine(action, "").encode(),
    )

    /** An ISO-8601 server timestamp in epoch ms, or null if it doesn't parse. */
    fun epochMs(ts: String?): Long? = ts?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /**
     * The marker row. It sits just after [serverTs] (R4: lists order by `local_ts`); without a
     * parsable [serverTs] it takes [fallbackLocalTs].
     */
    fun row(conversationId: String, action: String, serverTs: String?, fallbackLocalTs: Long): MessageEntity {
        val text = if (action == SystemLine.HISTORY_GAP) SystemLine.HISTORY_GAP_TEXT else SystemLine.UNDECRYPTABLE_TEXT
        val id = if (action == SystemLine.HISTORY_GAP) historyId(conversationId) else undecryptableId(conversationId)
        val ms = epochMs(serverTs)
        return MessageEntity(
            clientMsgId = id,
            messageId = null,
            conversationId = conversationId,
            from = "",
            to = conversationId,
            body = text,
            serverTs = serverTs.takeIf { ms != null },
            localTs = ms?.plus(1) ?: fallbackLocalTs,
            status = MessageStatus.READ.name,
            outgoing = false,
            ackedStatus = MessageStatus.READ.name,
            kind = MessageEntity.KIND_SYSTEM,
            systemJson = SystemLine(action, "").encode(),
        )
    }
}
