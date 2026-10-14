package ai.risicloud.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Proposal 2026-10-14-send-for-me (to be v1.49 §42): "Send a message for me". The `send_message` client tool
 * (the phone sends the user's own message, E2EE, after one [Send]), the card's `send` display object, the
 * `confirm_write` `edit: {"text"}` and the `message_sent` reply. Additive; behind `risi_send`.
 */

/** §42.2 the recipient as the card and the reply name it. */
@Serializable
data class RisiSendTo(
    val name: String,
    @SerialName("user_id") val userId: String? = null,
)

/** §42.2 a `send_message` (or `schedule_message`) card's display data; `args` stays the exact-match part. */
@Serializable
data class RisiSend(
    val to: RisiSendTo,
    val tab: String = RisiSendTabs.PRIVATE,
    val language: String? = null,
)

object RisiSendTabs {
    const val PRIVATE = "private"
    const val OFFICIAL = "official"
}

/** §42.2 the tool call's args. */
@Serializable
data class SendMessageArgs(
    @SerialName("write_id") val writeId: String,
    @SerialName("conversation_id") val conversationId: String,
    val text: String,
)

/** §42.2 the result: `sent` (the server took it within 10 s) or `queued` (in this phone's outbox). */
@Serializable
data class SendMessageResult(val state: String) {
    companion object {
        const val SENT = "sent"
        const val QUEUED = "queued"
    }
}

object RisiSend149 {
    const val TOOL_SEND_MESSAGE = "send_message"
    const val KIND_MESSAGE_SENT = "message_sent"

    const val BUTTON_SEND = "send"
    const val BUTTON_EDIT = "edit"
    const val BUTTON_SCHEDULE = "schedule"
    const val BUTTON_CANCEL = "cancel"

    /** The only key an `edit` of a `send_message` / `schedule_message` card may carry. */
    const val EDIT_TEXT = "text"

    const val MAX_TEXT_GRAPHEMES = 4096
}

/** Proposal 2026-10-14-permission-fixit (v1.49 §43): "I need your calendar to do that." [Allow calendar]. */
object RisiFixit143 {
    const val KIND_NEEDS_PERMISSION = "needs_permission"
    const val ACTION_GRANTED = "permission_granted"
    const val ACTION_DECLINED = "permission_declined"
    const val BUTTON_ALLOW = "allow"

    const val CALENDAR = "calendar"
    const val CONTACTS = "contacts"
    const val MICROPHONE = "microphone"
    const val NOTIFICATIONS = "notifications"
    const val LOCATION = "location"
    const val ALARMS = "alarms"

    val ACTIONS = setOf(ACTION_GRANTED, ACTION_DECLINED)
}

fun RisiToolCall.sendMessageArgs(): SendMessageArgs? =
    runCatching { ProtocolJson.decodeFromJsonElement(SendMessageArgs.serializer(), args) }.getOrNull()
