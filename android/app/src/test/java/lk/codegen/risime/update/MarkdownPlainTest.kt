package lk.codegen.risime.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MarkdownPlainTest {
    private fun p(s: String) = markdownToPlainText(s)

    @Test fun headingsLoseHashes() {
        assertEquals("RisiMe 0.9.0\nWhat's new", p("# RisiMe 0.9.0\n## What's new ##"))
        assertEquals("Title", p("Title\n====="))
        assertEquals("#hashtag stays", p("#hashtag stays"))
    }

    @Test fun emphasisAndCode() {
        assertEquals("bold, italic, also bold, also italic, gone, code", p("**bold**, *italic*, __also bold__, _also italic_, ~~gone~~, `code`"))
        assertEquals("snake_case_name and 2 * 3 * 4", p("snake_case_name and 2 * 3 * 4"))
        assertEquals("a *literal* star", p("a \\*literal\\* star"))
    }

    @Test fun listsBecomeBullets() {
        assertEquals("• one\n• two\n  • nested\n1. first\n• done", p("- one\n* two\n  + nested\n1. first\n- [x] done"))
    }

    @Test fun linksBecomeTheirText() {
        assertEquals("See the release page and logo, or https://x.y/z.", p("See [the release page](https://x.y) and ![logo](a.png), or <https://x.y/z>."))
        assertEquals("ref link", p("[ref link][1]"))
    }

    @Test fun quotesRulesFencesAndBlankLines() {
        assertEquals("quoted\n\ncode line\n\nafter", p("> quoted\n\n---\n\n```kotlin\ncode line\n```\n\n\n\nafter"))
        assertEquals("a b", p("a <b>b</b>"))
    }

    @Test fun realisticNotesHaveNoMarkdownLeft() {
        val notes = """
            # RisiMe 0.9.0

            ## Highlights
            - **Reactions** on messages ([details](https://risicloud.ai/x))
            - _Faster_ sync

            ### Fixes
            1. Update screen scrolls
        """.trimIndent()
        val out = p(notes)
        assertEquals("RisiMe 0.9.0\n\nHighlights\n• Reactions on messages (details)\n• Faster sync\n\nFixes\n1. Update screen scrolls", out)
        for (m in listOf("#", "**", "](", "_F")) assertFalse(m, out.contains(m))
    }
}
