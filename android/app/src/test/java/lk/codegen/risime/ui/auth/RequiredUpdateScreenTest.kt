package lk.codegen.risime.ui.auth

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import lk.codegen.risime.ui.theme.RisiMeTheme
import lk.codegen.risime.update.UpdateInfo
import lk.codegen.risime.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.pow

/** The "Update required" gate on a small phone, large font, long notes: never a dead end (P0). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp", application = Application::class)
class RequiredUpdateScreenTest {
    @get:Rule val rule = createComposeRule()

    private val longNotes = buildString {
        append("# RisiMe 0.9.0\n\n## What's new\n\n")
        repeat(60) { append("- **Item $it**: a [linked](https://example.com/$it) change with `code` and _emphasis_.\n") }
    }
    private val info = UpdateInfo(
        versionCode = 90099, versionName = "0.9.0", date = null, notes = longNotes,
        url = "https://risicloud.ai/app/risime/risime-0.9.0.apk", sha256 = "a".repeat(64), certSha256 = "b".repeat(64),
        required = true,
    )

    private var updates = 0
    private var downloads = 0
    private var signOuts = 0

    private fun gate(state: UpdateState, dark: Boolean, fontScale: Float = 2f) {
        rule.setContent {
            RisiMeTheme(dark = dark) {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    // As in RisiMeRoot: the gate in the remaining space (no global banner, decision 048).
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            RequiredUpdateContent(state, info, { updates++ }, { downloads++ }, { signOuts++ })
                        }
                    }
                }
            }
        }
    }

    private fun assertActionsReachable(primary: String) {
        val rootBottom = rule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        rule.onNodeWithText(primary).assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        assertTrue("$primary below the screen", rule.onNodeWithText(primary).fetchSemanticsNode().boundsInRoot.bottom <= rootBottom)
        rule.onNodeWithText(OPEN_DOWNLOAD_PAGE).assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText(SIGN_OUT_KEEPS_CHATS).assertIsDisplayed().assertIsEnabled()
    }

    private fun assertNotesScroll() {
        val notes = rule.onNodeWithTag(UPDATE_GATE_NOTES_TAG)
        val range = notes.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("long notes must overflow into a scrollable area", range.maxValue() > 0f)
        assertEquals(0f, range.value(), 0f)
        notes.performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertTrue("notes area scrolled", range.value() > 0f)
        // Still reachable after scrolling.
        rule.onNodeWithText("Update").assertIsDisplayed()
    }

    @Test fun longNotesSmallScreenDarkKeepsUpdateReachable() {
        gate(UpdateState.Available(info), dark = true)
        assertActionsReachable("Update")
        assertNotesScroll()
        rule.onNodeWithText("Update").performClick()
        rule.onNodeWithText(OPEN_DOWNLOAD_PAGE).performClick()
        rule.onNodeWithText(SIGN_OUT_KEEPS_CHATS).performClick() // an escape: keeps chats, no dialog
        assertEquals(listOf(1, 1, 1), listOf(updates, downloads, signOuts))
    }

    @Test fun longNotesSmallScreenLightKeepsUpdateReachable() {
        gate(UpdateState.Available(info), dark = false)
        assertActionsReachable("Update")
        assertNotesScroll()
    }

    @Test fun notesRenderAsPlainTextNotMarkdown() {
        gate(UpdateState.Available(info), dark = true, fontScale = 1f)
        rule.onNodeWithText("Item 0: a linked change with code and emphasis.", substring = true).assertExists()
        rule.onAllNodes(hasText("**", substring = true)).assertCountEquals(0)
        rule.onAllNodes(hasText("# ", substring = true)).assertCountEquals(0)
    }

    @Test fun summaryPreferredOverNotes() {
        rule.setContent {
            RisiMeTheme(dark = true) {
                RequiredUpdateContent(UpdateState.Available(info.copy(summary = "Faster sync and reactions.")), info.copy(summary = "Faster sync and reactions."), {}, {}, {})
            }
        }
        rule.onNodeWithText("Faster sync and reactions.").assertIsDisplayed()
        rule.onAllNodes(hasText("Item 0", substring = true)).assertCountEquals(0)
    }

    @Test fun failedStateShowsErrorRetryAndDownloadPage() {
        val msg = "Couldn't download the update: no connection to the update server. Check your internet and retry."
        gate(UpdateState.Failed(info, msg), dark = true)
        rule.onNodeWithText(msg).assertIsDisplayed()
        assertActionsReachable("Retry")
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, updates)
    }

    @Test fun workingStateStillOffersDownloadPageAndSignOut() {
        gate(UpdateState.Working(info, "Downloading…"), dark = true)
        rule.onNodeWithText("Update").assertIsDisplayed()
        rule.onNodeWithText(OPEN_DOWNLOAD_PAGE).assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText(SIGN_OUT_KEEPS_CHATS).assertIsDisplayed().assertIsEnabled()
    }

    // ---- colours: theme roles only, readable on the gate background in dark and light ----

    private fun channel(c: Float): Double = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Composable private fun Capture(dark: Boolean, out: (UpdateGateColors, Color) -> Unit) =
        RisiMeTheme(dark = dark) { out(updateGateColors(MaterialTheme.colorScheme), MaterialTheme.colorScheme.onBackground) }

    @Test fun gateTextHasThemeContrastInDarkAndLight() {
        val seen = mutableMapOf<Boolean, UpdateGateColors>()
        rule.setContent {
            Capture(dark = true) { c, _ -> seen[true] = c }
            Capture(dark = false) { c, _ -> seen[false] = c }
        }
        rule.waitForIdle()
        for ((dark, c) in seen) {
            val mode = if (dark) "dark" else "light"
            for ((name, fg) in listOf("title" to c.title, "subtitle" to c.body, "notes" to c.notes, "error" to c.error)) {
                val r = contrast(fg, c.background)
                assertTrue("$mode $name contrast ${"%.2f".format(r)} < 4.5", r >= 4.5)
            }
        }
        assertEquals(2, seen.size)
        // Dark mode must not fall back to dark-on-dark (the bug: default content colour = black).
        assertFalse(luminance(seen[true]!!.title) < 0.2)
    }
}
