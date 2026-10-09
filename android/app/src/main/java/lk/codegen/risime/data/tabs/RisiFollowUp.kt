package lk.codegen.risime.data.tabs

import lk.codegen.risime.data.db.MessageEntity

/**
 * Follow-ups in Official: after Risi answers a request **I** sent, my next message in that chat within
 * [WINDOW_MS] continues the Risi conversation. The composer shows "Continuing with Risi ×" and sends
 * it as a `risi_request` `ask` (the chip makes it explicit; the server still never parses free text
 * for intent). Anything I send after the answer, or tapping ×, ends it.
 */
object RisiFollowUp {
    const val WINDOW_MS = 3L * 60_000

    const val CHIP_LABEL = "Continuing with Risi"
    const val CHIP_DISMISS = "Send a normal message instead"

    private val ANSWER_KINDS = setOf(RisiKinds.ANSWER, RisiKinds.SUMMARY, RisiKinds.REPORT)

    /** The answer (its client_msg_id) my next message would continue, or null. [messages] in chat order. */
    fun active(messages: List<MessageEntity>, me: String?, nowMs: Long): String? {
        me ?: return null
        val mine = messages.asSequence()
            .filter { it.risiCtl && it.outgoing && it.from.equals(me, true) }
            .mapNotNull { RisiControl.requestIdOf(it.systemJson)?.lowercase() }
            .toSet()
        if (mine.isEmpty()) return null
        val idx = messages.indexOfLast { m ->
            val r = RisiMessages.meta(m) ?: return@indexOfLast false
            r.kind in ANSWER_KINDS && r.requestId?.lowercase() in mine
        }
        if (idx < 0) return null
        val answer = messages[idx]
        if (nowMs - answer.localTs > WINDOW_MS || nowMs < answer.localTs - WINDOW_MS) return null
        // Anything I sent after it (a normal message or another ask) ends the follow-up.
        if (messages.subList(idx + 1, messages.size).any { it.outgoing || it.from.equals(me, true) }) return null
        return answer.clientMsgId
    }
}
