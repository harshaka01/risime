package lk.codegen.risime.data.messaging

import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.media.FileMeta
import lk.codegen.risime.data.mls.MlsPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §33.2 the selection bar matrix; §33.0 view once; §33.5 gone media (gate 5 with a clock seam). */
class SelectionRulesTest {
    private val me = "u-me"
    private val now = 100_000L

    private fun msg(id: String, from: String = "u-k", kind: String = MessageEntity.KIND_TEXT, body: String = "hi", messageId: String? = "m-$id", systemJson: String? = null) =
        MessageEntity(id, messageId, "dm:a_b", from, me, body, "2026-10-10T01:56:00.000Z", 1, if (messageId == null) "PENDING" else "READ", from == me, kind = kind, systemJson = systemJson)

    private fun media(id: String, state: MediaState, file: String? = null, expires: Long? = now + 1, outgoing: Boolean = false) =
        MediaEntity(id, "dm:a_b", outgoing, state.name, "b-$id", 100, "x", null, ByteArray(0), null, "image/jpeg", 10, 10, file, expiresAtEst = expires, lastAccess = 0)

    @Test fun neverSelectable() {
        assertFalse(SelectionRules.selectable(msg("t", kind = MessageEntity.KIND_DELETED)))
        assertFalse(SelectionRules.selectable(msg("s", kind = MessageEntity.KIND_SYSTEM)))
        assertFalse(SelectionRules.selectable(msg("c", kind = MessageEntity.KIND_CALL)))
        assertFalse(SelectionRules.selectable(msg("v").copy(viewOnce = true)))
        assertFalse(SelectionRules.selectable(msg("ctl", kind = MessageEntity.KIND_RISI_CTL, systemJson = """{"type":"risi_action"}""")))
        assertTrue(SelectionRules.selectable(msg("ask", from = me, kind = MessageEntity.KIND_RISI_CTL, systemJson = """{"type":"risi_request","text":"Am I free?"}""")))
        assertTrue(SelectionRules.selectable(msg("ok")))
    }

    /** §33.20 gate 12: a synthetic view-once envelope is not selectable, quotable, searchable or forwardable. */
    @Test fun viewOnceIsNeverSelectableQuotableOrForwardable() {
        val d = MlsPayload.decode("""{"v":1,"type":"text","body":"Door code 4711","view_once":{}}""".toByteArray()) as MlsPayload.Decoded.Text
        val m = msg("vo").copy(body = d.body, viewOnce = d.extras.viewOnce)
        assertFalse(SelectionRules.selectable(m))
        assertFalse(SelectionRules.replyable(m))
        assertNull(ForwardRules.build(m, me))
        assertNull(CopyFormat.content(m, single = true))
        assertFalse(SelectionRules.actions(listOf(m), me, emptyMap(), emptySet(), now, canSend = true).forward)
    }

    @Test fun theMatrix() {
        val a = msg("a")
        val b = msg("b", from = me)
        val pending = msg("p", from = me, messageId = null)
        val one = SelectionRules.actions(listOf(b), me, emptyMap(), emptySet(), now, canSend = true)
        assertTrue(one.copy && one.forward && one.share && one.star && one.reply && one.info)
        assertFalse(one.unstar)
        // Info: only own, with a message_id; Reply: exactly one with a message_id.
        assertFalse(SelectionRules.actions(listOf(a), me, emptyMap(), emptySet(), now, true).info)
        val pend = SelectionRules.actions(listOf(pending), me, emptyMap(), emptySet(), now, true)
        assertFalse(pend.info || pend.reply || pend.star)
        assertTrue("own pending text is forwardable", pend.forward)
        val two = SelectionRules.actions(listOf(a, b), me, emptyMap(), setOf("m-a", "m-b"), now, true)
        assertFalse(two.reply || two.info)
        assertTrue(two.unstar)
        assertFalse(SelectionRules.actions(listOf(a, b), me, emptyMap(), setOf("m-a"), now, true).unstar)
        // Limits: forward ≤ 30, share ≤ 10, select ≤ 100.
        val many = (1..31).map { msg("x$it") }
        val m31 = SelectionRules.actions(many, me, emptyMap(), emptySet(), now, true)
        assertFalse(m31.forward)
        assertFalse(m31.share)
        assertTrue(m31.copy)
        assertFalse(SelectionRules.actions((1..101).map { msg("y$it") }, me, emptyMap(), emptySet(), now, true).copy)
        // A card with buttons: no Reply, no Forward.
        val confirm = msg("k", systemJson = """{"v":1,"kind":"confirm"}""")
        val ck = SelectionRules.actions(listOf(confirm), me, emptyMap(), emptySet(), now, true)
        assertFalse(ck.reply || ck.forward)
        // No composer (read-only): no Reply.
        assertFalse(SelectionRules.actions(listOf(a), me, emptyMap(), emptySet(), now, canSend = false).reply)
    }

    @Test fun goneMediaIsNotForwardableButDownloadedMediaIs() {
        val photo = msg("i", kind = MessageEntity.KIND_IMAGE, body = "")
        val cached = mapOf("i" to media("i", MediaState.CACHED, file = "f"))
        val fetchable = mapOf("i" to media("i", MediaState.NONE))
        val expired = mapOf("i" to media("i", MediaState.NONE, expires = now - 1))
        val gone = mapOf("i" to media("i", MediaState.GONE))
        assertNull(SelectionRules.forwardBlock(photo, cached["i"], now))
        assertNull(SelectionRules.forwardBlock(photo, fetchable["i"], now))
        assertEquals("This photo is no longer available", SelectionRules.forwardBlock(photo, expired["i"], now))
        assertEquals("This photo is no longer available", SelectionRules.forwardBlock(photo, gone["i"], now))
        val r = SelectionRules.actions(listOf(photo), me, expired, emptySet(), now, true)
        assertFalse(r.forward)
        assertEquals("This photo is no longer available", r.forwardWhyNot)
        // A photo without a caption: Copy off alone (nothing to copy), Share on.
        val alone = SelectionRules.actions(listOf(photo), me, cached, emptySet(), now, true)
        assertFalse(alone.copy)
        assertTrue(alone.share)
        // Own media still uploading: forwardable from its local ciphertext.
        val own = msg("o", from = me, kind = MessageEntity.KIND_IMAGE, messageId = null)
        assertNull(SelectionRules.forwardBlock(own, media("o", MediaState.UPLOADING, file = "f", outgoing = true), now))
        // A parts file: never.
        val parts = msg("f", kind = MessageEntity.KIND_FILE, systemJson = FileMeta("a.zip", "application/zip", parts = true).encode())
        assertEquals("This file is no longer available", SelectionRules.forwardBlock(parts, null, now))
    }
}
