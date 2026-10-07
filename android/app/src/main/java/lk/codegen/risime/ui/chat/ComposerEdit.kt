package lk.codegen.risime.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** The grapheme boundary before [offset] in [text] (UAX #29): android.icu on devices, ICU4J in tests. */
fun interface GraphemeBoundary {
    fun preceding(text: String, offset: Int): Int
}

object IcuGraphemeBoundary : GraphemeBoundary {
    override fun preceding(text: String, offset: Int): Int {
        if (offset <= 0) return 0
        val it = android.icu.text.BreakIterator.getCharacterInstance()
        it.setText(text)
        val b = it.preceding(offset.coerceAtMost(text.length))
        return if (b == android.icu.text.BreakIterator.DONE) 0 else b
    }
}

/**
 * The emoji picker's insert: [s] replaces the selection (or goes in at the cursor) and the cursor
 * moves after it, so picking several emoji in a row appends them in order.
 */
fun insertAtCursor(v: TextFieldValue, s: String): TextFieldValue {
    val start = v.selection.min.coerceIn(0, v.text.length)
    val end = v.selection.max.coerceIn(0, v.text.length)
    return TextFieldValue(v.text.replaceRange(start, end, s), TextRange(start + s.length))
}

/**
 * The picker's backspace: deletes the selection, else the whole grapheme before the cursor (a ZWJ
 * family, a flag, a skin-toned emoji or a keycap goes in one tap, never half a surrogate pair).
 */
fun deleteBeforeCursor(v: TextFieldValue, boundary: GraphemeBoundary): TextFieldValue {
    val start = v.selection.min.coerceIn(0, v.text.length)
    val end = v.selection.max.coerceIn(0, v.text.length)
    if (start != end) return TextFieldValue(v.text.removeRange(start, end), TextRange(start))
    if (start == 0) return v
    val from = boundary.preceding(v.text, start).coerceIn(0, start - 1)
    return TextFieldValue(v.text.removeRange(from, start), TextRange(from))
}
