package lk.codegen.risime.data

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.groups.GroupStore
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.GroupRef
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.realtime.PushResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** ChatEngine on `grp:` conversations (§12): receive, send, events, receipts and blob refs. */
class GroupChatEngineTest {
    private val me = "u-me"
    private val kamal = "u-kamal"
    private val conv = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val messages = FakeMessageDao()
    private val sync = FakeSyncDao()
    private val realtime = FakeRealtime()
    private val mls = FakeMlsEngine(me, "dev-me")
    private val pending = FakeMlsPendingDao()
    private val groupDao = FakeGroupDao()
    private val ops = FakeGroupOpDao()
    private val blobStore = mutableMapOf<String, ByteArray>()
    private var blobsDown = false
    private val unrecoverable = mutableListOf<String>()
    private val parkedAhead = mutableListOf<String>()
    private var ids = 0
    private var now = 1_000L

    private fun TestScope.engine(groupsOn: Boolean = true) = ChatEngine(
        messages = messages, sync = sync,
        tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
        scope = this, realtime = { realtime }, meId = { me }, clock = { now++ }, newClientMsgId = { "c${++ids}" },
        mls = MlsPipeline({ mls }, pending, onParkedAhead = { parkedAhead += it }),
        mlsEngine = { mls },
        groupsEnabled = { groupsOn },
        groups = GroupStore(groupDao, ops, messages, { "dev-me" }, { mls.groupMeta(it) }, { now++ }),
        blobs = { ref ->
            when {
                blobsDown -> BlobFetch.Transient
                else -> blobStore[ref.blobId]?.let { BlobFetch.Ok(it) } ?: BlobFetch.Gone
            }
        },
        onUnrecoverable = { unrecoverable += it },
    )

    private fun event(json: String) = ProtocolJson.decodeFromString<Event>(json)

    private fun groupMessage(eid: String, mid: String, from: String, dev: String, text: String, epoch: Long = 1) = event(
        """{"event_id":"$eid","kind":"message","data":{"message_id":"$mid","client_msg_id":"cm-$mid","conversation_id":"$conv",
        "from":"$from","from_device":"$dev","ciphertext":"${FakeMlsEngine.ciphertext(1, epoch, from, dev, String(MlsPayload.text(text)))}",
        "generation":1,"epoch":$epoch,"server_ts":"2026-10-06T08:15:30.456Z"}}""",
    )

    private fun joinGroup() {
        mls.groups[conv] = GroupRef(conv, 1, 1)
        mls.metas[conv] = GroupMeta(name = "Pilot team", admins = listOf(kamal))
    }

    @Test fun groupMessagesDecryptWithoutToAndKeepTheSender() = runTest {
        val e = engine()
        joinGroup()
        e.onEvents(listOf(groupMessage("e1", "m1", kamal, "dev-k", "hello group")))
        val row = messages.rows.values.single()
        assertEquals("hello group", row.body)
        assertEquals(kamal, row.from)
        assertEquals(conv, row.to) // to_id holds the conversation id for groups
        assertEquals(conv, row.conversationId)
        assertEquals(listOf("m1"), realtime.acks.single().first) // group messages are acked as usual
    }

    @Test fun groupEventsAreSkippedByAPreGroupsAppButStillAdvanceTheCursor() = runTest {
        val e = engine(groupsOn = false)
        joinGroup()
        e.onEvents(listOf(groupMessage("e1", "m1", kamal, "dev-k", "hi")))
        assertTrue(messages.rows.isEmpty())
        assertEquals("e1", sync.last)
    }

    @Test fun groupEventCreatesTheRowAndAWelcomeJoinsWithTheMetaName() = runTest {
        val e = engine()
        e.onEvents(
            listOf(
                event("""{"event_id":"w1","kind":"mls_welcome","data":{"conversation_id":"$conv","generation":1,"epoch":1,"welcome":"${FakeMlsEngine.b64("welcome|1")}","to_devices":["dev-me"]}}"""),
            ),
        )
        assertEquals(1L, mls.group(conv)!!.epoch)
        mls.metas[conv] = GroupMeta(name = "Pilot team", admins = listOf(kamal))
        e.onEvents(listOf(event(javaClass.classLoader!!.getResource("contract/v1/examples/event_group_created.json")!!.readText())))
        val g = groupDao.groups[conv]!!
        assertEquals("Pilot team", g.name)
        assertEquals(2, groupDao.members.size)
        assertTrue(messages.rows.values.single().system)
    }

    @Test fun sendGoesToTheGroupEncryptedAndWaitsWithoutBlockingDms() = runTest {
        val e = engine()
        val waiting = e.sendText(conv, "before the welcome")!!
        val peer = "u-peer"
        val dm = e.sendText(peer, "dm still flows")!!
        advanceUntilIdle()
        assertEquals("PENDING", messages.rows[waiting]!!.status)
        assertEquals("SENT", messages.rows[dm]!!.status)
        assertEquals(dmConversationId(me, peer), messages.rows[dm]!!.conversationId)

        joinGroup()
        e.flushOutbox()
        val sent = realtime.sentGroup.single()
        assertEquals(conv, sent.conversationId)
        assertEquals(1L, sent.epoch)
        assertTrue(String(Base64.getDecoder().decode(sent.ciphertext)).contains("before the welcome"))
        assertEquals("SENT", messages.rows[waiting]!!.status)
        assertEquals("gid-$waiting", messages.rows[waiting]!!.messageId)
    }

    @Test fun notMemberFailsTheMessage() = runTest {
        val e = engine()
        joinGroup()
        realtime.groupReplies = { PushResult.Rejected("not_member") }
        val id = e.sendText(conv, "after removal")!!
        advanceUntilIdle()
        assertEquals("FAILED", messages.rows[id]!!.status)
        assertEquals("not_member", messages.rows[id]!!.failReason)
    }

    @Test fun groupReceiptTicksAndGroupOpQueue() = runTest {
        val e = engine()
        joinGroup()
        val id = e.sendText(conv, "hi")!!
        advanceUntilIdle()
        e.onEvents(
            listOf(
                event(
                    """{"event_id":"r1","kind":"group_receipt","data":{"conversation_id":"$conv","message_id":"gid-$id","client_msg_id":"$id",
                    "delivered":2,"read":2,"of":2,"all_delivered":true,"all_read":true,"at":"2026-10-06T08:15:40.120Z"}}""",
                ),
                event(
                    """{"event_id":"o1","kind":"group_op","data":{"group_id":"$conv","generation":1,"op":{"op_id":"op1","type":"remove","actor":"$me",
                    "user_ids":["$kamal"],"role":null,"added":[],"removed":[],"committer":{"user_id":"$me","device_id":"dev-me"}}}}""",
                ),
            ),
        )
        assertEquals("READ", messages.rows[id]!!.status)
        assertEquals("op1", ops.rows.values.single().opId)
    }

    @Test fun referencedCommitIsFetchedVerifiedAndApplied() = runTest {
        val e = engine()
        joinGroup()
        val bytes = "commit|big".toByteArray()
        blobStore["b1"] = bytes
        val sha = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
        assertTrue(lk.codegen.risime.data.groups.blobMatches(bytes, BlobRef("b1", bytes.size.toLong(), sha)))
        val ref = """{"event_id":"x1","kind":"mls_commit","data":{"conversation_id":"$conv","generation":1,"epoch":1,"commit":null,
            "commit_ref":{"blob_id":"b1","size":${bytes.size},"sha256":"$sha"},"from_device":"dev-k"}}"""

        blobsDown = true
        e.onEvents(listOf(event(ref)))
        assertNull(sync.last) // transient: the cursor doesn't move, the next sync redelivers
        assertEquals(1L, mls.group(conv)!!.epoch)

        blobsDown = false
        e.onEvents(listOf(event(ref)))
        assertEquals(2L, mls.group(conv)!!.epoch)
        assertEquals(listOf("big"), mls.processed)
        assertEquals("x1", sync.last)
    }

    @Test fun goneBlobOrRejectedCommitIsUnrecoverableAndAheadCommitAsksForCatchUp() = runTest {
        val e = engine()
        joinGroup()
        e.onEvents(
            listOf(
                event(
                    """{"event_id":"x1","kind":"mls_commit","data":{"conversation_id":"$conv","generation":1,"epoch":1,"commit":null,
                    "commit_ref":{"blob_id":"missing","size":3,"sha256":"x"},"from_device":"dev-k"}}""",
                ),
                event("""{"event_id":"x2","kind":"mls_commit","data":{"conversation_id":"$conv","generation":1,"epoch":1,"commit":"${FakeMlsEngine.b64("commit|bad")}","from_device":"dev-k"}}"""),
                event("""{"event_id":"x3","kind":"mls_commit","data":{"conversation_id":"$conv","generation":1,"epoch":5,"commit":"${FakeMlsEngine.b64("commit|later")}","from_device":"dev-k"}}"""),
            ),
        )
        assertEquals(listOf(conv, conv), unrecoverable)
        assertEquals(listOf(conv), parkedAhead)
        assertEquals(1, pending.rows.size)
    }
}
