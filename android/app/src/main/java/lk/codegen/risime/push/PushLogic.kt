package lk.codegen.risime.push

import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.FriendRequest
import lk.codegen.risime.net.PushPayload

/**
 * Push client rules (contract v1.5 §8, decision 026). The payload is only a wake-up: nothing from it
 * is ever displayed. Notifications are built from the local DB after a normal channel sync.
 */

/** Does this FCM data message ask us to sync? Unknown types are ignored. */
fun isInboxWakeUp(data: Map<String, String>): Boolean = PushPayload.fromData(data)?.isInbox == true

/** One notification per chat (grouped under a summary). */
data class ChatNotification(
    val conversationId: String,
    val peerId: String,
    /** Sender's name from the local contacts (never from the push). */
    val title: String,
    /** Newest last; at most [MAX_LINES]. */
    val lines: List<String>,
    val count: Int,
    val newestTs: Long,
)

const val MAX_LINES = 5
const val PREVIEW_CHARS = 120

/**
 * Unread incoming messages newer than [notifiedUpTo] → one notification per conversation, newest
 * chat first. Chats that are open on screen ([suppressPeer]) are skipped.
 */
fun planChatNotifications(
    unreadIncoming: List<MessageEntity>,
    contacts: List<ContactEntity>,
    notifiedUpTo: Long,
    suppressPeer: String? = null,
): List<ChatNotification> {
    val names = contacts.filter { it.userId != null }.associate { it.userId!!.lowercase() to it.displayName }
    return unreadIncoming
        .filter { !it.outgoing && it.status != "READ" }
        .groupBy { it.conversationId }
        .filter { (_, msgs) -> msgs.any { it.localTs > notifiedUpTo } }
        .mapNotNull { (conv, msgs) ->
            val sorted = msgs.sortedBy { it.localTs }
            val peer = sorted.last().from
            if (suppressPeer != null && peer.equals(suppressPeer, ignoreCase = true)) return@mapNotNull null
            ChatNotification(
                conversationId = conv,
                peerId = peer,
                title = names[peer.lowercase()] ?: "New message",
                lines = sorted.takeLast(MAX_LINES).map { preview(it.body) },
                count = sorted.size,
                newestTs = sorted.last().localTs,
            )
        }
        .sortedByDescending { it.newestTs }
}

fun preview(body: String): String {
    val oneLine = body.replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length <= PREVIEW_CHARS) oneLine else oneLine.take(PREVIEW_CHARS - 1) + "…"
}

/** Incoming friend requests we haven't notified yet (ids remembered between syncs). */
fun newRequests(incoming: List<FriendRequest>, alreadyNotified: Set<String>): List<FriendRequest> =
    incoming.filter { it.id !in alreadyNotified }

fun requestNotificationText(r: List<FriendRequest>): Pair<String, String>? = when (r.size) {
    0 -> null
    1 -> "New friend request" to "${r[0].displayName ?: r[0].phone} wants to be friends on RisiMe"
    else -> "${r.size} new friend requests" to r.joinToString(", ") { it.displayName ?: it.phone }
}

/** Push registration only makes sense with Firebase configured and a signed-in, verified user. */
fun shouldRegisterDevice(pushConfigured: Boolean, signedIn: Boolean, phoneVerified: Boolean, token: String?): Boolean =
    pushConfigured && signedIn && phoneVerified && !token.isNullOrBlank()

/** Ask for POST_NOTIFICATIONS once, after sign-in, on Android 13+; respect a denial. */
fun shouldPromptNotifications(sdk: Int, granted: Boolean, alreadyAsked: Boolean, signedIn: Boolean): Boolean =
    sdk >= 33 && signedIn && !granted && !alreadyAsked
