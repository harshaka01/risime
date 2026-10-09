package lk.codegen.risime.data.tabs

import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.Group
import lk.codegen.risime.net.RisiChatReply
import lk.codegen.risime.net.TabsErrors

/*
 * Contract v1.25 §25.2: the user's Risi chat, an Official `grp:` with `chat_kind: "risi"` and
 * `chat_id` = its own id; only the user and Risi are in it. Shown only on `risi_tools` devices.
 */

/** What opening the Risi chat came to. */
sealed interface RisiChatOpen {
    data class Ready(val conversationId: String) : RisiChatOpen

    data class Failed(val text: String) : RisiChatOpen
}

/** The chat list's name for it and the composer's hint. */
const val RISI_CHAT_NAME = "Risi"
const val RISI_CHAT_HINT = "Ask Risi…"
const val RISI_CHAT_SUBTITLE = "Only you and Risi are in this chat"
const val RISI_CHAT_EMPTY_PREVIEW = "Ask Risi anything"

fun risiChatErrorText(code: String?): String = when (code) {
    TabsErrors.AGENT_UNAVAILABLE -> "Risi isn't available right now. Try again later."
    "invalid_device" -> "Update RisiMe to chat with Risi on this phone."
    "network" -> "No connection. Try again."
    else -> "Couldn't open your Risi chat. Try again."
}

/**
 * §25.2 first open: `POST /risi/chat` (201 creating, or 200 the existing one), record the group, and
 * when it is still `creating` and this device holds no MLS state for it, queue epoch 0 through the
 * group-op outbox (it survives process death; the op POSTs again, idempotently). Never without
 * `risi_tools` ([enabled]).
 */
class RisiChatOpener(
    private val enabled: () -> Boolean,
    private val create: suspend () -> ApiResult<RisiChatReply>,
    private val applyGroup: suspend (Group) -> Unit,
    private val hasMlsGroup: suspend (String) -> Boolean,
    private val queueEpoch0: suspend (String) -> Unit,
    private val log: (String) -> Unit = {},
) {
    suspend fun open(): RisiChatOpen {
        if (!enabled()) return RisiChatOpen.Failed(risiChatErrorText("invalid_device"))
        val reply = when (val r = create()) {
            is ApiResult.Ok -> r.value
            is ApiResult.Error -> return RisiChatOpen.Failed(risiChatErrorText(r.code))
            is ApiResult.NetworkError -> return RisiChatOpen.Failed(risiChatErrorText("network"))
        }
        val g = reply.group
        applyGroup(g)
        if (g.state == Group.STATE_CREATING && !hasMlsGroup(g.id)) {
            log("risi chat ${g.id}: creating epoch 0")
            queueEpoch0(g.id)
        }
        return RisiChatOpen.Ready(g.id)
    }
}
