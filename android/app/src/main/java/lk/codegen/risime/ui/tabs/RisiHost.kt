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

    /** Official chat menu: "Summarise" (the last 24 h) and "Report". */
    fun summarise()

    fun report()

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
)

/** Builds a [RisiCardContext] for the rows on screen. */
fun risiCardContext(host: RisiHost, messages: List<MessageEntity>, nameOf: (String) -> String, nowMs: Long, onRef: (String) -> Unit, knownNames: List<String> = emptyList(), readOnly: Boolean = false, prefill: (String) -> Unit = {}, risiChatReady: Boolean = false) =
    RisiCardContext(host, nameOf, RisiCards.states(messages), RisiCards.awaiting(messages, host.me, nowMs), nowMs, onRef, knownNames, readOnly,
        quotes = { r -> RisiCards.sourceQuotes(r, messages, nameOf) }, messages = messages, prefill = prefill, risiChatReady = risiChatReady)
