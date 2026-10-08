package lk.codegen.risime.data.tabs

/*
 * §24.5/§24.9 calls are per tab: a `call_id` and its room belong to exactly one conversation.
 * - Private 1:1: §16/§19 (peer to peer) on the `dm:`.
 * - Private group: §20 (LiveKit) on its `grp:`.
 * - Official, 1:1 or group: §20 (LiveKit) on the Official `grp:` — never the `dm:`.
 * Call records follow: each call's history line lives in the conversation it was placed in, so the
 * Private and Official tabs each show their own calls.
 */

/** Which call machinery a conversation uses. */
enum class CallPath {
    /** §16/§19 1:1 WebRTC on a `dm:`. */
    PEER_TO_PEER,

    /** §20 LiveKit SFU on a `grp:` (every group, and every Official conversation, 1:1 included). */
    SFU,
}

/** A call to place: the conversation and its path. */
data class CallTarget(val conversationId: String, val path: CallPath)

/** The path of a conversation: a `dm:` is peer to peer, a `grp:` (Private group or any Official) is §20. */
fun callPathOf(conversationId: String): CallPath =
    if (lk.codegen.risime.net.isGroupConversation(conversationId)) CallPath.SFU else CallPath.PEER_TO_PEER

/**
 * The call a chat places from [tab]: Private calls its own conversation (the chat id); Official calls
 * its Official conversation over §20 — null while the chat has no Official conversation on this
 * phone (nothing to call yet: the intro card shows instead).
 */
fun callTargetFor(chatId: String, tab: Tab, officialConversation: String?): CallTarget? = when (tab) {
    Tab.PRIVATE -> CallTarget(chatId, callPathOf(chatId))
    Tab.OFFICIAL -> officialConversation
        ?.takeIf { lk.codegen.risime.net.isGroupConversation(it) } // an Official is always a grp: (§24.1)
        ?.let { CallTarget(it, CallPath.SFU) }
}
