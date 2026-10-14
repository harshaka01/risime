package ai.risicloud.risime.data.backup

import android.app.Application
import kotlinx.coroutines.runBlocking
import ai.risicloud.risime.data.ChatEngine
import ai.risicloud.risime.data.MessageStatus
import ai.risicloud.risime.data.backup.BackupData.DM
import ai.risicloud.risime.data.backup.BackupData.GRP
import ai.risicloud.risime.data.backup.BackupData.ME
import ai.risicloud.risime.data.backup.BackupData.PEER
import ai.risicloud.risime.data.backup.BackupData.msg
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.history.HistoryFixtures
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Proposal 2026-10-14-backup-held (§22.5/§22.6 amendment, hard rule 9): my unsent texts ("Waiting for encryption…",
 * queued, "Not sent") are in a backup as `outbox` lines and come back to the outbox: held again, sent once the chat is
 * ready, never twice; old backups restore as before; the upgrade gate's counts include them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupHeldTest {
    @get:Rule val tmp = TemporaryFolder()
    private val phones = mutableListOf<BackupPhone>()

    private fun phone() = BackupPhone(dir = tmp.newFolder()).also { phones += it }

    @After fun close() = phones.forEach { it.close() }

    private fun ms(i: Int) = HistoryFixtures.ms(BackupData.at(i))

    /** My unsent text: never accepted by the server (no message_id, no server_ts). */
    private fun unsent(conv: String, i: Int, body: String, status: MessageStatus = MessageStatus.PENDING, failReason: String? = null, e2ee: String? = null) =
        MessageEntity(
            clientMsgId = UUID.nameUUIDFromBytes("held-$conv-$i".toByteArray()).toString(), messageId = null, conversationId = conv,
            from = ME, to = if (conv == DM) PEER else conv, body = body, serverTs = null, localTs = ms(i), status = status.name,
            outgoing = true, failReason = failReason, e2ee = e2ee,
        )

    /** A DM with sent history, three held/unsent texts and a group with one queued text. */
    private suspend fun fillHeld(p: BackupPhone): List<MessageEntity> {
        val m = p.db.messages()
        for (i in 0 until 4) m.insert(msg(DM, if (i % 2 == 0) PEER else ME, i))
        val held = unsent(DM, 5, "upgrade-test A1", failReason = ChatEngine.E2EE_WAITING)
        // The user had tapped "Send without encryption" on the old phone: that consent never travels.
        val heldLong = unsent(DM, 6, "upgrade-test A2", failReason = ChatEngine.E2EE_WAITING_LONG, e2ee = MessageEntity.E2EE_PLAIN_OK)
            .copy(replyToMessageId = msg(DM, PEER, 2).messageId, replyToFrom = PEER)
        val failed = unsent(DM, 7, "upgrade-test A3", status = MessageStatus.FAILED, failReason = "not_friends")
        val queued = unsent(GRP, 8, "queued in the group")
        listOf(held, heldLong, failed, queued).forEach { m.insert(it) }
        return listOf(held, heldLong, failed, queued)
    }

    @Test fun heldRowsRoundTripAndWaitForEncryptionAgain() = runBlocking {
        val a = phone()
        val (held, heldLong, failed, queued) = fillHeld(a)
        val lines = bundleOf(a)
        val outbox = lines.filter { it.contains("\"outbox\":") }.map(BackupData::json)
        assertEquals(4, outbox.size)
        for (o in outbox) {
            assertEquals(JsonNull, o["message_id"])
            assertEquals(JsonNull, o["server_ts"])
            assertEquals(JsonNull, o["status"])
            assertEquals("text", o["payload"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        assertTrue(lines.none { it.contains("plain_ok") })

        val b = phone()
        val r = b.importer().import(lines.iterator())
        assertEquals(4, r.outbox)
        assertEquals(0, r.skipped["malformed"] ?: 0)
        val m = b.db.messages()
        for (x in listOf(held, heldLong, queued)) {
            val got = m.byClientMsgId(x.clientMsgId)!!
            assertEquals(x.body, got.body)
            assertEquals(x.localTs, got.localTs)
            assertEquals(MessageStatus.PENDING.name, got.status)
            assertTrue(got.outgoing)
            assertNull(got.messageId)
            assertNull("never plain_ok after a restore", got.e2ee)
            assertEquals(BundleImporter.ORIGIN_BACKUP, got.origin)
        }
        // A 1:1 shows "Waiting for encryption…" at once; a group row is just queued.
        assertEquals(ChatEngine.E2EE_WAITING, m.byClientMsgId(held.clientMsgId)!!.failReason)
        assertEquals(ChatEngine.E2EE_WAITING, m.byClientMsgId(heldLong.clientMsgId)!!.failReason)
        assertNull(m.byClientMsgId(queued.clientMsgId)!!.failReason)
        assertEquals(msg(DM, PEER, 2).messageId, m.byClientMsgId(heldLong.clientMsgId)!!.replyToMessageId)
        // "Not sent" stays "Not sent" (sent only on the user's tap).
        assertEquals(MessageStatus.FAILED.name, m.byClientMsgId(failed.clientMsgId)!!.status)
        // The outbox picks the pending ones up (it decides encrypt / hold when it runs).
        assertEquals(setOf(held.clientMsgId, heldLong.clientMsgId, queued.clientMsgId), m.pendingOutbox().map { it.clientMsgId }.toSet())
    }

    @Test fun noDoubleSendAfterARestore() = runBlocking {
        val a = phone()
        val (held, _, _, queued) = fillHeld(a)
        val lines = bundleOf(a)
        val b = phone()
        var kicks = 0
        val importer = { BundleImporter(
            b.db.messages(), b.db.deletes(), b.db.groups(), b.db.contacts(), b.db.backup(), b.db.history(),
            ai.risicloud.risime.data.ReactionStore(b.db.reactions()), b.images, b.tx, b.progress, b.me,
            onOutboxRestored = { kicks++ },
        ) }
        importer().import(lines.iterator())
        assertEquals("the outbox is kicked once after a restore with unsent texts", 1, kicks)
        val before = b.db.messages().pendingOutbox().map { it.clientMsgId }
        // A second restore of the same backup adds nothing: one row (one send) per client_msg_id.
        val again = importer().import(lines.iterator())
        assertEquals(0, again.imported)
        assertEquals(0, again.outbox)
        assertEquals(1, kicks)
        assertEquals(before, b.db.messages().pendingOutbox().map { it.clientMsgId })
        assertEquals(before.size, before.toSet().size)

        // A phone where the held text was already sent (same client_msg_id, now with a message_id): never re-queued.
        val c = phone()
        val sentId = HistoryFixtures.tuuid(ms(5), 5)
        c.db.messages().insert(held.copy(messageId = sentId, serverTs = BackupData.at(5), status = MessageStatus.DELIVERED.name, failReason = null))
        val rc = c.importer().import(lines.iterator())
        assertTrue(rc.skipped["existing_row"]!! >= 1)
        val row = c.db.messages().byClientMsgId(held.clientMsgId)!!
        assertEquals(sentId, row.messageId)
        assertEquals(MessageStatus.DELIVERED.name, row.status)
        assertTrue(c.db.messages().pendingOutbox().none { it.clientMsgId == held.clientMsgId })
        assertNotNull(c.db.messages().byClientMsgId(queued.clientMsgId))
    }

    @Test fun oldBackupsWithoutTheFieldStillRestore() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        fillHeld(a)
        // An old app's bundle: the same lines without any `outbox` line.
        val old = bundleOf(a).filterNot { it.contains("\"outbox\":") }
        val b = phone()
        val r = b.importer().import(old.iterator())
        assertEquals(0, r.outbox)
        assertEquals(0, r.skipped["malformed"] ?: 0)
        assertTrue(b.db.messages().pendingOutbox().isEmpty())
        assertNotNull(b.db.messages().byMessageId(msg(DM, ME, 1).messageId!!))
        // A message line without a message_id and without `outbox` is still refused, as before.
        val bare = """{"type":"message","conversation_id":"$DM","message_id":null,"client_msg_id":"${UUID.randomUUID()}","from":"$ME","server_ts":null,"payload":{"v":1,"type":"text","body":"x"}}"""
        val weird = """{"type":"message","conversation_id":"$DM","message_id":null,"client_msg_id":"${UUID.randomUUID()}","from":"$ME","server_ts":null,"payload":{"v":1,"type":"text","body":"x"},"outbox":{"state":"sending","client_ts":"2026-10-14T00:00:00.000Z"}}"""
        val notMine = """{"type":"message","conversation_id":"$DM","message_id":null,"client_msg_id":"${UUID.randomUUID()}","from":"$PEER","server_ts":null,"payload":{"v":1,"type":"text","body":"x"},"outbox":{"state":"pending","client_ts":"2026-10-14T00:00:00.000Z"}}"""
        val c = phone()
        val rc = c.importer().import((listOf(old.first()) + listOf(bare, weird, notMine)).iterator())
        assertEquals(0, rc.imported)
        assertEquals(3, rc.skipped["malformed"])
    }

    @Test fun theUpgradeGateCountsIncludeHeldMessages() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        fillHeld(a)
        val before = a.counts()
        // Held texts are rows of the chat: the gate counts every text row, unsent ones included.
        val dmTexts = a.db.backup().page(DM, Long.MIN_VALUE, "", 1_000).filter { it.kind == MessageEntity.KIND_TEXT }
        assertEquals(4, dmTexts.count { it.messageId == null }) // fill's pending-20 + three of fillHeld's
        assertEquals(dmTexts.size, before[DM]!![0])
        // After a backup → restore on a fresh phone the counts are equal (nightly.6 saw "dm|text:7->4").
        val b = phone()
        b.importer().import(bundleOf(a).iterator())
        assertEquals(before, b.counts())
    }
}
