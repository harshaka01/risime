package lk.codegen.risime.ui.common

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P0 2026-10-10 Markdown in Risi bubbles: bold, italics, lists, inline code, links; never a raw "**". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiMarkdownTest {
    @get:Rule val rule = createComposeRule()

    @Test fun boldItalicCodeAndLinks() {
        val a = RisiMarkdown.inline("You have **3 meetings** on *Monday*, see `calendar_check` or [the guide](https://example.com/help).")
        assertEquals("You have 3 meetings on Monday, see calendar_check or the guide.", a.text)
        val bold = a.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals("3 meetings", a.text.substring(bold.start, bold.end))
        val it = a.spanStyles.first { s -> s.item.fontStyle == FontStyle.Italic }
        assertEquals("Monday", a.text.substring(it.start, it.end))
        val link = a.getLinkAnnotations(0, a.length).single()
        assertEquals("https://example.com/help", (link.item as LinkAnnotation.Url).url)
        assertEquals("the guide", a.text.substring(link.start, link.end))
        assertEquals("Bold also __this__ way".replace("__this__", "this"), RisiMarkdown.inline("Bold also __this__ way").text)
    }

    @Test fun noRawDoubleStarsEver() {
        for (s in listOf("**unclosed bold", "a ** b", "trailing**", "**", "x **y** **z", "****")) {
            assertTrue(s, "**" !in RisiMarkdown.plain(s))
        }
        // Single stars that aren't emphasis stay ("2 * 3"), snake_case stays.
        assertEquals("2 * 3 = 6", RisiMarkdown.inline("2 * 3 = 6").text)
        assertEquals("risi_calendar_add", RisiMarkdown.inline("risi_calendar_add").text)
        assertEquals("[not a link](javascript:x)", RisiMarkdown.inline("[not a link](javascript:x)").text)
    }

    @Test fun listsAndHeadings() {
        val text = "# This week\n**Mon 12 Oct**\n- Standup *10:00*\n- Review\n  - nested\n1. First\n2) Second\n\nDone."
        val b = RisiMarkdown.blocks(text)
        assertEquals(RisiMarkdown.Block.Para("This week", heading = true), b[0])
        assertEquals(RisiMarkdown.Block.Para("**Mon 12 Oct**"), b[1])
        assertEquals(RisiMarkdown.Block.Bullet("Standup *10:00*", 0), b[2])
        assertEquals(RisiMarkdown.Block.Bullet("nested", 1), b[4])
        assertEquals(RisiMarkdown.Block.Numbered("2", "Second", 0), b[6])
        assertEquals("This week\nMon 12 Oct\n• Standup 10:00\n• Review\n• nested\n1. First\n2. Second\nDone.", RisiMarkdown.plain(text))
    }

    @Test fun rendersWithoutMarkers() {
        rule.setContent { RisiMeTheme { RisiMarkdownText("You're free on **Monday**.\n\n- Standup\n- Review") } }
        rule.onNodeWithText("You're free on Monday.").assertIsDisplayed()
        rule.onNodeWithText("Standup").assertIsDisplayed()
        rule.onNodeWithText("**", substring = true).assertDoesNotExist()
    }

    @Test fun aPlainAnswerStaysOneText() {
        val body = "You have 3 busy times this week.\n\nChecked: Risi Calendar · Phone calendar."
        rule.setContent { RisiMeTheme { RisiMarkdownText(body) } }
        rule.onNodeWithText(body).assertIsDisplayed()
    }

    @Test fun headerBarGrowsWithFontScale() {
        assertEquals(64f, headerBarHeight(1.0f, twoLines = true).value, 0.01f)
        assertTrue(headerBarHeight(1.3f, twoLines = true).value > 64f)
        assertEquals(64f, headerBarHeight(1.0f, twoLines = false).value, 0.01f)
    }
}
