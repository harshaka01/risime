package ai.risicloud.risime.calls.transcribe

import ai.risicloud.risime.net.CallTranscribeConsent
import ai.risicloud.risime.net.CallTranscribeErrors
import ai.risicloud.risime.net.CallTranscribePush
import ai.risicloud.risime.net.CallTranscribeSession
import ai.risicloud.risime.net.CallTranscribeStates

/** The call this phone holds, as §36 sees it (v1.45 §40.9: also a group call on an Official `grp:`). */
data class TranscribeCall(
    val callId: String,
    /** The 1:1 peer; for a group call its starter. */
    val peer: String,
    /** §36.1: the call's offer said `"tab": "official"` (or this phone placed it from the Official tab). */
    val official: Boolean,
    /** The chat's Official `grp:` (null: this phone holds none, so no transcription). */
    val officialConversation: String?,
    /** Connected (ACTIVE or RECONNECTING): the only time [Transcribe] shows and audio is recorded. */
    val live: Boolean,
    val muted: Boolean = false,
    /** v1.45 §40.9 a §20 group call (participants come from the room on the server). */
    val group: Boolean = false,
    /** v1.45 §40.9 nobody else is in the room (a group hang-up sends `stop ended` only then). */
    val alone: Boolean = true,
)

/** The peer's request on screen: "<name> wants Risi to transcribe this call." */
data class TranscribePrompt(val sessionId: String, val requestedBy: String, val compare: Boolean, val expiresAt: String?)

/** A short line on the call screen after something happened. */
data class TranscribeNotice(val code: String, val by: String? = null)

/** What the call screen shows for §36. */
data class TranscribeView(
    val button: Boolean = false,
    /** "Asking <peer>…" (my request waits for the peer). */
    val asking: Boolean = false,
    val prompt: TranscribePrompt? = null,
    /** The red "Risi transcribing" bar (fails toward showing). */
    val bar: Boolean = false,
    val compare: Boolean = false,
    val notice: TranscribeNotice? = null,
    /** v1.45 §40.9 the session is `paused`: "Paused until <names> allow" (user ids still pending). */
    val pausedFor: List<String> = emptyList(),
)

/** What the recorder does now ([paused]: v1.45 §40.9, record nothing but keep the session's `seq`). */
data class RecordTarget(val sessionId: String, val callId: String, val startedAt: String?, val conversationId: String, val paused: Boolean = false)

/**
 * §36.2/§36.6 the phone's side of the consent state machine. Pure; every input returns the pushes to send.
 * The server owns the state; this keeps what the screen shows and whether this phone records:
 * - Private calls: no button, and any request for them is declined at once with `private_call`.
 * - The bar shows from an `on` until a final state is seen (signal, `GET`, or `409 stopped`).
 * - Recording runs only while the session is `on`, the call is Official and live.
 */
class TranscribeConsentMachine(private val me: String) {
    var call: TranscribeCall? = null
        private set
    var session: CallTranscribeSession? = null
        private set

    /** The session whose `on` this phone saw and whose final state it has not seen yet. */
    private var barSession: String? = null

    /** Sessions this phone knows are over (from a 409 stopped / any final), never revived by a stale signal. */
    private val finals = HashSet<String>()

    /** A request push is in flight (the button waits). */
    private var requesting = false
    private var switchOn = false
    private var capable = false
    private var notice: TranscribeNotice? = null

    /** §36.1 `/auth/config` `call_transcribe` and this device advertising `call_transcribe`. */
    fun setAvailable(switchOn: Boolean, capable: Boolean) {
        this.switchOn = switchOn
        this.capable = capable
    }

    private fun meIs(id: String?) = id != null && id.equals(me, true)

    /** The call changed (or is gone: [c] null). A new call never inherits anything (§36.2). Returns pushes. */
    fun onCall(c: TranscribeCall?): List<CallTranscribePush> {
        val prev = call
        val out = ArrayList<CallTranscribePush>()
        if (prev != null && (c == null || c.callId != prev.callId)) {
            // Hang-up: stop with `ended` before teardown (§36.2), for a live session of this call.
            hangUpPush(prev)?.let { out += it }
            reset()
        }
        call = c
        return out
    }

    /** The call is ending on this phone (End tapped / the call is over): the stop to send, if any. */
    fun onHangUp(): List<CallTranscribePush> {
        val c = call ?: return emptyList()
        val p = hangUpPush(c) ?: return emptyList()
        session = session?.copy(state = CallTranscribeStates.STOPPED)
        session?.sessionId?.let { finals += it }
        barSession = null
        return listOf(p)
    }

    private fun hangUpPush(c: TranscribeCall): CallTranscribePush? {
        val s = session ?: return null
        if (!c.official || s.callId != c.callId || s.final || s.sessionId in finals) return null
        // v1.45 §40.9: leaving a group call stops nothing while others are still in it.
        if (c.group && !c.alone) return null
        val conv = c.officialConversation ?: s.conversationId
        return CallTranscribePush.stop(c.callId, conv, s.sessionId, CallTranscribeStates.REASON_ENDED, discard = false)
    }

    private fun reset() {
        session = null
        barSession = null
        finals.clear()
        requesting = false
        notice = null
    }

    /**
     * A session from the signal, a push reply or `GET` (§36.3). Returns pushes (the automatic Private decline).
     * A session of another call is ignored (this phone doesn't hold that call).
     */
    fun onSession(s: CallTranscribeSession): List<CallTranscribePush> {
        val c = call ?: return emptyList()
        if (!s.callId.equals(c.callId, true)) return emptyList()
        if (!c.official) {
            // §36.1: a phone that holds the call as Private declines any request, without asking, and shows nothing.
            return if (s.state == CallTranscribeStates.REQUESTED && s.consentOf(me) == CallTranscribeConsent.PENDING) {
                listOf(CallTranscribePush.decline(s.callId, s.conversationId, s.sessionId, CallTranscribeStates.REASON_PRIVATE_CALL))
            } else {
                emptyList()
            }
        }
        val cur = session
        // A final state is final: an older `on` arriving late never brings the bar back for that session.
        if (s.sessionId in finals && !s.final) return emptyList()
        if (cur != null && cur.sessionId == s.sessionId && cur.final && !s.final) return emptyList()
        if (meIs(s.requestedBy)) requesting = false
        session = s
        when {
            s.state == CallTranscribeStates.ON -> {
                barSession = s.sessionId
                notice = null
            }
            // v1.45 §40.9: a joiner paused it: the bar stays (fails toward showing).
            s.state == CallTranscribeStates.PAUSED -> if (barSession == null) barSession = s.sessionId
            s.final -> {
                finals += s.sessionId
                if (barSession == s.sessionId || barSession == null) barSession = null
                notice = finalNotice(s)
            }
        }
        return emptyList()
    }

    private fun finalNotice(s: CallTranscribeSession): TranscribeNotice? = when (s.state) {
        // Nobody needs a line for their own action or for a hang-up.
        CallTranscribeStates.DECLINED -> s.participants.firstOrNull { it.consent == CallTranscribeConsent.DECLINED }?.userId?.takeIf { !meIs(it) }?.let { TranscribeNotice(NOTICE_DECLINED, it) }
        CallTranscribeStates.EXPIRED -> if (meIs(s.requestedBy)) TranscribeNotice(NOTICE_EXPIRED) else null
        CallTranscribeStates.STOPPED -> if (meIs(s.by) || s.by == null && s.reason == CallTranscribeStates.REASON_STOPPED) null else TranscribeNotice(NOTICE_STOPPED, s.by)
        CallTranscribeStates.ENDED -> null
        else -> null
    }

    /** §36.4 `409 stopped` (or another stop from the upload): the session is over for this phone. */
    fun onUploadStopped(code: String) {
        val s = session ?: return
        if (code == CallTranscribeErrors.STOPPED || code == CallTranscribeErrors.TRANSCRIBE_OFF) {
            finals += s.sessionId
            barSession = null
            session = s.copy(state = CallTranscribeStates.STOPPED)
        }
    }

    /** [Transcribe] tapped: the request push (null when it can't be asked now). */
    fun request(): CallTranscribePush? {
        if (!view().button) return null
        val c = call ?: return null
        requesting = true
        notice = null
        return CallTranscribePush.request(c.callId, c.officialConversation ?: return null)
    }

    /** The request push failed: the error line, and the button comes back. */
    fun onRequestFailed(reason: String) {
        requesting = false
        notice = TranscribeNotice(reason)
    }

    fun allow(): CallTranscribePush? = promptPush { c, s -> CallTranscribePush.allow(c.callId, s.conversationId, s.sessionId) }

    fun decline(): CallTranscribePush? = promptPush { c, s -> CallTranscribePush.decline(c.callId, s.conversationId, s.sessionId) }

    private inline fun promptPush(make: (TranscribeCall, CallTranscribeSession) -> CallTranscribePush): CallTranscribePush? {
        val c = call ?: return null
        val s = session ?: return null
        if (!c.official || (s.state != CallTranscribeStates.REQUESTED && s.state != CallTranscribeStates.PAUSED) || s.consentOf(me) != CallTranscribeConsent.PENDING) return null
        return make(c, s)
    }

    /** [Stop] / [Stop and delete transcript]: stops for everyone (§36.0). Also cancels my own request. */
    fun stop(discard: Boolean): CallTranscribePush? {
        val c = call ?: return null
        val s = session ?: return null
        if (s.final || s.sessionId in finals) return null
        return CallTranscribePush.stop(c.callId, s.conversationId, s.sessionId, CallTranscribeStates.REASON_STOPPED, discard)
    }

    fun clearNotice() {
        notice = null
    }

    fun view(): TranscribeView {
        val c = call ?: return TranscribeView()
        if (!c.official) return TranscribeView() // §36.0: a Private call shows nothing at all
        val s = session
        val live = s != null && !s.final && s.sessionId !in finals && (s.state == CallTranscribeStates.REQUESTED || s.state == CallTranscribeStates.ON || s.state == CallTranscribeStates.PAUSED)
        val button = switchOn && capable && c.live && c.officialConversation != null && !live && !requesting && barSession == null
        val asking = (requesting && s?.state != CallTranscribeStates.ON) || (s != null && live && s.state == CallTranscribeStates.REQUESTED && meIs(s.requestedBy))
        // v1.45 §40.9: a joiner of a group call is asked while the session is `paused` (or still `requested`).
        val prompt = s?.takeIf { live && (it.state == CallTranscribeStates.REQUESTED || it.state == CallTranscribeStates.PAUSED) && !meIs(it.requestedBy) && it.consentOf(me) == CallTranscribeConsent.PENDING }
            ?.let { TranscribePrompt(it.sessionId, it.requestedBy ?: c.peer, it.compare, it.expiresAt) }
        return TranscribeView(
            button = button,
            asking = asking,
            prompt = prompt,
            bar = barSession != null,
            compare = s?.compare == true,
            notice = notice,
            pausedFor = s?.takeIf { live && it.state == CallTranscribeStates.PAUSED }?.participants?.filter { it.consent == CallTranscribeConsent.PENDING }?.map { it.userId }.orEmpty(),
        )
    }

    /** §36.4/§36.6: what to record now (null = record nothing, zero the buffers). */
    fun recordTarget(): RecordTarget? {
        val c = call ?: return null
        val s = session ?: return null
        if (!c.official || !c.live) return null
        if (s.sessionId in finals || barSession != s.sessionId) return null
        // v1.45 §40.9: `paused` keeps the recorder (its `seq` goes on) but records and sends nothing.
        if (s.state == CallTranscribeStates.PAUSED) return RecordTarget(s.sessionId, c.callId, s.startedAt, s.conversationId, paused = true)
        if (s.state != CallTranscribeStates.ON) return null
        return RecordTarget(s.sessionId, c.callId, s.startedAt, s.conversationId)
    }

    companion object {
        const val NOTICE_DECLINED = "declined"
        const val NOTICE_EXPIRED = "expired"
        const val NOTICE_STOPPED = "stopped"
    }
}
