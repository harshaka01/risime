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
    /** A locked chat: shown as a bare "RisiMe" / "New message" that opens the app, not the chat. */
    val locked: Boolean = false,
)

data class NotifLine(val sender: String, val text: String, val ts: Long)

/** The text of a content-free notification (decision 064: the lock is on, "Show content" off). */
const val LOCKED_CONTENT_TEXT = "New message"

/**
 * Decision 064, "Show content in notifications" off with the fingerprint lock on: the chat's
 * notification keeps its id, count and tap target, but shows no sender, group name or text.
 */
fun redactForLock(n: ChatNotification): ChatNotification = n.copy(
    title = "RisiMe",
    lines = listOf(if (n.count > 1) "${n.count} new messages" else LOCKED_CONTENT_TEXT),
    group = false,
    messages = emptyList(),
)

/**
 * Locked chat (WhatsApp "Lock chat"): "RisiMe" / "New message", no sender or group name, no text, no
 * avatar, no MessagingStyle; the id stays so opening the chat or clearing it removes it; the tap
 * opens the app, never the chat.
 */
fun redactLockedChat(n: ChatNotification): ChatNotification = n.copy(
    title = "RisiMe",
    lines = listOf(LOCKED_CONTENT_TEXT),
    count = 1,
    group = false,
    messages = emptyList(),
    locked = true,
)

const val MAX_LINES = 5

/** §24.9 the tab part of a notification title ("Kamal · Official", "Kamal · 🔒 Private"). */
const val NOTIF_OFFICIAL_SUFFIX = " · Official"
const val NOTIF_PRIVATE_SUFFIX = " · 🔒 Private"

/**
 * §24.9 the title of a chat's notification while tabs are on: the chat's name (the peer for a 1:1,
 * also for its Official, whose group name is empty; else the Private group's name) and the tab.
 * [row] is the conversation's MLS-derived tab row (null: Private, its own chat).
 */
fun tabTitle(conv: String, row: lk.codegen.risime.data.db.ChatTabEntity?, me: String?, names: Map<String, String>, groupNames: Map<String, String>, fallbackPeer: String?): String {
    val official = row?.official == true
    val chat = if (official) row!!.chatId else conv
    val base = if (lk.codegen.risime.net.isGroupConversation(chat)) {
        groupNames[chat] ?: groupNames[chat.lowercase()] ?: lk.codegen.risime.data.groups.GROUP_NAME_PENDING
    } else {
        val peer = me?.let { lk.codegen.risime.net.dmPeer(chat, it) } ?: fallbackPeer
        peer?.let { names[it.lowercase()] } ?: "New message"
    }
    return base + if (official) NOTIF_OFFICIAL_SUFFIX else NOTIF_PRIVATE_SUFFIX
}
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
    /** §24.11: this user (a Risi message notifies only the users in its `notify`). */
    me: String? = null,
    /** §24.9: the tab titles ("Kamal · Official") while tabs are on; [tabRows] = the MLS-derived tab rows. */
    tabsOn: Boolean = false,
    tabRows: Map<String, lk.codegen.risime.data.db.ChatTabEntity>? = null,
): List<ChatNotification> {
    val names = contacts.filter { it.userId != null }.associate { it.userId!!.lowercase() to it.displayName }
    fun titled(n: ChatNotification): ChatNotification =
        if (!tabsOn) n else n.copy(title = tabTitle(n.conversationId, tabRows?.get(n.conversationId.lowercase()), me, names, groupNames, n.peerId))
    return unreadIncoming
        // §16.6: a missed call has its own notification ("Missed call from <name>").
        .filter { !it.outgoing && it.status != "READ" && it.kind != lk.codegen.risime.data.db.MessageEntity.KIND_CALL }
        // §24.9/§24.11: a Risi message for someone else is silent (still unread, never notified).
        .filter { lk.codegen.risime.data.tabs.RisiMessages.notifies(it, me) }
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
                ).let(::titled)
            }
            ChatNotification(
                conversationId = conv,
                peerId = peer,
                title = names[peer.lowercase()] ?: "New message",
                lines = sorted.takeLast(MAX_LINES).map { preview(bodyPreview(it.kind, it.body)) },
                count = sorted.size,
                newestTs = sorted.last().localTs,
            ).let(::titled)
        }
        .sortedByDescending { it.newestTs }
}

/** §14.7 Receiving 9: an image reads "📷 Photo" or "📷 <caption>" (never the image itself). */
fun bodyPreview(kind: String, body: String): String = when {
    kind == lk.codegen.risime.data.db.MessageEntity.KIND_CALL -> "📞 $body" // §16.6 "📞 Missed voice call"
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
        .mapNotNull { r -> myMessage(r.targetMessageId)?.takeIf { it.outgoing && !it.showsAsDeleted }?.let { r to it } }
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

/** §15.6 (android R7) what a silent refresh does with the chat notifications in the shade. */
data class NotificationRefresh(val repost: List<ChatNotification>, val cancel: Set<Int>)

/** Only chats that already have a posted notification are touched: reposted from the new plan, or cancelled when empty. */
fun planNotificationRefresh(activeChatIds: Set<Int>, planById: Map<Int, ChatNotification>): NotificationRefresh =
    NotificationRefresh(
        repost = activeChatIds.mapNotNull { planById[it] }.sortedByDescending { it.newestTs },
        cancel = activeChatIds.filter { it !in planById }.toSet(),
    )
