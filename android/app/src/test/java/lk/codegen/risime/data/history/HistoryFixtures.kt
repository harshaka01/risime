package lk.codegen.risime.data.history

import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.HistoryGapEntity
import lk.codegen.risime.data.db.MessageEntity
import java.time.Instant
import java.util.UUID

object HistoryFixtures {
    const val ME = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    const val PEER = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    const val THIRD = "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f"
    const val GRP = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"

    fun ms(ts: String) = Instant.parse(ts).toEpochMilli()

    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString().let { if (it.length == 20) it.dropLast(1) + ".000Z" else it }

    /** A version-1 (TimeUUID) id at [ms] (what the server's message ids are). */
    fun tuuid(ms: Long, seq: Int = 0): String {
        val t = ms * 10_000 + 0x01B21DD213814000L
        val msb = ((t and 0xffffffffL) shl 32) or (((t shr 32) and 0xffffL) shl 16) or (((t shr 48) and 0x0fffL) or 0x1000L)
        val lsb = (0x8000L shl 48) or (seq.toLong() and 0xffffffffffffL)
        return UUID(msb, lsb).toString()
    }

    fun msg(conv: String, from: String, ts: String, body: String, seq: Int = 0, kind: String = MessageEntity.KIND_TEXT, me: String = ME): MessageEntity {
        val id = tuuid(ms(ts), seq)
        return MessageEntity(
            clientMsgId = "cm-$id", messageId = id, conversationId = conv, from = from, to = conv, body = body, serverTs = ts, localTs = ms(ts),
            status = if (from == me) MessageStatus.SENT.name else MessageStatus.READ.name, outgoing = from == me, kind = kind,
        )
    }

    fun gap(m: MessageEntity, createdAt: Long = 0) = HistoryGapEntity(m.messageId!!, m.conversationId, m.clientMsgId, m.from, m.fromDevice, m.serverTs!!, 1, 0, createdAt)
}
