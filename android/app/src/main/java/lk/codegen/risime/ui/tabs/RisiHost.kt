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
)

/** Builds a [RisiCardContext] for the rows on screen. */
fun risiCardContext(host: RisiHost, messages: List<MessageEntity>, nameOf: (String) -> String, nowMs: Long, onRef: (String) -> Unit, knownNames: List<String> = emptyList(), readOnly: Boolean = false) =
    RisiCardContext(host, nameOf, RisiCards.states(messages), RisiCards.awaiting(messages, host.me, nowMs), nowMs, onRef, knownNames, readOnly)
