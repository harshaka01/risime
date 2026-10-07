package lk.codegen.risime.ui.chat

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.LastMessage
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.push.bodyPreview
import lk.codegen.risime.push.planChatNotifications
import lk.codegen.risime.ui.chats.dmPreview
import lk.codegen.risime.ui.common.MessageBubble
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §14 UI: attach states, the viewer, the not-e2ee refusal, bubble states, previews; dark mode on a 320 dp screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp-night", application = Application::class)
class ImageUiTest {
    @get:Rule val rule = createComposeRule()

    private fun row(state: MediaState, fileName: String? = null, fail: String? = null) = MediaEntity(
        "c1", "dm:a_b", state == MediaState.ENCRYPTED || state == MediaState.UPLOADING || state == MediaState.FAILED, state.name, "b1", 17, "x", null,
        ByteArray(1), null, "image/jpeg", 400, 300, fileName, 0, null, 0, failReason = fail,
    )

    @Test
    fun attachSheetGoesFromPreparingToReadyWithCaptionAndSends() {
        var state by mutableStateOf<AttachState>(AttachState.Preparing)
        var sent = false
        rule.setContent {
            RisiMeTheme(dark = true) {
                var caption by remember { mutableStateOf("") }
                AttachSheetContent(state, caption, { caption = it }, GROUP_IMAGES_NOTICE, onSend = { sent = true }, onCancel = {})
            }
        }
        rule.onNodeWithText("Preparing photo…").assertIsDisplayed()
        rule.onNodeWithText("Send").assertIsNotEnabled()
        rule.onNodeWithText(GROUP_IMAGES_NOTICE).assertIsDisplayed()
        state = AttachState.Ready(Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888), 2048, 1536)
        rule.onNodeWithText("Add a caption").performTextInput("Site visit")
        rule.onNodeWithText("Send").assertIsEnabled().performClick()
        assertTrue(sent)
    }

    @Test
    fun attachErrorIsShownAndCannotSend() {
        rule.setContent {
            RisiMeTheme(dark = true) {
                AttachSheetContent(AttachState.Error("This photo format isn't supported on this phone"), "", {}, null, {}, {})
            }
        }
        rule.onNodeWithText("This photo format isn't supported on this phone").assertIsDisplayed()
        rule.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test
    fun viewerShowsThePhotoCaptionSaveAndCloses() {
        var saved = false
        var closed = false
        val bmp = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).asImageBitmap()
        rule.setContent {
            RisiMeTheme(dark = true) { ImageViewerContent(bmp, "Level 3", null, canSave = true, onSave = { saved = true }, onClose = { closed = true }) }
        }
        rule.onNodeWithContentDescription("Photo: Level 3").assertIsDisplayed()
        rule.onNodeWithText("Level 3").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithContentDescription("Close").performClick()
        assertTrue(saved && closed)
    }

    @Test
    fun viewerWithoutTheImageShowsWhy() {
        rule.setContent {
            RisiMeTheme(dark = true) { ImageViewerContent(null, null, "This photo is no longer available", canSave = true, onSave = {}, onClose = {}) }
        }
        rule.onNodeWithText("This photo is no longer available").assertIsDisplayed()
        rule.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun notE2eeDmRefusesWithTheContractText() {
        assertEquals("Couldn't send: this chat isn't end-to-end encrypted yet.", dmImagesBlockedText(false, null, false, "Kamal"))
        assertEquals("Kamal's phone can't receive photos yet", dmImagesBlockedText(true, false, false, "Kamal"))
        assertEquals("Your other phone can't receive photos yet", dmImagesBlockedText(true, false, true, "Kamal"))
        assertNull(dmImagesBlockedText(true, true, false, "Kamal"))
        assertNull(dmImagesBlockedText(true, null, false, "Kamal"))

        var tapped = false
        rule.setContent {
            RisiMeTheme(dark = true) {
                androidx.compose.foundation.layout.Column {
                    ImageToast(dmImagesBlockedText(false, null, false, "Kamal")!!)
                    AttachButton(enabled = false) { tapped = true }
                }
            }
        }
        rule.onNodeWithText("Couldn't send: this chat isn't end-to-end encrypted yet.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Attach photo (unavailable)").performClick()
        assertTrue(tapped) // a tap still explains (and refetches readiness)
    }

    @Test
    fun bubbleStatesThumbnailFirstAndFailureTexts() {
        assertEquals("Sending photo…", imageStatusText(row(MediaState.UPLOADING)))
        assertEquals("Tap to download", imageStatusText(row(MediaState.NONE)))
        assertEquals("This photo is no longer available", imageStatusText(row(MediaState.GONE)))
        assertEquals("Couldn't open this photo", imageStatusText(row(MediaState.CORRUPT)))
        assertEquals("This photo is too large", imageStatusText(row(MediaState.FAILED, fail = "too_large")))
        assertNull(imageStatusText(row(MediaState.CACHED, "f")))
        assertEquals(ImageTap.OPEN, imageTap(row(MediaState.CACHED, "f")))
        assertEquals(ImageTap.DOWNLOAD, imageTap(row(MediaState.NONE)))
        assertEquals(ImageTap.NONE, imageTap(row(MediaState.GONE)))

        var tapped = false
        val thumb = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888).asImageBitmap()
        rule.setContent {
            RisiMeTheme(dark = true) {
                MessageBubble(
                    body = "Site visit", time = "10:00", mine = false, status = null, onMenu = {},
                    image = { ImageBox(2048, 1536, thumb, null, "Tap to download", busy = false) },
                    onTap = { tapped = true }, tapLabel = "Download photo", sender = "Kamal",
                )
            }
        }
        rule.onNodeWithContentDescription("Kamal: Photo, Site visit, 10:00", substring = true).assertIsDisplayed().performClick()
        assertTrue(tapped)
    }

    @Test
    fun previewsSayPhoto() {
        assertEquals("📷 Photo", bodyPreview(MessageEntity.KIND_IMAGE, ""))
        assertEquals("📷 Level 3", bodyPreview(MessageEntity.KIND_IMAGE, "Level 3"))
        assertEquals("hi", bodyPreview(MessageEntity.KIND_TEXT, "hi"))
        assertEquals("You: 📷 Photo", dmPreview(LastMessage("dm:a_b", "", 1, true, "SENT", kind = MessageEntity.KIND_IMAGE)))
        val incoming = MessageEntity("c1", "m1", "dm:a_b", "u-b", "u-a", "", "t", 10, MessageStatus.DELIVERED.name, false, kind = MessageEntity.KIND_IMAGE)
        val n = planChatNotifications(listOf(incoming), emptyList(), 0).single()
        assertEquals(listOf("📷 Photo"), n.lines)
    }
}
