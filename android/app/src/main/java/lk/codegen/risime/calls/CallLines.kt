package lk.codegen.risime.calls

/**
 * §16.6 the call-history line, from this user's own perspective. Who sent the `call_end` tells the
 * role: `cancelled`/`timeout`/`busy` come from the caller, `declined` from a callee device.
 */
object CallLines {
    const val MISSED = "Missed voice call"
    const val VOICE_CALL = "Voice call"

    data class Line(val text: String, val missed: Boolean)

    fun line(reason: String, senderIsMe: Boolean, durationS: Long?, rangUnanswered: Boolean): Line = when (reason) {
        CallEnvelope.R_HANGUP -> Line(durationS?.let { "$VOICE_CALL · ${duration(it)}" } ?: VOICE_CALL, false)
        CallEnvelope.R_CANCELLED, CallEnvelope.R_TIMEOUT, CallEnvelope.R_BUSY ->
            if (senderIsMe) Line("$VOICE_CALL · No answer", false) else Line(MISSED, true)
        CallEnvelope.R_DECLINED -> if (senderIsMe) Line("Declined voice call", false) else Line("$VOICE_CALL · Declined", false)
        CallEnvelope.R_FAILED -> if (rangUnanswered) Line(MISSED, true) else Line("$VOICE_CALL · Couldn't connect", false)
        else -> Line(VOICE_CALL, false)
    }

    /** "0:07", "3:12", "1:02:03". */
    fun duration(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}

/** What the chat pipeline hands to the calls layer (ChatEngine calls these after each event's transaction commits). */
interface CallHooks {
    /** Read inside the transaction: did this device ring for [callId] without answering? */
    suspend fun rangUnanswered(callId: String): Boolean

    suspend fun onSignal(s: InboundCall)

    suspend fun onCallEnd(conversationId: String, fromUser: String, fromDevice: String?, end: CallEnvelope.End)

    /** The batch (join/sync page or live burst) is applied: ring for offers still incoming. */
    suspend fun onPageEnd()

    /** A new "Missed voice call" line arrived live (not a replay): notify "Missed call from <name>". */
    fun onMissedCall(conversationId: String, from: String) = Unit
}

/** The app's [CallHooks]: the state machine plus the persisted marks. */
class MachineCallHooks(
    private val machine: () -> CallStateMachine?,
    private val marks: CallMarks,
    private val missed: (conversationId: String, from: String) -> Unit = { _, _ -> },
) : CallHooks {
    override suspend fun rangUnanswered(callId: String): Boolean = marks.get(callId)?.let { it.rang && !it.answered } == true

    override suspend fun onSignal(s: InboundCall) {
        machine()?.onSignal(s)
    }

    override suspend fun onCallEnd(conversationId: String, fromUser: String, fromDevice: String?, end: CallEnvelope.End) {
        val m = machine()
        if (m != null) m.onCallEnd(conversationId, fromUser, fromDevice, end) else marks.put((marks.get(end.callId) ?: CallMark(end.callId)).copy(ended = true))
    }

    override suspend fun onPageEnd() {
        machine()?.onPageEnd()
    }

    override fun onMissedCall(conversationId: String, from: String) = missed(conversationId, from)
}
