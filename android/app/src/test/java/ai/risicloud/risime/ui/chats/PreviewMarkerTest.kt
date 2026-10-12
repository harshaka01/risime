package ai.risicloud.risime.ui.chats

import ai.risicloud.risime.data.ChatEngine
import ai.risicloud.risime.data.db.LastMessage
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.tabs.Tab
import ai.risicloud.risime.ui.chat.EncryptionWait
import ai.risicloud.risime.ui.chat.encryptionWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Chat-list clarity items 1–2: exactly one marker per message preview (🔒 / ● / ⚠ Not encrypted), none on a system line. */
class PreviewMarkerTest {
    private fun last(
        conv: String = "dm:a_b", outgoing: Boolean = false, kind: String = MessageEntity.KIND_TEXT, e2ee: String? = null,
        status: String = "READ", fromDevice: String? = null, from: String = "b", body: String = "hi",
    ) = LastMessage(conv, body, 1, outgoing, status, from = from, kind = kind, e2ee = e2ee, fromDevice = fromDevice)

    @Test fun privateOfficialAndNotEncrypted() {
        assertEquals("🔒 ", previewMarker(last(e2ee = MessageEntity.E2EE_YES), null, e2eeChat = false))
        assertEquals("🔒 ", previewMarker(last(e2ee = MessageEntity.E2EE_YES), Tab.PRIVATE, e2eeChat = true))
        assertEquals("● ", previewMarker(last(e2ee = MessageEntity.E2EE_YES), Tab.OFFICIAL, e2eeChat = true))
        assertEquals(NOT_ENCRYPTED_MARK, previewMarker(last(e2ee = MessageEntity.E2EE_PLAIN), null, e2eeChat = true))
        assertEquals("⚠ wins over the tab", NOT_ENCRYPTED_MARK, previewMarker(last(e2ee = MessageEntity.E2EE_PLAIN), Tab.PRIVATE, e2eeChat = true))
        assertEquals(NOT_ENCRYPTED_MARK, previewMarker(last(outgoing = true, status = "PENDING", e2ee = MessageEntity.E2EE_PLAIN_OK), null, true))
    }

    @Test fun neverBlankForAMessage() {
        // Rows from before v17 (no recorded state): judged honestly, never blank.
        assertEquals("🔒 ", previewMarker(last(conv = "grp:g"), null, e2eeChat = false))
        assertEquals("🔒 ", previewMarker(last(fromDevice = "d1"), null, e2eeChat = false))
        assertEquals("🔒 ", previewMarker(last(outgoing = true, status = "PENDING", from = "a"), null, e2eeChat = false))
        assertEquals("🔒 ", previewMarker(last(outgoing = true, status = "READ", from = "a"), null, e2eeChat = true))
        assertEquals("unknown and no group here: never a false padlock", NOT_ENCRYPTED_MARK, previewMarker(last(outgoing = true, status = "READ", from = "a"), null, e2eeChat = false))
        for (k in listOf(MessageEntity.KIND_TEXT, MessageEntity.KIND_IMAGE, MessageEntity.KIND_FILE, MessageEntity.KIND_CALL, MessageEntity.KIND_DELETED)) {
            assert(previewMarker(last(kind = k, e2ee = MessageEntity.E2EE_YES), null, true).isNotEmpty()) { k }
        }
    }

    @Test fun aSystemLineHasNoPadlock() {
        val gap = last(kind = MessageEntity.KIND_SYSTEM, body = "Earlier messages aren't available on this device", e2ee = null, from = "")
        assertEquals("", previewMarker(gap, Tab.PRIVATE, e2eeChat = true))
        assertEquals("", previewMarker(gap.copy(conversationId = "grp:g"), Tab.PRIVATE, e2eeChat = true))
        assertEquals("", previewMarker(null, Tab.PRIVATE, true))
        // A group row with no message yet (placeholder) neither.
        assertEquals("", previewMarker(last(conv = "grp:g", from = "", body = ""), null, true))
    }

    @Test fun waitingForEncryptionLabels() {
        val m = MessageEntity("c1", null, "dm:a_b", "a", "b", "hi", null, 1, "PENDING", outgoing = true, failReason = ChatEngine.E2EE_WAITING)
        assertEquals(EncryptionWait.SHORT, encryptionWait(m))
        assertEquals(EncryptionWait.LONG, encryptionWait(m.copy(failReason = ChatEngine.E2EE_WAITING_LONG)))
        assertNull(encryptionWait(m.copy(status = "SENT")))
        assertNull(encryptionWait(m.copy(failReason = null)))
        assertNull(encryptionWait(m.copy(outgoing = false)))
    }
}
