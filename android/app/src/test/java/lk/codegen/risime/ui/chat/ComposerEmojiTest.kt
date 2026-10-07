package lk.codegen.risime.ui.chat

import android.app.Application
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer's emoji sheet stays open, appends every pick at the cursor and ⌫ deletes whole
 * graphemes. The fake picker keeps the FIRST callback it gets, like emoji2's EmojiPickerView (its
 * listener is set once in the AndroidView factory): before the fix each pick replaced the last.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp-night", application = Application::class)
class ComposerEmojiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun severalEmojiInARowThenBackspace() {
        var v by mutableStateOf(TextFieldValue("hi ", TextRange(3)))
        rule.setContent {
            RisiMeTheme(dark = true) {
                Composer(
                    value = v, onValue = { v = it }, onSend = {},
                    emojiPicker = { cb ->
                        val first = remember { cb }
                        listOf("😀", "👍🏽", "👨‍👩‍👧").forEach { e -> TextButton(onClick = { first(e) }) { Text("pick $e") } }
                    },
                )
            }
        }
        rule.onNodeWithContentDescription("Emoji").performClick()
        rule.onNodeWithText("pick 😀").performClick()
        rule.onNodeWithText("pick 👍🏽").performClick()
        rule.onNodeWithText("pick 👨‍👩‍👧").performClick()
        rule.waitForIdle()
        assertEquals("hi 😀👍🏽👨‍👩‍👧", v.text)
        assertEquals(TextRange(v.text.length), v.selection)
        rule.onNodeWithText("pick 😀").assertIsDisplayed() // still open
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitForIdle()
        assertEquals("hi 😀👍🏽", v.text)
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitForIdle()
        assertEquals("hi 😀", v.text)
        // The cursor moved back: the next pick goes at the end again.
        rule.onNodeWithText("pick 👍🏽").performClick()
        rule.waitForIdle()
        assertEquals("hi 😀👍🏽", v.text)
    }
}
