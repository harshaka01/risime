package lk.codegen.risime.calls

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import lk.codegen.risime.ui.chat.callBlockedText
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** §16 incoming and in-call screens on a small phone, in light and dark (decision 040). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp", application = Application::class)
class CallScreensTest(private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @get:Rule val rule = createComposeRule()
    private val events = mutableListOf<String>()

    @Test fun incomingShowsTheCallerAnswerAndDecline() {
        rule.setContent { RisiMeTheme(dark = dark) { IncomingCallScreen("Kamal Perera", onAnswer = { events += "answer" }, onDecline = { events += "decline" }) } }
        rule.onNodeWithText("Kamal Perera").assertIsDisplayed()
        rule.onNodeWithText("RisiMe voice call").assertIsDisplayed()
        rule.onNodeWithContentDescription("Answer").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Decline").performScrollTo().performClick()
        assertEquals(listOf("answer", "decline"), events)
    }

    @Test fun lockedRingShowsNoName() {
        rule.setContent { RisiMeTheme(dark = dark) { IncomingCallScreen(null, onAnswer = { events += "answer" }, onDecline = {}) } }
        rule.onNodeWithText("Incoming RisiMe call").assertIsDisplayed()
        rule.onNodeWithText("Unlock RisiMe to see who's calling").assertIsDisplayed()
        rule.onNodeWithContentDescription("Answer").performScrollTo().performClick()
        assertEquals(listOf("answer"), events)
    }

    @Test fun inCallMuteRouteEndAndTheEncryptedLabelOnlyWhenVerified() {
        val earpiece = EndpointUi("1", "Phone", EndpointUi.Kind.EARPIECE)
        val speaker = EndpointUi("2", "Speaker", EndpointUi.Kind.SPEAKER)
        var ui by mutableStateOf(InCallUi("Kamal Perera", "Connecting…", endpoints = listOf(earpiece, speaker), current = earpiece))
        rule.setContent {
            RisiMeTheme(dark = dark) {
                InCallScreen(ui, onMute = { m -> events += "mute=$m"; ui = ui.copy(muted = m) }, onEndpoint = { e -> events += "route=${e.name}"; ui = ui.copy(current = e) }, onEnd = { events += "end" })
            }
        }
        rule.onNodeWithText("Connecting…").assertIsDisplayed()
        assertTrue(runCatching { rule.onNodeWithText("End-to-end encrypted").assertIsDisplayed() }.isFailure)
        rule.onNodeWithContentDescription("Mute").performScrollTo().performClick()
        rule.onNodeWithText("Muted").assertIsDisplayed()
        rule.onNodeWithContentDescription("Audio output").performScrollTo().performClick()
        rule.onNodeWithText("Speaker").performClick()
        rule.onNodeWithContentDescription("End call").performScrollTo().performClick()
        assertEquals(listOf("mute=true", "route=Speaker", "end"), events)
        ui = ui.copy(status = "3:12", verified = true)
        rule.onNodeWithText("End-to-end encrypted").assertIsDisplayed()
        rule.onNodeWithText("3:12").assertIsDisplayed()
    }

    @Test fun oneRouteDisablesThePickerAndAnEndedCallHasNoControls() {
        rule.setContent {
            RisiMeTheme(dark = dark) {
                InCallScreen(InCallUi("Kamal", "Can't connect the call", endpoints = listOf(EndpointUi("1", "Phone", EndpointUi.Kind.EARPIECE)), ended = true), {}, {}, {})
            }
        }
        rule.onNodeWithText("Can't connect the call").assertIsDisplayed()
        assertTrue(runCatching { rule.onNodeWithContentDescription("End call").assertIsDisplayed() }.isFailure)
    }

    @Test fun callLineOffersCallBackAndDeleteForMe() {
        rule.setContent { RisiMeTheme(dark = dark) { CallLineRow("Missed voice call", missed = true, onCallBack = { events += "back" }, onDeleteForMe = { events += "del" }) } }
        rule.onNodeWithText("📞 Missed voice call").performClick()
        rule.onNodeWithText("Call back").performClick()
        rule.onNodeWithText("📞 Missed voice call").performClick()
        rule.onNodeWithText("Delete for me").performClick()
        assertEquals(listOf("back", "del"), events)
    }

    @Test fun buttonRulesAndTexts() {
        assertEquals("Calls aren't supported on this phone", callBlockedText("Calls aren't supported on this phone", true, true, "Kamal"))
        assertEquals("Calls need an end-to-end encrypted chat.", callBlockedText(null, false, true, "Kamal"))
        assertEquals("Kamal needs to update the app to receive calls", callBlockedText(null, true, false, "Kamal"))
        assertNull(callBlockedText(null, true, true, "Kamal"))
        assertEquals("Kamal is on another call", CallTexts.notice(CallNotice.BUSY, "Kamal"))
        assertEquals("Can't connect the call", CallTexts.notice(CallNotice.CANT_CONNECT, "Kamal"))
        assertEquals("Ringing…", CallTexts.status(CallPhase.RINGING_OUT, null))
        assertEquals("1:02:03", CallLines.duration(3723))
        // §16.6 table.
        assertEquals("Voice call · 3:12", CallLines.line("hangup", false, 192, false).text)
        assertEquals(CallLines.Line("Missed voice call", true), CallLines.line("timeout", false, null, false))
        assertEquals("Voice call · No answer", CallLines.line("busy", true, null, false).text)
        assertEquals("Declined voice call", CallLines.line("declined", true, null, false).text)
        assertEquals("Voice call · Declined", CallLines.line("declined", false, null, false).text)
        assertEquals("Voice call · Couldn't connect", CallLines.line("failed", false, null, false).text)
        assertEquals(CallLines.Line("Missed voice call", true), CallLines.line("failed", false, null, true))
        assertEquals("Voice call", CallLines.line("exploded", false, null, false).text)
    }
}
