package lk.codegen.risime.push

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/**
 * P0 background delivery (nightly.31). The server pushes only while the user has no live inbox
 * channel (decision 028, `RisiMe.Presence.online?` with its 5-s grace), so the app must not keep a
 * socket the server believes in while it can't act on it:
 *
 *  * the socket stays up for [BACKGROUND_SOCKET_GRACE_MS] after the app leaves the foreground (a
 *    photo picker, a quick app switch) and is then closed cleanly (close frame), so the server
 *    pushes. The grace is shorter than Android's cached-app freezer debounce (10 s): a frozen process
 *    holding an open TCP socket would look online to the server until the heartbeat timeout, and
 *    every message and call in that window would get no push;
 *  * a call (ringing, connecting, active), a push wake-up sync or a history export keeps it up;
 *  * anything that arrives over the socket while the app isn't in the foreground notifies exactly
 *    like a push-woken sync ([shouldNotifyFromSocket]).
 */
const val BACKGROUND_SOCKET_GRACE_MS = 5_000L

/** How long a push wake-up may sync directly inside `onMessageReceived` (FCM's high-priority window is ~10 s). */
const val DIRECT_PUSH_SYNC_MS = 9_000L

/** Debounce for notifications from live socket events (the inserting transaction has committed by then). */
const val SOCKET_NOTIFY_DEBOUNCE_MS = 600L

/**
 * The foreground part of [socketWanted]: in the foreground, or in the grace after it while the
 * screen is on. Screen off closes at once (push-device-test, nightly.31 finding): Doze and OEM
 * savers cut the app's network right after screen-off, so a close sent 5 s later never left the
 * phone and the server kept a dead socket "online" until its 60-s timeout: no push for anything
 * sent in that minute, and nothing showed until the app was opened.
 */
fun foregroundHold(foregroundOrGrace: Boolean, foreground: Boolean, screenOn: Boolean): Boolean =
    foreground || (foregroundOrGrace && screenOn)

/** Should the realtime socket be up? (Signed in / unlocked / not blocked are checked by `shouldConnect`.) */
fun socketWanted(foregroundOrGrace: Boolean, pushSync: Boolean, callActive: Boolean, historyExport: Boolean): Boolean =
    foregroundOrGrace || pushSync || callActive || historyExport

/**
 * The foreground flag with a [graceMs] tail: true while in the foreground and for [graceMs] after
 * leaving it, then false. A process that starts in the background (a push wake-up) is false at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<Boolean>.withBackgroundGrace(graceMs: Long = BACKGROUND_SOCKET_GRACE_MS): Flow<Boolean> = flow {
    var wasForeground = false
    emitAll(
        this@withBackgroundGrace.transformLatest { fg ->
            when {
                fg -> { wasForeground = true; emit(true) }
                !wasForeground -> emit(false)
                else -> { emit(true); delay(graceMs); emit(false) }
            }
        },
    )
}.distinctUntilChanged()

/**
 * The foreground hold as one flow: in the foreground, or in the [graceMs] after leaving it while the
 * screen stays on. A screen-off inside the grace ends it for good (the socket closes at once): a
 * screen-on afterwards (Home → screen off → on within the grace) doesn't reopen it until the app is
 * in the foreground again (no close/reopen churn, no second session the server sees).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun foregroundHoldFlow(
    foreground: Flow<Boolean>,
    screenOn: Flow<Boolean>,
    graceMs: Long = BACKGROUND_SOCKET_GRACE_MS,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
): Flow<Boolean> = flow {
    var graceEnd = Long.MIN_VALUE // no grace open (a process started in the background holds nothing)
    var wasForeground = false
    emitAll(
        combine(foreground, screenOn) { f, on -> f to on }.transformLatest { (fg, on) ->
            when {
                fg -> { wasForeground = true; graceEnd = Long.MIN_VALUE; emit(true) }
                !on -> { graceEnd = Long.MIN_VALUE; wasForeground = false; emit(false) }
                wasForeground || graceEnd != Long.MIN_VALUE -> {
                    // Just left the foreground: the grace starts now, once (a repeated value keeps its end).
                    if (graceEnd == Long.MIN_VALUE) graceEnd = now() + graceMs
                    wasForeground = false
                    val left = graceEnd - now()
                    if (left > 0) { emit(true); delay(left) }
                    graceEnd = Long.MIN_VALUE
                    emit(false)
                }
                else -> emit(false)
            }
        },
    )
}.distinctUntilChanged()

/**
 * Inbox wake-ups vs the direct sync (P0 background delivery): the expedited worker is enqueued first
 * (so a wake-up is never lost), then the direct sync runs. A worker that starts after a direct sync
 * went live (a full catch-up) since its enqueue skips its own socket session (no second session for
 * the same wake-up); its REST follow-ups (friends, owed group ops) still run.
 */
class PushWakeTracker(private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile private var lastDirectLive = Long.MIN_VALUE

    /** The time a wake-up's worker is enqueued (stored in its input data). */
    fun stamp(): Long = clock()

    /** A direct sync's join reached Live (its catch-up applied). */
    fun directLive() {
        val t = clock()
        synchronized(this) { if (t > lastDirectLive) lastDirectLive = t }
    }

    /** The worker enqueued at [enqueuedAt] may skip the socket. */
    fun workerCanSkipSocket(enqueuedAt: Long): Boolean = enqueuedAt > 0 && lastDirectLive >= enqueuedAt
}

/**
 * A message (or reaction) arrived over the socket: post notifications when the app isn't in the
 * foreground. A fresh install's replay never notifies (§13.3 R7).
 */
fun shouldNotifyFromSocket(foreground: Boolean, replayingFresh: Boolean): Boolean = !foreground && !replayingFresh

/** `RisiMe push:` log line for a received FCM data message (no content: the payload has none). */
fun pushReceivedLine(data: Map<String, String>, sentTimeMs: Long?, nowMs: Long): String {
    val kind = data["type"] ?: "unknown"
    val sent = data["ts"]?.toLongOrNull() ?: sentTimeMs?.takeIf { it > 0 }
    val delay = sent?.let { " delay_ms=${nowMs - it}" } ?: ""
    return "RisiMe push: received kind=$kind$delay"
}
