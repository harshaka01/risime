package lk.codegen.risime.data.mls

/**
 * Contract v1.21 §12.12.3 (decision 060): every device whose group state is missing rejoins; a
 * reset is the last resort, decided only from the server's rejoin reply. Never because the user is
 * the only admin, never because the others are offline, never on a timer that just ran out.
 */
enum class RejoinDecision {
    /** Stay "Setting up encryption on this phone…"; sends stay pending; rejoin again later. */
    WAIT,

    /** Nobody can (or will) re-add this device: reset the conversation and rebuild it. */
    RESET,
}

object RejoinRules {
    /** §12.12.3: a group admin resets after this long with no candidate (members may still update). */
    const val NO_CANDIDATE_RESET_MS = 24L * 60 * 60 * 1000

    /** §12.12.2: rejoin again at most this often while a waiting chat is open. */
    const val REFRESH_MS = 15L * 60 * 1000

    /** A kick (open, resume, reconnect) refreshes the reply no sooner than this (10 rejoins/user/min). */
    const val KICK_MIN_MS = 20_000L

    /** After this long with a candidate but no Welcome, say who we wait for. */
    const val WAITING_TEXT_AFTER_MS = 60_000L

    /** DM (§10.6.5): reset only when nobody can ever re-add this device, or everyone who could was asked. */
    fun dm(candidates: Int, exhausted: Boolean): RejoinDecision =
        if (candidates == 0 || exhausted) RejoinDecision.RESET else RejoinDecision.WAIT

    /**
     * Group (§12.12.3): non-admins never reset. An admin device resets when the op is `exhausted`, or
     * when it had no candidate for 24 h after the op's `created_at`. [candidates] null = a pre-v1.21
     * server ("unknown"): never automatically. [opCreatedAtMs] null (no op, or no time): never for
     * "no candidate".
     */
    fun group(iAmAdmin: Boolean, candidates: Int?, exhausted: Boolean, opCreatedAtMs: Long?, now: Long): RejoinDecision = when {
        !iAmAdmin -> RejoinDecision.WAIT
        candidates == null -> RejoinDecision.WAIT
        exhausted && candidates >= 1 -> RejoinDecision.RESET
        candidates == 0 && opCreatedAtMs != null && now - opCreatedAtMs >= NO_CANDIDATE_RESET_MS -> RejoinDecision.RESET
        else -> RejoinDecision.WAIT
    }

    /**
     * Whether the strip names who we're waiting for: the reply had a candidate, but none is online
     * to commit ([committerNamed] false) or nothing landed for a while.
     */
    fun waitingForOthers(candidates: Int?, committerNamed: Boolean, waitingMs: Long): Boolean =
        (candidates ?: 0) >= 1 && (!committerNamed || waitingMs >= WAITING_TEXT_AFTER_MS)

    /** ISO-8601 → epoch ms; null when absent or unparseable. */
    fun epochMs(iso: String?): Long? = iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
}

/** §12.12.3 DM strip while the peer must open the app to re-add this phone. */
fun dmWaitingText(name: String) = "$REPAIRING_TEXT Waiting for $name to open RisiMe"

/** §12.12.3 group strip while a member must open the app to re-add this phone. */
const val GROUP_WAITING_TEXT = "$REPAIRING_TEXT Waiting for a group member to open RisiMe"

/** §12.12.3 the manual reset's confirmation (group info, admins). */
const val RESET_CONFIRM_TEXT = "Members who haven't opened RisiMe recently may lose messages they haven't received yet. Reset?"
