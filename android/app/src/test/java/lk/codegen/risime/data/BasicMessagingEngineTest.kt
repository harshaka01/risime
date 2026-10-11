package lk.codegen.risime.data

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.deletes.FakeDeleteDao
import lk.codegen.risime.data.groups.GroupStore
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.GroupRef
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.net.ProtocolJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.34 §33 in the chat engine: forwarded / reply_to / view_once stored, sent, status times, star purge. */
class BasicMessagingEngineTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val kamal = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val conv = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val messages = FakeMessageDao()
    private val sync = FakeSyncDao()
    private val realtime = FakeRealtime()
    private val mls = FakeMlsEngine(me, "dev-me")
    private val deleteDao = FakeDeleteDao(messages)
    private var ids = 0
    private var now = 1_000L

    private fun TestScope.engine() = ChatEngine(
        messages = messages, sync = sync,
        tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
        scope = this, realtime = { realtime }, meId = { me }, clock = { now++ }, newClientMsgId = { "c${++ids}" },
        mls = MlsPipeline({ mls }, FakeMlsPendingDao()),
        mlsEngine = { mls },
        groupsEnabled = { true },
        groups = GroupStore(FakeGroupDao(), FakeGroupOpDao(), messages, { "dev-me" }, { mls.groupMeta(it) }, { now++ }),
        deletes = deleteDao,
    )

    private fun example(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private fun message(mid: String, envelope: String) = ProtocolJson.decodeFromString<Event>(
        """{"event_id":"e-$mid","kind":"message","data":{"message_id":"$mid","client_msg_id":"cm-$mid","conversation_id":"$conv",
        "from":"$kamal","from_device":"dev-k","ciphertext":"${FakeMlsEngine.ciphertext(1, 1, kamal, "dev-k", envelope)}",
        "generation":1,"epoch":1,"server_ts":"2026-10-06T08:15:30.456Z"}}""",
    )

    private fun join() {
        mls.groups[conv] = GroupRef(conv, 1, 1)
        mls.metas[conv] = GroupMeta(name = "Pilot team", admins = listOf(kamal))
    }

    @Test fun forwardedReplyAndViewOnceAreStoredFromTheEnvelope() = runTest {
        val e = engine()
        join()
        e.onEvents(
            listOf(
                message("c1a2b3e1-a0b1-11f0-8000-0242ac120002", example("envelope_text_forwarded_many.json")),
                message("c1a2b3e2-a0b1-11f0-8000-0242ac120002", example("envelope_text_reply.json")),
                message("c1a2b3e3-a0b1-11f0-8000-0242ac120002", example("envelope_text_forwarded_bad.json")),
                message("c1a2b3e4-a0b1-11f0-8000-0242ac120002", example("envelope_text_view_once_reserved.json")),
            ),
        )
        val rows = messages.rows.values.toList()
        assertEquals(4, rows.size)
        assertEquals(7, rows[0].forwardHops)
        assertEquals("c1a2b3e1-a0b1-11f0-8000-0242ac120002", rows[1].replyToMessageId)
        assertEquals(kamal, rows[1].replyToFrom)
        // A bad `forwarded` is ignored: stored and shown, without a label.
        assertNull(rows[2].forwardHops)
        assertEquals("Site visit moved to 3 PM", rows[2].body)
        assertTrue(rows[3].isViewOnce)
        assertFalse(rows[0].isViewOnce)
    }

    @Test fun aForwardedOrReplyRowSendsItsFieldsInsideMlsOnly() = runTest {
        val e = engine()
        join()
        messages.insert(
            MessageEntity(
                clientMsgId = "fw1", messageId = null, conversationId = conv, from = me, to = conv, body = "Site visit moved to 3 PM",
                serverTs = null, localTs = 5, status = "PENDING", outgoing = true, forwardHops = 2,
                replyToMessageId = "c1a2b3e1-a0b1-11f0-8000-0242ac120002", replyToFrom = kamal,
            ),
        )
        e.flushOutbox()
        advanceUntilIdle()
        val ct = String(java.util.Base64.getDecoder().decode(realtime.sentGroup.single().ciphertext))
        val env = ProtocolJson.parseToJsonElement(ct.substringAfter("dev-me|")).jsonObject
        assertEquals("""{"hops":2}""", env["forwarded"].toString())
        assertEquals("""{"message_id":"c1a2b3e1-a0b1-11f0-8000-0242ac120002","from":"$kamal"}""", env["reply_to"].toString())
        assertFalse(env.containsKey("view_once"))
    }

    @Test fun dmStatusTimesFillDeliveredAndRead() = runTest {
        val e = engine()
        val peer = kamal
        val dm = lk.codegen.risime.net.dmConversationId(me, peer)
        messages.insert(MessageEntity("o1", "m-o1", dm, me, peer, "hi", "2026-10-06T08:00:00.000Z", 1, "SENT", true))
        messages.insert(MessageEntity("o2", "m-o2", dm, me, peer, "hi 2", "2026-10-06T08:00:01.000Z", 2, "SENT", true))
        fun status(eid: String, cid: String, mid: String, s: String, at: String) = ProtocolJson.decodeFromString<Event>(
            """{"event_id":"$eid","kind":"status","data":{"message_id":"$mid","client_msg_id":"$cid","conversation_id":"$dm","status":"$s","by":"$peer","at":"$at"}}""",
        )
        e.onEvents(
            listOf(
                status("s1", "o1", "m-o1", "delivered", "2026-10-06T08:15:31.000Z"),
                status("s2", "o1", "m-o1", "read", "2026-10-06T08:20:00.000Z"),
                // A read without a delivered sets both.
                status("s3", "o2", "m-o2", "read", "2026-10-06T08:21:00.000Z"),
            ),
        )
        val o1 = messages.rows["o1"]!!
        assertEquals(java.time.Instant.parse("2026-10-06T08:15:31.000Z").toEpochMilli(), o1.deliveredAt)
        assertEquals(java.time.Instant.parse("2026-10-06T08:20:00.000Z").toEpochMilli(), o1.readAt)
        val o2 = messages.rows["o2"]!!
        assertEquals(o2.readAt, o2.deliveredAt)
    }

    @Test fun deletesPurgeStarRowsInTheSameTransaction() = runTest {
        val e = engine()
        join()
        e.onEvents(listOf(message("c1a2b3e1-a0b1-11f0-8000-0242ac120002", example("envelope_text_forwarded.json")), message("c1a2b3e2-a0b1-11f0-8000-0242ac120002", example("envelope_text_reply.json"))))
        val (a, b) = messages.rows.values.toList()
        deleteDao.stars[a.messageId!!] = lk.codegen.risime.data.db.StarEntity(a.messageId!!, conv, 1)
        deleteDao.stars[b.messageId!!] = lk.codegen.risime.data.db.StarEntity(b.messageId!!, conv, 2)
        e.deleteForMe(conv, listOf(a.clientMsgId))
        assertEquals(setOf(b.messageId), deleteDao.stars.keys)
        e.clearChat(conv, hide = false)
        assertTrue(deleteDao.stars.isEmpty())
    }
}
