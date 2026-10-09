package lk.codegen.risime.data.tabs

import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.RisiDigestItem
import lk.codegen.risime.net.RisiItemDue
import lk.codegen.risime.net.RisiItemStates
import lk.codegen.risime.net.RisiKinds127
import lk.codegen.risime.net.RisiMeta
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Contract v1.27 §27: what the phone shows for `made_by` (§27.1), the per-person discussion summary and
 * its items (§27.3, §27.5), the reminders (§27.6) and the short card in Official (§27.4). Pure logic; the
 * cards are in ui/tabs/RisiLedgerUi.kt.
 */

/** A ledger item as it stands on this phone: the summary copy, then every `item_update` (oldest first). */
data class LedgerItemView(
    val itemId: String,
    val summaryId: String?,
    val owner: String?,
    val counterpart: List<String>,
    val text: String,
    val due: String?,
    val allDay: Boolean,
    val dueText: String?,
    val state: String,
    /** The user of the latest update (null for the proposal). */
    val by: String?,
)

object RisiLedger {
    /** item_id (lowercase) -> its view: the `discussion_summary` items, then the `item_update`s in order. */
    fun items(messages: List<MessageEntity>): Map<String, LedgerItemView> {
        val out = LinkedHashMap<String, LedgerItemView>()
        for (m in messages) {
            val r = RisiMessages.meta(m) ?: continue
            when (r.kind) {
                RisiKinds127.DISCUSSION_SUMMARY -> for (i in r.items) {
                    val id = i.id.lowercase().ifEmpty { continue }
                    out.putIfAbsent(id, view(i, r.summaryId))
                }
                RisiKinds127.ITEM_UPDATE -> {
                    val id = r.itemId?.lowercase() ?: continue
                    val prev = out[id]
                    out[id] = LedgerItemView(
                        itemId = r.itemId, summaryId = r.summaryId ?: prev?.summaryId, owner = prev?.owner ?: r.owner,
                        counterpart = prev?.counterpart.orEmpty(), text = r.text ?: prev?.text.orEmpty(),
                        due = if (r.due != null || r.text != null) r.due else prev?.due,
                        allDay = r.allDay ?: prev?.allDay ?: false,
                        dueText = if (r.due != null && r.due != prev?.due) null else prev?.dueText,
                        state = r.state ?: prev?.state ?: RisiItemStates.PROPOSED, by = r.by,
                    )
                }
            }
        }
        return out
    }

    private fun view(i: RisiDigestItem, summaryId: String?) =
        LedgerItemView(i.id, summaryId, i.owner, i.counterpart, i.text, i.due, i.allDay, i.dueText, i.state ?: RisiItemStates.PROPOSED, null)

    /** The `discussion_summary` rows on this phone by summary_id (lowercase). */
    fun summaries(messages: List<MessageEntity>): Map<String, Pair<MessageEntity, RisiMeta>> {
        val out = HashMap<String, Pair<MessageEntity, RisiMeta>>()
        for (m in messages) {
            val r = RisiMessages.meta(m) ?: continue
            if (r.kind == RisiKinds127.DISCUSSION_SUMMARY) r.summaryId?.lowercase()?.let { out.putIfAbsent(it, m to r) }
        }
        return out
    }

    /**
     * The server message id a chat opened from a Risi card scrolls to: the summary's card in the Risi chat, or
     * the first message at or after `at` in Official (null: not on this phone yet).
     */
    fun focusTarget(messages: List<MessageEntity>, f: RisiUiBus.Focus): String? {
        f.messageId?.let { mid ->
            // Not (yet) on this phone: no scroll; the chat opens at its end and scrolls when history brings it.
            return messages.firstOrNull { it.messageId.equals(mid, true) }?.messageId
        }
        f.summaryId?.let { sid ->
            return messages.firstOrNull { m -> m.messageId != null && RisiMessages.meta(m)?.let { it.kind == RisiKinds127.DISCUSSION_SUMMARY && it.summaryId.equals(sid, true) } == true }?.messageId
        }
        val at = f.at?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
        return messages.firstOrNull { m -> m.messageId != null && (lk.codegen.risime.data.HistoryMarkers.epochMs(m.serverTs) ?: -1) >= at }?.messageId
    }

    /** §27.5 an `item_update` whose summary card is on this phone shows no bubble (the card changes instead). */
    fun updateHasCard(r: RisiMeta, messages: List<MessageEntity>): Boolean {
        val sid = r.summaryId?.lowercase() ?: return false
        return messages.any { m -> RisiMessages.meta(m)?.let { it.kind == RisiKinds127.DISCUSSION_SUMMARY && it.summaryId.equals(sid, true) } == true }
    }

    /** §27.3 a summary copy is past its `expires_at`: its `proposed` items show "Not tracked". */
    fun expired(r: RisiMeta, nowMs: Long): Boolean {
        val exp = r.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return false
        return nowMs >= exp
    }

    /** Names joined: "Shenika", "Shenika and Kamal", "Shenika, Kamal and Ruwan". */
    fun joinNames(names: List<String>): String = when (names.size) {
        0 -> "others"
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    private fun local(iso: String?, zone: ZoneId) = iso?.let { runCatching { Instant.parse(it).atZone(zone) }.getOrNull() }

    /**
     * §27.3 the card's header with names from the phone: "Summary of your discussion with Shenika (09:12, video
     * call 32 min)" or "…with Shenika and Kamal (09:12–09:40, chat)".
     */
    fun summaryHeader(r: RisiMeta, me: String, nameOf: (String) -> String, zone: ZoneId = ZoneId.systemDefault()): String {
        val others = r.withUsers.filterNot { it.equals(me, true) }.map(nameOf)
        val start = local(r.startedAt, zone)
        val end = local(r.endedAt, zone)
        val time = start?.let { HM.format(it) }
        val detail = if (r.source == "call") {
            val kind = if (r.media == "video") "video call" else "voice call"
            val min = r.durationS?.let { maxOf(1L, (it + 30) / 60) }
            listOfNotNull(time, kind + (min?.let { " $it min" } ?: "")).joinToString(", ")
        } else {
            val span = if (start != null && end != null && HM.format(end) != time) "$time–${HM.format(end)}" else time
            listOfNotNull(span, "chat").joinToString(", ")
        }
        return "Summary of your discussion with ${joinNames(others)} ($detail)"
    }

    /** §27.4 the short card's count line: "3 items · Details in your Risi chat". */
    fun cardCountLine(count: Int?): String {
        val n = count ?: 0
        return "$n item${if (n == 1) "" else "s"} · Details in your Risi chat"
    }

    private val DUE_TIMED = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)
    private val DUE_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** "due Fri 9 Oct, 17:00" / "by Mon 12 Oct" (all-day) in the phone's zone; the server's `due_text` if `due` doesn't parse; null without a date. */
    fun dueLabel(due: String?, allDay: Boolean, dueText: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
        val t = local(due, zone) ?: return dueText
        return if (allDay) "by " + DUE_DAY.format(t) else "due " + DUE_TIMED.format(t)
    }

    /** The state as read-only text: "waiting", "confirmed ✓", "declined", "done ✓", "cancelled", "Not tracked". */
    fun stateLabel(state: String, expired: Boolean): String = when (state) {
        RisiItemStates.PROPOSED -> if (expired) "Not tracked" else "waiting"
        RisiItemStates.CONFIRMED -> "confirmed ✓"
        RisiItemStates.EDITED -> "confirmed ✓ (edited)"
        RisiItemStates.DECLINED -> "declined"
        RisiItemStates.DONE -> "done ✓"
        RisiItemStates.CANCELLED -> "cancelled"
        RisiItemStates.EXPIRED -> "Not tracked"
        else -> state
    }

    /**
     * §27.3/§27.5 which buttons an item shows [me]: ✓ ✗ ✎ only on my own `proposed` item before `expires_at`;
     * on a tracked item, `done` for its owner or a counterpart (and ✎ for the owner); nothing while an
     * action of mine on it is on its way ([awaiting]).
     */
    fun itemButtons(me: String, item: LedgerItemView, expired: Boolean, awaiting: Set<String>): List<String> {
        if (item.itemId.lowercase() in awaiting) return emptyList()
        val mine = item.owner.equals(me, true)
        val counterpart = item.counterpart.any { it.equals(me, true) }
        return when {
            item.state == RisiItemStates.PROPOSED -> if (mine && !expired) listOf(CONFIRM, DECLINE, EDIT) else emptyList()
            RisiItemStates.tracked(item.state) -> when {
                mine -> listOf(DONE, EDIT)
                counterpart -> listOf(DONE)
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    const val CONFIRM = "item_confirm"
    const val DECLINE = "item_decline"
    const val EDIT = "item_edit"
    const val DONE = "done"

    /** §27.6 a reminder's header. */
    fun reminderHeader(r: RisiMeta): String = when (r.kind) {
        RisiKinds127.ITEM_DUE -> when (r.moment) {
            RisiItemDue.AT -> "Risi · Due now"
            RisiItemDue.TODAY -> "Risi · Due today"
            else -> "Risi · Reminder"
        }
        RisiKinds127.ITEM_OVERDUE -> "Risi · Overdue"
        RisiKinds127.ITEM_NUDGE -> "Risi · Not done yet"
        else -> "Risi"
    }

    /**
     * §27.6 a reminder's buttons for [me]: the owner's `done`/`new_date` as the server offers them; a
     * counterpart (no owner buttons) may [Mark done]. None once the item is no longer tracked on this phone
     * (or an action of mine is on its way); [Open chat] is separate (only when the summary is on the phone).
     */
    fun reminderButtons(me: String, r: RisiMeta, item: LedgerItemView?, awaiting: Set<String>): List<String> {
        val id = r.itemId?.lowercase() ?: return emptyList()
        if (id in awaiting) return emptyList()
        // Reminders come only for tracked items; a stale local `proposed` doesn't hide them, a closed state does.
        if (item != null && item.state in CLOSED) return emptyList()
        val owner = r.kind == RisiKinds127.ITEM_OVERDUE || r.role == RisiItemDue.ROLE_OWNER || (r.owner?.equals(me, true) == true)
        return if (owner) r.buttons.filter { it == RisiItemDue.BUTTON_DONE || it == RisiItemDue.BUTTON_NEW_DATE }
        else listOf(MARK_DONE)
    }

    const val MARK_DONE = "mark_done"

    private val CLOSED = setOf(RisiItemStates.DONE, RisiItemStates.CANCELLED, RisiItemStates.DECLINED, RisiItemStates.EXPIRED)

    /** §27.6 the personal digest: my own items, then what is owed to me. */
    fun digestSplit(r: RisiMeta, me: String): Pair<List<RisiDigestItem>, List<RisiDigestItem>> =
        r.items.partition { it.owner == null || it.owner.equals(me, true) }

    /** §27.9 My promises: `role` owner (and legacy commitments) first, then "Owed to me" (`role` counterpart). */
    fun promisesSplit(items: List<lk.codegen.risime.net.RisiCommitment>, me: String): Pair<List<lk.codegen.risime.net.RisiCommitment>, List<lk.codegen.risime.net.RisiCommitment>> =
        items.partition { c -> c.role != RisiItemDue.ROLE_COUNTERPART } // a legacy commitment (no role) stays where v1.26 showed it
}
