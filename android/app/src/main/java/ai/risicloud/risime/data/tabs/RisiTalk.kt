package ai.risicloud.risime.data.tabs

import ai.risicloud.risime.data.db.MessageEntity

/*
 * PART 4 (Harsha 2026-10-12) "Talking to Risi": how the composer addresses Risi.
 * - Official: typing "@" offers Risi (the picker), and a message holding the "@Risi" token is the @Risi request
 *   (the token is the chip in text form; the rest of the text is never parsed for intent, decision 066).
 * - A 1:1 Official: a message addressed to Risi by name ("Risi, …") is also a request: there is nobody else
 *   the user could mean by it there.
 * - Private: Risi never reads or replies (§24.0). "@Risi" typed there only shows a hint offering to move the
 *   text to Official; nothing is sent to Risi.
 * - The empty Risi chat shows "What can Risi do?" with examples that fill the composer (never send).
 */
object RisiTalk {
    const val MENTION = "@Risi"
    const val PICKER_LABEL = "Risi"
    const val PICKER_DESC = "Mention Risi"
    const val PRIVATE_HINT = "Risi only works in Official. Move this to Official?"
    const val PRIVATE_MOVE = "Move to Official"
    const val EXAMPLES_TITLE = "What can Risi do?"

    /** One example: the label on the card, the composer text a tap leaves, and where the cursor goes. */
    data class Example(val label: String, val text: String, val cursor: Int = text.length)

    val EXAMPLES = listOf(
        Example("Summarise my Official chats today", "Summarise my Official chats today"),
        // The cursor waits at the name ("… to call |").
        Example("Remind me tomorrow at 9 to call …", "Remind me tomorrow at 9 to call "),
        Example("Add a meeting to my calendar", "Add a meeting to my calendar"),
        Example("What did I promise this week?", "What did I promise this week?"),
    )

    private val tokenRe = Regex("(?i)(?<![\\p{L}\\p{N}_@])@risi(?![\\p{L}\\p{N}_])")
    private val directRe = Regex("(?is)^\\s*(?:(?:hey|hi|hello|ok)\\s+)?risi\\s*[,:]\\s*(\\S.*)$")

    /** The text holds the "@Risi" token (any case, as a word). */
    fun mentionsRisi(text: String): Boolean = tokenRe.containsMatchIn(text)

    /**
     * Official: the request text of a message holding the "@Risi" token (the token removed, spaces tidied), or
     * null when it has none or nothing is left.
     */
    fun mentionRequest(text: String): String? {
        if (!mentionsRisi(text)) return null
        val rest = tokenRe.replace(text, " ").replace(Regex("[ \\t]{2,}"), " ").trim().trimStart(',', ':').trim()
        return rest.takeIf { it.isNotEmpty() }
    }

    /** A 1:1 Official: "Risi, <question>" / "Risi: <question>" → the question (null: not addressed to Risi). */
    fun directRequest(text: String): String? = directRe.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The "@…" being typed right before the cursor, when it can still become "@Risi" ("@", "@r", "@Ris"):
     * its range in [text] (the picker replaces it), else null.
     */
    fun pickerQuery(text: String, cursor: Int): IntRange? {
        if (cursor < 1 || cursor > text.length) return null
        val at = text.lastIndexOf('@', cursor - 1)
        if (at < 0) return null
        if (at > 0 && !text[at - 1].isWhitespace()) return null
        val typed = text.substring(at + 1, cursor)
        if (typed.length > 4 || !"risi".startsWith(typed.lowercase())) return null
        return at until cursor
    }

    /** The picker's pick: the typed "@…" goes (the chip says "@Risi"); returns the new text and cursor. */
    fun applyPick(text: String, cursor: Int): Pair<String, Int>? {
        val r = pickerQuery(text, cursor) ?: return null
        val out = text.removeRange(r)
        return out to r.first
    }

    /** The Risi chat has nothing in it yet (system lines, such as the encryption line, don't count). */
    fun emptyRisiChat(messages: List<MessageEntity>): Boolean = messages.none { it.kind != MessageEntity.KIND_SYSTEM }
}
