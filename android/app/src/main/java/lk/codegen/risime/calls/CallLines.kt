package lk.codegen.risime.calls

/**
 * §16.6 the call-history line, from this user's own perspective. Who sent the `call_end` tells the
 * role: `cancelled`/`timeout`/`busy` come from the caller, `declined` from a callee device.
 */
object CallLines {
    const val MISSED = "Missed voice call"
    const val VOICE_CALL = "Voice call"

    /** §19.3 the video lines. */
    const val MISSED_VIDEO = "Missed video call"
    const val VIDEO_CALL = "Video call"

    data class Line(val text: String, val missed: Boolean)

    /** Is this stored line a missed call (voice or video)? */
    fun isMissed(body: String): Boolean = body == MISSED || body == MISSED_VIDEO

    /** Is this stored line a video call's (so "Call back" offers video)? */
    fun isVideo(body: String): Boolean = body.startsWith(VIDEO_CALL) || body == MISSED_VIDEO || body == "Declined video call"

    fun line(reason: String, senderIsMe: Boolean, durationS: Long?, rangUnanswered: Boolean, video: Boolean = false): Line {
        val call = if (video) VIDEO_CALL else VOICE_CALL
        val missed = if (video) MISSED_VIDEO else MISSED
        val kind = if (video) "video" else "voice"
        return when (reason) {
            CallEnvelope.R_HANGUP -> Line(durationS?.let { "$call · ${duration(it)}" } ?: call, false)
            CallEnvelope.R_CANCELLED, CallEnvelope.R_TIMEOUT, CallEnvelope.R_BUSY ->
                if (senderIsMe) Line("$call · No answer", false) else Line(missed, true)
            CallEnvelope.R_DECLINED -> if (senderIsMe) Line("Declined $kind call", false) else Line("$call · Declined", false)
            CallEnvelope.R_FAILED -> if (rangUnanswered) Line(missed, true) else Line("$call · Couldn't connect", false)
            else -> Line(call, false)
        }
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

    /** A new "Missed voice call" line arrived live (not a replay): notify "Missed call from <name>" ("Missed video call from <name>"). */
    fun onMissedCall(conversationId: String, from: String, video: Boolean = false) = Unit

    /** §20.4 a durable `group_call` arrived (after its transaction): e.g. `ended` while ringing stops the ring. */
    suspend fun onGroupCallLine(conversationId: String, from: String, env: GroupCallEnvelope) = Unit
}

/** The app's [CallHooks]: the state machine plus the persisted marks. */
class MachineCallHooks(
    private val machine: () -> CallStateMachine?,
    private val marks: CallMarks,
    private val missed: (conversationId: String, from: String, video: Boolean) -> Unit = { _, _, _ -> },
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

    override fun onMissedCall(conversationId: String, from: String, video: Boolean) = missed(conversationId, from, video)
}
