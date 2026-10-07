package lk.codegen.risime.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/** The composer's emoji picker: several emoji in a row at the cursor, ⌫ by whole graphemes. */
class ComposerEditTest {
    private val icu = GraphemeBoundary { text, offset ->
        val it = com.ibm.icu.text.BreakIterator.getCharacterInstance()
        it.setText(text)
        val b = it.preceding(offset)
        if (b == com.ibm.icu.text.BreakIterator.DONE) 0 else b
    }

    @Test fun severalPicksAppendInOrderAtTheCursor() {
        var v = TextFieldValue("hi there", TextRange(2))
        listOf("😀", "👍🏽", "🇱🇰").forEach { v = insertAtCursor(v, it) }
        assertEquals("hi😀👍🏽🇱🇰 there", v.text)
        assertEquals(TextRange(2 + "😀👍🏽🇱🇰".length), v.selection)
    }

    @Test fun aPickReplacesTheSelection() {
        val v = insertAtCursor(TextFieldValue("hello world", TextRange(6, 11)), "🌍")
        assertEquals("hello 🌍", v.text)
        assertEquals(TextRange(8), v.selection)
    }

    @Test fun backspaceDeletesWholeGraphemes() {
        val family = "👨‍👩‍👧‍👦"
        var v = TextFieldValue("a😀$family👍🏽🇱🇰1️⃣", TextRange("a😀$family👍🏽🇱🇰1️⃣".length))
        val expected = listOf("a😀$family👍🏽🇱🇰", "a😀$family👍🏽", "a😀$family", "a😀", "a", "")
        for (e in expected) {
            v = deleteBeforeCursor(v, icu)
            assertEquals(e, v.text)
            assertEquals(TextRange(e.length), v.selection)
        }
        assertEquals(v, deleteBeforeCursor(v, icu)) // nothing before the cursor
    }

    @Test fun backspaceInTheMiddleAndOnASelection() {
        val v = deleteBeforeCursor(TextFieldValue("x👍🏽y", TextRange(1 + "👍🏽".length)), icu)
        assertEquals("xy", v.text)
        assertEquals(TextRange(1), v.selection)
        assertEquals("ad", deleteBeforeCursor(TextFieldValue("abcd", TextRange(1, 3)), icu).text)
    }
}
