package lk.codegen.risime.data.tabs

/*
 * §24.5/§24.9 calls are per tab: a `call_id` and its room belong to exactly one conversation.
 * - Private 1:1: §16/§19 (peer to peer) on the `dm:`.
 * - Private group: §20 (LiveKit) on its `grp:`.
 * - Official 1:1 (v1.33, NEXT-PHASE D1): the same §16/§19 peer-to-peer call on the chat's `dm:` —
 *   never the Official `grp:` (no Risi in the call, the peer's name on the call screen).
 * - Official group: §20 (LiveKit) on the Official `grp:`.
 * Call records follow: each call's history line lives in the conversation it was placed in (a 1:1
 * call from either tab in the `dm:`).
 */

/** Which call machinery a conversation uses. */
enum class CallPath {
    /** §16/§19 1:1 WebRTC on a `dm:`. */
    PEER_TO_PEER,

    /** §20 LiveKit SFU on a `grp:` (every group, Private or Official; never a 1:1's Official tab). */
    SFU,
}

/** A call to place: the conversation and its path. */
data class CallTarget(val conversationId: String, val path: CallPath)

/** The path of a conversation: a `dm:` is peer to peer, a `grp:` (Private group or any Official) is §20. */
fun callPathOf(conversationId: String): CallPath =
    if (lk.codegen.risime.net.isGroupConversation(conversationId)) CallPath.SFU else CallPath.PEER_TO_PEER

/**
 * The call a chat places from [tab]: Private calls its own conversation (the chat id). Official: a 1:1
 * chat (`dm:`) calls its `dm:` peer to peer (v1.33 §24.5); a group calls its Official conversation over
 * §20 — null while the group has no Official conversation on this phone (the intro card shows instead).
 */
fun callTargetFor(chatId: String, tab: Tab, officialConversation: String?): CallTarget? = when {
    tab == Tab.PRIVATE -> CallTarget(chatId, callPathOf(chatId))
    !lk.codegen.risime.net.isGroupConversation(chatId) -> CallTarget(chatId, CallPath.PEER_TO_PEER)
    else -> officialConversation
        ?.takeIf { lk.codegen.risime.net.isGroupConversation(it) } // an Official is always a grp: (§24.1)
        ?.let { CallTarget(it, CallPath.SFU) }
}

/**
 * v1.33 §24.5: the `dm:` a call on [conversationId] must go to instead — set for a 1:1's Official `grp:`
 * (MLS-derived row: Official, `chat_kind` dm, chat id a `dm:`); null for anything else (a `dm:`, a group).
 */
fun dmChatForCall(conversationId: String, rows: Map<String, lk.codegen.risime.data.db.ChatTabEntity>?): String? {
    if (!lk.codegen.risime.net.isGroupConversation(conversationId)) return null
    val row = rows?.get(conversationId.lowercase()) ?: return null
    if (!row.official || row.chatKind != lk.codegen.risime.data.db.ChatTabEntity.KIND_DM) return null
    return row.chatId.takeIf { !lk.codegen.risime.net.isGroupConversation(it) && it.startsWith("dm:") }
}
