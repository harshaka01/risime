package lk.codegen.risime.crypto

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeGroupDao
import lk.codegen.risime.data.FakeGroupOpDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeReactionDao
import lk.codegen.risime.data.FakeRealtime
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.deletes.DeleteAad
import lk.codegen.risime.data.deletes.FakeDeleteDao
import lk.codegen.risime.data.deletes.iso
import lk.codegen.risime.data.deletes.tu
import lk.codegen.risime.data.groups.GroupStore
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.net.MsgDelete
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** §15 with the REAL core: canonical AAD equals the core's, sender_is_admin at the epoch, binding checks. */
class RealDeleteTest {
    private val aU = "aaaa0000-0000-4000-8000-00000000000a"
    private val bU = "bbbb0000-0000-4000-8000-00000000000b"
    private val cU = "cccc0000-0000-4000-8000-00000000000c"
    private val conv = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val b64 = Base64.getEncoder()
    private lateinit var a: RealMls.Device
    private lateinit var b: RealMls.Device
    private lateinit var c: RealMls.Device
    private val base = 1_791_000_000_000L

    @Before fun setUp() {
        RealMls.assumeHostLibrary()
        a = RealMls.device(aU, "a-phone")
        b = RealMls.device(bU, "b-phone")
        c = RealMls.device(cU, "c-phone")
    }

    @After fun tearDown() {
        if (::a.isInitialized) listOf(a, b, c).forEach { it.close() }
    }

    @Test fun kotlinAadMatchesTheCore() {
        val ids = listOf(tu(base, 3), tu(base, 1), tu(base + 5, 0).uppercase())
        assertArrayEquals(deleteAadEncode(ids), DeleteAad.encode(ids))
        assertEquals(deleteAadDecode(DeleteAad.encode(ids)), DeleteAad.decode(DeleteAad.encode(ids)))
    }

    private inner class App(val dev: RealMls.Device, scope: TestScope) {
        val messages = FakeMessageDao()
        val reactions = FakeReactionDao()
        val dao = FakeDeleteDao(messages, reactions)
        val realtime = FakeRealtime()
        val chat = ChatEngine(
            messages = messages, sync = FakeSyncDao(),
            tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
            scope = scope, realtime = { realtime }, meId = { dev.ref.userId }, clock = { base },
            mls = MlsPipeline({ dev.engine }, FakeMlsPendingDao()), mlsEngine = { dev.engine }, reactionsDao = reactions,
            groupsEnabled = { true }, groups = GroupStore(FakeGroupDao(), FakeGroupOpDao(), messages, { dev.ref.deviceId }, { dev.engine.groupMeta(it) }),
            deletes = dao,
        )
    }

    private fun message(from: RealMls.Device, mid: String, text: String) = Event(mid, "message", buildJsonObject {
        val g = from.engine.group(conv)!!
        put("message_id", mid); put("client_msg_id", "c-$mid"); put("conversation_id", conv)
        put("from", from.ref.userId); put("from_device", from.ref.deviceId)
        put("ciphertext", b64.encodeToString(from.transaction { from.engine.encrypt(conv, MlsPayload.text(text)) }))
        put("generation", g.generation); put("epoch", g.epoch); put("server_ts", iso(base))
    })

    private fun deleteEvent(from: RealMls.Device, req: MsgDelete, did: String, targetsFrom: String) = Event(did, "delete", buildJsonObject {
        put("message_id", did); put("client_msg_id", req.clientMsgId); put("conversation_id", conv)
        put("from", from.ref.userId); put("from_device", from.ref.deviceId)
        put("targets", buildJsonArray { req.targets.forEach { t -> add(buildJsonObject { put("message_id", t); put("from", targetsFrom); put("server_ts", iso(base)) }) } })
        put("ciphertext", req.ciphertext); put("generation", req.generation); put("epoch", req.epoch); put("server_ts", iso(base + 1000))
    })

    @Test fun adminDeleteAndOwnDeleteThroughTheRealCore() = runTest {
        val create = a.transaction { a.engine.createGroupWithMeta(conv, 1, listOf(b.keyPackage(), c.keyPackage()), GroupMeta(name = "P", admins = listOf(aU))) }
        a.transaction { a.engine.commitAccepted(conv) }
        b.transaction { b.engine.joinFromWelcome(conv, 1, create.welcome!!) }
        c.transaction { c.engine.joinFromWelcome(conv, 1, create.welcome!!) }
        val appA = App(a, this)
        val appC = App(c, this)
        val m1 = tu(base, 1)
        val m2 = tu(base, 2)
        val e1 = message(b, m1, "one")
        val e2 = message(b, m2, "two")
        appA.chat.onEvents(listOf(e1, e2))
        appC.chat.onEvents(listOf(e1, e2))

        // A (admin) deletes B's m1 through its outbox: AAD-bound PrivateMessage.
        appA.chat.deleteForEveryone(conv, listOf("c-$m1"))
        advanceUntilIdle()
        val req = appA.realtime.sentDeletes.single()
        assertEquals(listOf(m1), req.targets)
        appC.chat.onEvents(listOf(deleteEvent(a, req, tu(base + 1000), bU)))
        val r1 = appC.messages.rows["c-$m1"]!!
        assertTrue(r1.deleted && r1.deletedByAdmin)
        assertEquals(aU, r1.deletedBy)

        // A delete whose event names more targets than the authenticated AAD: dropped whole (binding).
        appA.chat.deleteForEveryone(conv, listOf("c-$m2"))
        advanceUntilIdle()
        val req2 = appA.realtime.sentDeletes.last()
        val bad = deleteEvent(a, req2.copy(targets = listOf(m1, m2)), tu(base + 3000), bU)
        appC.chat.onEvents(listOf(bad))
        assertEquals("two", appC.messages.rows["c-$m2"]!!.body)
    }

    @Test fun aNonAdminCannotDeleteAnotherMembersMessage() = runTest {
        val create = a.transaction { a.engine.createGroupWithMeta(conv, 1, listOf(b.keyPackage(), c.keyPackage()), GroupMeta(name = "P", admins = listOf(aU))) }
        a.transaction { a.engine.commitAccepted(conv) }
        b.transaction { b.engine.joinFromWelcome(conv, 1, create.welcome!!) }
        c.transaction { c.engine.joinFromWelcome(conv, 1, create.welcome!!) }
        val appB = App(b, this)
        val appC = App(c, this)
        val m = tu(base, 1)
        val e = message(a, m, "admin's")
        appB.chat.onEvents(listOf(e))
        appC.chat.onEvents(listOf(e))
        appB.chat.deleteForEveryone(conv, listOf("c-$m"))
        advanceUntilIdle()
        val req = appB.realtime.sentDeletes.single()
        appC.chat.onEvents(listOf(deleteEvent(b, req, tu(base + 1000), aU)))
        assertEquals("admin's", appC.messages.rows["c-$m"]!!.body)
    }
}
