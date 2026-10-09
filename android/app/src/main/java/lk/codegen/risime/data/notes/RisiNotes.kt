package lk.codegen.risime.data.notes

import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.LedgerItemView
import lk.codegen.risime.data.tabs.RisiCards
import lk.codegen.risime.data.tabs.RisiControl
import lk.codegen.risime.data.tabs.RisiLedger
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.net.RisiActions130
import lk.codegen.risime.net.RisiDigestItem
import lk.codegen.risime.net.RisiItemStates
import lk.codegen.risime.net.RisiKinds127
import lk.codegen.risime.net.RisiKinds130
import lk.codegen.risime.net.RisiNote
import lk.codegen.risime.net.RisiNoteEvent
import lk.codegen.risime.net.RisiNoteSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * v1.29 §30 Risi Notes: the phone's rules (pure; the composables are in ui/notes). The title is built per
 * viewer from the phone's own names; items are the §27 ledger items (their live state = the note's copy,
 * then every later `item_update`); ticking is §27.5 `done`, un-ticking §30.5 `item_reopen`.
 */

/** How one agreed item shows to the viewer (§30.3). */
sealed interface NoteItemControl {
    /** My own `proposed` item before `expires_at`: ✓ ✗ ✎. */
    data object Decide : NoteItemControl

    /** Someone else's `proposed` item: "waiting" (read-only). */
    data object Waiting : NoteItemControl

    /** A tracked or done item: a tick-box ([enabled] for the owner and the counterparts, not while an action is on its way). */
    data class Tick(val checked: Boolean, val enabled: Boolean) : NoteItemControl

    /** Declined, cancelled, expired (or proposed past `expires_at`): only the state as text. */
    data class Closed(val label: String) : NoteItemControl
}

object RisiNotes {
    const val SHARE_FOOTER = "— shared from Risi Notes"
    const val INFO_LINE = "Notes are stored on the RisiMe server, encrypted at rest. Risi can read them to answer you. They are not end-to-end encrypted."
    const val NOTES_TITLE = "Risi Notes"
    const val SAVED_LINE = "Notes saved · open"
    const val PAGE = 30

    private val DATE = DateTimeFormatter.ofPattern("EEE d MMM")
    private val TIMED = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

    private fun at(iso: String?, zone: ZoneId) = iso?.let { runCatching { Instant.parse(it).atZone(zone) }.getOrNull() }

    /**
     * §30.3 "<viewer> × <others> · <topic> · <date>": "Harsha × Shenika · interview planning · Fri 9 Oct";
     * more than 3 people as "Harsha × Shenika +2"; the date of `ended_at` in the viewer's zone and locale.
     */
    fun title(
        me: String, myName: String, withUsers: List<String>, nameOf: (String) -> String, topic: String, endedAt: String?,
        zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault(),
    ): String {
        val others = withUsers.filterNot { it.equals(me, true) }.distinctBy { it.lowercase() }.map(nameOf)
        val people = when {
            others.isEmpty() -> myName
            others.size + 1 > 3 -> "$myName × ${others[0]} +${others.size - 1}"
            else -> (listOf(myName) + others).joinToString(" × ")
        }
        val date = at(endedAt, zone)?.let { DATE.withLocale(locale).format(it) }
        return listOfNotNull(people, topic.trim().takeIf { it.isNotEmpty() }, date).joinToString(" · ")
    }

    /** The item's live view: the note's copy (or a REST reply), then this phone's later `item_update`s ([RisiLedger.items]). */
    fun view(i: RisiDigestItem, noteId: String?): LedgerItemView =
        LedgerItemView(i.id, noteId, i.owner, i.counterpart, i.text, i.due, i.allDay, i.dueText, i.state ?: RisiItemStates.PROPOSED, null)

    /**
     * §30.5 the note's items in their live state: [items], then the phone's `item_update`s for them in order
     * (as [RisiLedger.items]). [reflected] null: [items] are the card's copy, so every update applies. Else
     * [items] came from `GET /risi/notes/{id}` and [reflected] are the rows (client ids) that were already on
     * the phone when it was fetched (the reply already includes them): only later updates apply. No clocks.
     */
    fun liveItems(items: List<RisiDigestItem>, noteId: String?, rows: List<MessageEntity>, reflected: Set<String>?): List<LedgerItemView> {
        val out = LinkedHashMap<String, LedgerItemView>()
        items.forEach { i -> out[i.id.lowercase()] = view(i, noteId) }
        for (m in rows) {
            if (reflected != null && m.clientMsgId in reflected) continue
            val r = RisiMessages.meta(m) ?: continue
            if (r.kind != RisiKinds127.ITEM_UPDATE) continue
            val id = r.itemId?.lowercase() ?: continue
            val prev = out[id] ?: continue // only this note's own items
            out[id] = prev.copy(
                text = r.text ?: prev.text,
                due = if (r.due != null || r.text != null) r.due else prev.due,
                allDay = r.allDay ?: prev.allDay,
                dueText = if (r.due != null && r.due != prev.due) null else prev.dueText,
                state = r.state ?: prev.state, by = r.by,
            )
        }
        return out.values.toList()
    }

    /** The rows a REST note already reflects (taken when it is fetched). */
    fun reflectedIds(rows: List<MessageEntity>): Set<String> = rows.mapTo(HashSet()) { it.clientMsgId }

    /**
     * Item actions of mine still on their way (sent < 2 min ago, not failed) by item id: the last one wins.
     * Shown at once (a tick shows ticked) until Risi's `item_update` arrives: an `item_update` of that item
     * received after the action ends it (before, the tick-box stayed off for the full 2 minutes after a ✓,
     * found by scripts/ui-entry-test --risi-notes).
     */
    fun pending(rows: List<MessageEntity>, me: String, nowMs: Long): Map<String, String> {
        val out = HashMap<String, Pair<String, Long>>()
        val updated = HashMap<String, Long>()
        for (m in rows) {
            if (m.kind != MessageEntity.KIND_RISI_CTL || !m.from.equals(me, true)) {
                if (m.from.equals(me, true) || m.systemJson == null) continue
                val r = RisiMessages.meta(m) ?: continue
                if (r.kind != RisiKinds127.ITEM_UPDATE) continue
                val id = r.itemId?.lowercase() ?: continue
                updated[id] = maxOf(updated[id] ?: Long.MIN_VALUE, m.localTs)
                continue
            }
            if (m.status == MessageStatus.FAILED.name || nowMs - m.localTs > RisiCards.ACTION_PENDING_MS) continue
            val target = RisiControl.targetOf(m.systemJson)?.lowercase() ?: continue
            val action = RisiControl.actionOf(m.systemJson) ?: continue
            out[target] = action to m.localTs
        }
        return out.filter { (id, a) -> (updated[id] ?: Long.MIN_VALUE) < a.second }.mapValues { it.value.first }
    }

    /** §30.3 an item's control for [me] ([expired]: the note copy is past `expires_at`; [pending]: my action on its way). */
    fun control(me: String, item: LedgerItemView, expired: Boolean, pending: String?, readOnly: Boolean = false): NoteItemControl {
        val mine = item.owner.equals(me, true)
        val party = mine || item.counterpart.any { it.equals(me, true) }
        val state = when (pending) {
            RisiLedger.DONE -> RisiItemStates.DONE
            RisiActions130.ITEM_REOPEN -> RisiItemStates.CONFIRMED
            RisiLedger.CONFIRM -> RisiItemStates.CONFIRMED
            RisiLedger.DECLINE -> RisiItemStates.DECLINED
            else -> item.state
        }
        return when {
            state == RisiItemStates.PROPOSED -> when {
                expired -> NoteItemControl.Closed(RisiLedger.stateLabel(state, true))
                mine && !readOnly && pending == null -> NoteItemControl.Decide
                else -> NoteItemControl.Waiting
            }
            RisiItemStates.tracked(state) -> NoteItemControl.Tick(false, party && pending == null && !readOnly)
            state == RisiItemStates.DONE -> NoteItemControl.Tick(true, party && pending == null && !readOnly)
            else -> NoteItemControl.Closed(RisiLedger.stateLabel(state, false))
        }
    }

    /** §30.5 ticking sends `done`; un-ticking sends `item_reopen`. */
    fun tickAction(checked: Boolean): String = if (checked) RisiLedger.DONE else RisiActions130.ITEM_REOPEN

    /** "Shenika · due Fri 9 Oct, 17:00" / "You · by Mon 12 Oct". */
    fun ownerDue(item: LedgerItemView, me: String, nameOf: (String) -> String, zone: ZoneId = ZoneId.systemDefault()): String =
        listOfNotNull(
            item.owner?.let { if (it.equals(me, true)) "You" else nameOf(it) },
            RisiLedger.dueLabel(item.due, item.allDay, item.dueText, zone),
        ).joinToString(" · ")

    /** "Interview · Mon 12 Oct, 14:00" (all-day: the date only). */
    fun eventLine(e: RisiNoteEvent, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = at(e.start, zone)
        val time = t?.let { if (e.allDay) DATE.withLocale(Locale.ENGLISH).format(it) else TIMED.withLocale(Locale.ENGLISH).format(it) }
        return listOfNotNull(e.title.ifBlank { "Meeting" }, time).joinToString(" · ")
    }

    /** The note's [Accept] shows only for a proposed meeting of mine. */
    fun canAccept(e: RisiNoteEvent): Boolean = e.myStatus == "proposed"

    /** "2 agreed · 1 open · 1 meeting". */
    fun countsLine(items: Int, open: Int?, events: Int): String = listOfNotNull(
        "$items agreed",
        open?.let { "$it open" },
        if (events > 0) "$events meeting${if (events == 1) "" else "s"}" else null,
    ).joinToString(" · ")

    /** The note cards in a (Risi chat's) rows, newest first, one per note id. */
    fun localNotes(rows: List<MessageEntity>): List<RisiNote> {
        val seen = HashSet<String>()
        return rows.asReversed().mapNotNull { m ->
            val r = RisiMessages.meta(m) ?: return@mapNotNull null
            if (r.kind != RisiKinds130.NOTE_CARD) return@mapNotNull null
            RisiNote.parse(m.systemJson)?.takeIf { seen.add(it.noteId.lowercase()) }
        }
    }

    private val NOT_OPEN = setOf(RisiItemStates.DONE, RisiItemStates.DECLINED, RisiItemStates.CANCELLED, RisiItemStates.EXPIRED)

    /** A local card as a list row (counts from its items after this phone's updates). */
    fun summaryOf(n: RisiNote, rows: List<MessageEntity>): RisiNoteSummary {
        val live = liveItems(n.items, n.noteId, rows, null)
        return RisiNoteSummary(
            n.noteId, n.conversationId, n.withUsers, n.topic, n.endedAt, n.source, live.size,
            live.count { it.state !in NOT_OPEN }, n.events.size, // as the server: open = not done (declined rows are gone there)
        )
    }

    /** §30.6 "the phone may also search its local note cards": topic, key points, item texts, participants' names. */
    fun matches(n: RisiNote, q: String, nameOf: (String) -> String): Boolean {
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) return true
        val hay = buildList {
            add(n.topic)
            addAll(n.keyPoints)
            n.items.forEach { add(it.text) }
            n.withUsers.forEach { add(nameOf(it)) }
        }
        return hay.any { it.lowercase().contains(needle) }
    }

    /**
     * §30.6 share into a chat: the note as an ordinary message of the user's own, ending with
     * [SHARE_FOOTER]. Names come from the phone; the item states are the live ones.
     */
    fun shareText(
        title: String, note: RisiNote, items: List<LedgerItemView>, me: String, nameOf: (String) -> String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        appendLine("Notes: $title")
        note.keyPoints.forEach { appendLine("• $it") }
        if (items.isNotEmpty()) {
            appendLine("Agreed:")
            items.forEach { i ->
                val box = when {
                    i.state == RisiItemStates.DONE -> "☑"
                    RisiItemStates.tracked(i.state) -> "☐"
                    else -> "•"
                }
                val meta = ownerDue(i, me, nameOf, zone)
                appendLine("$box ${i.text}" + if (meta.isNotEmpty()) " ($meta)" else "")
            }
        }
        if (note.events.isNotEmpty()) {
            appendLine("Meetings:")
            note.events.forEach { appendLine("• " + eventLine(it, zone)) }
        }
        append(SHARE_FOOTER)
    }.let { clip(it) }

    /** Well inside the message cap (ChatEngine.MAX_BODY_BYTES, 16 KiB; graphemes count on the server): long notes are cut before the footer. */
    const val MAX_SHARE_CHARS = 3500

    private fun clip(s: String): String {
        if (s.length <= MAX_SHARE_CHARS) return s
        return s.take(MAX_SHARE_CHARS - SHARE_FOOTER.length - 3).trimEnd() + "…\n" + SHARE_FOOTER
    }
}
