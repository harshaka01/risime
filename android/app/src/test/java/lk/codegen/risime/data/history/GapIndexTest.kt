package lk.codegen.risime.data.history

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeReactionDao
import lk.codegen.risime.data.FakeRealtime
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.DeletedIdEntity
import lk.codegen.risime.data.deletes.FakeDeleteDao
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.GroupRef
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** §17.2 the gap index: what gets a content-free row, what never does, and what removes rows. */
class GapIndexTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val peer = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val dm = dmConversationId(me, peer)
    private val grp = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val myDev = "d-new"
    private val historyBefore = "2026-10-06T11:00:00.000Z"

    private val messages = FakeMessageDao()
    private val reactions = FakeReactionDao()
    private val deletes = FakeDeleteDao(messages, reactions)
    private val history = FakeHistoryDao(messages, reactions)
    private val mls = FakeMlsEngine(me, myDev)
    private val pending = FakeMlsPendingDao()
    private var now = Instant.parse("2026-10-06T12:00:00.000Z").toEpochMilli()

    private fun TestScope.engine() = ChatEngine(
        messages = messages, sync = FakeSyncDao(),
        tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
        scope = this, realtime = { FakeRealtime() }, meId = { me }, clock = { now++ },
        mls = MlsPipeline({ mls }, pending), mlsEngine = { mls }, reactionsDao = reactions,
        groupsEnabled = { true }, deletes = deletes, historyDao = history,
    )

    private fun ev(json: String) = ProtocolJson.decodeFromString<Event>(json)

    private fun e2ee(eid: String, conv: String, from: String, dev: String, epoch: Long, ts: String, text: String, gen: Long = 1) = ev(
        """{"event_id":"$eid","kind":"message","data":{"message_id":"$eid","client_msg_id":"cm-$eid","conversation_id":"$conv",
        "from":"$from",${if (conv == dm) "\"to\":\"${if (from == me) peer else me}\"," else ""}"from_device":"$dev",
        "ciphertext":"${FakeMlsEngine.ciphertext(gen, epoch, from, dev, String(MlsPayload.text(text)))}","generation":$gen,"epoch":$epoch,"server_ts":"$ts"}}""",
    )

    private fun welcome(eid: String, conv: String, epoch: Long, gen: Long = 1) = ev(
        """{"event_id":"$eid","kind":"mls_welcome","data":{"conversation_id":"$conv","generation":$gen,"epoch":$epoch,
        "welcome":"${FakeMlsEngine.b64("welcome|$epoch")}","to_devices":["$myDev"]}}""",
    )

    private fun delete(eid: String, conv: String, targets: List<String>, encrypted: Boolean = false) = ev(
        """{"event_id":"$eid","kind":"delete","data":{"message_id":"$eid","client_msg_id":"cm-$eid","conversation_id":"$conv","from":"$peer",
        "from_device":"d-kamal","targets":[${targets.joinToString { "{\"message_id\":\"$it\"}" }}],
        ${if (encrypted) "\"ciphertext\":\"${FakeMlsEngine.b64("AAD:#0|0|x/y|z")}\",\"generation\":1,\"epoch\":0," else ""}"server_ts":"2026-10-03T09:00:00.000Z"}}""",
    )

    private suspend fun ChatEngine.join(events: List<Event>) {
        cursor()
        onHistoryBefore(historyBefore)
        onEvents(events)
        onLive()
    }

    @Test fun preInstallMessagesGetContentFreeGapRowsWithTheirClientMsgId() = runTest {
        val e = engine()
        e.join(
            listOf(
                e2ee("x1", dm, peer, "d-kamal", 0, "2026-10-02T09:00:00.000Z", "secret from Kamal"),
                e2ee("x2", dm, me, "d-old", 1, "2026-10-02T09:05:00.000Z", "secret from my old install"),
                welcome("w1", dm, 2),
                e2ee("x3", dm, peer, "d-kamal", 2, "2026-10-06T11:30:00.000Z", "readable"),
            ),
        )
        advanceUntilIdle()
        val rows = history.gaps(dm)
        assertEquals(listOf("x1", "x2"), rows.map { it.messageId })
        val x1 = history.gap("x1")!!
        assertEquals("cm-x1", x1.clientMsgId)
        assertEquals(peer, x1.from)
        assertEquals("d-kamal", x1.fromDevice)
        assertEquals("2026-10-02T09:00:00.000Z", x1.serverTs)
        assertEquals(1L, x1.generation)
        assertEquals(0L, x1.epoch)
        assertEquals("cm-x2", history.gap("x2")!!.clientMsgId)
        // The readable message is stored (with its sending device), never a gap.
        assertNull(history.gap("x3"))
        assertEquals("d-kamal", messages.byMessageId("x3")!!.fromDevice)
        assertEquals("2026-10-02T09:00:00.000Z" to "2026-10-02T09:05:00.000Z", HistoryGaps.range(rows))
    }

    @Test fun controlEventsAndHiddenTombstonesNeverGetGapRows() = runTest {
        val e = engine()
        deletes.putDeletedId(DeletedIdEntity("x2", dm, peer, false, null, "everyone", 0))
        e.join(
            listOf(
                delete("d1", dm, listOf("x9"), encrypted = true), // a pre-install control
                e2ee("x2", dm, peer, "d-kamal", 0, "2026-10-02T09:05:00.000Z", "deleted for everyone"),
                e2ee("x1", dm, peer, "d-kamal", 0, "2026-10-02T09:00:00.000Z", "kept"),
            ),
        )
        advanceUntilIdle()
        assertEquals(listOf("x1"), history.gaps(dm).map { it.messageId })
    }

    @Test fun ownDeviceEchoesAreNotGaps() = runTest {
        val e = engine()
        e.join(listOf(e2ee("x1", dm, me, myDev, 0, "2026-10-02T09:00:00.000Z", "mine")))
        advanceUntilIdle()
        assertTrue(history.gaps(dm).isEmpty())
    }

    @Test fun aDeleteEventRemovesTheGapRow() = runTest {
        val e = engine()
        e.join(
            listOf(
                e2ee("x1", dm, peer, "d-kamal", 0, "2026-10-02T09:00:00.000Z", "one"),
                e2ee("x2", dm, peer, "d-kamal", 0, "2026-10-02T09:01:00.000Z", "two"),
                delete("d1", dm, listOf("x1")),
            ),
        )
        advanceUntilIdle()
        assertEquals(listOf("x2"), history.gaps(dm).map { it.messageId })
    }

    @Test fun clearChatAndThePruneRemoveGapRows() = runTest {
        val e = engine()
        e.join(
            listOf(
                e2ee("x1", dm, peer, "d-kamal", 0, "2026-09-01T09:00:00.000Z", "old"),
                e2ee("x2", dm, peer, "d-kamal", 0, "2026-10-02T09:01:00.000Z", "two"),
                e2ee("g1", grp, peer, "d-kamal", 0, "2026-10-02T09:02:00.000Z", "group"),
            ),
        )
        advanceUntilIdle()
        assertEquals(3, history.allGaps().size)
        assertEquals(1, HistoryGaps.prune(history, Instant.parse("2026-10-02T09:00:00.000Z").toEpochMilli()))
        assertEquals(listOf("x2"), history.gaps(dm).map { it.messageId })
        e.clearChat(dm, hide = false)
        assertTrue(history.gaps(dm).isEmpty())
        assertEquals(listOf("g1"), history.gaps(grp).map { it.messageId })
    }

    @Test fun rejoinAndResetLossesAreGaps() = runTest {
        val e = engine()
        // A group message parked for a generation that never arrives (no group yet, after history_before).
        e.cursor()
        e.onHistoryBefore("2026-10-01T00:00:00.000Z")
        e.onEvents(listOf(e2ee("p1", grp, peer, "d-kamal", 3, "2026-10-06T11:40:00.000Z", "parked", gen = 1)))
        // Parked too: generation 2, below the epoch the coming Welcome joins at.
        e.onEvents(listOf(e2ee("p2", grp, peer, "d-kamal", 4, "2026-10-06T11:41:00.000Z", "before my join", gen = 2)))
        assertEquals(2, pending.forConversation(grp).size)
        // §12.8: the rejoin's Welcome is generation 2: the parked generation-1 message is lost → gap row;
        // rule 2 makes the parked generation-2 message below the Welcome's epoch a gap too.
        e.onEvents(listOf(welcome("w2", grp, 5, gen = 2)))
        assertEquals(listOf("p1", "p2"), history.gaps(grp).map { it.messageId })
        // An older generation's message after the reset.
        e.onEvents(listOf(e2ee("p3", grp, peer, "d-kamal", 9, "2026-10-06T11:42:00.000Z", "old generation", gen = 1)))
        advanceUntilIdle()
        assertEquals(listOf("p1", "p2", "p3"), history.gaps(grp).map { it.messageId })
        assertEquals(GroupRef(grp, 2, 5), mls.group(grp))
    }
}
