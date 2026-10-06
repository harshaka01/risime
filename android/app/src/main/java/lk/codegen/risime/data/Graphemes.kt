package lk.codegen.risime.data

/** Counts extended grapheme clusters (UAX #29): android.icu on devices, ICU4J in JVM tests. */
fun interface GraphemeCounter {
    fun count(text: String): Int
}

/** android.icu.text.BreakIterator (character instance), API 24+. */
object IcuGraphemes : GraphemeCounter {
    override fun count(text: String): Int {
        if (text.isEmpty()) return 0
        val it = android.icu.text.BreakIterator.getCharacterInstance()
        it.setText(text)
        var n = 0
        while (it.next() != android.icu.text.BreakIterator.DONE) n++
        return n
    }
}

/** §11.1: the composer's view of the limits. The server stays authoritative (`too_long`). */
data class BodyLimits(val graphemes: Int, val bytes: Int) {
    val tooLong: Boolean get() = graphemes > MAX_GRAPHEMES || bytes > MAX_BYTES

    /** Show "3,950 / 4,096" from 3,900 graphemes (or near the byte cap). */
    val showCounter: Boolean get() = graphemes >= COUNTER_FROM || bytes > MAX_BYTES - 1_024

    companion object {
        const val MAX_GRAPHEMES = 4096
        const val MAX_BYTES = 16 * 1024
        const val COUNTER_FROM = 3900

        fun of(text: String, counter: GraphemeCounter) = BodyLimits(counter.count(text), text.toByteArray(Charsets.UTF_8).size)
    }
}

/**
 * §11.2 reaction emoji: exactly one grapheme, ≤ 32 bytes of UTF-8, no control or whitespace
 * characters except ZWJ (U+200D) and variation selectors.
 */
fun isValidReactionEmoji(e: String, counter: GraphemeCounter): Boolean {
    if (e.isEmpty() || e.toByteArray(Charsets.UTF_8).size > 32 || counter.count(e) != 1) return false
    var i = 0
    while (i < e.length) {
        val cp = e.codePointAt(i)
        val allowed = cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF
        if (!allowed && (Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.getType(cp) == Character.FORMAT.toInt() && cp !in 0xE0020..0xE007F)) return false
        i += Character.charCount(cp)
    }
    return true
}
