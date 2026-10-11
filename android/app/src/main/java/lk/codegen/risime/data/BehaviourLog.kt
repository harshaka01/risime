package lk.codegen.risime.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.db.BehaviourDao
import lk.codegen.risime.data.db.BehaviourEventEntity
import java.security.MessageDigest

/**
 * A6: local-only behaviour log for the future on-device client agent.
 * Never uploaded, no UI in 0.1. Peers are stored only as sha256(peer_id + per-install salt).
 */
class BehaviourLog(
    private val dao: BehaviourDao,
    private val salt: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun appOpen() = log(APP_OPEN, null, null)

    suspend fun chatOpen(peerId: String) = log(CHAT_OPEN, peerHash(peerId), null)

    /** Records message_sent (length only, never the body) and, if this is a reply, reply_latency_ms. */
    suspend fun messageSent(peerId: String, length: Int, replyLatencyMs: Long?) {
        val peer = peerHash(peerId)
        log(MESSAGE_SENT, peer, buildJsonObject { put("length", length) }.toString())
        if (replyLatencyMs != null && replyLatencyMs >= 0) {
            log(REPLY_LATENCY_MS, peer, buildJsonObject { put("ms", replyLatencyMs) }.toString())
        }
    }

    /** §14.9: "image sent" with byte size and dimensions only, never content or caption. */
    suspend fun imageSent(peerId: String, bytes: Long, w: Int, h: Int) =
        log(IMAGE_SENT, peerHash(peerId), buildJsonObject { put("bytes", bytes); put("w", w); put("h", h) }.toString())

    /** §33.16 counts only: forwarded n items to m chats (never content, names or ids). */
    suspend fun forwarded(items: Int, chats: Int) = log(FORWARDED, null, buildJsonObject { put("items", items); put("chats", chats) }.toString())

    /** §33.16 copied n messages. */
    suspend fun copied(n: Int) = log(COPIED, null, buildJsonObject { put("n", n) }.toString())

    /** §33.16 shared n items. */
    suspend fun shared(n: Int) = log(SHARED, null, buildJsonObject { put("n", n) }.toString())

    /** §33.14 "pdf exported" with the page count and the source type only. */
    suspend fun pdfExported(pages: Int, sourceType: String) =
        log(PDF_EXPORTED, null, buildJsonObject { put("pages", pages); put("source", sourceType) }.toString())

    suspend fun peerHash(peerId: String): String = hash(peerId.lowercase(), salt())

    private suspend fun log(type: String, peerHash: String?, meta: String?) {
        dao.insert(BehaviourEventEntity(type = type, peerHash = peerHash, at = clock(), metaJson = meta))
    }

    companion object {
        const val APP_OPEN = "app_open"
        const val CHAT_OPEN = "chat_open"
        const val MESSAGE_SENT = "message_sent"
        const val REPLY_LATENCY_MS = "reply_latency_ms"
        const val IMAGE_SENT = "image_sent"
        const val FORWARDED = "forwarded"
        const val COPIED = "copied"
        const val SHARED = "shared"
        const val PDF_EXPORTED = "pdf_exported"

        fun hash(peerId: String, salt: String): String =
            MessageDigest.getInstance("SHA-256").digest((peerId + salt).toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
