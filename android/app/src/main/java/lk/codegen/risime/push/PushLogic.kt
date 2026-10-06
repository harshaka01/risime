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
    /** §12: a group conversation (MessagingStyle with sender names; [title] = the group name). */
    val group: Boolean = false,
    /** Group: the lines with their senders, newest last (at most [MAX_LINES]). */
    val messages: List<NotifLine> = emptyList(),
)

data class NotifLine(val sender: String, val text: String, val ts: Long)

const val MAX_LINES = 5
const val PREVIEW_CHARS = 120

/**
 * Unread incoming messages newer than [notifiedUpTo] → one notification per conversation, newest
 * chat first. The chat open on screen ([suppressConversation]) is skipped.
 */
fun planChatNotifications(
    unreadIncoming: List<MessageEntity>,
    contacts: List<ContactEntity>,
    notifiedUpTo: Long,
    suppressConversation: String? = null,
    /** §12: group name per `grp:` conversation (the cached group_meta name). */
    groupNames: Map<String, String> = emptyMap(),
    /** §12: member display names per `grp:` conversation (non-friends included). */
    memberNames: Map<String, Map<String, String>> = emptyMap(),
): List<ChatNotification> {
    val names = contacts.filter { it.userId != null }.associate { it.userId!!.lowercase() to it.displayName }
    return unreadIncoming
        .filter { !it.outgoing && it.status != "READ" }
        .groupBy { it.conversationId }
        .filter { (conv, msgs) -> conv != suppressConversation && msgs.any { it.localTs > notifiedUpTo } }
        .mapNotNull { (conv, msgs) ->
            val sorted = msgs.sortedBy { it.localTs }
            val peer = sorted.last().from
            if (lk.codegen.risime.net.isGroupConversation(conv)) {
                val who = { id: String -> memberNames[conv]?.get(id.lowercase()) ?: names[id.lowercase()] ?: "Someone" }
                val shown = sorted.takeLast(MAX_LINES)
                return@mapNotNull ChatNotification(
                    conversationId = conv,
                    peerId = peer,
                    title = groupNames[conv] ?: lk.codegen.risime.data.groups.GROUP_NAME_PENDING,
                    lines = shown.map { "${who(it.from)}: ${preview(bodyPreview(it.kind, it.body))}" },
                    count = sorted.size,
                    newestTs = sorted.last().localTs,
                    group = true,
                    messages = shown.map { NotifLine(who(it.from), preview(bodyPreview(it.kind, it.body)), it.localTs) },
                )
            }
            ChatNotification(
                conversationId = conv,
                peerId = peer,
                title = names[peer.lowercase()] ?: "New message",
                lines = sorted.takeLast(MAX_LINES).map { preview(bodyPreview(it.kind, it.body)) },
                count = sorted.size,
                newestTs = sorted.last().localTs,
            )
        }
        .sortedByDescending { it.newestTs }
}

/** §14.7 Receiving 9: an image reads "📷 Photo" or "📷 <caption>" (never the image itself). */
fun bodyPreview(kind: String, body: String): String = when {
    kind != lk.codegen.risime.data.db.MessageEntity.KIND_IMAGE -> body
    body.isBlank() -> "📷 Photo"
    else -> "📷 $body"
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

/**
 * §11.2: only an effective `add` on one of MY messages by someone else notifies:
 * "<name> reacted 👍 to: <preview>". Merged into that chat's notification (or a new one).
 */
fun mergeReactionNotifications(
    plan: List<ChatNotification>,
    adds: List<lk.codegen.risime.data.db.ReactionEntity>,
    myMessage: (targetMessageId: String) -> MessageEntity?,
    nameOf: (userId: String) -> String,
    me: String,
    suppressConversation: String? = null,
): List<ChatNotification> {
    val byConv = plan.associateBy { it.conversationId }.toMutableMap()
    adds.filter { it.op == "add" && !it.pending && !it.reactorUserId.equals(me, true) }
        .filter { it.conversationId != suppressConversation }
        .mapNotNull { r -> myMessage(r.targetMessageId)?.takeIf { it.outgoing }?.let { r to it } }
        .sortedBy { it.first.localTs }
        .forEach { (r, target) ->
            val line = "${nameOf(r.reactorUserId)} reacted ${r.emoji} to: ${preview(bodyPreview(target.kind, target.body))}"
            val cur = byConv[r.conversationId]
            byConv[r.conversationId] = if (cur == null) {
                ChatNotification(r.conversationId, r.reactorUserId, nameOf(r.reactorUserId), listOf(line), 1, r.localTs)
            } else {
                cur.copy(lines = (cur.lines + line).takeLast(MAX_LINES), count = cur.count + 1, newestTs = maxOf(cur.newestTs, r.localTs))
            }
        }
    return byConv.values.sortedByDescending { it.newestTs }
}

/** §12.7 S4 notification text. */
fun addedToGroupText(actor: String, groupName: String?): String =
    if (groupName.isNullOrBlank()) "$actor added you to a group" else "$actor added you to $groupName"
