package ai.risicloud.risime.calls

/**
 * P0 calls (Harsha 2026-10-16 11:42): why the platform ends a call. Every reason goes through
 * [CallManager.endCall]; the machines' own ends (the peer's `call_end`, network loss, the ring and
 * media timeouts) reach the same platform release through the state flow ([CallManager.releasePlatform]).
 */
enum class CallEndReason {
    /** The user tapped End / Decline (call screen, notification, Bluetooth, watch). */
    HANGUP,

    /** Telecom disconnected the call (the system, a car, another app). */
    SYSTEM,

    /** A normal phone call took over (the audio mode watch, or Telecom put the call on hold). */
    CELLULAR,

    /** The app was swiped away or its process is going. */
    BACKGROUND,

    /** The held call wasn't live any more (self-heal before "busy"). */
    STALE,
}

/**
 * P0 calls (2026-10-16): sees a normal phone call take over a connected RisiMe call from the audio
 * mode alone (no READ_PHONE_STATE, android R9). It fires once per call, when the mode turns
 * `MODE_IN_CALL` after this connected call was seen in `MODE_IN_COMMUNICATION` (the mode RisiMe's
 * self-managed call runs in). A phone whose own VoIP calls run in `MODE_IN_CALL` from the start never
 * fires, so a RisiMe call is never ended by its own audio mode.
 */
class CellularWatch(
    private val modeInCall: Int = 2, // AudioManager.MODE_IN_CALL
    private val modeInCommunication: Int = 3, // AudioManager.MODE_IN_COMMUNICATION
) {
    private var callId: String? = null
    private var sawCommunication = false
    private var fired = false

    /**
     * One observation: the call [id] this device holds (null = none), whether it is [connected]
     * (ACTIVE or RECONNECTING) and the audio [mode] now. True = a cellular call took over [id] now.
     */
    fun observe(id: String?, connected: Boolean, mode: Int): Boolean {
        if (id != callId) {
            callId = id
            sawCommunication = false
            fired = false
        }
        if (id == null) return false
        if (connected && mode == modeInCommunication) sawCommunication = true
        if (mode != modeInCall || !sawCommunication || fired) return false
        fired = true
        return true
    }
}
