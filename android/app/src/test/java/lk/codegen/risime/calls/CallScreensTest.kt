package lk.codegen.risime.calls

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
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

private fun SemanticsNodeInteraction.assertStateDescription(v: String) = assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, v))

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
        // Earpiece + speaker only: one tap switches to the speaker (no list).
        rule.onNodeWithContentDescription("Audio output").performScrollTo().performClick()
        rule.onNodeWithContentDescription("End call").performScrollTo().performClick()
        assertEquals(listOf("mute=true", "route=Speaker", "end"), events)
        ui = ui.copy(status = "3:12", verified = true)
        rule.onNodeWithText("End-to-end encrypted").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("3:12").performScrollTo().assertIsDisplayed()
    }

    @Test fun bluetoothOpensTheRouteList() {
        val earpiece = EndpointUi("1", "Phone", EndpointUi.Kind.EARPIECE)
        val speaker = EndpointUi("2", "Speaker", EndpointUi.Kind.SPEAKER)
        val bt = EndpointUi("3", "Pixel Buds", EndpointUi.Kind.BLUETOOTH)
        var ui by mutableStateOf(InCallUi("Kamal", "0:12", endpoints = listOf(earpiece, speaker, bt), current = bt, verified = true))
        rule.setContent { RisiMeTheme(dark = dark) { InCallScreen(ui, {}, { e -> events += "route=${e.name}"; ui = ui.copy(current = e) }, {}) } }
        // The button shows the real route: Bluetooth.
        rule.onNodeWithText("Bluetooth").assertExists()
        rule.onNodeWithContentDescription("Audio output").performScrollTo().performClick()
        // The picker: the device's name, Phone and Speaker; the current one checked.
        rule.onNodeWithText("Pixel Buds").assertExists()
        rule.onNodeWithText("Phone").assertExists()
        rule.onNodeWithContentDescription("Selected").assertExists()
        rule.onNodeWithText("Speaker").performClick()
        assertEquals(listOf("route=Speaker"), events)
        rule.onNodeWithText("Speaker").assertExists()
    }

    /** P0 audio routing: Telecom lists no route (or one): the button is still enabled in every in-call phase and still switches. */
    @Test fun noOrOneTelecomRouteKeepsTheRouteButtonWorking() {
        var ui by mutableStateOf(InCallUi("Kamal", "Calling…", endpoints = emptyList()))
        rule.setContent { RisiMeTheme(dark = dark) { InCallScreen(ui, {}, { e -> events += "route=${e.kind}:${e.id}"; ui = ui.copy(current = e) }, {}) } }
        rule.onNodeWithContentDescription("Audio output").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Audio output").assertStateDescription("On")
        rule.onNodeWithText("Speaker").assertExists()
        rule.onNodeWithContentDescription("Audio output").performClick()
        rule.onNodeWithContentDescription("Audio output").assertStateDescription("Off")
        rule.onNodeWithText("Phone").assertExists()
        assertEquals(listOf("route=SPEAKER:am:SPEAKER", "route=EARPIECE:am:EARPIECE"), events)
        // Telecom lists only the earpiece (Harsha's phones): the tap still asks for the speaker.
        events.clear()
        val earpiece = EndpointUi("1", "Phone", EndpointUi.Kind.EARPIECE)
        ui = ui.copy(status = "0:05", active = true, endpoints = listOf(earpiece), current = earpiece)
        rule.onNodeWithContentDescription("Audio output").assertIsEnabled().performClick()
        assertEquals(listOf("route=SPEAKER:am:SPEAKER"), events)
    }

    @Test fun theRouteButtonIsEnabledInEveryInCallPhase() {
        val earpiece = EndpointUi("1", "Phone", EndpointUi.Kind.EARPIECE)
        for (endpoints in listOf(emptyList(), listOf(earpiece))) {
            for (active in listOf(false, true)) {
                assertTrue(routeButtonEnabled(InCallUi("K", "", endpoints = endpoints, active = active)))
            }
        }
        assertTrue(!routeButtonEnabled(InCallUi("K", "", ended = true)))
    }

    /** P0-3: with [earpiece, speaker] the label and the On/Off state follow Telecom's current route. */
    @Test fun earpieceAndSpeakerLabelAndState() {
        val earpiece = EndpointUi("1", "Phone", EndpointUi.Kind.EARPIECE)
        val speaker = EndpointUi("2", "Speaker", EndpointUi.Kind.SPEAKER)
        var ui by mutableStateOf(InCallUi("Kamal", "0:05", endpoints = listOf(earpiece, speaker), current = earpiece, active = true))
        rule.setContent { RisiMeTheme(dark = dark) { InCallScreen(ui, {}, { e -> events += "route=${e.name}"; ui = ui.copy(current = e) }, {}) } }
        rule.onNodeWithContentDescription("Audio output").performScrollTo().assertIsEnabled().assertStateDescription("Off")
        // The label is the real route: the earpiece ("Phone").
        rule.onNodeWithText("Phone").assertIsDisplayed()
        // Telecom moved the video call to the speaker: the button shows it on.
        ui = ui.copy(current = speaker)
        rule.onNodeWithContentDescription("Audio output").assertStateDescription("On")
        rule.onNodeWithContentDescription("Audio output").performClick()
        assertEquals(listOf("route=Phone"), events)
        rule.onNodeWithContentDescription("Audio output").assertStateDescription("Off")
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
        assertEquals("Kamal's phone can't take calls yet", callBlockedText(null, true, false, "Kamal"))
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
