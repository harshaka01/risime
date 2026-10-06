package lk.codegen.risime.crypto

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.BehaviourLog
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeBehaviourDao
import lk.codegen.risime.data.FakeGroupDao
import lk.codegen.risime.data.FakeGroupOpDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeRealtime
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.groups.GroupStore
import lk.codegen.risime.data.mls.CommitOutcome
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.MlsDecryptException
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.data.mls.PendingCommit
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.GroupMeta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** §12 groups through the app's engine and ChatEngine with the REAL MLS core (host build via JNA). */
class RealGroupTest {
    private val aU = "aaaa0000-0000-4000-8000-00000000000a"
    private val bU = "bbbb0000-0000-4000-8000-00000000000b"
    private val cU = "cccc0000-0000-4000-8000-00000000000c"
    private val conv = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val b64 = Base64.getEncoder()
    private lateinit var a: RealMls.Device
    private lateinit var b: RealMls.Device
    private lateinit var c: RealMls.Device

    @Before fun setUp() {
        RealMls.assumeHostLibrary()
        a = RealMls.device(aU, "a-phone")
        b = RealMls.device(bU, "b-phone")
        c = RealMls.device(cU, "c-phone")
    }

    @After fun tearDown() {
        if (::a.isInitialized) listOf(a, b, c).forEach { it.close() }
    }

    private inner class App(val dev: RealMls.Device, scope: TestScope) {
        val messages = FakeMessageDao()
        val groups = FakeGroupDao()
        val realtime = FakeRealtime()
        val chat = ChatEngine(
            messages = messages, sync = FakeSyncDao(),
            tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
            scope = scope, realtime = { realtime }, meId = { dev.ref.userId },
            behaviour = BehaviourLog(FakeBehaviourDao(), { "s" }, { 0L }),
            mls = MlsPipeline({ dev.engine }, FakeMlsPendingDao()), mlsEngine = { dev.engine },
            groupsEnabled = { dev.engine.groupsSupported },
            groups = GroupStore(groups, FakeGroupOpDao(), messages, { dev.ref.deviceId }, { dev.engine.groupMeta(it) }),
        )
    }

    private var n = 0
    private fun welcome(pc: PendingCommit, to: List<String>) = Event("ev-${++n}", "mls_welcome", buildJsonObject {
        put("conversation_id", conv); put("generation", pc.generation); put("epoch", pc.epoch + 1); put("welcome", b64.encodeToString(pc.welcome!!))
        put("to_devices", buildJsonArray { to.forEach { add(JsonPrimitive(it)) } })
    })

    private fun commit(pc: PendingCommit, from: String) = Event("ev-${++n}", "mls_commit", buildJsonObject {
        put("conversation_id", conv); put("generation", pc.generation); put("epoch", pc.epoch); put("commit", b64.encodeToString(pc.commit)); put("from_device", from)
    })

    private fun message(from: RealMls.Device, sent: lk.codegen.risime.net.MsgSendGroup) = Event("ev-${++n}", "message", buildJsonObject {
        put("message_id", "m-${sent.clientMsgId}"); put("client_msg_id", sent.clientMsgId); put("conversation_id", conv)
        put("from", from.ref.userId); put("from_device", from.ref.deviceId); put("ciphertext", sent.ciphertext)
        put("generation", sent.generation); put("epoch", sent.epoch); put("server_ts", "2026-10-06T08:15:30.456Z")
    })

    @Test fun createJoinMessageRenameRemoveAndPolicy() = runTest {
        assertTrue(a.engine.groupsSupported)
        val appA = App(a, this)
        val appB = App(b, this)
        val appC = App(c, this)

        // A creates the group (epoch 0 with group_meta), merged only on the "200".
        val meta = GroupMeta(name = "Pilot team", admins = listOf(aU))
        val create = a.transaction { a.engine.createGroupWithMeta(conv, 1, listOf(b.keyPackage(), c.keyPackage()), meta) }
        assertEquals(0L, create.epoch)
        assertEquals(0L, a.engine.group(conv)!!.epoch) // pending: not merged before the 200
        a.transaction { a.engine.commitAccepted(conv) }
        assertEquals(1L, a.engine.group(conv)!!.epoch)
        assertEquals(meta, a.engine.groupMeta(conv))

        // B and C join from the Welcome through ChatEngine; the row gets the decrypted name.
        appB.chat.onEvents(listOf(welcome(create, listOf("b-phone", "c-phone"))))
        appC.chat.onEvents(listOf(welcome(create, listOf("b-phone", "c-phone"))))
        assertEquals("Pilot team", b.engine.groupMeta(conv)!!.name)
        assertEquals("Pilot team", appB.groups.groups[conv]!!.name)

        // A sends through its outbox (group msg:send); B and C decrypt it with the sender's name kept.
        appA.chat.sendText(conv, "hello group")
        advanceUntilIdle()
        val sent = appA.realtime.sentGroup.single()
        appB.chat.onEvents(listOf(message(a, sent)))
        appC.chat.onEvents(listOf(message(a, sent)))
        assertEquals(listOf("hello group" to aU), appB.messages.rows.values.map { it.body to it.from })
        assertEquals(1, appC.messages.rows.size)

        // A non-admin can't rename (admin policy in the core).
        try {
            b.transaction { b.engine.updateGroupMeta(conv, meta.copy(name = "Mine")) }
            fail("non-admin rename must be refused")
        } catch (e: Exception) {
            assertTrue(e.toString(), e.toString().contains("Policy", ignoreCase = true))
        }

        // A renames; B processes the (PrivateMessage) commit and sees the new name.
        val rename = a.transaction { a.engine.updateGroupMeta(conv, meta.copy(name = "Rise core")) }
        assertTrue(rename.metaChanged)
        a.transaction { a.engine.commitAccepted(conv) }
        appB.chat.onEvents(listOf(commit(rename, "a-phone")))
        appC.chat.onEvents(listOf(commit(rename, "a-phone")))
        assertEquals("Rise core", b.engine.groupMeta(conv)!!.name)
        assertEquals("Rise core", appB.groups.groups[conv]!!.name)

        // A removes C (an admin committing a removal); C sees removed_self and is locked out.
        val remove = a.transaction { a.engine.removeGroupUsers(conv, listOf(cU)) }
        assertEquals(listOf("c-phone"), remove.removed.map { it.deviceId })
        a.transaction { a.engine.commitAccepted(conv) }
        appB.chat.onEvents(listOf(commit(remove, "a-phone")))
        appC.chat.onEvents(listOf(commit(remove, "a-phone")))
        assertNull(c.engine.group(conv))
        assertEquals("removed", appC.groups.groups[conv]!!.state)
        appA.chat.sendText(conv, "after removal")
        advanceUntilIdle()
        val later = appA.realtime.sentGroup.last()
        appB.chat.onEvents(listOf(message(a, later)))
        assertEquals(2, appB.messages.rows.size)
        try {
            c.transaction { c.engine.decrypt(conv, 1, Base64.getDecoder().decode(later.ciphertext)) }
            fail("removed device must not decrypt")
        } catch (_: MlsDecryptException) {
        }
    }

    @Test fun ownCommitAcceptedButNotMergedIsRecoveredFromTheLog() {
        val create = a.transaction { a.engine.createGroupWithMeta(conv, 1, listOf(b.keyPackage()), GroupMeta(name = "x", admins = listOf(aU))) }
        a.transaction { a.engine.commitAccepted(conv) }
        b.transaction { b.engine.joinFromWelcome(conv, 1, create.welcome!!) }
        // A's rename reached the server (200) but the process died before commitAccepted.
        val rename = a.transaction { a.engine.updateGroupMeta(conv, GroupMeta(name = "y", admins = listOf(aU))) }
        val outcome = a.transaction { a.engine.processCommit(conv, 1, rename.commit) }
        assertTrue(outcome.toString(), outcome is CommitOutcome.Applied)
        assertEquals(2L, a.engine.group(conv)!!.epoch)
        assertEquals("y", a.engine.groupMeta(conv)!!.name)
        a.transaction { a.engine.commitAccepted(conv) } // the late 200 is harmless
        assertTrue(b.transaction { b.engine.processCommit(conv, 1, rename.commit) } is CommitOutcome.Applied)
        assertEquals("y", b.engine.groupMeta(conv)!!.name)
    }
}
