package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * v1.36 §35 Risi Brain v2: the learning opt-out REST bodies and the answer source `memory`.
 * Minimal models; additive only.
 */

/** §35 `PUT /api/v1/risi/learning`: switch Risi's learning on or off for one chat. */
@Serializable
data class RisiLearningPut(
    @SerialName("conversation_id") val conversationId: String,
    val enabled: Boolean,
)

@Serializable
data class RisiLearningChat(
    @SerialName("conversation_id") val conversationId: String,
    val enabled: Boolean,
)

/** §35 the learning reply: the global switch and the per-chat opt-outs. */
@Serializable
data class RisiLearningReply(
    val global: Boolean,
    val chats: List<RisiLearningChat> = emptyList(),
)

object RisiKinds136 {
    /** §35 the `Source` type for a long-term memory hit (`conversation_id`, `message_ids`, `at`); shown as nothing. */
    const val SOURCE_MEMORY = "memory"
}
