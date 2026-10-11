package lk.codegen.risime.data.messaging

import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.MediaDao
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.db.MessageDao
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.media.FileMeta
import lk.codegen.risime.net.Forwarding
import lk.codegen.risime.net.RisiKinds130

/**
 * v1.34 §33.4 the forwarded envelope a target gets for one source (rebuilt from parsed known fields:
 * `risi`, `reply_to`, `view_once` and unknown fields never travel). Media gets a fresh upload per target.
 */
data class ForwardSpec(
    val kind: String,
    /** text: the body; image / file: the caption ("" without one). */
    val body: String,
    val hops: Int?,
    /** file: its [FileMeta] JSON. */
    val systemJson: String? = null,
    /** image / file: the source row whose plaintext is re-encrypted for each target. */
    val mediaSource: String? = null,
)

/** One target of the forward picker (§33.4: each tab is its own target). */
data class ForwardTarget(
    val conversationId: String,
    val name: String,
    /** "🔒 Private" or "● Official". */
    val official: Boolean,
    val dm: Boolean,
    /** Offered but disabled (null: selectable). */
    val disabledReason: String? = null,
    val lastActivity: Long = 0,
    /** §33.1 a group some of whose members can't see files yet (still selectable). */
    val notice: String? = null,
) {
    val label: String get() = if (official) OFFICIAL_LABEL else PRIVATE_LABEL

    companion object {
        const val PRIVATE_LABEL = "🔒 Private"
        const val OFFICIAL_LABEL = "● Official"
    }
}

/** A candidate conversation for the picker before the §33.4 rules. */
data class ForwardCandidate(
    val conversationId: String,
    val name: String,
    val official: Boolean,
    val risiChat: Boolean,
    val e2ee: Boolean,
    val activeMember: Boolean,
    /** An Official tab whose state is `off` or `none`. */
    val officialOff: Boolean = false,
    /** A DM with a blocked or unfriended user. */
    val blockedOrUnfriended: Boolean = false,
    /** DMs: `images_ready` / `files_ready` (null: not known yet: allowed, the send re-checks). */
    val imagesReady: Boolean? = null,
    val filesReady: Boolean? = null,
    val lastActivity: Long = 0,
)

object ForwardRules {
    const val MANY_TIMES_SNACKBAR = "Messages forwarded many times can be sent to one chat at a time"
    const val GROUP_FILES_NOTICE = "Some members need to update to see files"
    const val HINT_TITLE = "Forward to Official?"
    const val HINT_BODY = "Risi can read messages, photos and files in Official chats. What you forward there is no longer only in Private."
    const val HINT_FORWARD = "Forward"
    const val HINT_CANCEL = "Cancel"
    const val PICKER_TITLE = "Forward to…"
    const val SEARCH_HINT = "Search"
    const val RECENT = "Recent chats"
    const val ALL = "All chats"
    const val ADD_MESSAGE = "Add a message"
    const val SEND = "Send"

    fun done(n: Int) = if (n == 1) "Forwarded to 1 chat" else "Forwarded to $n chats"

    /** At most 5 targets; 1 when any source is "Forwarded many times". */
    fun maxTargets(sources: List<MessageEntity>): Int =
        if (sources.any { (it.forwardHops ?: 0) >= Forwarding.MANY_TIMES }) 1 else SelectionRules.MAX_TARGETS

    /**
     * §33.4 the targets: the Risi chat, non-e2ee conversations, ones I'm no longer in, Official tabs
     * that are off/none and blocked/unfriended DMs are not offered; a DM that can't take the selection's
     * media is offered disabled with the reason.
     */
    fun targets(candidates: List<ForwardCandidate>, hasImage: Boolean, hasFile: Boolean): List<ForwardTarget> =
        candidates.filter { !it.risiChat && it.e2ee && it.activeMember && !(it.official && it.officialOff) && !it.blockedOrUnfriended }
            .map { c ->
                val dm = c.conversationId.startsWith("dm:")
                val why = when {
                    dm && hasImage && c.imagesReady == false -> "${c.name} needs to update the app to receive photos"
                    dm && hasFile && c.filesReady == false -> "${c.name} needs to update the app to receive files"
                    else -> null
                }
                // §33.1 groups: sending is allowed, with a one-line notice.
                val notice = if (!dm && hasFile && c.filesReady == false) GROUP_FILES_NOTICE else null
                ForwardTarget(c.conversationId, c.name, c.official, dm, why, c.lastActivity, notice)
            }

    /** The picker's lists: "Recent chats" (by last activity, at most [recent]) then all chats A–Z; [query] filters by name. */
    fun lists(targets: List<ForwardTarget>, query: String, recent: Int = 6): Pair<List<ForwardTarget>, List<ForwardTarget>> {
        val q = query.trim().lowercase()
        val matching = targets.filter { q.isEmpty() || it.name.lowercase().contains(q) }
        val rec = if (q.isEmpty()) matching.filter { it.lastActivity > 0 }.sortedByDescending { it.lastActivity }.take(recent) else emptyList()
        return rec to matching.sortedWith(compareBy({ it.name.lowercase() }, { it.official }))
    }

    /** §33.8: due the first time a forward has a Private source and an Official target. */
    fun hintDue(sourcePrivate: Boolean, targets: List<ForwardTarget>, alreadyShown: Boolean): Boolean =
        !alreadyShown && sourcePrivate && targets.any { it.official }

    /**
     * §33.4 the envelope fields for one source. [noteText] gives a `note_card`'s §30.6 share text.
     * Null: not forwardable (view-once, a card with buttons, a control, a placeholder file).
     */
    fun build(src: MessageEntity, me: String, noteText: (MessageEntity) -> String? = { null }): ForwardSpec? {
        if (!SelectionRules.selectable(src) || src.risiCtl) return null
        val risi = lk.codegen.risime.data.tabs.RisiMessages.meta(src)
        if (risi != null) {
            if (risi.kind in SelectionRules.NOT_FORWARDABLE_RISI) return null
            // A Risi message becomes a plain text from its body (a note card: the share text); never its `risi`.
            val body = (if (risi.kind == RisiKinds130.NOTE_CARD) noteText(src) else null) ?: src.body
            return ForwardSpec(MessageEntity.KIND_TEXT, body, Forwarding.nextHops(src.forwardHops, sourceIsOwn = false))
        }
        val own = src.from.equals(me, true)
        val hops = Forwarding.nextHops(src.forwardHops, own)
        return when {
            src.image -> ForwardSpec(MessageEntity.KIND_IMAGE, src.body, hops, mediaSource = src.clientMsgId)
            src.file -> {
                val meta = FileMeta.decode(src.systemJson)?.takeIf { !it.parts } ?: return null
                ForwardSpec(MessageEntity.KIND_FILE, src.body, hops, meta.encode(), mediaSource = src.clientMsgId)
            }
            src.kind == MessageEntity.KIND_TEXT -> src.body.takeIf { it.isNotBlank() }?.let { ForwardSpec(MessageEntity.KIND_TEXT, it, hops) }
            else -> null
        }
    }
}

/**
 * §33.4 step 4: persists a whole forward before anything is sent: for each target and each source (in
 * chat order) one PENDING outbox row with a fresh client_msg_id; for media, a REENCRYPT media row (the
 * §33.5 job, its own client_blob_id). One transaction, so a crash resumes the whole forward. The
 * optional "Add a message" goes after the items as an ordinary unforwarded text.
 */
class Forwarder(
    private val messages: MessageDao,
    private val media: MediaDao,
    private val tx: TransactionRunner,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    data class Plan(val rows: List<MessageEntity>, val mediaJobs: List<String>)

    suspend fun persist(specs: List<ForwardSpec>, targets: List<String>, me: String, addMessage: String?): Plan {
        val rows = mutableListOf<MessageEntity>()
        val jobs = mutableListOf<String>()
        tx.run {
            var ts = clock()
            for (conv in targets) {
                val to = lk.codegen.risime.net.dmPeer(conv, me) ?: conv
                for (s in specs) {
                    val id = newId()
                    val row = MessageEntity(
                        clientMsgId = id, messageId = null, conversationId = conv, from = me, to = to, body = s.body.trim(),
                        serverTs = null, localTs = ts++, status = MessageStatus.PENDING.name, outgoing = true, kind = s.kind,
                        systemJson = s.systemJson, forwardHops = s.hops,
                    )
                    messages.insert(row)
                    rows += row
                    val src = s.mediaSource
                    if (src != null) {
                        val srcMedia = media.get(src)
                        media.insert(
                            MediaEntity(
                                clientMsgId = id, conversationId = conv, outgoing = true, state = MediaState.REENCRYPT.name,
                                blobId = null, blobSize = 0, blobSha256 = "", clientBlobId = newId(), sealedEnc = ByteArray(0),
                                sealedThumb = null, mime = srcMedia?.mime ?: "application/octet-stream", w = srcMedia?.w ?: 0, h = srcMedia?.h ?: 0,
                                fileName = null, expiresAtEst = null, lastAccess = clock(), forwardFrom = src,
                            ),
                        )
                        jobs += id
                    }
                }
                addMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                    val row = MessageEntity(newId(), null, conv, me, to, text, null, ts++, MessageStatus.PENDING.name, true)
                    messages.insert(row)
                    rows += row
                }
            }
        }
        return Plan(rows, jobs)
    }
}
