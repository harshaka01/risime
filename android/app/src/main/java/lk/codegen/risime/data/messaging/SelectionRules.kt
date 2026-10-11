package lk.codegen.risime.data.messaging

import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.media.FileMeta
import lk.codegen.risime.data.tabs.RisiMessages

/**
 * v1.34 §33.2 / §33.7 / §33.10: what can be selected, and when each action of the selection bar is
 * enabled. An action that doesn't apply to every selected bubble is disabled (never applied to a subset).
 */
object SelectionRules {
    const val MAX_SELECT = 100
    const val MAX_FORWARD = 30
    const val MAX_SHARE = 10
    const val MAX_TARGETS = 5

    /** Risi cards with buttons: never a Reply target, never forwarded. */
    val BUTTON_KINDS = setOf("confirm", "offer", "calendar_offer", "calendar_invite")

    /** §33.7: meaningless outside their conversation. */
    val NOT_FORWARDABLE_RISI = BUTTON_KINDS + "error"

    /** §33.2 never selectable: tombstones, system / call / undecryptable lines, view-once, controls (except an own `risi_request`). */
    fun selectable(m: MessageEntity): Boolean = when {
        m.showsAsDeleted || m.system || m.call || m.isViewOnce -> false
        m.risiCtl -> m.outgoing && CopyFormat.requestText(m) != null
        else -> true
    }

    fun risiKind(m: MessageEntity): String? = RisiMessages.meta(m)?.kind

    /** Where a photo or file's bytes are (§33.5). */
    enum class MediaAvail {
        /** The verified ciphertext is on the phone (cached, or my own local ciphertext). */
        READY,

        /** Not on the phone but still fetchable (download first, with progress). */
        FETCHABLE,

        /** Past its horizon / 404 / evicted and gone, or a `parts` placeholder. */
        GONE,
    }

    fun mediaAvail(m: MessageEntity, media: MediaEntity?, now: Long): MediaAvail {
        if (m.file && FileMeta.decode(m.systemJson)?.parts == true) return MediaAvail.GONE
        media ?: return MediaAvail.GONE
        if (media.fileName != null && (media.state == MediaState.CACHED.name || media.outgoing)) return MediaAvail.READY
        return when (media.state) {
            MediaState.NONE.name, MediaState.DOWNLOADING.name ->
                if (media.blobId != null && (media.expiresAtEst ?: Long.MAX_VALUE) > now) MediaAvail.FETCHABLE else MediaAvail.GONE
            else -> MediaAvail.GONE
        }
    }

    /** "This photo is no longer available" ("…file…"). */
    fun goneText(m: MessageEntity): String = if (m.file) "This file is no longer available" else "This photo is no longer available"

    /** §33.7 forwardable, or the reason it isn't (null: forwardable). */
    fun forwardBlock(m: MessageEntity, media: MediaEntity?, now: Long): String? {
        if (!selectable(m) || m.risiCtl) return "Can't be forwarded"
        risiKind(m)?.let { k -> if (k in NOT_FORWARDABLE_RISI) return "Risi cards with buttons can't be forwarded" }
        if (m.media && mediaAvail(m, media, now) == MediaAvail.GONE) return goneText(m)
        if (m.status == MessageStatus.FAILED.name && m.messageId == null && !m.media && m.body.isBlank()) return "Can't be forwarded"
        return null
    }

    /** §33.10 shareable: copyable (or media), and its media on the phone or still fetchable. */
    fun shareable(m: MessageEntity, media: MediaEntity?, now: Long): Boolean {
        if (!selectable(m)) return false
        if (m.media) return mediaAvail(m, media, now) != MediaAvail.GONE
        return CopyFormat.copyable(m)
    }

    /** §33.9 a Reply target: a normal message of this conversation with a `message_id`, no buttons. */
    fun replyable(m: MessageEntity): Boolean =
        selectable(m) && !m.risiCtl && m.messageId != null && risiKind(m) !in BUTTON_KINDS

    /** The selection bar's state for [selected] (in chat order). */
    data class Actions(
        val count: Int,
        val copy: Boolean,
        val forward: Boolean,
        val share: Boolean,
        val star: Boolean,
        /** All selected are starred: the action reads "Unstar". */
        val unstar: Boolean,
        val reply: Boolean,
        val info: Boolean,
        /** Why Forward is off (a gone photo), shown on tap. */
        val forwardWhyNot: String? = null,
    )

    fun actions(selected: List<MessageEntity>, me: String, media: Map<String, MediaEntity>, starred: Set<String>, now: Long, canSend: Boolean): Actions {
        val n = selected.size
        val ok = n in 1..MAX_SELECT && selected.all(::selectable)
        val fwBlocks = selected.mapNotNull { forwardBlock(it, media[it.clientMsgId], now) }
        val allStarred = ok && selected.all { it.messageId != null && it.messageId in starred }
        return Actions(
            count = n,
            copy = ok && selected.all { CopyFormat.copyable(it) && (CopyFormat.content(it, single = n == 1) != null || n > 1) },
            forward = ok && n <= MAX_FORWARD && fwBlocks.isEmpty(),
            share = ok && n <= MAX_SHARE && selected.all { shareable(it, media[it.clientMsgId], now) },
            star = ok && selected.all { it.messageId != null },
            unstar = allStarred,
            reply = ok && n == 1 && canSend && replyable(selected.single()),
            info = ok && n == 1 && selected.single().let { it.from.equals(me, true) && it.messageId != null && !it.risiCtl },
            forwardWhyNot = fwBlocks.firstOrNull().takeIf { n <= MAX_FORWARD } ?: if (n > MAX_FORWARD) "Forward up to $MAX_FORWARD messages at a time" else null,
        )
    }
}
