package ai.risicloud.risime.ui.chat

import ai.risicloud.risime.data.mls.E2EE_INFO_TEXT

/*
 * Item 6 (Harsha 2026-10-14, honesty; decision 048, proposal 2026-10-14-official-wording): one table for every
 * encryption marker and wording in the app.
 *
 * - Private (1:1 and group) is end-to-end encrypted between the members' phones only: the lock and
 *   "End-to-end encrypted" / "Messages are end-to-end encrypted" (unchanged since decision 048).
 * - Official (1:1 and group) and the Risi chat are encrypted too, but Risi is a member and Risi's keys live on
 *   RisiMe's server, so the server side can read them. They NEVER show the Private lock or the Private wording:
 *   their marker is the lock with the Risi badge ([EncMarker.OFFICIAL_LOCK]) and the text "Encrypted · Risi can read".
 * - A chat that isn't encrypted yet shows its "Not end-to-end encrypted yet: <reason>" strip, whatever its tab.
 */

/** A conversation, by tab and chat type: what its encryption label depends on. */
enum class EncSurface {
    PRIVATE_DM, PRIVATE_GROUP, OFFICIAL_DM, OFFICIAL_GROUP, RISI_CHAT,

    /**
     * A group call whose tab isn't known yet (this phone's tab table hasn't loaded): decision 048 never falls back to
     * Private. It says a neutral "Encrypted" with no lock, and switches once the table loads.
     */
    GROUP_TAB_UNKNOWN;

    /** Risi is a member (Official, or the Risi chat): Risi, whose keys are on RisiMe's server, can read it. */
    val risiCanRead: Boolean get() = this == OFFICIAL_DM || this == OFFICIAL_GROUP || this == RISI_CHAT

    companion object {
        /** [official]: the conversation is an Official tab; [risiChat]: the user's Risi chat; [group]: a group chat. */
        fun of(official: Boolean, risiChat: Boolean, group: Boolean): EncSurface = when {
            risiChat -> RISI_CHAT
            official -> if (group) OFFICIAL_GROUP else OFFICIAL_DM
            else -> if (group) PRIVATE_GROUP else PRIVATE_DM
        }
    }
}

/** The encryption marker drawn for a conversation. */
enum class EncMarker {
    /** Private only: the plain lock ("🔒"). */
    PRIVATE_LOCK,

    /** Official and the Risi chat: the lock with the Risi badge (never the plain lock). */
    OFFICIAL_LOCK,

    /** Not encrypted yet: no lock; the "Not end-to-end encrypted yet: …" strip says why. */
    NONE,
}

/** What a conversation's encryption state shows: its [marker], the [short] header text and the chat-info [info] text. */
data class EncLabel(val marker: EncMarker, val short: String?, val info: String?)

object EncryptionWording {
    /** Private header / call text (decision 048, unchanged). */
    const val PRIVATE_SHORT = "End-to-end encrypted"

    /** Private chat-info line (decision 048, unchanged). */
    const val PRIVATE_INFO = E2EE_INFO_TEXT

    /** Official and the Risi chat: the header text and the marker's content description. */
    const val OFFICIAL_SHORT = "Encrypted · Risi can read"

    /** The Official marker's spoken description (TalkBack). */
    const val OFFICIAL_LOCK_DESCRIPTION = "Encrypted, Risi can read"

    private const val OFFICIAL_WHY =
        "Messages are encrypted on the way and on the server. Risi, whose keys are on RisiMe's server, can read Official."

    /** Chat info's Official section, a 1:1. */
    const val OFFICIAL_INFO_DM = "$OFFICIAL_WHY Private is only you and the other person."

    /** Chat info's Official section, a group. */
    const val OFFICIAL_INFO_GROUP = "$OFFICIAL_WHY Private is only the people in this chat."

    /** The Risi chat. */
    const val RISI_CHAT_INFO =
        "Messages are encrypted on the way and on the server. Risi, whose keys are on RisiMe's server, reads this chat to answer you."

    /** A group call whose tab isn't known yet: neutral, no lock, never the Private wording. */
    const val UNKNOWN_TAB_CALL_SHORT = "Encrypted"
    const val UNKNOWN_TAB_CALL_INFO = "Encrypted (checking whether Risi is in this chat…)"

    /** Calls: a group's Official call (§20 on the Official `grp:`; Risi is a leaf and can derive the frame keys, §27.7). */
    const val OFFICIAL_GROUP_CALL_SHORT = "Encrypted · Risi can listen"

    /**
     * Calls: a 1:1's Official call is §16/§19 on the `dm:` (Risi has no key there); Risi hears it only through
     * Transcribe, which everyone must allow (§36). Distinct from Private, and still true.
     */
    const val OFFICIAL_DM_CALL_SHORT = "End-to-end encrypted · Official"

    const val OFFICIAL_GROUP_CALL_INFO = "Encrypted (MLS frame keys). Risi is in Official and holds its keys, so it can listen"
    const val OFFICIAL_DM_CALL_INFO = "End-to-end encrypted (MLS-bound DTLS-SRTP). Risi hears it only if everyone allows Transcribe"
    const val PRIVATE_CALL_INFO = "End-to-end encrypted (MLS-bound DTLS-SRTP)"
}

/** A header subtitle starting with this is drawn as the Official lock (lock + Risi badge) + the text (never the emoji). */
const val HEADER_OFFICIAL_LOCK_PREFIX = "🔐 "

/** The label for [surface] in its current state: [encrypted] true (MLS), false (the strip says why). */
fun encryptionLabel(surface: EncSurface, encrypted: Boolean): EncLabel = when {
    !encrypted -> EncLabel(EncMarker.NONE, null, null)
    surface == EncSurface.GROUP_TAB_UNKNOWN -> EncLabel(EncMarker.NONE, EncryptionWording.UNKNOWN_TAB_CALL_SHORT, EncryptionWording.UNKNOWN_TAB_CALL_INFO)
    surface == EncSurface.PRIVATE_DM || surface == EncSurface.PRIVATE_GROUP ->
        EncLabel(EncMarker.PRIVATE_LOCK, EncryptionWording.PRIVATE_SHORT, EncryptionWording.PRIVATE_INFO)
    surface == EncSurface.RISI_CHAT -> EncLabel(EncMarker.OFFICIAL_LOCK, EncryptionWording.OFFICIAL_SHORT, EncryptionWording.RISI_CHAT_INFO)
    surface == EncSurface.OFFICIAL_DM -> EncLabel(EncMarker.OFFICIAL_LOCK, EncryptionWording.OFFICIAL_SHORT, EncryptionWording.OFFICIAL_INFO_DM)
    else -> EncLabel(EncMarker.OFFICIAL_LOCK, EncryptionWording.OFFICIAL_SHORT, EncryptionWording.OFFICIAL_INFO_GROUP)
}

/**
 * A chat header's subtitle (drawn by RisiTopBar: "🔒 " = the plain lock, [HEADER_OFFICIAL_LOCK_PREFIX] = the Official
 * lock). [base] is what the header says besides encryption ("3 members"; a 1:1 Official's "Official").
 * - Private: "🔒 <base>" (a 1:1 without a base: "🔒 End-to-end encrypted"), as before.
 * - Official group: "🔐 Encrypted · Risi can read · 3 members"; Official 1:1 and the Risi chat: "🔐 Encrypted · Risi can read"
 *   (the tab bar / title already say Official / Risi).
 * - Not encrypted: just [base] (the strip under the header says why).
 */
fun headerEncryptionSubtitle(surface: EncSurface, encrypted: Boolean, base: String?): String? {
    val label = encryptionLabel(surface, encrypted)
    return when (label.marker) {
        EncMarker.NONE -> base
        EncMarker.PRIVATE_LOCK -> "🔒 " + (base ?: EncryptionWording.PRIVATE_SHORT)
        EncMarker.OFFICIAL_LOCK ->
            HEADER_OFFICIAL_LOCK_PREFIX + label.short + if (surface == EncSurface.OFFICIAL_GROUP && !base.isNullOrEmpty()) " · $base" else ""
    }
}

/** A call's encryption line on the call screen (null: not verified, nothing is claimed). */
fun callEncryptionLabel(surface: EncSurface, verified: Boolean): Pair<EncMarker, String>? = when {
    !verified -> null
    surface == EncSurface.OFFICIAL_GROUP -> EncMarker.OFFICIAL_LOCK to EncryptionWording.OFFICIAL_GROUP_CALL_SHORT
    surface == EncSurface.OFFICIAL_DM -> EncMarker.OFFICIAL_LOCK to EncryptionWording.OFFICIAL_DM_CALL_SHORT
    surface == EncSurface.RISI_CHAT -> null // Call Risi has its own "Risi hears this call" banner (§41.4), never a lock
    surface == EncSurface.GROUP_TAB_UNKNOWN -> EncMarker.NONE to EncryptionWording.UNKNOWN_TAB_CALL_SHORT
    else -> EncMarker.PRIVATE_LOCK to EncryptionWording.PRIVATE_SHORT
}

/** Call info's "Encryption" value. */
fun callEncryptionInfo(surface: EncSurface, verified: Boolean): String = when {
    surface == EncSurface.RISI_CHAT -> ai.risicloud.risime.calls.risi.CallRisiTexts.NOT_E2EE // Call Risi: never a lock (§41.4)
    !verified -> "Not verified yet"
    surface == EncSurface.OFFICIAL_GROUP -> EncryptionWording.OFFICIAL_GROUP_CALL_INFO
    surface == EncSurface.OFFICIAL_DM -> EncryptionWording.OFFICIAL_DM_CALL_INFO
    surface == EncSurface.GROUP_TAB_UNKNOWN -> EncryptionWording.UNKNOWN_TAB_CALL_INFO
    else -> EncryptionWording.PRIVATE_CALL_INFO
}
