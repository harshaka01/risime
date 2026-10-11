package lk.codegen.risime.ui.chat

import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.media.FileMeta
import lk.codegen.risime.net.GroupReceipt
import lk.codegen.risime.net.GroupReceiptsReply
import lk.codegen.risime.push.bodyPreview
import lk.codegen.risime.ui.group.ReceiptSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** §33.9 quotes (gate 8 unit part), §33.12 Info sections without agents, §33.4/§33.13 previews. */
class MessagingLogicTest {
    private val conv = "dm:a_b"
    private fun row(id: String, body: String = "hi", from: String = "u-k", kind: String = MessageEntity.KIND_TEXT, conversation: String = conv) =
        MessageEntity(id, "m-$id", conversation, from, "u-me", body, "2026-10-10T01:56:00.000Z", 1, "READ", false, kind = kind)

    private val names = mapOf("u-k" to "Kamal", "u-me" to "You")
    private val nameOf: (String) -> String = { names[it] ?: "Someone" }

    @Test fun quotesRenderFromTheLocalCopy() {
        val target = row("t", body = "Line one\nLine two\nLine three")
        val reply = row("r", from = "u-me").copy(replyToMessageId = "m-t", replyToFrom = "u-forged")
        val q = Quotes.resolve(reply, listOf(target, reply), nameOf, hiddenDeleted = false) as QuoteView.Found
        // The target's authenticated sender (the local row), never the reply's `from`; the first two lines.
        assertEquals("Kamal", q.name)
        assertEquals("Line one\nLine two", q.text)
        assertEquals("t", q.clientMsgId)
    }

    @Test fun aMissingTargetShowsNotOnThisPhoneWithReplyToFrom() {
        val reply = row("r").copy(replyToMessageId = "m-x", replyToFrom = "u-k")
        assertEquals(QuoteView.Missing("Kamal"), Quotes.resolve(reply, listOf(reply), nameOf, hiddenDeleted = false))
        assertEquals(QuoteView.Deleted, Quotes.resolve(reply, listOf(reply), nameOf, hiddenDeleted = true))
    }

    @Test fun aDeletedTargetSaysSoAndAnotherConversationShowsNoQuote() {
        val reply = row("r").copy(replyToMessageId = "m-t", replyToFrom = "u-k")
        assertEquals(QuoteView.Deleted, Quotes.resolve(reply, listOf(row("t", kind = MessageEntity.KIND_DELETED), reply), nameOf, false))
        assertNull(Quotes.resolve(reply, listOf(row("t", conversation = "grp:other"), reply), nameOf, false))
        assertNull(Quotes.resolve(row("plain"), emptyList(), nameOf, false))
    }

    @Test fun quotePreviewsForMedia() {
        assertEquals("📷 Photo", Quotes.preview(row("i", body = "", kind = MessageEntity.KIND_IMAGE)))
        val f = row("f", kind = MessageEntity.KIND_FILE).copy(systemJson = FileMeta("Plan.pdf", "application/pdf").encode())
        assertEquals("📄 Plan.pdf", Quotes.preview(f))
    }

    @Test fun groupInfoNeverListsAgents() {
        val reply = GroupReceiptsReply(
            of = 3,
            receipts = listOf(
                GroupReceipt("u-k", "2026-10-06T08:15:31.002Z", "2026-10-06T08:15:40.120Z"),
                GroupReceipt("u-s", "2026-10-06T08:15:33.000Z", "2026-10-06T08:16:40.120Z"),
                GroupReceipt("u-risi", "2026-10-06T08:15:31.002Z", "2026-10-06T08:15:32.000Z"),
                GroupReceipt("u-n", null, null),
            ),
        )
        val s = ReceiptSections.of(reply) { it == "u-risi" }
        // Newest read first; Risi never listed.
        assertEquals(listOf("u-s", "u-k"), s.read.map { it.userId })
        assertEquals(listOf("u-n"), s.waiting.map { it.userId })
        assertEquals(0, s.delivered.size)
    }

    @Test fun previewsForFilesAndForwards() {
        assertEquals("📄 Plan.pdf", bodyPreview(MessageEntity.KIND_FILE, "", FileMeta("Plan.pdf", "application/pdf").encode()))
        assertEquals("↪ Forwarded: hello", bodyPreview(MessageEntity.KIND_TEXT, "hello", null, 2))
        assertEquals("📄 File", bodyPreview(MessageEntity.KIND_FILE, "", null))
    }
}
