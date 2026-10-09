package lk.codegen.risime.data.tabs

import lk.codegen.risime.data.HistoryMarkers
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.RisiMeta

/** §24.11 the card kinds the app draws. */
object RisiKinds {
    const val COMMITMENT = "commitment"
    const val COMMITMENT_UPDATE = "commitment_update"
    const val REMINDER = "reminder"
    const val ESCALATION = "escalation"
    const val DIGEST = "digest"
    const val ANSWER = "answer"
    const val SUMMARY = "summary"
    const val REPORT = "report"
    const val OFFER = "offer"
    const val ERROR = "error"

    // v1.25 §25.4
    const val CONFIRM = "confirm"
    const val REMINDER_SET = "reminder_set"
    const val DRAFT = "draft"
}

/** One answer source shown as a quote of the message (tapping scrolls to it). */
data class SourceQuote(val messageId: String, val sender: String, val excerpt: String)

/** A commitment's current state after the `commitment_update`s that followed its card. */
data class CommitmentView(
    val state: String,
    val text: String?,
    val due: String?,
    /** The user of the latest update (null for the proposal). */
    val by: String?,
)

object RisiCards {
    /** §24.11 a `proposed` card that gets no `confirm` within 48 h expires (greyed out). */
    const val EXPIRY_MS = 48L * 3600_000

    /** After tapping, the buttons stay off for this long while the action is on its way (Risi answers with an update). */
    const val ACTION_PENDING_MS = 2L * 60_000

    /** commitment_id -> its state: the card, then every honoured `commitment_update` in order (oldest first). */
    fun states(messages: List<MessageEntity>): Map<String, CommitmentView> {
        val out = HashMap<String, CommitmentView>()
        for (m in messages) {
            val r = RisiMessages.meta(m) ?: continue
            val id = r.commitmentId?.lowercase() ?: continue
            when (r.kind) {
                RisiKinds.COMMITMENT -> out.putIfAbsent(id, CommitmentView(r.state ?: "proposed", r.text, r.due, null))
                RisiKinds.COMMITMENT_UPDATE -> {
                    val prev = out[id]
                    out[id] = CommitmentView(r.state ?: prev?.state ?: "proposed", r.text ?: prev?.text, if (r.due != null || r.state == "edited") r.due else prev?.due, r.by)
                }
            }
        }
        return out
    }

    /** §24.11 only the commitment's owner or a counterpart may act on a commitment. */
    fun canAct(me: String?, r: RisiMeta): Boolean {
        me ?: return false
        return r.owner.equals(me, true) || r.counterpart.any { it.equals(me, true) }
    }

    /** An offer's addressees are the users it notifies. */
    fun canAnswerOffer(me: String?, r: RisiMeta): Boolean = me != null && r.notify.any { it.equals(me, true) }

    fun expired(row: MessageEntity, view: CommitmentView, nowMs: Long): Boolean {
        if (view.state != "proposed") return false
        val ts = HistoryMarkers.epochMs(row.serverTs) ?: return false
        return nowMs - ts > EXPIRY_MS
    }

    /** Targets this user has just acted on (pending or lately sent `risi_action`s): their buttons are off meanwhile. */
    fun awaiting(messages: List<MessageEntity>, me: String?, nowMs: Long): Set<String> {
        me ?: return emptySet()
        val out = HashSet<String>()
        for (m in messages) {
            if (m.kind != MessageEntity.KIND_RISI_CTL || !m.from.equals(me, true)) continue
            if (m.status == MessageStatus.FAILED.name) continue
            if (nowMs - m.localTs > ACTION_PENDING_MS) continue
            RisiControl.targetOf(m.systemJson)?.let { out += it.lowercase() }
        }
        return out
    }

    /** Which buttons a commitment card shows its viewer (empty: only the state). */
    fun buttons(me: String?, r: RisiMeta, view: CommitmentView, row: MessageEntity, awaiting: Set<String>, nowMs: Long): List<String> {
        if (!canAct(me, r)) return emptyList()
        if (r.commitmentId?.lowercase() in awaiting) return emptyList()
        if (expired(row, view, nowMs)) return emptyList()
        return when (view.state) {
            "proposed" -> listOf("confirm", "decline", "edit")
            "confirmed", "edited" -> listOf("done")
            else -> emptyList()
        }
    }

    /** Offer buttons: only the addressees, once, not while their answer is on its way. */
    fun offerButtons(me: String?, r: RisiMeta, awaiting: Set<String>): List<String> {
        if (!canAnswerOffer(me, r) || r.offerId?.lowercase() in awaiting) return emptyList()
        return r.buttons.mapNotNull { when (it) { "yes" -> "offer_yes"; "not_now" -> "offer_not_now"; else -> null } }
    }

    /** The human text of a state ("Confirmed by Kamal"). */
    fun stateLabel(view: CommitmentView, expired: Boolean, nameOf: (String) -> String): String = when {
        expired -> "Expired"
        view.state == "proposed" -> "Proposed"
        view.state == "confirmed" -> view.by?.let { "Confirmed by ${nameOf(it)}" } ?: "Confirmed"
        view.state == "edited" -> view.by?.let { "Edited by ${nameOf(it)}" } ?: "Edited"
        view.state == "declined" -> view.by?.let { "Declined by ${nameOf(it)}" } ?: "Declined"
        view.state == "done" -> view.by?.let { "Done (${nameOf(it)})" } ?: "Done"
        view.state == "cancelled" -> "Cancelled"
        else -> view.state.replaceFirstChar { it.uppercase() }
    }

    /** Error codes as text. */
    fun errorText(code: String?): String = when (code) {
        "model_unavailable" -> "Risi can't answer right now. Try again later."
        "rate_limited" -> "Too many requests to Risi. Try again later."
        "nothing_to_summarise" -> "There is nothing to summarise yet."
        "out_of_window" -> "I can only summarise the last 24 hours."
        "queue_overflow" -> "Risi has too many of your requests waiting. Try again in a few minutes."
        "tool_timeout" -> "I couldn't reach your phone to add it."
        else -> "Risi couldn't do that."
    }

    /** Words of a source quote ("Kamal: “we agreed the budget is 2 million…”"). */
    const val QUOTE_WORDS = 8

    /**
     * An answer's sources as quotes of the actual messages (sender and first [QUOTE_WORDS] words), in
     * order: `refs`, then v1.25 `sources` of type `message`. A message not on this phone (or deleted,
     * or not text) shows nothing — never a "Message 1" placeholder.
     */
    fun sourceQuotes(r: RisiMeta, messages: List<MessageEntity>, nameOf: (String) -> String): List<SourceQuote> {
        val ids = (r.refs + r.sources.filter { it.type == "message" }.mapNotNull { it.messageId }).distinctBy { it.lowercase() }
        if (ids.isEmpty()) return emptyList()
        val byId = messages.filter { it.messageId != null }.associateBy { it.messageId!!.lowercase() }
        return ids.mapNotNull { id ->
            val m = byId[id.lowercase()] ?: return@mapNotNull null
            if (m.kind != MessageEntity.KIND_TEXT || m.showsAsDeleted || RisiMessages.meta(m) != null) return@mapNotNull null
            val words = m.body.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return@mapNotNull null
            val excerpt = words.take(QUOTE_WORDS).joinToString(" ") + if (words.size > QUOTE_WORDS) "…" else ""
            SourceQuote(m.messageId!!, if (m.outgoing) "You" else nameOf(m.from), excerpt)
        }
    }

    /** Where a rendered message's card goes: a `risi` row in the list of messages, by server message id. */
    fun indexOfMessage(messages: List<MessageEntity>, messageId: String): Int = messages.indexOfFirst { it.messageId.equals(messageId, true) }
}
