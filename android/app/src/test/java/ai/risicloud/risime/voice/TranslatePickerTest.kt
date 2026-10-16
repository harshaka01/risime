package ai.risicloud.risime.voice

import android.app.Application
import android.content.Context
import ai.risicloud.risime.ui.chat.TranslateLanguageButtons
import ai.risicloud.risime.ui.settings.alwaysTranslateChips
import ai.risicloud.risime.ui.theme.RisiMeTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Architect 2026-10-16 14:05 item 3: the Translate picker (si / en / ta first, More languages…, recents on top). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TranslatePickerTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun recent() = RecentTranslateLanguages(app.getSharedPreferences("t_${System.nanoTime()}", Context.MODE_PRIVATE))
    private val many = TranslateLanguages.supported(listOf("en", "si", "ta", "fr", "hi", "de", "ml"))

    @Test fun defaultsAreSinhalaEnglishTamilWithoutTheSource() {
        assertEquals(listOf("si", "en", "ta"), TranslateLanguages.buttons(null, TranslateLanguages.supported(null)))
        assertEquals(listOf("en", "ta"), TranslateLanguages.buttons("si", TranslateLanguages.supported(null)))
        assertEquals(listOf("si", "en"), TranslateLanguages.buttons("ta", many))
        assertFalse("today's server: no More languages…", TranslateLanguages.hasMore(TranslateLanguages.supported(null)))
        assertTrue(TranslateLanguages.hasMore(many))
        assertEquals("the defaults lead, then the server's order", listOf("si", "en", "ta", "fr", "hi", "de", "ml"), many)
    }

    @Test fun theListPutsRecentPicksFirstSearchesAndHidesTheSource() {
        val (top, rest) = TranslateLanguages.list("", many, recent = listOf("hi", "xx", "si"), source = "si")
        assertEquals("unsupported and source codes never show", listOf("hi"), top)
        assertFalse("si" in rest || "hi" in rest)
        assertEquals(rest.sortedBy { TranslateLanguages.name(it) }, rest)
        assertEquals(listOf("fr") to emptyList<String>(), TranslateLanguages.list("fre", many, listOf("fr"), null))
        assertEquals(listOf("ml"), TranslateLanguages.list("malay", many, emptyList(), null).second)
        assertEquals("by code", listOf("de"), TranslateLanguages.list("de", many, emptyList(), null).second)
        assertEquals("French", TranslateLanguages.name("fr"))
        assertEquals("Sinhala", TranslateLanguages.name("si"))
    }

    @Test fun recentPicksAreKeptMostRecentFirstAndCapped() {
        val r = recent()
        listOf("fr", "hi", "fr", "de", "ml", "ta", "si").forEach(r::record)
        assertEquals(listOf("si", "ta", "ml", "de", "fr"), r.list())
    }

    @Test fun alwaysTranslateOffersTheDefaultsAndTheCurrentOtherPick() {
        assertEquals(listOf("off", "si", "en", "ta"), alwaysTranslateChips("off", TranslateLanguages.supported(null)))
        assertEquals(listOf("off", "si", "en", "ta", "fr"), alwaysTranslateChips("fr", many))
    }

    @Test fun moreLanguagesOpensASearchableListAndRemembersThePick() {
        val r = recent()
        val picked = mutableListOf<String>()
        rule.setContent { RisiMeTheme { TranslateLanguageButtons("ta", many, r, label = { "Translate to " + TranslateLanguages.name(it) }) { picked += it } } }
        rule.onNodeWithText("Translate to Sinhala").assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasTestTag("translate_ta")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("translate_more").performClick()
        rule.onNodeWithTag("translate_search").performTextInput("fre")
        rule.onNodeWithTag("translate_pick_fr").performClick()
        assertEquals(listOf("fr"), picked)
        assertEquals(listOf("fr"), r.list())
        // Next time French is the first row, under "Recent".
        rule.onNodeWithTag("translate_more").performClick()
        rule.onNodeWithText("Recent").assertIsDisplayed()
        rule.onNodeWithTag("translate_pick_recent_fr").assertIsDisplayed()
    }

    @Test fun withoutTheServersListThereIsNoMoreLanguages() {
        rule.setContent { RisiMeTheme { TranslateLanguageButtons(null, TranslateLanguages.supported(null), recent(), label = { it }) {} } }
        assertTrue(rule.onAllNodes(hasTestTag("translate_more")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("translate_si").assertIsDisplayed()
    }
}
