package ai.risicloud.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Decision 092 (proposal 2026-10-14-private-transcribe §3) `POST /api/v1/translate`: only the text the user chose to
 * translate, the target language and (when known) the source language. No conversation, no message id.
 */
@Serializable
data class TranslateRequest(
    val text: String,
    val target: String,
    val source: String? = null,
)

/** The reply: the translation, shown only to the person who tapped; `engine` is always `risime-server`. */
@Serializable
data class TranslateReply(
    val text: String,
    val target: String,
    val source: String? = null,
    val engine: String = "risime-server",
)
