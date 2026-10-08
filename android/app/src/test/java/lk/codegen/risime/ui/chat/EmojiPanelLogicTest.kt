package lk.codegen.risime.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiPanelLogicTest {
    @Test fun recentsAreMostRecentFirstWithoutDuplicates() {
        var r = emptyList<String>()
        listOf("😀", "👍🏽", "😀", "👨‍👩‍👧").forEach { r = EmojiRecents.push(r, it) }
        assertEquals(listOf("👨‍👩‍👧", "😀", "👍🏽"), r)
    }

    @Test fun recentsAreCappedAt32() {
        var r = emptyList<String>()
        (1..40).forEach { r = EmojiRecents.push(r, "e$it") }
        assertEquals(32, r.size)
        assertEquals("e40", r.first())
        assertEquals("e9", r.last())
        assertEquals(r, EmojiRecents.decode(EmojiRecents.encode(r)))
    }

    @Test fun blankIsNotRecorded() {
        assertEquals(listOf("😀"), EmojiRecents.push(listOf("😀"), " "))
        assertEquals(emptyList<String>(), EmojiRecents.decode(null))
    }

    @Test fun panelHeightFollowsTheKeyboardElseDefault() {
        assertEquals(300.dp, emojiPanelHeight(300.dp))
        assertEquals(280.dp, emojiPanelHeight(null))
        assertEquals(280.dp, emojiPanelHeight(40.dp))
    }

    @Test fun attachSheetListsFiveAndOnlyGalleryAndCameraWork() {
        val all = AttachOption.visible(canCamera = true)
        assertEquals(listOf("Gallery", "Camera", "Document", "Location", "Contact"), all.map { it.label })
        assertEquals(listOf(AttachOption.GALLERY, AttachOption.CAMERA), all.filter { it.wired })
        assertFalse(AttachOption.DOCUMENT.wired)
        assertTrue(AttachOption.visible(canCamera = false).none { it == AttachOption.CAMERA })
    }
}
