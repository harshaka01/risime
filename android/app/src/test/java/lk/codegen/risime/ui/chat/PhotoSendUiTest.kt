package lk.codegen.risime.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.push.planChatNotifications
import lk.codegen.risime.ui.group.GroupMessageList
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Photos going out (Harsha's P4): the sender's bubble shows the encrypt / upload % / sending steps,
 * a waiting upload and a failed one offer a Retry tap target ("Couldn't send photo · Retry"), in a
 * DM and in a group; the attach sheet shows the encrypting step; group previews say "📷 Photo".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp", application = Application::class)
class PhotoSendUiTest {
    @get:Rule val rule = createComposeRule()

    private fun msg(id: String, status: MessageStatus, conv: String = "dm:a_b", outgoing: Boolean = true, fail: String? = null, body: String = "") =
        MessageEntity(
            id, null, conv, if (outgoing) "me" else "u-b", if (conv.startsWith("grp:")) conv else "u-b", body, null, 1_000,
            status.name, outgoing, failReason = fail, kind = MessageEntity.KIND_IMAGE,
        )

    private fun media(id: String, state: MediaState, conv: String = "dm:a_b", attempts: Int = 0, fail: String? = null, outgoing: Boolean = true) = MediaEntity(
        id, conv, outgoing, state.name, null, 17, "x", "cb-1", ByteArray(1), null, "image/jpeg", 400, 300, "f.enc", 0, null, 0,
        attempts = attempts, failReason = fail,
    )

    @Test fun sendStatesFromTheRowsAndTheProgress() {
        val pending = msg("c1", MessageStatus.PENDING)
        assertEquals(PhotoSend.Encrypted, photoSendState(pending, media("c1", MediaState.ENCRYPTED), null))
        assertEquals(PhotoSend.Uploading(0.42f), photoSendState(pending, media("c1", MediaState.UPLOADING), 0.42f))
        assertEquals(PhotoSend.Uploading(null), photoSendState(pending, media("c1", MediaState.UPLOADING), null))
        assertEquals(PhotoSend.Waiting, photoSendState(pending, media("c1", MediaState.ENCRYPTED, attempts = 2), null))
        assertEquals(PhotoSend.Sending, photoSendState(pending, media("c1", MediaState.UPLOADED), null))
        assertEquals(
            PhotoSend.Failed("You've reached your photo storage limit. Older photos free up space after 30 days."),
            photoSendState(msg("c1", MessageStatus.FAILED, fail = "quota_exceeded"), media("c1", MediaState.FAILED, fail = "quota_exceeded"), null),
        )
        // Uploaded, then the send failed: still a clear failure with Retry.
        assertEquals(PhotoSend.Failed(PHOTO_SEND_FAILED), photoSendState(msg("c1", MessageStatus.FAILED, fail = "network"), media("c1", MediaState.UPLOADED), null))
        assertNull(photoSendState(msg("c1", MessageStatus.SENT), media("c1", MediaState.UPLOADED), null))
        assertNull(photoSendState(msg("c1", MessageStatus.DELIVERED, outgoing = false), media("c1", MediaState.NONE, outgoing = false), null))
        assertEquals("Uploading 42%", photoSendText(PhotoSend.Uploading(0.42f)))
        assertEquals("Encrypted · waiting to upload", photoSendText(PhotoSend.Encrypted))
    }

    /** The merged bubble TalkBack reads (its children are cleared): "You: Photo, Uploading 42%, 10:00, Pending". */
    private fun bubble(text: String) = rule.onNodeWithContentDescription(text, substring = true)

    private fun hasRetry(n: SemanticsNodeInteraction) =
        n.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == PHOTO_RETRY } == true

    private fun retry(n: SemanticsNodeInteraction) {
        val action = n.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == PHOTO_RETRY }
        rule.runOnIdle { action.action() }
    }

    @Test fun imageBoxShowsADeterminateRingAndARetryTapTarget() {
        var progress by mutableStateOf<Float?>(0.42f)
        var action by mutableStateOf<Pair<String, () -> Unit>?>(null)
        var status by mutableStateOf("Uploading 42%")
        var tapped = 0
        rule.setContent { RisiMeTheme(dark = true) { ImageBox(400, 300, null, null, status, busy = true, progress = progress, action = action) } }
        rule.onNodeWithText("Uploading 42%").assertIsDisplayed()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.42f, 0f..1f))).assertExists()
        progress = null
        status = PHOTO_SEND_FAILED
        action = PHOTO_RETRY to { tapped++ }
        rule.onNodeWithText(PHOTO_SEND_FAILED).assertIsDisplayed()
        rule.onNodeWithText(PHOTO_RETRY).assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(1, tapped)
    }

    @Test fun dmBubbleShowsTheUploadPercentThenAFailureWithRetry() {
        var m by mutableStateOf(msg("c1", MessageStatus.PENDING))
        var row by mutableStateOf(media("c1", MediaState.UPLOADING))
        var upload by mutableStateOf<Float?>(0.42f)
        val retried = mutableListOf<String>()
        rule.setContent {
            RisiMeTheme(dark = false) {
                Bubble(
                    m, canRetry = true, onRetry = { retried += it }, onDelete = {}, chips = emptyList(), canReact = false,
                    onReact = { _, _ -> }, onOpenReactions = {}, media = row, upload = upload,
                )
            }
        }
        bubble("You: Photo, Uploading 42%").assertIsDisplayed()
        assertFalse(hasRetry(bubble("You: Photo")))

        // A network drop: waiting for the automatic retry; Retry runs it now.
        upload = null
        row = media("c1", MediaState.ENCRYPTED, attempts = 1)
        bubble("Upload interrupted · retrying").assertIsDisplayed()
        retry(bubble("You: Photo"))
        assertEquals(listOf("c1"), retried)

        // A refusal: "Couldn't send photo · Retry".
        m = msg("c1", MessageStatus.FAILED, fail = "file_lost")
        row = media("c1", MediaState.FAILED, fail = "file_lost")
        bubble("Photo, $PHOTO_SEND_FAILED").assertIsDisplayed()
        retry(bubble("You: Photo"))
        assertEquals(listOf("c1", "c1"), retried)

        // Retried: back to encrypted, no Retry.
        m = msg("c1", MessageStatus.PENDING)
        row = media("c1", MediaState.ENCRYPTED)
        bubble("Encrypted · waiting to upload").assertIsDisplayed()
        assertFalse(hasRetry(bubble("You: Photo")))
    }

    @Test fun aFormerFriendsFailedPhotoHasNoRetry() {
        rule.setContent {
            RisiMeTheme(dark = true) {
                Bubble(
                    msg("c1", MessageStatus.FAILED, fail = "not_friends"), canRetry = false, onRetry = {}, onDelete = {}, chips = emptyList(),
                    canReact = false, onReact = { _, _ -> }, onOpenReactions = {}, media = media("c1", MediaState.UPLOADED),
                )
            }
        }
        bubble(PHOTO_SEND_FAILED).assertIsDisplayed()
        assertFalse(hasRetry(bubble(PHOTO_SEND_FAILED)))
    }

    @Test fun groupPhotosSendAndReceiveInTheGroupList() {
        val conv = "grp:g1"
        val retried = mutableListOf<String>()
        val rows = listOf(
            msg("in1", MessageStatus.DELIVERED, conv, outgoing = false, body = "Level 3"),
            msg("up1", MessageStatus.PENDING, conv),
            msg("bad1", MessageStatus.FAILED, conv, fail = "too_large"),
        )
        val mediaRows = mapOf(
            "in1" to media("in1", MediaState.NONE, conv, outgoing = false),
            "up1" to media("up1", MediaState.UPLOADING, conv),
            "bad1" to media("bad1", MediaState.FAILED, conv, fail = "too_large"),
        )
        rule.setContent {
            RisiMeTheme(dark = false) {
                GroupMessageList(
                    messages = rows, meId = "me", nameOf = { "Kamal" }, memberName = { "Kamal" }, readOnly = false,
                    reactions = emptyMap(), onReact = { _, _, _ -> }, onOpenReactions = {}, onRetry = { retried += it }, onDelete = {},
                    onInfo = {}, modifier = Modifier.fillMaxSize(), media = mediaRows,
                    uploads = mapOf("up1" to 0.7f),
                )
            }
        }
        bubble("You: Photo, Uploading 70%").assertIsDisplayed()
        bubble("You: Photo, This photo is too large").assertIsDisplayed()
        retry(bubble("This photo is too large"))
        assertEquals(listOf("bad1"), retried)
        // The received group photo: "Kamal: Photo, Level 3, Tap to download" (a tap downloads).
        bubble("Kamal: Photo, Level 3, Tap to download").assertIsDisplayed().assertHasClickAction()
    }

    @Test fun aCaptionTooLongBlocksSend() {
        val long = "x".repeat(lk.codegen.risime.data.BodyLimits.MAX_GRAPHEMES + 1)
        rule.setContent {
            RisiMeTheme(dark = false) {
                PhotoPreviewContent(listOf(PickedPhoto("p1", android.net.Uri.parse("content://x/1"), long)), { null }, null, { _, _ -> }, {}, {}, {})
            }
        }
        rule.onNodeWithText("Send photo").assertIsDisplayed()
        rule.onNodeWithText("A caption is too long").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send photo").assertIsNotEnabled()
    }

    @Test fun groupNotificationsSayPhoto() {
        val m = msg("in1", MessageStatus.DELIVERED, "grp:g1", outgoing = false).copy(localTs = 10)
        val n = planChatNotifications(listOf(m), emptyList(), 0, groupNames = mapOf("grp:g1" to "Site team"), memberNames = mapOf("grp:g1" to mapOf("u-b" to "Kamal"))).single()
        assertEquals(listOf("Kamal: 📷 Photo"), n.lines)
        assertEquals("Site team", n.title)
    }
}
