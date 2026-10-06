package lk.codegen.risime.data.deletes

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import lk.codegen.risime.data.BlobFetch
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeGroupDao
import lk.codegen.risime.data.FakeGroupOpDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeReactionDao
import lk.codegen.risime.data.FakeRealtime
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.DeleteOutboxEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.groups.GroupStore
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.GroupRef
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.net.DeleteEvent
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.MsgDelete
import lk.codegen.risime.net.MsgDeleteReply
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.realtime.PushResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §15.2/§15.7 the send side (behind the UI flag): delete for everyone/me, pending rules, Clear/Delete chat. */
class DeleteSendTest {
    private val me = "u-me"
    private val bob = "u-bob"
    private val dm = dmConversationId(me, bob)
    private val messages = FakeMessageDao()
    private val reactions = FakeReactionDao()
    private val dao = FakeDeleteDao(messages, reactions)
    private val sync = FakeSyncDao()
    private val realtime = FakeRealtime()
    private val mls = FakeMlsEngine(me, "dev-me")
    private val base = 1_791_000_000_000L
    private var ids = 0
    private val catchUps = mutableListOf<String>()

    private fun TestScope.engine() = ChatEngine(
        messages = messages, sync = sync,
        tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
        scope = this, realtime = { realtime }, meId = { me }, clock = { base }, newClientMsgId = { "c${++ids}" },
        mls = MlsPipeline({ mls }, FakeMlsPendingDao()), mlsEngine = { mls }, reactionsDao = reactions,
        catchUp = { catchUps += it },
        groupsEnabled = { true }, groups = GroupStore(FakeGroupDao(), FakeGroupOpDao(), messages, { "dev-me" }, { mls.groupMeta(it) }, { base }),
        blobs = { BlobFetch.Gone }, deletes = dao,
    )

    private suspend fun sent(n: Int, from: String = me, blob: String? = null): List<MessageEntity> = (0 until n).map { i ->
        val m = MessageEntity("cm$i", tu(base, i), dm, from, if (from == me) bob else me, "msg $i", iso(base), base + i, "SENT", from == me, blobId = blob)
        messages.insert(m)
        m
    }

    @Test fun deleteForEveryoneEncryptsTheTargetsIntoTheAadAndTombstonesOnOk() = runTest {
        mls.groups[dm] = GroupRef(dm, 1, 1)
        val e = engine()
        val rows = sent(2, blob = "blob-1")
        e.deleteForEveryone(dm, rows.map { it.clientMsgId })
        // Shown as deleting at once.
        assertTrue(messages.rows.values.all { it.showsAsDeleted })
        advanceUntilIdle()
        val req = realtime.sentDeletes.single()
        assertEquals(MsgDelete.SCOPE_EVERYONE, req.scope)
        assertEquals(rows.map { it.messageId }, req.targets)
        assertEquals(listOf("blob-1", "blob-1"), req.blobIds)
        val ct = String(java.util.Base64.getDecoder().decode(req.ciphertext))
        val aad = java.util.Base64.getDecoder().decode(ct.substringAfter("AAD:").substringBefore('#'))
        assertArrayEquals(DeleteAad.encode(req.targets), aad)
        assertTrue(ct.substringAfter('#').endsWith(String(MlsPayload.delete(req.targets))))
        assertTrue(messages.rows.values.all { it.deleted && it.deletedBy == me })
        assertEquals("You deleted this message", lk.codegen.risime.ui.chat.tombstoneText(messages.rows.values.first(), me))
        assertTrue(dao.outbox.isEmpty())
    }

    @Test fun moreThan100AreSplitIntoRequestsOfAtMost100() = runTest {
        val e = engine()
        val rows = sent(101)
        e.deleteForEveryone(dm, rows.map { it.clientMsgId })
        advanceUntilIdle()
        assertEquals(listOf(100, 1), realtime.sentDeletes.map { it.targets.size })
        assertEquals(2, realtime.sentDeletes.map { it.clientMsgId }.toSet().size)
        assertNull("plaintext DM: no ciphertext", realtime.sentDeletes[0].ciphertext)
    }

    @Test fun aRefusalRestoresTheFailingRowsAndRetriesTheRest() = runTest {
        val e = engine()
        val rows = sent(2)
        val failing = rows[0].messageId!!
        realtime.deleteReplies = { m ->
            if (failing in m.targets) PushResult.Rejected.withBody(
                "too_old", ProtocolJson.parseToJsonElement("""{"reason":"too_old","failures":[{"target":"$failing","reason":"too_old"}]}""").jsonObject,
            ) else PushResult.Ok(MsgDeleteReply("d", m.conversationId, "2026-10-06T09:00:00.000Z", m.targets))
        }
        val notices = mutableListOf<ChatEngine.DeleteNotice>()
        backgroundScope.launchCollect(e, notices)
        e.deleteForEveryone(dm, rows.map { it.clientMsgId })
        advanceUntilIdle()
        assertEquals(2, realtime.sentDeletes.size)
        val r0 = messages.rows["cm0"]!!
        assertFalse(r0.showsAsDeleted)
        assertEquals("msg 0", r0.body)
        assertEquals("SENT", r0.status) // ticks survive the restore
        assertTrue(messages.rows["cm1"]!!.deleted)
        val n = notices.single()
        assertEquals(DeleteRules.TOO_OLD_TEXT, n.text)
        assertEquals(listOf("cm0"), n.failedClientMsgIds)
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(e: ChatEngine, out: MutableList<ChatEngine.DeleteNotice>) {
        val self: kotlinx.coroutines.CoroutineScope = this
        self.launch(kotlinx.coroutines.Dispatchers.Unconfined) { e.deleteNotices.collect { out += it } }
    }

    @Test fun pendingNeverPushedIsCancelledPushedIsDeletedAfterSending() = runTest {
        val e = engine()
        messages.insert(MessageEntity("p0", null, dm, me, bob, "never pushed", null, base, "PENDING", true))
        messages.insert(MessageEntity("p1", null, dm, me, bob, "reply lost", null, base + 1, "PENDING", true, sendAttempts = 1))
        realtime.connected = false
        e.deleteForEveryone(dm, listOf("p0", "p1"))
        assertNull(messages.rows["p0"])
        assertEquals(MessageEntity.DELETE_STATE_CANCEL_AFTER_SEND, messages.rows["p1"]!!.deleteState)
        assertTrue(messages.rows["p1"]!!.showsAsDeleted)
        // Back online: the outbox finishes sending it (same client_msg_id), then deletes it for everyone.
        realtime.connected = true
        e.flushOutbox()
        advanceUntilIdle()
        assertEquals(listOf("p1"), realtime.sent.map { it.clientMsgId })
        assertEquals(listOf("mid-p1"), realtime.sentDeletes.single().targets)
        assertTrue(messages.rows["p1"]!!.deleted)
    }

    @Test fun sendAttemptsAreCountedBeforeEveryPush() = runTest {
        val e = engine()
        realtime.sendReplies = { PushResult.Unavailable }
        e.sendText(bob, "hi")
        advanceUntilIdle()
        assertEquals(1, messages.rows.values.single().sendAttempts)
    }

    @Test fun deleteForMeRemovesTheRowAndAReplayCantBringItBack() = runTest {
        val e = engine()
        val rows = sent(1, from = bob)
        e.deleteForMe(dm, rows.map { it.clientMsgId })
        advanceUntilIdle()
        assertTrue(messages.rows.isEmpty())
        assertEquals(MsgDelete.SCOPE_ME, dao.deletedIds[rows[0].messageId]!!.scope)
        val req = realtime.sentDeletes.single()
        assertEquals(MsgDelete.SCOPE_ME, req.scope)
        assertNull(req.ciphertext)
        // The same message replayed (re-login before the server removed it): never stored again.
        e.onEvents(listOf(ProtocolJson.decodeFromString<Event>(
            """{"event_id":"${rows[0].messageId}","kind":"message","data":{"message_id":"${rows[0].messageId}","client_msg_id":"x","conversation_id":"$dm","from":"$bob","to":"$me","body":"msg 0","server_ts":"${iso(base)}"}}""",
        )))
        assertTrue(messages.rows.isEmpty())
    }

    @Test fun clearChatSetsTheWatermarkSendsChatClearAndKeepsPendingDeletesAndPushedSends() = runTest {
        val e = engine()
        sent(3, from = bob)
        messages.insert(MessageEntity("p1", null, dm, me, bob, "pushed", null, base + 10, "PENDING", true, sendAttempts = 1))
        messages.insert(MessageEntity("p0", null, dm, me, bob, "draft", null, base + 11, "PENDING", true))
        val cursor = tu(base + 50_000)
        sync.last = cursor
        // A pending delete for everyone (from before) stays.
        dao.queue(DeleteOutboxEntity("dx", dm, MsgDelete.SCOPE_EVERYONE, "[\"${tu(base)}\"]", "[]", DeleteOutboxEntity.QUEUED, createdAt = base))
        realtime.connected = false
        e.clearChat(dm, hide = false)
        assertEquals(listOf("p1"), messages.rows.keys.toList())
        assertEquals(TimeUuid.ticks(cursor), dao.chatStates.value[dm]!!.clearedUpto)
        assertFalse(dao.chatStates.value[dm]!!.hidden)
        assertNotNull(dao.outbox["dx"])
        realtime.connected = true
        e.flushDeletes()
        assertEquals(cursor, realtime.clears.single().upto)
        // A re-login / parked replay of an old message stays gone; a newer one shows.
        val old = tu(base, 1)
        e.onEvents(listOf(ProtocolJson.decodeFromString<Event>(
            """{"event_id":"$old","kind":"message","data":{"message_id":"$old","client_msg_id":"x1","conversation_id":"$dm","from":"$bob","to":"$me","body":"old","server_ts":"${iso(base)}"}}""",
        )))
        assertNull(messages.rows["x1"])
    }

    @Test fun deleteChatHidesItUntilANewMessage() = runTest {
        val e = engine()
        sent(1, from = bob)
        sync.last = tu(base + 1000)
        e.clearChat(dm, hide = true)
        assertTrue(dao.chatStates.value[dm]!!.hidden)
        assertTrue(hideDeletedChatsFor(dm))
        val fresh = tu(base + 2000)
        e.onEvents(listOf(ProtocolJson.decodeFromString<Event>(
            """{"event_id":"$fresh","kind":"message","data":{"message_id":"$fresh","client_msg_id":"n1","conversation_id":"$dm","from":"$bob","to":"$me","body":"new","server_ts":"${iso(base + 2000)}"}}""",
        )))
        assertFalse(dao.chatStates.value[dm]!!.hidden)
        assertFalse(hideDeletedChatsFor(dm))
    }

    private fun hideDeletedChatsFor(conv: String): Boolean {
        val row = lk.codegen.risime.ui.chats.ChatRow(bob, "Bob", "", true, null)
        return lk.codegen.risime.ui.chats.hideDeletedChats(listOf(row), dao.chatStates.value.values.toList(), me).isEmpty()
    }

    @Test fun staleEpochReEncryptsWithTheSameClientMsgId() = runTest {
        mls.groups[dm] = GroupRef(dm, 1, 1)
        val e = engine()
        val rows = sent(1)
        var first = true
        realtime.deleteReplies = { m ->
            if (first) { first = false; PushResult.Rejected("stale_epoch") } else PushResult.Ok(MsgDeleteReply("d", m.conversationId, "2026-10-06T09:00:00.000Z", m.targets))
        }
        e.deleteForEveryone(dm, rows.map { it.clientMsgId })
        advanceUntilIdle()
        assertEquals(listOf(dm), catchUps)
        assertEquals(1, realtime.sentDeletes.map { it.clientMsgId }.toSet().size)
        assertEquals(2, realtime.sentDeletes.size)
        assertTrue(messages.rows["cm0"]!!.deleted)
    }

    @Test fun eligibilityHidesEveryoneForOldOrOthersMessages() {
        val mine = MessageEntity("a", tu(base), dm, me, bob, "x", iso(base), base, "SENT", true)
        val theirs = mine.copy(clientMsgId = "b", messageId = tu(base, 1), from = bob, outgoing = false)
        val d1 = lk.codegen.risime.ui.chat.deleteDialogFor(listOf(mine), me, false, false, true, base + 1000, 0)!!
        assertTrue(d1.canEveryone && !d1.oldAppsHint)
        assertFalse(lk.codegen.risime.ui.chat.deleteDialogFor(listOf(mine, theirs), me, false, false, true, base, 0)!!.canEveryone)
        assertFalse(lk.codegen.risime.ui.chat.deleteDialogFor(listOf(mine), me, false, false, true, base + DeleteRules.WINDOW_MS, 0)!!.canEveryone)
        assertTrue("not deletes_ready: the hint", lk.codegen.risime.ui.chat.deleteDialogFor(listOf(mine), me, false, false, false, base, 0)!!.oldAppsHint)
        assertFalse(lk.codegen.risime.ui.chat.deleteDialogFor(listOf(mine.copy(kind = MessageEntity.KIND_DELETED)), me, false, false, true, base, 0)!!.canEveryone)
    }
}
