package lk.codegen.risime.data

import lk.codegen.risime.net.User

/**
 * Proof that the user confirmed "Log out" (or "Log out and delete chats from this phone") in its
 * confirm dialog. [lk.codegen.risime.AppContainer.logout] requires one: no automatic path (refresh
 * failure, 401, identity check, recomposition) can log out. Only `ui/common/ConfirmLogout.kt`
 * creates it (a unit test greps the call sites). [deleteChats] = the user picked the labelled
 * delete action (decision 050); a plain logout keeps the chats, the MLS state and the device id.
 */
class UserConfirmation private constructor(val deleteChats: Boolean) {
    companion object {
        /** Call only from the confirm dialog's confirm button (see LogoutCallSitesTest). */
        fun fromConfirmDialog(deleteChats: Boolean = false): UserConfirmation = UserConfirmation(deleteChats)
    }
}

/** What [LocalAccount.beforeSignIn] decided. */
enum class SignInDecision { KEEP, WIPED, CANCELLED }

/** Why local chat data is deleted. These are the only reasons (P0 nightly.10, hotfix rules). */
enum class WipeReason {
    /** The user confirmed "Log out and delete chats from this phone" (Settings or the chats menu only). */
    LOGOUT,

    /** The user confirmed a different server: its tokens and data don't belong to the new one. */
    SWITCH_SERVER,

    /** A different account (stable ids that compare unequal) and the user confirmed the switch. */
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
    /**
     * Before a new login is saved. The same (or an uncertain) account keeps everything. A confirmed
     * different account asks first ([confirmSwitch] with the previous and the new display name):
     * yes wipes, no keeps the data and the caller abandons this sign-in. The default never wipes.
     */
    suspend fun beforeSignIn(
        user: User,
        confirmSwitch: suspend (previousName: String?, newName: String) -> Boolean = { _, _ -> false },
    ): SignInDecision {
        val previous = store.lastUserId()
        if (!AccountIds.confirmedDifferent(previous, user.id)) {
            if (previous != null && AccountIds.stable(previous) == null) log("sign-in: previous account id not comparable, data kept")
            return SignInDecision.KEEP
        }
        if (!confirmSwitch(store.lastUserName(), user.displayName)) {
            log("sign-in: a different account, the user kept the chats")
            return SignInDecision.CANCELLED
        }
        log("sign-in: a different account, confirmed: wiping local chats")
        wipe(WipeReason.DIFFERENT_ACCOUNT)
        return SignInDecision.WIPED
    }

    /** Session ended / token rejected / key invalidated: back to sign-in, chats and owner kept. */
    suspend fun signOutKeepData() = store.clearLogin(forgetUser = false)

    /**
     * Decision 050: confirmed plain "Log out". The login goes, everything else stays (chats, MLS
     * state, device id, the owner): the same account signs back in with no rejoin and no gap; a
     * different account is asked first ([beforeSignIn]).
     */
    suspend fun logoutKeepChats() = store.clearLogin(forgetUser = false)

    /** Confirmed "Log out and delete chats from this phone": today's full wipe. */
    suspend fun logoutAndDeleteChats() {
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
