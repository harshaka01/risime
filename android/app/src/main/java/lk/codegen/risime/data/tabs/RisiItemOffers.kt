package lk.codegen.risime.data.tabs

import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.RisiActions127
import lk.codegen.risime.net.RisiCommitment
import lk.codegen.risime.net.RisiDigestItem
import lk.codegen.risime.net.RisiItemDue
import lk.codegen.risime.net.RisiItemStates
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.RisiToolCall
import lk.codegen.risime.net.RisiTotals
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * Server items 8–10 (2026-10-09, docs/status/server.md "Android needs"): proactive calendar/reminder
 * offers (ordinary `confirm` cards with `origin: "offer"`), the one `item_clarify` question of a vague
 * item, and the My promises split by `direction`. Pure rules; the composables draw them. Every field
 * is optional: an older server's items fall back to the v1.27 rules.
 */

/** Item 8: a proactive offer is an ordinary confirm card; only its header differs (no request, no progress bubble). */
object RisiOffers {
    const val ORIGIN_OFFER = "offer"

    fun isOffer(r: RisiMeta): Boolean = r.origin == ORIGIN_OFFER

    /** "Add to calendar?" / "Remind me?" for an offer; null for any other confirm (the usual header). */
    fun header(r: RisiMeta): String? = when {
        !isOffer(r) -> null
        r.tool == RisiToolCall.TOOL_CALENDAR_ADD -> "Add to calendar?"
        r.tool == TOOL_SET_REMINDER -> "Remind me?"
        else -> null
    }

    const val TOOL_SET_REMINDER = "set_reminder"
}

/** Item 10: the `item_clarify` card ([New date] → §27.5 `item_edit` with the new `due`, in my Risi chat). */
object RisiClarify {
    /** [New date] shows for the item's owner (in `notify`), with `new_date` in `buttons` and the item's text known, until my edit went out. */
    fun canPickDate(me: String?, r: RisiMeta, messages: List<MessageEntity>, awaiting: Set<String>): Boolean {
        me ?: return false
        val id = r.itemId ?: return false
        if (RisiItemDue.BUTTON_NEW_DATE !in r.buttons) return false
        if (r.text.isNullOrBlank()) return false
        if (r.notify.isNotEmpty() && r.notify.none { it.equals(me, true) }) return false
        if (id.lowercase() in awaiting) return false
        return !answered(me, r, messages)
    }

    /** My `item_edit` for this item is in the chat (sent from this card or anywhere else). */
    fun answered(me: String, r: RisiMeta, messages: List<MessageEntity>): Boolean {
        val id = r.itemId ?: return false
        return messages.any { m ->
            m.kind == MessageEntity.KIND_RISI_CTL && m.from.equals(me, true) &&
                RisiControl.targetOf(m.systemJson)?.equals(id, true) == true &&
                RisiControl.actionOf(m.systemJson) == RisiActions127.ITEM_EDIT
        }
    }

    /** The picked date (and time) as the `item_edit` `due`: a timed due at that minute, an all-day one at the day's end. */
    fun due(date: LocalDate, time: LocalTime?, zone: ZoneId = ZoneId.systemDefault()): Pair<String, Boolean> {
        val at = if (time != null) date.atTime(time).atZone(zone) else date.atTime(LocalTime.of(23, 59, 59, 999_000_000)).atZone(zone)
        return RisiControl.iso(at.toInstant().toEpochMilli()) to (time == null)
    }
}

/** Item 9: My promises in sections by `direction`. */
object RisiPromises {
    const val I_PROMISED = "i_promised"
    const val PROMISED_TO_ME = "promised_to_me"
    const val OTHERS = "others"

    const val TITLE_I_PROMISED = "I promised"
    const val TITLE_PROMISED_TO_ME = "Promised to me"
    const val TITLE_OTHERS = "Others"

    const val NEEDS_CLARIFICATION = "needs_clarification"

    data class Section(val direction: String, val title: String, val count: Int, val items: List<RisiCommitment>)

    /** The item's direction; an older server's item: `role` counterpart → promised to me, else I promised (as v1.27 showed it). */
    fun direction(c: RisiCommitment): String = when (c.direction) {
        I_PROMISED, PROMISED_TO_ME, OTHERS -> c.direction
        null -> if (c.role == RisiItemDue.ROLE_COUNTERPART) PROMISED_TO_ME else I_PROMISED
        else -> OTHERS
    }

    fun directionOf(i: RisiDigestItem, me: String): String = when (i.direction) {
        I_PROMISED, PROMISED_TO_ME, OTHERS -> i.direction
        null -> if (i.owner == null || i.owner.equals(me, true)) I_PROMISED else PROMISED_TO_ME
        else -> OTHERS
    }

    fun title(direction: String): String = when (direction) {
        I_PROMISED -> TITLE_I_PROMISED
        PROMISED_TO_ME -> TITLE_PROMISED_TO_ME
        else -> TITLE_OTHERS
    }

    /** The server's count for a direction, else the number of items listed. */
    fun count(totals: RisiTotals?, direction: String, listed: Int): Int = when {
        totals == null -> listed
        direction == I_PROMISED -> totals.iPromised
        direction == PROMISED_TO_ME -> totals.promisedToMe
        else -> totals.others
    }

    /**
     * "I promised", "Promised to me", then "Others" — each only when it has items (or the server counts any);
     * the counts are the reply's `totals` when it has them.
     */
    fun sections(items: List<RisiCommitment>, totals: RisiTotals?): List<Section> {
        val by = items.groupBy(::direction)
        return listOf(I_PROMISED, PROMISED_TO_ME, OTHERS).mapNotNull { d ->
            val its = by[d].orEmpty()
            val n = count(totals, d, its.size)
            if (its.isEmpty() && n == 0) null else Section(d, title(d), n, its)
        }
    }

    /** "I promised 2 · Promised to me 1" (the directions with a count). */
    fun countsLine(totals: RisiTotals?, items: List<RisiCommitment>): String? {
        val parts = sections(items, totals).filter { it.count > 0 }.map { "${it.title} ${it.count}" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** The owner as the server labels it ("You", a name), else from the phone's contacts. */
    fun owner(c: RisiCommitment, me: String, nameOf: (String) -> String): String? =
        c.ownerName?.takeIf { it.isNotBlank() } ?: c.owner?.let { if (it.equals(me, true)) "You" else nameOf(it) }

    /** "due Fri 9 Oct, 17:00" / "by Mon 12 Oct" (all-day), else the said phrase. */
    fun due(c: RisiCommitment, zone: ZoneId = ZoneId.systemDefault(), timed: (String?) -> String? = { null }): String? =
        if (c.allDay != null) RisiLedger.dueLabel(c.due, c.allDay, c.dueText, zone) else (timed(c.due) ?: c.dueText)?.let { "due $it" }

    /** The status line: "Needs a date" for a vague item, else the state in words. */
    fun status(c: RisiCommitment): String {
        val s = c.status ?: if (c.needsClarification == true) NEEDS_CLARIFICATION else c.state
        return when (s) {
            NEEDS_CLARIFICATION -> "Needs a date"
            RisiItemStates.PROPOSED -> "Waiting"
            RisiItemStates.CONFIRMED -> "Confirmed"
            RisiItemStates.EDITED -> "Confirmed (edited)"
            RisiItemStates.DONE -> "Done"
            RisiItemStates.DECLINED -> "Declined"
            RisiItemStates.CANCELLED -> "Cancelled"
            RisiItemStates.EXPIRED -> "Not tracked"
            "open" -> "Open"
            else -> s.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    /** Where a tap opens: the source conversation (null: the row isn't tappable) and the message to scroll to. */
    fun target(c: RisiCommitment): Pair<String, String?>? {
        val conv = c.sourceConversationId ?: c.officialConversationId ?: return null
        return conv to (c.sourceMessageId ?: c.sourceMessageIds.firstOrNull())
    }

    /** The personal digest's items grouped like My promises (with the digest's `totals` as the counts). */
    fun digestSections(r: RisiMeta, me: String): List<Triple<String, Int, List<RisiDigestItem>>> {
        val by = r.items.groupBy { directionOf(it, me) }
        return listOf(I_PROMISED, PROMISED_TO_ME, OTHERS).mapNotNull { d ->
            val its = by[d].orEmpty()
            if (its.isEmpty()) null else Triple(d, count(r.totals, d, its.size), its)
        }
    }
}
