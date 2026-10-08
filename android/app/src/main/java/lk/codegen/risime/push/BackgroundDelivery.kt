package lk.codegen.risime.push

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
