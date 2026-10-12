package ai.risicloud.risime.data.tabs

import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.net.ActionItem
import ai.risicloud.risime.net.RisiActionCard
import ai.risicloud.risime.net.RisiActions142
import ai.risicloud.risime.net.RisiItemStates
import ai.risicloud.risime.net.RisiKinds142
import ai.risicloud.risime.net.RisiTranscriptKinds
import ai.risicloud.risime.net.SourceRef
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * v1.42 §38 Risi voice → action items: what the phone shows for the Official `action_items` card and the owner's
 * `action_confirm` card, which buttons an item offers, the confirm-before-write proposals (calendar, reminder) and
 * the jump to an item's line. Pure logic; the cards are in ui/tabs/RisiActionItemsUi.kt.
 *
 * Product rules (Harsha): Risi always asks, never acts silently (every write is a tap on a dialog that shows what will
 * be written); Risi never guesses names, amounts or dates (no owner reads "Unassigned", no due shows nothing, an
 * unclear source shows the "couldn't hear clearly" card).
 */
object RisiActionItems {
    const val HEADER_ITEMS = "Risi · Action items"
    const val HEADER_CONFIRM = "Risi · Your action items"
    const val UNASSIGNED = "Unassigned"
    const val OWNERS_CONFIRM = "Owners confirm theirs in their Risi chat."
    const val LINE_GONE = "Line no longer available"
    const val NOT_MINE_DONE = "Not yours: Risi won't track it or offer it to anyone else."
    const val NOTHING_LEFT = "Nothing left for you here."

    const val ADD_PROMISE = "Add to My promises"
    const val ADD_CALENDAR = "Add to calendar"
    const val REMIND = "Remind me"
    const val NOT_MINE = "Not mine"
    const val EDIT = "Edit"

    /** Default length of a calendar event made from an item (§38.4: 60 min from `due`). */
    const val EVENT_MS = 60L * 60_000

    /** §38.4 the default reminder: `due − 1 h`. */
    const val REMIND_BEFORE_MS = 60L * 60_000

    fun kindTitle(kind: String): String = when (kind) {
        RisiKinds142.PROMISE -> "Promises"
        RisiKinds142.TASK -> "Tasks"
        RisiKinds142.MEETING -> "Meetings"
        RisiKinds142.DECISION -> "Decisions"
        RisiKinds142.QUESTION -> "Open questions"
        RisiKinds142.NUMBER -> "Numbers"
        else -> "Other"
    }

    /** One kind as a word (My promises rows). */
    fun kindLabel(kind: String?): String? = when (kind) {
        RisiKinds142.PROMISE -> "Promise"
        RisiKinds142.TASK -> "Task"
        RisiKinds142.MEETING -> "Meeting"
        RisiKinds142.DECISION -> "Decision"
        RisiKinds142.QUESTION -> "Question"
        RisiKinds142.NUMBER -> "Number"
        else -> null
    }

    /** "From a call" / "From a voice note" (My promises rows; null for a chat item). */
    fun sourceLabel(source: String?): String? = when (source) {
        RisiKinds142.SOURCE_CALL -> "From a call"
        RisiKinds142.SOURCE_VOICE_NOTE -> "From a voice note"
        else -> null
    }

    /** The items grouped by kind in the contract's order (empty groups left out). */
    fun grouped(items: List<ActionItem>): List<Pair<String, List<ActionItem>>> =
        RisiKinds142.ITEM_KINDS.mapNotNull { k -> items.filter { it.kind == k }.takeIf { it.isNotEmpty() }?.let { k to it } }

    /** The item's live state: the newest `item_update` this phone holds for it (any conversation), else the card's. */
    fun state(item: ActionItem, views: Map<String, LedgerItemView>): String? =
        views[item.itemId.lowercase()]?.state ?: item.state

    /**
     * "Not mine" hides an item for the owner who said so (on both cards); everyone else still sees it as declined.
     * A `cancelled` item (its note or transcript deleted, §38.6) is removed from the cards for everyone.
     */
    fun visible(item: ActionItem, state: String?, me: String): Boolean = when {
        state == RisiItemStates.CANCELLED -> false
        state == RisiItemStates.DECLINED && item.owner.equals(me, true) -> false
        else -> true
    }

    /** "You" / the name / "Unassigned" for a promise, task or meeting; null for a decision, question or number. */
    fun ownerLabel(item: ActionItem, me: String, nameOf: (String) -> String): String? = when {
        item.kind !in RisiKinds142.LEDGER_KINDS -> null
        item.owner == null -> UNASSIGNED
        item.owner.equals(me, true) -> "You"
        else -> nameOf(item.owner)
    }

    /** The due only when one was said (never a guessed date). */
    fun dueLabel(item: ActionItem, zone: ZoneId = ZoneId.systemDefault()): String? =
        if (item.due == null && item.dueText.isNullOrBlank()) null else RisiLedger.dueLabel(item.due, item.allDay, item.dueText, zone)

    /** A ledger item's state in words ("waiting", "confirmed ✓", "declined", …); null for the other kinds. */
    fun stateLabel(item: ActionItem, state: String?, expired: Boolean): String? {
        if (!item.ledger) return null
        return RisiLedger.stateLabel(state ?: RisiItemStates.PROPOSED, expired)
    }

    /** "From your voice note" / "From Kamal's voice note" / "From the call" (+ " in <chat>"). */
    fun sourceLine(card: RisiActionCard, me: String, noteSender: String?, nameOf: (String) -> String, conversationName: String?): String {
        val where = conversationName?.let { " in $it" } ?: ""
        return when (card.source) {
            RisiKinds142.SOURCE_VOICE_NOTE -> when {
                noteSender == null -> "From a voice note$where"
                noteSender.equals(me, true) -> "From your voice note$where"
                else -> "From ${nameOf(noteSender)}'s voice note$where"
            }
            else -> "From the call$where"
        }
    }

    /** v1.43 §38.9 point 6: the "couldn't hear clearly" card's text, every reason, in the card's language. */
    const val UNCLEAR_EN = "I couldn't hear this clearly."
    const val UNCLEAR_SI = "මට මෙය පැහැදිලිව ඇසුණේ නැහැ."
    const val UNCLEAR_TA = "இதை என்னால் தெளிவாகக் கேட்க முடியவில்லை."

    /** §38.2 the "couldn't hear clearly" card's text (no items, never a guess). */
    fun unclearText(card: RisiActionCard): String = when (card.lang) {
        "si" -> UNCLEAR_SI
        "ta" -> UNCLEAR_TA
        else -> UNCLEAR_EN
    }

    /** v1.43 §38.9 point 1: "It's mine" under an unowned item, and how long it may be claimed. */
    const val ITS_MINE = "It's mine"
    const val CLAIM_WINDOW_MS = 48L * 3_600_000

    /** v1.43 §38.9 point 5: under the buttons when the owner's Reminders skill is off (nothing is turned on). */
    const val REMINDERS_NOTE = "Reminders come here in your Risi chat. Phone reminders (the Reminders skill) stay off."

    /** The item with its live owner: the card's, else the newest `item_update`'s (a claim, §38.9). */
    fun live(item: ActionItem, views: Map<String, LedgerItemView>): ActionItem =
        if (item.owner != null) item else views[item.itemId.lowercase()]?.owner?.let { item.copy(owner = it) } ?: item

    /**
     * §38.9 [It's mine] on the Official card: an unowned item still without an owner, within 48 h of the card
     * ([postedMs], its server time), not cancelled, not just claimed from this phone, and the screen not read-only.
     */
    fun canClaim(item: ActionItem, views: Map<String, LedgerItemView>, postedMs: Long?, nowMs: Long, awaiting: Set<String>, readOnly: Boolean): Boolean {
        if (readOnly || !item.claim || live(item, views).owner != null) return false
        if (state(item, views) == RisiItemStates.CANCELLED) return false
        if (item.itemId.lowercase() in awaiting) return false
        return postedMs == null || nowMs < postedMs + CLAIM_WINDOW_MS
    }

    /** §38.9 the Reminders skill is off: the card explains [REMINDERS_NOTE] under [Remind me]. */
    fun remindersNote(card: RisiActionCard, buttons: List<String>): String? =
        REMINDERS_NOTE.takeIf { card.remindersSkill == ai.risicloud.risime.net.RisiActions143.REMINDERS_OFF && RisiKinds142.BUTTON_REMIND in buttons }

    /** §38.4 past `expires_at`: proposed items are no longer offered. */
    fun expired(card: RisiActionCard, nowMs: Long): Boolean {
        val exp = card.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return false
        return nowMs >= exp
    }

    /**
     * §38.4 the buttons [me] sees on an `action_confirm` item: the server's list, only for my own `proposed` item before
     * `expires_at`, and none while an action of mine on it is on its way ([awaiting]) or the screen is read-only.
     */
    fun buttons(item: ActionItem, state: String?, me: String, expired: Boolean, awaiting: Set<String>, readOnly: Boolean): List<String> {
        if (readOnly || expired) return emptyList()
        if (!item.owner.equals(me, true)) return emptyList()
        if ((state ?: RisiItemStates.PROPOSED) != RisiItemStates.PROPOSED) return emptyList()
        if (item.itemId.lowercase() in awaiting) return emptyList()
        return RisiKinds142.BUTTONS.filter { it in item.buttons }
    }

    /** The items whose `item_not_mine` I sent from this phone (hidden at once; the `item_update` follows). */
    fun notMineSent(messages: List<MessageEntity>, me: String): Set<String> {
        val out = HashSet<String>()
        for (m in messages) {
            if (m.kind != MessageEntity.KIND_RISI_CTL || !m.from.equals(me, true)) continue
            if (m.status == ai.risicloud.risime.data.MessageStatus.FAILED.name) continue
            if (RisiControl.actionOf(m.systemJson) != RisiActions142.ITEM_NOT_MINE) continue
            RisiControl.targetOf(m.systemJson)?.let { out += it.lowercase() }
        }
        return out
    }

    /** A calendar time the owner confirms: start and end (ms), all-day or not. */
    data class Slot(val startMs: Long, val endMs: Long, val allDay: Boolean)

    /**
     * §38.4 [Add to calendar] with a usable time: a timed due in the future → `due` … `due + 60 min`. Null: no usable
     * time (no due, an all-day due, a past due); the phone opens the picker first and never guesses one.
     */
    fun calendarSlot(item: ActionItem, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Slot? {
        val due = item.due?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
        if (due <= nowMs) return null
        if (item.allDay) {
            // v1.43 §38.9 point 3: an all-day due → an all-day event on that date (00:00 to 00:00 the next day).
            val day = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
            return Slot(day.atStartOfDay(zone).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), true)
        }
        return Slot(due, due + EVENT_MS, false)
    }

    /** §38.4 [Remind me] default: `due − 1 h` while that is still ahead; else null (the picker asks). */
    fun remindAt(item: ActionItem, nowMs: Long): Long? {
        if (item.allDay) return null
        val due = item.due?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
        val at = due - REMIND_BEFORE_MS
        return at.takeIf { it > nowMs }
    }

    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** "Wed 14 Oct, 10:00–11:00" in the phone's zone; an all-day slot "Fri 16 Oct (all day)". */
    fun slotLabel(s: Slot, zone: ZoneId = ZoneId.systemDefault()): String {
        if (s.allDay) return DAY.format(Instant.ofEpochMilli(s.startMs).atZone(zone)) + " (all day)"
        val a = Instant.ofEpochMilli(s.startMs).atZone(zone)
        val b = Instant.ofEpochMilli(s.endMs).atZone(zone)
        return if (a.toLocalDate() == b.toLocalDate()) WHEN.format(a) + "–" + HM.format(b) else WHEN.format(a) + " – " + WHEN.format(b)
    }

    fun atLabel(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = WHEN.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** The confirm dialog's question for [Add to calendar]. */
    fun calendarQuestion(item: ActionItem, s: Slot, zone: ZoneId = ZoneId.systemDefault()): String =
        "Add \"${item.text}\" to your Risi Calendar on ${slotLabel(s, zone)}?"

    fun remindQuestion(item: ActionItem, atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "Remind you about \"${item.text}\" on ${atLabel(atMs, zone)}?"

    /** `item_calendar.edit` for the slot the owner confirmed. */
    fun calendarEdit(s: Slot) = RisiActions142.calendarEdit(RisiControl.iso(s.startMs), RisiControl.iso(s.endMs), s.allDay)

    fun remindEdit(atMs: Long) = RisiActions142.remindEdit(RisiControl.iso(atMs))

    /**
     * §38.3 the row an item's line is: a call → the `call_transcript` post with that `session_id` + `turn`; a voice
     * note → the note itself. Null: not on this phone ("Line no longer available").
     */
    fun lineRow(messages: List<MessageEntity>, ref: SourceRef): MessageEntity? {
        if (!ref.jumpable) return null
        if (ref.isVoiceNote) return messages.firstOrNull { it.voice && it.messageId != null && it.messageId.equals(ref.messageId, true) }
        for (m in messages) {
            if (m.kind != MessageEntity.KIND_TEXT || m.systemJson == null) continue
            val t = RisiTranscriptKinds.transcript(m.systemJson) ?: continue
            if (t.sessionId.equals(ref.sessionId, true) && t.turn == ref.turn) return m
        }
        return null
    }

    /** The voice note an `action_items` card is about (its sender names the card). */
    fun noteSender(card: RisiActionCard, messages: List<MessageEntity>): String? {
        val id = card.sourceRef?.messageId ?: return null
        return messages.firstOrNull { it.messageId.equals(id, true) }?.from
    }

    /** The playback fraction of [offsetMs] in a note of [durationMs]. */
    fun seekFraction(offsetMs: Long?, durationMs: Long): Float =
        if (offsetMs == null || durationMs <= 0) 0f else (offsetMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** §38.5 the My promises chip "From calls & voice notes": its `?source=` value. */
    const val SOURCE_FILTER = RisiKinds142.SOURCE_CALL + "," + RisiKinds142.SOURCE_VOICE_NOTE
    const val SOURCE_CHIP = "From calls & voice notes"
}
