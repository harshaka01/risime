package lk.codegen.risime.calls

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The one call screen (overnight item 3): panel per state, Speaker, the switch prompt, auto-hide, minimise, E2EE label. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp", application = Application::class)
class CallScreenTest {
    @get:Rule val rule = createComposeRule()
    private val events = mutableListOf<String>()
    private val earpiece = EndpointUi("1", "Phone", EndpointUi.Kind.EARPIECE)
    private val speaker = EndpointUi("2", "Speaker", EndpointUi.Kind.SPEAKER)

    private fun actions() = CallScreenActions(
        onMinimise = { events += "minimise" },
        onAnswer = { events += "answer" },
        onAnswerWithoutVideo = { events += "answer-no-video" },
        onDecline = { events += "decline" },
        onMute = { events += "mute=$it" },
        onEndpoint = { events += "route=${it.kind}" },
        onVideo = { events += "video" },
        onShare = { events += "share" },
        onEnd = { events += "end" },
        onCancelAsk = { events += "cancel-ask" },
        onPrompt = { a, c -> events += "prompt=$a,$c" },
        onStopShare = { events += "stop-share" },
    )

    private fun show(ui: CallScreenUi, remote: Boolean = false) = rule.setContent {
        RisiMeTheme {
            CallScreen(ui, actions(), remote = if (remote) ({ m -> Box(m.background(Color.DarkGray)) }) else null, local = { m -> Box(m.background(Color.Gray)) })
        }
    }

    private fun connected(video: Boolean = false, encrypted: Boolean = true, current: EndpointUi = earpiece) = CallScreenUi(
        name = "Kamal Perera", status = "0:12", stage = CallStage.ACTIVE, encrypted = encrypted, video = video,
        endpoints = listOf(earpiece, speaker), current = current,
        videoControl = ControlUi(description = "Switch to video"), shareControl = ControlUi(description = "Share screen"),
    )

    @Test fun incomingVoiceHasAnswerAndDeclineOnly() {
        show(CallScreenUi("Kamal Perera", "RisiMe voice call", CallStage.INCOMING))
        rule.onNodeWithText("Kamal Perera").assertIsDisplayed()
        rule.onNodeWithContentDescription("Answer").performClick()
        rule.onNodeWithContentDescription("Decline").performClick()
        assertTrue(runCatching { rule.onNodeWithContentDescription("Answer without video").assertExists() }.isFailure)
        assertTrue("no panel while ringing", runCatching { rule.onNodeWithContentDescription("End call").assertExists() }.isFailure)
        assertTrue("no minimise while ringing", runCatching { rule.onNodeWithContentDescription("Minimise").assertExists() }.isFailure)
        assertEquals(listOf("answer", "decline"), events)
    }

    @Test fun incomingVideoOffersAnswerWithoutVideo() {
        show(CallScreenUi("Kamal Perera", "Incoming video call", CallStage.INCOMING, incomingVideo = true))
        rule.onNodeWithText("Incoming video call").assertIsDisplayed()
        rule.onNodeWithContentDescription("Answer without video").performClick()
        assertEquals(listOf("answer-no-video"), events)
    }

    @Test fun outgoingShowsTheTwoRowPanel() {
        show(connected(encrypted = false).copy(status = "Calling…", stage = CallStage.OUTGOING, videoControl = ControlUi(enabled = false, description = "Switch to video"), shareControl = ControlUi(enabled = false, description = "Share screen")))
        rule.onNodeWithText("Calling…").assertIsDisplayed()
        for (d in listOf("Audio output", "Mute", "More options", "End call", "Minimise")) rule.onNodeWithContentDescription(d).assertIsDisplayed()
        rule.onNodeWithContentDescription("Switch to video").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Share screen").assertIsNotEnabled()
        // Speaker always enabled (P0 routing), even before connecting.
        rule.onNodeWithContentDescription("Audio output").assertIsEnabled()
        assertTrue("never claims encryption before it is verified", runCatching { rule.onNodeWithText("End-to-end encrypted").assertExists() }.isFailure)
        rule.onNodeWithContentDescription("End call").performClick()
        assertEquals(listOf("end"), events)
    }

    @Test fun connectedVoiceSpeakerHighlightAndEncryptedLabel() {
        var ui by mutableStateOf(connected())
        rule.setContent { RisiMeTheme { CallScreen(ui, actions().copy(onEndpoint = { e -> events += "route=${e.kind}"; ui = ui.copy(current = e) })) } }
        rule.onNodeWithText("End-to-end encrypted").assertIsDisplayed()
        rule.onNodeWithText("Speaker").assertIsDisplayed()
        rule.onNodeWithContentDescription("Audio output").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
        rule.onNodeWithContentDescription("Audio output").performClick()
        rule.onNodeWithContentDescription("Audio output").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
        rule.onNodeWithContentDescription("Switch to video").assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Share screen").performClick()
        rule.onNodeWithContentDescription("Mute").performClick()
        assertEquals(listOf("route=SPEAKER", "video", "share", "mute=true"), events)
    }

    @Test fun minimiseKeepsTheCall() {
        show(connected())
        rule.onNodeWithContentDescription("Minimise").performClick()
        assertEquals(listOf("minimise"), events)
    }

    @Test fun switchPromptAcceptWithoutCameraAndDecline() {
        var ui by mutableStateOf(connected().copy(prompt = CallEnvelope.SOURCE_CAMERA))
        rule.setContent { RisiMeTheme { CallScreen(ui, actions().copy(onPrompt = { a, c -> events += "prompt=$a,$c"; ui = ui.copy(prompt = null) })) } }
        rule.onNodeWithText("Switch to video call?").assertIsDisplayed()
        rule.onNodeWithText("Kamal Perera wants to switch to video").assertIsDisplayed()
        rule.onNodeWithText("Accept").performClick()
        ui = ui.copy(prompt = CallEnvelope.SOURCE_CAMERA)
        rule.onNodeWithText("Without my camera").performClick()
        ui = ui.copy(prompt = CallEnvelope.SOURCE_CAMERA)
        rule.onNodeWithText("Decline").performClick()
        ui = ui.copy(prompt = CallEnvelope.SOURCE_SCREEN)
        rule.onNodeWithText("Kamal Perera wants to share their screen").assertIsDisplayed()
        rule.onNodeWithText("Watch").performClick()
        assertEquals(listOf("prompt=true,true", "prompt=true,false", "prompt=false,false", "prompt=true,false"), events)
    }

    @Test fun askingLineSharingBannerAndPeerLabel() {
        show(connected(video = true).copy(asking = "Asking Kamal Perera to switch to video…", sharing = true, peerSharing = true), remote = true)
        rule.onNodeWithText("Asking Kamal Perera to switch to video…").assertIsDisplayed()
        rule.onNodeWithText("You're sharing your screen").assertIsDisplayed()
        rule.onNodeWithText("Kamal Perera is sharing their screen").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.onAllNodesWithText("Stop")[0].performClick()
        assertEquals(listOf("cancel-ask", "stop-share"), events)
    }

    @Test fun videoControlsAutoHideAfterFourSecondsAndATapBringsThemBack() {
        rule.mainClock.autoAdvance = false
        show(connected(video = true).copy(showLocal = true), remote = true)
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithContentDescription("End call").assertIsDisplayed()
        rule.onNodeWithContentDescription("Your camera").assertIsDisplayed()
        // Left alone for 4 s: hidden.
        rule.mainClock.advanceTimeBy(CONTROLS_HIDE_MS + 1_000)
        assertTrue(runCatching { rule.onNodeWithContentDescription("End call").assertIsDisplayed() }.isFailure)
        // A tap (or TalkBack's action on the stage) toggles them: the stage's action label follows.
        val stage = rule.onNodeWithTag("call-stage")
        stage.assert(SemanticsMatcher("show") { it.config[SemanticsActions.OnClick].label == "Show call controls" })
        stage.performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeByFrame()
        stage.assert(SemanticsMatcher("hide") { it.config[SemanticsActions.OnClick].label == "Hide call controls" })
    }

    @Test fun voiceControlsNeverHide() {
        assertFalse(controlsAutoHide(connected()))
        assertTrue(controlsAutoHide(connected(video = true)))
        assertFalse(controlsAutoHide(connected(video = true).copy(prompt = CallEnvelope.SOURCE_CAMERA)))
        assertFalse(controlsAutoHide(connected(video = true).copy(stage = CallStage.OUTGOING)))
    }

    // ---- the snapshot → screen rules ----

    private val snap = CallSnapshot("c", "dm:x", "peer", outgoing = true, phase = CallPhase.ACTIVE, verified = true, canSwitch = true, canShare = true)

    @Test fun videoButtonRules() {
        assertEquals(VideoAction.REQUEST, videoAction(snap, 0))
        assertEquals(VideoAction.NONE, videoAction(snap.copy(canSwitch = false), 0))
        assertEquals("Kamal needs to update RisiMe to switch to video", videoControl(snap.copy(canSwitch = false), "Kamal", 0).hint)
        assertEquals(VideoAction.NONE, videoAction(snap.copy(asking = "camera"), 0))
        assertEquals(VideoAction.NONE, videoAction(snap.copy(videoBlockedUntilMs = 10), 5))
        assertEquals(VideoAction.NONE, videoAction(snap.copy(phase = CallPhase.CALLING), 0))
        assertEquals(VideoAction.BACK_TO_VOICE, videoAction(snap.copy(video = true, wantCamera = true), 0))
        assertEquals(VideoAction.CAMERA_ON, videoAction(snap.copy(video = true, wantCamera = false), 0))
        // An old peer in a video call: v1.18 camera on/off.
        assertEquals(VideoAction.CAMERA_OFF, videoAction(snap.copy(video = true, wantCamera = true, canSwitch = false), 0))
        assertEquals("Turn camera off", videoControl(snap.copy(video = true, wantCamera = true, canSwitch = false), "K", 0).description)
        assertEquals("Switch to voice call", videoControl(snap.copy(video = true, wantCamera = true), "K", 0).description)
    }

    @Test fun shareButtonRules() {
        assertTrue(shareControl(snap, "K", 0).enabled)
        assertFalse(shareControl(snap.copy(canShare = false), "K", 0).enabled)
        assertFalse(shareControl(snap.copy(group = true), "K", 0).enabled)
        assertEquals("Stop sharing", shareControl(snap.copy(sharing = true, video = true), "K", 0).description)
    }

    @Test fun screenStateFromASnapshot() {
        val routes = CallRoutes(listOf(earpiece, speaker), speaker)
        val ui = callScreenUi(snap.copy(video = true, cameraOn = true, wantCamera = true, peerSharing = true, prompt = null, switchNotice = SwitchNotice.DECLINED), "Kamal", "0:10", routes, 0)
        assertTrue(ui.encrypted && ui.video && ui.showLocal && ui.canFlip && ui.canCameraOff && ui.peerSharing)
        assertEquals("Kamal declined video", ui.notice)
        assertFalse(callScreenUi(snap.copy(verified = false), "Kamal", "", routes, 0).encrypted)
        assertEquals(CallStage.INCOMING, callScreenUi(snap.copy(phase = CallPhase.RINGING_IN, video = true), "K", "", routes, 0).stage)
        assertTrue(callScreenUi(snap.copy(phase = CallPhase.RINGING_IN, video = true), "K", "", routes, 0).incomingVideo)
    }
}
