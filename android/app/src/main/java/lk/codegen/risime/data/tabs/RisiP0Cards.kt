package lk.codegen.risime.data.tabs

import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.RisiKinds135
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.RisiNextAction

/*
 * Contract v1.35 §34 Phase 2 cards: the superseded card (`confirm_update`, see RisiToolCards.confirmState), the
 * error card's text and `next_actions`, and the native name-check `clarify` card (a tap sends the `ask` text of
 * the matching `next_actions` entry: one path for Phase 1 and Phase 2; the app never invents request text).
 */
object RisiP0Cards {
    const val SUPERSEDED_ERROR = "This was replaced by your newer request."
    const val CLARIFY_HEADER = "Risi · Check the name"

    /**
     * The error card's line: the known codes' own words; `superseded` and any code this build doesn't know show
     * the server's `body` (§25.4, §34.2), else the generic line.
     */
    fun errorText(code: String?, body: String?): String = when (code) {
        RisiKinds135.ERROR_SUPERSEDED -> body?.trim()?.takeIf { it.isNotEmpty() } ?: SUPERSEDED_ERROR
        in RisiCards.KNOWN_ERRORS -> RisiCards.errorText(code)
        else -> body?.trim()?.takeIf { it.isNotEmpty() } ?: RisiCards.errorText(code)
    }

    /** One button of the clarify card: its label and the exact `ask` text it sends. */
    data class ClarifyButton(val label: String, val text: String, val keep: Boolean = false)

    /**
     * §34.5 the clarify card's buttons: one per option (best first), then [Keep "<said>"] when `keep`. Each takes
     * the `ask` text of its `next_actions` entry (by label, else by an entry text naming it); a button without a
     * matching entry is left out, so a tap always sends a server-issued string.
     */
    fun clarifyButtons(r: RisiMeta): List<ClarifyButton> {
        val c = r.clarify ?: return emptyList()
        val asks = r.nextActions.orEmpty().filter { it.action == RisiNextAction.ASK && !it.text.isNullOrBlank() }
        if (asks.isEmpty()) return emptyList()
        val used = HashSet<Int>()
        fun pick(match: (RisiNextAction) -> Boolean): RisiNextAction? =
            asks.withIndex().firstOrNull { it.index !in used && match(it.value) }?.also { used += it.index }?.value
        val out = ArrayList<ClarifyButton>()
        for (o in c.options.take(3)) {
            val n = o.name.trim()
            if (n.isEmpty()) continue
            val a = pick { it.label.trim().equals(n, true) } ?: pick { !it.label.trim().startsWith("Keep", true) && it.text!!.trim().endsWith(n, true) } ?: continue
            out += ClarifyButton(n, a.text!!.trim())
        }
        if (c.keep && c.said.isNotBlank()) {
            pick { it.label.trim().startsWith("Keep", true) }?.let { out += ClarifyButton(keepLabel(c.said), it.text!!.trim(), keep = true) }
        }
        return out
    }

    fun keepLabel(said: String): String = "Keep \"${said.trim()}\""

    /** The `next_actions` the clarify card doesn't already show (they stay chips). */
    fun chipsBesideClarify(r: RisiMeta): List<RisiNextAction>? {
        val list = r.nextActions ?: return null
        if (r.clarify == null) return list
        val texts = clarifyButtons(r).map { it.text }.toSet()
        return list.filterNot { it.action == RisiNextAction.ASK && it.text?.trim() in texts }
    }

    enum class ClarifyState { OPEN, ANSWERED, CLOSED }

    /**
     * Open until answered: a later confirm card with the clarify's `write_id` (the name was settled), or a
     * `confirm_update` for it (superseded or closed, §34.2), whichever comes first.
     */
    fun clarifyState(r: RisiMeta, messages: List<MessageEntity>): ClarifyState {
        val wid = r.clarify?.writeId ?: return ClarifyState.OPEN
        for (m in messages) {
            if (RisiToolCards.confirmUpdate(m, wid) != null) return ClarifyState.CLOSED
            if (m.kind != MessageEntity.KIND_TEXT || m.systemJson?.contains(wid, ignoreCase = true) != true) continue
            val x = RisiMessages.meta(m) ?: continue
            if (x.kind == RisiKinds.CONFIRM && x.writeId?.equals(wid, true) == true) return ClarifyState.ANSWERED
        }
        return ClarifyState.OPEN
    }

    /** A `confirm_update` row needs no bubble when the card it closes is on this phone (the card shows the state). */
    fun confirmUpdateHasCard(r: RisiMeta, messages: List<MessageEntity>): Boolean {
        val wid = r.writeId ?: return false
        return messages.any { m ->
            m.kind == MessageEntity.KIND_TEXT && m.systemJson?.contains(wid, ignoreCase = true) == true &&
                RisiMessages.meta(m)?.let { x -> x.kind != RisiKinds135.CONFIRM_UPDATE && (x.writeId?.equals(wid, true) == true || x.clarify?.writeId?.equals(wid, true) == true) } == true
        }
    }
}
