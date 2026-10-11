package lk.codegen.risime.ui.tabs

import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiCards
import lk.codegen.risime.data.tabs.CommitmentView

/**
 * §24.9/§24.11 what the Official chat screen can do with Risi. `null` everywhere else: the Private tab
 * passes no host, so it has no @Risi chip, no card and no "ask Risi" (the screen never even looks).
 */
interface RisiHost {
    /** The signed-in user (who may act on which card). */
    val me: String

    /** `risi_request` `ask` from the @Risi chip (the text is never parsed). */
    fun ask(text: String)

    /** v1.32 §25.4 an `open` chip: `settings.calendar`, `settings.risi_skills`, `settings.notifications`, `calendar.event`. */
    fun openTarget(target: String, eventId: String?) {
        when (target) {
            lk.codegen.risime.net.RisiNextAction.SETTINGS_CALENDAR, lk.codegen.risime.net.RisiNextAction.SETTINGS_CALENDAR_PERMISSION -> openSkills(lk.codegen.risime.net.RisiSkillIds.CALENDAR)
            lk.codegen.risime.net.RisiNextAction.SETTINGS_RISI_SKILLS -> openSkills(null)
            lk.codegen.risime.net.RisiNextAction.SETTINGS_NOTIFICATIONS -> openNotificationSettings()
            lk.codegen.risime.net.RisiNextAction.CALENDAR_EVENT -> eventId?.toLongOrNull()?.let { calendar?.open(it) }
        }
    }

    /** Android's notification settings for this app. */
    fun openNotificationSettings() {}

    /** v1.34 §33.15 a `pdf` chip: render the PDF on this phone (the §33.14 sheet; nothing is sent). */
    fun exportPdf(source: kotlinx.serialization.json.JsonObject) {}

    /** v1.32 §29.7 "Your events": this phone's own events in the range (phone provider + cached Risi Calendar). */
    suspend fun localEvents(fromMs: Long, toMs: Long): lk.codegen.risime.data.tabs.LocalEventsResult = lk.codegen.risime.data.tabs.LocalEventsResult.Unavailable

    /** Official chat menu: "Summarise" (the last 24 h) and "Report". */
    fun summarise()

    fun report()

    /** Summarise "today" | "7d" | "30d" (proposal 2026-10-09-risi-30day-summaries). */
    fun summarisePeriod(period: String) = summarise()

    /** Summarise a date range (at most 31 days back). */
    fun summariseRange(fromMs: Long, toMs: Long) = summarise()

    /** `risi_action` on a commitment or an offer (`edit` carries [editText] and [editDue]). */
    fun act(target: String, action: String, editText: String? = null, editDue: String? = null)

    /** 👍/👎 on a Risi card: private REST, no visible reaction. */
    fun feedback(callRef: String, rating: String, reason: String?)

    /** §26.4 [Undo] on a `skill_done` (`POST …/activity/{entry}/undo`); the outcome shows as a notice. */
    fun undo(skillId: String, entryId: String, token: String) {}

    /** Entry ids undone (or being undone) from this screen: their [Undo] is gone. */
    val undone: Set<String> get() = emptySet()

    /** §26.5 "Open Risi skills" (Settings → Risi skills at [skillId]). */
    fun openSkills(skillId: String?) {}

    /** §25.4 a draft's [Use]: opens [conversationId] with the composer filled; never sends. */
    fun useDraft(conversationId: String, text: String) {}

    /** Whether a conversation is on this phone (a draft's [Use] shows only then). */
    fun hasConversation(conversationId: String): Boolean = false

    /** A conversation's local name ("Kumu", a group's name), or null when it isn't on this phone. */
    fun conversationName(conversationId: String): String? = null

    /** §26.6 without SCHEDULE_EXACT_ALARM a scheduled message may be a few minutes late (the card says so). */
    fun scheduleMayBeLate(): Boolean = false

    /** §27.5 an item action (`item_confirm`/`item_decline`/`item_edit`/`done`) in this (the actor's Risi) chat. */
    fun actItem(itemId: String, action: String, text: String? = null, due: String? = null, allDay: Boolean = false) = act(itemId, action, text, due)

    /** §27.3/§27.6 [Open chat]: the Official conversation, scrolled to the first message at or after [atIso]. */
    fun openChat(conversationId: String, atIso: String?) {}

    /** §27.4 [Open Risi chat] on the short card: my Risi chat at that summary (only when [risiChatAvailable]). */
    fun openRisiChat(summaryId: String) {}

    /** This phone has a usable Risi chat with the Ledger on (the short card's button shows only then). */
    fun risiChatAvailable(): Boolean = false

    /** [Add] after [Edit] on a calendar_add card: `confirm_write` with `edit` (the corrected title/start/end/all_day). */
    fun confirmEdited(writeId: String, edit: kotlinx.serialization.json.JsonObject) {}

    /** P0 the phone's calendar for the calendar action card (picker, the local add record, Open). */
    val calendar: lk.codegen.risime.data.tabs.RisiCalendarPort? get() = null

    /** §29 Risi Calendar for the event cards (null: not a calendar device; the cards render from their own data). */
    val risiCalendar: lk.codegen.risime.data.calendar.RisiCalendarCardsPort? get() = null

    /** §30 this is a Risi Notes device (the switch, `risi_notes` advertised): [Open] on `notes_saved`, the Notes menu item. */
    val notesOn: Boolean get() = false

    /** The viewer's own display name (a note's title starts with it: "Harsha × Shenika · …"). */
    val myName: String get() = "You"

    /** §30.4 the note screen. */
    fun openNote(noteId: String) {}

    /** §30.6 the Notes list. */
    fun openNotes() {}

    /**
     * §31.5 the local names of Google calendars by their `ref` (the Google phone only: empty elsewhere). The "Checked:"
     * line of an answer shows them in place of the server's counts; the stored message is never changed.
     */
    val gcalNames: Map<String, String> get() = emptyMap()

    /**
     * §31.7 the Google side of the day timeline of an event at [startMs]: the day's busy blocks on the Google phone
     * (kept 5 minutes in memory, never stored), or the caption on a failed read / on the user's other devices.
     */
    suspend fun googleTimeline(startMs: Long, zone: java.time.ZoneId): lk.codegen.risime.data.calendar.TimelineGoogle? = null

    /** §31.8 the `google_reconnect` card's [Reconnect]: Settings → Risi skills → Calendar. */
    fun openGoogleCalendarSettings() = openSkills(lk.codegen.risime.net.RisiSkillIds.CALENDAR)
}

/** Everything a card needs from its screen. */
class RisiCardContext(
    val host: RisiHost,
    val nameOf: (String) -> String,
    /** commitment_id (lowercase) -> its state after the updates. */
    val states: Map<String, CommitmentView>,
    /** Targets this user acted on a moment ago (buttons off). */
    val awaiting: Set<String>,
    val nowMs: Long,
    /** Scroll to a message by its server id (answer refs). */
    val onRef: (String) -> Unit,
    /** Display names of the members (to put an owner chip on "Kamal: send the quote"). */
    val knownNames: List<String> = emptyList(),
    /** Official is off (history only): no buttons. */
    val readOnly: Boolean = false,
    /** An answer's sources as quotes of messages on this phone (none found: nothing shown). */
    val quotes: (lk.codegen.risime.net.RisiMeta) -> List<lk.codegen.risime.data.tabs.SourceQuote> = { emptyList() },
    /** Every message of the conversation (card states that follow actions: confirm, Me too). */
    val messages: List<MessageEntity> = emptyList(),
    /** §25.4 a next-step chip pre-fills this screen's composer. */
    val prefill: (String) -> Unit = {},
    /** §27.4 this phone has a usable Risi chat with the Ledger on (observed, so the short card's button follows it). */
    val risiChatReady: Boolean = false,
    /**
     * P0 2026-10-10 a next-step chip RUNS its request: sends the text to Risi as the user's `risi_request` `ask`
     * (the same path as typing it in the Risi chat and pressing send). Null: no chips on this screen.
     */
    val sendChip: ((String) -> Unit)? = null,
    /** This screen is the user's own Risi chat (v1.32 §29.7 "Your events" shows only there). */
    val risiChat: Boolean = false,
)

/** Builds a [RisiCardContext] for the rows on screen. */
fun risiCardContext(host: RisiHost, messages: List<MessageEntity>, nameOf: (String) -> String, nowMs: Long, onRef: (String) -> Unit, knownNames: List<String> = emptyList(), readOnly: Boolean = false, prefill: (String) -> Unit = {}, risiChatReady: Boolean = false, sendChip: ((String) -> Unit)? = null, risiChat: Boolean = false) =
    RisiCardContext(host, nameOf, RisiCards.states(messages), RisiCards.awaiting(messages, host.me, nowMs), nowMs, onRef, knownNames, readOnly,
        quotes = { r -> RisiCards.sourceQuotes(r, messages, nameOf) }, messages = messages, prefill = prefill, risiChatReady = risiChatReady, sendChip = sendChip, risiChat = risiChat)
