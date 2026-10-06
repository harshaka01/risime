package lk.codegen.risime.data

import lk.codegen.risime.net.User

/** Why local chat data is deleted. These are the only reasons (P0 nightly.10, hotfix rules). */
enum class WipeReason {
    /** The user confirmed "Log out" / "Sign out". */
    LOGOUT,

    /** The user confirmed a different server: its tokens and data don't belong to the new one. */
    SWITCH_SERVER,

    /** A sign-in confirmed as a different account (stable ids that compare unequal). */
    DIFFERENT_ACCOUNT,
}

/**
 * RisiMe user ids are server UUIDs. Compared trimmed and case-insensitively; anything that is not a
 * UUID is "uncertain" and never counts as a different account.
 */
object AccountIds {
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** The comparable form of a user id, or null when it isn't a usable stable id. */
    fun stable(id: String?): String? = id?.trim()?.lowercase()?.takeIf { UUID.matches(it) }

    /** True only when both ids are stable and differ. Unknown or malformed → false (keep the data). */
    fun confirmedDifferent(previous: String?, current: String?): Boolean {
        val p = stable(previous) ?: return false
        val c = stable(current) ?: return false
        return p != c
    }
}

/**
 * The one place that decides whether local chats are deleted. Never on token refresh, an app
 * update, a reconnect, a session that ended, or a re-sign-in of the same account; if the identity
 * is uncertain, the data stays. [wipe] deletes the chat data (messages, contacts, cursor, MLS state).
 */
class LocalAccount(
    private val store: SessionStore,
    private val wipe: suspend (WipeReason) -> Unit,
    private val log: (String) -> Unit = {},
) {
    /** Before a new login is saved: wipes only for a confirmed different account. Returns true if it wiped. */
    suspend fun beforeSignIn(user: User): Boolean {
        val previous = store.lastUserId()
        if (!AccountIds.confirmedDifferent(previous, user.id)) {
            if (previous != null && AccountIds.stable(previous) == null) log("sign-in: previous account id not comparable, data kept")
            return false
        }
        log("sign-in: a different account, wiping local chats")
        wipe(WipeReason.DIFFERENT_ACCOUNT)
        return true
    }

    /** Session ended / token rejected / key invalidated: back to sign-in, chats and owner kept. */
    suspend fun signOutKeepData() = store.clearLogin(forgetUser = false)

    /** Explicit, confirmed logout. */
    suspend fun logout() {
        store.clearLogin(forgetUser = true)
        wipe(WipeReason.LOGOUT)
    }

    /** Explicit, confirmed server change. */
    suspend fun switchServer(url: String) {
        store.clearLoginAndSetServerUrl(url)
        wipe(WipeReason.SWITCH_SERVER)
    }

    /**
     * Recovery (hotfix for nightly.10): replay the server inbox (30-day TTL) once when local history
     * is missing. Resetting only the cursor is safe: already-applied events are deduped by
     * `seen_events` and message ids, so the replay adds only what is missing (lost rows, v1.10
     * sender copies and the backfill). Returns true when the cursor was reset.
     *
     * Triggers: the cursor is set but there are no messages (data lost), or this build's one-time
     * history replay hasn't run yet on this install ([HISTORY_REPLAY_VERSION]).
     */
    suspend fun recoverHistoryIfNeeded(messageCount: suspend () -> Int, cursor: suspend () -> String?, resetCursor: suspend () -> Unit): Boolean {
        val c = cursor() ?: run {
            store.setHistoryReplayVersion(HISTORY_REPLAY_VERSION) // a since:null join is a full replay anyway
            return false
        }
        // Lost: a cursor but no messages. Once per cursor value, so an account with no chats yet
        // doesn't replay on every start.
        val lost = messageCount() == 0 && store.emptyReplayCursor() != c
        val due = store.historyReplayVersion() < HISTORY_REPLAY_VERSION
        if (!lost && !due) return false
        log(if (lost) "history: cursor without messages, replaying the inbox" else "history: one-time inbox replay")
        resetCursor()
        if (lost) store.setEmptyReplayCursor(c)
        store.setHistoryReplayVersion(HISTORY_REPLAY_VERSION)
        return true
    }

    companion object {
        /** Bump to make every install replay its inbox once more (e.g. after a server backfill). */
        const val HISTORY_REPLAY_VERSION = 1
    }
}
